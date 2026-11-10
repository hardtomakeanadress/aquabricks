package com.aquabricks.scanner

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

/**
 * Drives the real manager screen against a tiny fake tank: it lists a fish,
 * confirms removal, calls DELETE, and refreshes to an empty tank.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManageTankActivityTest {

    @Test
    fun removeFishThroughTheManagerUi() {
        val server = FakeTankServer()
        val intent = Intent(RuntimeEnvironment.getApplication(), ManageTankActivity::class.java)
            .putExtra(ManageTankActivity.EXTRA_HOST, "127.0.0.1")
            .putExtra(ManageTankActivity.EXTRA_PORT, server.port)
            .putExtra(ManageTankActivity.EXTRA_TANK_NAME, "Test Reef")
        val controller = Robolectric.buildActivity(ManageTankActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertTrue(
                "timed out waiting for the fish list",
                waitUntil { findText(activity.window.decorView, "Luna") != null }
            )
            val remove = findText(activity.window.decorView, "REMOVE")
            assertNotNull("REMOVE action did not render", remove)
            remove!!.performClick()
            idle()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull("remove confirmation did not appear", dialog)
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idle()
            assertTrue(
                "timed out waiting for DELETE and the empty tank state",
                waitUntil {
                    server.deleted &&
                        findText(activity.window.decorView, "NO FISH IN THE TANK YET.") != null
                }
            )
            assertTrue("the DELETE request never reached the tank", server.deleted)
            assertEquals("Luna should be gone", null, findText(activity.window.decorView, "Luna"))
        } finally {
            controller.pause().stop().destroy()
            server.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(80)
        }
        idle()
        return condition()
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
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

    private class FakeTankServer {
        private val socket = ServerSocket(0)
        val port: Int = socket.localPort
        @Volatile var deleted = false

        private val worker = Thread {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                        val requestLine = reader.readLine().orEmpty()
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        if (contentLength > 0) {
                            repeat(contentLength) { reader.read() }
                        }
                        val path = requestLine.split(' ').getOrNull(1).orEmpty()
                        val body = when {
                            requestLine.startsWith("DELETE ") && path == "/fish/luna" -> {
                                deleted = true
                                """{"ok":true,"fish":0}"""
                            }
                            requestLine.startsWith("GET ") && path == "/fish" -> {
                                if (deleted) {
                                    "[]"
                                } else {
                                    """[{"id":"luna","name":"Luna","creator":"Mia","facing":"right","addedAt":1,"width":10,"height":10}]"""
                                }
                            }
                            else -> """{"ok":false,"error":"not found"}"""
                        }
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        val status = if (requestLine.startsWith("DELETE ") || requestLine.startsWith("GET ")) 200 else 404
                        client.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status OK\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${bytes.size}\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)
                            )
                            write(bytes)
                            flush()
                        }
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
            worker.join(1000)
        }
    }
}
