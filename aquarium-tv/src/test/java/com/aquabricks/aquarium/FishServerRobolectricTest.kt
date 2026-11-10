package com.aquabricks.aquarium

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Exercises the real HTTP server, including PNG decoding, persistence and the
 * discovery beacon. Robolectric supplies the Android Bitmap implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FishServerRobolectricTest {

    @Test
    fun serverAcceptsListsAndDeletesAFish() {
        val directory = File.createTempFile("fish-server-test", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        val repository = FishRepository(directory)
        val added = AtomicReference<FishMeta>()
        val callbackBitmap = AtomicReference<Bitmap>()
        val latch = CountDownLatch(1)
        val server = FishServer(
            repository = repository,
            tankName = "Test Reef",
            onAdded = { meta, bitmap ->
                added.set(meta)
                callbackBitmap.set(bitmap)
                latch.countDown()
            },
            onDeleted = {},
            preferredHttpPorts = 0..0,
            enableBeacon = false
        )
        try {
            assertTrue(server.start())
            assertTrue(server.httpPort > 0)

            val health = request("GET", "http://127.0.0.1:${server.httpPort}/health")
            assertEquals(200, health.first)
            assertTrue(health.second.contains("\"fish\":0"))

            val headers = mapOf(
                "Content-Type" to "image/png",
                "X-Fish-Name" to URLEncoder.encode("Luna Reef", "UTF-8"),
                "X-Fish-Creator" to URLEncoder.encode("Mia", "UTF-8"),
                "X-Fish-Facing" to "left"
            )
            val upload = request(
                "POST",
                "http://127.0.0.1:${server.httpPort}/fish",
                headers,
                Base64.getDecoder().decode(ONE_PIXEL_PNG)
            )
            assertEquals(200, upload.first)
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals("Luna Reef", added.get()?.name)
            assertEquals("Mia", added.get()?.creator)
            assertEquals("left", added.get()?.facing)
            assertNotNull(callbackBitmap.get())
            assertEquals(1, repository.count())

            val list = request("GET", "http://127.0.0.1:${server.httpPort}/fish")
            assertEquals(200, list.first)
            assertTrue(list.second.contains("Luna Reef"))

            val deleted = request(
                "DELETE",
                "http://127.0.0.1:${server.httpPort}/fish/${added.get()!!.id}"
            )
            assertEquals(200, deleted.first)
            assertEquals(0, repository.count())
        } finally {
            server.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun serverRejectsNonPngBodies() {
        val directory = createTempDirectory()
        val repository = FishRepository(directory)
        val server = FishServer(repository, "Test Reef", { _, _ -> }, {}, 0..0, enableBeacon = false)
        try {
            assertTrue(server.start())
            val response = request(
                "POST",
                "http://127.0.0.1:${server.httpPort}/fish",
                mapOf("Content-Type" to "text/plain"),
                "not a png".toByteArray()
            )
            assertEquals(415, response.first)
            assertEquals(0, repository.count())
        } finally {
            server.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun transparentPngIsRejectedBeforeItConsumesATankSlot() {
        val directory = createTempDirectory()
        val repository = FishRepository(directory)
        val server = FishServer(repository, "Test Reef", { _, _ -> }, {}, 0..0, enableBeacon = false)
        val transparent = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            transparent.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        transparent.recycle()
        try {
            assertTrue(server.start())
            val response = request(
                "POST",
                "http://127.0.0.1:${server.httpPort}/fish",
                mapOf("Content-Type" to "image/png", "X-Fish-Name" to "Invisible"),
                bytes
            )
            assertEquals(422, response.first)
            assertEquals(0, repository.count())
        } finally {
            server.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFullTankReturnsAConflictError() {
        val directory = createTempDirectory()
        val repository = FishRepository(directory)
        repeat(FishRepository.MAX_FISH) { index ->
            repository.add("Fish $index", "", "right", byteArrayOf(1), 1, 1)
        }
        val server = FishServer(repository, "Test Reef", { _, _ -> }, {}, 0..0, enableBeacon = false)
        try {
            assertTrue(server.start())
            val upload = request(
                "POST",
                "http://127.0.0.1:${server.httpPort}/fish",
                mapOf(
                    "Content-Type" to "image/png",
                    "X-Fish-Name" to "Too Many"
                ),
                Base64.getDecoder().decode(ONE_PIXEL_PNG)
            )
            assertEquals(409, upload.first)
            assertTrue(upload.second.contains("full"))
            assertEquals(FishRepository.MAX_FISH, repository.count())
        } finally {
            server.stop()
            directory.deleteRecursively()
        }
    }

    @Test
    fun discoveryBeaconAnswersThePhoneBroadcast() {
        val directory = createTempDirectory()
        val repository = FishRepository(directory)
        val probe = DatagramSocket(0)
        val freePort = probe.localPort
        probe.close()
        val server = FishServer(
            repository = repository,
            tankName = "Beacon Reef",
            onAdded = { _, _ -> },
            onDeleted = {},
            preferredHttpPorts = 0..0,
            beaconPort = freePort,
            enableBeacon = true
        )
        val client = DatagramSocket()
        try {
            assertTrue(server.start())
            client.soTimeout = 2500
            Thread.sleep(150)
            val message = "AQUABRICKS_DISCOVER".toByteArray(StandardCharsets.UTF_8)
            client.send(DatagramPacket(message, message.size, InetAddress.getByName("127.0.0.1"), freePort))
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            client.receive(packet)
            val reply = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
            assertTrue(reply, reply.startsWith("AQUABRICKS|Beacon Reef|${server.httpPort}|"))
        } finally {
            client.close()
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
            val connection = (URL(url).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = method
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
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
        worker.join(6000)
        return result ?: (0 to "timeout")
    }

    private fun createTempDirectory(): File {
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "aquabricks-server-${System.currentTimeMillis()}-${Random.nextInt(100000)}"
        )
        file.mkdirs()
        return file
    }

    companion object {
        private const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAW0lEQVR4nGNgGAWM2AQfxJj9xyausOQUhnomYjXjkmMhRrPUvCMINQw2/5FdAncBMZphfGS1GF4gFVDPAGwhzMDAwPAsyQaDj6wWQxO+WMBmEYYXcLmEkNwQBgC4mCWqXV1P7wAAAABJRU5ErkJggg=="
    }
}
