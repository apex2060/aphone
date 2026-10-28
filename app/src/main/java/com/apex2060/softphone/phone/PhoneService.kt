package com.apex2060.softphone.phone

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.apex2060.softphone.CallPhase
import com.apex2060.softphone.Registration
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.Provisioner
import com.apex2060.softphone.push.PushGate
import com.apex2060.softphone.push.PushRuntime
import com.apex2060.softphone.config.RevokedException
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.Outcome
import com.apex2060.softphone.record.Recordings
import com.apex2060.softphone.ui.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps registration up, rings, and refreshes the provisioning document. */
class PhoneService : Service() {
    enum class FgsMode { SHORT, MICROPHONE, SPECIAL }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastPhase: CallPhase? = null
    private var ringing = false
    /** True while the foreground or incoming notification is a CallStyle. */
    private var callNotificationPosted = false
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var screenWake: PowerManager.WakeLock? = null
    private var stayAwake: PowerManager.WakeLock? = null

    private val engine get() = (application as SoftphoneApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(Notifications.status(this, engine.registration.value, null), mode = nextMode ?: idleMode())
        nextMode = null
        if (!ConfigStore.anyEnabled()) {
            stopSelf()
            return
        }
        stayAwake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sipspike:register")
            .apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        scope.launch {
            ConfigStore.lines
                .map { lines ->
                    lines.filter { it.enabled }.map { line ->
                        val creds = line.resolvedCreds()
                        Triple(line.id, creds?.first.orEmpty(), creds?.second.orEmpty())
                    }
                }
                .distinctUntilChanged()
                .collect { wanted ->
                    engine.syncEnabledLines()
                    if (wanted.isEmpty() || (!PushGate.needsIdleService() && !engine.isAwake() && engine.call.value == null)) {
                        stopSelf()
                    }
                }
        }
        scope.launch {
            combine(engine.registration, engine.call) { r, c -> r to c }.collect { (reg, call) ->
                try {
                    render(reg, call)
                } catch (t: Throwable) {
                    SpikeLog.append("render failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        scope.launch {
            while (true) {
                delay(REGISTER_REFRESH_MS)
                engine.refreshRegistration("timer")
            }
        }
        scope.launch {
            while (true) {
                refreshIfDue()
                delay(60 * 60_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DECLINE -> engine.decline()
            ACTION_HANGUP -> engine.hangup()
            ACTION_PROMOTE -> goForeground(
                Notifications.status(this, engine.registration.value, engine.call.value),
                mode = FgsMode.MICROPHONE,
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRing()
        stayAwake?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun render(reg: Registration, call: com.apex2060.softphone.CallUi?) {
        val nm = getSystemService(NotificationManager::class.java)
        val incoming = call?.takeIf { it.phase == CallPhase.RINGING_IN }
        if (incoming != null) {
            val who = incoming.callerName?.ifBlank { null } ?: incoming.number
            nm.notify(Notifications.ID_INCOMING, Notifications.incoming(this, who, incoming.lineLabel))
            startRing(incoming.number)
        } else {
            stopRing()
            nm.cancel(Notifications.ID_INCOMING)
        }
        if (call?.phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED && call.outcome == Outcome.MISSED) {
            nm.notify(Notifications.ID_MISSED, Notifications.missed(this, call.number, call.lineLabel))
        }
        lastPhase = call?.phase
        val inCall = call != null && call.phase in setOf(CallPhase.DIALING, CallPhase.RINGING_OUT, CallPhase.ACTIVE)
        val callChrome = inCall || incoming != null
        if (callNotificationPosted && !callChrome) {
            // Updating a CallStyle notification in place leaves the lock-screen
            // chronometer running. Remove it, then post the idle status.
            dismissCallNotifications(nm)
        }
        callNotificationPosted = callChrome
        val mode = modeFor(call)
        if (mode == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        goForeground(Notifications.status(this, reg, call), mode = mode)
    }

    private fun dismissCallNotifications(nm: NotificationManager) {
        nm.cancel(Notifications.ID_INCOMING)
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(Notifications.ID_SERVICE)
        SpikeLog.append("cleared call notification and lock-screen timer")
    }

    private fun startRing(number: String) {
        if (ringing) return
        ringing = true
        val fullScreen = Notifications.canUseFullScreenIntent(this)
        val foreground = engine.isAppForeground()
        SpikeLog.append("ring start from=$number foreground=$foreground fullScreenIntent=$fullScreen")
        val mode = getSystemService(AudioManager::class.java).ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT) startVibration()
        if (mode == AudioManager.RINGER_MODE_NORMAL) startTone()
        wakeScreen()
        if (foreground || !fullScreen) {
            runCatching {
                startActivity(
                    Intent(this, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }.onFailure { SpikeLog.append("opening call screen blocked: ${it.javaClass.simpleName}") }
        }
    }

    private fun startTone() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(applicationContext, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600, 800, 600), 0)) }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        screenWake = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "sipspike:ring",
        ).apply { acquire(20_000) }
    }

    private fun stopRing() {
        if (!ringing && player == null && screenWake == null) return
        ringing = false
        player?.runCatching { release() }
        player = null
        vibrator?.cancel()
        screenWake?.let { if (it.isHeld) it.release() }
        screenWake = null
    }

    private fun idleMode(): FgsMode = if (PushGate.needsIdleService()) FgsMode.SPECIAL else FgsMode.SHORT

    /** Microphone only while a call is up and the app is on screen. Ringing stays a short service. */
    private fun modeFor(call: com.apex2060.softphone.CallUi?): FgsMode? {
        val phase = call?.phase
        val outgoing = phase == CallPhase.DIALING || phase == CallPhase.RINGING_OUT
        val answered = phase == CallPhase.ACTIVE && engine.isAppForeground()
        return when {
            outgoing || answered -> FgsMode.MICROPHONE
            phase == CallPhase.RINGING_IN || engine.isAwake() -> FgsMode.SHORT
            PushGate.needsIdleService() -> FgsMode.SPECIAL
            else -> null
        }
    }

    private fun goForeground(notification: android.app.Notification, mode: FgsMode) {
        val type = when (mode) {
            FgsMode.SHORT -> if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
            FgsMode.MICROPHONE -> if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            FgsMode.SPECIAL -> if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, type)
            SpikeLog.append("fgs ${mode.name.lowercase()}")
        } catch (e: Exception) {
            val fallback = if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                0
            }
            SpikeLog.append("fgs ${mode.name.lowercase()} refused: ${e.javaClass.simpleName}: ${e.message}")
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, fallback)
        }
    }

    private suspend fun refreshIfDue() {
        val now = System.currentTimeMillis()
        for (line in ConfigStore.lines.value) {
            val ticket = line.ticket ?: continue
            if (line.config.refreshHours == 0) continue
            if (now - line.refreshedAt < line.config.refreshHours * 3_600_000L) continue
            try {
                val (json, next) = Provisioner.fetch(ticket)
                ConfigStore.updateDocument(line.id, ticket, json, next)
                PushRuntime.sync(this)
                SpikeLog.append("provision refresh saved title=${next.title} refreshHours=${next.refreshHours}")
            } catch (e: RevokedException) {
                SpikeLog.append("provision revoked user=${line.config.sipUsername}")
                removeLine(this, line.id)
            } catch (e: Exception) {
                SpikeLog.append("provision refresh failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val REGISTER_REFRESH_MS = 90_000L
        const val ACTION_DECLINE = "com.apex2060.softphone.DECLINE"
        const val ACTION_HANGUP = "com.apex2060.softphone.HANGUP"
        const val ACTION_PUSH = "com.apex2060.softphone.PUSH"
        const val ACTION_PROMOTE = "com.apex2060.softphone.PROMOTE"

        @Volatile
        var nextMode: FgsMode? = null

        fun start(context: Context) {
            if (!ConfigStore.anyEnabled() || !PushGate.needsIdleService()) return
            nextMode = FgsMode.SPECIAL
            ContextCompat.startForegroundService(context, Intent(context, PhoneService::class.java))
        }

        /** High-priority FCM wake. shortService keeps the process up until the user answers. */
        fun startForPush(context: Context) {
            nextMode = FgsMode.SHORT
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneService::class.java).setAction(ACTION_PUSH),
            )
        }

        /** Outgoing call from the dialer, which is already on screen. */
        fun startForOutgoing(context: Context) {
            nextMode = FgsMode.MICROPHONE
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneService::class.java).setAction(ACTION_PROMOTE),
            )
        }

        /** Answer screen became visible, so the microphone type is allowed. */
        fun promoteMicrophone(context: Context) {
            val phase = (context.applicationContext as SoftphoneApp).engine.call.value?.phase ?: return
            if (phase != CallPhase.ACTIVE && phase != CallPhase.DIALING && phase != CallPhase.RINGING_OUT) return
            nextMode = FgsMode.MICROPHONE
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneService::class.java).setAction(ACTION_PROMOTE),
            )
        }

