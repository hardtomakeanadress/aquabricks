package com.aquabricks.scanner.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

class FishImageProcessorTest {

    private val background = ArgbImage.argb(255, 242, 240, 230)
    private val body = ArgbImage.argb(255, 224, 92, 54)
    private val fin = ArgbImage.argb(255, 242, 166, 90)
    private val eye = ArgbImage.argb(255, 250, 250, 250)
    private val pupil = ArgbImage.argb(255, 20, 30, 34)

    @Test
    fun autoQuadFindsTheFish() {
        val scene = buildScene(420, 280)
        val quad = FishImageProcessor.autoQuad(scene)
        assertEquals(8, quad.size)
        assertTrue("left edge was ${quad[0]}", quad[0] < 100f)
        assertTrue("right edge was ${quad[2]}", quad[2] > 320f)
        assertTrue("top edge was ${quad[1]}", quad[1] < 110f)
        assertTrue("bottom edge was ${quad[5]}", quad[5] > 180f)
        assertTrue(quad[0] >= 0f && quad[1] >= 0f)
        assertTrue(quad[2] <= scene.width && quad[5] <= scene.height)
    }

    @Test
    fun warpIdentityKeepsTheCentreColour() {
        val scene = buildScene(420, 280)
        val quad = floatArrayOf(0f, 0f, 420f, 0f, 420f, 280f, 0f, 280f)
        val warped = FishImageProcessor.warp(scene, quad, 500)
        assertTrue(warped.width in 400..500)
        val centre = warped.pixels[(warped.height / 2) * warped.width + warped.width / 2]
        assertTrue(ArgbImage.alpha(centre) > 200)
        assertTrue(ArgbImage.red(centre) > ArgbImage.green(centre) + 30)
    }

    @Test
    fun aTightCropDoesNotMistakeTheFishForBackground() {
        val scene = buildScene(420, 280)
        // Deliberately hug the fish: the tail almost touches the left edge and the
        // nose almost touches the right edge, exactly what a child will drag.
        val tight = floatArrayOf(62f, 78f, 340f, 78f, 340f, 202f, 62f, 202f)
        val warped = FishImageProcessor.warp(scene, tight, 900)
        val cut = FishImageProcessor.removeBackground(warped, tolerance = 28)
        val centre = cut.pixels[(cut.height / 2) * cut.width + cut.width / 2]
        assertTrue("the fish was eaten", ArgbImage.alpha(centre) > 100)
        assertEquals(0, ArgbImage.alpha(cut.pixels[0]))
    }

    @Test
    fun backgroundIsRemovedAndFishSurvives() {
        val scene = buildScene(420, 280)
        val cut = FishImageProcessor.removeBackground(scene, tolerance = 28)
        assertEquals(0, ArgbImage.alpha(cut.pixels[0]))
        assertEquals(0, ArgbImage.alpha(cut.pixels[cut.width - 1]))
        val centre = cut.pixels[(cut.height / 2) * cut.width + cut.width / 2]
        assertTrue(ArgbImage.alpha(centre) > 100)
    }

    @Test
    fun gridResampleMakesAStudGrid() {
        val scene = buildScene(420, 280)
        val cut = FishImageProcessor.removeBackground(scene, tolerance = 28)
        val trimmed = FishImageProcessor.trimTransparent(cut, 2)
        assertNotNull(trimmed)
        val grid = FishImageProcessor.resampleGrid(trimmed!!, 12)
        assertEquals(12, grid.width)
        assertTrue(grid.height in 5..12)
        val opaqueCells = grid.pixels.filter { ArgbImage.alpha(it) > 0 }
        assertTrue("opaque cells: ${opaqueCells.size}", opaqueCells.size > 20)
        assertTrue("opaque cells: ${opaqueCells.size}", opaqueCells.size < 12 * grid.height)
        assertTrue(
            "grid cells lost their fish colours",
            opaqueCells.any { ArgbImage.red(it) > ArgbImage.green(it) + 30 }
        )
    }

