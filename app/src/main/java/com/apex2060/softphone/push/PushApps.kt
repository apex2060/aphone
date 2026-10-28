package com.apex2060.softphone.push

import android.content.Context
import com.apex2060.softphone.config.PushConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/** One named FirebaseApp per projectId, shared by every line that names that project. */
object PushApps {
    const val DEFAULT = ""

    fun name(projectId: String) = "push-$projectId"

    fun ensure(context: Context, config: PushConfig): FirebaseApp {
        val name = name(config.projectId)
        FirebaseApp.getApps(context).firstOrNull { it.name == name }?.let { return it }
        val options = FirebaseOptions.Builder()
            .setProjectId(config.projectId)
            .setApplicationId(config.applicationId)
            .setApiKey(config.apiKey)
            .setGcmSenderId(config.gcmSenderId)
            .build()
        return FirebaseApp.initializeApp(context, options, name)
            ?: FirebaseApp.getInstance(name)
    }

    fun messaging(app: FirebaseApp): FirebaseMessaging = app.get(FirebaseMessaging::class.java)
}
