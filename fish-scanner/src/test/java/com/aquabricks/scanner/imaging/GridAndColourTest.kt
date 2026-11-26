package com.aquabricks.scanner.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Verifies that the auto grid locks onto the real stud pitch (so brick counts and
 * sizes survive) and that cell colours come from the flat brick face and snap to
 * the brick palette rather than averaging in stud highlights or gaps.
 */
class GridAndColourTest {

    private val red = ArgbImage.argb(255, 201, 26, 9)
    private val blue = ArgbImage.argb(255, 0, 85, 191)
    private val yellow = ArgbImage.argb(255, 242, 205, 55)
    private val green = ArgbImage.argb(255, 35, 120, 65)
    private val mediumAzure = ArgbImage.argb(255, 0x36, 0xAE, 0xBF)
    private val offPaletteTeal = ArgbImage.argb(255, 40, 120, 120)

    @Test
    fun detectsTheStudPitchAndCount() {
        val image = brickGrid(columns = 12, rows = 8, pitch = 35)
        val grid = FishImageProcessor.detectGrid(image)
        assertNotNull("pitch detector failed", grid)
        assertTrue("pitchX ${grid!!.pitchX}", abs(grid.pitchX - 35f) <= 2.5f)
        assertTrue("pitchY ${grid.pitchY}", abs(grid.pitchY - 35f) <= 2.5f)
        assertTrue("columns ${grid.columns}", grid.columns in 11..13)
        assertTrue("rows ${grid.rows}", grid.rows in 7..9)
    }

    @Test
    fun samplesFlatBrickFacesAndSnapsColours() {
        // 4x3 studs. Cells (1,0),(2,0),(1,1),(2,1) form one 2x2 blue brick.
        val image = brickGrid(
            columns = 4,
            rows = 3,
            pitch = 44,
            colourAt = { cx, cy ->
                when {
                    cx in 1..2 && cy in 0..1 -> blue
                    cx == 0 && cy == 0 -> red
                    cx == 3 && cy == 0 -> yellow
                    cx == 0 && cy == 1 -> green
                    else -> red
                }
            }
        )
        val result = FishImageProcessor.resampleGrid(image, 4, 3, snapBrickColours = true)
        assertEquals(4, result.width)
        assertEquals(3, result.height)
        assertColour(blue, result, 1, 0)
        assertColour(blue, result, 2, 0)
        assertColour(blue, result, 1, 1)
        assertColour(blue, result, 2, 1)
        assertColour(red, result, 0, 0)
        assertColour(yellow, result, 3, 0)
        assertColour(green, result, 0, 1)
    }

    @Test
    fun reconcilesSilhouettePitchesOnEitherAxis() {
        val horizontalWasBad = FishImageProcessor.reconcilePitches(400, 240, 160f, 24f)
        assertNotNull(horizontalWasBad)
        assertEquals(24f, horizontalWasBad!!.first, 0.01f)
        assertEquals(24f, horizontalWasBad.second, 0.01f)

        val verticalWasBad = FishImageProcessor.reconcilePitches(400, 240, 24f, 160f)
        assertNotNull(verticalWasBad)
        assertEquals(24f, verticalWasBad!!.first, 0.01f)
        assertEquals(24f, verticalWasBad.second, 0.01f)
    }

    @Test
    fun resamplingHonoursTheDetectedLatticeOrigin() {
        val width = 130
        val height = 100
        val pixels = IntArray(width * height) { ArgbImage.argb(255, 242, 240, 230) }
        for (y in 40 until 70) {
            for (x in 40 until 70) pixels[y * width + x] = red
        }
        val image = ArgbImage(width, height, pixels)
        val estimate = FishImageProcessor.GridEstimate(
            columns = 4,
            rows = 3,
            pitchX = 30f,
            pitchY = 30f,
            originX = 10f,
            originY = 10f
        )
        val grid = FishImageProcessor.resampleGrid(image, estimate, snapBrickColours = false)
        assertEquals(4, grid.width)
        assertEquals(3, grid.height)
        assertEquals(red, grid.pixels[1 * grid.width + 1])
        assertEquals(ArgbImage.argb(255, 242, 240, 230), grid.pixels[0])
    }