    @Test
    fun fullPipelineProducesASpriteAndDebugImages() {
        val scene = buildScene(420, 280)
        val quad = FishImageProcessor.autoQuad(scene)
        val processed = FishImageProcessor.process(
            scene,
            quad,
            FishImageProcessor.Options(tolerance = 28, gridCols = 14, maxOut = 900)
        )
        assertTrue(processed.usedGrid)
        assertTrue(processed.gridCols in 10..14)
        assertTrue(processed.gridRows > 0)
        val opaquePixels = processed.image.pixels.filter { ArgbImage.alpha(it) > 0 }
        assertTrue("sprite was empty", opaquePixels.size > 400)
        assertTrue(
            "sprite was colourless",
            opaquePixels.any { ArgbImage.red(it) > ArgbImage.green(it) + 30 }
        )

        saveDebugImage(scene, "01-source.png")
        val cut = FishImageProcessor.removeBackground(FishImageProcessor.warp(scene, quad, 900), 28)
        saveDebugImage(cut, "02-cutout.png")
        saveDebugImage(processed.image, "03-sprite.png")
    }

    @Test
    fun undetectableGridFallsBackToAShapeBrickMosaic() {
        val width = 220
        val height = 140
        val pixels = IntArray(width * height) { background }
        for (y in 30 until 110) {
            for (x in 40 until 180) {
                pixels[y * width + x] = body
            }
        }
        val image = ArgbImage(width, height, pixels)
        val processed = FishImageProcessor.process(
            image,
            FishImageProcessor.insetQuad(image, 0f),
            FishImageProcessor.Options(tolerance = 20, gridCols = -1)
        )
        assertFalse("a textureless shape must not claim an automatic stud grid", processed.usedGrid)
        assertFalse(processed.autoGrid)
        assertTrue("fallback shape was not brickized", processed.brickized)
        assertTrue(processed.gridCols >= 2 && processed.gridRows >= 2)
        assertTrue("shape-brick sprite is empty", processed.image.pixels.any { ArgbImage.alpha(it) > 0 })
    }

    @Test
    fun smoothFallbackHonoursBrickColourMatching() {
        val width = 220
        val height = 140
        val custom = ArgbImage.argb(255, 40, 120, 120)
        val pixels = IntArray(width * height) { background }
        for (y in 30 until 110) {
            for (x in 40 until 180) pixels[y * width + x] = custom
        }
        val image = ArgbImage(width, height, pixels)
        val quad = FishImageProcessor.insetQuad(image, 0f)

        val snapped = FishImageProcessor.process(
            image,
            quad,
            FishImageProcessor.Options(tolerance = 20, gridCols = -1, snapBrickColours = true)
        )
        val raw = FishImageProcessor.process(
            image,
            quad,
            FishImageProcessor.Options(tolerance = 20, gridCols = -1, snapBrickColours = false)
        )

        val mediumAzure = ArgbImage.argb(255, 0x36, 0xAE, 0xBF)
        assertTrue("snapped fallback lost the brick palette", snapped.image.pixels.any { it == mediumAzure })
        val preserved = raw.image.pixels.any { colour ->
            ArgbImage.alpha(colour) > 0 &&
                abs(ArgbImage.red(colour) - 40) <= 12 &&
                abs(ArgbImage.green(colour) - 120) <= 12 &&
                abs(ArgbImage.blue(colour) - 120) <= 12
        }
        assertTrue("palette-off fallback must preserve the sampled colour", preserved)
        assertTrue("the checkbox had no effect on the fallback", raw.image.pixels.none { it == mediumAzure })
    }

    @Test
    fun concaveCropIsRejectedBeforeWarping() {
        val image = ArgbImage(200, 200, IntArray(200 * 200) { background })
        val concave = floatArrayOf(178f, 178f, 188f, 12f, 188f, 188f, 12f, 188f)
        assertFalse(FishImageProcessor.isValidQuad(concave))
        assertThrows(IllegalArgumentException::class.java) {
            FishImageProcessor.warp(image, concave)
        }
        assertTrue(
            FishImageProcessor.isValidQuad(
                floatArrayOf(12f, 12f, 188f, 12f, 188f, 188f, 12f, 188f)
            )
        )
    }

