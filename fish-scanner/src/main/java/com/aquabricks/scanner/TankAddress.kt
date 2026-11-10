package com.aquabricks.scanner

/** Parses what a human types into the manual address field. */
object TankAddress {

    const val DEFAULT_PORT = 8765

    fun parse(raw: String): Pair<String, Int>? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        value = value.removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (value.isEmpty()) return null

        val firstColon = value.indexOf(':')
        val lastColon = value.lastIndexOf(':')
        if (firstColon > 0 && firstColon == lastColon) {
            val host = value.substring(0, lastColon).trim()
            val port = value.substring(lastColon + 1).toIntOrNull() ?: return null
            if (!isValidHost(host) || port !in 1..65535) return null
            return host to port
        }
        // No port, or an IPv6-style address we deliberately do not support in the
        // manual field (discovery supplies the correct value for those networks).
        if (value.contains(':')) return null
        if (!isValidHost(value)) return null
        return value to DEFAULT_PORT
    }

    private fun isValidHost(value: String): Boolean {
        if (value.isEmpty() || value.length > 253) return false
        return value.all { character ->
            character.isLetterOrDigit() || character == '.' || character == '-' || character == '_'
        }
    }
}
