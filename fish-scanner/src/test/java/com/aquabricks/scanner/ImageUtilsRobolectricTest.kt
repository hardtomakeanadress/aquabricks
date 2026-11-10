package com.aquabricks.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max

/**
 * Verifies the Android-specific image glue (Bitmap<->ArgbImage, JPEG decoding,
 * PNG encoding, checkerboard preview) on the JVM with Robolectric's native Skia.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageUtilsRobolectricTest {

    @Test
    fun bitmapArgbRoundTripKeepsPixels() {
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(224, 92, 54)
        canvas.drawOval(4f, 4f, 44f, 28f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(36f, 14f, 4f, paint)

        val argb = ImageUtils.toArgb(bitmap)
        assertEquals(48, argb.width)
        assertEquals(32, argb.height)
        val back = ImageUtils.toBitmap(argb)
        val original = IntArray(bitmap.width * bitmap.height)
        val roundTripped = IntArray(back.width * back.height)
        bitmap.getPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        back.getPixels(roundTripped, 0, back.width, 0, 0, back.width, back.height)
        assertTrue(original.contentEquals(roundTripped))

        val png = ImageUtils.pngBytes(back)
        assertTrue("PNG signature missing", png.size > 8)
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }

    @Test
    fun decodePhotoRespectsTheMaximumSide() {
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(243, 241, 232))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(224, 92, 54)
        canvas.drawOval(30f, 25f, 175f, 100f, paint)
        val file = File.createTempFile("aquabricks-decode", ".jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        try {
            val decoded = ImageUtils.decodePhoto(file.absolutePath, maxSide = 64)
            assertNotNull(decoded)
            assertTrue(max(decoded!!.width, decoded.height) <= 64)
            assertTrue(decoded.width > 0 && decoded.height > 0)
        } finally {
            file.delete()
        }
    }

    @Test
    fun checkerboardPreviewIsAvailable() {
        val drawable = ImageUtils.checkerDrawable(RuntimeEnvironment.getApplication())
        assertNotNull(drawable)
    }
}
