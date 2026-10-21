package com.apex2060.softphone.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
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
    private const val CH_INCOMING = "incoming"
    private const val CH_MISSED = "missed"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Phone status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun title() = ConfigStore.config.value?.title ?: "Softphone"

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
            .setContentTitle(title())
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
            .setContentText("Incoming call · ${title()}")
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
