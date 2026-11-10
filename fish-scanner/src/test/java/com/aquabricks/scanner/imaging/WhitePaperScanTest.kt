package com.aquabricks.scanner.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Regression for a real scan of a red LEGO fish on white paper. The crop is valid,
 * but the old pitch detector trusted a vertical peak from the fish silhouette and
 * produced a 13x1 grid, squashing the fish into a long strip on the TV.
 */
class WhitePaperScanTest {

    @Test
    fun whitePaperScanKeepsAPlausibleTwoDimensionalGrid() {
        val photo = loadSample()
        val quad = FishImageProcessor.insetQuad(photo, 0f)
        val processed = FishImageProcessor.process(
            photo,
            quad,
            FishImageProcessor.Options(
                tolerance = 20,
                gridCols = -1,
                snapLegoColours = true,
                maxOut = 900
            )
        )

        println("white-paper auto grid: ${processed.gridCols}x${processed.gridRows}")
        val artifacts = java.io.File("build/test-artifacts").apply { mkdirs() }
        TestPng.write(processed.image, java.io.File(artifacts, "white-paper-sprite.png"))
        assertTrue("auto grid was not used", processed.usedGrid && processed.autoGrid)
        assertTrue("the sprite was not rebuilt as LEGO bricks", processed.brickized)
        assertTrue(
            "brick shading is missing",
            processed.image.pixels.filter { ArgbImage.alpha(it) > 0 }.toHashSet().size > 4
        )
        assertTrue("the fish collapsed to one row", processed.gridRows > 1)
        assertTrue("implausible row count: ${processed.gridRows}", processed.gridRows in 5..14)
        assertTrue("implausible column count: ${processed.gridCols}", processed.gridCols in 7..16)
        assertTrue("sprite is empty", processed.image.pixels.any { ArgbImage.alpha(it) > 0 })
        assertTrue(
            "red fish pixels were lost",
            processed.image.pixels.any {
                ArgbImage.alpha(it) > 0 && ArgbImage.red(it) > ArgbImage.green(it) + 30
            }
        )
    }

    @Test
    fun highCutoutToleranceDoesNotEraseTheGreyBrick() {
        val photo = loadSample()
        val cut = FishImageProcessor.removeBackground(photo, tolerance = 40)
        val tight = FishImageProcessor.trimTransparent(cut, 0)
        assertNotNull("the whole fish disappeared at a high cutout setting", tight)
        assertTrue("fish bounds collapsed: ${tight!!.width}x${tight.height}", tight.width >= 180)
        assertTrue("fish bounds collapsed: ${tight.width}x${tight.height}", tight.height >= 140)
    }

    @Test
    fun paperShadowIsRemovedWithoutErasingTheGreyBrick() {
        val photo = loadSample()
        for (tolerance in intArrayOf(14, 20, 40)) {
            val cut = FishImageProcessor.removeBackground(photo, tolerance)
            val artifacts = java.io.File("build/test-artifacts").apply { mkdirs() }
            TestPng.write(cut, java.io.File(artifacts, "white-paper-cut-$tolerance.png"))
            assertEquals(
                "paper shadow survived at tolerance $tolerance (40,20)",
                0,
                alphaAt(cut, 40, 20)
            )
            assertEquals(
                "paper shadow survived at tolerance $tolerance (5,90)",
                0,
                alphaAt(cut, 5, 90)
            )
            assertTrue(
                "grey brick was erased at tolerance $tolerance (100,100)",
                alphaAt(cut, 100, 100) > 60
            )
            assertTrue(
                "most of the grey brick disappeared at tolerance $tolerance",
                opaqueCount(cut, 90, 75, 190, 140) > 500
            )
            assertEquals(
                "ghost-shadow stud survived at tolerance $tolerance (240,102)",
                0,
                alphaAt(cut, 240, 102)
            )
        }
    }

    private fun alphaAt(image: ArgbImage, x: Int, y: Int): Int {
        if (x !in 0 until image.width || y !in 0 until image.height) return 0
        return ArgbImage.alpha(image.pixels[y * image.width + x])
    }

    private fun opaqueCount(image: ArgbImage, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(image.height)) {
            for (x in left.coerceAtLeast(0) until right.coerceAtMost(image.width)) {
                if (ArgbImage.alpha(image.pixels[y * image.width + x]) > 60) count++
            }
        }
        return count
    }

    private fun loadSample(): ArgbImage {
        val stream = javaClass.classLoader!!.getResourceAsStream("samples/white-paper-fish.bin")
        assertNotNull("missing white-paper sample", stream)
        val bytes = stream!!.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val width = buffer.int
        val height = buffer.int
        assertTrue("bad sample header", width > 0 && height > 0)
        val pixels = IntArray(width * height) { buffer.int }
        return ArgbImage(width, height, pixels)
    }
}
