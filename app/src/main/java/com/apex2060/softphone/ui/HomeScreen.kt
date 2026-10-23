package com.apex2060.softphone.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.apex2060.softphone.LineUi
import com.apex2060.softphone.R
import com.apex2060.softphone.Registration
import com.apex2060.softphone.SipEngine
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.UiText
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.LineLogic
import com.apex2060.softphone.config.StoredLine
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
fun HomeScreen(onOpenCall: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    val engine = (context.applicationContext as SoftphoneApp).engine
    val stored by ConfigStore.lines.collectAsState()
    val statuses by engine.statuses.collectAsState()
    val lastDial by ConfigStore.lastDial.collectAsState()
    val enabled = stored.filter { it.enabled }
    var picked by remember { mutableStateOf<String?>(null) }
    val selectedId = when {
        enabled.size == 1 -> enabled[0].id
        picked != null && enabled.any { it.id == picked } -> picked
        lastDial != null && enabled.any { it.id == lastDial } -> lastDial
        else -> enabled.firstOrNull()?.id
    }
    val active = stored.firstOrNull { it.id == selectedId }
    val tabs = buildList {
        if (active?.config?.allowFreeDial != false && enabled.isNotEmpty()) add(Tab.KEYPAD)
        if (active != null && active.config.speedDials.isNotEmpty() && enabled.isNotEmpty()) add(Tab.SPEED)
        add(Tab.RECENTS)
    }
    var selected by remember { mutableIntStateOf(0) }
    val tab = tabs[selected.coerceIn(0, tabs.lastIndex)]
    var resetId by remember { mutableStateOf<String?>(null) }
    var detailsId by remember { mutableStateOf<String?>(null) }

    if (detailsId != null && stored.any { it.id == detailsId }) {
        DetailsScreen(engine, detailsId!!, onBack = { detailsId = null })
        return
    }

    fun dial(number: String, lineId: String) {
        val line = ConfigStore.line(lineId)
        if (line == null) {
            Toast.makeText(context, "That phone was removed", Toast.LENGTH_SHORT).show()
            return
        }
        if (!line.enabled) {
            Toast.makeText(context, "That phone is off", Toast.LENGTH_SHORT).show()
            return
        }
        ConfigStore.setLastDial(lineId)
        if (engine.dial(number, lineId)) onOpenCall()
        else Toast.makeText(context, "That phone isn't ready", Toast.LENGTH_SHORT).show()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().then(
                if (tabs.size > 1) Modifier else Modifier.navigationBarsPadding(),
            ),
        ) {
            NetworkBar(engine)
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                stored.forEach { line ->
                    PhoneRow(
                        line,
                        statuses[line.id],
                        onOpen = { detailsId = line.id },
                        onReset = { resetId = line.id },
                        onToggle = { on ->
                            if (on) PhoneService.turnOn(context, line.id) else PhoneService.turnOff(context, line.id)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                }
                TextButton(
                    onClick = {
                        if (stored.size >= LineLogic.MAX_LINES) {
                            Toast.makeText(context, "You can add up to ${LineLogic.MAX_LINES} phones", Toast.LENGTH_SHORT).show()
                        } else {
                            onAdd()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Add phone") }
            }
            if (enabled.isEmpty()) {
                Text(
                    "All phones are off",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            if (enabled.size > 1 && tab != Tab.RECENTS) {
                LinePicker(enabled, selectedId) { id ->
                    picked = id
                    ConfigStore.setLastDial(id)
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.KEYPAD -> if (active != null) {
                        Keypad(
                            statuses[active.id],
                            onDial = { number -> dial(number, active.id) },
                        )
                    }
                    Tab.SPEED -> if (active != null) SpeedDials(active) { dial(it, active.id) }
                    Tab.RECENTS -> Recents(engine, stored, enabled, selectedId, ::dial)
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
    val resetting = stored.firstOrNull { it.id == resetId }
    if (resetting != null) {
        ResetDialog(resetting, onDismiss = { resetId = null })
    }
}

@Composable
private fun NetworkBar(engine: SipEngine) {
    val network by engine.network.collectAsState()
    val label = UiText.networkIndicator(network)
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "22 Phone",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (label.isNotEmpty()) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneRow(
    line: StoredLine,
    status: LineUi?,
    onOpen: () -> Unit,
    onReset: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val label = statusLabel(line.enabled, status?.registration)
    val dot = when {
        !line.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        status?.registration == Registration.ONLINE -> LocalCallColors.current.accept
        status?.registration == Registration.FAILED || status?.registration == Registration.OFFLINE ->
            LocalCallColors.current.decline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                line.config.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClickLabel = "Open details",
                    onClick = onOpen,
                    onLongClick = onReset,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                Text(
                    "${lineNumber(line)} · $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = line.enabled,
            onCheckedChange = onToggle,
        )
    }
}

private fun statusLabel(enabled: Boolean, registration: Registration?): String = when {
    !enabled -> "Off"
    registration == Registration.ONLINE -> "Ready"
    registration == Registration.CONNECTING -> "Connecting"
    registration == Registration.FAILED -> "Failed"
    else -> "Offline"
}

private fun lineNumber(line: StoredLine): String {
    val user = line.resolvedCreds()?.first ?: line.config.sipUsername
    val sub = line.config.subtitle?.trim().orEmpty()
    val subIsNumber = sub.any { it.isDigit() }
    return when {
        subIsNumber && sub != user -> "$sub · $user"
        subIsNumber -> sub
        user.isNotBlank() -> user
        else -> "No number"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    status: LineUi?,
    onDial: (String) -> Unit,
) {
    var number by remember { mutableStateOf("") }
    val ready = status?.registration == Registration.ONLINE
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Text(
            number.ifEmpty { " " }, fontSize = 32.sp, maxLines = 1, textAlign = TextAlign.Center,
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
                enabled = number.isNotEmpty() && ready,
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
private fun LinePicker(lines: List<StoredLine>, selectedId: String?, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Call from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        lines.forEach { line ->
            val selected = line.id == selectedId
            Text(
                line.config.title,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .clickable { onPick(line.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SpeedDials(line: StoredLine, onDial: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(line.config.speedDials) { sd ->
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
private fun Recents(
    engine: SipEngine,
    stored: List<StoredLine>,
    enabled: List<StoredLine>,
    selectedId: String?,
    onDial: (String, String) -> Unit,
) {
    val records by CallHistory.records.collectAsState()
    val context = LocalContext.current
    val names = remember(stored) {
        stored.flatMap { line ->
            line.config.speedDials.map { sd -> engine.normalize(sd.number, line.id) to sd.label }
        }.toMap()
    }
    var chosen by remember { mutableStateOf<CallRecord?>(null) }
    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No calls yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(records, key = { it.id }) { r ->
                val name = names[engine.normalize(r.number, r.lineId)]
                val line = r.lineId?.let { id -> stored.firstOrNull { it.id == id } }
                val allow = line?.config?.allowFreeDial ?: stored.firstOrNull()?.config?.allowFreeDial ?: true
                val canCall = allow || name != null
                RecentRow(
                    r, name, enabled = canCall,
                    onRecording = r.recording?.let { { chosen = r } },
                ) {
                    val target = when {
                        r.lineId != null && stored.none { it.id == r.lineId } -> {
                            Toast.makeText(context, "That phone was removed", Toast.LENGTH_SHORT).show()
                            return@RecentRow
                        }
                        r.lineId != null && line != null && !line.enabled -> {
                            Toast.makeText(context, "That phone is off", Toast.LENGTH_SHORT).show()
                            return@RecentRow
                        }
                        r.lineId != null && line?.enabled == true -> r.lineId
                        enabled.size == 1 -> enabled[0].id
                        selectedId != null -> selectedId
                        else -> {
                            Toast.makeText(context, "That phone is off", Toast.LENGTH_SHORT).show()
                            return@RecentRow
                        }
                    }
                    onDial(r.number, target)
                }
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
        r.lineLabel?.takeIf { it.isNotBlank() }?.let {
            append(" · ")
            append(it)
        }
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
private fun ResetDialog(line: StoredLine, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var manualUser by remember { mutableStateOf("") }
    var manualPass by remember { mutableStateOf("") }
    val unlocked = line.config.adminPin == null || pin == line.config.adminPin
    val others = ConfigStore.lines.value.any { it.id != line.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (others) "This removes ${line.config.title} and its call history."
                    else "This removes the line and call history. The phone returns to the setup scanner.",
                )
                if (line.config.adminPin != null) OutlinedTextField(
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
                    if (line.manualCreds() != null) {
                        TextButton(onClick = {
                            ConfigStore.clearManual(line.id)
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
                        ConfigStore.setManual(line.id, manualUser, manualPass)
                        onDismiss()
                    }) { Text("Use manual") }
                }
                TextButton(onClick = {
                    if (unlocked) {
                        onDismiss()
                        PhoneService.resetLine(context, line.id)
                    } else {
                        wrong = true
                    }
                }) { Text("Reset", color = LocalCallColors.current.decline) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
