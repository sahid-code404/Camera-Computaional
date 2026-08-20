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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.sahid.camera.core.CameraAccessPath
import com.sahid.camera.core.CameraCapabilityProbe
import com.sahid.camera.core.CameraDiagnostics
import com.sahid.camera.core.CameraDiscoverySource
import com.sahid.camera.core.CameraPreviewController
import com.sahid.camera.core.CameraQualificationReport
import com.sahid.camera.core.LensCapability
import com.sahid.camera.ui.CameraTheme
import com.sahid.camera.update.OtaCheckResult
import com.sahid.camera.update.OtaUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                "Camera starts from learned + advertised lens routes. Hidden-ID deep discovery runs only when you request it.",
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
    val scope = rememberCoroutineScope()
    var lenses by remember { mutableStateOf<List<LensCapability>>(emptyList()) }
    var selectedLens by remember { mutableStateOf<LensCapability?>(null) }
    var diagnosticsJson by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Loading learned lens map…") }
    var lensScanBusy by remember { mutableStateOf(false) }
    var otaResult by remember { mutableStateOf<OtaCheckResult?>(null) }
    var otaBusy by remember { mutableStateOf(false) }
    var otaMessage by remember { mutableStateOf("Checking OTA…") }
    val nativeStatus = remember {
        runCatching {
            "Aurora ${AuroraNative.version()} • native self-test ${if (AuroraNative.selfTest()) "OK" else "FAILED"}"
        }.getOrElse { "Aurora native core unavailable" }
    }

    fun applyReport(report: CameraQualificationReport, prefix: String) {
        lenses = report.visibleLenses
        val currentId = selectedLens?.cameraId
        selectedLens = currentId?.let { id -> report.visibleLenses.firstOrNull { it.cameraId == id } }
            ?: report.visibleLenses.firstOrNull { !it.isFrontFacing }
            ?: report.visibleLenses.firstOrNull()
        diagnosticsJson = CameraDiagnostics.toJson(report)
        status = buildString {
            append(prefix)
            append(" • Java ")
            append(report.discovery.javaDirectIds.size)
            append(" • NDK ")
            append(report.discovery.ndkDirectIds.size)
            append(" • hidden ")
            append(report.discovery.hiddenDiscoveredIds.size)
            append(" • lenses ")
            append(report.visibleLenses.size)
            val learned = report.visibleLenses.count {
                CameraDiscoverySource.LEARNED_CACHE in it.discoverySources
            }
            if (learned > 0) {
                append(" • learned ")
                append(learned)
            }
        }
    }

    LaunchedEffect(Unit) {
        val report = withContext(Dispatchers.Default) {
            CameraCapabilityProbe(context).probeFastQualificationReport()
        }
        applyReport(report, "Fast startup")
    }

    LaunchedEffect(Unit) {
        otaBusy = true
        otaResult = OtaUpdateManager.checkForUpdate().also { result ->
            otaMessage = when (result) {
                is OtaCheckResult.Available -> "OTA ${result.update.versionName} available"
                is OtaCheckResult.UpToDate -> "OTA up to date • ${result.versionName}"
                is OtaCheckResult.Failed -> "OTA check unavailable"
            }
        }
        otaBusy = false
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
            Text(
                "${BuildConfig.APPLICATION_ID} • build ${BuildConfig.VERSION_CODE} • $otaMessage",
                color = Color.Gray,
                fontSize = 10.sp,
            )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                diagnosticsJson?.let { diagnostics ->
                    Button(onClick = { shareDiagnostics(context, diagnostics) }) {
                        Text("Diagnostics", fontSize = 10.sp)
                    }
                }

                Button(
                    enabled = !lensScanBusy,
                    onClick = {
                        scope.launch {
                            lensScanBusy = true
                            val previousId = selectedLens?.cameraId
                            // Release the active preview before deep camera opens so OEM camera
                            // resource arbitration cannot create false negatives.
                            selectedLens = null
                            status = "Deep hidden-lens scan… this runs only on demand"
                            val report = withContext(Dispatchers.Default) {
                                CameraCapabilityProbe(context).probeDeepQualificationReport()
                            }
                            lenses = report.visibleLenses
                            selectedLens = previousId?.let { id ->
                                report.visibleLenses.firstOrNull { it.cameraId == id }
                            } ?: report.visibleLenses.firstOrNull { !it.isFrontFacing }
                                ?: report.visibleLenses.firstOrNull()
                            diagnosticsJson = CameraDiagnostics.toJson(report)
                            status = "Lens map learned • ${report.visibleLenses.size} working cameras cached for this ROM"
                            lensScanBusy = false
                        }
                    },
                ) {
                    Text(if (lensScanBusy) "Scanning…" else "Find lenses", fontSize = 10.sp)
                }

                Button(
                    enabled = !otaBusy,
                    onClick = {
                        val current = otaResult
                        if (current is OtaCheckResult.Available) {
                            if (!OtaUpdateManager.canRequestPackageInstalls(context)) {
                                otaMessage = "Allow Camera to install updates, then tap Update again"
                                OtaUpdateManager.openUnknownSourcesSettings(context)
                            } else {
                                scope.launch {
                                    otaBusy = true
                                    otaMessage = "Downloading ${current.update.versionName}…"
                                    val apk = OtaUpdateManager.downloadAndVerify(context, current.update)
                                    otaBusy = false
                                    apk.onSuccess { file ->
                                        otaMessage = "Opening Android installer…"
                                        OtaUpdateManager.launchInstaller(context, file)
                                    }.onFailure { error ->
                                        otaMessage = "OTA download failed: ${error.message ?: error.javaClass.simpleName}"
                                    }
                                }
                            }
                        } else {
                            scope.launch {
                                otaBusy = true
                                otaMessage = "Checking OTA…"
                                otaResult = OtaUpdateManager.checkForUpdate().also { result ->
                                    otaMessage = when (result) {
                                        is OtaCheckResult.Available -> "OTA ${result.update.versionName} available"
                                        is OtaCheckResult.UpToDate -> "OTA up to date • ${result.versionName}"
                                        is OtaCheckResult.Failed -> "OTA check failed: ${result.detail}"
                                    }
                                }
                                otaBusy = false
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            otaBusy -> "Wait…"
                            otaResult is OtaCheckResult.Available -> "Update"
                            else -> "OTA"
                        },
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
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
                "Normal launch: learned + advertised routes only • Find lenses: deep hidden scan + real-frame cache",
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
            val hidden = CameraDiscoverySource.HIDDEN_ID_PROBE in lens.discoverySources
            val learned = CameraDiscoverySource.LEARNED_CACHE in lens.discoverySources
            val previewLabel = buildString {
                if (learned) append("Learned • ") else if (hidden) append("Hidden • ")
                append(
                    when {
                        lens.accessPath == CameraAccessPath.NDK_DIRECT && lens.qualification.previewSessionQualified ->
                            "NDK preview"
                        lens.qualification.previewSessionQualified -> "Camera2 preview"
                        lens.accessPath == CameraAccessPath.NDK_DIRECT && lens.qualification.yuvSessionQualified ->
                            "NDK YUV"
                        lens.qualification.yuvSessionQualified -> "YUV preview"
                        else -> "Diagnostic only"
                    }
                )
            }
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
                        append(previewLabel)
                        lens.focalLengthMm?.let {
                            append(" • ")
                            append(String.format("%.1fmm", it))
                        }
                        if (lens.rawUsable) {
                            append(" • RAW")
                            qualifiedRaw?.let { append(" ${it.width}×${it.height}") }
                        }
                    },
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
