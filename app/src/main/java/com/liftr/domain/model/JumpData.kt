package com.liftr.domain.model

data class JumpData(
    val height: Float, // meters
    val flightTime: Float, // seconds
    val takeoffTimestamp: Float, // seconds from start
    val landingTimestamp: Float, // seconds from start
    val peakVelocity: Float = 0f // m/s at takeoff
)
