package com.apex2060.softphone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.ProvisionConfig
import com.apex2060.softphone.phone.Notifications
import com.apex2060.softphone.phone.PhoneService
import com.apex2060.softphone.record.Recordings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Recordings.restoreDialog(this)
        openRecordingPrompt(intent)
        setContent {
            val stored by ConfigStore.lines.collectAsState()
            var adding by remember { mutableStateOf(false) }
            val theme = (stored.firstOrNull { it.enabled } ?: stored.firstOrNull())?.config?.theme
                ?: ProvisionConfig.DEFAULT_THEME
            if (stored.isEmpty()) {
                SoftphoneTheme { ScanScreen() }
            } else if (adding) {
                SoftphoneTheme(theme) { ScanScreen(onCancel = { adding = false }) }
            } else {
                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { askBatteryExemption() }
                LaunchedEffect(stored.joinToString { it.id }) {
                    if (ConfigStore.anyEnabled()) PhoneService.start(this@MainActivity)
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                }
                SoftphoneTheme(theme) {
                    HomeScreen(onOpenCall = ::openCall, onAdd = { adding = true })
                    RecordingPromptHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRecordingPrompt(intent)
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
        if (ConfigStore.askedBattery) askFullScreenIntent()
    }

    private fun openCall() = startActivity(Intent(this, CallActivity::class.java))

    private fun openRecordingPrompt(intent: Intent?) {
        if (intent?.getBooleanExtra(Recordings.EXTRA_PROMPT, false) != true) return
        intent.removeExtra(Recordings.EXTRA_PROMPT)
        Recordings.showPrompt(this)
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        if (ConfigStore.askedBattery) return
        ConfigStore.askedBattery = true
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val opened = runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.isSuccess
            if (opened) return
        }
        askFullScreenIntent()
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
