package com.apex2060.softphone.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex2060.softphone.R
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.ProvisionConfig
import com.apex2060.softphone.phone.CallPhase
import com.apex2060.softphone.phone.CallState
import com.apex2060.softphone.phone.PhoneManager
import kotlinx.coroutines.delay

/** Incoming-call and in-call screen. Shows over the lock screen and finishes when the call is gone. */
class CallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleAnswer(intent)

        // Driven from the lifecycle rather than composition: Compose stops recomposing while the screen is
        // off (proximity sensor, lock screen), which used to leave this screen and its timer up after hangup.
        lifecycleScope.launch {
            PhoneManager.call.collect { call ->
                updateProximity(call)
                if (call == null || call.phase == CallPhase.ENDED) finish()
            }
        }

        setContent {
            val call by PhoneManager.call.collectAsState()
            val config by ConfigStore.config.collectAsState()
            val current = call
            SoftphoneTheme(config?.theme ?: ProvisionConfig.DEFAULT_THEME) {
                if (current != null) CallScreen(current, config?.speedDials.orEmpty()
                    .associate { PhoneManager.normalize(it.number) to it.label }[current.number])
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneManager.onActivityStarted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAnswer(intent)
    }

    override fun onStop() {
        PhoneManager.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        proximity?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun handleAnswer(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) PhoneManager.answer()
    }

    /** Blank the screen against the user's ear during a handset call. */
    private fun updateProximity(call: CallState?) {
        val want = call != null && call.phase != CallPhase.RINGING_IN && call.phase != CallPhase.ENDED && !call.speaker
        val pm = getSystemService(PowerManager::class.java)
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "softphone:proximity")
        }
        val lock = proximity ?: return
        if (want && !lock.isHeld) lock.acquire(4 * 60 * 60_000L)
        if (!want && lock.isHeld) lock.release()
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
    }
}

@Composable
private fun CallScreen(call: CallState, name: String?) {
    val colors = LocalCallColors.current
    var showKeypad by remember { mutableStateOf(false) }
    var dtmf by remember { mutableStateOf("") }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.answeredAt) {
        while (call.answeredAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val status = when (call.phase) {
        CallPhase.RINGING_IN -> "Incoming call"
        CallPhase.DIALING -> "Calling…"
        CallPhase.RINGING_OUT -> "Ringing…"
        CallPhase.ACTIVE -> call.answeredAt?.let { DateUtils.formatElapsedTime((now - it) / 1000) } ?: "Connected"
        CallPhase.ENDED -> call.endedLabel ?: "Call ended"
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                name ?: call.number, fontSize = 32.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (name != null) Text(call.number, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dtmf.isNotEmpty()) Text(dtmf, fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)

            Spacer(Modifier.weight(1f))

            when (call.phase) {
                CallPhase.RINGING_IN -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(R.drawable.ic_call_end, colors.decline, size = 76, contentDescription = "Decline") {
                        PhoneManager.decline()
                    }
                    RoundButton(R.drawable.ic_call, colors.accept, size = 76, contentDescription = "Answer") {
                        PhoneManager.answer()
                    }
                }
                CallPhase.ENDED -> Unit
                else -> {
                    if (showKeypad) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("123", "456", "789", "*0#").forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    row.forEach { d ->
                                        DialKey(d, "", onClick = { dtmf += d; PhoneManager.sendDigit(d) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    val idle = MaterialTheme.colorScheme.surface
                    val on = MaterialTheme.colorScheme.primary
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(R.drawable.ic_mic_off, if (call.muted) on else idle, contentDescription = "Mute",
                            tint = if (call.muted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                            PhoneManager.toggleMute()
                        }
                        RoundButton(R.drawable.ic_dialpad, if (showKeypad) on else idle, contentDescription = "Keypad",
                            tint = if (showKeypad) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                            showKeypad = !showKeypad
                        }
                        RoundButton(R.drawable.ic_volume_up, if (call.speaker) on else idle, contentDescription = "Speaker",
                            tint = if (call.speaker) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                            PhoneManager.toggleSpeaker()
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    RoundButton(R.drawable.ic_call_end, colors.decline, size = 76, contentDescription = "Hang up") {
                        PhoneManager.hangup()
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
