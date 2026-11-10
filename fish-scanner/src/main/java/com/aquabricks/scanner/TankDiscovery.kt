package com.aquabricks.scanner

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Finds the aquarium on the local network. The TV answers a UDP broadcast on 8766,
 * which is far more reliable on home routers than mDNS. Manual IP entry is always
 * available as a fallback.
 */
object TankDiscovery {

    private const val BEACON_PORT = 8766
    private const val DEFAULT_PORT = 8765
    private const val REQUEST = "AQUABRICKS_DISCOVER"

    data class Tank(
        val name: String,
        val host: String,
        val port: Int,
        val fish: Int
    )

    fun discover(timeoutMs: Long = 2400, callback: (List<Tank>) -> Unit) {
        Thread({
            val found = LinkedHashMap<String, Tank>()
            probeLocalhost(found)
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 180
                }
                val payload = REQUEST.toByteArray(StandardCharsets.UTF_8)
                val targets = broadcastTargets()
                val deadline = System.currentTimeMillis() + timeoutMs
                var sends = 0
                var nextSend = 0L

                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (sends < 3 && now >= nextSend) {
                        for (target in targets) {
                            try {
                                socket.send(DatagramPacket(payload, payload.size, target, BEACON_PORT))
                            } catch (_: Exception) {
                            }
                        }
                        sends++
                        nextSend = now + 550L
                    }
                    try {
                        val buffer = ByteArray(256)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                        val parts = text.split('|')
                        if (parts.size >= 4 && parts[0] == "AQUABRICKS") {
                            val host = packet.address?.hostAddress ?: continue
                            val port = parts[2].toIntOrNull() ?: continue
                            val fish = parts[3].toIntOrNull() ?: 0
                            val name = parts[1].ifBlank { "AquaBricks tank" }
                            found["$host:$port"] = Tank(name, host, port, fish)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Keep polling until the deadline.
                    }
                }
            } catch (_: Exception) {
                // No network, no discovery: the manual address field still works.
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
            val tanks = found.values.toList()
            Handler(Looper.getMainLooper()).post { callback(tanks) }
        }, "tank-discovery").start()
    }

    /**
     * Same-phone testing: when the aquarium and scanner run on the same device the
     * Wi-Fi broadcast may not be delivered back to the sender, so also ask
     * 127.0.0.1:8765. The tank's normal port is 8765 and it reports the actual port.
     */
    private fun probeLocalhost(found: MutableMap<String, Tank>) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("http://127.0.0.1:$DEFAULT_PORT/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 300
            connection.readTimeout = 300
            connection.requestMethod = "GET"
            if (connection.responseCode == 200) {
                val text = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(text)
                if (json.optString("app") == "aquabricks") {
                    val port = json.optInt("port", DEFAULT_PORT)
                    found["127.0.0.1:$port"] = Tank(
                        name = json.optString("name", "AquaBricks tank"),
                        host = "127.0.0.1",
                        port = port,
                        fish = json.optInt("fish", 0)
                    )
                }
            }
        } catch (_: Exception) {
            // No aquarium on this phone; broadcasting may still find the TV.
        } finally {
            connection?.disconnect()
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashMap<String, InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.interfaceAddresses) {
                    val inet = address.broadcast ?: continue
                    targets[inet.hostAddress ?: continue] = inet
                }
            }
        } catch (_: Exception) {
        }
        try {
            val global = InetAddress.getByName("255.255.255.255")
            targets[global.hostAddress ?: "255.255.255.255"] = global
        } catch (_: Exception) {
        }
        return targets.values.toList()
    }
}
