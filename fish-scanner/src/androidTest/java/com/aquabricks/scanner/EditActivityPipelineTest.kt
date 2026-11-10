package com.aquabricks.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end on-device test of the editor: decode a real JPEG, auto-crop, cut the
 * background out, resample the LEGO grid and enable the send button.
 */
@RunWith(AndroidJUnit4::class)
class EditActivityPipelineTest {

    @Test
    fun photoBecomesAReadyToSendSprite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "editor-test-${System.nanoTime()}.jpg")
        writeSyntheticPhoto(photo)
        try {
            val intent = Intent(context, EditActivity::class.java)
                .putExtra(EditActivity.EXTRA_PHOTO_PATH, photo.absolutePath)
            val scenario = ActivityScenario.launch<EditActivity>(intent)
            try {
                val deadline = System.currentTimeMillis() + 20_000L
                var sendEnabled = false
                var previewReady = false
                var status = ""
                while (System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        sendEnabled = activity.findViewById<Button>(R.id.sendButton).isEnabled
                        previewReady = activity.findViewById<ImageView>(R.id.previewImage).drawable != null
                        status = activity.findViewById<TextView>(R.id.statusText).text.toString()
                    }
                    if (sendEnabled && previewReady) break
                    Thread.sleep(150)
                }
                assertTrue("send button never enabled, status=$status", sendEnabled)
                assertTrue("preview never rendered, status=$status", previewReady)
                assertTrue(
                    "unexpected processing status: $status",
                    status.contains("GRID") || status.contains("SPRITE")
                )
                assertTrue("status should report stud count: $status", status.contains("x"))
            } finally {
                scenario.close()
            }
        } finally {
            photo.delete()
        }
    }

    private fun writeSyntheticPhoto(file: File) {
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(243, 241, 232))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(224, 92, 54)
        canvas.drawOval(130f, 110f, 470f, 300f, paint)
        paint.color = Color.rgb(232, 104, 93)
        val tail = Path().apply {
            moveTo(150f, 205f)
            lineTo(70f, 145f)
            lineTo(70f, 265f)
            close()
        }
        canvas.drawPath(tail, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(415f, 170f, 22f, paint)
        paint.color = Color.rgb(20, 30, 34)
        canvas.drawCircle(415f, 170f, 9f, paint)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
    }
}
