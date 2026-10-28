package com.apex2060.softphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
fun DetailsScreen(engine: SipEngine, lineId: String, onBack: () -> Unit, onHome: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val stored by ConfigStore.lines.collectAsState()
    val line = stored.firstOrNull { it.id == lineId }
    if (line == null) {
        LaunchedEffect(lineId) { onBack() }
        return
    }
    val statuses by engine.statuses.collectAsState()
    val status = statuses[lineId]
    val network by engine.network.collectAsState()
    val selected = line.transportMode()
    var outbound by remember(line.outboundRoute) { mutableStateOf(line.route()) }
    val probe by ClientProbe.text.collectAsState()
    val autoRecording by ConfigStore.autoRecording.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    var pendingRecord by remember { mutableStateOf<Boolean?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            PhoneAppBar(engine, onTitle = onHome, onNetwork = null)
            Column(
                Modifier.weight(1f).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
                Text(
                    "Details",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.config.title, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (line.enabled) "Phone is on" else "Phone is off",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Switch(
                        checked = line.enabled,
                        onCheckedChange = { on ->
                            if (on) PhoneService.turnOn(context, lineId) else PhoneService.turnOff(context, lineId)
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Auto-record calls", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Records every call. You'll be asked to save or discard each recording when the call ends.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoRecording,
                        onCheckedChange = { want ->
                            val pin = line.config.adminPin
                            if (pin == null) ConfigStore.setAutoRecording(want) else pendingRecord = want
                        },
                    )
                }
                val regText = if (!line.enabled) "Off" else UiText.scrub(status?.status ?: "Offline")
                StatusLine("Registration", regText)
                StatusLine(
                    "Username",
                    UiText.scrub(
                        (status?.username?.ifBlank { line.config.sipUsername } ?: line.config.sipUsername) +
                            if (line.manualCreds() != null) " (manual)" else "",
                    ),
                )
                StatusLine("Network", UiText.networkIndicator(network).ifBlank { "—" })
                StatusLine("Transport", UiText.scrub(status?.transport ?: "auto"))
                StatusLine("Remembered for this network", UiText.scrub(status?.remembered ?: "none"))
                StatusLine("RTP", "in=${status?.rtpIn ?: 0} out=${status?.rtpOut ?: 0}")
                StatusLine("Notifications", if (Notifications.notificationsAllowed(context)) "allowed" else "denied")
                StatusLine("Full-screen alerts", if (Notifications.canUseFullScreenIntent(context)) "allowed" else "denied")
                StatusLine("Battery", if (Notifications.batteryUnrestricted(context)) "unrestricted" else "optimized")
                Text("Outgoing route", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                OutboundRoute.entries.forEach { route ->
                    OverrideRow(route.uiLabel, outbound == route) {
                        outbound = route
                        engine.setOutboundRoute(lineId, route)
                    }
                }
                StatusLine("Alternate route probe", UiText.scrub(probe))
                Text("Transport override", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                OverrideRow("Auto", selected == null) { engine.setTransportOverride(lineId, null) }
                TransportMode.entries.forEach { mode ->
                    OverrideRow(mode.uiLabel, selected == mode) { engine.setTransportOverride(lineId, mode) }
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
                TextButton(onClick = { confirmRemove = true }) {
                    Text("Remove this phone", color = LocalCallColors.current.decline)
                }
                AboutLinks(onLicenses = { showLicenses = true })
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
    val requested = pendingRecord
    if (requested != null) {
        RecordingPinDialog(
            accept = { it == line.config.adminPin },
            onDismiss = { pendingRecord = null },
            onConfirm = {
                ConfigStore.setAutoRecording(requested)
                pendingRecord = null
            },
        )
    }
    if (confirmRemove) {
        RemoveDialog(
            pinRequired = line.config.adminPin,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                PhoneService.removeLine(context, lineId)
                onBack()
            },
        )
    }
}

@Composable
fun AppSettingsScreen(engine: SipEngine, onBack: () -> Unit, onHome: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val stored by ConfigStore.lines.collectAsState()
    val pins = stored.mapNotNull { it.config.adminPin }
    val autoRecording by ConfigStore.autoRecording.collectAsState()
    var pendingRecord by remember { mutableStateOf<Boolean?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            PhoneAppBar(engine, onTitle = onHome, onNetwork = null)
            Column(
                Modifier.weight(1f).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    )
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Auto-record calls", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Records every call. You'll be asked to save or discard each recording when the call ends.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = autoRecording,
                            onCheckedChange = { want ->
                                if (pins.isEmpty()) ConfigStore.setAutoRecording(want) else pendingRecord = want
                            },
                        )
                    }
                    StatusLine("Notifications", if (Notifications.notificationsAllowed(context)) "allowed" else "denied")
                    StatusLine("Full-screen alerts", if (Notifications.canUseFullScreenIntent(context)) "allowed" else "denied")
                    StatusLine("Battery", if (Notifications.batteryUnrestricted(context)) "unrestricted" else "optimized")
                    DeviceAccessLinks(context)
                    AboutLinks(onLicenses = { showLicenses = true })
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
    val requested = pendingRecord
    if (requested != null) {
        RecordingPinDialog(
            accept = { it in pins },
            onDismiss = { pendingRecord = null },
            onConfirm = {
                ConfigStore.setAutoRecording(requested)
                pendingRecord = null
            },
        )
    }
}

@Composable
private fun DeviceAccessLinks(context: android.content.Context) {
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

@Composable
private fun RecordingPinDialog(accept: (String) -> Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the admin PIN to change auto-record.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; wrong = false },
                    label = { Text("Admin PIN") },
                    isError = wrong,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (accept(pin)) onConfirm() else wrong = true }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoveDialog(pinRequired: String?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This unregisters it on this device. Other phones stay as they are.")
                if (pinRequired != null) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it; wrong = false },
                        label = { Text("Admin PIN") },
                        isError = wrong,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pinRequired == null || pin == pinRequired) onConfirm() else wrong = true
            }) { Text("Remove", color = LocalCallColors.current.decline) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
