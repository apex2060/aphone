package com.apex2060.softphone.record

import android.app.Application
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.config.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pjsip.pjsua2.AudioMedia
import org.pjsip.pjsua2.AudioMediaRecorder
import java.io.File

/**
 * Mixes the call and the microphone into one WAV. A failure here is logged
 * and leaves the call's own audio connections alone.
 */
class CallRecording(private val app: Application) {
    private val lock = Any()
    private var recorder: AudioMediaRecorder? = null
    private var file: File? = null
    private var remote: AudioMedia? = null
    private var capture: AudioMedia? = null
    private var tried = false
    private var confirmed = false
    private var closed = true
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun reset() {
        synchronized(lock) {
            if (recorder != null) return
            closed = false
            confirmed = false
            tried = false
        }
    }

    fun markConfirmed() {
        synchronized(lock) {
            if (closed) return
            confirmed = true
        }
    }

    fun startIfReady(remoteMedia: AudioMedia?, captureMedia: AudioMedia?) {
        synchronized(lock) {
            if (closed || tried || recorder != null || !confirmed) return
            if (!ConfigStore.autoRecording.value) return
            val remotePort = remoteMedia ?: return
            tried = true
            var created: AudioMediaRecorder? = null
            var pending: File? = null
            try {
                val dir = File(app.filesDir, "recordings/pending").apply { mkdirs() }
                pending = uniquePending(dir)
                created = AudioMediaRecorder()
                created.createRecorder(pending.absolutePath)
                remotePort.startTransmit(created)
                if (captureMedia != null) {
                    try {
                        captureMedia.startTransmit(created)
                    } catch (e: Exception) {
                        SpikeLog.append("recording mic failed: ${e.message}")
                    }
                } else {
                    SpikeLog.append("recording mic unavailable")
                }
                recorder = created
                file = pending
                remote = remotePort
                capture = captureMedia
                _active.value = true
                SpikeLog.append("recording started ${pending.name}")
            } catch (e: Exception) {
                SpikeLog.append("recording failed: ${e.message}")
                try {
                    created?.let { remotePort.stopTransmit(it) }
                } catch (_: Exception) {
                }
                try {
                    created?.delete()
                } catch (_: Exception) {
                }
                pending?.delete()
            }
        }
    }

    /** Stops the writer and returns the WAV only after the file is closed. */
    fun stop(): File? {
        synchronized(lock) {
            closed = true
            confirmed = false
            _active.value = false
            val rec = recorder
            val pending = file
            val remotePort = remote
            val capturePort = capture
            recorder = null
            file = null
            remote = null
            capture = null
            tried = false
            if (rec == null) return null
            try {
                remotePort?.stopTransmit(rec)
            } catch (e: Exception) {
                SpikeLog.append("recording stop remote failed: ${e.message}")
            }
            try {
                capturePort?.stopTransmit(rec)
            } catch (e: Exception) {
                SpikeLog.append("recording stop mic failed: ${e.message}")
            }
            try {
                rec.delete()
            } catch (e: Exception) {
                SpikeLog.append("recording close failed: ${e.message}")
            }
            if (pending == null || !pending.exists() || pending.length() <= 44L) {
                pending?.delete()
                SpikeLog.append("recording empty, discarded")
                return null
            }
            SpikeLog.append("recording stopped ${pending.name} bytes=${pending.length()}")
            return pending
        }
    }

    private fun uniquePending(dir: File): File {
        var n = 0
        while (true) {
            val suffix = if (n == 0) "" else "_$n"
            val file = File(dir, "call-${System.currentTimeMillis()}$suffix.wav")
            if (!file.exists()) return file
            n++
        }
    }
}
