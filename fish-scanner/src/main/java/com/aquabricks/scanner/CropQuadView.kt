package com.aquabricks.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.aquabricks.scanner.imaging.FishImageProcessor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Photo + four draggable perspective corners. Corners are stored in source-bitmap
 * coordinates so the processing pipeline can use them directly.
 */
class CropQuadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onQuadChanged: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private val fitMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val quad = FloatArray(8)
    private var activeCorner = -1

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }
    private val scrimPaint = Paint().apply { color = Color.parseColor("#9E081A22") }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#FFD166")
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 244, 214)
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.8f)
        color = Color.parseColor("#FFD166")
    }
    private val handleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD166")
    }
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = Color.parseColor("#FFD166")
    }
    private val loupeCrosshair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.WHITE
    }
    private val path = Path()
    private val loupeClip = Path()
    private val mapped = FloatArray(8)
    private val handleRadius = dp(13f)
    private val touchRadius = dp(52f)
    private val loupeRadius = dp(48f)
    private val loupeOffset = dp(76f)
    private val loupeZoom = 2.6f

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setPhoto(photo: Bitmap) {
        bitmap = photo
        quad[0] = photo.width * 0.06f
        quad[1] = photo.height * 0.06f
        quad[2] = photo.width * 0.94f
        quad[3] = photo.height * 0.06f
        quad[4] = photo.width * 0.94f
        quad[5] = photo.height * 0.94f
        quad[6] = photo.width * 0.06f
        quad[7] = photo.height * 0.94f
        recomputeMatrix()
        invalidate()
    }

    fun setQuad(points: FloatArray) {
        if (points.size < 8) return
        val candidate = points.copyOf(8)
        if (!FishImageProcessor.isValidQuad(candidate)) return
        System.arraycopy(candidate, 0, quad, 0, 8)
        invalidate()
    }

    fun quad(): FloatArray = quad.copyOf()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        recomputeMatrix()
    }

    private fun recomputeMatrix() {
        val photo = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = dp(6f)
        val destination = RectF(pad, pad, width - pad, height - pad)
        val source = RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        fitMatrix.setRectToRect(source, destination, Matrix.ScaleToFit.CENTER)
        fitMatrix.invert(inverseMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val photo = bitmap ?: return
        canvas.drawBitmap(photo, fitMatrix, photoPaint)

        for (i in 0 until 4) {
            mapped[i * 2] = quad[i * 2]
            mapped[i * 2 + 1] = quad[i * 2 + 1]
        }
        fitMatrix.mapPoints(mapped)

        path.reset()
        path.moveTo(mapped[0], mapped[1])
        path.lineTo(mapped[2], mapped[3])
        path.lineTo(mapped[4], mapped[5])
        path.lineTo(mapped[6], mapped[7])
        path.close()

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawPath(path, clearPaint)
        canvas.restoreToCount(layer)
        canvas.drawPath(path, borderPaint)

        for (i in 0 until 4) {
            val cx = mapped[i * 2]
            val cy = mapped[i * 2 + 1]
            canvas.drawCircle(cx, cy, handleRadius, handleFill)
            canvas.drawCircle(cx, cy, handleRadius, handleStroke)
            canvas.drawCircle(cx, cy, dp(2.2f), handleDot)
        }

        if (activeCorner in 0..3) {
            drawLoupe(canvas, photo, mapped[activeCorner * 2], mapped[activeCorner * 2 + 1])
        }
    }

    /**
     * A finger hides the corner it is dragging. The loupe is offset above (or
     * below near the top edge) and shows the exact pixels under the corner with a
     * crosshair, so the user can see whether the crop line clips the fish.
     */
    private fun drawLoupe(canvas: Canvas, photo: Bitmap, cornerX: Float, cornerY: Float) {
        val margin = dp(8f)
        // A multi-window/IME resize can make the view smaller than the loupe.
        // coerceIn() throws when its lower bound exceeds its upper bound.
        if (width < 2 * (loupeRadius + margin) || height < 2 * (loupeRadius + margin)) return
        val loupeX = cornerX.coerceIn(loupeRadius + margin, width - loupeRadius - margin)
        val above = cornerY - loupeOffset
        val loupeY = if (above - loupeRadius >= margin) {
            above
        } else {
            (cornerY + loupeOffset).coerceIn(loupeRadius + margin, height - loupeRadius - margin)
        }
        loupeClip.reset()
        loupeClip.addCircle(loupeX, loupeY, loupeRadius, Path.Direction.CW)
        val layer = canvas.saveLayer(
            loupeX - loupeRadius, loupeY - loupeRadius,
            loupeX + loupeRadius, loupeY + loupeRadius, null
        )
        canvas.clipPath(loupeClip)
        canvas.drawColor(Color.BLACK)
        canvas.translate(loupeX, loupeY)
        canvas.scale(loupeZoom, loupeZoom)
        canvas.translate(-cornerX, -cornerY)
        canvas.drawBitmap(photo, fitMatrix, photoPaint)
        canvas.restoreToCount(layer)

        canvas.drawCircle(loupeX, loupeY, loupeRadius, loupeRing)
        val cross = dp(11f)
        canvas.drawLine(loupeX - cross, loupeY, loupeX + cross, loupeY, loupeCrosshair)
        canvas.drawLine(loupeX, loupeY - cross, loupeX, loupeY + cross, loupeCrosshair)
        canvas.drawCircle(loupeX, loupeY, dp(3f), handleDot)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = nearestCorner(event.x, event.y)
                if (activeCorner < 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                moveCorner(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner >= 0) {
                    moveCorner(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCorner >= 0) {
                    activeCorner = -1
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onQuadChanged?.invoke()
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearestCorner(viewX: Float, viewY: Float): Int {
        var best = -1
        var bestDistance = touchRadius
        for (i in 0 until 4) {
            val distance = hypot(mapped[i * 2] - viewX, mapped[i * 2 + 1] - viewY)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private fun moveCorner(viewX: Float, viewY: Float) {
        val photo = bitmap ?: return
        val point = floatArrayOf(viewX, viewY)
        inverseMatrix.mapPoints(point)
        var x = point[0].coerceIn(0f, photo.width.toFloat())
        var y = point[1].coerceIn(0f, photo.height.toFloat())
        val minGap = 10f

        when (activeCorner) {
            0 -> {
                x = x.coerceAtMost(quad[2] - minGap)
                y = y.coerceAtMost(quad[7] - minGap)
            }
            1 -> {
                x = x.coerceAtLeast(quad[0] + minGap)
                y = y.coerceAtMost(quad[5] - minGap)
            }
            2 -> {
                x = x.coerceAtLeast(quad[6] + minGap)
                y = y.coerceAtLeast(quad[3] + minGap)
            }
            3 -> {
                x = x.coerceAtMost(quad[4] - minGap)
                y = y.coerceAtLeast(quad[1] + minGap)
            }
        }
        val previousX = quad[activeCorner * 2]
        val previousY = quad[activeCorner * 2 + 1]
        quad[activeCorner * 2] = x
        quad[activeCorner * 2 + 1] = y
        if (!FishImageProcessor.isValidQuad(quad)) {
            // Keep the last valid rectangle; a concave crop cannot be rectified.
            quad[activeCorner * 2] = previousX
            quad[activeCorner * 2 + 1] = previousY
            invalidate()
            return
        }
        invalidate()
        onQuadChanged?.invoke()
    }
}
