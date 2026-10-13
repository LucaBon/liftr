package com.liftr.ui.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Adds a tactile press-scale micro-interaction: the element shrinks slightly
 * while pressed and springs back on release, making taps feel responsive.
 *
 * Share the same [interactionSource] with the clickable (e.g. a [androidx.compose.material3.Button])
 * so the scale tracks the real press state:
 *
 * ```
 * val source = remember { MutableInteractionSource() }
 * Button(onClick = ..., interactionSource = source, modifier = Modifier.pressScale(source)) { ... }
 * ```
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
    durationMillis: Int = 120
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = durationMillis),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
