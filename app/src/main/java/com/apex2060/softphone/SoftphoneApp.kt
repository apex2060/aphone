package com.apex2060.softphone

import android.app.Application
import android.util.Log
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.phone.Notifications
import com.apex2060.softphone.phone.PhoneManager
import com.apex2060.softphone.phone.PhoneService

class SoftphoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        CallHistory.init(this)
        PhoneManager.init(this)
        Notifications.createChannels(this)
        if (BuildConfig.PUSH_ENABLED) {
            runCatching { com.google.firebase.FirebaseApp.initializeApp(this) }
                .onFailure { Log.e("Softphone", "Firebase init failed: ${it.javaClass.simpleName}") }
        }
        if (ConfigStore.config.value != null) {
            PhoneManager.onProvisioned()
            if (ConfigStore.stayRegistered) PhoneService.startStay(this)
        }
    }
}
