package com.apex2060.softphone.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex2060.softphone.CallPhase
import com.apex2060.softphone.LineStandby
import com.apex2060.softphone.LineUi
import com.apex2060.softphone.R
import com.apex2060.softphone.Registration
import com.apex2060.softphone.SipEngine
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.UiText
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.LineLogic
import com.apex2060.softphone.config.ProvisionConfig
import com.apex2060.softphone.config.StoredLine
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.CallRecord
import com.apex2060.softphone.history.Direction
import com.apex2060.softphone.history.Outcome
import com.apex2060.softphone.phone.PhoneService
import com.apex2060.softphone.push.PushGate

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    KEYPAD("Keypad", R.drawable.ic_dialpad),
    SPEED("Contacts", R.drawable.ic_star),
    RECENTS("Recents", R.drawable.ic_history),
}

private sealed interface Place {
    data object Phones : Place
    data class Dialer(val lineId: String) : Place
    data class LineSettings(val lineId: String) : Place
    data object Settings : Place
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
    var place by remember { mutableStateOf<Place>(Place.Phones) }
    var resetId by remember { mutableStateOf<String?>(null) }

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

    when (val here = place) {
        Place.Settings -> AppSettingsScreen(
            engine,
            onBack = { place = Place.Phones },
            onHome = { place = Place.Phones },
        )
        is Place.LineSettings -> DetailsScreen(
            engine,
            here.lineId,
            onBack = { place = Place.Dialer(here.lineId) },
            onHome = { place = Place.Phones },
        )
        else -> {
            val dialerId = (here as? Place.Dialer)?.lineId
            if (dialerId != null) BackHandler { place = Place.Phones }
            Column(Modifier.fillMaxSize()) {
                PhoneAppBar(
                    engine,
                    onTitle = if (dialerId != null) ({ place = Place.Phones }) else null,
                    onNetwork = {
                        place = if (dialerId != null) Place.LineSettings(dialerId) else Place.Settings
                    },
                )
                val call by engine.call.collectAsState()
                if (call != null && call?.phase != CallPhase.ENDED) {
                    Text(
                        "Call in progress · tap to return",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LocalCallColors.current.accept)
                            .clickable(onClick = onOpenCall)
                            .padding(12.dp),
                    )
                }
                if (dialerId == null) {
                    PhoneList(
                        stored,
                        statuses,
                        onOpen = { place = Place.Dialer(it) },
                        onRemove = { resetId = it },
                        onAdd = onAdd,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(Modifier.weight(1f)) {
                        val line = stored.firstOrNull { it.id == dialerId }
                        if (line == null) {
                            LaunchedEffect(dialerId) { place = Place.Phones }
                        } else {
                            SoftphoneTheme(line.config.theme) {
                                LineDialer(line, statuses[line.id], engine, ::dial)
                            }
                        }
                    }
                }
            }
        }
    }
    val resetting = stored.firstOrNull { it.id == resetId }
    if (resetting != null) {
        RemoveLineDialog(resetting, onDismiss = { resetId = null })
    }
}

@Composable
fun PhoneAppBar(
    engine: SipEngine,
    onTitle: (() -> Unit)?,
    onNetwork: (() -> Unit)?,
) {
    val network by engine.network.collectAsState()
    val detected = UiText.networkIndicator(network)
    val label = detected.ifBlank { "Network" }
    val bar = Color(ProvisionConfig.DEFAULT_THEME.primary)
    val onBar = Color(ProvisionConfig.DEFAULT_THEME.onPrimary)
    Row(
        Modifier
            .fillMaxWidth()
            .background(bar)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "22 Phone",
            color = onBar,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = if (onTitle != null) {
                Modifier.clickable(onClickLabel = "Phones", onClick = onTitle)
            } else {
                Modifier
            },
        )
        Spacer(Modifier.weight(1f))
        Text(
            label,
            color = onBar.copy(alpha = if (detected.isEmpty()) 0.75f else 0.9f),
            style = MaterialTheme.typography.labelLarge,
            modifier = if (onNetwork != null) {
                Modifier.clickable(onClickLabel = "Settings", onClick = onNetwork)
            } else {
                Modifier
            },
        )
    }
}

