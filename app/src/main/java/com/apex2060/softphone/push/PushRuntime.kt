package com.apex2060.softphone.push

import android.content.Context
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.PushConfig
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.ConcurrentHashMap

/**
 * Tokens are per Firebase project. A line's `push` object selects a named secondary app.
 * Lines without one use the default app from google-services.json, when that file was present.
 */
object PushRuntime {
    private val tokens = ConcurrentHashMap<String, String>()

    fun start(context: Context) = sync(context)

    /** Re-read every line's project and fetch its token. Safe to call after provision. */
    fun sync(context: Context) {
        val lines = ConfigStore.lines.value
        val projects = lines.mapNotNull { it.config.push }.distinctBy { it.projectId }
        if (BuildConfig.PUSH_ENABLED) {
            fetchDefault(context)
        }
        for (config in projects) {
            fetchSecondary(context, config)
        }
        if (!BuildConfig.PUSH_ENABLED && projects.isEmpty() && lines.isNotEmpty()) {
            SpikeLog.append("push off, no firebase project for these lines")
        }
    }

    /** Default-app rotation. Secondary apps are refreshed by [sync]. */
    fun onDefaultToken(context: Context, next: String) {
        if (next.isBlank()) return
        tokens[PushApps.DEFAULT] = next
        SpikeLog.noteSecret(next)
        val ids = ConfigStore.lines.value.filter { it.config.push == null }.map { it.id }.toSet()
        if (ids.isNotEmpty()) PushClient.publish(context, next, ids)
    }

    /** Token already fetched for this ticket's line, if any. Omitted from the first provision. */
    fun tokenForProvision(url: String): String? {
        val line = ConfigStore.lines.value.firstOrNull { it.ticketUrl == url } ?: return null
        val project = line.config.push?.projectId
        return if (project != null) tokens[project] else tokens[PushApps.DEFAULT]
    }

    fun deliver(context: Context, data: Map<String, String>) {
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
                val lines = PushRoute.select(
                    ConfigStore.lines.value.filter { it.enabled }.map { line ->
                        PushLine(line.id, line.config.sipUsername, line.ticketUrl, line.config.subtitle)
                    },
                    data["endpoint"],
                    data["origin"],
                    data["to"],
                )
                if (lines.isEmpty()) {
                    SpikeLog.append("push ignore no line")
                    return
                }
                com.apex2060.softphone.phone.PhoneService.startForPush(context)
                engine.wakeIncoming(
                    callUuid = callUuid,
                    from = data["from"].orEmpty(),
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

    private fun fetchDefault(context: Context) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    SpikeLog.append("push token failed: ${task.exception?.javaClass?.simpleName}")
                    return@addOnCompleteListener
                }
                val next = task.result ?: return@addOnCompleteListener
                onDefaultToken(context, next)
            }
        } catch (e: Exception) {
            SpikeLog.append("push default app unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun fetchSecondary(context: Context, config: PushConfig) {
        val app = try {
            PushApps.ensure(context, config)
        } catch (e: Exception) {
            SpikeLog.append("push app init failed project=${config.projectId}: ${e.javaClass.simpleName}")
            return
        }
        SpikeLog.noteSecret(config.apiKey)
        SpikeLog.append("push app project=${config.projectId}")
        PushApps.messaging(app).token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                SpikeLog.append("push token failed project=${config.projectId}: ${task.exception?.javaClass?.simpleName}")
                return@addOnCompleteListener
            }
            val next = task.result ?: return@addOnCompleteListener
            tokens[config.projectId] = next
            SpikeLog.noteSecret(next)
            val ids = ConfigStore.lines.value
                .filter { it.config.push?.projectId == config.projectId }
                .map { it.id }
                .toSet()
            if (ids.isNotEmpty()) PushClient.publish(context, next, ids)
        }
    }
}
