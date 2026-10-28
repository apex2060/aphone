package com.apex2060.softphone.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.apex2060.softphone.CallPhase
import com.apex2060.softphone.CallUi
import com.apex2060.softphone.R
import com.apex2060.softphone.Registration
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.record.Recordings
import com.apex2060.softphone.ui.CallActivity
import com.apex2060.softphone.ui.MainActivity

object Notifications {
    const val ID_SERVICE = 1
    const val ID_INCOMING = 2
    const val ID_MISSED = 3
    const val ID_RECORDING = 4

    private const val CH_STATUS = "status"
    private const val CH_INCOMING = "incoming_v2"
    private const val CH_MISSED = "missed"
    private const val CH_RECORDING = "recording"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("incoming")
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "22 Phone", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Call recordings", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun title() = ConfigStore.themeLine()?.config?.title ?: "22 Phone"

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

    fun status(context: Context, reg: Registration, call: CallUi?): Notification {
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
            // A previous CallStyle notification keeps its lock-screen chronometer
            // unless these are cleared on the replacement.
            b.setUsesChronometer(false)
                .setShowWhen(false)
                .setWhen(0)
                .setStyle(null)
                .setContentText(
                when (reg) {
                    Registration.ONLINE -> "Ready for calls"
                    Registration.CONNECTING -> "Connecting…"
                    Registration.FAILED -> "Can't reach the server, retrying"
                    Registration.OFFLINE -> "Offline"
                }
            )
        }
        return b.build()
    }

    fun incoming(context: Context, number: String, lineLabel: String): Notification {
        val person = Person.Builder().setName(number).setImportant(true).build()
        val line = lineLabel.ifBlank { title() }
        return NotificationCompat.Builder(context, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(number)
            .setContentText("Incoming call · $line")
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

    fun showRecordingPrompt(context: Context, number: String, token: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(ID_RECORDING, recordingPrompt(context, number, token))
    }

    /** Plain notification. CallStyle would keep a lock-screen timer, so this stays a normal alert. */
    fun recordingPrompt(context: Context, number: String, token: String): Notification =
        NotificationCompat.Builder(context, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("Save this call recording?")
            .setContentText(number.ifBlank { "Call" })
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setContentIntent(openRecordingPrompt(context))
            .setDeleteIntent(recordingAction(context, Recordings.ACTION_DISMISSED, token, 43))
            .addAction(0, "Save", recordingAction(context, Recordings.ACTION_SAVE, token, 41))
            .addAction(0, "Discard", recordingAction(context, Recordings.ACTION_DISCARD, token, 42))
            .build()

    private fun openRecordingPrompt(context: Context) = PendingIntent.getActivity(
        context,
        40,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Recordings.EXTRA_PROMPT, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun recordingAction(context: Context, action: String, token: String, request: Int) =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, RecordingActionReceiver::class.java)
                .setAction(action)
                .putExtra(Recordings.EXTRA_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun missed(context: Context, number: String, lineLabel: String): Notification =
        NotificationCompat.Builder(context, CH_MISSED)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Missed call")
            .setContentText(if (lineLabel.isBlank()) number else "$number · $lineLabel")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
}
