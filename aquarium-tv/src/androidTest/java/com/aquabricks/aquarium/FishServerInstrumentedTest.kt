package com.aquabricks.aquarium

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device verification of the real Android HTTP server, real BitmapFactory
 * decoding and real filesystem persistence.
 */
@RunWith(AndroidJUnit4::class)
class FishServerInstrumentedTest {

    @Test
    fun uploadIsPersistedListedAndDeletedOverHttp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "server-test-${System.nanoTime()}").apply { mkdirs() }
        val repository = FishRepository(directory)
        val added = AtomicReference<FishMeta>()
        val bitmap = AtomicReference<Bitmap>()
        val latch = CountDownLatch(1)
        val server = FishServer(
            repository = repository,
            tankName = "Instrumented Reef",
            onAdded = { meta, image ->
                added.set(meta)
                bitmap.set(image)
                latch.countDown()
            },
            onDeleted = {},
            preferredHttpPorts = 0..0,
            enableBeacon = false
        )
        try {
            assertTrue(server.start())
            assertTrue(server.httpPort > 0)

            val upload = request(
                "POST",
                "http://127.0.0.1:${server.httpPort}/fish",
                mapOf(
                    "Content-Type" to "image/png",
                    "X-Fish-Name" to URLEncoder.encode("Luna Reef", "UTF-8"),
                    "X-Fish-Creator" to URLEncoder.encode("Mia", "UTF-8"),
                    "X-Fish-Facing" to "right"
                ),
                Base64.getDecoder().decode(TEST_PNG)
            )
            assertEquals(200, upload.first)
            assertTrue(latch.await(4, TimeUnit.SECONDS))
            assertNotNull(bitmap.get())
            assertEquals("Luna Reef", added.get()?.name)
            assertEquals(1, repository.count())
            assertTrue(repository.fileFor(added.get()!!).exists())

            val list = request("GET", "http://127.0.0.1:${server.httpPort}/fish")
            assertTrue(list.second.contains("Luna Reef"))

            val persisted = FishRepository(directory)
            assertEquals(1, persisted.count())

            val delete = request(
                "DELETE",
                "http://127.0.0.1:${server.httpPort}/fish/${added.get()!!.id}"
            )
            assertEquals(200, delete.first)
            assertEquals(0, repository.count())
        } finally {
            server.stop()
            directory.deleteRecursively()
        }
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null
    ): Pair<Int, String> {
        var result: Pair<Int, String>? = null
        val worker = Thread {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                if (body != null) {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                result = code to text
            } finally {
                connection.disconnect()
            }
        }
        worker.start()
        worker.join(7000)
        return result ?: (0 to "timeout")
    }

    companion object {
        private const val TEST_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAW0lEQVR4nGNgGAWM2AQfxJj9xyausOQUhnomYjXjkmMhRrPUvCMINQw2/5FdAncBMZphfGS1GF4gFVDPAGwhzMDAwPAsyQaDj6wWQxO+WMBmEYYXcLmEkNwQBgC4mCWqXV1P7wAAAABJRU5ErkJggg=="
    }
}
