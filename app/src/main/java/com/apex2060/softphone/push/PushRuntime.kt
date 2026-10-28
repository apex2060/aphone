package com.apex2060.softphone.push

import android.content.Context
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.SpikeLog
import com.google.firebase.messaging.FirebaseMessaging

/** Firebase is called only when this build was compiled with google-services.json. */
object PushRuntime {
    @Volatile
    var token: String? = null
        private set

    fun start(context: Context) {
        if (!BuildConfig.PUSH_ENABLED) {
            SpikeLog.append("push disabled, no google-services.json")
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                SpikeLog.append("push token failed: ${task.exception?.javaClass?.simpleName}")
                return@addOnCompleteListener
            }
            val next = task.result ?: return@addOnCompleteListener
            onToken(context, next)
        }
    }

    fun onToken(context: Context, next: String) {
        if (!BuildConfig.PUSH_ENABLED || next.isBlank()) return
        token = next
        SpikeLog.noteSecret(next)
        PushClient.publishToken(context, next)
    }

    fun deliver(context: Context, data: Map<String, String>) {
        if (!BuildConfig.PUSH_ENABLED) return
        val type = data["type"].orEmpty()
        val callUuid = data["callUuid"].orEmpty()
        if (callUuid.isBlank()) {
            SpikeLog.append("push ignore empty callUuid")
            return
        }
        val ts = data["ts"]?.toLongOrNull() ?: 0L
        val age = if (ts > 0L) System.currentTimeMillis() - ts else 0L
        if (ts > 0L && age > 30_000L) {
            SpikeLog.append("push ignore stale ageMs=$age")
            return
        }
        val engine = (context.applicationContext as SoftphoneApp).engine
        when (type) {
            "incoming_call" -> {
                SpikeLog.append("push recv type=incoming_call callUuid=$callUuid ageMs=$age")
                val from = data["from"].orEmpty()
                val lines = linesForCalledNumber(data["to"].orEmpty())
                if (lines.isEmpty()) {
                    SpikeLog.append("push ignore no line")
                    return
                }
                com.apex2060.softphone.phone.PhoneService.startForPush(context)
                engine.wakeIncoming(
                    callUuid = callUuid,
                    from = from,
                    fromName = data["fromName"].orEmpty(),
                    lineIds = lines,
                )
            }
            "cancel" -> {
                SpikeLog.append("push cancel callUuid=$callUuid")
                engine.cancelIncoming(callUuid)
            }
            else -> SpikeLog.append("push ignore unknown type")
        }
    }
}
