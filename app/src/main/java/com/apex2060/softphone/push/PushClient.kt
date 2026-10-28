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

    fun publishToken(context: Context, token: String) {
        if (!BuildConfig.PUSH_ENABLED || token.isBlank()) return
        SpikeLog.noteSecret(token)
        scope.launch {
            for (line in ConfigStore.lines.value) {
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
        if (!BuildConfig.PUSH_ENABLED) return
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

fun linesForCalledNumber(to: String): Set<String> {
    val enabled = ConfigStore.lines.value.filter { it.enabled }
    val digits = SipPushMatch.digits(to)
    if (digits.isEmpty()) return enabled.map { it.id }.toSet()
    val hits = enabled.filter { line ->
        val user = SipPushMatch.digits(line.config.sipUsername)
        val sub = SipPushMatch.digits(line.config.subtitle.orEmpty())
        (user.isNotEmpty() && (user.endsWith(digits) || digits.endsWith(user))) ||
            (sub.length >= 7 && (sub.contains(digits) || digits.contains(sub)))
    }
    return (if (hits.isEmpty()) enabled else hits).map { it.id }.toSet()
}

/** Keeps the ticket URL and replaces only the action, for callers that already hold a ticket. */
fun ProvisionTicket.forAction(action: String): ProvisionTicket =
    ProvisionTicket(ProvisionActions.withAction(url, action), key)
