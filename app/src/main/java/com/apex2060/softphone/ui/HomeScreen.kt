package com.apex2060.softphone.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.apex2060.softphone.CallPhase
import com.apex2060.softphone.R
import com.apex2060.softphone.Registration
import com.apex2060.softphone.UiText
import com.apex2060.softphone.SipEngine
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.ProvisionConfig
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.CallRecord
import com.apex2060.softphone.history.Direction
import com.apex2060.softphone.history.Outcome
import com.apex2060.softphone.phone.PhoneService

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    KEYPAD("Keypad", R.drawable.ic_dialpad),
    SPEED("Contacts", R.drawable.ic_star),
    RECENTS("Recents", R.drawable.ic_history),
}

private val KEYS = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

@Composable
fun HomeScreen(config: ProvisionConfig, onOpenCall: () -> Unit, onDial: (String) -> Unit) {
    val context = LocalContext.current
    val engine = (context.applicationContext as SoftphoneApp).engine
    val tabs = buildList {
        if (config.allowFreeDial) add(Tab.KEYPAD)
        if (config.speedDials.isNotEmpty()) add(Tab.SPEED)
        add(Tab.RECENTS)
    }
    var selected by remember { mutableIntStateOf(0) }
    val tab = tabs[selected.coerceIn(0, tabs.lastIndex)]
    var showReset by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val phoneOn by ConfigStore.phoneEnabled.collectAsState()

    if (showDetails) {
        DetailsScreen(engine, onBack = { showDetails = false })
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Header(
                config, engine, phoneOn,
                onLongPress = { showReset = true },
                onDetails = { showDetails = true },
                onPhoneChange = { on ->
                    if (on) PhoneService.turnOn(context) else PhoneService.turnOff(context)
                },
            )
            if (!phoneOn) {
                PhoneOff()
                return@Column
            }
            val call by engine.call.collectAsState()
            if (call != null && call?.phase != CallPhase.ENDED) {
                Text(
                    "Call in progress · tap to return",
                    color = androidx.compose.ui.graphics.Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalCallColors.current.accept)
                        .clickable(onClick = onOpenCall)
                        .padding(12.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.KEYPAD -> Keypad(engine, onDial)
                    Tab.SPEED -> SpeedDials(config, onDial)
                    Tab.RECENTS -> Recents(engine, config, onDial)
                }
            }
            if (tabs.size > 1) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { selected = i },
                            icon = { Icon(painterResource(t.icon), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    }
    if (showReset) ResetDialog(config, engine, onDismiss = { showReset = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(
    config: ProvisionConfig,
    engine: SipEngine,
    phoneOn: Boolean,
    onLongPress: () -> Unit,
    onDetails: () -> Unit,
    onPhoneChange: (Boolean) -> Unit,
) {
    val line by engine.line.collectAsState()
    val reg by engine.registration.collectAsState()
    val (dot, label) = if (!phoneOn) {
        MaterialTheme.colorScheme.onSurfaceVariant to "Phone is off"
    } else when (reg) {
        Registration.ONLINE -> LocalCallColors.current.accept to "Ready"
        Registration.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant to "Connecting…"
        Registration.FAILED -> LocalCallColors.current.decline to "Failed, retrying"
        Registration.OFFLINE -> LocalCallColors.current.decline to "Offline"
    }
    val network = UiText.networkIndicator(line.network)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            config.title, color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = "Open details",
                    onClick = onDetails,
                    onLongClick = onLongPress,
                ),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(
                listOfNotNull(config.subtitle, label).joinToString(" · "),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = phoneOn,
                onCheckedChange = onPhoneChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.45f),
                    uncheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                ),
            )
        }
        if (network.isNotEmpty()) {
            Text(
                network,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PhoneOff() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Phone is off",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Incoming calls are stopped. Turn the phone on to register again.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(engine: SipEngine, onDial: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    val reg by engine.registration.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(
            number.ifEmpty { " " }, fontSize = 34.sp, maxLines = 1, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxWidth(),
        )
        KEYS.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (digit, letters) ->
                    DialKey(
                        digit, letters,
                        onClick = { number += digit },
                        onLongClick = if (digit == '0') ({ number += '+' }) else null,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(64.dp))
            RoundButton(
                R.drawable.ic_call, LocalCallColors.current.accept, size = 72,
                enabled = number.isNotEmpty() && reg == Registration.ONLINE,
            ) { onDial(number) }
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .combinedClickable(onClick = { number = number.dropLast(1) }, onLongClick = { number = "" }),
                contentAlignment = Alignment.Center,
            ) {
                if (number.isNotEmpty()) Icon(
                    painterResource(R.drawable.ic_backspace), "Delete",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun SpeedDials(config: ProvisionConfig, onDial: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(config.speedDials) { sd ->
            Row(
                Modifier.fillMaxWidth().clickable { onDial(sd.number) }.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(sd.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(sd.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_call), "Call", tint = LocalCallColors.current.accept)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surface)
        }
    }
}

@Composable
private fun Recents(engine: SipEngine, config: ProvisionConfig, onDial: (String) -> Unit) {
    val records by CallHistory.records.collectAsState()
    val names = remember(config) { config.speedDials.associate { engine.normalize(it.number) to it.label } }
    var chosen by remember { mutableStateOf<CallRecord?>(null) }
    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No calls yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(records, key = { it.id }) { r ->
                val name = names[engine.normalize(r.number)]
                val canCall = config.allowFreeDial || name != null
                RecentRow(
                    r, name, enabled = canCall,
                    onRecording = r.recording?.let { { chosen = r } },
                ) { onDial(r.number) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surface)
            }
        }
    }
    chosen?.let { RecordingActions(it, onDismiss = { chosen = null }) }
}

@Composable
private fun RecentRow(
    r: CallRecord,
    name: String?,
    enabled: Boolean,
    onRecording: (() -> Unit)?,
    onCall: () -> Unit,
) {
    val missed = r.outcome == Outcome.MISSED
    val detail = buildString {
        append(if (r.direction == Direction.INCOMING) "Incoming" else "Outgoing")
        append(" · ")
        append(
            when (r.outcome) {
                Outcome.COMPLETED -> DateUtils.formatElapsedTime(r.durationSec)
                Outcome.MISSED -> "Missed"
                Outcome.DECLINED -> "Declined"
                Outcome.FAILED -> "Not connected"
            }
        )
        append(" · ")
        append(DateUtils.getRelativeTimeSpanString(r.startedAt))
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onCall).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name ?: r.number, style = MaterialTheme.typography.titleMedium,
                color = if (missed) LocalCallColors.current.decline else MaterialTheme.colorScheme.onBackground,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRecording != null) {
            IconButton(onClick = onRecording) {
                Icon(
                    painterResource(R.drawable.ic_recording),
                    contentDescription = "Recording",
                    tint = LocalCallColors.current.decline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (enabled) Icon(painterResource(R.drawable.ic_call), "Call back", tint = LocalCallColors.current.accept)
    }
}

@Composable
private fun ResetDialog(config: ProvisionConfig, engine: SipEngine, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var manualUser by remember { mutableStateOf("") }
    var manualPass by remember { mutableStateOf("") }
    val unlocked = config.adminPin == null || pin == config.adminPin
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This removes the line and call history. The phone returns to the setup scanner.")
                if (config.adminPin != null) OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; wrong = false },
                    label = { Text("Admin PIN") },
                    isError = wrong,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (unlocked) {
                    Text("Manual SIP login is a fallback. Leave it blank to keep using the QR line.")
                    OutlinedTextField(
                        value = manualUser, onValueChange = { manualUser = it },
                        label = { Text("SIP username") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = manualPass, onValueChange = { manualPass = it },
                        label = { Text("SIP password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (ConfigStore.manualCreds() != null) {
                        TextButton(onClick = {
                            ConfigStore.clearManual()
                            val creds = ConfigStore.resolvedCreds()
                            if (creds != null) engine.login(creds.first, creds.second)
                            onDismiss()
                        }) { Text("Use QR credentials again") }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (unlocked && manualUser.isNotBlank() && manualPass.isNotEmpty()) {
                    TextButton(onClick = {
                        ConfigStore.setManual(manualUser, manualPass)
                        engine.login(manualUser.trim(), manualPass)
                        onDismiss()
                    }) { Text("Use manual") }
                }
                TextButton(onClick = {
                    if (unlocked) {
                        onDismiss()
                        PhoneService.deprovision(context)
                    } else {
                        wrong = true
                    }
                }) { Text("Reset", color = LocalCallColors.current.decline) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
