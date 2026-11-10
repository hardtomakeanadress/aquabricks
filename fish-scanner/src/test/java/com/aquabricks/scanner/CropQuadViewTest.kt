package com.aquabricks.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The corner handles must not be the only thing under the user's finger. Dragging
 * a corner shows a magnifier offset from the touch point; this test proves the
 * view draws it (and does not crash) with real Skia graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropQuadViewTest {

    @Test
    fun draggingACornerCannotMakeTheCropConcave() {
        val context = RuntimeEnvironment.getApplication() as Context
        val view = CropQuadView(context)
        val photo = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(photo).drawColor(Color.rgb(240, 238, 230))
        view.setPhoto(photo)
        val exact = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        view.measure(exact, exact)
        view.layout(0, 0, 400, 400)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 29f, 29f, 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        val move = MotionEvent.obtain(0L, 1L, MotionEvent.ACTION_MOVE, 370f, 370f, 0)
        view.dispatchTouchEvent(move)
        move.recycle()

        val quad = view.quad()
        assertTrue("the crop became concave", com.aquabricks.scanner.imaging.FishImageProcessor.isValidQuad(quad))
        assertTrue("the top-left corner crossed the other corners", quad[0] < 100f && quad[1] < 100f)
    }

    @Test
    fun draggingACornerDrawsAMagnifier() {
        val context = RuntimeEnvironment.getApplication() as Context
        val view = CropQuadView(context)
        val photo = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val photoCanvas = Canvas(photo)
        photoCanvas.drawColor(Color.rgb(240, 238, 230))
        photoCanvas.drawCircle(100f, 100f, 55f, Paint().apply { color = Color.rgb(200, 30, 20) })
        view.setPhoto(photo)
        val exact = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
        view.measure(exact, exact)
        view.layout(0, 0, 400, 400)

        val before = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(before))

        // The initial quad is inset 6%; the photo fits the 400 px view with a 6 dp pad.
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 31f, 31f, 0)
        view.dispatchTouchEvent(down)
        down.recycle()

        val after = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(after))

        val a = IntArray(before.width * before.height)
        val b = IntArray(after.width * after.height)
        before.getPixels(a, 0, before.width, 0, 0, before.width, before.height)
        after.getPixels(b, 0, after.width, 0, 0, after.width, after.height)
        val changed = a.indices.count { a[it] != b[it] }
        assertTrue("dragging a corner should draw a visible magnifier, changed=$changed", changed > 500)

        val artifacts = java.io.File("build/test-artifacts").apply { mkdirs() }
        java.io.FileOutputStream(java.io.File(artifacts, "crop-before.png")).use {
            before.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        java.io.FileOutputStream(java.io.File(artifacts, "crop-dragging.png")).use {
            after.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
