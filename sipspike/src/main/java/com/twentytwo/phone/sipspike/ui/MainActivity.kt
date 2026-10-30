package com.twentytwo.phone.sipspike.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.twentytwo.phone.sipspike.SpikeApplication
import com.twentytwo.phone.sipspike.config.ConfigStore
import com.twentytwo.phone.sipspike.phone.SpikeNotifications
import com.twentytwo.phone.sipspike.phone.SpikeService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = (application as SpikeApplication).engine
        setContent {
            val config by ConfigStore.config.collectAsState()
            val cfg = config
            if (cfg == null) {
                SoftphoneTheme { ScanScreen() }
            } else {
                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { askBatteryExemption() }
                LaunchedEffect(cfg.sipUsername) {
                    SpikeService.start(this@MainActivity)
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                }
                SoftphoneTheme(cfg.theme) {
                    HomeScreen(cfg, onOpenCall = ::openCall, onDial = { number ->
                        if (engine.dial(number)) openCall()
                    })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as SpikeApplication).engine.onActivityStarted()
    }

    override fun onStop() {
        (application as SpikeApplication).engine.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (ConfigStore.config.value == null) return
        (application as SpikeApplication).engine.refreshRegistration("resume")
        if (ConfigStore.askedBattery) askFullScreenIntent()
    }

    private fun openCall() = startActivity(Intent(this, CallActivity::class.java))

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
