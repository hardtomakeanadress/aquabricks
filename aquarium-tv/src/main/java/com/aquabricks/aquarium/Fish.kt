package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A swimming fish. The scan is normalised to a transparent sprite whose head faces
 * right; the renderer mirrors it when the fish swims left. A hinged mesh wave keeps
 * the head steady while the rear half beats like a tail.
 */
class Fish(
    val meta: FishMeta,
    bitmap: Bitmap,
    private val width: Float,
    private val height: Float,
    private val sandY: Float,
    rng: Random
) {
    val phase: Float = rng.nextFloat() * 6.283f
    /** 0 = far end of the tank, 1 = against the front glass. */
    var depth: Float = 0.25f + rng.nextFloat() * 0.75f
    private var targetDepth = depth
    // Keep 2-3 scanned fish comfortably spaced. The sprite is prepared at the
    // largest depth; the draw code scales it down when the fish is farther away.
    private val maxDrawHeight: Float = height * 0.13f
    // Keep twice as many texture pixels as the fish occupies on screen. The fish
    // is still drawn small, but LEGO studs, faces and the thin brick joints
    // survive downscaling instead of turning into aliased mush.
    val sprite: Bitmap = normalise(
        bitmap,
        mirror = meta.facing.equals("left", ignoreCase = true),
        targetHeight = (maxDrawHeight * SUPERSAMPLE).roundToInt().coerceAtLeast(9),
        maxWidth = (width * 0.30f * SUPERSAMPLE).roundToInt().coerceAtLeast(9)
    )
    private val baseDrawHeight: Float = sprite.height / SUPERSAMPLE
    private val baseDrawWidth: Float = sprite.width / SUPERSAMPLE
    // The depth range is deliberately wide: far fish are visibly smaller, dimmer
    // and higher in the tank, so moving toward the back reads as 3D rather than
    // as just another left/right slide.
    private val depthScale: Float get() = 0.55f + 0.45f * depth
    val drawHeight: Float get() = baseDrawHeight * depthScale
    val drawWidth: Float get() = baseDrawWidth * depthScale

    var x: Float = 40f + rng.nextFloat() * (width - 80f)
    var y: Float = 44f + rng.nextFloat() * (sandY - 120f)
    var vx: Float = (if (rng.nextBoolean()) 1f else -1f) *
        (baseDrawWidth * 0.6f).coerceIn(24f, 80f)
    var vy: Float = (rng.nextFloat() - 0.5f) * 10f
    var facingRight: Boolean = vx >= 0f
    /** Signed horizontal scale: -1 fully left, +1 fully right, passing through 0. */
    var turnScaleX: Float = if (vx >= 0f) 1f else -1f
        private set
    var tilt: Float = 0f
    var justAte: Boolean = false
    var age: Float = 0f
    var activity: Float = 0.9f + rng.nextFloat() * 0.2f
    var tailHz: Float = 1f
        private set
    /**
     * Accumulated tail phase. Multiplying absolute uptime by a changing frequency
     * would make the phase jump whenever the fish accelerates or slows.
     */
    var tailPhase: Float = rng.nextFloat() * 6.2831855f
        private set

    // Swim speed scales with apparent body length. A 0.8-body stride keeps the
    // tail beat and the distance travelled in the same physical relationship.
    private val cruiseFactor: Float = 0.55f + rng.nextFloat() * 0.20f
    // Every fish gets its own metabolic tempo and beat character. The old shared
    // 0.7 rad/s activity sine made a whole tank slow down and speed up together,
    // and the common speed-to-tail mapping made the beats look choreographed.
    private val cruiseTempo: Float = 0.35f + rng.nextFloat() * 0.65f
    private val cruiseSwing: Float = 0.08f + rng.nextFloat() * 0.14f
    private val cruiseBase: Float = 0.90f + rng.nextFloat() * 0.12f
    private val chaseActivity: Float = 1.55f + rng.nextFloat() * 0.40f
    private val burstActivity: Float = 1.35f + rng.nextFloat() * 0.30f
    private val tailTempo: Float = 0.88f + rng.nextFloat() * 0.24f
    private val beatWobbleRate: Float = 0.30f + rng.nextFloat() * 0.50f
    private val beatWobbleAmount: Float = 0.02f + rng.nextFloat() * 0.05f
    private val turnRate: Float = 3.5f + rng.nextFloat() * 4.0f
    private val steeringRate: Float = 1.4f + rng.nextFloat() * 1.8f
    private val verticalShare: Float = 0.62f + rng.nextFloat() * 0.28f
    private val weaveRate: Float = 0.8f + rng.nextFloat() * 0.9f
    private val weaveStrength: Float = 0.16f + rng.nextFloat() * 0.18f
    private val retargetMin: Float = 1.4f + rng.nextFloat() * 2.4f
    private val retargetMax: Float = retargetMin + 0.6f + rng.nextFloat() * 2.2f
    private val tripMinFraction: Float = 0.06f + rng.nextFloat() * 0.28f
    private val pauseChance: Float = rng.nextFloat() * 0.55f
    private val depthRate: Float = 0.28f + rng.nextFloat() * 0.30f
    private var pauseRemaining = 0f
    private val minX = 26f + baseDrawWidth * 0.5f
    private val maxX = width - 26f - baseDrawWidth * 0.5f
    private val minY = 34f + baseDrawHeight * 0.45f
    private val maxY = sandY - 30f
    private var targetX = x
    private var targetY = y
    private var retargetIn = 0.4f + rng.nextFloat() * 4f
    private var burstRemaining = 0f
    private var untilNextBurst = 3f + rng.nextFloat() * 6f

    init {
        pickTarget(rng)
        ensureBounds()
    }

    fun update(dt: Float, food: Food?, rng: Random) {
        age += dt
        justAte = false
        val chasing = food != null && !food.eaten && food.y < sandY - 6f
        if (chasing) {
            pauseRemaining = 0f
            targetX = food!!.x
            targetY = food.y
            val distance = hypot(food.x - x, food.y - y)
            if (distance < drawHeight * 0.22f) {
                food.eaten = true
                justAte = true
            }
        } else if (pauseRemaining > 0f) {
            // Fish pause independently at waypoints instead of every fish turning
            // and reversing on the same beat.
            pauseRemaining -= dt
        } else {
            retargetIn -= dt
            val distance = hypot(targetX - x, targetY - y)
            if (retargetIn <= 0f || distance < 18f) {
                if (distance < 18f && rng.nextFloat() < pauseChance) {
                    pauseRemaining = 0.5f + rng.nextFloat() * 1.8f
                } else {
                    pickTarget(rng)
                    retargetIn = retargetMin + rng.nextFloat() * (retargetMax - retargetMin)
                }
            }
        }

        if (burstRemaining > 0f) {
            burstRemaining -= dt
        } else {
            untilNextBurst -= dt
            if (untilNextBurst <= 0f) {
                burstRemaining = 0.8f + rng.nextFloat() * 1.1f
                untilNextBurst = 4f + rng.nextFloat() * 6f
            }
        }
        val targetActivity = when {
            chasing -> chaseActivity
            pauseRemaining > 0f -> 0.32f
            burstRemaining > 0f -> burstActivity
            else -> cruiseBase + cruiseSwing * sin(age * cruiseTempo + phase)
        }
        activity += (targetActivity - activity) * (dt * 2.5f).coerceAtMost(1f)

        depth += (targetDepth - depth) * (dt * depthRate).coerceAtMost(1f)
        val desiredSpeed = drawWidth * cruiseFactor * (0.85f + depth * 0.15f) * activity
        val dx = targetX - x
        val dy = targetY - y
        val length = max(1f, hypot(dx, dy))
        val directionX = dx / length
        val directionY = dy / length
        // A slow lateral weave keeps the path curved instead of a straight glide.
        val weave = sin(age * weaveRate + phase) * desiredSpeed * weaveStrength
        val desiredVx = directionX * desiredSpeed - directionY * weave
        val desiredVy = directionY * desiredSpeed * verticalShare + directionX * weave * 0.65f
        val steering = (dt * steeringRate).coerceAtMost(1f)
        vx += (desiredVx - vx) * steering
        vy += (desiredVy - vy) * steering

        x += vx * dt
        y += vy * dt

        if (x < minX) {
            x = minX
            vx = abs(vx) * 0.8f
            targetX = clampX(width * 0.6f)
        }
        if (x > maxX) {
            x = maxX
            vx = -abs(vx) * 0.8f
            targetX = clampX(width * 0.4f)
        }
        if (y < minY) {
            y = minY
            vy = abs(vy) * 0.6f
            targetY = clampY(maxY * 0.7f)
        }
        if (y > maxY) {
            y = maxY
            vy = -abs(vy) * 0.6f
            targetY = clampY(minY + 20f)
        }

        if (vx > 8f) facingRight = true
        if (vx < -8f) facingRight = false
        // Turn through edge-on instead of instantly mirroring the sprite. The
        // horizontal scale eases from +1 to -1 over ~0.4 s, so a direction change
        // looks like the fish banking around, not flipping a card.
        val targetScale = if (facingRight) 1f else -1f
        turnScaleX += (targetScale - turnScaleX) * (dt * turnRate).coerceAtMost(1f)
        // Tail-beat frequency follows swim speed, not a random timer. A fish
        // advances roughly 0.8 body lengths per tail beat, so a fish that barely
        // moves cannot keep beating its tail at full speed.
        val speed = hypot(vx, vy)
        val baseBeat = speed / (drawWidth * 0.8f) * tailTempo
        val beatWobble = 1f + beatWobbleAmount * sin(age * beatWobbleRate + phase * 1.7f)
        tailHz = (baseBeat * beatWobble).coerceIn(0.45f, 1.8f)
        tailPhase = (tailPhase + dt * tailHz * 6.2831855f) % 6.2831855f
        val desiredTilt = atan2(vy, abs(vx) + 18f).coerceIn(-0.22f, 0.22f)
        // Smooth the pitch instead of snapping the whole sprite around; sudden
        // whole-body rotations are what made the old tank look like a seizure.
        tilt += (desiredTilt - tilt) * (dt * 4.5f).coerceAtMost(1f)
    }

    private fun pickTarget(rng: Random) {
        if (minX > maxX || minY > maxY) {
            targetX = width * 0.5f
            targetY = (minY + maxY) * 0.5f
            return
        }
        // Prefer a genuinely different part of the tank, but let each fish choose
        // its own trip length. Some hover locally; others cross the whole tank.
        val minimumTrip = width * tripMinFraction
        repeat(5) {
            targetX = minX + rng.nextFloat() * (maxX - minX)
            targetY = minY + rng.nextFloat() * (maxY - minY)
            targetDepth = pickDepth(rng)
            // Weight vertical distance so fish explore the water column instead
            // of satisfying every trip with a purely horizontal move.
            val trip = hypot(targetX - x, (targetY - y) * 1.8f)
            if (trip >= minimumTrip) return
        }
        // If the tank is tiny, go to the opposite corner.
        targetX = if (x < width * 0.5f) maxX else minX
        targetY = if (y < (minY + maxY) * 0.5f) maxY else minY
        targetDepth = pickDepth(rng)
    }

    /**
     * Bias waypoints toward the far and near glass. The occasional middle depth
     * keeps it from looking like fish only inhabit two flat planes.
     */
    private fun pickDepth(rng: Random): Float {
        if (rng.nextFloat() >= 0.65f) return 0.2f + rng.nextFloat() * 0.8f
        return if (rng.nextBoolean()) {
            0.08f + rng.nextFloat() * 0.27f
        } else {
            0.70f + rng.nextFloat() * 0.30f
        }
    }

    private fun clampX(value: Float): Float =
        if (minX <= maxX) value.coerceIn(minX, maxX) else width * 0.5f

    private fun clampY(value: Float): Float =
        if (minY <= maxY) value.coerceIn(minY, maxY) else (minY + maxY) * 0.5f

    private fun ensureBounds() {
        x = clampX(x)
        targetX = clampX(targetX)
        y = clampY(y)
        targetY = clampY(targetY)
    }

    private companion object {
        const val SUPERSAMPLE = 2f
    }

    private fun normalise(src: Bitmap, mirror: Boolean, targetHeight: Int, maxWidth: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) throw IllegalArgumentException("Empty fish bitmap")
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (Color.alpha(pixels[row + x]) > 12) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) throw IllegalArgumentException("Fish has no visible pixels")
        var result = Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
        val scaleForHeight = targetHeight / result.height.toFloat()
        val scaleForWidth = maxWidth / result.width.toFloat()
        val scale = min(scaleForHeight, scaleForWidth)
        val targetWidth = max(1, (result.width * scale).roundToInt())
        val targetHeightPx = max(1, (result.height * scale).roundToInt())
        result = Bitmap.createScaledBitmap(result, targetWidth, targetHeightPx, true)
        if (mirror) {
            val m = Matrix().apply { setScale(-1f, 1f) }
            result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, m, false)
        }
        return result
    }
}
