package com.aquabricks.scanner.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrickRendererTest {

    @Test
    fun cellGridBecomesAStuddedBrickSurface() {
        val cells = ArgbImage(
            2, 2,
            intArrayOf(
                ArgbImage.argb(255, 201, 26, 9), ArgbImage.argb(255, 201, 26, 9),
                ArgbImage.argb(255, 201, 26, 9), 0
            )
        )
        val sprite = BrickRenderer.render(cells)
        assertTrue(sprite.width >= 20 && sprite.height >= 20)
        assertTrue(ArgbImage.alpha(sprite.pixels[(sprite.height / 4) * sprite.width + sprite.width / 4]) > 0)
        val colours = sprite.pixels.filter { ArgbImage.alpha(it) > 0 }.toHashSet()
        assertTrue("the brick should have shaded faces and studs", colours.size >= 4)
        assertEquals(0, ArgbImage.alpha(sprite.pixels.last()))
    }

    @Test
    fun silhouetteFallbackKeepsSmallTabsAndAddsStuds() {
        val width = 120
        val height = 60
        val fill = ArgbImage.argb(255, 232, 232, 228)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until 100) pixels[y * width + x] = fill
        }
        for (y in 22 until 38) {
            for (x in 100 until width) pixels[y * width + x] = fill
        }
        val source = ArgbImage(width, height, pixels)
        val sprite = BrickRenderer.renderSilhouette(source, columns = 4, rows = 2)
        assertEquals(width, sprite.width)
        assertEquals(height, sprite.height)
        assertTrue("body survived", ArgbImage.alpha(sprite.pixels[30 * width + 40]) > 0)
        assertTrue("small tail tab was cropped", ArgbImage.alpha(sprite.pixels[30 * width + 112]) > 0)
        assertEquals("outside the silhouette must stay transparent", 0, ArgbImage.alpha(sprite.pixels[5 * width + 110]))
        val colours = sprite.pixels.filter { ArgbImage.alpha(it) > 0 }.toHashSet()
        assertTrue("stud shading missing", colours.size >= 4)
    }
}
