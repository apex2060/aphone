package com.apex2060.softphone.config

import android.os.Build
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.push.ProvisionActions
import com.apex2060.softphone.push.PushRuntime
import com.apex2060.softphone.push.provisionRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the setup QR code carries: the endpoint to call and the key to present. */
data class ProvisionTicket(val url: String, val key: String?)

class RevokedException : IOException("This phone has been deactivated")

/**
 * Same QR and HTTP handling as 22 Phone. A JSON ticket is
 * POST {url} with {"key","device"} and header X-Provision-Key. A bare URL is a GET.
 * The Comm Manager QR's url is typically `.../_comm?action=phone.provision`.
 */
object Provisioner {

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
        SpikeLog.noteSecret(ticket.key)
        val conn = URL(ticket.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            val body = ticket.key?.let { key ->
                provisionRequestBody(
                    key = key,
                    device = JSONObject()
                        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                        .put("android", Build.VERSION.RELEASE)
                        .put("app", BuildConfig.VERSION_NAME),
                    token = PushRuntime.tokenForProvision(ticket.url),
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceId = ConfigStore.deviceId(),
                )
            }
            SpikeLog.append(
                "provision request ${if (body != null) "POST" else "GET"} ${ticket.url}" +
                    (if (ticket.key != null) "\nX-Provision-Key: [redacted]" else "")
            )
            if (body != null) {
                val bytes = body.toByteArray()
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Provision-Key", ticket.key)
                conn.outputStream.use { it.write(bytes) }
                SpikeLog.append("provision body $body")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 410) {
                SpikeLog.append("provision response HTTP 410\n$text")
                throw RevokedException()
            }
            if (code !in 200..299) {
                SpikeLog.append("provision response HTTP $code\n${text.take(800)}")
                throw IOException("Setup server returned HTTP $code")
            }
            val pass = runCatching { JSONObject(text).optJSONObject("sip")?.optString("password") }.getOrNull()
            SpikeLog.noteSecret(pass)
            SpikeLog.append("provision response HTTP $code\n$text")
            text to ProvisionConfig.parse(text)
        } finally {
            conn.disconnect()
        }
    }

    /** POST the same host with `action` replaced. 410 throws [RevokedException]. */
    fun postAction(ticket: ProvisionTicket, action: String, body: JSONObject): Int {
        SpikeLog.noteSecret(ticket.key)
        val url = ProvisionActions.withAction(ticket.url, action)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            if (ticket.key != null) conn.setRequestProperty("X-Provision-Key", ticket.key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 410) throw RevokedException()
            return code
        } finally {
            conn.disconnect()
        }
    }
}
