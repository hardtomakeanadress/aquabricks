package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharkTest {

    @Test
    fun sharkEmergesFromTheMurkInsideTheTankAndRecedes() {
        val shark = Shark(640f, 360f, 302f, sprite(), Random(5))
        shark.spawn(fromRight = true, targetY = 150f)
        assertFalse("shark should face left when swimming from the right", shark.facingRight)
        assertTrue("shark must start inside the water, not beyond the glass", shark.x in 0f..640f)
        assertTrue("shark should start far back in the murk", shark.depth < 0.15f)
        val farWidth = shark.drawWidth

        var elapsed = 0f
        var maxDepth = shark.depth
        var maxWidth = shark.drawWidth
        while (shark.active && elapsed < 40f) {
            shark.update(1f / 30f, 150f)
            elapsed += 1f / 30f
            assertTrue("shark centre escaped the tank: ${shark.x}", shark.x in 0f..640f)
            assertTrue("tail beat out of range: ${shark.tailHz}", shark.tailHz in 0.42f..0.90f)
            maxDepth = maxOf(maxDepth, shark.depth)
            maxWidth = maxOf(maxWidth, shark.drawWidth)
        }
        assertFalse("shark never receded", shark.active)
        assertTrue("visit took $elapsed s", elapsed in 5f..25f)
        assertTrue("shark never came forward from the murk", maxDepth > 0.9f)
        assertTrue("shark never grew to full size", maxWidth > farWidth * 1.8f)
    }

    @Test
    fun mouthSitsAheadOfTheBodyAndBelowTheCentreLine() {
        val shark = Shark(640f, 360f, 302f, sprite(), Random(6))
        shark.spawn(fromRight = false, targetY = 150f)
        assertTrue(shark.facingRight)
        assertEquals(shark.drawWidth * 0.36f, shark.mouthX() - shark.x, 1f)
        assertEquals(shark.drawHeight * 0.15f, shark.mouthY() - shark.visualY(), 1f)
    }

    @Test
    fun sceneSharkEatsAtTheMouthAndRemovesTheFish() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        val first = scene.addFish(
            FishMeta("shark-a", "Snack", "", "right", 1L, 120, 70),
            fishBitmap(),
            announce = false
        )
        scene.addFish(
            FishMeta("shark-b", "Bystander", "", "right", 2L, 120, 70),
            fishBitmap(),
            announce = false
        )
        var eaten: FishMeta? = null
        scene.onFishEaten = { eaten = it }
        scene.startSharkForTest()
        scene.placeSharkMouthForTest(first!!.x, first.y)

        scene.update(1f / 30f)

        assertNotNull("the shark did not take a fish at its mouth", eaten)
        assertEquals("shark-a", eaten!!.id)
        assertEquals("only the bitten fish should disappear", 1, scene.fish.size)
        assertTrue(scene.isSharkActiveForTest())
    }

    @Test
    fun sharkCatchesAtLeastOneFishDuringAnUnscriptedCrossing() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        repeat(5) { index ->
            scene.addFish(
                FishMeta("hunt-$index", "Fish $index", "", "right", index.toLong() + 1, 120, 70),
                fishBitmap(),
                announce = false
            )
        }
        var eaten = 0
        scene.onFishEaten = { eaten++ }
        scene.startSharkForTest()

        var elapsed = 0f
        while (elapsed < 30f && eaten == 0) {
            scene.update(1f / 30f)
            elapsed += 1f / 30f
        }
        assertTrue("shark crossed without catching anything in $elapsed s", eaten > 0)
    }

    @Test
    fun disabledSharkRetreatsWithoutEating() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        repeat(3) { index ->
            scene.addFish(
                FishMeta("toggle-$index", "Fish $index", "", "right", index.toLong() + 1, 120, 70),
                fishBitmap(),
                announce = false
            )
        }
        var eaten = 0
        scene.onFishEaten = { eaten++ }
        scene.setSharkEnabled(false)
        scene.startSharkForTest()
        assertTrue(scene.isSharkActiveForTest())

        repeat(120) { scene.update(1f / 30f) }

        assertFalse("disabled shark should leave the tank", scene.isSharkActiveForTest())
        assertEquals("disabled shark must not eat", 0, eaten)
        assertEquals(3, scene.fish.size)
    }

    @Test
    fun sharkLeavesTheLastFishAlone() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        val only = scene.addFish(
            FishMeta("shark-only", "Lucky", "", "right", 1L, 120, 70),
            fishBitmap(),
            announce = false
        )
        var eaten: FishMeta? = null
        scene.onFishEaten = { eaten = it }
        scene.startSharkForTest()
        scene.placeSharkMouthForTest(only!!.x, only.y)

        repeat(8) { scene.update(1f / 30f) }

        assertNull("the shark must not empty the tank", eaten)
        assertEquals(1, scene.fish.size)
    }

    @Test
    fun sharkAnimationFramesAreRendered() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        scene.addFish(
            FishMeta("anim-a", "A", "", "right", 1L, 120, 70),
            fishBitmap(),
            announce = false
        )
        scene.addFish(
            FishMeta("anim-b", "B", "", "right", 2L, 120, 70),
            fishBitmap(),
            announce = false
        )
        scene.startSharkForTest()
        scene.placeSharkMouthForTest(380f, 180f)
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val first = IntArray(640 * 360)
        var differences = 0
        repeat(6) { index ->
            scene.update(0.2f)
            canvas.drawColor(Color.BLACK)
            scene.draw(canvas)
            savePng(frame, "shark-anim-$index.png")
            val pixels = IntArray(640 * 360)
            frame.getPixels(pixels, 0, 640, 0, 0, 640, 360)
            if (index == 0) {
                pixels.copyInto(first)
            } else {
                differences += pixels.indices.count { pixels[it] != first[it] }
            }
        }
        assertTrue("shark frames did not animate: $differences differing pixels", differences > 20_000)
    }

    @Test
    fun sharkVisitFramesShowDepthEmergence() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        repeat(4) { index ->
            scene.addFish(
                FishMeta("visit-$index", "Fish $index", "", "right", index.toLong() + 1, 120, 70),
                fishBitmap(),
                announce = false
            )
        }
        scene.startSharkForTest()
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        repeat(20) { index ->
            scene.update(0.4f)
            canvas.drawColor(Color.BLACK)
            scene.draw(canvas)
            savePng(frame, "shark-visit-$index.png")
        }
    }

    @Test
    fun sharkSpriteIsPaintedWithAVisibleBody() {
        val bitmap = Shark.renderSprite()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val opaque = pixels.count { Color.alpha(it) > 200 }
        assertTrue("shark sprite is mostly empty ($opaque px)", opaque > bitmap.width * bitmap.height / 5)
        val highlight = pixels.count {
            Color.red(it) > 225 && Color.green(it) > 225 && Color.blue(it) > 220
        }
        assertTrue("shark sprite has no teeth or belly highlight", highlight > 200)
        savePng(bitmap, "shark-sprite.png")
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        val dir = File("build/test-artifacts").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sprite(): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 110, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(70, 95, 110)
        canvas.drawOval(10f, 10f, 290f, 100f, paint)
        return bitmap
    }

    private fun fishBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 70, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(245, 120, 60)
        canvas.drawOval(10f, 14f, 110f, 56f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(92f, 30f, 5f, paint)
        return bitmap
    }
}
