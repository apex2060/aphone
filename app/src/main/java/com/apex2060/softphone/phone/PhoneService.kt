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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.Provisioner
import com.apex2060.softphone.config.RevokedException
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.Outcome
import com.apex2060.softphone.ui.CallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs for the length of a call: ringtone, full-screen notification, microphone.
 * It does not keep a SIP registration alive. The debug "stay registered" action is the
 * only path that leaves it running while idle.
 */
class PhoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastPhase: CallPhase? = null
    private var ringing = false
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var screenWake: PowerManager.WakeLock? = null
    private var holdingStay = false
    private var stayJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        holdingStay = ConfigStore.stayRegistered
        goForeground(Notifications.status(this, PhoneManager.registration.value, PhoneManager.call.value), mic = false)

        scope.launch {
            ConfigStore.config.collect { cfg ->
                if (cfg == null) {
                    PhoneManager.logout()
                    stopSelf()
                } else if (holdingStay) {
                    PhoneManager.login(cfg.sipUsername, cfg.sipPassword, reason = "stay")
                }
            }
        }
        scope.launch {
            combine(PhoneManager.registration, PhoneManager.call) { r, c -> r to c }
                .collect { (reg, call) ->
                    try {
                        render(reg, call)
                    } catch (t: Throwable) {
                        // A thrown notify() used to cancel this collector, so a later hangup never
                        // cleared the ongoing-call notification.
                        Log.e(TAG, "render failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
        }
        scope.launch {
            while (true) {
                refreshConfigIfDue(this@PhoneService)
                delay(60 * 60_000L)
            }
        }
        if (holdingStay) startStayLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DECLINE -> PhoneManager.decline()
            ACTION_HANGUP -> PhoneManager.hangup()
            ACTION_STAY -> {
                holdingStay = true
                startStayLoop()
                ConfigStore.config.value?.let {
                    PhoneManager.login(it.sipUsername, it.sipPassword, reason = "stay")
                }
            }
            ACTION_STOP_STAY -> {
                holdingStay = false
                stopStayLoop()
            }
        }
        maybeStop(PhoneManager.call.value)
        return if (holdingStay) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStayLoop()
        stopRing()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(reg: Registration, call: CallState?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (call?.phase == CallPhase.RINGING_IN) {
            nm.notify(Notifications.ID_INCOMING, Notifications.incoming(this, call.number))
            startRing(call.number)
        } else {
            stopRing()
            nm.cancel(Notifications.ID_INCOMING)
        }
        if (call?.phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED && call.outcome == Outcome.MISSED) {
            nm.notify(Notifications.ID_MISSED, Notifications.missed(this, call.number))
        }
        lastPhase = call?.phase

        val inCall = call != null && call.phase in setOf(CallPhase.DIALING, CallPhase.RINGING_OUT, CallPhase.ACTIVE)
        if (inCall || holdingStay || call?.phase == CallPhase.RINGING_IN) {
            goForeground(Notifications.status(this, reg, call), mic = inCall)
        }
        maybeStop(call)
    }

    private fun maybeStop(call: CallState?) {
        val idle = call == null || call.phase == CallPhase.ENDED
        if (!idle || holdingStay) return
        Log.i(TAG, "call service stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Debug toggle only. No wake lock and no wifi lock. */
    private fun startStayLoop() {
        if (stayJob != null) return
        Log.i(TAG, "stay-registered loop started")
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { PhoneManager.refreshRegistration("network") }
            }

            override fun onLost(network: Network) {
                scope.launch { PhoneManager.refreshRegistration("network") }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "network callback failed: ${it.javaClass.simpleName}") }
        stayJob = scope.launch {
            while (holdingStay) {
                delay(REGISTER_REFRESH_MS)
                PhoneManager.refreshRegistration("timer")
            }
        }
    }

    private fun stopStayLoop() {
        stayJob?.cancel()
        stayJob = null
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    private fun startRing(number: String) {
        if (ringing) return
        ringing = true
        val fullScreen = Notifications.canUseFullScreenIntent(this)
        val foreground = PhoneManager.isAppForeground()
        Log.i(
            TAG,
            "ring start from=$number foreground=$foreground fullScreenIntent=$fullScreen " +
                "notifications=${Notifications.notificationsAllowed(this)} " +
                "batteryUnrestricted=${Notifications.batteryUnrestricted(this)} " +
                PhoneManager.registrationSnapshot(),
        )
        val mode = getSystemService(AudioManager::class.java).ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT) startVibration()
        if (mode == AudioManager.RINGER_MODE_NORMAL) startTone()
        wakeScreen()
        // Full-screen intent is the reliable path while backgrounded, and only when Android 14+
        // has actually granted it. Otherwise open the call screen ourselves.
        if (foreground || !fullScreen) {
            Log.i(TAG, "opening call screen foreground=$foreground fullScreenIntent=$fullScreen")
            runCatching {
                startActivity(
                    Intent(this, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }.onFailure { Log.w(TAG, "opening call screen blocked: ${it.javaClass.simpleName}") }
        } else {
            Log.i(TAG, "call screen left to fullScreenIntent")
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
        }.onFailure { Log.w(TAG, "ringtone failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val pattern = longArrayOf(0, 800, 600, 800, 600)
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }.onFailure { Log.w(TAG, "vibrate failed: ${it.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        screenWake = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "softphone:ring",
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
        val withMic = if (mic && Build.VERSION.SDK_INT >= 30) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else base
        try {
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, withMic)
        } catch (e: Exception) {
            // The microphone type is refused when the app isn't visible; keep the service alive regardless.
            Log.w(TAG, "foreground with mic refused: ${e.message}")
            ServiceCompat.startForeground(this, Notifications.ID_SERVICE, notification, base)
        }
    }

    companion object {
        private const val TAG = "PhoneService"
        private const val REGISTER_REFRESH_MS = 90_000L
        const val ACTION_DECLINE = "com.apex2060.softphone.DECLINE"
        const val ACTION_HANGUP = "com.apex2060.softphone.HANGUP"
        const val ACTION_STAY = "com.apex2060.softphone.STAY"
        const val ACTION_STOP_STAY = "com.apex2060.softphone.STOP_STAY"

        fun startForCall(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PhoneService::class.java))
        }

        fun startStay(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneService::class.java).setAction(ACTION_STAY),
            )
        }

        fun stopStay(context: Context) {
            context.startService(Intent(context, PhoneService::class.java).setAction(ACTION_STOP_STAY))
        }

        /** Returns the phone to its factory state: only the QR scanner remains. */
        fun deprovision(context: Context) {
            PhoneManager.logout()
            CallHistory.clear()
            ConfigStore.clear()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }

        suspend fun refreshConfigIfDue(context: Context) {
            val cfg = ConfigStore.config.value ?: return
            val ticket = ConfigStore.ticket ?: return
            if (cfg.refreshHours == 0) return
            if (System.currentTimeMillis() - ConfigStore.refreshedAt < cfg.refreshHours * 3_600_000L) return
            try {
                val (json, next) = Provisioner.fetch(ticket)
                ConfigStore.save(ticket, json, next)
            } catch (e: RevokedException) {
                deprovision(context)
            } catch (e: Exception) {
                Log.w(TAG, "config refresh failed: ${e.message}")
            }
        }
    }
}
