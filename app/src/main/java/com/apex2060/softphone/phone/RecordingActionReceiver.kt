package com.apex2060.softphone.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apex2060.softphone.record.Recordings

/** Save, discard, or swipe-away for a call recording while the app is backgrounded. */
class RecordingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val token = intent?.getStringExtra(Recordings.EXTRA_TOKEN)
        when (intent?.action) {
            Recordings.ACTION_SAVE -> Recordings.save(context, token)
            Recordings.ACTION_DISCARD -> Recordings.discard(context, token)
            Recordings.ACTION_DISMISSED -> Recordings.onDismissed(context, token)
        }
    }
}
