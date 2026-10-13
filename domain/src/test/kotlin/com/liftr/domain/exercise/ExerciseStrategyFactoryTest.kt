package com.liftr.domain.exercise

import com.liftr.domain.exercise.strategy.BackSquatStrategy
import com.liftr.domain.exercise.strategy.BenchPressStrategy
import com.liftr.domain.exercise.strategy.CleanStrategy
import com.liftr.domain.exercise.strategy.DeadliftStrategy
import com.liftr.domain.exercise.strategy.FrontSquatStrategy
import com.liftr.domain.exercise.strategy.JumpStrategy
import com.liftr.domain.exercise.strategy.SnatchStrategy
import com.liftr.domain.logging.Logger
import com.liftr.domain.model.ExerciseType
import com.liftr.domain.vision.IPositionCalculator
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseStrategyFactoryTest {

    private val positionCalculator = mockk<IPositionCalculator>(relaxed = true)
    private val logger = mockk<Logger>(relaxed = true)

    private val factory = ExerciseStrategyFactory(
        backSquatStrategy = BackSquatStrategy(positionCalculator, logger),
        frontSquatStrategy = FrontSquatStrategy(positionCalculator, logger),
        benchPressStrategy = BenchPressStrategy(positionCalculator, logger),
        deadliftStrategy = DeadliftStrategy(positionCalculator, logger),
        cleanStrategy = CleanStrategy(positionCalculator, logger),
        snatchStrategy = SnatchStrategy(positionCalculator, logger),
        verticalJumpStrategy = JumpStrategy(ExerciseType.VERTICAL_JUMP, logger),
        loadedJumpStrategy = JumpStrategy(ExerciseType.LOADED_JUMP, logger)
    )

    @Test
    fun `every exercise type resolves to a strategy for that type`() {
        ExerciseType.entries.forEach { type ->
            assertEquals(type, factory.getStrategy(type).exerciseType)
        }
    }

    @Test
    fun `supported exercises cover every exercise type`() {
        assertEquals(ExerciseType.entries.toSet(), factory.getSupportedExercises().toSet())
    }
}
