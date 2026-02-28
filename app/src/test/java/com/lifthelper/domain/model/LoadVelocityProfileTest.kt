package com.lifthelper.domain.model

import com.lifthelper.domain.value.Kilograms
import com.lifthelper.domain.value.MetersPerSecond
import org.junit.Assert.*
import org.junit.Test

class LoadVelocityProfileTest {

    private fun makeProfile(
        points: List<Pair<Float, Float>>,
        exerciseType: ExerciseType = ExerciseType.BACK_SQUAT
    ): LoadVelocityProfile {
        return LoadVelocityProfile(
            exerciseType = exerciseType,
            dataPoints = points.map { (load, velocity) ->
                LoadVelocityPoint(
                    load = Kilograms(load),
                    meanVelocity = MetersPerSecond(velocity),
                    timestamp = System.currentTimeMillis()
                )
            },
            minVelocityAt1RM = LoadVelocityProfile.getMinVelocityAt1RM(exerciseType)
        )
    }

    @Test
    fun `should return null when fewer than 3 data points`() {
        val profile = makeProfile(listOf(60f to 0.90f, 80f to 0.70f))
        assertNull(profile.estimatedOneRepMax())
    }

    @Test
    fun `should return null for empty data`() {
        val profile = makeProfile(emptyList())
        assertNull(profile.estimatedOneRepMax())
    }

    @Test
    fun `should estimate 1RM from linear load-velocity data`() {
        // Perfect linear: velocity = -0.01 * load + 1.50
        // At velocity 0.30 (back squat 1RM threshold): load = (0.30 - 1.50) / -0.01 = 120
        val profile = makeProfile(listOf(
            60f to 0.90f,
            80f to 0.70f,
            100f to 0.50f
        ))

        val estimated = profile.estimatedOneRepMax()
        assertNotNull(estimated)
        assertEquals(120f, estimated!!.value, 1f)
    }

    @Test
    fun `should return null when slope is positive`() {
        // Velocity increases with load (impossible / bad data)
        val profile = makeProfile(listOf(
            60f to 0.50f,
            80f to 0.70f,
            100f to 0.90f
        ))

        assertNull(profile.estimatedOneRepMax())
    }

    @Test
    fun `should return null when all loads are the same`() {
        val profile = makeProfile(listOf(
            80f to 0.70f,
            80f to 0.65f,
            80f to 0.72f
        ))

        assertNull(profile.estimatedOneRepMax())
    }

    @Test
    fun `should return null when R-squared is below threshold`() {
        // Scattered data that won't fit a line well
        val profile = makeProfile(listOf(
            60f to 0.90f,
            80f to 0.95f,  // faster at higher load = bad fit
            100f to 0.50f,
            120f to 0.85f   // faster again
        ))

        assertNull(profile.estimatedOneRepMax())
    }

    @Test
    fun `should accept custom R-squared threshold`() {
        val profile = makeProfile(listOf(
            60f to 0.90f,
            80f to 0.70f,
            100f to 0.50f
        ))

        // Should work with default threshold
        assertNotNull(profile.estimatedOneRepMax())
        // Should work with lower threshold
        assertNotNull(profile.estimatedOneRepMax(minRSquared = 0.50))
        // Should work with very high threshold (perfect data)
        assertNotNull(profile.estimatedOneRepMax(minRSquared = 0.99))
    }

    @Test
    fun `should use exercise-specific min velocity at 1RM`() {
        // Bench press has lower min velocity (0.17) than squat (0.30)
        // Same data should produce different 1RM estimates
        val data = listOf(60f to 0.80f, 80f to 0.60f, 100f to 0.40f)

        val squatProfile = makeProfile(data, ExerciseType.BACK_SQUAT)
        val benchProfile = makeProfile(data, ExerciseType.BENCH_PRESS)

        val squatMax = squatProfile.estimatedOneRepMax()
        val benchMax = benchProfile.estimatedOneRepMax()

        assertNotNull(squatMax)
        assertNotNull(benchMax)
        // Bench 1RM should be higher (lower min velocity → further along the line)
        assertTrue(benchMax!!.value > squatMax!!.value)
    }

    @Test
    fun `should return correct min velocity for each exercise type`() {
        assertEquals(0.30f, LoadVelocityProfile.getMinVelocityAt1RM(ExerciseType.BACK_SQUAT), 0.001f)
        assertEquals(0.30f, LoadVelocityProfile.getMinVelocityAt1RM(ExerciseType.FRONT_SQUAT), 0.001f)
        assertEquals(0.17f, LoadVelocityProfile.getMinVelocityAt1RM(ExerciseType.BENCH_PRESS), 0.001f)
        assertEquals(0.15f, LoadVelocityProfile.getMinVelocityAt1RM(ExerciseType.DEADLIFT), 0.001f)
    }

    @Test
    fun `fitLinearRegression should return correct statistics`() {
        // Perfect linear: velocity = -0.01 * load + 1.50
        val profile = makeProfile(listOf(
            60f to 0.90f,
            80f to 0.70f,
            100f to 0.50f
        ))

        val regression = profile.fitLinearRegression()
        assertNotNull(regression)
        assertEquals(-0.01, regression!!.slope, 0.001)
        assertEquals(1.50, regression.intercept, 0.01)
        assertEquals(1.0, regression.rSquared, 0.01) // Perfect fit
    }

    @Test
    fun `fitLinearRegression should return null for insufficient data`() {
        val profile = makeProfile(listOf(60f to 0.90f))
        assertNull(profile.fitLinearRegression())
    }

    @Test
    fun `realistic back squat profile`() {
        // Typical advanced lifter:
        // 60kg @ 1.10 m/s, 80kg @ 0.85 m/s, 100kg @ 0.65 m/s, 120kg @ 0.45 m/s
        val profile = makeProfile(listOf(
            60f to 1.10f,
            80f to 0.85f,
            100f to 0.65f,
            120f to 0.45f
        ))

        val estimated = profile.estimatedOneRepMax()
        assertNotNull(estimated)
        // Should be around 135-145kg for this profile
        assertTrue(estimated!!.value > 130f)
        assertTrue(estimated.value < 150f)
    }
}

class LoadVelocityPointTest {

    @Test
    fun `should create LoadVelocityPoint correctly`() {
        val point = LoadVelocityPoint(
            load = Kilograms(100f),
            meanVelocity = MetersPerSecond(0.65f),
            timestamp = 1000L
        )

        assertEquals(100f, point.load.value, 0.001f)
        assertEquals(0.65f, point.meanVelocity.value, 0.001f)
        assertEquals(1000L, point.timestamp)
    }
}

class LinearRegressionResultTest {

    @Test
    fun `should create LinearRegressionResult correctly`() {
        val result = LinearRegressionResult(
            slope = -0.01,
            intercept = 1.50,
            rSquared = 0.95
        )

        assertEquals(-0.01, result.slope, 0.001)
        assertEquals(1.50, result.intercept, 0.001)
        assertEquals(0.95, result.rSquared, 0.001)
    }
}
