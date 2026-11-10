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
    fun adjacentBricksHaveThinDarkJoints() {
        val red = ArgbImage.argb(255, 201, 26, 9)
        val horizontal = BrickRenderer.render(ArgbImage(2, 1, intArrayOf(red, red)))
        val jointX = horizontal.width / 2 - 1
        val midY = horizontal.height / 2
        val joint = horizontal.pixels[midY * horizontal.width + jointX]
        val face = horizontal.pixels[midY * horizontal.width + jointX - 4]
        assertTrue("horizontal brick joint is not dark: ${colourSum(joint)}", colourSum(joint) < 120)
        assertTrue("brick face was darkened too much: ${colourSum(face)}", colourSum(face) > 200)

        val vertical = BrickRenderer.render(ArgbImage(1, 2, intArrayOf(red, red)))
        val jointY = vertical.height / 2 - 1
        val midX = vertical.width / 2
        val horizontalJoint = vertical.pixels[jointY * vertical.width + midX]
        assertTrue("vertical brick joint is not dark: ${colourSum(horizontalJoint)}", colourSum(horizontalJoint) < 120)
    }

    @Test
    fun silhouetteBricksAlsoHaveDarkJoints() {
        val width = 120
        val height = 60
        val fill = ArgbImage.argb(255, 232, 232, 228)
        val source = ArgbImage(width, height, IntArray(width * height) { fill })
        val sprite = BrickRenderer.renderSilhouette(source, columns = 4, rows = 2)
        // 4 columns over 120 px puts the first shared edge at x=29.
        val joint = sprite.pixels[30 * width + 29]
        assertTrue("silhouette brick joint is not dark: ${colourSum(joint)}", colourSum(joint) < 120)
    }

    private fun colourSum(color: Int): Int =
        ArgbImage.red(color) + ArgbImage.green(color) + ArgbImage.blue(color)

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
