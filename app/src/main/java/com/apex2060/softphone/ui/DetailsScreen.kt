package com.apex2060.softphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.apex2060.softphone.ClientProbe
import com.apex2060.softphone.OutboundRoute
import com.apex2060.softphone.SipEngine
import com.apex2060.softphone.TransportMode
import com.apex2060.softphone.UiText
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.phone.Notifications
import com.apex2060.softphone.phone.PhoneService
import java.io.File

@Composable
fun DetailsScreen(engine: SipEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val line by engine.line.collectAsState()
    val selected = ConfigStore.transportOverride()
    var outbound by remember { mutableStateOf(ConfigStore.outboundRoute()) }
    val probe by ClientProbe.text.collectAsState()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text("Details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            val phoneOn by ConfigStore.phoneEnabled.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (phoneOn) {
                    Button(onClick = { PhoneService.turnOff(context) }) { Text("Turn Off") }
                } else {
                    Button(onClick = { PhoneService.turnOn(context) }) { Text("Turn On") }
                }
                StatusLine("Registration", if (phoneOn) UiText.scrub(line.status) else "Phone is off")
                StatusLine("Username", UiText.scrub(line.username.ifBlank { "—" } + if (line.manual) " (manual)" else ""))
                StatusLine("Network", UiText.networkIndicator(line.network).ifBlank { "—" })
                StatusLine("Transport", UiText.scrub(line.transport))
                StatusLine("Remembered for this network", UiText.scrub(line.remembered))
                StatusLine("RTP", "in=${line.rtpIn} out=${line.rtpOut}")
                StatusLine("Notifications", if (Notifications.notificationsAllowed(context)) "allowed" else "denied")
                StatusLine("Full-screen alerts", if (Notifications.canUseFullScreenIntent(context)) "allowed" else "denied")
                StatusLine("Battery", if (Notifications.batteryUnrestricted(context)) "unrestricted" else "optimized")
                Text("Outgoing route", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                OutboundRoute.entries.forEach { route ->
                    OverrideRow(route.uiLabel, outbound == route) {
                        outbound = route
                        engine.setOutboundRoute(route)
                    }
                }
                StatusLine("Alternate route probe", UiText.scrub(probe))
                Text("Transport override", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                OverrideRow("Auto", selected == null) { engine.setTransportOverride(null) }
                TransportMode.entries.forEach { mode ->
                    OverrideRow(mode.uiLabel, selected == mode) { engine.setTransportOverride(mode) }
                }
                if (!Notifications.notificationsAllowed(context)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                    }) { Text("Allow notifications") }
                }
                if (!Notifications.batteryUnrestricted(context)) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }
                    }) { Text("Unrestrict battery") }
                }
                if (!Notifications.canUseFullScreenIntent(context) && Build.VERSION.SDK_INT >= 34) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }) { Text("Allow full-screen alerts") }
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { share(context, engine) }) { Text("Share") }
                Button(onClick = { copy(context, engine) }) { Text("Copy") }
                TextButton(onClick = { engine.clearLog() }) { Text("Clear") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(engine.lines) { row ->
                    Text(UiText.scrub(row), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun OverrideRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.weight(1f))
    }
}

private fun shownLog(engine: SipEngine): String = UiText.scrub(engine.logText())

private fun copy(context: android.content.Context, engine: SipEngine) {
    val text = shownLog(engine)
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("22 Phone log", text))
    Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
}

private fun share(context: android.content.Context, engine: SipEngine) {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "22phone-log.txt")
    file.writeText(shownLog(engine))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "22 Phone log")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share log")) }
        .onFailure { Toast.makeText(context, it.message ?: "Could not share the log", Toast.LENGTH_LONG).show() }
}
