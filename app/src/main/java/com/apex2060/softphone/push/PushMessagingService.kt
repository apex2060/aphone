package com.apex2060.softphone.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRuntime.onToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushRuntime.deliver(this, message.data)
    }
}
