package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FishTest {

    @Test
    fun simulationNeverLeavesTheWater() {
        val fish = Fish(
            meta = FishMeta("bounded", "Bubbles", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(11)
        )
        val rng = Random(12)
        repeat(20_000) {
            fish.update(1f / 30f, null, rng)
            assertTrue("x drifted out of the tank: ${fish.x}", fish.x in 0f..640f)
            assertTrue("y drifted out of the water: ${fish.y}", fish.y in 0f..302f)
            assertTrue("non-finite velocity", fish.vx.isFinite() && fish.vy.isFinite())
            assertTrue("sprite was empty", fish.sprite.width > 0 && fish.sprite.height > 0)
            assertTrue("fish became invisible", fish.drawHeight > 0f)
        }
        assertFalse(abs(fish.vx) > 500f)
    }

    @Test
    fun hungryFishEatsNearbyFood() {
        val fish = Fish(
            meta = FishMeta("hungry", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(21)
        )
        val rng = Random(22)
        val food = Food(fish.x + 40f, fish.y + 10f, rng)
        var ate = false
        repeat(600) {
            fish.update(1f / 30f, food, rng)
            if (food.eaten || fish.justAte) {
                ate = true
                return@repeat
            }
        }
        assertTrue("fish never reached the food", ate)
    }

    @Test
    fun tailBeatIsSpeedMatchedAndPitchChangesSmoothly() {
        val fish = Fish(
            meta = FishMeta("swimmer", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(41)
        )
        val rng = Random(42)
        var previousTilt = fish.tilt
        var fastestCruise = 0f
        repeat(3_000) {
            fish.update(1f / 30f, null, rng)
            fastestCruise = maxOf(fastestCruise, abs(fish.vx))
            assertTrue("tail beat out of range: ${fish.tailHz}", fish.tailHz in 0.45f..1.8f)
            assertTrue(
                "whole-body pitch jumped ${abs(fish.tilt - previousTilt)} rad in one frame",
                abs(fish.tilt - previousTilt) <= 0.08f
            )
            previousTilt = fish.tilt
        }
        val speed = hypot(fish.vx, fish.vy)
        val base = speed / (fish.drawWidth * 0.8f)
        val lower = (base * 0.84f).coerceAtLeast(0.45f)
        val upper = (base * 1.22f).coerceAtMost(1.8f)
        assertTrue(
            "tail beat must still follow swim speed: base=$base actual=${fish.tailHz}",
            fish.tailHz in lower..upper
        )
        assertTrue(
            "cruise speed should scale with body length, peak was $fastestCruise",
            fastestCruise > 20f
        )
    }

    @Test
    fun fishDoNotBeatTheirTailsInLockstep() {
        val bitmap = sprite()
        val rng = Random(91)
        val school = (0 until 8).map { index ->
            Fish(
                meta = FishMeta("school-$index", "Nemo $index", "", "right", 1L, 120, 60),
                bitmap = bitmap,
                width = 640f,
                height = 360f,
                sandY = 302f,
                rng = rng
            )
        }
        val tau = 6.2831855f
        val initialOffsets = school.map { (it.tailPhase + it.phase) % tau }
        repeat(900) { school.forEach { it.update(1f / 30f, null, rng) } }
        val finalOffsets = school.map { (it.tailPhase + it.phase) % tau }
        var largestOffsetShift = 0f
        for (index in 0 until school.size - 1) {
            val before = initialOffsets[index + 1] - initialOffsets[index]
            val after = finalOffsets[index + 1] - finalOffsets[index]
            largestOffsetShift = maxOf(largestOffsetShift, abs(after - before))
        }
        assertTrue(
            "the school kept a fixed tail-phase relationship: shift=$largestOffsetShift",
            largestOffsetShift > 0.05f
        )
        val rates = school.map { it.tailHz }
        assertTrue(
            "all fish beat at nearly the same rate: ${rates.minOrNull()}..${rates.maxOrNull()}",
            rates.maxOrNull()!! - rates.minOrNull()!! > 0.02f
        )
    }

    @Test
    fun tailPhaseAdvancesSmoothlyWithoutLifetimeJumps() {
        val fish = Fish(
            meta = FishMeta("phase", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(81)
        )
        val rng = Random(82)
        val tau = 6.2831855f
        repeat(18_000) {
            val before = fish.tailPhase
            fish.update(1f / 30f, null, rng)
            val delta = (fish.tailPhase - before + tau) % tau
            val expected = tau * fish.tailHz / 30f
            assertEquals("tail phase must integrate the current beat rate", expected, delta, 0.0005f)
            assertTrue("tail phase jumped by $delta in one frame", delta <= tau * 1.8f / 30f + 0.001f)
        }
    }

    @Test
    fun fishSwimBetweenTheNearAndFarGlass() {
        val fish = Fish(
            meta = FishMeta("depth-explorer", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(101)
        )
        val rng = Random(102)
        var nearest = fish.depth
        var farthest = fish.depth
        var smallest = fish.drawHeight
        var largest = fish.drawHeight
        repeat(12_000) {
            fish.update(1f / 30f, null, rng)
            nearest = maxOf(nearest, fish.depth)
            farthest = minOf(farthest, fish.depth)
            smallest = minOf(smallest, fish.drawHeight)
            largest = maxOf(largest, fish.drawHeight)
        }
        assertTrue(
            "fish stayed on one depth plane: depth ${farthest}..${nearest}",
            nearest - farthest > 0.45f
        )
        assertTrue(
            "apparent size did not change with depth: $smallest..$largest",
            largest > smallest * 1.35f
        )
    }

    @Test
    fun depthChangesApparentSizeWithoutBreakingBounds() {
        val fish = Fish(
            meta = FishMeta("deep", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(71)
        )
        fish.depth = 0.2f
        val farHeight = fish.drawHeight
        fish.depth = 1f
        val nearHeight = fish.drawHeight
        assertTrue("near fish should be larger", nearHeight > farHeight * 1.3f)
        val rng = Random(72)
        repeat(2_000) {
            fish.update(1f / 30f, null, rng)
            assertTrue("depth escaped its range: ${fish.depth}", fish.depth in 0f..1f)
            assertTrue("x escaped the tank", fish.x in 0f..640f)
            assertTrue("y escaped the water", fish.y in 0f..302f)
        }
    }

    @Test
    fun fishExploreBothHalvesAndTheWaterColumn() {
        val fish = Fish(
            meta = FishMeta("explorer", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(61)
        )
        val rng = Random(62)
        var left = false
        var right = false
        var top = false
        var bottom = false
        repeat(8_000) {
            fish.update(1f / 30f, null, rng)
            if (fish.x < 640f * 0.4f) left = true
            if (fish.x > 640f * 0.6f) right = true
            if (fish.y < 302f * 0.45f) top = true
            if (fish.y > 302f * 0.75f) bottom = true
        }
        assertTrue("fish stayed on the left", right)
        assertTrue("fish stayed on the right", left)
        assertTrue("fish stayed at the bottom", top)
        assertTrue("fish stayed at the top", bottom)
    }

    @Test
    fun directionChangesTurnThroughEdgeOnInsteadOfSnapping() {
        val fish = Fish(
            meta = FishMeta("turner", "Nemo", "", "right", 1L, 120, 60),
            bitmap = sprite(),
            width = 640f,
            height = 360f,
            sandY = 302f,
            rng = Random(51)
        )
        val rng = Random(52)
        var previous = fish.turnScaleX
        var sawIntermediate = false
        var largestStep = 0f
        repeat(20_000) {
            fish.update(1f / 30f, null, rng)
            val scale = fish.turnScaleX
            largestStep = maxOf(largestStep, abs(scale - previous))
            if (abs(scale) in 0.05f..0.90f) sawIntermediate = true
            previous = scale
        }
        assertTrue("the fish never turned through an edge-on pose", sawIntermediate)
        assertTrue("the turn snapped by $largestStep", largestStep < 0.5f)
    }

    /**
     * Regression for the Chromecast crash: a long, thin scan (507x39 arrived
     * from the phone) used to produce a draw width wider than the 512 px tank.
     * Its valid x range became empty and coerceIn threw on every launch.
     */
    @Test
    fun veryLongThinFishFitsInsideTheTank() {
        val bitmap = Bitmap.createBitmap(600, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.rgb(220, 30, 30)
        canvas.drawRect(0f, 10f, 600f, 30f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(540f, 20f, 5f, paint)

        val fish = Fish(
            meta = FishMeta("thin", "Lazy", "Adi", "left", 1L, 507, 39),
            bitmap = bitmap,
            width = 512f,
            height = 288f,
            sandY = 250f,
            rng = Random(31)
        )

        assertTrue("thin sprite must be capped to the tank width", fish.drawWidth <= 512f * 0.30f + 1f)
        assertTrue("thin sprite should stay proportionate", fish.drawWidth > fish.drawHeight * 10f)
        val rng = Random(32)
        repeat(5_000) {
            fish.update(1f / 30f, null, rng)
            assertTrue("x escaped the tank: ${fish.x}", fish.x.isFinite() && fish.x in 0f..512f)
            assertTrue("y escaped the water: ${fish.y}", fish.y.isFinite() && fish.y in 0f..250f)
        }
    }

    private fun sprite(): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(255, 0, 220)
        canvas.drawOval(20f, 12f, 100f, 48f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(86f, 24f, 6f, paint)
        return bitmap
    }
}
