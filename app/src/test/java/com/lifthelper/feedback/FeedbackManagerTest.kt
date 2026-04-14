package com.lifthelper.feedback

import com.lifthelper.domain.exercise.FormIssue
import com.lifthelper.domain.exercise.Severity
import com.lifthelper.domain.model.FeedbackEvent
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [FeedbackEvent] sealed class — verifies event construction
 * and exhaustive pattern matching.
 */
class FeedbackEventTest {

    @Test
    fun `RepCounted stores rep number`() {
        val event = FeedbackEvent.RepCounted(5)
        assertEquals(5, event.repNumber)
    }

    @Test
    fun `VelocityLossAlert stores loss percent`() {
        val event = FeedbackEvent.VelocityLossAlert(0.25f)
        assertEquals(0.25f, event.lossPercent, 0.001f)
    }

    @Test
    fun `FormWarning stores FormIssue with severity and suggestion`() {
        val issue = FormIssue(Severity.WARNING, "knee_cave", "Watch your knees")
        val event = FeedbackEvent.FormWarning(issue)

        assertEquals(Severity.WARNING, event.issue.severity)
        assertEquals("knee_cave", event.issue.message)
        assertEquals("Watch your knees", event.issue.suggestion)
    }

    @Test
    fun `FormCritical stores FormIssue with CRITICAL severity`() {
        val issue = FormIssue(Severity.CRITICAL, "back_rounding", "Stop — back rounding")
        val event = FeedbackEvent.FormCritical(issue)

        assertEquals(Severity.CRITICAL, event.issue.severity)
    }

    @Test
    fun `all FeedbackEvent subtypes are covered in when expression`() {
        val events: List<FeedbackEvent> = listOf(
            FeedbackEvent.RepCounted(1),
            FeedbackEvent.SetComplete,
            FeedbackEvent.VelocityLossAlert(0.20f),
            FeedbackEvent.FormWarning(FormIssue(Severity.WARNING, "test", "test")),
            FeedbackEvent.FormCritical(FormIssue(Severity.CRITICAL, "test", "test")),
            FeedbackEvent.DepthAchieved,
            FeedbackEvent.DepthInsufficient,
            FeedbackEvent.PersonalRecord,
            FeedbackEvent.RestComplete
        )

        // Exhaustive when — compiler enforces all branches
        events.forEach { event ->
            val label = when (event) {
                is FeedbackEvent.RepCounted -> "rep"
                is FeedbackEvent.SetComplete -> "set"
                is FeedbackEvent.VelocityLossAlert -> "velocity"
                is FeedbackEvent.FormWarning -> "warning"
                is FeedbackEvent.FormCritical -> "critical"
                is FeedbackEvent.DepthAchieved -> "depth"
                is FeedbackEvent.DepthInsufficient -> "depth_insufficient"
                is FeedbackEvent.PersonalRecord -> "pr"
                is FeedbackEvent.RestComplete -> "rest"
                is FeedbackEvent.TempoCountdown -> "tempo_countdown"
                is FeedbackEvent.TempoHold -> "tempo_hold"
                is FeedbackEvent.TempoSpeedCue -> "tempo_speed"
            }
            assertTrue(label.isNotEmpty())
        }

        assertEquals(9, events.size)
    }

    @Test
    fun `SetComplete and DepthAchieved are singletons`() {
        assertSame(FeedbackEvent.SetComplete, FeedbackEvent.SetComplete)
        assertSame(FeedbackEvent.DepthAchieved, FeedbackEvent.DepthAchieved)
    }
}
