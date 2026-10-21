package com.twentytwo.phone.sipspike

import android.app.Application
import com.twentytwo.phone.sipspike.config.ConfigStore
import com.twentytwo.phone.sipspike.history.CallHistory
import com.twentytwo.phone.sipspike.phone.SpikeNotifications
import com.twentytwo.phone.sipspike.phone.SpikeService

class SpikeApplication : Application() {
    lateinit var engine: SipEngine
        private set

    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        ConfigStore.init(this)
        CallHistory.init(this)
        SpikeNotifications.createChannels(this)
        engine = SipEngine(this)
        engine.start()
        if (ConfigStore.config.value != null) SpikeService.start(this)
    }
}
