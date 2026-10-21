package com.apex2060.softphone.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.lifecycle.lifecycleScope
import com.apex2060.softphone.CallPhase
import com.apex2060.softphone.CallUi
import com.apex2060.softphone.R
import com.apex2060.softphone.SoftphoneApp
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.ProvisionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Incoming-call and in-call screen. Shows over the lock screen and finishes when the call is gone. */
class CallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val engine = (application as SoftphoneApp).engine
        handleAnswer(intent, engine)
        lifecycleScope.launch {
            engine.call.collect { call ->
                updateProximity(call)
                if (call == null || call.phase == CallPhase.ENDED) {
                    leaveLockScreen()
                    finish()
                }
            }
        }
        setContent {
            val call by engine.call.collectAsState()
            val config by ConfigStore.config.collectAsState()
            val current = call
            SoftphoneTheme(config?.theme ?: ProvisionConfig.DEFAULT_THEME) {
                if (current != null && current.phase != CallPhase.ENDED) {
                    val names = config?.speedDials.orEmpty().associate { engine.normalize(it.number) to it.label }
                    CallScreen(engine, current, names[current.number])
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as SoftphoneApp).engine.onActivityStarted()
    }

    override fun onStop() {
        (application as SoftphoneApp).engine.onActivityStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnswer(intent, (application as SoftphoneApp).engine)
    }

    override fun onDestroy() {
        proximity?.takeIf { it.isHeld }?.release()
        leaveLockScreen()
        super.onDestroy()
    }

    /** Drop the lock-screen window so a finished call cannot keep the timer up. */
    private fun leaveLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        }
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
    }

    private fun handleAnswer(intent: Intent?, engine: com.apex2060.softphone.SipEngine) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) engine.answer()
    }

    private fun updateProximity(call: CallUi?) {
        val want = call != null && call.phase != CallPhase.RINGING_IN && call.phase != CallPhase.ENDED && !call.speaker
        val pm = getSystemService(PowerManager::class.java)
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "sipspike:proximity")
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
private fun CallScreen(engine: com.apex2060.softphone.SipEngine, call: CallUi, name: String?) {
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
            Text(name ?: call.number, fontSize = 32.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
            if (name != null) Text(call.number, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dtmf.isNotEmpty()) Text(dtmf, fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            when (call.phase) {
                CallPhase.RINGING_IN -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(R.drawable.ic_call_end, colors.decline, size = 76, contentDescription = "Decline") { engine.decline() }
                    RoundButton(R.drawable.ic_call, colors.accept, size = 76, contentDescription = "Answer") { engine.answer() }
                }
                CallPhase.ENDED -> Unit
                else -> {
                    if (showKeypad) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("123", "456", "789", "*0#").forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                    row.forEach { d ->
                                        DialKey(d, "", onClick = { dtmf += d; engine.sendDigit(d) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    val idle = MaterialTheme.colorScheme.surface
                    val on = MaterialTheme.colorScheme.primary
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundButton(
                            R.drawable.ic_mic_off, if (call.muted) on else idle, contentDescription = "Mute",
                            tint = if (call.muted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ) { engine.toggleMute() }
                        RoundButton(
                            R.drawable.ic_dialpad, if (showKeypad) on else idle, contentDescription = "Keypad",
                            tint = if (showKeypad) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ) { showKeypad = !showKeypad }
                        RoundButton(
                            R.drawable.ic_volume_up, if (call.speaker) on else idle, contentDescription = "Speaker",
                            tint = if (call.speaker) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ) { engine.toggleSpeaker() }
                    }
                    Spacer(Modifier.height(32.dp))
                    RoundButton(R.drawable.ic_call_end, colors.decline, size = 76, contentDescription = "Hang up") {
                        engine.hangup()
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
