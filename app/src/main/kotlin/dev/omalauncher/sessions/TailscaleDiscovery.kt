package dev.omalauncher.sessions

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only control-plane discovery. Connecting still uses the phone's existing VPN + SSH. */
object TailscaleDiscovery {
    fun fetch(tailnet: String, apiKey: String): List<Machine> {
        require(apiKey.isNotBlank()) { "Enter a Tailscale API access token" }
        val net = java.net.URLEncoder.encode(tailnet.ifBlank { "-" }, "UTF-8")
        val connection = URL("https://api.tailscale.com/api/v2/tailnet/$net/devices").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            check(code == 200) {
                when (code) {
                    401, 403 -> "Tailscale denied discovery (HTTP $code). The token is still saved; check expiry, revocation, device-read access, and tailnet."
                    429 -> "Tailscale rate limit reached. Try again later."
                    else -> "Tailscale discovery failed (HTTP $code)"
                }
            }
            val text = connection.inputStream.bufferedReader().use { input ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(result.length + size <= 2_000_000) { "Tailscale device list is too large" }
                    result.append(buffer, 0, size)
                }
                result.toString()
            }
            return parse(text)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(text: String): List<Machine> = JSONObject(text).getJSONArray("devices").mapObjects { device ->
        val addresses = device.getJSONArray("addresses")
        val ips = (0 until addresses.length()).map { addresses.getString(it) }
        val host = ips.firstOrNull { ':' !in it } ?: ips.firstOrNull().orEmpty()
        Machine(id = "tailscale-${device.getString("id")}", name = device.optString("hostname").ifBlank { device.getString("name") },
            host = host, lastSeen = device.optString("lastSeen"))
    }.filter { it.host.isNotBlank() }
}
