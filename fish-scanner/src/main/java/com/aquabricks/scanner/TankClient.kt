package com.aquabricks.scanner

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TankClient {

    data class SendOutcome(
        val ok: Boolean,
        val message: String,
        val fishCount: Int = -1
    )

    data class FishSummary(
        val id: String,
        val name: String,
        val creator: String,
        val addedAt: Long
    )

    fun sendFish(
        host: String,
        port: Int,
        name: String,
        creator: String,
        facing: String,
        png: ByteArray
    ): SendOutcome {
        val connection: HttpURLConnection = try {
            URL("http://$host:$port/fish").openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return SendOutcome(
                false,
                "Could not reach the tank. Check that the TV app is open and you are on the same Wi-Fi."
            )
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 6000
            connection.readTimeout = 12000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "image/png")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Fish-Name", URLEncoder.encode(name, "UTF-8"))
            connection.setRequestProperty("X-Fish-Creator", URLEncoder.encode(creator, "UTF-8"))
            connection.setRequestProperty("X-Fish-Facing", facing)
            connection.setFixedLengthStreamingMode(png.size)
            connection.outputStream.use { it.write(png) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                null
            }
            if (code in 200..299) {
                SendOutcome(true, "OK", json?.optInt("fish", -1) ?: -1)
            } else {
                val error = json?.optString("error").orEmpty().ifBlank {
                    "The tank answered with error $code."
                }
                SendOutcome(false, error)
            }
        } catch (_: SocketTimeoutException) {
            SendOutcome(false, "The tank took too long to answer.")
        } catch (_: Exception) {
            SendOutcome(
                false,
                "Could not reach the tank. Check that the TV app is open and you are on the same Wi-Fi."
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Returns null when the tank cannot be reached; an empty list when it is empty. */
    fun listFish(host: String, port: Int): List<FishSummary>? {
        val connection: HttpURLConnection = try {
            URL("http://$host:$port/fish").openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                FishSummary(
                    id = id,
                    name = item.optString("name", "Fishy"),
                    creator = item.optString("creator", ""),
                    addedAt = item.optLong("addedAt", 0L)
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun deleteFish(host: String, port: Int, id: String): Boolean {
        val connection: HttpURLConnection = try {
            URL("http://$host:$port/fish/${URLEncoder.encode(id, "UTF-8")}")
                .openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return false
        }
        return try {
            connection.requestMethod = "DELETE"
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
