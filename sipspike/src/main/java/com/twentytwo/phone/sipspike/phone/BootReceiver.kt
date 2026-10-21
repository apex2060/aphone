package com.twentytwo.phone.sipspike.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.twentytwo.phone.sipspike.config.ConfigStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ConfigStore.config.value != null) SpikeService.start(context)
    }
}
