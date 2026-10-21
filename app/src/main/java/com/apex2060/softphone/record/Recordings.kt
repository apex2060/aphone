package com.apex2060.softphone.record

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.phone.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

data class PendingRecording(
    val file: File,
    val historyId: Long,
    val number: String,
    val startedAt: Long,
    val token: String,
    val inApp: Boolean,
)

/** Pending and saved call recordings. An unanswered prompt deletes the file. */
object Recordings {
    const val EXTRA_PROMPT = "recording_prompt"
    const val EXTRA_TOKEN = "token"
    const val ACTION_SAVE = "com.apex2060.softphone.RECORDING_SAVE"
    const val ACTION_DISCARD = "com.apex2060.softphone.RECORDING_DISCARD"
    const val ACTION_DISMISSED = "com.apex2060.softphone.RECORDING_DISMISSED"

    private const val PREFS = "recordings"
    private const val K_PATH = "pending_path"
    private const val K_ID = "pending_history"
    private const val K_NUMBER = "pending_number"
    private const val K_STARTED = "pending_started"
    private const val K_TOKEN = "pending_token"
    private const val K_IN_APP = "pending_in_app"

    private val _prompt = MutableStateFlow<PendingRecording?>(null)
    val prompt: StateFlow<PendingRecording?> = _prompt

    fun cleanupOrphans(context: Context) {
        val keep = read(context)?.file?.absolutePath
        val dir = File(context.applicationContext.filesDir, "recordings/pending")
        if (dir.isDirectory) {
            RecordingFiles.orphans(dir.listFiles()?.toList().orEmpty(), keep).forEach { file ->
                if (file.delete()) SpikeLog.append("recording orphan deleted ${file.name}")
            }
        }
        val pending = read(context)
        if (pending != null && !pending.file.exists()) {
            clear(context)
            _prompt.value = null
            cancelNotification(context)
        }
    }

    /** Brings back an in-app prompt after the process died with the dialog still open. */
    fun restoreDialog(context: Context) {
        val pending = read(context) ?: return
        if (!pending.file.exists()) {
            clear(context)
            return
        }
        if (pending.inApp) _prompt.value = pending
    }

    fun offer(
        context: Context,
        historyId: Long,
        number: String,
        startedAt: Long,
        file: File,
        foreground: Boolean,
    ) {
        val app = context.applicationContext
        if (ConfigStore.lines.value.isEmpty()) {
            file.delete()
            SpikeLog.append("recording dropped, phone has no line")
            return
        }
        if (!file.exists() || file.length() <= 44L) {
            file.delete()
            SpikeLog.append("recording empty, discarded")
            return
        }
        val previous = read(app)
        if (previous != null && previous.file.absolutePath != file.absolutePath) {
            previous.file.delete()
            SpikeLog.append("recording replaced unanswered ${previous.file.name}")
        }
        val locked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val inApp = (foreground && !locked) || !Notifications.notificationsAllowed(app)
        val pending = PendingRecording(
            file = file,
            historyId = historyId,
            number = number,
            startedAt = startedAt,
            token = UUID.randomUUID().toString(),
            inApp = inApp,
        )
        write(app, pending)
        if (inApp) {
            _prompt.value = pending
            cancelNotification(app)
        } else {
            _prompt.value = null
            Notifications.showRecordingPrompt(app, pending.number, pending.token)
        }
        SpikeLog.append("recording ready prompt=${if (inApp) "dialog" else "notification"}")
    }

    fun showPrompt(context: Context) {
        val pending = read(context) ?: return
        if (!pending.file.exists()) {
            clear(context)
            _prompt.value = null
            cancelNotification(context)
            return
        }
        // A new token makes the cancelled notification's swipe intent stale.
        val shown = pending.copy(token = UUID.randomUUID().toString(), inApp = true)
        write(context, shown)
        _prompt.value = shown
        cancelNotification(context)
    }

    fun save(context: Context, token: String? = null) {
        val pending = read(context) ?: return
        if (token != null && token != pending.token) return
        val name = RecordingFiles.savedName(pending.startedAt, pending.number)
        val dest = RecordingFiles.unique(savedDir(context), name)
        if (!RecordingFiles.move(pending.file, dest)) {
            SpikeLog.append("recording save failed")
            return
        }
        if (!CallHistory.setRecording(pending.historyId, dest.name)) {
            dest.delete()
            SpikeLog.append("recording save failed, history row missing")
            clear(context)
            _prompt.value = null
            cancelNotification(context)
            return
        }
        clear(context)
        _prompt.value = null
        cancelNotification(context)
        SpikeLog.append("recording saved ${dest.name}")
    }

    fun discardIfHistory(context: Context, historyIds: Set<Long>) {
        if (historyIds.isEmpty()) return
        val pending = read(context) ?: return
        if (pending.historyId in historyIds) discard(context)
    }

    fun discard(context: Context, token: String? = null) {
        val pending = read(context) ?: return
        if (token != null && token != pending.token) return
        pending.file.delete()
        clear(context)
        _prompt.value = null
        cancelNotification(context)
        SpikeLog.append("recording discarded")
    }

    fun onDismissed(context: Context, token: String?) {
        if (token.isNullOrBlank()) return
        discard(context, token)
    }

    fun deleteSaved(context: Context, historyId: Long, fileName: String?) {
        if (!fileName.isNullOrBlank()) File(savedDir(context), fileName).delete()
        CallHistory.setRecording(historyId, null)
    }

    fun savedFile(context: Context, fileName: String): File = File(savedDir(context), fileName)

    private fun cancelNotification(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java)
            .cancel(Notifications.ID_RECORDING)
    }

    private fun savedDir(context: Context) =
        File(context.applicationContext.filesDir, "recordings/saved").apply { mkdirs() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): PendingRecording? {
        val stored = prefs(context)
        val path = stored.getString(K_PATH, null) ?: return null
        val token = stored.getString(K_TOKEN, null) ?: return null
        return PendingRecording(
            file = File(path),
            historyId = stored.getLong(K_ID, -1L),
            number = stored.getString(K_NUMBER, "").orEmpty(),
            startedAt = stored.getLong(K_STARTED, 0L),
            token = token,
            inApp = stored.getBoolean(K_IN_APP, false),
        )
    }

    private fun write(context: Context, pending: PendingRecording) {
        prefs(context).edit()
            .putString(K_PATH, pending.file.absolutePath)
            .putLong(K_ID, pending.historyId)
            .putString(K_NUMBER, pending.number)
            .putLong(K_STARTED, pending.startedAt)
            .putString(K_TOKEN, pending.token)
            .putBoolean(K_IN_APP, pending.inApp)
            .apply()
    }

    private fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
