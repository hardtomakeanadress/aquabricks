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
    private val maxDrawHeight: Float = (40f + 22f) * (height / 360f)
    // Pre-scale to the exact maximum on-screen size: drawBitmapMesh then samples
    // a tiny bitmap every frame instead of a 220 px one, which matters with many
    // fish. The width cap prevents a very long, thin scan from exceeding the tank.
    val sprite: Bitmap = normalise(
        bitmap,
        mirror = meta.facing.equals("left", ignoreCase = true),
        targetHeight = maxDrawHeight.roundToInt().coerceAtLeast(9),
        maxWidth = (width * 0.38f).roundToInt().coerceAtLeast(9)
    )
    private val baseDrawHeight: Float = sprite.height.toFloat()
    private val baseDrawWidth: Float = sprite.width.toFloat()
    private val depthScale: Float get() = 0.68f + 0.32f * depth
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
    var activity: Float = 1f
    var tailHz: Float = 1f
        private set
    /**
     * Accumulated tail phase. Multiplying absolute uptime by a changing frequency
     * would make the phase jump whenever the fish accelerates or slows.
     */
    var tailPhase: Float = 0f
        private set

    // Swim speed scales with apparent body length. A 0.8-body stride keeps the
    // tail beat and the distance travelled in the same physical relationship.
    private val cruiseFactor: Float = 0.65f + rng.nextFloat() * 0.25f
    private val minX = 26f + baseDrawWidth * 0.5f
    private val maxX = width - 26f - baseDrawWidth * 0.5f
    private val minY = 34f + baseDrawHeight * 0.45f
    private val maxY = sandY - 30f
    private var targetX = x
    private var targetY = y
    private var retargetIn = rng.nextFloat() * 3f
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
            targetX = food!!.x
            targetY = food.y
            val distance = hypot(food.x - x, food.y - y)
            if (distance < drawHeight * 0.22f) {
                food.eaten = true
                justAte = true
            }
        } else {
            retargetIn -= dt
            val distance = hypot(targetX - x, targetY - y)
            if (retargetIn <= 0f || distance < 18f) {
                pickTarget(rng)
                retargetIn = 1.8f + rng.nextFloat() * 3.4f
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
            chasing -> 1.9f
            burstRemaining > 0f -> 1.55f
            else -> 0.95f + 0.08f * sin(age * 0.7f + phase)
        }
        activity += (targetActivity - activity) * (dt * 2.5f).coerceAtMost(1f)

        depth += (targetDepth - depth) * (dt * 0.55f).coerceAtMost(1f)
        val desiredSpeed = drawWidth * cruiseFactor * (0.9f + depth * 0.1f) * activity
        val dx = targetX - x
        val dy = targetY - y
        val length = max(1f, hypot(dx, dy))
        val directionX = dx / length
        val directionY = dy / length
        // A slow lateral weave keeps the path curved instead of a straight glide.
        val weave = sin(age * 1.25f + phase) * desiredSpeed * 0.22f
        val desiredVx = directionX * desiredSpeed - directionY * weave
        val desiredVy = directionY * desiredSpeed * 0.62f + directionX * weave * 0.65f
        val steering = (dt * 2.2f).coerceAtMost(1f)
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
        turnScaleX += (targetScale - turnScaleX) * (dt * 6f).coerceAtMost(1f)
        // Tail-beat frequency follows swim speed, not a random timer. A fish
        // advances roughly 0.8 body lengths per tail beat, so a fish that barely
        // moves cannot keep beating its tail at full speed.
        val speed = hypot(vx, vy)
        tailHz = (speed / (drawWidth * 0.8f)).coerceIn(0.45f, 1.8f)
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
        // Prefer a genuinely different part of the tank. This prevents a fish
        // from orbiting one corner forever and makes two or three fish explore.
        val minimumTrip = width * 0.22f
        repeat(5) {
            targetX = minX + rng.nextFloat() * (maxX - minX)
            targetY = minY + rng.nextFloat() * (maxY - minY)
            targetDepth = 0.2f + rng.nextFloat() * 0.8f
            if (hypot(targetX - x, targetY - y) >= minimumTrip) return
        }
        // If the tank is tiny, go to the opposite corner.
        targetX = if (x < width * 0.5f) maxX else minX
        targetY = if (y < (minY + maxY) * 0.5f) maxY else minY
        targetDepth = 0.2f + rng.nextFloat() * 0.8f
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
        result = Bitmap.createScaledBitmap(result, targetWidth, targetHeightPx, false)
        if (mirror) {
            val m = Matrix().apply { setScale(-1f, 1f) }
            result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, m, false)
        }
        return result
    }
}
