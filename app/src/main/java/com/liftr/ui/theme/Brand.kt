package com.liftr.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Brand gradients for the "Volt Performance" identity. Used on hero CTAs,
 * stat highlights, and decorative accents to give the app its energetic,
 * athletic signature.
 */
object Brand {
    /** Diagonal volt-lime → cyan sweep. The hero accent gradient. */
    val voltGradient: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.linearGradient(
            colors = listOf(GradientVoltStart, GradientVoltEnd),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )

    /** Horizontal variant for wide buttons / bars. */
    val voltGradientHorizontal: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.horizontalGradient(listOf(GradientVoltStart, GradientVoltEnd))
}
