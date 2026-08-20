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
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase-01 runtime session qualifier.
 *
 * A lens is only considered user-visible after an actual CameraCaptureSession with a
 * preview output configures successfully. If RAW is advertised, preview + RAW_SENSOR
 * is separately tested and only receives a RAW badge when that combination configures.
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

        val previewSize = chooseQualificationPreviewSize(lens.previewSizes)
            ?: return lens.copy(
                qualification = LensQualification.unqualified("No preview stream available"),
            )

        val open = openCamera(lens.logicalCameraId)
        val camera = open.camera
            ?: return lens.copy(
                qualification = LensQualification.unqualified(open.detail),
            )

        return try {
            val previewCheck = checkSession(camera, lens, previewSize, rawSize = null)
            if (!previewCheck.supported) {
                lens.copy(
                    qualification = LensQualification.unqualified("Preview rejected: ${previewCheck.detail}"),
                )
            } else if (!lens.rawSupported || lens.rawSizes.isEmpty()) {
                lens.copy(
                    qualification = LensQualification(
                        previewSessionQualified = true,
                        rawSessionQualified = false,
                        qualifiedRawSize = null,
                        detail = "Preview session qualified; RAW not advertised",
                    ),
                )
            } else {
                val rawCandidates = boundedRawCandidates(lens.rawSizes)
                var qualifiedRaw: Size? = null
                var lastRawDetail = "RAW session was not tested"

                for (rawSize in rawCandidates) {
                    val rawCheck = checkSession(camera, lens, previewSize, rawSize)
                    lastRawDetail = rawCheck.detail
                    if (rawCheck.supported) {
                        qualifiedRaw = rawSize
                        break
                    }
                }

                lens.copy(
                    qualification = LensQualification(
                        previewSessionQualified = true,
                        rawSessionQualified = qualifiedRaw != null,
                        qualifiedRawSize = qualifiedRaw,
                        detail = if (qualifiedRaw != null) {
                            "Preview + RAW ${qualifiedRaw.width}×${qualifiedRaw.height} session qualified"
                        } else {
                            "Preview qualified; RAW combination rejected: $lastRawDetail"
                        },
                    ),
                )
            }
        } finally {
            runCatching { camera.close() }
        }
    }

    private fun chooseQualificationPreviewSize(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        val bounded = sizes.filter {
            it.width <= 1920 && it.height <= 1920 &&
                it.width.toLong() * it.height.toLong() <= 1920L * 1080L
        }
        return (bounded.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    /**
     * Prefer maximum RAW first, but bound worst-case probing time on devices that advertise
     * a very large number of redundant sizes. The smallest advertised size is retained as
     * a final compatibility probe.
     */
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
        previewSize: Size,
        rawSize: Size?,
    ): SessionCheck {
        val query = querySupportIfAvailable(lens, previewSize, rawSize)
        if (query == false) {
            return SessionCheck(false, "CameraDeviceSetup rejected configuration")
        }

        val texture = try {
            SurfaceTexture(false).apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }
        } catch (t: Throwable) {
            return SessionCheck(false, "SurfaceTexture failed: ${t.javaClass.simpleName}")
        }
        val previewSurface = Surface(texture)
        val rawReader = rawSize?.let {
            runCatching {
                ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 2)
            }.getOrNull()
        }

        if (rawSize != null && rawReader == null) {
            previewSurface.release()
            texture.release()
            return SessionCheck(false, "RAW ImageReader allocation failed")
        }

        val outputs = mutableListOf<OutputConfiguration>()
        outputs += OutputConfiguration(previewSurface).apply {
            lens.physicalCameraId?.let(::setPhysicalCameraId)
        }
        rawReader?.let { reader ->
            outputs += OutputConfiguration(reader.surface).apply {
                lens.physicalCameraId?.let(::setPhysicalCameraId)
            }
        }

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
        runCatching { previewSurface.release() }
        runCatching { texture.release() }
        return resultRef.get()
    }

    /**
     * API 35+ can reject unsupported combinations without paying session-creation cost.
     * We still create the real session afterward; this is only a fast negative gate.
     */
    private fun querySupportIfAvailable(
        lens: LensCapability,
        previewSize: Size,
        rawSize: Size?,
    ): Boolean? {
        if (Build.VERSION.SDK_INT < 35) return null

        return runCatching {
            if (!manager.isCameraDeviceSetupSupported(lens.logicalCameraId)) {
                return@runCatching null
            }

            val outputs = mutableListOf<OutputConfiguration>()
            outputs += OutputConfiguration(previewSize, SurfaceTexture::class.java).apply {
                lens.physicalCameraId?.let(::setPhysicalCameraId)
            }
            rawSize?.let { size ->
                outputs += OutputConfiguration(ImageFormat.RAW_SENSOR, size).apply {
                    lens.physicalCameraId?.let(::setPhysicalCameraId)
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
            manager.getCameraDeviceSetup(lens.logicalCameraId)
                .isSessionConfigurationSupported(config)
        }.getOrNull()
    }

    override fun close() {
        callbackThread.quitSafely()
    }

    private data class OpenResult(val camera: CameraDevice?, val detail: String)
    private data class SessionCheck(val supported: Boolean, val detail: String)

    private companion object {
        const val OPEN_TIMEOUT_MS = 4_000L
        const val SESSION_TIMEOUT_MS = 4_000L
        const val MAX_RAW_PROBES = 6
    }
}
