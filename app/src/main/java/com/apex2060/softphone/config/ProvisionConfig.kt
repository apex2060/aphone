package com.apex2060.softphone.config

import android.graphics.Color
import org.json.JSONObject

data class SpeedDial(val label: String, val number: String)

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
 * The provisioning document returned by the setup endpoint. See docs/PROVISIONING.md
 * for the wire format; only `sip.username` and `sip.password` are required.
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
                title = root.optString("title").ifBlank { "Softphone" },
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
            )
        }
    }
}
