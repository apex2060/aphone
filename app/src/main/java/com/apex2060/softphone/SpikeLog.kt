package com.apex2060.softphone

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Timestamped log. The active file rotates at 1 MB and one previous file is
 * kept, so the pair stays around 2 MB. Secrets are redacted before the write.
 */
object SpikeLog {
    private const val MAX_BYTES = 1_000_000
    private const val TAG = "SipSpike"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val secrets = mutableListOf<String>()
    private val lock = Any()
    private var active: File? = null
    private var rotated: File? = null
    var listener: ((String) -> Unit)? = null

    fun init(context: Context) {
        val dir = context.filesDir
        active = File(dir, "sipspike.log")
        rotated = File(dir, "sipspike.log.1")
    }

    fun noteSecret(value: String?) {
        if (value.isNullOrBlank() || value.length < 3) return
        synchronized(secrets) {
            if (value !in secrets) secrets += value
        }
    }

    fun append(message: String) {
        val clean = LogSanitizer.redact(message, secretsSnapshot()).trimEnd()
        if (clean.isEmpty()) return
        val now = LocalDateTime.now().format(stamp)
        val block = clean.lineSequence()
            .filter { it.isNotBlank() }
            .joinToString("\n") { "$now $it" }
        if (block.isEmpty()) return
        synchronized(lock) { write(block) }
        Log.i(TAG, block)
        listener?.invoke(block)
    }

    fun text(): String = synchronized(lock) {
        val older = rotated?.takeIf { it.exists() }?.readText().orEmpty()
        val current = active?.takeIf { it.exists() }?.readText().orEmpty()
        LogSanitizer.redact(older + current, secretsSnapshot())
    }

    fun clear() {
        synchronized(lock) {
            active?.delete()
            rotated?.delete()
        }
    }

    private fun write(block: String) {
        val file = active ?: return
        val previous = rotated
        if (file.exists() && file.length() + block.length > MAX_BYTES && previous != null) {
            if (previous.exists()) previous.delete()
            if (!file.renameTo(previous)) file.writeText("")
        }
        file.appendText(block + "\n")
    }

    private fun secretsSnapshot(): List<String> = synchronized(secrets) { secrets.toList() }
}
