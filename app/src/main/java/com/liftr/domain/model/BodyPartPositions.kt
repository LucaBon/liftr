package com.liftr.domain.model

/**
 * Data class to hold positions of different body parts (in pixels)
 */
data class BodyPartPositions(
    val hipsY: Float?,
    val shouldersY: Float?,
    val headY: Float?,
    val kneesY: Float?,
    val hipsX: Float? = null,
    val kneesX: Float? = null,
    val wristsY: Float? = null,
    val wristsX: Float? = null,
    val elbowsY: Float? = null,
    val elbowsX: Float? = null,
    val anklesY: Float? = null,
    val anklesX: Float? = null,
    val shouldersX: Float? = null
)
