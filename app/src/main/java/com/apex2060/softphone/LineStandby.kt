package com.apex2060.softphone

/**
 * A push line is not registered between calls. That is standby, not a failure,
 * and dialing it must still be allowed.
 */
object LineStandby {
    fun label(enabled: Boolean, registration: Registration?, pushStandby: Boolean): String = when {
        !enabled -> "Off"
        registration == Registration.CONNECTING -> "Connecting"
        pushStandby || registration == Registration.ONLINE -> "Ready"
        registration == Registration.FAILED -> "Failed"
        else -> "Offline"
    }

    fun showsPush(enabled: Boolean, pushStandby: Boolean): Boolean = enabled && pushStandby

    fun canDial(enabled: Boolean, registration: Registration?, pushStandby: Boolean): Boolean = when {
        !enabled -> false
        pushStandby -> true
        registration == Registration.ONLINE || registration == Registration.CONNECTING -> true
        else -> false
    }

    /**
     * Parked lines are unregistered while idle. A line that is held for a push
     * or an outbound attempt, or that has a call, keeps its account.
     */
    fun linesToDrop(
        lines: List<HeldLine>,
        waking: Set<String>,
    ): Set<String> = lines.filter { line ->
        line.parked && !line.hold && !line.inCall && line.id !in waking
    }.map { it.id }.toSet()

    /** One Firebase token is posted to every line that uses that project. Null project is the default app. */
    fun lineIdsForProject(lines: List<Pair<String, String?>>, projectId: String?): Set<String> =
        lines.filter { (_, project) -> project == projectId }.map { it.first }.toSet()
}

data class HeldLine(
    val id: String,
    val parked: Boolean,
    val hold: Boolean,
    val inCall: Boolean,
)
