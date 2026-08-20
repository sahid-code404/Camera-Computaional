package com.sahid.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.sahid.camera.aurora.NativeCameraEnumerator
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase-01 runtime session qualifier.
 *
 * Discovery metadata never decides visibility. We first prove the configured access path can
 * open, then try a real SurfaceTexture preview session. If that metadata/path is unusual we
 * also try a YUV stream and RAW rather than dropping the candidate early.
 */
class CameraSessionQualifier(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val callbackThread = HandlerThread("CameraQualification").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val executor = Executor { runnable -> handler.post(runnable) }

    fun qualify(lens: LensCapability): LensCapability {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return lens.copy(
                qualification = LensQualification.unqualified("Camera permission missing during qualification"),
            )
        }

        val nativeOpenDetail = if (lens.accessPath == CameraAccessPath.NDK_DIRECT) {
            val nativeProbe = runCatching { NativeCameraEnumerator.probeDirectOpen(lens.cameraId) }
                .getOrElse {
                    return lens.copy(
                        qualification = LensQualification.unqualified(
                            "NDK direct open probe failed: ${it.javaClass.simpleName}"
                        ),
                    )
                }
            if (!nativeProbe.opened) {
                return lens.copy(
                    qualification = LensQualification.unqualified(
                        "NDK direct open rejected with status ${nativeProbe.status}"
                    ),
                )
            }
            "NDK direct open OK; "
        } else {
            ""
        }

        val open = openCamera(lens.openCameraId)
        val camera = open.camera
            ?: return lens.copy(
                qualification = LensQualification.unqualified(nativeOpenDetail + open.detail),
            )

        return try {
            val previewSize = chooseQualificationSize(lens.previewSizes)
            val yuvSize = chooseQualificationSize(lens.yuvSizes)

            val previewCheck = previewSize?.let {
                checkSession(camera, lens, previewSize = it)
            }
            val previewQualified = previewCheck?.supported == true

            // If SurfaceTexture metadata/session is missing or rejected, prove another real
            // camera output before deciding the candidate is useless.
            val yuvCheck = if (!previewQualified && yuvSize != null) {
                checkSession(camera, lens, yuvSize = yuvSize)
            } else {
                null
            }
            val yuvQualified = yuvCheck?.supported == true

            val rawResult = qualifyRaw(
                camera = camera,
                lens = lens,
                previewSize = previewSize.takeIf { previewQualified },
                yuvSize = yuvSize.takeIf { yuvQualified },
            )

            val detail = buildString {
                append(nativeOpenDetail)
                append("open ")
                append(lens.openCameraId)
                append(" OK")
                when {
                    previewQualified -> append("; preview OK ${previewSize!!.width}×${previewSize.height}")
                    previewCheck != null -> append("; preview rejected (${previewCheck.detail})")
                    else -> append("; no SurfaceTexture/private preview size")
                }
                when {
                    yuvQualified -> append("; YUV fallback OK ${yuvSize!!.width}×${yuvSize.height}")
                    yuvCheck != null -> append("; YUV rejected (${yuvCheck.detail})")
                    !previewQualified && lens.yuvSizes.isEmpty() -> append("; no YUV fallback size")
                }
                if (lens.rawSupported) {
                    if (rawResult.qualifiedSize != null) {
                        append("; RAW OK ${rawResult.qualifiedSize.width}×${rawResult.qualifiedSize.height}")
                        append(" via ${rawResult.mode}")
                    } else {
                        append("; RAW rejected (${rawResult.detail})")
                    }
                } else {
                    append("; RAW not advertised")
                }
            }

            lens.copy(
                qualification = LensQualification(
                    accessPathOpenQualified = true,
                    previewSessionQualified = previewQualified,
                    yuvSessionQualified = yuvQualified,
                    rawSessionQualified = rawResult.qualifiedSize != null,
                    qualifiedRawSize = rawResult.qualifiedSize,
                    detail = detail,
                ),
            )
        } finally {
            runCatching { camera.close() }
        }
    }

    private fun qualifyRaw(
        camera: CameraDevice,
        lens: LensCapability,
        previewSize: Size?,
        yuvSize: Size?,
    ): RawQualification {
        if (!lens.rawSupported) return RawQualification(null, "none", "RAW not advertised")
        if (lens.rawSizes.isEmpty()) return RawQualification(null, "none", "no RAW output size")

        var lastDetail = "RAW session not configured"
        for (rawSize in boundedRawCandidates(lens.rawSizes)) {
            val primary = when {
                previewSize != null -> checkSession(
                    camera,
                    lens,
                    previewSize = previewSize,
                    rawSize = rawSize,
                ) to "preview+raw"
                yuvSize != null -> checkSession(
                    camera,
                    lens,
                    yuvSize = yuvSize,
                    rawSize = rawSize,
                ) to "yuv+raw"
                else -> checkSession(camera, lens, rawSize = rawSize) to "raw-only"
            }
            lastDetail = primary.first.detail
            if (primary.first.supported) {
                return RawQualification(rawSize, primary.second, primary.first.detail)
            }

            // A device can support RAW capture while rejecting a simultaneous preview/RAW
            // combination. Prove the RAW endpoint itself before declaring RAW unusable.
            if (previewSize != null || yuvSize != null) {
                val standalone = checkSession(camera, lens, rawSize = rawSize)
                lastDetail = standalone.detail
                if (standalone.supported) {
                    return RawQualification(rawSize, "raw-only", standalone.detail)
                }
            }
        }
        return RawQualification(null, "none", lastDetail)
    }

    private fun chooseQualificationSize(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        val bounded = sizes.filter {
            it.width <= 1920 && it.height <= 1920 &&
                it.width.toLong() * it.height.toLong() <= 1920L * 1080L
        }
        return (bounded.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun boundedRawCandidates(sizes: List<Size>): List<Size> {
        val sorted = sizes.distinct().sortedByDescending { it.width.toLong() * it.height.toLong() }
        if (sorted.size <= MAX_RAW_PROBES) return sorted
        return (sorted.take(MAX_RAW_PROBES - 1) + sorted.last()).distinct()
    }

    private fun openCamera(cameraId: String): OpenResult {
        val latch = CountDownLatch(1)
        val cameraRef = AtomicReference<CameraDevice?>()
        val detailRef = AtomicReference("Camera open timed out")

        try {
            manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraRef.set(camera)
                    detailRef.set("Opened")
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    detailRef.set("Camera disconnected during qualification")
                    runCatching { camera.close() }
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    detailRef.set("Camera open error $error")
                    runCatching { camera.close() }
                    latch.countDown()
                }
            })
        } catch (t: Throwable) {
            return OpenResult(null, "Camera open failed: ${t.javaClass.simpleName}")
        }

        if (!latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { cameraRef.getAndSet(null)?.close() }
            return OpenResult(null, "Camera open timed out")
        }

        return OpenResult(cameraRef.get(), detailRef.get())
    }

    private fun checkSession(
        camera: CameraDevice,
        lens: LensCapability,
        previewSize: Size? = null,
        yuvSize: Size? = null,
        rawSize: Size? = null,
    ): SessionCheck {
        if (previewSize == null && yuvSize == null && rawSize == null) {
            return SessionCheck(false, "No output supplied")
        }

        val query = querySupportIfAvailable(lens, previewSize, yuvSize, rawSize)
        if (query == false) {
            return SessionCheck(false, "CameraDeviceSetup rejected configuration")
        }

        val texture = previewSize?.let { size ->
            runCatching {
                SurfaceTexture(false).apply {
                    setDefaultBufferSize(size.width, size.height)
                }
            }.getOrNull()
        }
        if (previewSize != null && texture == null) {
            return SessionCheck(false, "SurfaceTexture allocation failed")
        }
        val previewSurface = texture?.let(::Surface)
        val yuvReader = yuvSize?.let {
            runCatching {
                ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 2)
            }.getOrNull()
        }
        val rawReader = rawSize?.let {
            runCatching {
                ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 2)
            }.getOrNull()
        }

        if (yuvSize != null && yuvReader == null) {
            previewSurface?.release()
            texture?.release()
            return SessionCheck(false, "YUV ImageReader allocation failed")
        }
        if (rawSize != null && rawReader == null) {
            yuvReader?.close()
            previewSurface?.release()
            texture?.release()
            return SessionCheck(false, "RAW ImageReader allocation failed")
        }

        val outputs = mutableListOf<OutputConfiguration>()
        previewSurface?.let { outputs += physicalOutput(it, lens) }
        yuvReader?.surface?.let { outputs += physicalOutput(it, lens) }
        rawReader?.surface?.let { outputs += physicalOutput(it, lens) }

        val latch = CountDownLatch(1)
        val sessionRef = AtomicReference<CameraCaptureSession?>()
        val resultRef = AtomicReference(SessionCheck(false, "Session configuration timed out"))
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                sessionRef.set(session)
                resultRef.set(SessionCheck(true, "Configured"))
                latch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                sessionRef.set(session)
                resultRef.set(SessionCheck(false, "onConfigureFailed"))
                latch.countDown()
            }
        }

        try {
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    callback,
                )
            )
        } catch (t: Throwable) {
            resultRef.set(SessionCheck(false, "createCaptureSession: ${t.javaClass.simpleName}"))
            latch.countDown()
        }

        if (!latch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            resultRef.set(SessionCheck(false, "Session configuration timed out"))
        }

        runCatching { sessionRef.getAndSet(null)?.close() }
        runCatching { rawReader?.close() }
        runCatching { yuvReader?.close() }
        runCatching { previewSurface?.release() }
        runCatching { texture?.release() }
        return resultRef.get()
    }

    private fun physicalOutput(surface: Surface, lens: LensCapability): OutputConfiguration =
        OutputConfiguration(surface).apply {
            if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                lens.physicalCameraId?.let(::setPhysicalCameraId)
            }
        }

    /** API 35+ fast-negative check; a real session is still created after a positive result. */
    private fun querySupportIfAvailable(
        lens: LensCapability,
        previewSize: Size?,
        yuvSize: Size?,
        rawSize: Size?,
    ): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return null

        return runCatching {
            if (!manager.isCameraDeviceSetupSupported(lens.openCameraId)) {
                return@runCatching null
            }

            val outputs = mutableListOf<OutputConfiguration>()
            previewSize?.let { size ->
                outputs += OutputConfiguration(size, SurfaceTexture::class.java).apply {
                    if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                        lens.physicalCameraId?.let(::setPhysicalCameraId)
                    }
                }
            }
            yuvSize?.let { size ->
                outputs += OutputConfiguration(ImageFormat.YUV_420_888, size).apply {
                    if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                        lens.physicalCameraId?.let(::setPhysicalCameraId)
                    }
                }
            }
            rawSize?.let { size ->
                outputs += OutputConfiguration(ImageFormat.RAW_SENSOR, size).apply {
                    if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                        lens.physicalCameraId?.let(::setPhysicalCameraId)
                    }
                }
            }

            val noOpCallback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = Unit
                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                noOpCallback,
            )
            manager.getCameraDeviceSetup(lens.openCameraId)
                .isSessionConfigurationSupported(config)
        }.getOrNull()
    }

    override fun close() {
        callbackThread.quitSafely()
    }

    private data class OpenResult(val camera: CameraDevice?, val detail: String)
    private data class SessionCheck(val supported: Boolean, val detail: String)
    private data class RawQualification(
        val qualifiedSize: Size?,
        val mode: String,
        val detail: String,
    )

    private companion object {
        const val OPEN_TIMEOUT_MS = 4_000L
        const val SESSION_TIMEOUT_MS = 4_000L
        const val MAX_RAW_PROBES = 6
    }
}
