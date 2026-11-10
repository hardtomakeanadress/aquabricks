package com.aquabricks.scanner

import com.aquabricks.scanner.imaging.ArgbImage
import com.aquabricks.scanner.imaging.TestPng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FacePainterTest {

    private val baseColour = ArgbImage.argb(255, 224, 92, 54)

    @Test
    fun noneLeavesTheSpriteUntouched() {
        val base = sprite()
        val result = FacePainter.render(
            base,
            FaceConfig(eye = EyeModel.NONE, mouth = MouthModel.NONE),
            facingRight = true
        )
        assertTrue(base.pixels.contentEquals(result.pixels))
    }

    @Test
    fun everyModelDrawsSomething() {
        val base = sprite()
        for (model in EyeModel.values()) {
            if (model == EyeModel.NONE) continue
            val result = FacePainter.render(
                base,
                FaceConfig(eye = model, eyeX = 0.5f, eyeY = 0.5f, mouth = MouthModel.NONE),
                facingRight = true
            )
            assertTrue("eye $model drew nothing", changedPixels(base, result) > 5)
        }
        for (model in MouthModel.values()) {
            if (model == MouthModel.NONE) continue
            val result = FacePainter.render(
                base,
                FaceConfig(eye = EyeModel.NONE, mouth = model, mouthX = 0.5f, mouthY = 0.5f),
                facingRight = true
            )
            assertTrue("mouth $model drew nothing", changedPixels(base, result) > 5)
        }
    }

    @Test
    fun faceFeaturesAreBoldEnoughForTheTank() {
        val base = sprite()
        val eyeOnly = FacePainter.render(
            base,
            FaceConfig(eye = EyeModel.ROUND, eyeX = 0.5f, eyeY = 0.5f, mouth = MouthModel.NONE),
            facingRight = true
        )
        val mouthOnly = FacePainter.render(
            base,
            FaceConfig(eye = EyeModel.NONE, mouth = MouthModel.SMILE, mouthX = 0.5f, mouthY = 0.5f),
            facingRight = true
        )
        assertTrue("eye is only ${changedPixels(base, eyeOnly)} px", changedPixels(base, eyeOnly) > 220)
        assertTrue("mouth is only ${changedPixels(base, mouthOnly)} px", changedPixels(base, mouthOnly) > 180)
    }

    @Test
    fun facesFollowTheirNormalisedPositions() {
        val base = sprite()
        val left = FacePainter.render(
            base,
            FaceConfig(eye = EyeModel.ROUND, eyeX = 0.2f, eyeY = 0.3f, mouth = MouthModel.NONE),
            facingRight = true
        )
        val right = FacePainter.render(
            base,
            FaceConfig(eye = EyeModel.ROUND, eyeX = 0.8f, eyeY = 0.3f, mouth = MouthModel.NONE),
            facingRight = true
        )
        val width = base.width
        val height = base.height
        assertNotEquals(
            base.pixels[(height * 0.3f).toInt() * width + (width * 0.2f).toInt()],
            left.pixels[(height * 0.3f).toInt() * width + (width * 0.2f).toInt()]
        )
        assertNotEquals(
            base.pixels[(height * 0.3f).toInt() * width + (width * 0.8f).toInt()],
            right.pixels[(height * 0.3f).toInt() * width + (width * 0.8f).toInt()]
        )
        assertEquals(
            base.pixels[(height * 0.3f).toInt() * width + (width * 0.8f).toInt()],
            left.pixels[(height * 0.3f).toInt() * width + (width * 0.8f).toInt()]
        )
    }

    @Test
    fun expressionsCanBeHappyOrAngry() {
        val base = sprite()
        val happy = FacePainter.render(
            base,
            FaceConfig(
                eye = EyeModel.HAPPY, eyeX = 0.7f, eyeY = 0.35f,
                mouth = MouthModel.SMILE, mouthX = 0.8f, mouthY = 0.62f
            ),
            facingRight = true
        )
        val angry = FacePainter.render(
            base,
            FaceConfig(
                eye = EyeModel.ANGRY, eyeX = 0.7f, eyeY = 0.35f,
                mouth = MouthModel.FROWN, mouthX = 0.8f, mouthY = 0.62f
            ),
            facingRight = true
        )
        assertTrue(changedPixels(base, happy) > 20)
        assertTrue(changedPixels(base, angry) > 20)
        assertTrue(changedPixels(happy, angry) > 10)
    }

    @Test
    fun mirroredPlacementStaysOnTheOppositeSide() {
        val base = sprite()
        val config = FaceConfig(eye = EyeModel.ROUND, eyeX = 0.7f, eyeY = 0.35f, mouth = MouthModel.NONE)
        val leftFacing = FacePainter.render(base, config.mirrored(), facingRight = false)
        val rightFacing = FacePainter.render(base, config, facingRight = true)
        val width = base.width
        val height = base.height
        val mirroredX = (width * 0.3f).toInt()
        val normalX = (width * 0.7f).toInt()
        val y = (height * 0.35f).toInt()
        assertNotEquals(leftFacing.pixels[y * width + mirroredX], base.pixels[y * width + mirroredX])
        assertNotEquals(rightFacing.pixels[y * width + normalX], base.pixels[y * width + normalX])
        assertEquals(leftFacing.pixels[y * width + normalX], base.pixels[y * width + normalX])
    }

    @Test
    fun writesHappyAndAngryPreviewArtifacts() {
        val base = fishShape()
        val happy = FacePainter.render(
            base,
            FaceConfig(
                eye = EyeModel.HAPPY, eyeX = 0.72f, eyeY = 0.36f,
                mouth = MouthModel.SMILE, mouthX = 0.80f, mouthY = 0.60f
            ),
            facingRight = true
        )
        val angry = FacePainter.render(
            base,
            FaceConfig(
                eye = EyeModel.ANGRY, eyeX = 0.72f, eyeY = 0.36f,
                mouth = MouthModel.FROWN, mouthX = 0.80f, mouthY = 0.60f
            ),
            facingRight = true
        )
        val directory = File("build/test-artifacts").apply { mkdirs() }
        TestPng.write(base, File(directory, "face-base.png"))
        TestPng.write(happy, File(directory, "face-happy.png"))
        TestPng.write(angry, File(directory, "face-angry.png"))
    }

    private fun sprite(): ArgbImage {
        val width = 160
        val height = 100
        return ArgbImage(width, height, IntArray(width * height) { baseColour })
    }

    private fun fishShape(): ArgbImage {
        val width = 240
        val height = 140
        val body = ArgbImage.argb(255, 201, 26, 9)
        val tail = ArgbImage.argb(255, 254, 138, 24)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - width * 0.55f) / (width * 0.34f)
                val dy = (y - height * 0.5f) / (height * 0.34f)
                if (dx * dx + dy * dy <= 1f) pixels[y * width + x] = body
                if (x in 20..90 && kotlin.math.abs(y - height / 2) <= (x - 20) * 0.9f) {
                    pixels[y * width + x] = tail
                }
            }
        }
        return ArgbImage(width, height, pixels)
    }

    private fun changedPixels(first: ArgbImage, second: ArgbImage): Int {
        var changed = 0
        for (index in first.pixels.indices) {
            if (first.pixels[index] != second.pixels[index]) changed++
        }
        return changed
    }
}
