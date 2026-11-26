package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real scene with Robolectric's native Skia graphics. This catches
 * blank frames, broken Canvas code and dead animation without needing the TV.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AquariumSceneTest {

    @Test
    fun themesRebuildDistinctLayoutsWithoutLosingFish() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        scene.addFish(
            FishMeta("theme-fish", "Luna", "Mia", "right", 1L, 120, 70),
            fishBitmap(),
            announce = false
        )
        val reef = render(scene, steps = 2)
        scene.setTheme(AquariumTheme.KELP_FOREST, announce = false)
        assertEquals(AquariumTheme.KELP_FOREST, scene.currentTheme)
        val kelp = render(scene, steps = 2)
        assertTrue("theme did not change the aquascape", differingPixels(reef, kelp) > 2_000)
        assertEquals(1, scene.fish.size)

        val next = scene.cycleTheme(1)
        assertEquals(AquariumTheme.SUNKEN_WRECK, next)
        assertEquals(AquariumTheme.SUNKEN_WRECK, scene.currentTheme)
        val wreck = render(scene, steps = 2)
        assertTrue("next theme did not change the aquascape", differingPixels(kelp, wreck) > 2_000)

        scene.setTheme(AquariumTheme.REEF, announce = false)
        assertEquals(
            "reverse cycling must wrap to the last theme",
            AquariumTheme.RIVERBED,
            scene.cycleTheme(-1)
        )
        assertEquals(AquariumTheme.RIVERBED, scene.currentTheme)
    }

    @Test
    fun foodSinksExpiresAndFeedingRecovers() {
        val scene = AquariumScene(512, 288)
        scene.setLoaded(true)
        scene.feed()
        val initial = scene.foodPositions()
        assertTrue("feeding produced no pellets", initial.isNotEmpty())

        repeat(30) { scene.update(1f / 30f) }
        val sinking = scene.foodPositions()
        assertEquals(initial.size, sinking.size)
        for (index in initial.indices) {
            assertTrue(
                "pellet $index did not sink: ${initial[index]} -> ${sinking[index]}",
                sinking[index] > initial[index]
            )
        }

        repeat(45 * 30) { scene.update(1f / 30f) }
        assertTrue(
            "pellets accumulated at the surface and would block future feeding",
            scene.foodPositions().isEmpty()
        )
        scene.feed()
        assertTrue("feeding did not recover after the pellets expired", scene.foodPositions().isNotEmpty())
    }

    @Test
    fun aiBackdropsRenderFourDistinctScenes() {
        val resources = RuntimeEnvironment.getApplication().resources
        val resourcesForTheme = mapOf(
            AquariumTheme.REEF to R.drawable.background_reef,
            AquariumTheme.KELP_FOREST to R.drawable.background_kelp,
            AquariumTheme.SUNKEN_WRECK to R.drawable.background_wreck,
            AquariumTheme.RIVERBED to R.drawable.background_river
        )
        val backdrops = resourcesForTheme.mapValues { (_, resource) ->
            BitmapFactory.decodeResource(resources, resource)
        }
        for (bitmap in backdrops.values) {
            assertNotNull("AI backdrop failed to decode", bitmap)
        }
        val rendered = LinkedHashMap<AquariumTheme, Bitmap>()
        for (theme in AquariumTheme.values()) {
            val scene = AquariumScene(640, 360, backdrops)
            scene.setTheme(theme, announce = false)
            scene.setLoaded(true)
            scene.addFish(
                FishMeta("ai-${theme.name}", "Luna", "Mia", "right", 1L, 120, 70),
                fishBitmap(),
                announce = false
            )
            val frame = render(scene, steps = 3)
            rendered[theme] = frame
            savePng(frame, "ai-${theme.name.lowercase()}.png")
        }
        assertTrue(
            "reef and kelp backdrops rendered the same",
            differingPixels(rendered.getValue(AquariumTheme.REEF), rendered.getValue(AquariumTheme.KELP_FOREST)) > 20_000
        )
        assertTrue(
            "wreck and river backdrops rendered the same",
            differingPixels(rendered.getValue(AquariumTheme.SUNKEN_WRECK), rendered.getValue(AquariumTheme.RIVERBED)) > 20_000
        )
    }

    @Test
    fun duplicateFishIdsCannotBeAddedOrResurrected() {
        val scene = AquariumScene(640, 360)
        val meta = FishMeta("same-id", "Luna", "Mia", "right", 1L, 120, 70)
        val bitmap = fishBitmap()
        assertNotNull(scene.addFish(meta, bitmap, announce = false))
        assertNull(scene.addFish(meta, bitmap, announce = false))
        assertEquals(1, scene.fish.size)

        scene.removeFish(meta.id)
        assertTrue(scene.fish.isEmpty())
        assertNull("a deleted fish must not be re-added by a stale callback", scene.addFish(meta, bitmap, announce = false))
        assertTrue(scene.fish.isEmpty())
    }

    @Test
    fun unreadableFishIsSkippedInsteadOfCrashingTheTank() {
        val scene = AquariumScene(512, 288)
        val empty = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val added = scene.addFish(
            FishMeta("broken", "Broken", "", "right", 1L, 40, 40),
            empty,
            announce = true
        )
        assertNull("a fully transparent sprite must be rejected", added)
        scene.setLoaded(true)
        render(scene, steps = 2)
    }

    @Test
    fun sceneDrawsDecorFishAndAnimation() {
        val scene = AquariumScene(640, 360)
        scene.setLoaded(true)
        scene.setServerInfo("192.0.2.10", 8765, true)

        val empty = render(scene, steps = 6)
        savePng(empty, "tv-empty.png")

        scene.addFish(
            FishMeta("test", "Luna", "Mia", "right", 1L, 120, 70),
            fishBitmap(),
            announce = false
        )
        val withFish = render(scene, steps = 1)
        savePng(withFish, "tv-fish.png")

        val animated = render(scene, steps = 80)
        savePng(animated, "tv-animated.png")

        assertTrue("scene should not contain letterbox black bars", nonBlackFraction(empty) > 0.97f)
        assertTrue("pixel-art palette should be rich", distinctColours(withFish) > 60)
        assertTrue("adding a fish must change the frame", differingPixels(empty, withFish) > 300)
        assertTrue("the scene must animate over time", differingPixels(withFish, animated) > 500)
        assertTrue("the fish sprite should be visible", magentaPixels(animated) > 40)
        assertTrue("the fish eye/details were lost during mesh warping", whitePixels(animated) > 5)
    }

    private fun render(scene: AquariumScene, steps: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        repeat(steps) { scene.update(1f / 30f) }
        scene.draw(canvas)
        return bitmap
    }

    private fun fishBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(120, 70, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(255, 0, 220)
        canvas.drawOval(24f, 16f, 104f, 54f, paint)
        val tail = Path().apply {
            moveTo(26f, 35f)
            lineTo(2f, 14f)
            lineTo(2f, 56f)
            close()
        }
        canvas.drawPath(tail, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(90f, 28f, 7f, paint)
        paint.color = Color.rgb(20, 30, 34)
        canvas.drawCircle(90f, 28f, 3f, paint)
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    private fun nonBlackFraction(bitmap: Bitmap): Float {
        val pixels = pixels(bitmap)
        var visible = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r + g + b > 20) visible++
        }
        return visible / pixels.size.toFloat()
    }

    private fun distinctColours(bitmap: Bitmap): Int {
        val pixels = pixels(bitmap)
        val colours = HashSet<Int>()
        var index = 0
        while (index < pixels.size) {
            colours.add(pixels[index])
            index += 3
        }
        return colours.size
    }

    private fun differingPixels(first: Bitmap, second: Bitmap): Int {
        val a = pixels(first)
        val b = pixels(second)
        var different = 0
        for (i in a.indices) if (a[i] != b[i]) different++
        return different
    }

    private fun magentaPixels(bitmap: Bitmap): Int {
        return pixels(bitmap).count { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            r > 170 && b > 150 && g < 110
        }
    }

    private fun whitePixels(bitmap: Bitmap): Int {
        return pixels(bitmap).count { pixel ->
            ((pixel shr 16) and 0xFF) > 250 &&
                ((pixel shr 8) and 0xFF) > 250 &&
                (pixel and 0xFF) > 250
        }
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        runCatching {
            val directory = File("build/test-artifacts").apply { mkdirs() }
            TestPng.writePixels(bitmap.width, bitmap.height, pixels(bitmap), File(directory, name))
        }
    }
}
