package com.aquabricks.aquarium

import java.io.InputStream

class PayloadTooLargeException : Exception("Request body is too large")
class RequestTimeoutException : Exception("Request took too long")

data class ParsedHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
)

/**
 * Minimal, allocation-conscious HTTP/1.1 request reader. Kept free of Android
 * types so it can be unit-tested on the JVM. Only the single request shape our
 * phone client sends is supported: one request per connection, Content-Length,
 * then close.
 */
object HttpRequestReader {

    const val MAX_HEAD_BYTES = 16 * 1024
    const val MAX_BODY_BYTES = 12L * 1024 * 1024

    fun read(input: InputStream, deadlineNanos: Long = Long.MAX_VALUE): ParsedHttpRequest? {
        // Fixed buffer, incremental terminator check: no cumulative toByteArray()
        // copy for every header byte on the three server workers.
        val head = ByteArray(MAX_HEAD_BYTES)
        var headSize = 0
        var terminated = false
        while (headSize < MAX_HEAD_BYTES) {
            checkDeadline(deadlineNanos)
            val byte = input.read()
            if (byte == -1) return null
            head[headSize++] = byte.toByte()
            if (headSize >= 4 &&
                head[headSize - 4] == CR && head[headSize - 3] == LF &&
                head[headSize - 2] == CR && head[headSize - 1] == LF
            ) {
                terminated = true
                break
            }
        }
        if (!terminated || headSize >= MAX_HEAD_BYTES) return null

        val lines = String(head, 0, headSize, Charsets.ISO_8859_1).split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val rawPath = requestLine[1]
        if (!rawPath.startsWith("/")) return null
        val path = rawPath.substringBefore('?')

        val headers = HashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }

        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0 || length > MAX_BODY_BYTES) throw PayloadTooLargeException()
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            checkDeadline(deadlineNanos)
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) return null
            offset += read
        }
        return ParsedHttpRequest(method, path, headers, body)
    }

    private fun checkDeadline(deadlineNanos: Long) {
        if (System.nanoTime() > deadlineNanos) throw RequestTimeoutException()
    }

    private const val CR = '\r'.code.toByte()
    private const val LF = '\n'.code.toByte()
}
