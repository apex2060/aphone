package com.apex2060.softphone.phone

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.SpikeLog

/**
 * Self-managed Telecom registration so the phoneCall foreground type is allowed.
 * PJSIP keeps the audio. This does not become the device dialer.
 */
object OwnCalls {
    private const val ID = "22phone"

    @Volatile
    var connection: Connection? = null

    private fun handle(context: Context) = PhoneAccountHandle(
        ComponentName(context, OwnCallService::class.java),
        ID,
    )

    fun register(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(context), "22 Phone")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        runCatching { tm.registerPhoneAccount(account) }
            .onFailure { SpikeLog.append("telecom account refused: ${it.javaClass.simpleName}") }
    }

    fun addIncoming(context: Context, from: String) {
        register(context)
        val extras = Bundle()
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
        val shown = from.filter { it.isDigit() }.ifBlank { "0" }
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", shown, null))
        val tm = context.getSystemService(TelecomManager::class.java) ?: return
        runCatching { tm.addNewIncomingCall(handle(context), extras) }
            .onFailure { SpikeLog.append("telecom incoming refused: ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun active() {
        runCatching { connection?.setActive() }
    }

    fun close() {
        val current = connection ?: return
        connection = null
        runCatching {
            current.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            current.destroy()
        }
    }
}

class OwnCallService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val conn = object : Connection() {
            override fun onAnswer() {
                setActive()
                (application as SoftphoneApp).engine.answer()
            }

            override fun onReject() {
                setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
                destroy()
                if (OwnCalls.connection === this) OwnCalls.connection = null
                (application as SoftphoneApp).engine.decline()
            }
        }
        conn.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED)
        conn.connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD
        request?.address?.let { conn.setAddress(it, TelecomManager.PRESENTATION_ALLOWED) }
        conn.setRinging()
        OwnCalls.connection = conn
        return conn
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        SpikeLog.append("telecom incoming failed")
    }
}
