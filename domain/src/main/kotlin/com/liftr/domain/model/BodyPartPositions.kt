package com.liftr.domain.model

/**
 * Positions of body parts in normalized image coordinates.
 *
 * Y values are normalized by the image height. X values are expressed in the
 * same unit (normalized x multiplied by [PoseResult.aspectScaleX]), so X and Y
 * can be combined directly in angles and distances. They are therefore not
 * suitable for drawing on screen, which needs the raw normalized x.
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
