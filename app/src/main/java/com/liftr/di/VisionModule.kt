package com.liftr.di

import com.liftr.domain.vision.IMovementPhaseDetector
import com.liftr.domain.vision.IPoseDetector
import com.liftr.domain.vision.IPositionCalculator
import com.liftr.domain.vision.ISquatPhaseDetector
import com.liftr.domain.vision.IVelocityCalculator
import com.liftr.vision.calculator.PositionCalculator
import com.liftr.vision.calculator.VelocityCalculator
import com.liftr.vision.pose.PoseDetector
import com.liftr.vision.tracking.MovementPhaseDetector
import com.liftr.vision.tracking.SquatPhaseDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for providing vision-related dependencies.
 *
 * This module binds vision interfaces to their concrete implementations,
 * allowing use cases to depend on abstractions rather than concrete classes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VisionModule {

    /**
     * Binds PoseDetector to IPoseDetector interface.
     */
    @Binds
    @Singleton
    abstract fun bindPoseDetector(
        impl: PoseDetector
    ): IPoseDetector

    /**
     * Binds VelocityCalculator to IVelocityCalculator interface.
     *
     * NOTE: NOT a singleton - each injection gets a new instance.
     * This ensures thread safety as each workout session has its own state.
     */
    @Binds
    abstract fun bindVelocityCalculator(
        impl: VelocityCalculator
    ): IVelocityCalculator

    /**
     * Binds PositionCalculator to IPositionCalculator interface.
     */
    @Binds
    @Singleton
    abstract fun bindPositionCalculator(
        impl: PositionCalculator
    ): IPositionCalculator

    /**
     * Binds MovementPhaseDetector to IMovementPhaseDetector interface.
     */
    @Binds
    @Singleton
    abstract fun bindMovementPhaseDetector(
        impl: MovementPhaseDetector
    ): IMovementPhaseDetector

    /**
     * Binds SquatPhaseDetector to ISquatPhaseDetector interface.
     */
    @Binds
    @Singleton
    abstract fun bindSquatPhaseDetector(
        impl: SquatPhaseDetector
    ): ISquatPhaseDetector
}
