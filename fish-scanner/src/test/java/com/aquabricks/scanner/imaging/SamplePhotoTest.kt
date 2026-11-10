package com.aquabricks.scanner.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Regression test against a synthetic but photographic sample: textured baseplate,
 * studs, shadows, sensor noise and a tilted camera. Nothing here is a flat colour
 * fill, so a regression in the cutout or grid sampling shows up immediately.
 */
class SamplePhotoTest {

    @Test
    fun realisticPhotoBecomesACleanPixelSprite() {
        val photo = loadSample()

        val quad = FishImageProcessor.autoQuad(photo)
        val warped = FishImageProcessor.warp(photo, quad, 900)
        val cut = FishImageProcessor.removeBackground(warped, tolerance = 14)
        val transparentFraction = cut.pixels.count { ArgbImage.alpha(it) == 0 } / cut.pixels.size.toFloat()
        assertTrue("background was not removed: $transparentFraction", transparentFraction > 0.12f)
        assertTrue("background removal ate the fish: $transparentFraction", transparentFraction < 0.85f)

        val opaque = cut.pixels.filter { ArgbImage.alpha(it) > 60 }
        assertTrue("no fish pixels survived", opaque.size > 1000)
        val plateGrey = opaque.count { isGreyish(it) } / opaque.size.toFloat()
        assertTrue("baseplate leaked into the cutout: $plateGrey", plateGrey < 0.12f)

        val trimmed = FishImageProcessor.trimTransparent(cut, 2)
        assertNotNull("trim produced nothing", trimmed)

        val processed = FishImageProcessor.process(
            photo,
            quad,
            FishImageProcessor.Options(tolerance = 14, gridCols = 14, maxOut = 900)
        )
        val automatic = FishImageProcessor.process(
            photo,
            quad,
            FishImageProcessor.Options(
                tolerance = 14,
                gridCols = -1,
                snapLegoColours = true,
                maxOut = 900
            )
        )
        assertTrue("auto grid was not used", automatic.autoGrid)
        assertTrue("auto columns ${automatic.gridCols}", automatic.gridCols in 10..16)
        assertTrue("auto rows ${automatic.gridRows}", automatic.gridRows in 5..10)

        // Write visual artefacts before the assertions so a colour regression can
        // still be inspected.
        saveDebugImage(photo, "sample-01-source.png")
        saveDebugImage(cut, "sample-02-cutout.png")
        saveDebugImage(processed.image, "sample-03-sprite.png")
        saveDebugImage(automatic.image, "sample-04-auto-sprite.png")

        assertTrue(
            "auto grid should still have warm brick colours",
            automatic.image.pixels.any {
                ArgbImage.alpha(it) > 0 && ArgbImage.red(it) > ArgbImage.green(it) + 30
            }
        )
        assertTrue(
            "light eye/cream brick was eaten by the cutout",
            automatic.image.pixels.any {
                ArgbImage.alpha(it) > 0 && ArgbImage.red(it) > 200 &&
                    ArgbImage.green(it) > 180 && ArgbImage.blue(it) > 150
            }
        )
        assertTrue("expected LEGO-grid output", processed.usedGrid)
        assertTrue("grid was trimmed too far: ${processed.gridCols}", processed.gridCols >= 10)
        assertTrue("grid was not detected", processed.gridRows in 4..14)
        val spritePixels = processed.image.pixels.filter { ArgbImage.alpha(it) > 0 }
        assertTrue("sprite is empty", spritePixels.size > 500)
        assertTrue(
            "sprite lost its LEGO colour",
            spritePixels.any { ArgbImage.red(it) > ArgbImage.green(it) + 30 }
        )

        val corners = intArrayOf(
            processed.image.pixels.first(),
            processed.image.pixels[processed.image.width - 1],
            processed.image.pixels[processed.image.pixels.size - processed.image.width],
            processed.image.pixels.last()
        )
        assertTrue("sprite corners should be transparent", corners.any { ArgbImage.alpha(it) == 0 })
    }

    private fun isGreyish(color: Int): Boolean {
        val r = ArgbImage.red(color)
        val g = ArgbImage.green(color)
        val b = ArgbImage.blue(color)
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        return high - low < 22 && high > 120
    }

    private fun loadSample(): ArgbImage {
        val stream = javaClass.classLoader!!.getResourceAsStream("samples/lego-fish-photo.bin")
        assertNotNull("missing sample resource", stream)
        val bytes = stream!!.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val width = buffer.int
        val height = buffer.int
        assertTrue("bad sample header", width > 0 && height > 0)
        val pixels = IntArray(width * height) { buffer.int }
        return ArgbImage(width, height, pixels)
    }

    private fun saveDebugImage(image: ArgbImage, name: String) {
        runCatching {
            val directory = File("build/test-artifacts").apply { mkdirs() }
            TestPng.write(image, File(directory, name))
        }
    }
}
