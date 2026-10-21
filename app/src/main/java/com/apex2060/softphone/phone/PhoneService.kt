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
import kotlinx.coroutines.launch

/** Keeps registration up, rings, and refreshes the provisioning document. */
class PhoneService : Service() {
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
        goForeground(Notifications.status(this, engine.registration.value, null), mic = false)
        if (!ConfigStore.phoneEnabled.value) {
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
            ConfigStore.config.collect { cfg ->
                val creds = ConfigStore.resolvedCreds()
                if (cfg == null || creds == null) {
                    engine.logout()
                    stopSelf()
                } else {
                    engine.login(creds.first, creds.second)
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
            nm.notify(Notifications.ID_INCOMING, Notifications.incoming(this, incoming.number))
            startRing(incoming.number)
        } else {
            stopRing()
            nm.cancel(Notifications.ID_INCOMING)
        }
        if (call?.phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED && call.outcome == Outcome.MISSED) {
            nm.notify(Notifications.ID_MISSED, Notifications.missed(this, call.number))
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
        goForeground(Notifications.status(this, reg, call), mic = inCall)
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

    private fun goForeground(notification: android.app.Notification, mic: Boolean) {
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val withMic = if (mic && Build.VERSION.SDK_INT >= 30) {
            base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            base
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, withMic)
        } catch (e: Exception) {
            SpikeLog.append(
                "foreground with mic refused: ${e.javaClass.simpleName}: ${e.message} " +
                    "(call is not hung up)"
            )
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, base)
        }
    }

    private suspend fun refreshIfDue() {
        val cfg = ConfigStore.config.value ?: return
        val ticket = ConfigStore.ticket ?: return
        if (cfg.refreshHours == 0) return
        if (System.currentTimeMillis() - ConfigStore.refreshedAt < cfg.refreshHours * 3_600_000L) return
        try {
            val (json, next) = Provisioner.fetch(ticket)
            ConfigStore.save(ticket, json, next)
            SpikeLog.append("provision refresh saved title=${next.title} refreshHours=${next.refreshHours}")
        } catch (e: RevokedException) {
            deprovision(this)
        } catch (e: Exception) {
            SpikeLog.append("provision refresh failed: ${e.message}")
        }
    }

    companion object {
        private const val REGISTER_REFRESH_MS = 90_000L
        const val ACTION_DECLINE = "com.apex2060.softphone.DECLINE"
        const val ACTION_HANGUP = "com.apex2060.softphone.HANGUP"

        fun start(context: Context) {
            if (!ConfigStore.phoneEnabled.value) return
            ContextCompat.startForegroundService(context, Intent(context, PhoneService::class.java))
        }

        fun turnOn(context: Context) {
            ConfigStore.setPhoneEnabled(true)
            ContextCompat.startForegroundService(context, Intent(context, PhoneService::class.java))
        }

        fun turnOff(context: Context) {
            ConfigStore.setPhoneEnabled(false)
            (context.applicationContext as SoftphoneApp).engine.turnOff()
            context.stopService(Intent(context, PhoneService::class.java))
        }

        fun deprovision(context: Context) {
            val app = context.applicationContext as SoftphoneApp
            app.engine.logout()
            Recordings.discard(context)
            CallHistory.clear()
            ConfigStore.clear()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }
}
