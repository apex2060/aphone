package com.apex2060.softphone.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PushRuntime.onDefaultToken(this, token)
        PushRuntime.sync(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PushRuntime.deliver(this, message.data)
    }
}
