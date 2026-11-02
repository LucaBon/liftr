package com.liftr.domain.exercise

import org.junit.Assert.assertEquals
import org.junit.Test

class FormIssueTest {

    private fun lean(severity: Severity, degrees: Int) = FormIssue(
        severity = severity,
        message = "Forward lean ($degrees°)",
        suggestion = "Chest up",
        kind = "torso_uprightness"
    )

    @Test
    fun `issues of one kind with different measured values collapse to one`() {
        val issues = listOf(lean(Severity.CRITICAL, 36), lean(Severity.CRITICAL, 37), lean(Severity.CRITICAL, 38))

        assertEquals(listOf(lean(Severity.CRITICAL, 36)), issues.worstPerKind())
    }

    @Test
    fun `the most severe instance of a kind is kept`() {
        val issues = listOf(lean(Severity.WARNING, 27), lean(Severity.CRITICAL, 40), lean(Severity.WARNING, 30))

        assertEquals(listOf(lean(Severity.CRITICAL, 40)), issues.worstPerKind())
    }

    @Test
    fun `different kinds are all kept in first-seen order`() {
        val heels = FormIssue(Severity.WARNING, "Heels lifting off ground", "Feet flat", kind = "foot_stability")
        val issues = listOf(heels, lean(Severity.WARNING, 27), heels, lean(Severity.CRITICAL, 36))

        assertEquals(listOf(heels, lean(Severity.CRITICAL, 36)), issues.worstPerKind())
    }

    @Test
    fun `kind defaults to the message`() {
        val a = FormIssue(Severity.INFO, "Same", "x")
        val b = FormIssue(Severity.WARNING, "Same", "y")
        val c = FormIssue(Severity.INFO, "Other", "z")

        assertEquals(listOf(b, c), listOf(a, b, c).worstPerKind())
    }
}
