package com.lifthelper.domain.calculator

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CoordinateCalculatorAngleTest {

    private lateinit var calculator: CoordinateCalculator

    @Before
    fun setup() {
        calculator = CoordinateCalculator()
    }

    // --- angleBetweenPoints tests ---

    @Test
    fun `angleBetweenPoints with right angle returns 90 degrees`() {
        // A=(0,1), B=(0,0), C=(1,0) — right angle at origin
        val angle = calculator.angleBetweenPoints(
            ax = 0f, ay = 1f,
            bx = 0f, by = 0f,
            cx = 1f, cy = 0f
        )
        assertEquals(90f, angle, 0.1f)
    }

    @Test
    fun `angleBetweenPoints with collinear points returns 180 degrees`() {
        // A=(0,0), B=(1,0), C=(2,0) — straight line
        val angle = calculator.angleBetweenPoints(
            ax = 0f, ay = 0f,
            bx = 1f, by = 0f,
            cx = 2f, cy = 0f
        )
        assertEquals(180f, angle, 0.1f)
    }

    @Test
    fun `angleBetweenPoints with acute angle returns correct value`() {
        // A=(0,1), B=(0,0), C=(1,1) — 45 degree angle
        val angle = calculator.angleBetweenPoints(
            ax = 0f, ay = 1f,
            bx = 0f, by = 0f,
            cx = 1f, cy = 1f
        )
        assertEquals(45f, angle, 0.1f)
    }

    @Test
    fun `angleBetweenPoints with obtuse angle returns correct value`() {
        // A=(1,1), B=(0,0), C=(-1,0) — 135 degree angle
        val angle = calculator.angleBetweenPoints(
            ax = 1f, ay = 1f,
            bx = 0f, by = 0f,
            cx = -1f, cy = 0f
        )
        assertEquals(135f, angle, 0.1f)
    }

    @Test
    fun `angleBetweenPoints with zero-length segment returns 0`() {
        // B == A, so BA has zero length
        val angle = calculator.angleBetweenPoints(
            ax = 1f, ay = 1f,
            bx = 1f, by = 1f,
            cx = 2f, cy = 2f
        )
        assertEquals(0f, angle, 0.01f)
    }

    @Test
    fun `angleBetweenPoints with coincident B and C returns 0`() {
        val angle = calculator.angleBetweenPoints(
            ax = 0f, ay = 0f,
            bx = 1f, by = 1f,
            cx = 1f, cy = 1f
        )
        assertEquals(0f, angle, 0.01f)
    }

    @Test
    fun `angleBetweenPoints with 60 degree angle`() {
        // Equilateral triangle: A=(0,0), B=(1,0), C=(0.5, sqrt(3)/2)
        val sqrt3over2 = 0.866025f
        val angle = calculator.angleBetweenPoints(
            ax = 0f, ay = 0f,
            bx = 1f, by = 0f,
            cx = 0.5f, cy = sqrt3over2
        )
        assertEquals(60f, angle, 0.1f)
    }

    @Test
    fun `angleBetweenPoints simulates fully extended elbow at 180 degrees`() {
        // Shoulder at (0.5, 0.3), Elbow at (0.5, 0.5), Wrist at (0.5, 0.7)
        // Fully extended arm — straight line
        val angle = calculator.angleBetweenPoints(
            ax = 0.5f, ay = 0.3f,
            bx = 0.5f, by = 0.5f,
            cx = 0.5f, cy = 0.7f
        )
        assertEquals(180f, angle, 0.1f)
    }

    @Test
    fun `angleBetweenPoints simulates bent elbow at roughly 90 degrees`() {
        // Shoulder at (0.5, 0.3), Elbow at (0.5, 0.5), Wrist at (0.7, 0.5)
        val angle = calculator.angleBetweenPoints(
            ax = 0.5f, ay = 0.3f,
            bx = 0.5f, by = 0.5f,
            cx = 0.7f, cy = 0.5f
        )
        assertEquals(90f, angle, 0.1f)
    }

    // --- torsoAngle tests ---

    @Test
    fun `torsoAngle with perfectly vertical torso returns 0 degrees`() {
        val angle = calculator.torsoAngle(
            shoulderX = 0.5f, shoulderY = 0.3f,
            hipX = 0.5f, hipY = 0.6f
        )
        assertEquals(0f, angle, 0.1f)
    }

    @Test
    fun `torsoAngle with 45 degree lean returns 45 degrees`() {
        // Shoulder offset by the same amount in X as the Y difference
        val angle = calculator.torsoAngle(
            shoulderX = 0.8f, shoulderY = 0.3f,
            hipX = 0.5f, hipY = 0.6f
        )
        assertEquals(45f, angle, 0.1f)
    }

    @Test
    fun `torsoAngle with horizontal torso returns 90 degrees`() {
        // Shoulder and hip at same Y, different X
        val angle = calculator.torsoAngle(
            shoulderX = 0.8f, shoulderY = 0.5f,
            hipX = 0.5f, hipY = 0.5f
        )
        assertEquals(90f, angle, 0.1f)
    }

    @Test
    fun `torsoAngle with slight forward lean returns small angle`() {
        val angle = calculator.torsoAngle(
            shoulderX = 0.52f, shoulderY = 0.3f,
            hipX = 0.5f, hipY = 0.6f
        )
        // atan2(0.02, 0.3) ≈ 3.8 degrees
        assertEquals(3.8f, angle, 0.5f)
    }

    @Test
    fun `torsoAngle direction does not matter`() {
        // Uses abs() so leaning left or right gives the same angle
        val angleRight = calculator.torsoAngle(
            shoulderX = 0.8f, shoulderY = 0.3f,
            hipX = 0.5f, hipY = 0.6f
        )
        val angleLeft = calculator.torsoAngle(
            shoulderX = 0.2f, shoulderY = 0.3f,
            hipX = 0.5f, hipY = 0.6f
        )
        assertEquals(angleRight, angleLeft, 0.01f)
    }
}
