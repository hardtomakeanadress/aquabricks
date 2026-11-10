package com.aquabricks.aquarium

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tiny LAN HTTP server baked into the tank.
 *
 *   GET    /health      liveness + fish count
 *   GET    /fish        JSON list of fish
 *   POST   /fish        PNG body + X-Fish-Name / X-Fish-Creator / X-Fish-Facing headers
 *   DELETE /fish/<id>   remove a fish
 *   GET    /            a small browser page for debugging
 *
 * A UDP beacon on 8766 answers the phone's broadcast, so nobody has to type an IP.
 */
class FishServer(
    private val repository: FishRepository,
    private val tankName: String,
    private val onAdded: (FishMeta, Bitmap) -> Unit,
    private val onDeleted: (String) -> Unit,
    private val preferredHttpPorts: IntRange = 8765..8775,
    private val beaconPort: Int = 8766,
    private val enableBeacon: Boolean = true
) {
    @Volatile
    var httpPort: Int = 0
        private set

    @Volatile
    var localIp: String = "0.0.0.0"
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var beaconSocket: DatagramSocket? = null
    private var acceptThread: Thread? = null
    private var beaconThread: Thread? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private var workers = newWorkerPool()

    private fun newWorkerPool() = ThreadPoolExecutor(
        3, 3, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(6),
        { runnable ->
            Thread(runnable, "fish-http-worker").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun start(): Boolean {
        if (isRunning) return true
        var bound: ServerSocket? = null
        for (port in preferredHttpPorts) {
            try {
                bound = ServerSocket(port)
                httpPort = bound.localPort
                break
            } catch (_: IOException) {
                // try the next one
            }
        }
        if (bound == null) return false
        if (workers.isShutdown) workers = newWorkerPool()
        serverSocket = bound
        localIp = findLocalIp()
        isRunning = true

        acceptThread = thread(name = "fish-accept", isDaemon = true) {
            while (isRunning) {
                try {
                    val socket = bound.accept()
                    clients.add(socket)
                    try {
                        workers.execute {
                            try {
                                handleClient(socket)
                            } finally {
                                clients.remove(socket)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        // The bounded queue is full. Close instead of accumulating
                        // accepted-but-unserved sockets and file descriptors.
                        clients.remove(socket)
                        closeQuietly(socket)
                    } catch (_: Exception) {
                        clients.remove(socket)
                        closeQuietly(socket)
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
        startBeacon()
        return true
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { beaconSocket?.close() } catch (_: Exception) {}
        beaconSocket = null
        // Closing the active sockets interrupts slow header/body reads before the
        // executor wait. Repository mutations also use a directory-wide lock, so a
        // worker that is already inside add/delete cannot overwrite a new tank.
        clients.toList().forEach { closeQuietly(it) }
        workers.shutdownNow()
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Also close sockets queued between the first snapshot and shutdown.
        clients.toList().forEach { closeQuietly(it) }
        clients.clear()
        acceptThread = null
        beaconThread = null
    }

    // ---------------------------------------------------------------- HTTP

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = READ_POLL_MS
                val deadline = System.nanoTime() + REQUEST_TIMEOUT_MS * 1_000_000L
                val request = HttpRequestReader.read(s.getInputStream(), deadline)
                if (request == null) {
                    respond(s.getOutputStream(), 400, jsonError("Bad request"), "application/json")
                    return
                }
                route(request, s.getOutputStream())
            } catch (_: RequestTimeoutException) {
                quietRespond(s, 408, jsonError("The request took too long."), "application/json")
            } catch (_: SocketTimeoutException) {
                quietRespond(s, 408, jsonError("The request took too long."), "application/json")
            } catch (e: PayloadTooLargeException) {
                quietRespond(s, 413, jsonError("That image is too large."), "application/json")
            } catch (_: Exception) {
                quietRespond(s, 500, jsonError("The tank had a hiccup."), "application/json")
            }
        }
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: Exception) {}
    }

    private fun route(request: ParsedHttpRequest, out: OutputStream) {
        val path = request.path
        when {
            request.method == "GET" && (path == "/health" || path == "/info") -> {
                val body = JSONObject()
                    .put("app", "aquabricks")
                    .put("role", "aquarium")
                    .put("name", tankName)
                    .put("fish", repository.count())
                    .put("port", httpPort)
                    .put("version", 1)
                    .toString()
                respond(out, 200, body, "application/json")
            }

            request.method == "GET" && path == "/fish" -> {
                respond(out, 200, fishJson().toString(), "application/json")
            }

            request.method == "POST" && path == "/fish" -> {
                handleUpload(request, out)
            }

            request.method == "DELETE" && path.startsWith("/fish/") -> {
                val id = URLDecoder.decode(path.removePrefix("/fish/"), "UTF-8")
                if (repository.delete(id)) {
                    onDeleted(id)
                    respond(out, 200, JSONObject().put("ok", true).put("fish", repository.count()).toString(), "application/json")
                } else {
                    respond(out, 404, jsonError("No fish with that id."), "application/json")
                }
            }

            request.method == "GET" && (path == "/" || path == "/index.html") -> {
                respond(out, 200, infoPage(), "text/html; charset=utf-8")
            }

            else -> respond(out, 404, jsonError("Not found."), "application/json")
        }
    }

    private fun handleUpload(request: ParsedHttpRequest, out: OutputStream) {
        val png = request.body
        if (png.size < 8 || png[0] != 0x89.toByte() || png[1] != 0x50.toByte() ||
            png[2] != 0x4E.toByte() || png[3] != 0x47.toByte()
        ) {
            respond(out, 415, jsonError("Send a PNG image."), "application/json")
            return
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > 2048 || bounds.outHeight > 2048
        ) {
            respond(out, 422, jsonError("That image could not be read."), "application/json")
            return
        }

        val bitmap = try {
            BitmapFactory.decodeByteArray(png, 0, png.size)
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            respond(out, 422, jsonError("That image could not be read."), "application/json")
            return
        }
        if (!hasVisiblePixels(bitmap)) {
            bitmap.recycle()
            respond(out, 422, jsonError("That image has no visible fish."), "application/json")
            return
        }

        val name = headerValue(request, "x-fish-name").ifBlank { "Fishy" }
        val creator = headerValue(request, "x-fish-creator")
        val facing = headerValue(request, "x-fish-facing").ifBlank { "right" }
        val meta = try {
            repository.add(name, creator, facing, png, bounds.outWidth, bounds.outHeight)
        } catch (e: TankFullException) {
            bitmap.recycle()
            respond(out, 409, jsonError("The tank is full - remove a fish first."), "application/json")
            return
        } catch (e: IOException) {
            bitmap.recycle()
            respond(out, 507, jsonError("The tank could not save that fish."), "application/json")
            return
        }

        onAdded(meta, bitmap)
        val body = JSONObject()
            .put("ok", true)
            .put("id", meta.id)
            .put("name", meta.name)
            .put("fish", repository.count())
            .toString()
        respond(out, 200, body, "application/json")
    }

    private fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { Color.alpha(it) > 12 }) return true
        }
        return false
    }

    private fun headerValue(request: ParsedHttpRequest, key: String): String {
        val raw = request.headers[key] ?: return ""
        return try {
            URLDecoder.decode(raw, "UTF-8").take(32)
        } catch (_: Exception) {
            raw.take(32)
        }
    }

    private fun fishJson(): JSONArray {
        val array = JSONArray()
        for (m in repository.entries()) {
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("creator", m.creator)
                    .put("facing", m.facing)
                    .put("addedAt", m.addedAt)
                    .put("width", m.width)
                    .put("height", m.height)
            )
        }
        return array
    }

    private fun respond(out: OutputStream, code: Int, body: String, contentType: String) {
        respond(out, code, body.toByteArray(StandardCharsets.UTF_8), contentType)
    }

    private fun respond(out: OutputStream, code: Int, body: ByteArray, contentType: String) {
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            408 -> "Request Timeout"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            422 -> "Unprocessable Entity"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        try {
            val header = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1)
            out.write(header)
            out.write(body)
            out.flush()
        } catch (_: Exception) {
            // Client went away.
        }
    }

    private fun quietRespond(socket: Socket, code: Int, body: String, contentType: String) {
        try {
            respond(socket.getOutputStream(), code, body, contentType)
        } catch (_: Exception) {
        }
    }

    private fun jsonError(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    private fun infoPage(): String {
        val fishRows = repository.entries().joinToString("") { m ->
            "<tr><td>${escapeHtml(m.name)}</td><td>${escapeHtml(m.creator.ifBlank { "-" })}</td>" +
                "<td>${m.width}x${m.height}</td><td>${m.facing}</td></tr>"
        }
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>$tankName</title>
            <style>
            body{font-family:ui-monospace,monospace;background:#0e2a36;color:#fff4d6;padding:24px;max-width:760px;margin:auto}
            h1{color:#ffd166}table{border-collapse:collapse;width:100%}td,th{padding:8px;border-bottom:1px solid #2a6b72;text-align:left}
            code{background:#163e49;padding:2px 6px;border-radius:6px}
            </style></head><body>
            <h1>$tankName</h1>
            <p>${repository.count()} fish &middot; AquaBricks aquarium at <code>${localIp}:${httpPort}</code></p>
            <p>Open the <b>AquaBricks Scanner</b> app on a phone connected to the same Wi-Fi,
               scan a LEGO fish, and tap <b>Send to tank</b>.</p>
            <table><tr><th>Name</th><th>Made by</th><th>Size</th><th>Facing</th></tr>$fishRows</table>
            <p style="opacity:.6">POST a PNG to <code>/fish</code> with
               <code>X-Fish-Name</code>, <code>X-Fish-Creator</code>, <code>X-Fish-Facing</code>.</p>
            </body></html>
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ------------------------------------------------------------- discovery

    private fun startBeacon() {
        if (!enableBeacon) return
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 800
                bind(InetSocketAddress(beaconPort))
            }
        } catch (_: Exception) {
            return
        }
        beaconSocket = socket
        beaconThread = thread(name = "fish-beacon", isDaemon = true) {
            val buffer = ByteArray(256)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                    if (text == DISCOVER_REQUEST) {
                        val reply = "AQUABRICKS|$tankName|$httpPort|${repository.count()}"
                            .toByteArray(StandardCharsets.UTF_8)
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                } catch (_: SocketTimeoutException) {
                    // loop around and check isRunning
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
    }

    private fun findLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress && address.isSiteLocalAddress
                    ) {
                        return address.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "0.0.0.0"
    }

    companion object {
        private const val DISCOVER_REQUEST = "AQUABRICKS_DISCOVER"
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val READ_POLL_MS = 2_000
        const val TANK_DISPLAY_NAME = "The Living Room Reef"
    }
}
