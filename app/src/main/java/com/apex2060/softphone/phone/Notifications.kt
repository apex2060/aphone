package com.apex2060.softphone.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.Person
import com.apex2060.softphone.R
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.ui.CallActivity
import com.apex2060.softphone.ui.MainActivity

object Notifications {
    const val ID_SERVICE = 1
    const val ID_INCOMING = 2
    const val ID_MISSED = 3

    private const val CH_STATUS = "status"
    /** New id: an existing "incoming" channel keeps its old sound and cannot be changed in place. */
    private const val CH_INCOMING = "incoming_v2"
    private const val CH_MISSED = "missed"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, context.getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        // Sound and vibration are played by PhoneService. A sounding channel would ring twice.
        nm.deleteNotificationChannel("incoming")
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, context.getString(R.string.notif_channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, context.getString(R.string.notif_channel_missed), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** Line name from provisioning, or the app name before a document arrives. */
    private fun title(context: Context) =
        ConfigStore.config.value?.title ?: context.getString(R.string.app_name)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun openCall(context: Context, answer: Boolean) = PendingIntent.getActivity(
        context,
        if (answer) 11 else 10,
        Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CallActivity.EXTRA_ANSWER, answer),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun serviceAction(context: Context, action: String) = PendingIntent.getService(
        context, action.hashCode(),
        Intent(context, PhoneService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    /** The persistent foreground-service notification; doubles as the ongoing-call notification. */
    fun status(context: Context, reg: Registration, call: CallState?): Notification {
        val b = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentTitle(title(context))
            .setContentIntent(openApp(context))
        if (call != null && call.phase != CallPhase.RINGING_IN && call.phase != CallPhase.ENDED) {
            val person = Person.Builder().setName(call.number).build()
            b.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, serviceAction(context, PhoneService.ACTION_HANGUP)))
                .setContentIntent(openCall(context, answer = false))
                .setContentText(if (call.phase == CallPhase.ACTIVE) "On call" else "Calling…")
            call.answeredAt?.let { b.setWhen(it).setUsesChronometer(true).setShowWhen(true) }
        } else {
            b.setContentText(
                when (reg) {
                    Registration.ONLINE -> "Ready for calls"
                    Registration.CONNECTING -> "Connecting…"
                    Registration.FAILED -> "Can't reach Plivo, retrying"
                    Registration.OFFLINE -> "Offline"
                }
            )
        }
        return b.build()
    }

    fun incoming(context: Context, number: String): Notification {
        val person = Person.Builder().setName(number).setImportant(true).build()
        return NotificationCompat.Builder(context, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(number)
            .setContentText("Incoming call · ${title(context)}")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(openCall(context, answer = false), true)
            .setContentIntent(openCall(context, answer = false))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    serviceAction(context, PhoneService.ACTION_DECLINE),
                    openCall(context, answer = true),
                )
            )
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Android 14+ sideload installs do not grant this until the user allows it in settings. */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun missed(context: Context, number: String): Notification =
        NotificationCompat.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Missed call")
            .setContentText(number)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
}