@Composable
private fun PhoneList(
    stored: List<StoredLine>,
    statuses: Map<String, LineUi>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (stored.isEmpty()) {
        EmptyPhones(onAdd, modifier)
        return
    }
    val allOff = stored.none { it.enabled }
    LazyColumn(modifier.navigationBarsPadding()) {
        if (allOff) {
            item {
                Text(
                    "All phones are off",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(stored, key = { it.id }) { line ->
            PhoneCard(
                line,
                statuses[line.id],
                onOpen = { onOpen(line.id) },
                onRemove = { onRemove(line.id) },
                onToggle = { on ->
                    if (on) PhoneService.turnOn(context, line.id) else PhoneService.turnOff(context, line.id)
                },
            )
        }
        item {
            AddPhoneButton(
                onClick = {
                    if (stored.size >= LineLogic.MAX_LINES) {
                        Toast.makeText(context, "You can add up to ${LineLogic.MAX_LINES} phones", Toast.LENGTH_SHORT).show()
                    } else {
                        onAdd()
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AddPhoneButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Add phone")
    }
}

@Composable
private fun EmptyPhones(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No phones yet",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Ask your business manager for a QR code to set up your phone.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddPhoneButton(onClick = onAdd, modifier = Modifier.padding(top = 24.dp))
        OutlinedCard(Modifier.fillMaxWidth().padding(top = 28.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("New to 22 Phone?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "22 Phone connects to phone lines you set up on Alphabetize. Create an account, add a number and a phone there, then tap Add phone and scan the QR code it shows.",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://a.alphabetize.us/main")))
                        }
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Create an account") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhoneCard(
    line: StoredLine,
    status: LineUi?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val theme = line.config.theme
    val primary = Color(theme.primary)
    val onPrimary = Color(theme.onPrimary)
    val standby = PushGate.parked(line.id)
    val label = shownStatus(line.enabled, status?.registration, standby)
    val dot = when {
        !line.enabled -> onPrimary.copy(alpha = 0.45f)
        status?.registration == Registration.ONLINE || standby -> Color(theme.accept)
        status?.registration == Registration.FAILED -> Color(theme.decline)
        else -> onPrimary.copy(alpha = 0.7f)
    }
    val subtitle = phoneSubtitle(line)
    Surface(
        color = primary,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .combinedClickable(
                        onClickLabel = "Open dialer",
                        onClick = onOpen,
                        onLongClick = onRemove,
                    ),
            ) {
                Text(
                    phoneTitle(line),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onPrimary.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = onPrimary.copy(alpha = 0.9f))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(
                    checked = line.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = onPrimary,
                        checkedTrackColor = onPrimary.copy(alpha = 0.38f),
                        uncheckedThumbColor = onPrimary.copy(alpha = 0.85f),
                        uncheckedTrackColor = onPrimary.copy(alpha = 0.18f),
                        uncheckedBorderColor = onPrimary.copy(alpha = 0.55f),
                    ),
                )
                TextButton(onClick = onRemove) { Text("Remove", color = onPrimary) }
            }
        }
    }
}

/** Business name from provisioning. The SIP username is used only when title is missing. */
internal fun phoneTitle(line: StoredLine): String {
    val title = line.config.title.trim()
    if (title.isNotEmpty()) return title
    return line.config.sipUsername.ifBlank { "Phone" }
}

/** Provisioned subtitle, usually the endpoint name and phone number. */
internal fun phoneSubtitle(line: StoredLine): String = line.config.subtitle?.trim().orEmpty()

private fun shownStatus(enabled: Boolean, registration: Registration?, pushStandby: Boolean): String {
    val label = LineStandby.label(enabled, registration, pushStandby)
    return if (LineStandby.showsPush(enabled, pushStandby) && label == "Ready") "$label · Push" else label
}

@Composable
private fun LineDialer(
    line: StoredLine,
    status: LineUi?,
    engine: SipEngine,
    onDial: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val tabs = buildList {
        if (line.config.allowFreeDial) add(Tab.KEYPAD)
        if (line.config.speedDials.isNotEmpty()) add(Tab.SPEED)
        add(Tab.RECENTS)
    }
    var selected by remember(line.id) { mutableIntStateOf(0) }
    val tab = tabs[selected.coerceIn(0, tabs.lastIndex)]
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().then(
                if (tabs.size > 1) Modifier else Modifier.navigationBarsPadding(),
            ),
        ) {
            LineHeader(line, status, PushGate.parked(line.id)) { on ->
                if (on) PhoneService.turnOn(context, line.id) else PhoneService.turnOff(context, line.id)
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.KEYPAD -> Keypad(status, PushGate.parked(line.id)) { number -> onDial(number, line.id) }
                    Tab.SPEED -> SpeedDials(line) { onDial(it, line.id) }
                    Tab.RECENTS -> LineRecents(engine, line, onDial)
                }
            }
            if (tabs.size > 1) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = item == tab,
                            onClick = { selected = i },
                            icon = { Icon(painterResource(item.icon), contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LineHeader(line: StoredLine, status: LineUi?, pushStandby: Boolean, onToggle: (Boolean) -> Unit) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val subtitle = phoneSubtitle(line)
    val label = shownStatus(line.enabled, status?.registration, pushStandby)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            phoneTitle(line),
            color = onPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                color = onPrimary.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = onPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = line.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = onPrimary,
                    checkedTrackColor = onPrimary.copy(alpha = 0.38f),
                    uncheckedThumbColor = onPrimary.copy(alpha = 0.85f),
                    uncheckedTrackColor = onPrimary.copy(alpha = 0.18f),
                    uncheckedBorderColor = onPrimary.copy(alpha = 0.55f),
                ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    status: LineUi?,
    pushStandby: Boolean,
    onDial: (String) -> Unit,
) {
    var number by remember { mutableStateOf("") }
    val ready = LineStandby.canDial(true, status?.registration, pushStandby)
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
private fun LineRecents(
    engine: SipEngine,
    line: StoredLine,
    onDial: (String, String) -> Unit,
) {
    val records by CallHistory.records.collectAsState()
    val shown = records.filter { it.lineId == line.id }
    val context = LocalContext.current
    val names = remember(line.id, line.config.speedDials) {
        line.config.speedDials.associate { engine.normalize(it.number, line.id) to it.label }
    }
    var chosen by remember { mutableStateOf<CallRecord?>(null) }
    if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No calls yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { record ->
                val name = names[engine.normalize(record.number, line.id)]
                val canCall = line.config.allowFreeDial || name != null
                RecentRow(
                    record, name, enabled = canCall,
                    onRecording = record.recording?.let { { chosen = record } },
                ) {
                    if (!line.enabled) {
                        Toast.makeText(context, "That phone is off", Toast.LENGTH_SHORT).show()
                    } else {
                        onDial(record.number, line.id)
                    }
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
private fun RemoveLineDialog(line: StoredLine, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this phone?") },
        text = {
            Text("This unregisters ${phoneTitle(line)} on this device and deletes its saved setup.")
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                PhoneService.resetLine(context, line.id)
            }) { Text("Remove", color = LocalCallColors.current.decline) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
