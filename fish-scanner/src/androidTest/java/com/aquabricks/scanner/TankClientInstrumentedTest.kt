package com.aquabricks.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives TankClient against a real local HTTP socket, verifying method, path,
 * encoded headers, exact PNG body, success parsing and server error surfacing.
 */
@RunWith(AndroidJUnit4::class)
class TankClientInstrumentedTest {

    @Test
    fun uploadSendsEncodedMetadataAndExactPng() {
        val server = MockServer(200, """{"ok":true,"fish":7}""")
        try {
            val outcome = TankClient.sendFish(
                host = "127.0.0.1",
                port = server.port,
                name = "Luna Reef",
                creator = "Mia",
                facing = "left",
                png = PNG_BYTES
            )
            assertTrue(outcome.ok)
            assertEquals(7, outcome.fishCount)

            val request = server.captured.get()
            assertNotNull("server did not receive a request", request)
            assertEquals("POST", request!!.method)
            assertEquals("/fish", request.path)
            assertEquals("Luna Reef", URLDecoder.decode(request.headers["x-fish-name"].orEmpty(), "UTF-8"))
            assertEquals("Mia", URLDecoder.decode(request.headers["x-fish-creator"].orEmpty(), "UTF-8"))
            assertEquals("left", request.headers["x-fish-facing"])
            assertEquals("image/png", request.headers["content-type"])
            assertArrayEquals(PNG_BYTES, request.body)
        } finally {
            server.close()
        }
    }

    @Test
    fun listFishParsesTheTankContents() {
        val server = MockServer(
            200,
            """[{"id":"a1","name":"Luna","creator":"Mia","facing":"right","addedAt":11,"width":10,"height":10},
                {"id":"b2","name":"Nemo","creator":"","facing":"left","addedAt":12,"width":8,"height":8}]"""
        )
        try {
            val fish = TankClient.listFish("127.0.0.1", server.port)
            assertNotNull(fish)
            assertEquals(2, fish!!.size)
            assertEquals("Luna", fish[0].name)
            assertEquals("Mia", fish[0].creator)
            assertEquals("a1", fish[0].id)
            val request = server.captured.get()
            assertEquals("GET", request!!.method)
            assertEquals("/fish", request.path)
        } finally {
            server.close()
        }
    }

    @Test
    fun deleteFishCallsTheDeleteEndpoint() {
        val server = MockServer(200, """{"ok":true,"fish":0}""")
        try {
            assertTrue(TankClient.deleteFish("127.0.0.1", server.port, "a1"))
            val request = server.captured.get()
            assertEquals("DELETE", request!!.method)
            assertEquals("/fish/a1", request.path)
        } finally {
            server.close()
        }
    }

    @Test
    fun serverErrorMessageReachesTheUi() {
        val server = MockServer(409, """{"ok":false,"error":"The tank is full."}""")
        try {
            val outcome = TankClient.sendFish(
                host = "127.0.0.1",
                port = server.port,
                name = "Nemo",
                creator = "",
                facing = "right",
                png = PNG_BYTES
            )
            assertFalse(outcome.ok)
            assertTrue(outcome.message, outcome.message.contains("full"))
        } finally {
            server.close()
        }
    }

    private class MockServer(private val code: Int, private val responseBody: String) {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        val captured = AtomicReference<CapturedRequest?>()
        private val worker = Thread {
            try {
                socket.accept().use { client ->
                    val input = BufferedInputStream(client.getInputStream())
                    captured.set(readRequest(input))
                    val body = responseBody.toByteArray(StandardCharsets.UTF_8)
                    val header = "HTTP/1.1 $code Test\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(header.toByteArray(StandardCharsets.ISO_8859_1))
                        write(body)
                        flush()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            worker.join(2000)
        }
    }

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    companion object {
        private val PNG_BYTES: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAW0lEQVR4nGNgGAWM2AQfxJj9xyausOQUhnomYjXjkmMhRrPUvCMINQw2/5FdAncBMZphfGS1GF4gFVDPAGwhzMDAwPAsyQaDj6wWQxO+WMBmEYYXcLmEkNwQBgC4mCWqXV1P7wAAAABJRU5ErkJggg=="
        )

        private fun readRequest(input: InputStream): CapturedRequest {
            val head = ByteArrayOutputStream()
            while (head.size() < 32768) {
                val byte = input.read()
                if (byte == -1) break
                head.write(byte)
                val bytes = head.toByteArray()
                val size = bytes.size
                if (size >= 4 && bytes[size - 4] == '\r'.code.toByte() &&
                    bytes[size - 3] == '\n'.code.toByte() &&
                    bytes[size - 2] == '\r'.code.toByte() &&
                    bytes[size - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            val lines = String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n")
            val requestLine = lines[0].split(" ")
            val headers = HashMap<String, String>()
            for (index in 1 until lines.size) {
                val colon = lines[index].indexOf(':')
                if (colon <= 0) continue
                headers[lines[index].substring(0, colon).trim().lowercase()] =
                    lines[index].substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                val read = input.read(body, offset, body.size - offset)
                if (read < 0) break
                offset += read
            }
            return CapturedRequest(requestLine[0], requestLine[1], headers, body)
        }
    }
}
