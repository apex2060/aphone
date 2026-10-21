package com.twentytwo.phone.sipspike

import android.app.Application

class SpikeApplication : Application() {
    lateinit var engine: SipEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = SipEngine(this)
        engine.start()
    }
}
