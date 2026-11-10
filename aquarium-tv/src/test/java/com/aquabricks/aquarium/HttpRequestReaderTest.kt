package com.aquabricks.aquarium

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpRequestReaderTest {

    @Test
    fun parsesGetWithQueryAndNormalisesHeaderNames() {
        val raw = "GET /health?verbose=1 HTTP/1.1\r\nHost: tank\r\nX-Fish-Name: Luna\r\n\r\n"
        val request = HttpRequestReader.read(stream(raw))!!
        assertEquals("GET", request.method)
        assertEquals("/health", request.path)
        assertEquals("tank", request.headers["host"])
        assertEquals("Luna", request.headers["x-fish-name"])
        assertEquals(0, request.body.size)
    }

    @Test
    fun parsesPostBodyByteForByte() {
        val body = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0xFF.toByte(), 13, 10)
        val prefix = "POST /fish HTTP/1.1\r\nContent-Length: ${body.size}\r\nX-Fish-Facing: right\r\n\r\n"
        val request = HttpRequestReader.read(stream(prefix, body))!!
        assertEquals("POST", request.method)
        assertEquals("/fish", request.path)
        assertEquals("right", request.headers["x-fish-facing"])
        assertArrayEquals(body, request.body)
    }

    @Test
    fun truncatesQueryStringFromPath() {
        val raw = "DELETE /fish/abc?ignored=1 HTTP/1.1\r\nContent-Length: 0\r\n\r\n"
        val request = HttpRequestReader.read(stream(raw))!!
        assertEquals("/fish/abc", request.path)
    }

    @Test
    fun truncatedBodyReturnsNull() {
        val raw = "POST /fish HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"
        assertNull(HttpRequestReader.read(stream(raw)))
    }

    @Test
    fun oversizedBodyIsRejectedBeforeAllocation() {
        val raw = "POST /fish HTTP/1.1\r\nContent-Length: ${HttpRequestReader.MAX_BODY_BYTES + 1}\r\n\r\n"
        assertThrows(PayloadTooLargeException::class.java) {
            HttpRequestReader.read(stream(raw))
        }
    }

    @Test
    fun garbageRequestLineReturnsNull() {
        assertNull(HttpRequestReader.read(stream("HELLO\r\n\r\n")))
        assertNull(HttpRequestReader.read(stream("GET\r\n\r\n")))
        assertNull(HttpRequestReader.read(stream("GET relative/path HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun missingHeaderTerminatorReturnsNull() {
        assertNull(HttpRequestReader.read(stream("GET /health HTTP/1.1\r\nHost: tank")))
    }

    @Test
    fun expiredRequestDeadlineIsRejected() {
        assertThrows(RequestTimeoutException::class.java) {
            HttpRequestReader.read(
                stream("GET /health HTTP/1.1\r\nHost: tank\r\n\r\n"),
                System.nanoTime() - 1L
            )
        }
    }

    private fun stream(text: String, body: ByteArray = ByteArray(0)): ByteArrayInputStream {
        return ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1) + body)
    }
}
