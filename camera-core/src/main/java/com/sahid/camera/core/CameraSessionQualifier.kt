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
import com.sahid.camera.aurora.NativeCameraSession
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase-01 runtime session qualifier.
 *
 * NDK_DIRECT never falls back to Java CameraManager: the complete route stays
 * ACameraManager -> ACameraDevice -> ACameraCaptureSession -> ACaptureRequest and is only
 * accepted after an actual SurfaceTexture/YUV/RAW frame arrives. Java/physical paths keep
 * Camera2 session qualification and physical routing through setPhysicalCameraId().
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
        return if (lens.accessPath == CameraAccessPath.NDK_DIRECT) {
            qualifyNativeDirect(lens)
        } else {
            qualifyJavaPath(lens)
        }
    }

    private fun qualifyNativeDirect(lens: LensCapability): LensCapability {
        val previewSize = chooseQualificationSize(lens.previewSizes)
        val yuvSize = chooseQualificationSize(lens.yuvSizes)

        val previewCheck = previewSize?.let { checkNativePreviewFrame(lens.cameraId, it) }
        val previewQualified = previewCheck?.supported == true

        val yuvCheck = if (!previewQualified && yuvSize != null) {
            checkNativeImageFrame(
                cameraId = lens.cameraId,
                size = yuvSize,
                format = ImageFormat.YUV_420_888,
                repeating = true,
            )
        } else {
            null
        }
        val yuvQualified = yuvCheck?.supported == true

        var rawQualifiedSize: Size? = null
        var rawDetail = if (lens.rawSupported) "RAW frame not tested" else "RAW not advertised"
        if (lens.rawSupported) {
            for (rawSize in boundedRawCandidates(lens.rawSizes)) {
                val rawCheck = checkNativeImageFrame(
                    cameraId = lens.cameraId,
                    size = rawSize,
                    format = ImageFormat.RAW_SENSOR,
                    repeating = false,
                )
                rawDetail = rawCheck.detail
                if (rawCheck.supported) {
                    rawQualifiedSize = rawSize
                    break
                }
            }
            if (lens.rawSizes.isEmpty()) rawDetail = "No RAW output size"
        }

        val checks = listOfNotNull(previewCheck, yuvCheck)
        val cameraOpened = checks.any { it.cameraOpened } || rawQualifiedSize != null
        val detail = buildString {
            append("NDK_DIRECT ")
            append(lens.cameraId)
            append(": ")
            when {
                previewQualified -> append("PRIVATE/Surface frame OK ${previewSize!!.width}×${previewSize.height}")
                previewCheck != null -> append("PRIVATE/Surface failed (${previewCheck.detail})")
                else -> append("no PRIVATE preview size")
            }
            when {
                yuvQualified -> append("; YUV frame OK ${yuvSize!!.width}×${yuvSize.height}")
                yuvCheck != null -> append("; YUV failed (${yuvCheck.detail})")
                !previewQualified && lens.yuvSizes.isEmpty() -> append("; no YUV fallback")
            }
            if (lens.rawSupported) {
                if (rawQualifiedSize != null) {
                    append("; RAW frame OK ${rawQualifiedSize.width}×${rawQualifiedSize.height}")
                } else {
                    append("; RAW failed ($rawDetail)")
                }
            } else {
                append("; RAW not advertised")
            }
        }

        return lens.copy(
            qualification = LensQualification(
                accessPathOpenQualified = cameraOpened,
                previewSessionQualified = previewQualified,
                yuvSessionQualified = yuvQualified,
                rawSessionQualified = rawQualifiedSize != null,
                qualifiedRawSize = rawQualifiedSize,
                detail = detail,
            ),
        )
    }

    private fun checkNativePreviewFrame(cameraId: String, size: Size): NativeFrameCheck {
        val frameArrived = CountDownLatch(1)
        val texture = runCatching {
            SurfaceTexture(false).apply {
                setDefaultBufferSize(size.width, size.height)
                setOnFrameAvailableListener({ frameArrived.countDown() }, handler)
            }
        }.getOrElse {
            return NativeFrameCheck(false, false, "SurfaceTexture allocation failed")
        }
        val surface = Surface(texture)
        val start = NativeCameraSession.startPreview(cameraId, surface)
        if (!start.started) {
            surface.release()
            texture.release()
            return NativeFrameCheck(
                supported = false,
                cameraOpened = start.cameraOpened,
                detail = "native ${start.stageLabel} status ${start.status}",
            )
        }

        val frameSeen = frameArrived.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        NativeCameraSession.stop(start.handle)
        runCatching { texture.setOnFrameAvailableListener(null) }
        surface.release()
        texture.release()
        return NativeFrameCheck(
            supported = frameSeen,
            cameraOpened = true,
            detail = if (frameSeen) "native frame received" else "native Surface frame timed out",
        )
    }

    private fun checkNativeImageFrame(
        cameraId: String,
        size: Size,
        format: Int,
        repeating: Boolean,
    ): NativeFrameCheck {
        val reader = runCatching {
            ImageReader.newInstance(size.width, size.height, format, 3)
        }.getOrElse {
            return NativeFrameCheck(false, false, "ImageReader allocation failed")
        }
        val frameArrived = CountDownLatch(1)
        val imageObserved = AtomicBoolean(false)
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            if (image != null) {
                image.close()
                if (imageObserved.compareAndSet(false, true)) frameArrived.countDown()
            }
        }, handler)

        val start = if (repeating) {
            NativeCameraSession.startPreview(cameraId, reader.surface)
        } else {
            NativeCameraSession.startSingleCapture(cameraId, reader.surface)
        }
        if (!start.started) {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            return NativeFrameCheck(
                supported = false,
                cameraOpened = start.cameraOpened,
                detail = "native ${start.stageLabel} status ${start.status}",
            )
        }

        val frameSeen = frameArrived.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        NativeCameraSession.stop(start.handle)
        reader.setOnImageAvailableListener(null, null)
        reader.close()
        return NativeFrameCheck(
            supported = frameSeen,
            cameraOpened = true,
            detail = if (frameSeen) "native image frame received" else "native image frame timed out",
        )
    }

    private fun qualifyJavaPath(lens: LensCapability): LensCapability {
        val open = openCamera(lens.openCameraId)
        val camera = open.camera
            ?: return lens.copy(
                qualification = LensQualification.unqualified(open.detail),
            )

        return try {
            val previewSize = chooseQualificationSize(lens.previewSizes)
            val yuvSize = chooseQualificationSize(lens.yuvSizes)

            val previewCheck = previewSize?.let {
                checkSession(camera, lens, previewSize = it)
            }
            val previewQualified = previewCheck?.supported == true

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
                append(lens.accessPath.name)
                append(" open ")
                append(lens.openCameraId)
                append(" OK")
                when {
                    previewQualified -> append("; preview session OK ${previewSize!!.width}×${previewSize.height}")
                    previewCheck != null -> append("; preview rejected (${previewCheck.detail})")
                    else -> append("; no SurfaceTexture/private preview size")
                }
                when {
                    yuvQualified -> append("; YUV fallback session OK ${yuvSize!!.width}×${yuvSize.height}")
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
    private data class NativeFrameCheck(
        val supported: Boolean,
        val cameraOpened: Boolean,
        val detail: String,
    )
    private data class RawQualification(
        val qualifiedSize: Size?,
        val mode: String,
        val detail: String,
    )

    private companion object {
        const val OPEN_TIMEOUT_MS = 4_000L
        const val SESSION_TIMEOUT_MS = 4_000L
        const val FRAME_TIMEOUT_MS = 5_000L
        const val MAX_RAW_PROBES = 6
    }
}
