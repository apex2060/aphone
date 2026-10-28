package com.apex2060.softphone.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private data class LicenseEntry(val name: String, val terms: String)

private val LICENSES = listOf(
    LicenseEntry("22 Phone", "GNU GPL-2.0-or-later"),
    LicenseEntry("PJSIP / pjproject", "GNU GPL-2.0-or-later"),
    LicenseEntry("OpenSSL", "Apache-2.0"),
    LicenseEntry("Kotlin, AndroidX, Jetpack Compose, CameraX, kotlinx.coroutines", "Apache-2.0"),
    LicenseEntry("ZXing", "Apache-2.0"),
)

@Composable
fun AboutLinks(onLicenses: () -> Unit) {
    val context = LocalContext.current
    Text("About", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    TextButton(onClick = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Legal.PRIVACY_POLICY_URL)))
        }
    }) { Text("Privacy policy") }
    TextButton(onClick = onLicenses) { Text("Open-source licenses") }
}

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Open-source licenses", style = MaterialTheme.typography.titleLarge)
            Text(
                "22 Phone is free software under the GNU GPL, version 2 or any later version. " +
                    "PJSIP is GPL-2.0-or-later. OpenSSL and the Android libraries are Apache-2.0. " +
                    "Apache-2.0 can be combined with this app because the GPL grant includes later versions.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LICENSES.forEach { entry ->
                Column {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(entry.terms, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
