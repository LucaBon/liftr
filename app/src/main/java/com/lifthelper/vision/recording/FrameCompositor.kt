package com.lifthelper.vision.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.lifthelper.domain.model.FrameAnnotation
import com.lifthelper.domain.model.PoseResult

/**
 * Composites the pose skeleton + workout HUD (rep counter, velocity, VBT zone,
 * watermark) onto a decoded camera frame, producing the burned-in overlay used
 * by the shareable annotated video (Feature 09).
 *
 * Pose landmark coordinates are normalized against the same bitmap the model
 * ran on, so they map straight onto the output with a simple multiply — no
 * rotation or mirror transform is needed here.
 *
 * All drawing is sized relative to [height] so the overlay looks consistent
 * across recording resolutions.
 */
class FrameCompositor(
    private val width: Int,
    private val height: Int
) {

    /** Header text shared by every frame (exercise + load). */
    data class Header(
        val exerciseLabel: String,
        val weightKg: Float,
        val targetReps: Int,
        val watermark: String = "LiftHelper"
    )

    private val unit = (height / 48f).coerceAtLeast(8f)

    private val skeletonLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = unit * 0.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 76, 175, 80) // green
        style = Paint.Style.FILL
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val repTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = unit * 2.2f
        isFakeBoldText = true
    }
    private val velocityTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = unit * 2.6f
        isFakeBoldText = true
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = unit * 1.2f
    }
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = unit * 1.3f
        isFakeBoldText = true
    }

    /**
     * Draw the overlay for [annotation] onto a copy of [frame] scaled to the
     * compositor's [width] x [height]. The returned bitmap is a new ARGB_8888
     * bitmap owned by the caller; [frame] is left untouched.
     */
    fun composite(frame: Bitmap, annotation: FrameAnnotation?, header: Header): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        canvas.drawBitmap(frame, Rect(0, 0, frame.width, frame.height), Rect(0, 0, width, height), null)

        annotation?.pose?.let { drawSkeleton(canvas, it) }
        drawHud(canvas, annotation, header)
        drawWatermark(canvas, header)

        return output
    }

    private fun drawSkeleton(canvas: Canvas, pose: PoseResult) {
        val landmarks = pose.landmarks
        CONNECTIONS.forEach { (start, end) ->
            val a = landmarks.getOrNull(start) ?: return@forEach
            val b = landmarks.getOrNull(end) ?: return@forEach
            if (a.visibility > VISIBILITY_THRESHOLD && b.visibility > VISIBILITY_THRESHOLD) {
                canvas.drawLine(
                    a.x * width, a.y * height,
                    b.x * width, b.y * height,
                    skeletonLinePaint
                )
            }
        }
        landmarks.forEach { landmark ->
            if (landmark.visibility > VISIBILITY_THRESHOLD) {
                canvas.drawCircle(landmark.x * width, landmark.y * height, unit * 0.55f, jointPaint)
            }
        }
    }

    private fun drawHud(canvas: Canvas, annotation: FrameAnnotation?, header: Header) {
        val padding = unit
        val repCount = annotation?.repCount ?: 0
        val repText = if (header.targetReps > 0) {
            "Rep $repCount / ${header.targetReps}"
        } else {
            "Rep $repCount"
        }
        val velocityValue = annotation?.velocity?.value ?: 0f
        val velocityText = "%.2f m/s".format(velocityValue)
        val zoneColor = annotation?.vbtZone?.let { (it.colorHex and 0xFFFFFFFF).toInt() } ?: Color.WHITE

        // Panel sizing based on the widest line of text.
        val panelWidth = maxOf(
            repTextPaint.measureText(repText),
            velocityTextPaint.measureText(velocityText),
            labelTextPaint.measureText(header.exerciseLabel)
        ) + padding * 2
        val panelHeight = unit * 8.5f
        val panel = RectF(padding, padding, padding + panelWidth, padding + panelHeight)
        canvas.drawRoundRect(panel, unit, unit, panelPaint)

        var cursorY = panel.top + unit * 1.8f
        canvas.drawText(header.exerciseLabel, panel.left + padding, cursorY, labelTextPaint)
        cursorY += unit * 2.6f
        canvas.drawText(repText, panel.left + padding, cursorY, repTextPaint)
        cursorY += unit * 3.0f
        velocityTextPaint.color = zoneColor
        canvas.drawText(velocityText, panel.left + padding, cursorY, velocityTextPaint)

        // Zone label under the velocity, when known.
        annotation?.vbtZone?.let { zone ->
            val label = zone.displayName
            labelTextPaint.color = zoneColor
            canvas.drawText(label, panel.left + padding, cursorY + unit * 1.6f, labelTextPaint)
            labelTextPaint.color = Color.argb(220, 255, 255, 255)
        }
    }

    private fun drawWatermark(canvas: Canvas, header: Header) {
        val text = buildString {
            append(header.watermark)
            if (header.weightKg > 0f) append(" \u00B7 ${header.weightKg.toInt()} kg")
        }
        val textWidth = watermarkPaint.measureText(text)
        canvas.drawText(
            text,
            width - textWidth - unit,
            height - unit * 1.2f,
            watermarkPaint
        )
    }

    companion object {
        private const val VISIBILITY_THRESHOLD = 0.5f

        /** Skeleton connections by MediaPipe landmark index (matches PoseOverlay). */
        private val CONNECTIONS = listOf(
            11 to 13, 13 to 15, // left arm
            12 to 14, 14 to 16, // right arm
            11 to 12,           // shoulders
            11 to 23, 12 to 24, // shoulders to hips
            23 to 24,           // hips
            23 to 25, 25 to 27, // left leg
            24 to 26, 26 to 28  // right leg
        )
    }
}
