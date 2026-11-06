package com.liftr.domain.usecase

import com.liftr.domain.calculator.ForceVelocityProfileResult
import com.liftr.domain.calculator.ForceVelocityRejection
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.JumpFitCheck
import com.liftr.domain.model.JumpRepRecord
import com.liftr.domain.model.RepJumpData
import com.liftr.domain.model.TimeRange
import com.liftr.domain.repository.AnalyticsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The jump force-velocity profile in the analytics summary. */
class GetAnalyticsSummaryUseCaseTest {

    private val repository = mockk<AnalyticsRepository>(relaxed = true)
    private val useCase = GetAnalyticsSummaryUseCase(repository)

    /** A jump at [loadKg] on F = 30 − 10·v N/kg for an 80 kg athlete. */
    private fun jump(loadKg: Float, velocity: Float) = JumpRepRecord(
        timestamp = 0L,
        setId = 1L,
        repNumber = 1,
        jump = RepJumpData(
            flightHeightCm = 30f,
            pushOffCm = 40f,
            meanForceN = (30f - 10f * velocity) * 80f,
            meanVelocity = velocity,
            systemMassKg = 80f + loadKg
        ),
        loadKg = loadKg
    )

    @Test
    fun `loaded jumps get a profile from the selected range's jumps`() = runTest {
        coEvery { repository.getJumpReps(ExerciseType.LOADED_JUMP, TimeRange.ONE_MONTH) } returns
            listOf(jump(40f, 1.0f), jump(20f, 1.2f), jump(0f, 1.4f))

        val summary = useCase(GetAnalyticsSummaryUseCase.Params(ExerciseType.LOADED_JUMP, TimeRange.ONE_MONTH))

        coVerify { repository.getJumpReps(ExerciseType.LOADED_JUMP, TimeRange.ONE_MONTH) }
        val profile = summary.forceVelocityProfile!!.profileOrNull!!
        assertEquals(30f, profile.f0, 1e-3f)
        assertEquals(3, profile.points.size)
    }

    @Test
    fun `suspect jumps stay out of the profile`() = runTest {
        val badFit = JumpFitCheck(
            gravityEstimate = 4f, gravityTStat = 9f, gravityOk = false, durbinWatson = 2f,
            lag1Autocorrelation = 0f, residualStep = false, residualsOk = true, rmsCm = 1f, samples = 20
        )
        val suspect = jump(60f, 2.5f).let { it.copy(jump = it.jump.copy(fitCheck = badFit)) }
        coEvery { repository.getJumpReps(ExerciseType.LOADED_JUMP, any()) } returns
            listOf(jump(40f, 1.0f), jump(20f, 1.2f), jump(0f, 1.4f), suspect)

        val profile = useCase(GetAnalyticsSummaryUseCase.Params(ExerciseType.LOADED_JUMP, TimeRange.ALL))
            .forceVelocityProfile!!.profileOrNull!!

        assertEquals(30f, profile.f0, 1e-3f)
        assertEquals(3, profile.points.size)
    }

    @Test
    fun `loaded jumps at one load get a rejection rather than nothing`() = runTest {
        coEvery { repository.getJumpReps(ExerciseType.LOADED_JUMP, any()) } returns listOf(jump(20f, 1.2f))

        val summary = useCase(GetAnalyticsSummaryUseCase.Params(ExerciseType.LOADED_JUMP, TimeRange.ALL))

        val rejected = summary.forceVelocityProfile as ForceVelocityProfileResult.Rejected
        assertEquals(ForceVelocityRejection.TOO_FEW_LOADS, rejected.reason)
    }

    @Test
    fun `other exercises get no profile`() = runTest {
        coEvery { repository.getJumpReps(any(), any()) } returns listOf(jump(0f, 1.2f), jump(0f, 1.4f))

        assertNull(useCase(GetAnalyticsSummaryUseCase.Params(ExerciseType.VERTICAL_JUMP, TimeRange.ALL)).forceVelocityProfile)
        assertNull(useCase(GetAnalyticsSummaryUseCase.Params(ExerciseType.BACK_SQUAT, TimeRange.ALL)).forceVelocityProfile)
        assertNull(useCase(GetAnalyticsSummaryUseCase.Params(null, TimeRange.ALL)).forceVelocityProfile)
    }
}
