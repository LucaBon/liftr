package com.liftr.domain.exercise

import com.liftr.domain.model.ExerciseType
import com.liftr.domain.model.PoseKeypoint
import com.liftr.domain.model.PoseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingPointTest {

    // Shoulders at (0.2, 0.2)/(0.4, 0.2), wrists at (0.6, 0.6)/(0.8, 0.6), everything else far away.
    private fun pose(wristVisibility: Float = 1f): PoseResult {
        val landmarks = MutableList(33) { PoseKeypoint(0.5f, 0.9f, 0f, 1f) }
        landmarks[PoseResult.LEFT_SHOULDER] = PoseKeypoint(0.2f, 0.2f, 0f, 1f)
        landmarks[PoseResult.RIGHT_SHOULDER] = PoseKeypoint(0.4f, 0.2f, 0f, 1f)
        landmarks[PoseResult.LEFT_WRIST] = PoseKeypoint(0.6f, 0.6f, 0f, wristVisibility)
        landmarks[PoseResult.RIGHT_WRIST] = PoseKeypoint(0.8f, 0.6f, 0f, wristVisibility)
        return PoseResult(landmarks, 0L)
    }

    private fun resolve(type: ExerciseType, pose: PoseResult = pose()) =
        normalizedXY(pose, trackingPoint(type))

    private fun assertShoulders(type: ExerciseType) {
        assertEquals(TrackingPoint.SHOULDERS, trackingPoint(type))
        val (x, y) = resolve(type)!!
        assertEquals(0.3f, x, 1e-5f)
        assertEquals(0.2f, y, 1e-5f)
    }

    private fun assertWrists(type: ExerciseType) {
        assertEquals(TrackingPoint.WRISTS, trackingPoint(type))
        val (x, y) = resolve(type)!!
        assertEquals(0.7f, x, 1e-5f)
        assertEquals(0.6f, y, 1e-5f)
    }

    @Test fun backSquatTracksShoulders() = assertShoulders(ExerciseType.BACK_SQUAT)
    @Test fun frontSquatTracksShoulders() = assertShoulders(ExerciseType.FRONT_SQUAT)
    @Test fun benchPressTracksWrists() = assertWrists(ExerciseType.BENCH_PRESS)
    @Test fun deadliftTracksWrists() = assertWrists(ExerciseType.DEADLIFT)
    @Test fun cleanTracksWrists() = assertWrists(ExerciseType.CLEAN)
    @Test fun snatchTracksWrists() = assertWrists(ExerciseType.SNATCH)

    @Test fun verticalJumpTracksCenterOfMass() =
        assertEquals(TrackingPoint.CENTER_OF_MASS, trackingPoint(ExerciseType.VERTICAL_JUMP))

    @Test fun approachJumpTracksCenterOfMass() =
        assertEquals(TrackingPoint.CENTER_OF_MASS, trackingPoint(ExerciseType.APPROACH_JUMP))

    @Test fun loadedJumpTracksLoadedCenterOfMass() =
        assertEquals(TrackingPoint.LOADED_CENTER_OF_MASS, trackingPoint(ExerciseType.LOADED_JUMP))

    @Test fun hiddenWristsGiveNull() {
        assertNull(resolve(ExerciseType.DEADLIFT, pose(wristVisibility = 0.2f)))
    }
}
