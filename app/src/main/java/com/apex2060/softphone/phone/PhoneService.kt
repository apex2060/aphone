package com.apex2060.softphone.phone

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.Provisioner
import com.apex2060.softphone.config.RevokedException
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the Plivo registration alive while the app is in the background, rings for incoming calls,
 * and holds microphone access during a call.
 */
class PhoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastPhase: CallPhase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground(Notifications.status(this, PhoneManager.registration.value, null), mic = false)

        scope.launch {
            ConfigStore.config.collect { cfg ->
                if (cfg == null) {
                    PhoneManager.logout()
                    stopSelf()
                } else {
                    PhoneManager.login(cfg.sipUsername, cfg.sipPassword)
                }
            }
        }
        scope.launch {
            combine(PhoneManager.registration, PhoneManager.call) { r, c -> r to c }
                .collect { (reg, call) -> render(reg, call) }
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
            ACTION_DECLINE -> PhoneManager.decline()
            ACTION_HANGUP -> PhoneManager.hangup()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(reg: Registration, call: CallState?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (call?.phase == CallPhase.RINGING_IN) {
            nm.notify(Notifications.ID_INCOMING, Notifications.incoming(this, call.number))
        } else {
            nm.cancel(Notifications.ID_INCOMING)
        }
        if (call?.phase == CallPhase.ENDED && lastPhase != CallPhase.ENDED && call.outcome == Outcome.MISSED) {
            nm.notify(Notifications.ID_MISSED, Notifications.missed(this, call.number))
        }
        lastPhase = call?.phase

        val inCall = call != null && call.phase in setOf(CallPhase.DIALING, CallPhase.RINGING_OUT, CallPhase.ACTIVE)
        goForeground(Notifications.status(this, reg, call), mic = inCall)
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

    private suspend fun refreshIfDue() {
        val cfg = ConfigStore.config.value ?: return
        val ticket = ConfigStore.ticket ?: return
        if (cfg.refreshHours == 0) return
        if (System.currentTimeMillis() - ConfigStore.refreshedAt < cfg.refreshHours * 3_600_000L) return
        try {
            val (json, next) = Provisioner.fetch(ticket)
            ConfigStore.save(ticket, json, next)
        } catch (e: RevokedException) {
            deprovision(this)
        } catch (e: Exception) {
            Log.w(TAG, "config refresh failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "PhoneService"
        const val ACTION_DECLINE = "com.apex2060.softphone.DECLINE"
        const val ACTION_HANGUP = "com.apex2060.softphone.HANGUP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PhoneService::class.java))
        }

        /** Returns the phone to its factory state: only the QR scanner remains. */
        fun deprovision(context: Context) {
            PhoneManager.logout()
            CallHistory.clear()
            ConfigStore.clear()
            context.getSystemService(NotificationManager::class.java).cancelAll()
        }
    }
}
