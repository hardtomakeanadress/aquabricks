package com.aquabricks.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Dims the camera preview and cuts a wide window for the fish, with chunky
 * viewfinder corners in the AquaBricks palette.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val scrimPaint = Paint().apply { color = Color.parseColor("#A6081A22") }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#FFD166")
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#D9FFF4D6")
        letterSpacing = 0.12f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#99FFF4D6")
        letterSpacing = 0.1f
    }
    private val frame = RectF()
    private val cornerPath = Path()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val frameWidth = width * 0.86f
        val frameHeight = minOf(width * 0.86f * 0.62f, height * 0.46f)
        val centerY = height * 0.46f
        frame.set(
            (width - frameWidth) / 2f,
            centerY - frameHeight / 2f,
            (width + frameWidth) / 2f,
            centerY + frameHeight / 2f
        )
        textPaint.textSize = dp(11f)
        hintPaint.textSize = dp(9f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.isEmpty) return

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val radius = dp(20f)
        canvas.drawRoundRect(frame, radius, radius, clearPaint)
        canvas.restoreToCount(layer)

        // Viewfinder corners.
        val arm = dp(26f)
        val gap = dp(1f)
        cornerPath.reset()
        // top-left
        cornerPath.moveTo(frame.left, frame.top + arm)
        cornerPath.lineTo(frame.left, frame.top + gap)
        cornerPath.lineTo(frame.left + arm, frame.top + gap)
        // top-right
        cornerPath.moveTo(frame.right - arm, frame.top + gap)
        cornerPath.lineTo(frame.right, frame.top + gap)
        cornerPath.lineTo(frame.right, frame.top + arm)
        // bottom-right
        cornerPath.moveTo(frame.right, frame.bottom - arm)
        cornerPath.lineTo(frame.right, frame.bottom - gap)
        cornerPath.lineTo(frame.right - arm, frame.bottom - gap)
        // bottom-left
        cornerPath.moveTo(frame.left + arm, frame.bottom - gap)
        cornerPath.lineTo(frame.left, frame.bottom - gap)
        cornerPath.lineTo(frame.left, frame.bottom - arm)
        canvas.drawPath(cornerPath, framePaint)

        canvas.drawText("FIT THE FISH IN THE FRAME", width / 2f, frame.top - dp(16f), textPaint)
        canvas.drawText("KEEP THE BASE FLAT AND THE BACKGROUND PLAIN", width / 2f, frame.bottom + dp(24f), hintPaint)
    }
}
