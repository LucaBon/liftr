package com.liftr.domain.usecase

import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.ExerciseThresholds
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.exercise.strategy.ReboundFrames
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.AnthropometricData
import com.liftr.domain.model.CalibrationData
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseResult
import com.liftr.domain.model.SetSummary
import com.liftr.domain.value.Kilograms
import com.liftr.domain.vision.VideoPoseSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A 240 fps video of rebound jumps through the video path: pass 1 finds the
 * jumps on ~30 fps, pass 2 adds every frame around takeoffs and landings, and
 * the summary carries each jump's contact time and RSI.
 */
class AnalyzeVideoReboundTest {

    private val userHeight = 1.75f
    private val logger = mockk<Logger>(relaxed = true)
    private val calibration = CalibrationData(
        500f, userHeight, 0.45f, AnthropometricData.getDefaultRatios(userHeight, 75f)
    )

    private fun analyze(poses: List<PoseResult>, type: ExerciseType = ExerciseType.VERTICAL_JUMP): SetSummary = runBlocking {
        val strategy = JumpStrategy(type, logger)
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }
        val useCase = AnalyzeVideoUseCase(
            mockk(relaxed = true), factory, AnalyzeRepUseCase(factory, logger), mockk(relaxed = true), logger
        )
        // Like the decoder: every frame is emitted, a pose only where asked for.
        val source = object : VideoPoseSource {
            override val totalFrames = poses.size
            override fun poses(include: (Long) -> Boolean): Flow<VideoPoseSource.Frame> = flow {
                poses.forEach { emit(VideoPoseSource.Frame(it.timestamp, it.takeIf { _ -> include(it.timestamp) })) }
            }
            override fun close() = Unit
        }
        useCase.invoke(
            AnalyzeVideoUseCase.Params(
                videoFile = File("rebounds.mp4"),
                exerciseType = type,
                weight = Kilograms(0f),
                targetReps = 6,
                calibrationData = calibration
            ),
            source,
            onProgress = {}
        ).getOrThrow()
    }

    private val flights = listOf(0.45, 0.42, 0.40, 0.38, 0.36, 0.40)
    private val contacts = listOf(0.25, 0.16, 0.30, 0.22, 0.18)

    @Test
    fun `rebound contact times at 240 fps come out within 2 ms, with RSI and its best-five mean`() {
        val summary = analyze(ReboundFrames.rebounds(240, flights, contacts))

        // The timing came from the every-frame windows, not the ~30 fps pass.
        verify { logger.i(any(), match { it.startsWith("Dense pass") }) }

        assertEquals(flights.size, summary.repJumpHeights.size)
        assertEquals(flights.size, summary.repContactTimesMs.size)
        assertEquals(flights.size, summary.repReactiveStrengthIndices.size)
        assertNull(summary.repContactTimesMs.first())
        assertNull(summary.repReactiveStrengthIndices.first())
        for (i in 1 until flights.size) {
            assertEquals("contact $i", contacts[i - 1] * 1000, summary.repContactTimesMs[i]!!.toDouble(), 2.0)
            val trueRsi = flights[i] / contacts[i - 1]
            assertEquals("RSI $i", trueRsi, summary.repReactiveStrengthIndices[i]!!.toDouble(), trueRsi * 0.03)
        }
        val trueBestFive = (1 until flights.size).map { flights[it] / contacts[it - 1] }.sortedDescending().take(5).average()
        assertEquals(trueBestFive, summary.bestFiveMeanRsi!!.toDouble(), trueBestFive * 0.03)
    }

    @Test
    fun `pogo contacts shorter than a dense window are timed from the every-frame windows`() {
        // ~4 coarse frames on the ground: the landing and next takeoff windows overlap.
        val pogoFlights = listOf(0.30, 0.30, 0.28, 0.30)
        val pogoContacts = listOf(0.12, 0.14, 0.13)
        val summary = analyze(ReboundFrames.rebounds(240, pogoFlights, pogoContacts, contactDipM = 0.02))

        assertEquals(pogoFlights.size, summary.repContactTimesMs.size)
        assertNull(summary.repContactTimesMs.first())
        // Within a frame: what the strategy gets from every frame too (short flights
        // leave a few ms of timing-level clip uncorrected), so the windows lose nothing.
        for (i in 1 until pogoFlights.size) {
            assertEquals("contact $i", pogoContacts[i - 1] * 1000, summary.repContactTimesMs[i]!!.toDouble(), 1000.0 / 240)
        }
        // Four jumps, three rebounds: the set's RSI is the best single one.
        assertNull(summary.bestFiveMeanRsi)
        assertEquals(summary.bestRsi, summary.setRsi)
    }

    @Test
    fun `a rest between jumps gives that jump no contact time through the video path`() {
        val rest = ExerciseThresholds.Jump.STILL_MS / 1000.0 + 0.4
        val summary = analyze(
            ReboundFrames.rebounds(240, listOf(0.45, 0.42, 0.40), listOf(0.2, rest), contactDipM = 0.0)
        )

        assertEquals(3, summary.repContactTimesMs.size)
        assertEquals(200.0, summary.repContactTimesMs[1]!!.toDouble(), 2.0)
        assertNull(summary.repContactTimesMs[2])
    }

    @Test
    fun `approach jumps carry no contact time through the video path`() {
        val summary = analyze(
            ReboundFrames.rebounds(240, listOf(0.5, 0.5, 0.5), listOf(0.3, 0.3)), ExerciseType.APPROACH_JUMP
        )

        assertTrue(summary.repJumpHeights.isNotEmpty())
        assertTrue(summary.repContactTimesMs.all { it == null })
        assertNull(summary.bestRsi)
    }
}
