package com.liftr.vision.tracking

import com.liftr.domain.model.MovementPhase
import com.liftr.domain.vision.IMovementPhaseDetector
import com.liftr.util.CircularBuffer
import com.liftr.util.Constants
import javax.inject.Inject
import kotlin.math.abs

class MovementPhaseDetector @Inject constructor() : IMovementPhaseDetector {
    private var currentPhase = MovementPhase.IDLE
    private val velocityHistory = CircularBuffer<Float>(10)

    override fun detectPhase(velocity: Float): MovementPhase {
        velocityHistory.add(velocity)

        val avgVelocity = if (velocityHistory.size > 0) {
            velocityHistory.toList().average().toFloat()
        } else {
            0f
        }

        currentPhase = when (currentPhase) {
            MovementPhase.IDLE -> {
                if (abs(avgVelocity) > Constants.MOVEMENT_VELOCITY_THRESHOLD) {
                    if (avgVelocity < 0) MovementPhase.ECCENTRIC
                    else MovementPhase.CONCENTRIC
                } else MovementPhase.IDLE
            }

            MovementPhase.ECCENTRIC -> {
                if (avgVelocity > -Constants.TRANSITION_VELOCITY_THRESHOLD) {
                    MovementPhase.BOTTOM
                } else MovementPhase.ECCENTRIC
            }

            MovementPhase.BOTTOM -> {
                if (avgVelocity > Constants.MOVEMENT_VELOCITY_THRESHOLD) {
                    MovementPhase.CONCENTRIC
                } else MovementPhase.BOTTOM
            }

            MovementPhase.CONCENTRIC -> {
                if (avgVelocity < Constants.TRANSITION_VELOCITY_THRESHOLD) {
                    MovementPhase.TOP
                } else MovementPhase.CONCENTRIC
            }

            MovementPhase.TOP -> {
                if (abs(avgVelocity) < Constants.IDLE_VELOCITY_THRESHOLD) {
                    MovementPhase.IDLE
                } else if (avgVelocity < -Constants.MOVEMENT_VELOCITY_THRESHOLD) {
                    MovementPhase.ECCENTRIC
                } else MovementPhase.TOP
            }
        }

        return currentPhase
    }

    override fun reset() {
        currentPhase = MovementPhase.IDLE
        velocityHistory.clear()
    }

    fun getCurrentPhase(): MovementPhase = currentPhase
}
