package com.apex2060.softphone.push

import android.content.Context
import com.apex2060.softphone.BuildConfig
import com.apex2060.softphone.SoftphoneApp
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
import java.io.IOException

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
                SpikeLog.append("token update posting")
                val result = retryTokenPost {
                    try {
                        val code = Provisioner.postAction(ticket, "phone.token", body)
                        when {
                            code in 200..299 -> TokenAttempt.Acked
                            code == 408 || code == 429 || code >= 500 -> TokenAttempt.Failed("HTTP $code")
                            else -> TokenAttempt.Rejected(code)
                        }
                    } catch (e: RevokedException) {
                        TokenAttempt.Revoked
                    } catch (e: IOException) {
                        TokenAttempt.Failed(e.message ?: "offline")
                    }
                }
                when (result) {
                    TokenAttempt.Acked -> {
                        SpikeLog.append("token update HTTP 200")
                        ConfigStore.setPushAcked(line.id, true)
                    }
                    TokenAttempt.Revoked -> {
                        SpikeLog.append("token update revoked")
                        PhoneService.removeLine(context, line.id)
                    }
                    is TokenAttempt.Rejected -> SpikeLog.append("token update HTTP ${result.code}")
                    is TokenAttempt.Failed -> SpikeLog.append("token update failed: ${result.reason}")
                }
            }
            (context.applicationContext as SoftphoneApp).engine.syncEnabledLines()
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

/**
 * Adds the push fields and returns this object. Must return the JSONObject:
 * a Unit return makes a chained `.toString()` send the text "kotlin.Unit".
 */
fun JSONObject.putPushFields(token: String?, appVersion: String, deviceId: String): JSONObject {
    if (!token.isNullOrBlank()) {
        put("fcmToken", token)
        put("platform", "android")
        put("appVersion", appVersion)
        put("deviceId", deviceId)
    }
    return this
}

/** JSON body for phone.provision. Always a JSON object, never a Unit string. */
fun provisionRequestBody(
    key: String,
    device: JSONObject,
    token: String?,
    appVersion: String,
    deviceId: String,
): String = JSONObject()
    .put("key", key)
    .put("device", device)
    .putPushFields(token, appVersion, deviceId)
    .toString()

/** Keeps the ticket URL and replaces only the action, for callers that already hold a ticket. */
fun ProvisionTicket.forAction(action: String): ProvisionTicket =
    ProvisionTicket(ProvisionActions.withAction(url, action), key)
