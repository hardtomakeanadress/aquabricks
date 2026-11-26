package com.aquabricks.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.Button
import android.widget.CheckBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditActivityTest {

    @Test
    fun changingTheColourModeInvalidatesSendBeforeTheDelayedJobRuns() {
        val photo = File.createTempFile("aquabricks-edit", ".png")
        val bitmap = Bitmap.createBitmap(260, 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 242, 232))
        canvas.drawRect(50f, 40f, 210f, 100f, Paint().apply { color = Color.rgb(200, 35, 25) })
        FileOutputStream(photo).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val intent = Intent().putExtra(EditActivity.EXTRA_PHOTO_PATH, photo.absolutePath)
        val activity = Robolectric.buildActivity(EditActivity::class.java, intent).setup().get()
        val send = activity.findViewById<Button>(R.id.sendButton)
        val colourMode = activity.findViewById<CheckBox>(R.id.brickColourCheck)
        try {
            val deadline = System.currentTimeMillis() + 6_000
            while (!send.isEnabled && System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
                Thread.sleep(20)
            }
            assertTrue("the initial sprite never became sendable", send.isEnabled)

            colourMode.isChecked = false
            // The old code left this enabled until its 30 ms delayed job started.
            assertFalse("Send was still enabled during the reprocess debounce", send.isEnabled)
        } finally {
            activity.finish()
            photo.delete()
        }
    }
}
