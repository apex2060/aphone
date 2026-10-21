package com.apex2060.softphone.config

import android.os.Build
import com.apex2060.softphone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the setup QR code carries: the endpoint to call and the key to present. */
data class ProvisionTicket(val url: String, val key: String?)

class RevokedException : IOException("This phone has been deactivated")

object Provisioner {

    /**
     * Accepts either `{"url": "...", "key": "..."}` or a bare URL (GET, key embedded in the URL).
     * Release builds accept only `https://` URLs. Debug builds also accept `http://` for a LAN server.
     */
    fun parseQr(text: String): ProvisionTicket? {
        val t = text.trim()
        val ticket = when {
            t.startsWith("{") -> runCatching {
                val o = JSONObject(t)
                ProvisionTicket(o.getString("url"), o.optString("key").ifBlank { null })
            }.getOrNull()
            t.startsWith("https://") || t.startsWith("http://") -> ProvisionTicket(t, null)
            else -> null
        } ?: return null
        val secure = ticket.url.startsWith("https://")
        return if (secure || (BuildConfig.DEBUG && ticket.url.startsWith("http://"))) ticket else null
    }

    /** Fetches the provisioning document. Returns the raw JSON and the parsed config. */
    suspend fun fetch(ticket: ProvisionTicket): Pair<String, ProvisionConfig> = withContext(Dispatchers.IO) {
        val conn = URL(ticket.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (ticket.key != null) {
                val body = JSONObject()
                    .put("key", ticket.key)
                    .put("device", JSONObject()
                        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("android", Build.VERSION.RELEASE)
                        .put("app", BuildConfig.VERSION_NAME))
                    .toString().toByteArray()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Provision-Key", ticket.key)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 410) throw RevokedException()
            if (code !in 200..299) throw IOException("Setup server returned HTTP $code")
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            json to ProvisionConfig.parse(json)
        } finally {
            conn.disconnect()
        }
    }
}
