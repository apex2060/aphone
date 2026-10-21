package com.twentytwo.phone.sipspike.phone

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
import com.twentytwo.phone.sipspike.CallPhase
import com.twentytwo.phone.sipspike.Registration
import com.twentytwo.phone.sipspike.SpikeApplication
import com.twentytwo.phone.sipspike.SpikeLog
import com.twentytwo.phone.sipspike.config.ConfigStore
import com.twentytwo.phone.sipspike.config.Provisioner
import com.twentytwo.phone.sipspike.config.RevokedException
import com.twentytwo.phone.sipspike.history.CallHistory
import com.twentytwo.phone.sipspike.history.Outcome
import com.twentytwo.phone.sipspike.ui.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps registration up, rings, and refreshes the provisioning document. */
class SpikeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastPhase: CallPhase? = null
    private var ringing = false
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var screenWake: PowerManager.WakeLock? = null
    private var stayAwake: PowerManager.WakeLock? = null

    private val engine get() = (application as SpikeApplication).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(SpikeNotifications.status(this, engine.registration.value, null), mic = false)
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

    private fun render(reg: Registration, call: com.twentytwo.phone.sipspike.CallUi?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (call?.phase == CallPhase.RINGING_IN) {
            nm.notify(SpikeNotifications.ID_INCOMING, SpikeNotifications.incoming(this, call.number))
            startRing(call.number)
        } else {
            stopRing()
            nm.cancel(SpikeNotifications.ID_INCOMING)
        }
        if (call?.phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED && call.outcome == Outcome.MISSED) {
            nm.notify(SpikeNotifications.ID_MISSED, SpikeNotifications.missed(this, call.number))
        }
        lastPhase = call?.phase
        val inCall = call != null && call.phase in setOf(CallPhase.DIALING, CallPhase.RINGING_OUT, CallPhase.ACTIVE)
        goForeground(SpikeNotifications.status(this, reg, call), mic = inCall)
    }

    private fun startRing(number: String) {
        if (ringing) return
        ringing = true
        val fullScreen = SpikeNotifications.canUseFullScreenIntent(this)
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
            ServiceCompat.startForeground(this, SpikeNotifications.ID_SERVICE, notification, withMic)
        } catch (e: Exception) {
            SpikeLog.append("foreground with mic refused: ${e.message}")
            ServiceCompat.startForeground(this, SpikeNotifications.ID_SERVICE, notification, base)
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
        const val ACTION_DECLINE = "com.twentytwo.phone.sipspike.DECLINE"
        const val ACTION_HANGUP = "com.twentytwo.phone.sipspike.HANGUP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SpikeService::class.java))
        }

        fun deprovision(context: Context) {
            val app = context.applicationContext as SpikeApplication
            app.engine.logout()
            CallHistory.clear()
            ConfigStore.clear()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }
}
