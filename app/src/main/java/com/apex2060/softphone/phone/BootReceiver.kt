package com.apex2060.softphone.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apex2060.softphone.config.ConfigStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ConfigStore.config.value != null) PhoneService.start(context)
    }
}