    @Test
    fun anEmptyResampledGridIsAnErrorNotASprite() {
        val width = 220
        val height = 140
        val pixels = IntArray(width * height) { background }
        for (y in 66 until 67) {
            for (x in 20 until 200) pixels[y * width + x] = body
        }
        val image = ArgbImage(width, height, pixels)
        var threw = false
        try {
            FishImageProcessor.process(
                image,
                FishImageProcessor.insetQuad(image, 0f),
                FishImageProcessor.Options(tolerance = 14, gridCols = 16)
            )
        } catch (_: FishImageProcessor.NoFishFoundException) {
            threw = true
        }
        assertTrue("an empty grid must throw NoFishFoundException", threw)
    }

    @Test
    fun plainBackgroundThrowsFriendlyError() {
        val width = 200
        val height = 100
        val pixels = IntArray(width * height) { background }
        val image = ArgbImage(width, height, pixels)
        var threw = false
        try {
            FishImageProcessor.process(
                image,
                FishImageProcessor.insetQuad(image, 0.05f),
                FishImageProcessor.Options()
            )
        } catch (_: FishImageProcessor.NoFishFoundException) {
            threw = true
        }
        assertTrue("Expected NoFishFoundException", threw)
    }

    @Test
    fun noisyBackgroundDoesNotBleedIntoTheFish() {
        val scene = buildScene(420, 280)
        val rng = Random(3)
        for (y in 0 until scene.height) {
            for (x in 0 until scene.width) {
                val index = y * scene.width + x
                if (scene.pixels[index] == background) {
                    val noise = rng.nextInt(-4, 5)
                    scene.pixels[index] = ArgbImage.argb(
                        255,
                        (242 + noise).coerceIn(0, 255),
                        (240 + noise).coerceIn(0, 255),
                        (230 + noise).coerceIn(0, 255)
                    )
                }
            }
        }
        val cut = FishImageProcessor.removeBackground(scene, tolerance = 28)
        val centre = cut.pixels[(cut.height / 2) * cut.width + cut.width / 2]
        assertTrue(ArgbImage.alpha(centre) > 100)
        assertEquals(0, ArgbImage.alpha(cut.pixels[0]))
    }

    private fun buildScene(width: Int, height: Int): ArgbImage {
        val pixels = IntArray(width * height) { background }

        fun put(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
        }

        val bodyCx = width * 0.54f
        val bodyCy = height * 0.5f
        val bodyRx = width * 0.23f
        val bodyRy = height * 0.20f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - bodyCx) / bodyRx
                val dy = (y - bodyCy) / bodyRy
                if (dx * dx + dy * dy <= 1f) put(x, y, body)
            }
        }

        // Tail triangle on the left.
        val tailBaseX = (bodyCx - bodyRx * 0.85f).toInt()
        val tailTipX = (bodyCx - bodyRx * 2.05f).toInt()
        for (x in tailTipX..tailBaseX) {
            val spread = ((x - tailTipX).toFloat() / max(1, tailBaseX - tailTipX) * height * 0.19f).roundToInt()
            for (y in (bodyCy.toInt() - spread)..(bodyCy.toInt() + spread)) {
                put(x, y, if (x % 9 < 4) fin else body)
            }
        }

        // A pale fin on top.
        for (x in (bodyCx - bodyRx * 0.5f).toInt()..(bodyCx + bodyRx * 0.2f).toInt()) {
            val t = (x - (bodyCx - bodyRx * 0.5f)) / (bodyRx * 0.7f)
            val top = (bodyCy - bodyRy * (0.8f + 0.5f * (1f - abs(t - 0.5f) * 2f))).toInt()
            for (y in top..(bodyCy - bodyRy * 0.5f).toInt()) put(x, y, fin)
        }

        // Eye, deliberately close to the right edge like a child's mosaic.
        val eyeCx = (bodyCx + bodyRx * 0.55f).toInt()
        val eyeCy = (bodyCy - bodyRy * 0.18f).toInt()
        for (y in -10..10) {
            for (x in -10..10) {
                if (x * x + y * y <= 100) put(eyeCx + x, eyeCy + y, eye)
                if (x * x + y * y <= 25) put(eyeCx + x, eyeCy + y, pupil)
            }
        }

        return ArgbImage(width, height, pixels)
    }

    private fun saveDebugImage(image: ArgbImage, name: String) {
        runCatching {
            val outputDir = File("build/test-artifacts")
            outputDir.mkdirs()
            TestPng.write(image, File(outputDir, name))
        }
    }
}
