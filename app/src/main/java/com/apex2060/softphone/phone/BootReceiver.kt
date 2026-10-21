package com.apex2060.softphone.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apex2060.softphone.config.ConfigStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A normal boot waits for FCM. The debug toggle is the only reason to start now.
        if (ConfigStore.config.value != null && ConfigStore.stayRegistered) PhoneService.startStay(context)
    }
}
