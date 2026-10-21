package com.apex2060.softphone.ui

import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.apex2060.softphone.history.CallRecord
import com.apex2060.softphone.record.Recordings
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun RecordingPromptHost() {
    val pending by Recordings.prompt.collectAsState()
    val current = pending ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { Recordings.discard(context) },
        title = { Text("Save this call recording?") },
        text = { if (current.number.isNotBlank()) Text(current.number) },
        confirmButton = {
            TextButton(onClick = { Recordings.save(context) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { Recordings.discard(context) }) { Text("Discard") }
        },
    )
}

@Composable
fun RecordingActions(record: CallRecord, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name = record.recording
    if (name.isNullOrBlank()) return
    val file = Recordings.savedFile(context, name)
    var playing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (!file.exists()) {
        LaunchedEffect(name) {
            Toast.makeText(context, "Recording file is missing", Toast.LENGTH_SHORT).show()
            Recordings.deleteSaved(context, record.id, name)
            onDismiss()
        }
        return
    }
    if (playing) {
        RecordingPlayerDialog(file, onDismiss = { playing = false })
        return
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recording?") },
            confirmButton = {
                TextButton(onClick = {
                    Recordings.deleteSaved(context, record.id, name)
                    onDismiss()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call recording") },
        text = {
            Column {
                TextButton(onClick = { playing = true }) { Text("Play") }
                TextButton(onClick = {
                    shareRecording(context, file)
                    onDismiss()
                }) { Text("Share") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun RecordingPlayerDialog(file: File, onDismiss: () -> Unit) {
    var error by remember(file) { mutableStateOf<String?>(null) }
    val player = remember(file) { MediaPlayer() }
    var ready by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableIntStateOf(0) }
    var duration by remember(file) { mutableIntStateOf(1) }
    var seeking by remember(file) { mutableStateOf(false) }
    DisposableEffect(file) {
        try {
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener {
                duration = it.duration.coerceAtLeast(1)
                ready = true
            }
            player.setOnCompletionListener {
                playing = false
                position = player.duration.coerceAtLeast(0)
            }
            player.prepareAsync()
        } catch (_: Exception) {
            error = "Can't play this recording"
        }
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(playing, ready) {
        while (playing && ready) {
            if (!seeking) position = player.currentPosition
            delay(200)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recording") },
        text = {
            if (error != null) {
                Text(error.orEmpty())
            } else {
                Column {
                    Slider(
                        value = position.coerceIn(0, duration).toFloat(),
                        onValueChange = {
                            seeking = true
                            position = it.toInt()
                        },
                        onValueChangeFinished = {
                            if (ready) runCatching { player.seekTo(position) }
                            seeking = false
                        },
                        valueRange = 0f..duration.toFloat(),
                        enabled = ready,
                    )
                    Text(
                        "${DateUtils.formatElapsedTime(position / 1000L)} / ${DateUtils.formatElapsedTime(duration / 1000L)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (error == null) {
                TextButton(
                    enabled = ready,
                    onClick = {
                        if (player.isPlaying) {
                            player.pause()
                            playing = false
                        } else {
                            runCatching {
                                player.start()
                                playing = true
                            }.onFailure { error = "Can't play this recording" }
                        }
                    },
                ) { Text(if (playing) "Pause" else "Play") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun shareRecording(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("recording", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share recording")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(chooser) }
        .onFailure { Toast.makeText(context, "Could not share the recording", Toast.LENGTH_LONG).show() }
}
