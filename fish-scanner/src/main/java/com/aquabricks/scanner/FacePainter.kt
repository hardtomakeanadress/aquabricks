package com.aquabricks.scanner

import com.aquabricks.scanner.imaging.ArgbImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class EyeModel(val label: String) {
    NONE("NONE"),
    ROUND("ROUND"),
    HAPPY("HAPPY"),
    ANGRY("ANGRY"),
    SLEEPY("SLEEPY")
}

enum class MouthModel(val label: String) {
    NONE("NONE"),
    SMILE("SMILE"),
    GRIN("GRIN"),
    FROWN("FROWN"),
    OPEN("OPEN")
}

/** Positions are normalised to the sprite bounds, so they survive reprocessing. */
data class FaceConfig(
    val eye: EyeModel = EyeModel.ROUND,
    val eyeX: Float = 0.70f,
    val eyeY: Float = 0.38f,
    val mouth: MouthModel = MouthModel.SMILE,
    val mouthX: Float = 0.80f,
    val mouthY: Float = 0.62f
) {
    fun mirrored(): FaceConfig = copy(
        eyeX = 1f - eyeX,
        mouthX = 1f - mouthX
    )
}

/**
 * Draws a chosen eye and mouth directly into an ARGB sprite. Everything is chunky
 * pixel art so it sits naturally on a scanned brick mosaic; the head direction is
 * used to mirror asymmetric shapes such as an angry brow.
 */
object FacePainter {

    private const val OUTLINE = 0xFF1B2A34.toInt()
    private const val WHITE = 0xFFF4F4F4.toInt()
    private const val PINK = 0xFFE4ADC8.toInt()
    private const val TONGUE = 0xFFC91A09.toInt()

    fun render(base: ArgbImage, config: FaceConfig, facingRight: Boolean): ArgbImage {
        val output = base.copy()
        if (config.eye == EyeModel.NONE && config.mouth == MouthModel.NONE) return output
        val brush = Brush(output)
        val minDim = min(base.width, base.height).toFloat()
        // Faces must survive the tank's ~50-75 px fish height. The old 14% eye
        // radius became a two-pixel dot on the TV; use a bold cartoon scale.
        val eyeSize = max(16f, minDim * 0.24f)
        val mouthWidth = max(20f, minDim * 0.34f)
        val direction = if (facingRight) 1f else -1f

        if (config.eye != EyeModel.NONE) {
            drawEye(
                brush,
                config.eye,
                config.eyeX.coerceIn(0f, 1f) * base.width,
                config.eyeY.coerceIn(0f, 1f) * base.height,
                eyeSize,
                direction
            )
        }
        if (config.mouth != MouthModel.NONE) {
            drawMouth(
                brush,
                config.mouth,
                config.mouthX.coerceIn(0f, 1f) * base.width,
                config.mouthY.coerceIn(0f, 1f) * base.height,
                mouthWidth,
                direction
            )
        }
        return output
    }

    private fun drawEye(
        brush: Brush,
        model: EyeModel,
        cx: Float,
        cy: Float,
        size: Float,
        direction: Float
    ) {
        fun lx(value: Float) = cx + value * size * direction
        fun ly(value: Float) = cy + value * size

        when (model) {
            EyeModel.ROUND, EyeModel.ANGRY, EyeModel.SLEEPY -> {
                brush.circle(cx, cy, size * 0.54f, OUTLINE)
                brush.circle(cx, cy, size * 0.47f, WHITE)
                brush.circle(lx(0.10f), ly(0.02f), size * 0.22f, OUTLINE)
                brush.circle(lx(-0.10f), ly(-0.13f), size * 0.075f, WHITE)
                if (model == EyeModel.ANGRY) {
                    brush.line(
                        lx(-0.62f), ly(-0.58f),
                        lx(0.52f), ly(-0.30f),
                        size * 0.16f,
                        OUTLINE
                    )
                }
                if (model == EyeModel.SLEEPY) {
                    brush.line(
                        lx(-0.52f), ly(-0.08f),
                        lx(0.52f), ly(-0.08f),
                        size * 0.18f,
                        OUTLINE
                    )
                }
            }
            EyeModel.HAPPY -> {
                val points = floatArrayOf(
                    lx(-0.48f), ly(0.12f),
                    lx(-0.22f), ly(-0.28f),
                    lx(0.22f), ly(-0.28f),
                    lx(0.48f), ly(0.12f)
                )
                brush.polyline(points, size * 0.16f, OUTLINE)
            }
            EyeModel.NONE -> Unit
        }
    }

