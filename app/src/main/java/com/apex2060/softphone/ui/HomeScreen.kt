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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.apex2060.softphone.R
import com.apex2060.softphone.config.ProvisionConfig
import com.apex2060.softphone.history.CallHistory
import com.apex2060.softphone.history.CallRecord
import com.apex2060.softphone.history.Direction
import com.apex2060.softphone.history.Outcome
import com.apex2060.softphone.phone.CallPhase
import com.apex2060.softphone.phone.PhoneManager
import com.apex2060.softphone.phone.PhoneService
import com.apex2060.softphone.phone.Registration

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
    KEYPAD("Keypad", R.drawable.ic_dialpad),
    SPEED("Contacts", R.drawable.ic_star),
    RECENTS("Recents", R.drawable.ic_history),
}

@Composable
fun HomeScreen(config: ProvisionConfig, onOpenCall: () -> Unit, onDial: (String) -> Unit) {
    val tabs = buildList {
        if (config.allowFreeDial) add(Tab.KEYPAD)
        if (config.speedDials.isNotEmpty()) add(Tab.SPEED)
        add(Tab.RECENTS)
    }
    var selected by remember { mutableIntStateOf(0) }
    val tab = tabs[selected.coerceIn(0, tabs.lastIndex)]
    var showReset by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Header(config, onLongPress = { showReset = true })
            val call by PhoneManager.call.collectAsState()
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
                    Tab.KEYPAD -> Keypad(onDial)
                    Tab.SPEED -> SpeedDials(config, onDial)
                    Tab.RECENTS -> Recents(config, onDial)
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
    if (showReset) ResetDialog(config, onDismiss = { showReset = false })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Header(config: ProvisionConfig, onLongPress: () -> Unit) {
    val reg by PhoneManager.registration.collectAsState()
    val (dot, label) = when (reg) {
        Registration.ONLINE -> LocalCallColors.current.accept to "Ready"
        Registration.CONNECTING -> MaterialTheme.colorScheme.onSurfaceVariant to "Connecting…"
        Registration.FAILED -> LocalCallColors.current.decline to "Offline, retrying"
        Registration.OFFLINE -> LocalCallColors.current.decline to "Offline"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            // Hidden admin gesture: long-press the title bar to reset the phone.
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            config.title, color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(
                listOfNotNull(config.subtitle, label).joinToString(" · "),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val KEYS = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(onDial: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    val reg by PhoneManager.registration.collectAsState()
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
                    DialKey(digit, letters,
                        onClick = { number += digit },
                        onLongClick = if (digit == '0') ({ number += '+' }) else null)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(64.dp))
            RoundButton(
                R.drawable.ic_call, LocalCallColors.current.accept, size = 72,
                enabled = reg == Registration.ONLINE && number.isNotEmpty(),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialKey(digit: Char, letters: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(digit.toString(), fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurface)
            if (letters.isNotEmpty()) Text(
                letters, fontSize = 10.sp, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RoundButton(
    @DrawableRes icon: Int,
    color: androidx.compose.ui.graphics.Color,
    size: Int = 64,
    enabled: Boolean = true,
    contentDescription: String? = null,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription, tint = tint,
            modifier = Modifier.size((size * 0.42f).dp))
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
private fun Recents(config: ProvisionConfig, onDial: (String) -> Unit) {
    val records by CallHistory.records.collectAsState()
    val names = remember(config) { config.speedDials.associate { PhoneManager.normalize(it.number) to it.label } }
    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No calls yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(records, key = { it.id }) { r ->
            val name = names[PhoneManager.normalize(r.number)]
            val canCall = config.allowFreeDial || name != null
            RecentRow(r, name, enabled = canCall) { onDial(r.number) }
            HorizontalDivider(color = MaterialTheme.colorScheme.surface)
        }
    }
}

@Composable
private fun RecentRow(r: CallRecord, name: String?, enabled: Boolean, onCall: () -> Unit) {
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
        if (enabled) Icon(painterResource(R.drawable.ic_call), "Call back", tint = LocalCallColors.current.accept)
    }
}

@Composable
private fun ResetDialog(config: ProvisionConfig, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (config.adminPin == null || pin == config.adminPin) {
                    onDismiss()
                    PhoneService.deprovision(context)
                } else {
                    wrong = true
                }
            }) { Text("Reset", color = LocalCallColors.current.decline) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
