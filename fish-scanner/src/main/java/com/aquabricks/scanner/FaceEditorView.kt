package com.aquabricks.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.aquabricks.scanner.imaging.ArgbImage
import kotlin.math.hypot
import kotlin.math.min

/**
 * Large interactive preview for placing the chosen eye and mouth on the fish.
 * The face is composited into the sprite itself, so what is dragged here is exactly
 * what the TV receives.
 */
class FaceEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var base: ArgbImage? = null
    private var config = FaceConfig()
    private var facingRight = true
    private var composed: Bitmap? = null

    private val imageRect = RectF()
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#900E2A36")
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#FFD166")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#FFD166")
        textSize = dp(9f)
    }

    private var activeHandle = -1

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    fun setData(base: ArgbImage, config: FaceConfig, facingRight: Boolean) {
        this.base = base
        this.config = config
        this.facingRight = facingRight
        refresh()
    }

    fun setConfig(config: FaceConfig) {
        this.config = config
        refresh()
    }

    fun currentConfig(): FaceConfig = config

    private fun refresh() {
        val source = base ?: return
        composed = ImageUtils.toBitmap(FacePainter.render(source, config, facingRight))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = composed ?: return
        val padding = dp(10f)
        val scale = min(
            (width - padding * 2f) / bitmap.width,
            (height - padding * 2f) / bitmap.height
        )
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        imageRect.set(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)

        if (config.eye != EyeModel.NONE) {
            drawHandle(
                canvas,
                imageRect.left + config.eyeX * imageRect.width(),
                imageRect.top + config.eyeY * imageRect.height(),
                "EYE"
            )
        }
        if (config.mouth != MouthModel.NONE) {
            drawHandle(
                canvas,
                imageRect.left + config.mouthX * imageRect.width(),
                imageRect.top + config.mouthY * imageRect.height(),
                "MOUTH"
            )
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, label: String) {
        canvas.drawCircle(x, y, dp(15f), handleFill)
        canvas.drawCircle(x, y, dp(15f), handleStroke)
        canvas.drawCircle(x, y, dp(2.5f), handleStroke)
        canvas.drawText(label, x, y - dp(21f), labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (base == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = nearestHandle(event.x, event.y)
                if (activeHandle < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle < 0) return false
                moveActive(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle >= 0) {
                    activeHandle = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestHandle(viewX: Float, viewY: Float): Int {
        var best = -1
        var bestDistance = dp(46f)
        if (config.eye != EyeModel.NONE) {
            val distance = hypot(
                imageRect.left + config.eyeX * imageRect.width() - viewX,
                imageRect.top + config.eyeY * imageRect.height() - viewY
            )
            if (distance < bestDistance) {
                bestDistance = distance
                best = 0
            }
        }
        if (config.mouth != MouthModel.NONE) {
            val distance = hypot(
                imageRect.left + config.mouthX * imageRect.width() - viewX,
                imageRect.top + config.mouthY * imageRect.height() - viewY
            )
            if (distance < bestDistance) {
                best = 1
            }
        }
        return best
    }

    private fun moveActive(viewX: Float, viewY: Float) {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return
        val normalizedX = ((viewX - imageRect.left) / imageRect.width()).coerceIn(0.03f, 0.97f)
        val normalizedY = ((viewY - imageRect.top) / imageRect.height()).coerceIn(0.03f, 0.97f)
        config = if (activeHandle == 0) {
            config.copy(eyeX = normalizedX, eyeY = normalizedY)
        } else {
            config.copy(mouthX = normalizedX, mouthY = normalizedY)
        }
        refresh()
    }
}
