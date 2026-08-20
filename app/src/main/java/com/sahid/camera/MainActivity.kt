package com.sahid.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sahid.camera.aurora.AuroraNative
import com.sahid.camera.core.CameraCapabilityProbe
import com.sahid.camera.core.CameraDiagnostics
import com.sahid.camera.core.CameraPreviewController
import com.sahid.camera.core.LensCapability
import com.sahid.camera.ui.CameraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CameraTheme {
                CameraPermissionGate()
            }
        }
    }
}

@Composable
private fun CameraPermissionGate() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var requested by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    LaunchedEffect(Unit) {
        if (!granted && !requested) {
            requested = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (granted) {
        CameraScreen()
    } else {
        PermissionScreen(onGrant = { launcher.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Camera permission is required", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Phase 01 compares Java Camera2, NDK Camera2, and logical physical-camera topology before runtime qualification.",
                color = Color.LightGray,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGrant) { Text("Grant camera permission") }
        }
    }
}

@Composable
private fun CameraScreen() {
    val context = LocalContext.current
    var lenses by remember { mutableStateOf<List<LensCapability>>(emptyList()) }
    var selectedLens by remember { mutableStateOf<LensCapability?>(null) }
    var diagnosticsJson by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Comparing Java + NDK + logical camera discovery…") }
    val nativeStatus = remember {
        runCatching {
            "Aurora ${AuroraNative.version()} • native self-test ${if (AuroraNative.selfTest()) "OK" else "FAILED"}"
        }.getOrElse { "Aurora native core unavailable" }
    }

    LaunchedEffect(Unit) {
        val report = withContext(Dispatchers.Default) {
            CameraCapabilityProbe(context).probeQualificationReport()
        }
        lenses = report.visibleLenses
        selectedLens = report.visibleLenses.firstOrNull { !it.isFrontFacing }
            ?: report.visibleLenses.firstOrNull()
        diagnosticsJson = CameraDiagnostics.toJson(report)
        status = buildString {
            append("Java ")
            append(report.discovery.javaDirectIds.size)
            append(" • NDK ")
            append(report.discovery.ndkDirectIds.size)
            append(" • qualified ")
            append(report.visibleLenses.size)
            append('/')
            append(report.candidates.map { it.cameraId }.distinct().size)
            val ndkOnly = report.discovery.ndkDirectIds.count {
                it !in report.discovery.javaDirectIds
            }
            if (ndkOnly > 0) {
                append(" • NDK-only ")
                append(ndkOnly)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        selectedLens?.let { lens ->
            CameraPreview(
                lens = lens,
                onStatus = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text("Camera • Aurora Phase 01", color = Color.White, fontSize = 16.sp)
            Text(status, color = Color.LightGray, fontSize = 12.sp)
            Text(nativeStatus, color = Color.Gray, fontSize = 11.sp)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xB3000000))
                .padding(top = 12.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LensSelector(lenses, selectedLens) { selectedLens = it }
            Spacer(Modifier.height(12.dp))
            ModeRow()
            Spacer(Modifier.height(10.dp))
            diagnosticsJson?.let { diagnostics ->
                Button(onClick = { shareDiagnostics(context, diagnostics) }) {
                    Text("Share diagnostics", fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Only real preview-qualified lenses are shown • all failed paths stay in diagnostics",
                color = Color.Gray,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun LensSelector(
    lenses: List<LensCapability>,
    selected: LensCapability?,
    onSelect: (LensCapability) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        items(lenses, key = { it.stableId }) { lens ->
            val isSelected = selected?.stableId == lens.stableId
            val qualifiedRaw = lens.qualification.qualifiedRawSize
            Column(
                modifier = Modifier
                    .padding(horizontal = 5.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) Color.White else Color(0x66000000))
                    .clickable { onSelect(lens) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(lens.displayName, color = if (isSelected) Color.Black else Color.White, fontSize = 13.sp)
                Text(
                    buildString {
                        lens.focalLengthMm?.let { append(String.format("%.1fmm", it)) }
                        if (lens.rawUsable) {
                            if (isNotEmpty()) append(" • ")
                            append("RAW verified")
                            qualifiedRaw?.let { append(" ${it.width}×${it.height}") }
                        }
                    }.ifBlank { "Preview verified" },
                    color = if (isSelected) Color.DarkGray else Color.LightGray,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun ModeRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        listOf("PHOTO", "VIDEO", "PORTRAIT", "NIGHT", "PRO").forEach { mode ->
            Text(
                mode,
                color = if (mode == "PHOTO") Color.White else Color.Gray,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CameraPreview(
    lens: LensCapability,
    onStatus: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember {
        CameraPreviewController(context.applicationContext, onStatus)
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextureView(viewContext).also(controller::attach)
        },
        update = {
            controller.setLens(lens)
        },
    )

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            controller.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }
}

private fun shareDiagnostics(context: Context, diagnosticsJson: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "Camera Aurora Phase 01 diagnostics")
        putExtra(Intent.EXTRA_TEXT, diagnosticsJson)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share Camera diagnostics"))
}