    private fun drawMouth(
        brush: Brush,
        model: MouthModel,
        cx: Float,
        cy: Float,
        width: Float,
        direction: Float
    ) {
        fun lx(value: Float) = cx + value * width * direction
        fun ly(value: Float) = cy + value * width
        val thickness = width * 0.11f

        when (model) {
            MouthModel.SMILE -> brush.polyline(
                floatArrayOf(
                    lx(-0.50f), ly(-0.10f),
                    lx(-0.26f), ly(0.22f),
                    lx(0f), ly(0.30f),
                    lx(0.26f), ly(0.22f),
                    lx(0.50f), ly(-0.10f)
                ),
                thickness,
                OUTLINE
            )
            MouthModel.FROWN -> brush.polyline(
                floatArrayOf(
                    lx(-0.50f), ly(0.22f),
                    lx(-0.26f), ly(-0.10f),
                    lx(0f), ly(-0.20f),
                    lx(0.26f), ly(-0.10f),
                    lx(0.50f), ly(0.22f)
                ),
                thickness,
                OUTLINE
            )
            MouthModel.GRIN -> {
                brush.ellipse(cx, cy, width * 0.46f, width * 0.28f, OUTLINE)
                brush.rect(cx, cy - width * 0.17f, width * 0.62f, width * 0.10f, WHITE)
                brush.ellipse(cx, cy + width * 0.16f, width * 0.18f, width * 0.08f, PINK)
            }
            MouthModel.OPEN -> {
                brush.ellipse(cx, cy, width * 0.28f, width * 0.34f, OUTLINE)
                brush.ellipse(cx, cy + width * 0.15f, width * 0.13f, width * 0.10f, TONGUE)
            }
            MouthModel.NONE -> Unit
        }
    }

    private class Brush(private val image: ArgbImage) {

        fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until image.width && y in 0 until image.height) {
                image.pixels[y * image.width + x] = color
            }
        }

        fun circle(cx: Float, cy: Float, radius: Float, color: Int) {
            if (radius <= 0f) return
            val left = floor(cx - radius).toInt()
            val top = floor(cy - radius).toInt()
            val right = ceil(cx + radius).toInt()
            val bottom = ceil(cy + radius).toInt()
            val radiusSquared = radius * radius
            for (y in top..bottom) {
                for (x in left..right) {
                    val dx = x + 0.5f - cx
                    val dy = y + 0.5f - cy
                    if (dx * dx + dy * dy <= radiusSquared) set(x, y, color)
                }
            }
        }

        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
            if (rx <= 0f || ry <= 0f) return
            val left = floor(cx - rx).toInt()
            val top = floor(cy - ry).toInt()
            val right = ceil(cx + rx).toInt()
            val bottom = ceil(cy + ry).toInt()
            for (y in top..bottom) {
                for (x in left..right) {
                    val dx = (x + 0.5f - cx) / rx
                    val dy = (y + 0.5f - cy) / ry
                    if (dx * dx + dy * dy <= 1f) set(x, y, color)
                }
            }
        }

        fun rect(cx: Float, cy: Float, width: Float, height: Float, color: Int) {
            val left = floor(cx - width * 0.5f).toInt()
            val top = floor(cy - height * 0.5f).toInt()
            val right = ceil(cx + width * 0.5f).toInt()
            val bottom = ceil(cy + height * 0.5f).toInt()
            for (y in top..bottom) {
                for (x in left..right) set(x, y, color)
            }
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float, thickness: Float, color: Int) {
            val dx = x1 - x0
            val dy = y1 - y0
            val steps = max(1, max(abs(dx), abs(dy)).roundToInt())
            val radius = thickness * 0.5f
            for (step in 0..steps) {
                val t = step / steps.toFloat()
                circle(x0 + dx * t, y0 + dy * t, radius, color)
            }
        }

        fun polyline(points: FloatArray, thickness: Float, color: Int) {
            var index = 0
            while (index + 3 < points.size) {
                line(points[index], points[index + 1], points[index + 2], points[index + 3], thickness, color)
                index += 2
            }
        }
    }
}
