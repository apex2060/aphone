package com.apex2060.softphone.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.SpikeLog
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.ProvisionTicket
import com.apex2060.softphone.config.Provisioner
import com.apex2060.softphone.config.SetupFlow
import com.apex2060.softphone.record.Recordings
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingTicket by mutableStateOf<ProvisionTicket?>(null)
    private var setupMessage by mutableStateOf<String?>(null)
    private var setupBusy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Recordings.restoreDialog(this)
        openRecordingPrompt(intent)
        consumeSetupIntent(intent)
        setContent {
            val stored by ConfigStore.lines.collectAsState()
            var adding by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            if (adding) {
                SoftphoneTheme { ScanScreen(onCancel = { adding = false }) }
            } else {
                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    askFullScreenIntent()
                }
                LaunchedEffect(stored.joinToString { it.id }) {
                    if (stored.isEmpty()) return@LaunchedEffect
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                }
                SoftphoneTheme {
                    HomeScreen(onOpenCall = ::openCall, onAdd = { adding = true })
                    RecordingPromptHost()
                }
            }
            val ticket = pendingTicket
            val message = setupMessage
            if (ticket != null || message != null) {
                SoftphoneTheme {
                    if (ticket != null) {
                        AlertDialog(
                            onDismissRequest = { if (!setupBusy) pendingTicket = null },
                            title = { Text("Set up this phone line?") },
                            text = {
                                if (setupBusy) {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator()
                                        Text("Setting up…")
                                    }
                                } else {
                                    Text("from ${Provisioner.ticketHost(ticket.url)}")
                                }
                            },
                            confirmButton = {
                                if (!setupBusy) {
                                    TextButton(onClick = {
                                        setupBusy = true
                                        scope.launch {
                                            when (val outcome = SetupFlow.provision(this@MainActivity, ticket)) {
                                                SetupFlow.Outcome.Added -> pendingTicket = null
                                                SetupFlow.Outcome.Updated -> {
                                                    Toast.makeText(this@MainActivity, "Phone updated", Toast.LENGTH_SHORT).show()
                                                    pendingTicket = null
                                                }
                                                SetupFlow.Outcome.Full -> {
                                                    pendingTicket = null
                                                    setupMessage = "You can add up to 8 phones"
                                                }
                                                is SetupFlow.Outcome.Failed -> {
                                                    pendingTicket = null
                                                    setupMessage = SetupFlow.INVALID_LINK
                                                }
                                            }
                                            setupBusy = false
                                        }
                                    }) { Text("Add") }
                                }
                            },
                            dismissButton = {
                                if (!setupBusy) {
                                    TextButton(onClick = { pendingTicket = null }) { Text("Cancel") }
                                }
                            },
                        )
                    } else if (message != null) {
                        AlertDialog(
                            onDismissRequest = { setupMessage = null },
                            title = { Text("Setup") },
                            text = { Text(message) },
                            confirmButton = {
                                TextButton(onClick = { setupMessage = null }) { Text("OK") }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRecordingPrompt(intent)
        consumeSetupIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as SoftphoneApp).engine.onActivityStarted()
    }

    override fun onStop() {
        (application as SoftphoneApp).engine.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!ConfigStore.anyEnabled()) return
        (application as SoftphoneApp).engine.refreshRegistration("resume")
        askFullScreenIntent()
    }

    private fun openCall() = startActivity(Intent(this, CallActivity::class.java))

    private fun openRecordingPrompt(intent: Intent?) {
        if (intent?.getBooleanExtra(Recordings.EXTRA_PROMPT, false) != true) return
        intent.removeExtra(Recordings.EXTRA_PROMPT)
        Recordings.showPrompt(this)
    }

    /** Reads a setup link once. The key is registered for redaction and is not logged. */
    private fun consumeSetupIntent(source: Intent?) {
        val data = source?.data ?: return
        val raw = data.toString()
        val ticket = Provisioner.parseLink(data)
        val setupLink = Provisioner.isSetupLink(raw)
        val cleared = Intent(source)
        cleared.data = null
        setIntent(cleared)
        ticket?.key?.let { SpikeLog.noteSecret(it) }
        if (ticket != null) {
            setupMessage = null
            pendingTicket = ticket
        } else if (setupLink) {
            pendingTicket = null
            setupMessage = SetupFlow.INVALID_LINK
        }
    }

    private fun askFullScreenIntent() {
        if (Build.VERSION.SDK_INT < 34 || ConfigStore.askedFullScreen) return
        val nm = getSystemService(NotificationManager::class.java)
        ConfigStore.askedFullScreen = true
        if (nm.canUseFullScreenIntent()) return
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
    }
}
