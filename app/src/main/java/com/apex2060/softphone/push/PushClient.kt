package com.apex2060.softphone.push

import android.content.Context
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.ProvisionTicket
import com.apex2060.softphone.config.Provisioner
import com.apex2060.softphone.config.RevokedException
import com.apex2060.softphone.phone.PhoneService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Uploads the FCM token and tells the server a pushed line has registered. */
object PushClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Posts this token only to [lineIds], each at its own provision origin. */
    fun publish(context: Context, token: String, lineIds: Set<String>) {
        if (token.isBlank() || lineIds.isEmpty()) return
        SpikeLog.noteSecret(token)
        scope.launch {
            for (line in ConfigStore.lines.value) {
                if (line.id !in lineIds) continue
                val ticket = line.ticket ?: continue
                if (ticket.key.isNullOrBlank()) continue
                val body = JSONObject()
                    .put("key", ticket.key)
                    .put("fcmToken", token)
                    .put("platform", "android")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("deviceId", ConfigStore.deviceId())
                try {
                    val code = Provisioner.postAction(ticket, "phone.token", body)
                    SpikeLog.append("token update HTTP $code")
                    if (code in 200..299) {
                        ConfigStore.setPushAcked(line.id, true)
                    }
                } catch (e: RevokedException) {
                    SpikeLog.append("token update revoked")
                    PhoneService.removeLine(context, line.id)
                } catch (e: Exception) {
                    SpikeLog.append("token update failed: ${e.message}")
                }
            }
            PhoneService.considerIdle(context)
        }
    }

    fun notifyReady(context: Context, lineId: String, callUuid: String) {
        val line = ConfigStore.line(lineId) ?: return
        val ticket = line.ticket ?: return
        if (ticket.key.isNullOrBlank()) return
        scope.launch {
            val body = JSONObject()
                .put("key", ticket.key)
                .put("callUuid", callUuid)
                .put("deviceId", ConfigStore.deviceId())
            try {
                val code = Provisioner.postAction(ticket, "phone.ready", body)
                SpikeLog.append("push ready HTTP $code")
            } catch (e: RevokedException) {
                SpikeLog.append("push ready revoked")
                PhoneService.removeLine(context, lineId)
            } catch (e: Exception) {
                SpikeLog.append("push ready failed: ${e.message}")
            }
        }
    }
}

/** Fields added to phone.provision when a token already exists. Omitted until then. */
fun JSONObject.putPushFields(token: String?) {
    if (token.isNullOrBlank()) return
    put("fcmToken", token)
    put("platform", "android")
    put("appVersion", BuildConfig.VERSION_NAME)
    put("deviceId", ConfigStore.deviceId())
}

/** Keeps the ticket URL and replaces only the action, for callers that already hold a ticket. */
fun ProvisionTicket.forAction(action: String): ProvisionTicket =
    ProvisionTicket(ProvisionActions.withAction(url, action), key)
