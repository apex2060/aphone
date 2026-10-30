package com.apex2060.softphone.config

import android.content.Context
import com.apex2060.softphone.push.PushRuntime

/** Shared QR and setup-link provisioning. The caller decides how to present the result. */
object SetupFlow {
    const val INVALID_LINK =
        "This setup link isn't valid. Ask your business manager for a new one, or tap Add phone to scan the QR code."

    sealed interface Outcome {
        data object Added : Outcome
        data object Updated : Outcome
        data object Full : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun provision(context: Context, ticket: ProvisionTicket): Outcome = try {
        val (json, config) = Provisioner.fetch(ticket)
        when (ConfigStore.upsert(ticket, json, config)) {
            LineChange.FULL -> Outcome.Full
            LineChange.UPDATED -> {
                PushRuntime.sync(context)
                Outcome.Updated
            }
            LineChange.ADDED -> {
                PushRuntime.sync(context)
                Outcome.Added
            }
        }
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: "Setup failed")
    }
}
