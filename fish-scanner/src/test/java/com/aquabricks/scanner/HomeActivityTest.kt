package com.aquabricks.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The launcher must not force the user into camera mode: both scanning and tank
 * management are visible and the scan button opens the camera screen explicitly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeActivityTest {

    @Test
    fun homeOffersScanAndManage() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup()
        try {
            val activity = controller.get()
            val decor = activity.window.decorView
            val scan = findText(decor, "SCAN A FISH")
            val manage = findText(decor, "MANAGE FISH IN THE TANK")
            assertNotNull("scan action missing", scan)
            assertNotNull("manage action missing", manage)
            assertTrue("scan action is not clickable", scan!!.isClickable)
            assertTrue("manage action is not clickable", manage!!.isClickable)

            val screenshot = Bitmap.createBitmap(
                activity.window.decorView.width.coerceAtLeast(1),
                activity.window.decorView.height.coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            activity.window.decorView.draw(Canvas(screenshot))
            val artifacts = File("build/test-artifacts").apply { mkdirs() }
            FileOutputStream(File(artifacts, "home-screen.png")).use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            scan.performClick()
            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull("scan button did not open the camera screen", started)
            assertEquals(MainActivity::class.java.name, started!!.component?.className)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findText(view.getChildAt(index), value)
                if (found != null) return found
            }
        }
        return null
    }
}
