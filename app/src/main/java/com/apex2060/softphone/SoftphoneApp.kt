package com.apex2060.softphone

import android.app.Application
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.phone.Notifications
import com.apex2060.softphone.phone.PhoneManager

class SoftphoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        CallHistory.init(this)
        PhoneManager.init(this)
        Notifications.createChannels(this)
    }
}
