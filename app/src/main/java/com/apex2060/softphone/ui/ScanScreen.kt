package com.apex2060.softphone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.apex2060.softphone.config.ConfigStore
import com.apex2060.softphone.config.LineChange
import com.apex2060.softphone.config.Provisioner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface ScanState {
    data object Scanning : ScanState
    data object Fetching : ScanState
    data class Error(val message: String) : ScanState
}

/** First-time setup, or adding another phone when [onCancel] is set. */
@Composable
fun ScanScreen(onCancel: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }
    var state by remember { mutableStateOf<ScanState>(ScanState.Scanning) }
    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }

    fun onCode(text: String) {
        if (state != ScanState.Scanning) return
        val ticket = Provisioner.parseQr(text)
        if (ticket == null) {
            state = ScanState.Error("That QR code isn't a setup code.")
            return
        }
        state = ScanState.Fetching
        scope.launch {
            state = try {
                val (json, config) = Provisioner.fetch(ticket)
                when (ConfigStore.upsert(ticket, json, config)) {
                    LineChange.FULL -> ScanState.Error("You can add up to 8 phones")
                    LineChange.UPDATED -> {
                        Toast.makeText(context, "Phone updated", Toast.LENGTH_SHORT).show()
                        onCancel?.invoke()
                        ScanState.Scanning
                    }
                    LineChange.ADDED -> {
                        onCancel?.invoke()
                        ScanState.Scanning
                    }
                }
            } catch (e: Exception) {
                ScanState.Error(e.message ?: "Setup failed")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera && state == ScanState.Scanning) {
            QrCamera(onCode = ::onCode)
            Box(
                Modifier.align(Alignment.Center).size(260.dp)
                    .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                ScanState.Scanning -> Text(
                    if (hasCamera) "Scan your setup QR code" else "Camera access is needed to scan the setup code",
                    color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                )
                ScanState.Fetching -> {
                    CircularProgressIndicator(color = Color.White)
                    Text("Setting up…", color = Color.White)
                }
                is ScanState.Error -> {
                    Text(s.message, color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { state = ScanState.Scanning }) { Text("Scan again") }
                }
            }
            if (!hasCamera) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
        ) {
            Text(
                "22 Phone",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                color = Color.Black,
            )
        }
    }
}

@Composable
private fun QrCamera(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = context as LifecycleOwner
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }
    val main = ContextCompat.getMainExecutor(context)
    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val fired = AtomicBoolean(false)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, QrAnalyzer { text ->
                if (fired.compareAndSet(false, true)) main.execute { onCode(text) }
            })
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, main)
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false
            )
            onResult(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text)
        } catch (_: Exception) {
        } finally {
            reader.reset()
            image.close()
        }
    }
}