        fun considerIdle(context: Context) {
            val app = context.applicationContext as SoftphoneApp
            val call = app.engine.call.value
            val inCall = call != null && call.phase != CallPhase.ENDED
            if (PushGate.needsIdleService() || app.engine.isAwake() || inCall) return
            context.stopService(Intent(context, PhoneService::class.java))
        }

        fun turnOn(context: Context, lineId: String) {
            ConfigStore.setEnabled(lineId, true)
            start(context)
            PushRuntime.sync(context.applicationContext)
        }

        fun turnOff(context: Context, lineId: String) {
            ConfigStore.setEnabled(lineId, false)
            (context.applicationContext as SoftphoneApp).engine.turnOff(lineId)
            if (!ConfigStore.anyEnabled()) {
                context.stopService(Intent(context, PhoneService::class.java))
            } else {
                considerIdle(context)
            }
        }

        /** Unregisters this phone and deletes it on this device. Other phones stay. */
        fun removeLine(context: Context, lineId: String) {
            (context.applicationContext as SoftphoneApp).engine.turnOff(lineId)
            ConfigStore.remove(lineId)
            if (!ConfigStore.anyEnabled()) {
                context.stopService(Intent(context, PhoneService::class.java))
            } else {
                considerIdle(context)
            }
            if (ConfigStore.lines.value.isEmpty()) {
                context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_SERVICE)
            }
        }

        /** Removes this phone and its call history. Other phones stay. */
        fun resetLine(context: Context, lineId: String) {
            val doomed = CallHistory.records.value.filter { it.lineId == lineId }.map { it.id }.toSet()
            Recordings.discardIfHistory(context, doomed)
            val app = context.applicationContext as SoftphoneApp
            app.engine.turnOff(lineId)
            CallHistory.clearLine(lineId)
            ConfigStore.remove(lineId)
            if (!ConfigStore.anyEnabled()) {
                context.stopService(Intent(context, PhoneService::class.java))
            } else {
                considerIdle(context)
            }
            if (ConfigStore.lines.value.isEmpty()) {
                context.getSystemService(NotificationManager::class.java).cancelAll()
            }
        }
    }
}
