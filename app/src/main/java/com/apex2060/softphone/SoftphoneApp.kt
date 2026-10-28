package com.apex2060.softphone

import android.app.Application
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.phone.Notifications
import com.apex2060.softphone.phone.PhoneService
import com.apex2060.softphone.push.PushRuntime
import com.apex2060.softphone.record.Recordings

class SoftphoneApp : Application() {
    lateinit var engine: SipEngine
        private set

    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        ConfigStore.init(this)
        CallHistory.init(this)
        Notifications.createChannels(this)
        Recordings.cleanupOrphans(this)
        engine = SipEngine(this)
        engine.start()
        PushRuntime.start(this)
        if (ConfigStore.anyEnabled()) PhoneService.start(this)
    }
}
