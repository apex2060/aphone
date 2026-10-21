package com.apex2060.softphone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.apex2060.softphone.config.ProvisionConfig
import com.apex2060.softphone.config.ThemeColors

data class CallColors(val accept: Color, val decline: Color)

val LocalCallColors = staticCompositionLocalOf {
    CallColors(Color(ProvisionConfig.DEFAULT_THEME.accept), Color(ProvisionConfig.DEFAULT_THEME.decline))
}

@Composable
fun SoftphoneTheme(colors: ThemeColors = ProvisionConfig.DEFAULT_THEME, content: @Composable () -> Unit) {
    val base = if (colors.dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = Color(colors.primary),
        onPrimary = Color(colors.onPrimary),
        primaryContainer = Color(colors.primary),
        onPrimaryContainer = Color(colors.onPrimary),
        secondaryContainer = Color(colors.primary).copy(alpha = 0.25f),
        onSecondaryContainer = Color(colors.onSurface),
        background = Color(colors.background),
        onBackground = Color(colors.onBackground),
        surface = Color(colors.surface),
        onSurface = Color(colors.onSurface),
        surfaceContainer = Color(colors.surface),
        surfaceVariant = Color(colors.surface),
        onSurfaceVariant = Color(colors.onSurface).copy(alpha = 0.7f),
        error = Color(colors.decline),
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCallColors provides CallColors(Color(colors.accept), Color(colors.decline))
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
