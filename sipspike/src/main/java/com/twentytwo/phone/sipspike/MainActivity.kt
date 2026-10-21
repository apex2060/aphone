package com.twentytwo.phone.sipspike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val engine = (application as SpikeApplication).engine
        setContent {
            MaterialTheme {
                SpikeScreen(
                    engine = engine,
                    onRegister = { user, pass, mode ->
                        ensurePermissions()
                        RegisterService.start(this)
                        engine.register(user, pass, mode)
                    },
                    onUnregister = {
                        engine.unregister()
                        RegisterService.stop(this)
                    },
                    onShare = { shareLog(engine) },
                )
            }
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }

    private fun shareLog(engine: SipEngine) {
        val dir = File(cacheDir, "share")
        dir.mkdirs()
        val file = File(dir, "sipspike-log.txt")
        file.writeText(engine.logText())
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "22 SIP Test log")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, "Share log"))
        } catch (e: RuntimeException) {
            Toast.makeText(this, e.message ?: "Could not share the log", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun SpikeScreen(
    engine: SipEngine,
    onRegister: (String, String, TransportMode) -> Unit,
    onUnregister: () -> Unit,
    onShare: () -> Unit,
) {
    val ui by engine.state.collectAsState()
    var user by remember { mutableStateOf(engine.savedUsername()) }
    var pass by remember { mutableStateOf(engine.savedPassword()) }
    var mode by remember { mutableStateOf(engine.savedMode()) }
    var number by remember { mutableStateOf("") }
    var speaker by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier
                .weight(1.15f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("22 SIP Test", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Side-by-side spike. Does not replace 22 Phone. Credentials stay in app-private storage.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Network: ${ui.network.ifBlank { "…" }}")
            Text(ui.status, style = MaterialTheme.typography.titleMedium)
            ui.nativeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("SIP username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("SIP password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            TransportMode.entries.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == item, onClick = { mode = item })
                    Text(item.label, modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onRegister(user, pass, mode) },
                    enabled = !ui.busy,
                ) { Text(if (ui.registered) "Re-register" else "Register") }
                Button(onClick = onUnregister, enabled = !ui.busy) { Text("Unregister") }
            }
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Button(onClick = { engine.call(number) }, enabled = ui.registered) { Text("Call") }
            if (ui.callPhase == "in") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { engine.answer() }) { Text("Answer") }
                    Button(onClick = { engine.decline() }) { Text("Decline") }
                }
            }
            if (ui.callPhase == "out" || ui.callPhase == "active" || ui.callPhase == "in") {
                Button(onClick = { engine.hangup() }) { Text("Hangup") }
            }
            if (ui.callPhase == "active") {
                Text("DTMF", style = MaterialTheme.typography.labelLarge)
                val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { digit ->
                            Button(onClick = { engine.dtmf(digit) }) { Text(digit) }
                        }
                    }
                }
                Button(onClick = {
                    speaker = !speaker
                    engine.setSpeaker(speaker)
                }) { Text(if (speaker) "Speaker on" else "Speaker off") }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Log", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { engine.clearLog() }) { Text("Clear") }
                Button(onClick = onShare) { Text("Share") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(engine.lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
