package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Launches the real tank on the TV, waits for frames, screenshots the surface and
 * asserts the picture is colourful rather than a black/blank screen. The PNG is
 * written to the app's external files directory so it can be pulled with adb.
 */
@RunWith(AndroidJUnit4::class)
class AquariumSmokeTest {

    @Test
    fun tankRendersAColourfulFrame() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            Thread.sleep(3000)
            scenario.onActivity { activity ->
                assertFalse("tank activity finished unexpectedly", activity.isFinishing)
            }

            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            assertTrue(screenshot.width > 0 && screenshot.height > 0)

            val pixels = IntArray(screenshot.width * screenshot.height)
            screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
            val colours = HashSet<Int>()
            var nonBlack = 0
            var index = 0
            while (index < pixels.size) {
                val pixel = pixels[index]
                colours.add(pixel)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r + g + b > 40) nonBlack++
                index += 7
            }
            assertTrue("screenshot has too few colours: ${colours.size}", colours.size > 60)
            assertTrue("screenshot looks black", nonBlack > pixels.size / 7 / 2)

            val directory = File(
                instrumentation.targetContext.getExternalFilesDir(null) ?: instrumentation.targetContext.filesDir,
                "screenshots"
            ).apply { mkdirs() }
            val output = File(directory, "tv-smoke.png")
            FileOutputStream(output).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i("AquariumSmokeTest", "TV screenshot written to ${output.absolutePath}")
        } finally {
            scenario.close()
        }
    }
}
