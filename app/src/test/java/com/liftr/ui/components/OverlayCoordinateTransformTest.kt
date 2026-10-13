package com.liftr.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rotation/mirroring of pose coordinates into display space.
 *
 * The front-camera flip must be applied AFTER rotation. PreviewView mirrors the
 * front camera horizontally in display space; flipping the buffer's X first
 * turns into a display-vertical flip once rotated 90°/270°, which left the
 * overlay mirrored on the wrong axis. Rotations 0/180 happened to agree either
 * way, which is why the bug stayed hidden.
 */
class OverlayCoordinateTransformTest {

    private fun transform(x: Float, y: Float, rotation: Int, front: Boolean) =
        OverlayCoordinateTransform.transformCoordinates(x, y, rotation, front)

    private fun assertPair(
        expectedX: Float,
        expectedY: Float,
        actual: Pair<Float, Float>,
        message: String
    ) {
        assertEquals("$message (x)", expectedX, actual.first, 1e-5f)
        assertEquals("$message (y)", expectedY, actual.second, 1e-5f)
    }

    @Test
    fun `back camera rotation 0 is identity`() {
        assertPair(0.25f, 0.75f, transform(0.25f, 0.75f, 0, false), "rot0")
    }

    @Test
    fun `back camera rotates 90 degrees clockwise`() {
        assertPair(1f - 0.75f, 0.25f, transform(0.25f, 0.75f, 90, false), "rot90")
    }

    @Test
    fun `back camera rotates 180 degrees`() {
        assertPair(1f - 0.25f, 1f - 0.75f, transform(0.25f, 0.75f, 180, false), "rot180")
    }

    @Test
    fun `back camera rotates 270 degrees`() {
        assertPair(0.75f, 1f - 0.25f, transform(0.25f, 0.75f, 270, false), "rot270")
    }

    @Test
    fun `front camera mirrors display x only, at every rotation`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val back = transform(0.25f, 0.75f, rotation, false)
            val front = transform(0.25f, 0.75f, rotation, true)

            assertEquals(
                "rotation $rotation should mirror display x",
                1f - back.first,
                front.first,
                1e-5f
            )
            assertEquals(
                "rotation $rotation must leave display y untouched",
                back.second,
                front.second,
                1e-5f
            )
        }
    }

    @Test
    fun `front camera at 90 degrees does not flip vertically`() {
        // The specific regression: the old pre-rotation flip produced
        // (1 - y, 1 - x) here, mirroring both axes.
        assertPair(0.75f, 0.25f, transform(0.25f, 0.75f, 90, true), "front rot90")
    }

    @Test
    fun `transform stays within unit range`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (front in listOf(true, false)) {
                for (x in listOf(0f, 0.5f, 1f)) {
                    for (y in listOf(0f, 0.5f, 1f)) {
                        val (tx, ty) = transform(x, y, rotation, front)
                        assertEquals("x in range", true, tx in 0f..1f)
                        assertEquals("y in range", true, ty in 0f..1f)
                    }
                }
            }
        }
    }
}
