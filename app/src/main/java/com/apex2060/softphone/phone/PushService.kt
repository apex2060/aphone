package com.apex2060.softphone.phone

import android.app.NotificationManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Plivo delivers an incoming call as a high-priority FCM data message. The message is handed to
 * the SDK, which builds a real [com.plivo.endpoint.Incoming], and the call notification is posted
 * before this method returns so the lock screen can show it even if the process was dead.
 */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Log.i(TAG, "onNewToken ${maskToken(token)}")
        PhoneManager.onFcmToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Log.i(TAG, "onMessageReceived priority=${message.priority} keys=${data.keys.sorted().joinToString(",")}")
        if (data.isEmpty()) return
        PhoneManager.onPush(data)
        val number = PhoneManager.call.value?.number
        if (number != null) {
            getSystemService(NotificationManager::class.java)
                .notify(Notifications.ID_INCOMING, Notifications.incoming(this, number))
        }
        runCatching { PhoneService.startForCall(this) }
            .onFailure { Log.w(TAG, "call service start failed: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "PushService"
    }
}
