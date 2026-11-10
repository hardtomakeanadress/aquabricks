package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * A rare, large shark visit. It enters from a random side, tracks the vertical
 * position of nearby fish and removes at most a couple of them before leaving.
 * The scene owns scheduling; this class owns the swim path and the bite pose.
 */
class Shark(
    private val tankWidth: Float,
    private val tankHeight: Float,
    private val sandY: Float,
    val sprite: Bitmap,
    private val rng: Random
) {
    private val baseDrawWidth: Float = tankWidth * 0.55f
    private val aspect: Float = sprite.height / sprite.width.toFloat()

    /** 0 = far murk, 1 = against the front glass. */
    var depth: Float = 0.06f
        private set
    val drawWidth: Float get() = baseDrawWidth * (0.40f + 0.60f * depth)
    val drawHeight: Float get() = drawWidth * aspect
    val alpha: Int get() = (
        (70 + 185 * depth) * (depth / 0.30f).coerceIn(0f, 1f)
        ).toInt().coerceIn(0, 255)

    var active: Boolean = false
        private set
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var facingRight: Boolean = true
        private set
    var tailPhase: Float = 0f
        private set
    var tailHz: Float = 0.4f
        private set
    var eatenThisVisit: Int = 0
        private set
    var visitAge: Float = 0f
        private set
    var tilt: Float = 0f
        private set

    private var direction = 1f
    private var speed = 70f
    private var targetY = 0f
    private var bobPhase = 0f
    private var bobRate = 0.6f
    private var biteFlash = 0f
    private var departing = false

    fun spawn(fromRight: Boolean, targetY: Float) {
        active = true
        departing = false
        direction = if (fromRight) -1f else 1f
        facingRight = direction > 0f
        depth = 0.06f
        // Appear in the distant water inside the tank, not beyond the side glass.
        // The chosen side is the side it will leave from, so it crosses the visible
        // water while it grows toward the front.
        x = if (direction > 0f) {
            tankWidth * (0.18f + rng.nextFloat() * 0.12f)
        } else {
            tankWidth * (0.70f + rng.nextFloat() * 0.12f)
        }
        // Enter off the stalking line so the first few seconds are a visible arc
        // toward the fish instead of a level, robotic glide.
        val entryOffset = (rng.nextFloat() - 0.5f) * drawHeight * 1.2f
        y = (targetY + entryOffset).coerceIn(minY(), maxY())
        this.targetY = targetY.coerceIn(minY(), maxY())
        speed = 62f + rng.nextFloat() * 16f
        visitAge = 0f
        eatenThisVisit = 0
        biteFlash = 0f
        tilt = 0f
        bobPhase = rng.nextFloat() * 6.2831855f
        bobRate = 0.55f + rng.nextFloat() * 0.35f
        tailPhase = rng.nextFloat() * 6.2831855f
    }

    /** @param nextTargetY vertical position of the fish it is currently stalking. */
    fun update(dt: Float, nextTargetY: Float?) {
        if (!active) return
        visitAge += dt
        biteFlash = (biteFlash - dt * 3f).coerceAtLeast(0f)
        var horizontalSpeed = speed
        var desiredY = y

        if (departing) {
            // Sink back into the distant water before the nose reaches the glass.
            depth = (depth - dt * 0.55f).coerceAtLeast(0.05f)
            horizontalSpeed = speed * 0.30f
            x += direction * horizontalSpeed * dt
            if (depth <= 0.055f) {
                active = false
                return
            }
        } else {
            depth = (depth + dt * 0.55f).coerceAtMost(1f)
            if (nextTargetY != null) targetY = nextTargetY
            // Sharks glide on a long, lazy line rather than darting at the prey.
            val bob = sin(visitAge * bobRate + bobPhase) * 7f
            desiredY = (targetY + bob).coerceIn(minY(), maxY())
            // Track the prey quickly enough to line the mouth up during one crossing.
            y += (desiredY - y) * (dt * 2.2f).coerceAtMost(1f)
            y = y.coerceIn(minY(), maxY())
            // A short burst when the prey is almost in line: the last few metres of
            // a shark attack are faster than its cruising glide.
            val lunge = if (
                depth > 0.65f && nextTargetY != null && abs(nextTargetY - y) < drawHeight * 0.75f
            ) {
                1.32f
            } else {
                1f
            }
            // The shark accelerates as it comes forward from the murk, instead of
            // sliding across the scene at a constant size and speed.
            val cruise = speed * (0.90f + 0.12f * sin(visitAge * 0.55f + bobPhase)) *
                (0.55f + 0.45f * depth)
            horizontalSpeed = cruise * lunge
            x += direction * horizontalSpeed * dt
            // Start receding while there is still water between the nose and glass.
            val nose = x + direction * drawWidth * 0.50f
            if (depth > 0.65f && (nose > tankWidth - 10f || nose < 10f)) {
                departing = true
            }
        }

        // Faster, speed-matched beat. The old 0.36 Hz tail looked frozen next to
        // the fish, so the shark now beats around 0.5-0.8 Hz while cruising.
        tailHz = (0.50f + horizontalSpeed / (drawWidth * 2.0f)).coerceIn(0.42f, 0.90f)
        tailPhase = (tailPhase + dt * tailHz * 6.2831855f) % 6.2831855f
        val desiredTilt = ((desiredY - y) / drawHeight).coerceIn(-0.12f, 0.12f) * 0.55f +
            sin(visitAge * 0.9f + bobPhase) * 0.014f
        tilt += (desiredTilt - tilt) * (dt * 2.2f).coerceAtMost(1f)
    }

    fun canHunt(): Boolean = active && !departing && depth > 0.62f

    fun bite() {
        eatenThisVisit++
        biteFlash = 1f
    }

    /** Send an active shark back into the murk without stopping mid-water. */
    fun retreat() {
        if (active) departing = true
    }

    /** Same depth projection the fish use: far animals sit higher in the water. */
    fun visualY(): Float = y - depthLift()

    fun mouthX(): Float = x + direction * drawWidth * 0.36f

    fun mouthY(): Float = visualY() + drawHeight * 0.15f

    fun tailX(): Float = x - direction * drawWidth * 0.46f

    fun tailY(): Float = visualY() + drawHeight * 0.04f

    fun canEat(maxEats: Int): Boolean = eatenThisVisit < maxEats

    /** Test hook: put the mouth exactly where a fixture fish is swimming. */
    internal fun placeMouthAtForTest(mouthX: Float, mouthY: Float) {
        active = true
        departing = false
        depth = 1f
        x = mouthX - direction * drawWidth * 0.36f
        y = mouthY - drawHeight * 0.15f
        targetY = y
    }

    private fun depthLift(): Float = (1f - depth) * tankHeight * 0.10f

    private fun minY(): Float = drawHeight * 0.5f + 6f + depthLift()

    private fun maxY(): Float = sandY - drawHeight * 0.42f + depthLift()

    companion object {
        private const val SPRITE_WIDTH = 960
        private const val SPRITE_HEIGHT = 330

        /**
         * Painted once at launch. Keeping the shark as a bitmap lets the scene use
         * the same travelling-wave mesh as the fish, so the tail sweeps sideways
         * instead of the whole sprite rotating like a cardboard cut-out.
         */
        fun renderSprite(): Bitmap {
            val bitmap = Bitmap.createBitmap(SPRITE_WIDTH, SPRITE_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(SPRITE_WIDTH.toFloat(), SPRITE_HEIGHT.toFloat())
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = 0xC016262E.toInt()
                strokeWidth = 0.0032f
                strokeJoin = Paint.Join.ROUND
            }

            // Body: pointed snout, tall dorsal fin, crescent tail.
            val body = Path().apply {
                moveTo(0.982f, 0.500f)
                cubicTo(0.930f, 0.405f, 0.845f, 0.330f, 0.740f, 0.305f)
                cubicTo(0.670f, 0.290f, 0.610f, 0.270f, 0.570f, 0.105f)
                lineTo(0.505f, 0.300f)
                cubicTo(0.420f, 0.295f, 0.315f, 0.330f, 0.215f, 0.405f)
                cubicTo(0.160f, 0.445f, 0.115f, 0.455f, 0.075f, 0.425f)
                lineTo(0.010f, 0.205f)
                cubicTo(0.045f, 0.315f, 0.052f, 0.430f, 0.048f, 0.500f)
                cubicTo(0.050f, 0.585f, 0.032f, 0.715f, 0.008f, 0.865f)
                lineTo(0.100f, 0.705f)
                cubicTo(0.160f, 0.735f, 0.270f, 0.795f, 0.395f, 0.825f)
                cubicTo(0.545f, 0.860f, 0.700f, 0.830f, 0.810f, 0.750f)
                cubicTo(0.885f, 0.695f, 0.950f, 0.605f, 0.982f, 0.500f)
                close()
            }
            fill.shader = LinearGradient(
                0f, 0f, 0f, 1f,
                intArrayOf(0xFF152A35.toInt(), 0xFF48616B.toInt(), 0xFFC4D2D3.toInt(), 0xFFF8FCFA.toInt()),
                floatArrayOf(0f, 0.36f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(body, fill)

            // Mottled countershading and soft underwater light. A perfectly
            // smooth vertical gradient was the main reason the first shark looked
            // like moulded plastic.
            canvas.save()
            canvas.clipPath(body)
            val texture = Random(77)
            repeat(44) {
                val cx = 0.16f + texture.nextFloat() * 0.68f
                val cy = 0.22f + texture.nextFloat() * 0.42f
                val rx = 0.012f + texture.nextFloat() * 0.032f
                val ry = 0.004f + texture.nextFloat() * 0.010f
                fill.shader = null
                fill.color = if (texture.nextBoolean()) 0x22202F38 else 0x14E8F2F0
                canvas.save()
                canvas.rotate(texture.nextFloat() * 180f, cx, cy)
                canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, fill)
                canvas.restore()
            }
            // A wet rim light along the back, plus two very soft caustic bands.
            line.strokeWidth = 0.005f
            line.color = 0x32D6E8E7
            val rim = Path().apply {
                moveTo(0.20f, 0.360f)
                cubicTo(0.38f, 0.300f, 0.55f, 0.290f, 0.70f, 0.318f)
            }
            canvas.drawPath(rim, line)
            line.strokeWidth = 0.014f
            line.color = 0x08E8FFF6
            for (i in 0 until 2) {
                val bx = 0.30f + i * 0.28f
                val caustic = Path().apply {
                    moveTo(bx, 0.25f)
                    cubicTo(bx - 0.04f, 0.40f, bx + 0.05f, 0.55f, bx - 0.01f, 0.76f)
                }
                canvas.drawPath(caustic, line)
            }
            // Soft shadow where the pectoral fin joins the body.
            fill.color = 0x2A0B1A22
            val finRoot = Path().apply {
                moveTo(0.585f, 0.640f)
                cubicTo(0.650f, 0.625f, 0.735f, 0.660f, 0.785f, 0.725f)
                cubicTo(0.720f, 0.735f, 0.640f, 0.710f, 0.585f, 0.680f)
                close()
            }
            canvas.drawPath(finRoot, fill)
            canvas.restore()
            canvas.drawPath(body, line)

            // Fin rays give the silhouette some internal structure.
            line.strokeWidth = 0.0022f
            line.color = 0x4A16262E.toInt()
            for (i in 0 until 3) {
                val dorsalRay = Path().apply {
                    moveTo(0.515f + i * 0.018f, 0.292f)
                    lineTo(0.552f + i * 0.007f, 0.148f + i * 0.042f)
                }
                canvas.drawPath(dorsalRay, line)
            }
            for (i in 0 until 2) {
                val upperTailRay = Path().apply {
                    moveTo(0.082f, 0.415f)
                    lineTo(0.018f + i * 0.010f, 0.230f + i * 0.055f)
                }
                canvas.drawPath(upperTailRay, line)
                val lowerTailRay = Path().apply {
                    moveTo(0.095f, 0.690f)
                    lineTo(0.018f + i * 0.008f, 0.825f - i * 0.040f)
                }
                canvas.drawPath(lowerTailRay, line)
            }
            line.strokeWidth = 0.0032f
            line.color = 0xC016262E.toInt()

            // Pectoral fin catches the light differently from the body.
            val pectoral = Path().apply {
                moveTo(0.700f, 0.665f)
                cubicTo(0.662f, 0.760f, 0.608f, 0.872f, 0.540f, 0.955f)
                cubicTo(0.618f, 0.910f, 0.700f, 0.845f, 0.785f, 0.748f)
                cubicTo(0.758f, 0.726f, 0.728f, 0.695f, 0.700f, 0.665f)
                close()
            }
            fill.shader = LinearGradient(
                0f, 0.62f, 0f, 1f,
                intArrayOf(0xFF38515C.toInt(), 0xFF9FB2B4.toInt()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(pectoral, fill)
            canvas.save()
            canvas.clipPath(pectoral)
            line.strokeWidth = 0.002f
            line.color = 0x4A22343B.toInt()
            for (i in 0 until 4) {
                val ray = Path().apply {
                    moveTo(0.690f - i * 0.012f, 0.700f + i * 0.005f)
                    cubicTo(
                        0.630f - i * 0.008f, 0.790f,
                        0.590f - i * 0.008f, 0.870f,
                        0.552f - i * 0.004f, 0.935f - i * 0.018f
                    )
                }
                canvas.drawPath(ray, line)
            }
            canvas.restore()
            canvas.drawPath(pectoral, line)
            line.strokeWidth = 0.0025f
            line.color = 0x55E4F1EF.toInt()
            val finHighlight = Path().apply {
                moveTo(0.678f, 0.700f)
                cubicTo(0.640f, 0.780f, 0.595f, 0.865f, 0.556f, 0.930f)
            }
            canvas.drawPath(finHighlight, line)
            line.strokeWidth = 0.0032f
            line.color = 0xC016262E.toInt()
            // Pelvic and anal fins.
            val smallFin = Path().apply {
                moveTo(0.400f, 0.825f)
                lineTo(0.355f, 0.925f)
                lineTo(0.465f, 0.840f)
                close()
            }
            canvas.drawPath(smallFin, fill)
            canvas.drawPath(smallFin, line)
            val analFin = Path().apply {
                moveTo(0.265f, 0.825f)
                lineTo(0.230f, 0.890f)
                lineTo(0.315f, 0.835f)
                close()
            }
            canvas.drawPath(analFin, fill)
            canvas.drawPath(analFin, line)

            // Mouth: a deep maw with a pink tongue and two rows of teeth.
            fill.shader = null
            val maw = Path().apply {
                moveTo(0.952f, 0.575f)
                cubicTo(0.885f, 0.600f, 0.820f, 0.620f, 0.758f, 0.630f)
                cubicTo(0.800f, 0.722f, 0.878f, 0.738f, 0.958f, 0.642f)
                close()
            }
            fill.color = 0xFF2B0A10.toInt()
            canvas.drawPath(maw, fill)
            val throat = Path().apply {
                moveTo(0.925f, 0.605f)
                cubicTo(0.865f, 0.625f, 0.815f, 0.638f, 0.790f, 0.645f)
                cubicTo(0.825f, 0.690f, 0.875f, 0.700f, 0.930f, 0.650f)
                close()
            }
            fill.color = 0xFF14060A.toInt()
            canvas.drawPath(throat, fill)
            val tongue = Path().apply {
                moveTo(0.790f, 0.680f)
                cubicTo(0.835f, 0.706f, 0.885f, 0.690f, 0.938f, 0.650f)
                cubicTo(0.900f, 0.700f, 0.842f, 0.724f, 0.802f, 0.700f)
                close()
            }
            fill.color = 0xFFA94E5E.toInt()
            canvas.drawPath(tongue, fill)
            line.strokeWidth = 0.0025f
            line.color = 0xAA160A0E.toInt()
            canvas.drawPath(maw, line)

            val tooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            for (i in 0 until 8) {
                val t = i / 7f
                val baseX = 0.940f - t * 0.165f
                val baseY = 0.596f + t * 0.030f
                val tipY = baseY + 0.038f
                val teeth = Path().apply {
                    moveTo(baseX - 0.011f, baseY)
                    lineTo(baseX + 0.011f, baseY)
                    lineTo(baseX, tipY)
                    close()
                }
                canvas.drawPath(teeth, tooth)
            }
            for (i in 0 until 6) {
                val t = i / 5f
                val baseX = 0.800f + t * 0.135f
                val baseY = 0.704f - t * 0.052f
                val tipY = baseY - 0.032f
                val teeth = Path().apply {
                    moveTo(baseX - 0.009f, baseY)
                    lineTo(baseX + 0.009f, baseY)
                    lineTo(baseX, tipY)
                    close()
                }
                canvas.drawPath(teeth, tooth)
            }

            // Eye, nostril and gill slits.
            fill.shader = null
            fill.color = 0xFF0B1418.toInt()
            canvas.drawCircle(0.835f, 0.425f, 0.020f, fill)
            fill.color = 0xFFF4FAF8.toInt()
            canvas.drawCircle(0.829f, 0.418f, 0.006f, fill)
            fill.color = 0xFF1D3038.toInt()
            canvas.drawCircle(0.948f, 0.515f, 0.006f, fill)

            line.strokeWidth = 0.0045f
            line.color = 0xB016262E.toInt()
            val brow = Path().apply {
                moveTo(0.788f, 0.405f)
                quadTo(0.826f, 0.375f, 0.868f, 0.402f)
            }
            canvas.drawPath(brow, line)

            line.strokeWidth = 0.0035f
            line.color = 0x88435E68.toInt()
            for (i in 0 until 5) {
                val bx = 0.755f - i * 0.022f
                val gill = Path().apply {
                    moveTo(bx, 0.475f)
                    cubicTo(bx - 0.012f, 0.540f, bx - 0.010f, 0.605f, bx + 0.004f, 0.655f)
                }
                canvas.drawPath(gill, line)
            }

            // A couple of pale flank marks break up the smooth painted body.
            line.strokeWidth = 0.0025f
            line.color = 0x66E8F2F0.toInt()
            val flank = Path().apply {
                moveTo(0.690f, 0.560f)
                cubicTo(0.610f, 0.575f, 0.520f, 0.585f, 0.440f, 0.585f)
            }
            canvas.drawPath(flank, line)
            line.color = 0x44BFD2D6.toInt()
            val flank2 = Path().apply {
                moveTo(0.815f, 0.630f)
                cubicTo(0.740f, 0.660f, 0.650f, 0.675f, 0.565f, 0.675f)
            }
            canvas.drawPath(flank2, line)

            return bitmap
        }
    }
}