    @Test
    fun turnsOffPaletteMatchingAndKeepsTheSampledColour() {
        val image = brickGrid(columns = 4, rows = 3, pitch = 40) { _, _ -> offPaletteTeal }
        val result = FishImageProcessor.resampleGrid(image, 4, 3, snapBrickColours = false)
        assertEquals(4, result.width)
        assertEquals(3, result.height)
        assertColour(offPaletteTeal, result, 1, 1)
    }

    @Test
    fun sRgbTransferUsesTheCorrectGammaForPaletteMatching() {
        // If the nonlinear branch cubes instead of raising to 2.4, this teal picks
        // Dark Bluish Gray rather than Medium Azure.
        val image = brickGrid(columns = 4, rows = 3, pitch = 40) { _, _ -> offPaletteTeal }
        val snapped = FishImageProcessor.resampleGrid(image, 4, 3, snapBrickColours = true)
        assertColour(mediumAzure, snapped, 1, 1)
        val raw = FishImageProcessor.resampleGrid(image, 4, 3, snapBrickColours = false)
        assertColour(offPaletteTeal, raw, 1, 1)
    }

    private fun assertColour(expected: Int, image: ArgbImage, x: Int, y: Int) {
        val actual = image.pixels[y * image.width + x]
        assertEquals(
            "cell ($x,$y) expected ${hex(expected)} but was ${hex(actual)}",
            expected,
            actual
        )
    }

    private fun hex(color: Int): String = "ARGB(%d,%d,%d,%d)".format(
        ArgbImage.alpha(color),
        ArgbImage.red(color),
        ArgbImage.green(color),
        ArgbImage.blue(color)
    )

    /**
     * Draws a full baseplate with a grid of coloured bricks, dark gaps and a bright
     * central stud on every cell, similar to a phone photo.
     */
    private fun brickGrid(
        columns: Int,
        rows: Int,
        pitch: Int,
        colourAt: (Int, Int) -> Int = { cx, cy ->
            when ((cx + cy) % 4) {
                0 -> red
                1 -> blue
                2 -> yellow
                else -> green
            }
        }
    ): ArgbImage {
        val width = columns * pitch
        val height = rows * pitch
        val pixels = IntArray(width * height) { ArgbImage.argb(255, 108, 110, 104) }
        val gap = maxOf(2, (pitch * 0.06f).roundToInt())
        val studRadius = pitch * 0.24f
        for (cy in 0 until rows) {
            for (cx in 0 until columns) {
                val colour = colourAt(cx, cy)
                val left = cx * pitch + gap
                val top = cy * pitch + gap
                val right = (cx + 1) * pitch - gap
                val bottom = (cy + 1) * pitch - gap
                for (y in top until bottom) {
                    for (x in left until right) {
                        pixels[y * width + x] = colour
                    }
                }
                val stud = lighten(colour, 1.22f)
                val centreX = cx * pitch + pitch * 0.5f
                val centreY = cy * pitch + pitch * 0.5f
                for (y in top until bottom) {
                    for (x in left until right) {
                        val dx = x + 0.5f - centreX
                        val dy = y + 0.5f - centreY
                        if (dx * dx + dy * dy <= studRadius * studRadius) {
                            pixels[y * width + x] = stud
                        }
                    }
                }
            }
        }
        return ArgbImage(width, height, pixels)
    }

    private fun lighten(color: Int, factor: Float): Int {
        return ArgbImage.argb(
            255,
            (ArgbImage.red(color) * factor).roundToInt().coerceIn(0, 255),
            (ArgbImage.green(color) * factor).roundToInt().coerceIn(0, 255),
            (ArgbImage.blue(color) * factor).roundToInt().coerceIn(0, 255)
        )
    }
}
