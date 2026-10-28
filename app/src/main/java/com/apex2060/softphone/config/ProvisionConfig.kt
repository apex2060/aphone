package com.apex2060.softphone.config

import android.graphics.Color
import org.json.JSONObject

data class SpeedDial(val label: String, val number: String)

/**
 * Public Firebase Android client values for the server that provisioned this line.
 * Not a secret. Absent when the line uses the default app, or no push at all.
 */
data class PushConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val gcmSenderId: String,
)

/** Colors are ARGB ints, ready for androidx.compose.ui.graphics.Color(int). */
data class ThemeColors(
    val dark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val accept: Int,
    val decline: Int,
)

/**
 * The provisioning document returned by the setup endpoint. Same fields as
 * 22 Phone. See docs/PROVISIONING.md. Only sip.username and sip.password are required.
 */
data class ProvisionConfig(
    val title: String,
    val subtitle: String?,
    val sipUsername: String,
    val sipPassword: String,
    val theme: ThemeColors,
    val countryCode: String?,
    val allowFreeDial: Boolean,
    val speedDials: List<SpeedDial>,
    val historyLimit: Int,
    val adminPin: String?,
    val refreshHours: Int,
    val push: PushConfig?,
) {
    companion object {
        val DEFAULT_THEME = ThemeColors(
            dark = true,
            primary = 0xFF0B5FFF.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            background = 0xFF0E1116.toInt(),
            onBackground = 0xFFE6E8EB.toInt(),
            surface = 0xFF171B22.toInt(),
            onSurface = 0xFFE6E8EB.toInt(),
            accept = 0xFF1DB954.toInt(),
            decline = 0xFFE5484D.toInt(),
        )

        /** @throws IllegalArgumentException when the document is unusable. */
        fun parse(json: String): ProvisionConfig {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("Setup server did not return JSON")
            }
            val sip = root.optJSONObject("sip")
                ?: throw IllegalArgumentException("Missing \"sip\" credentials")
            val user = sip.optString("username").trim()
            val pass = sip.optString("password")
            require(user.isNotEmpty() && pass.isNotEmpty()) { "SIP username and password are required" }

            val t = root.optJSONObject("theme")
            val d = DEFAULT_THEME
            fun color(key: String, fallback: Int): Int {
                val raw = t?.optString(key).orEmpty()
                return if (raw.isEmpty()) fallback else try {
                    Color.parseColor(raw)
                } catch (e: IllegalArgumentException) {
                    fallback
                }
            }
            val theme = ThemeColors(
                dark = t?.optBoolean("dark", d.dark) ?: d.dark,
                primary = color("primary", d.primary),
                onPrimary = color("onPrimary", d.onPrimary),
                background = color("background", d.background),
                onBackground = color("onBackground", d.onBackground),
                surface = color("surface", d.surface),
                onSurface = color("onSurface", d.onSurface),
                accept = color("accept", d.accept),
                decline = color("decline", d.decline),
            )

            val dialing = root.optJSONObject("dialing")
            val speed = root.optJSONArray("speedDials")
            val speedDials = buildList {
                if (speed != null) for (i in 0 until speed.length()) {
                    val o = speed.optJSONObject(i) ?: continue
                    val number = o.optString("number").trim()
                    if (number.isNotEmpty()) add(SpeedDial(o.optString("label", number).ifBlank { number }, number))
                }
            }

            return ProvisionConfig(
                title = root.optString("title").ifBlank { "22 Phone" },
                subtitle = root.optString("subtitle").ifBlank { null },
                sipUsername = user,
                sipPassword = pass,
                theme = theme,
                countryCode = dialing?.optString("countryCode")?.filter(Char::isDigit)?.ifBlank { null },
                allowFreeDial = dialing?.optBoolean("allowFreeDial", true) ?: true,
                speedDials = speedDials,
                historyLimit = root.optInt("historyLimit", 200).coerceIn(10, 5000),
                adminPin = root.optString("adminPin").ifBlank { null },
                refreshHours = root.optInt("refreshHours", 24).coerceAtLeast(0),
                push = parsePush(root.optJSONObject("push")),
            )
        }

        /** Missing or incomplete `push` means this line does not name its own Firebase project. */
        fun parsePush(raw: JSONObject?): PushConfig? {
            if (raw == null) return null
            val projectId = raw.optString("projectId").trim()
            val applicationId = raw.optString("applicationId").trim()
            val apiKey = raw.optString("apiKey").trim()
            val gcmSenderId = raw.optString("gcmSenderId").trim()
            if (projectId.isEmpty() || applicationId.isEmpty() || apiKey.isEmpty() || gcmSenderId.isEmpty()) {
                return null
            }
            return PushConfig(projectId, applicationId, apiKey, gcmSenderId)
        }
    }
}
