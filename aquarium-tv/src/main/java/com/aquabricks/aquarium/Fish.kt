package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
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
    /** Vertical kelp stems the fish gently steers around instead of crossing. */
    var obstacleXs: FloatArray = FloatArray(0)
    /** Set while the shark is in the tank; fish push away from its mouth. */
    var threatX: Float = Float.NaN
    var threatY: Float = Float.NaN

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
    // Real tank fish glide in wide, committed arcs. A low angular turn rate keeps
    // a fish from reversing direction a second after its last turn.
    private val turnRateRad: Float = 0.9f + rng.nextFloat() * 0.8f
    private val flipRate: Float = 4f + rng.nextFloat() * 2f
    private val acceleration: Float = 0.9f + rng.nextFloat() * 0.8f
    private val weaveRate: Float = 0.8f + rng.nextFloat() * 0.9f
    private val weaveAngle: Float = 0.04f + rng.nextFloat() * 0.10f
    private val retargetMin: Float = 5f + rng.nextFloat() * 4f
    private val retargetMax: Float = retargetMin + 2f + rng.nextFloat() * 4f
    private val tripMinFraction: Float = 0.12f + rng.nextFloat() * 0.20f
    private val pauseChance: Float = 0.40f + rng.nextFloat() * 0.40f
    private val depthRate: Float = 0.28f + rng.nextFloat() * 0.30f
    private var pauseRemaining = 0f
    // Bounds follow the fish's current projected size, not its maximum size.
    // A far, small fish can therefore reach the glass; the old fixed margins
    // left it stopping at an invisible wall well inside the tank.
    private val minX: Float get() = drawWidth * 0.5f + 2f
    private val maxX: Float get() = width - drawWidth * 0.5f - 2f
    private val minY: Float get() = drawHeight * 0.5f + 2f + (1f - depth) * height * 0.10f
    private val maxY: Float get() = sandY - drawHeight * 0.5f - 2f
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
            val mouthDistance = hypot(food.x - mouthX(), food.y - mouthY())
            if (mouthDistance < max(12f, drawHeight * 0.28f)) {
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
                    pauseRemaining = 1f + rng.nextFloat() * 3f
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
            pauseRemaining > 0f -> 0.22f
            burstRemaining > 0f -> burstActivity
            else -> cruiseBase + cruiseSwing * sin(age * cruiseTempo + phase)
        }
        activity += (targetActivity - activity) * (dt * 2.5f).coerceAtMost(1f)

        depth += (targetDepth - depth) * (dt * depthRate).coerceAtMost(1f)
        var desiredSpeed = drawWidth * cruiseFactor * (0.85f + depth * 0.15f) * activity
        if (chasing && hypot(targetX - x, targetY - y) < drawWidth * 0.5f) {
            // Approach a pellet slowly and let suction do the last few pixels
            // instead of orbiting it at full cruise speed.
            desiredSpeed *= 0.65f
        }
        // Only start easing away in the last few pixels before the glass; the
        // previous 10%-of-tank margin made fish turn around far from the edge.
        val edgeX = 3f
        val edgeY = 3f
        val nearEdge = x < minX + edgeX || x > maxX - edgeX ||
            y < minY + edgeY || y > maxY - edgeY
        if (x < minX + edgeX && vx < 0f) targetX = max(targetX, width * 0.55f)
        if (x > maxX - edgeX && vx > 0f) targetX = min(targetX, width * 0.45f)
        if (y < minY + edgeY && vy < 0f) targetY = max(targetY, (minY + maxY) * 0.5f)
        if (y > maxY - edgeY && vy > 0f) targetY = min(targetY, (minY + maxY) * 0.5f)
        if (nearEdge) desiredSpeed *= 0.72f

        val dx = targetX - x
        val dy = targetY - y
        val targetHeading = atan2(dy, dx)
        val currentSpeed = hypot(vx, vy)
        val currentHeading = if (currentSpeed > 4f) atan2(vy, vx) else targetHeading
        val weave = sin(age * weaveRate + phase) * weaveAngle
        val headingChange = normalizeAngle(targetHeading + weave - currentHeading)
        // A hungry fish may turn a little faster, but still follows an arc.
        val turnLimit = if (chasing) max(turnRateRad, 2.0f) else turnRateRad
        val maxTurn = turnLimit * dt
        val heading = currentHeading + headingChange.coerceIn(-maxTurn, maxTurn)
        val desiredVx = cos(heading) * desiredSpeed
        val desiredVy = sin(heading) * desiredSpeed
        val easing = (dt * acceleration).coerceAtMost(1f)
        vx += (desiredVx - vx) * easing
        vy += (desiredVy - vy) * easing

        // Give a few tall kelp stems a soft collision field. The fish curves
        // around them when it happens to pass close, then continues to its
        // target; it is not trapped on one side of the tank.
        val avoidRadius = max(24f, drawWidth * 0.55f)
        for (obstacleX in obstacleXs) {
            val dx = x - obstacleX
            val distance = abs(dx)
            if (distance < avoidRadius) {
                val side = if (distance < 0.5f) {
                    if (facingRight) -1f else 1f
                } else {
                    if (dx > 0f) 1f else -1f
                }
                val strength = (1f - distance / avoidRadius) * desiredSpeed * 1.4f
                vx += side * strength * dt
            }
        }

        // Panic response: a nearby shark pushes the fish away and makes it beat
        // harder. The push is a force, not an instant teleport, so slower fish can
        // still be caught while the rest scatter naturally.
        if (!threatX.isNaN()) {
            val awayX = x - threatX
            val awayY = y - threatY
            val distance = hypot(awayX, awayY)
            val fleeRadius = max(80f, drawWidth * 3.4f)
            if (distance < fleeRadius) {
                val push = 1f - distance / fleeRadius
                val inv = 1f / max(distance, 1f)
                vx += awayX * inv * desiredSpeed * 1.9f * push * dt
                vy += awayY * inv * desiredSpeed * 1.9f * push * dt
            }
        }

        x += vx * dt
        y += vy * dt

        if (x < minX) {
            x = minX
            vx = max(0f, vx)
        }
        if (x > maxX) {
            x = maxX
            vx = min(0f, vx)
        }
        if (y < minY) {
            y = minY
            vy = max(0f, vy)
        }
        if (y > maxY) {
            y = maxY
            vy = min(0f, vy)
        }

        if (vx > 8f) facingRight = true
        if (vx < -8f) facingRight = false
        // Turn through edge-on instead of instantly mirroring the sprite. The
        // horizontal scale eases from +1 to -1 over ~0.4 s, so a direction change
        // looks like the fish banking around, not flipping a card.
        val targetScale = if (facingRight) 1f else -1f
        turnScaleX += (targetScale - turnScaleX) * (dt * flipRate).coerceAtMost(1f)
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
        // its own trip length. Most waypoints lie ahead of the current heading;
        // only occasionally does a fish commit to crossing back the other way.
        val minimumTrip = width * tripMinFraction
        val forward = if (vx >= 0f || (abs(vx) < 1f && facingRight)) 1f else -1f
        val preferAhead = rng.nextFloat() < 0.74f
        repeat(5) {
            targetX = if (preferAhead) {
                (x + forward * width * (0.12f + rng.nextFloat() * 0.34f))
                    .coerceIn(minX, maxX)
            } else {
                minX + rng.nextFloat() * (maxX - minX)
            }
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

    private fun normalizeAngle(angle: Float): Float {
        var value = angle
        val tau = 6.2831855f
        while (value > 3.1415927f) value -= tau
        while (value < -3.1415927f) value += tau
        return value
    }

    /** World position of the mouth as rendered, including facing and pitch. */
    internal fun mouthX(): Float {
        val local = drawWidth * 0.42f * turnScaleX
        return x + local * cos(tilt)
    }

    internal fun mouthY(): Float {
        val local = drawWidth * 0.42f * turnScaleX
        // Match the renderer's depth lift and bob so a pellet disappears exactly
        // where the visible mouth is, not at the unprojected simulation point.
        val visualY = y - (1f - depth) * height * 0.10f + sin(age * 1.35f + phase) * 0.8f
        return visualY + local * sin(tilt)
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
