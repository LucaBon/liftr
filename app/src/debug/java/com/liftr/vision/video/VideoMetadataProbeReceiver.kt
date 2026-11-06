package com.liftr.vision.video

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.CamcorderProfile
import android.os.Build
import android.util.Log
import androidx.camera.core.DynamicRange
import androidx.camera.core.impl.CameraInfoInternal
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import java.io.File

/**
 * Debug-only entry point: probes the metadata of video files without running an analysis.
 *
 * adb shell am broadcast -n com.liftr/.vision.video.VideoMetadataProbeReceiver \
 *     --es path <file or dir; relative paths resolve under the app's external files dir>
 * or `--ez highspeed true` to log why CameraX does or doesn't offer high-speed recording.
 * With no `path`, probes every video in the external files dir (recursively).
 */
class VideoMetadataProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("highspeed", false)) {
            val pending = goAsync()
            Thread { try { probeHighSpeed(context) } finally { pending.finish() } }.start()
            return
        }
        val base = context.getExternalFilesDir(null) ?: return
        val arg = intent.getStringExtra("path")
        val target = when {
            arg == null -> base
            arg.startsWith("/") -> File(arg)
            else -> File(base, arg)
        }
        val files = target.walkTopDown().filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }.toList()
        Log.i(VideoMetadataProbe.TAG, "probing ${files.size} file(s) under $target")
        files.forEach { VideoMetadataProbe.probe(it) }
    }

    @SuppressLint("RestrictedApi")
    @OptIn(androidx.camera.video.ExperimentalHighSpeedVideo::class)
    private fun probeHighSpeed(context: Context) {
        val tag = VideoMetadataProbe.TAG
        for (id in 0..1) {
            for ((name, q) in HIGH_SPEED_QUALITIES) {
                val has = runCatching { CamcorderProfile.hasProfile(id, q) }.getOrDefault(false)
                val detail = if (has && Build.VERSION.SDK_INT >= 31) {
                    runCatching {
                        CamcorderProfile.getAll(id.toString(), q)?.videoProfiles?.joinToString {
                            it?.let { v -> "${v.mediaType} ${v.width}x${v.height}@${v.frameRate} hdr=${v.hdrFormat} bits=${v.bitDepth}" } ?: "null"
                        }
                    }.getOrElse { "getAll failed: $it" }
                } else ""
                Log.i(tag, "camera $id $name($q): hasProfile=$has $detail")
            }
        }
        val provider = ProcessCameraProvider.getInstance(context).get()
        provider.availableCameraInfos.forEach { info ->
            val internal = info as? CameraInfoInternal
            val caps = runCatching { Recorder.getHighSpeedVideoCapabilities(info) }
            Log.i(
                tag,
                "cameraX ${internal?.cameraId} facing=${info.lensFacing} isHighSpeedSupported=${internal?.isHighSpeedSupported} " +
                    "dynamicRanges=${internal?.supportedDynamicRanges} highSpeedCaps=${caps.getOrNull()?.let { c -> c.getSupportedQualities(DynamicRange.SDR) }} " +
                    "error=${caps.exceptionOrNull()}",
            )
        }
    }

    private companion object {
        val HIGH_SPEED_QUALITIES = listOf(
            "HIGH_SPEED_LOW" to CamcorderProfile.QUALITY_HIGH_SPEED_LOW,
            "HIGH_SPEED_HIGH" to CamcorderProfile.QUALITY_HIGH_SPEED_HIGH,
            "HIGH_SPEED_720P" to CamcorderProfile.QUALITY_HIGH_SPEED_720P,
            "HIGH_SPEED_1080P" to CamcorderProfile.QUALITY_HIGH_SPEED_1080P,
            "HIGH_SPEED_2160P" to CamcorderProfile.QUALITY_HIGH_SPEED_2160P,
        )
        val VIDEO_EXT = setOf("mp4", "mov", "3gp", "mkv", "webm")
    }
}
