package com.liftr.vision.endcap

import android.content.Context
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.vision.EndcapDetection
import com.liftr.domain.vision.EndcapDetector
import com.liftr.domain.vision.EndcapModelCodec
import com.liftr.vision.camera.LensDistortionCorrector
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * [EndcapDetector] running the bundled YOLO endcap model with the LiteRT
 * (TFLite) interpreter. Pre- and post-processing are in [EndcapModelCodec];
 * the detected centers get the same lens correction as the pose landmarks, so
 * both share one coordinate system.
 *
 * At load it times the model on the GPU delegate (when the device is on the
 * GPU compatibility list) and on the CPU, and keeps the faster one. All model
 * calls run on one dedicated thread, since a GPU delegate may be bound to the
 * thread that created it while the caller's coroutine can hop threads.
 *
 * If the model or the runtime fails to load, [isAvailable] is false and
 * [detect] returns nothing, so video analysis carries on with the pose alone.
 */
class TfliteEndcapDetector @Inject constructor(
    @ApplicationContext context: Context,
    private val logger: Logger,
    private val lensDistortionCorrector: LensDistortionCorrector
) : EndcapDetector {

    private val modelThread: ExecutorService =
        Executors.newSingleThreadExecutor { Thread(it, "endcap-detector") }
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val size = EndcapModelCodec.INPUT_SIZE
    private val inputValues = FloatArray(3 * size * size)
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(4 * inputValues.size).order(ByteOrder.nativeOrder())
    private var outputValues = FloatArray(0)
    private var outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var failureLogged = false

    init {
        onModelThread {
            try {
                load(loadModel(context))
            } catch (t: Throwable) {
                logger.w(TAG, "Endcap model unavailable, using the pose only: ${t.message}")
            }
        }
    }

    override fun isAvailable(): Boolean = interpreter != null

    override fun detect(argb: IntArray, width: Int, height: Int): List<EndcapDetection> {
        val model = interpreter ?: return emptyList()
        return try {
            val letterbox = EndcapModelCodec.Letterbox(width, height)
            EndcapModelCodec.fillInput(argb, letterbox, inputValues)
            inputBuffer.rewind()
            inputBuffer.asFloatBuffer().put(inputValues)
            onModelThread { run(model) }
            outputBuffer.rewind()
            outputBuffer.asFloatBuffer().get(outputValues)
            EndcapModelCodec.decode(outputValues, letterbox).map { d ->
                val corrected = lensDistortionCorrector.correctLandmark(
                    PoseKeypoint(d.centerX, d.centerY, 0f, 1f), width, height
                )
                d.copy(centerX = corrected.x, centerY = corrected.y)
            }
        } catch (t: Throwable) {
            if (!failureLogged) {
                failureLogged = true
                logger.e(TAG, "Endcap detection failed", t)
            }
            emptyList()
        }
    }

    override fun close() {
        runCatching {
            onModelThread {
                interpreter?.close()
                gpuDelegate?.close()
            }
        }
        interpreter = null
        gpuDelegate = null
        modelThread.shutdown()
    }

    /** Creates the CPU interpreter, checks the model shapes, then keeps the faster of CPU and GPU. */
    private fun load(model: MappedByteBuffer) {
        val cpu = Interpreter(model, Interpreter.Options().setNumThreads(NUM_THREADS))
        val inputShape = cpu.getInputTensor(0).shape().toList()
        val outputElements = cpu.getOutputTensor(0).numElements()
        if (inputShape != listOf(1, 3, size, size) || outputElements == 0 || outputElements % 5 != 0) {
            logger.e(TAG, "Unexpected model shapes: input $inputShape, output $outputElements values")
            cpu.close()
            return
        }
        outputValues = FloatArray(outputElements)
        outputBuffer = ByteBuffer.allocateDirect(4 * outputElements).order(ByteOrder.nativeOrder())

        val gpu = createGpuInterpreter(model)
        if (gpu == null) {
            interpreter = cpu
            logger.i(TAG, "Endcap model on CPU")
            return
        }
        val (gpuInterpreter, delegate) = gpu
        val gpuMs = try {
            benchmark(gpuInterpreter)
        } catch (t: Throwable) {
            logger.w(TAG, "GPU delegate failed to run, using CPU: ${t.message}")
            null
        }
        val cpuMs = benchmark(cpu)
        if (gpuMs != null && gpuMs < cpuMs) {
            cpu.close()
            interpreter = gpuInterpreter
            gpuDelegate = delegate
        } else {
            gpuInterpreter.close()
            delegate.close()
            interpreter = cpu
        }
        logger.i(
            TAG,
            "Endcap model: GPU ${gpuMs?.let { "%.1f ms".format(it) } ?: "failed"}, CPU ${"%.1f ms".format(cpuMs)} " +
                "per frame -> ${if (gpuDelegate != null) "GPU" else "CPU"}"
        )
    }

    /** An interpreter on the GPU delegate, or null when the device isn't supported or creation fails. */
    private fun createGpuInterpreter(model: MappedByteBuffer): Pair<Interpreter, GpuDelegate>? {
        var delegate: GpuDelegate? = null
        return try {
            val compatibility = CompatibilityList()
            val options = if (compatibility.isDelegateSupportedOnThisDevice) {
                compatibility.bestOptionsForThisDevice
            } else null
            compatibility.close()
            if (options == null) {
                logger.i(TAG, "GPU delegate not supported on this device")
                return null
            }
            delegate = GpuDelegate(options)
            Pair(Interpreter(model, Interpreter.Options().addDelegate(delegate)), delegate)
        } catch (t: Throwable) {
            delegate?.close()
            logger.w(TAG, "GPU delegate unavailable, using CPU: ${t.message}")
            null
        }
    }

    /** Mean inference time in ms on a blank (padding-gray) input, after a warm-up. */
    private fun benchmark(model: Interpreter): Double {
        inputValues.fill(BLANK_VALUE)
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(inputValues)
        repeat(WARMUP_RUNS) { run(model) }
        val start = System.nanoTime()
        repeat(TIMED_RUNS) { run(model) }
        return (System.nanoTime() - start) / 1e6 / TIMED_RUNS
    }

    private fun run(model: Interpreter) {
        inputBuffer.rewind()
        outputBuffer.rewind()
        model.run(inputBuffer, outputBuffer)
    }

    private fun <T> onModelThread(block: () -> T): T = modelThread.submit(Callable(block)).get()

    private fun loadModel(context: Context): MappedByteBuffer =
        context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    companion object {
        private const val TAG = "TfliteEndcapDetector"
        private const val MODEL_ASSET = "endcap_detector.tflite"
        private const val NUM_THREADS = 4
        private const val WARMUP_RUNS = 3
        private const val TIMED_RUNS = 5
        private const val BLANK_VALUE = 114f / 255f
    }
}
