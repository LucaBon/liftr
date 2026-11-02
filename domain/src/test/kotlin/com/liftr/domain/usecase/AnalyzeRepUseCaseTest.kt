package com.liftr.domain.usecase

import com.liftr.domain.exercise.ExerciseStrategy
import com.liftr.domain.exercise.ExerciseStrategyFactory
import com.liftr.domain.exercise.FormIssue
import com.liftr.domain.exercise.FormValidationResult
import com.liftr.domain.exercise.RepQualityScorer
import com.liftr.domain.exercise.Severity
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.value.Kilograms
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyzeRepUseCaseTest {

    private fun lean(severity: Severity, degrees: Int) = FormIssue(
        severity = severity,
        message = "Excessive forward lean ($degrees°)",
        suggestion = "Chest up",
        kind = "torso_uprightness"
    )

    @Test
    fun `a fault seen across many frames with changing angles costs the rep once`() = runBlocking {
        val perFrame = listOf(
            listOf(lean(Severity.WARNING, 27)),
            listOf(lean(Severity.CRITICAL, 36)),
            listOf(lean(Severity.CRITICAL, 37)),
            listOf(lean(Severity.CRITICAL, 38)),
            emptyList()
        )
        val strategy = mockk<ExerciseStrategy> {
            every { validateForm(any(), any(), any()) } returnsMany
                perFrame.map { FormValidationResult(isValid = it.none { i -> i.severity == Severity.CRITICAL }, issues = it) }
        }
        val factory = mockk<ExerciseStrategyFactory> { every { getStrategy(any()) } returns strategy }

        val result = AnalyzeRepUseCase(factory, mockk(relaxed = true))(
            AnalyzeRepUseCase.Params(
                exerciseType = ExerciseType.FRONT_SQUAT,
                calibrationData = mockk(relaxed = true),
                weight = Kilograms(0f),
                bufferedPoses = List(perFrame.size) { mockk(relaxed = true) },
                finalVelocityData = null
            )
        )

        assertEquals(listOf(lean(Severity.CRITICAL, 36)), result.formValidation.issues)
        assertEquals(15, RepQualityScorer.scoreForm(result.formValidation.issues))
    }
}
