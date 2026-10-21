package com.apex2060.softphone.record

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object RecordingFiles {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.US)

    fun numberPart(number: String): String {
        val cleaned = number.trim().replace(Regex("[^0-9+#*]"), "")
        return cleaned.ifEmpty { "unknown" }.take(40)
    }

    fun savedName(timeMillis: Long, number: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val formatted = stamp.format(Instant.ofEpochMilli(timeMillis).atZone(zone))
        return "${formatted}_${numberPart(number)}.wav"
    }

    fun unique(dir: File, name: String): File {
        dir.mkdirs()
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        var file = first
        while (file.exists()) {
            file = File(dir, "${stem}_$n$ext")
            n++
        }
        return file
    }

    fun orphans(files: List<File>, keepAbsolute: String?): List<File> =
        files.filter { it.isFile && it.absolutePath != keepAbsolute }

    fun move(from: File, to: File): Boolean {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return true
        return try {
            from.copyTo(to, overwrite = true)
            from.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
