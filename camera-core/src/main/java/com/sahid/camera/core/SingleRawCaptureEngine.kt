package com.sahid.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 02A: one persisted RAW_SENSOR exposure -> one standards-compatible DNG.
 *
 * A dedicated RAW session is warmed before the final exposure so a newly opened camera does not
 * capture at an arbitrary/default focus position. Warm-up frames are drained and discarded in
 * memory; only the final timestamp-matched RAW image is persisted as DNG.
 */
class SingleRawCaptureEngine(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)

    fun capture(lens: LensCapability): RawCaptureOutcome {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return RawCaptureOutcome.Failure("Camera permission is required")
        }
        if (!lens.rawSupported || lens.rawSizes.isEmpty()) {
            return RawCaptureOutcome.Unsupported("${lens.displayName} does not advertise RAW_SENSOR")
        }
        if (lens.accessPath == CameraAccessPath.NDK_DIRECT) {
            return RawCaptureOutcome.Unsupported(
                "NDK-only RAW has no standards-correct DNG metadata route; a physical Camera2 family profile is required"
            )
        }

        val surfaceRotationDegrees = DeviceOrientationTracker.surfaceRotationDegrees
        return try {
            RawCaptureOutcome.Success(captureJavaDng(lens, surfaceRotationDegrees))
        } catch (t: Throwable) {
            RawCaptureOutcome.Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private fun captureJavaDng(
        lens: LensCapability,
        surfaceRotationDegrees: Int,
    ): RawCaptureRecord {
        val rawSize = chooseRawSize(lens.rawSizes)
        val thread = HandlerThread("AuroraDngCapture").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { runnable -> handler.post(runnable) }
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)

        val acceptFinalImage = AtomicBoolean(false)
        val imageRef = AtomicReference<Image?>()
        val resultRef = AtomicReference<CaptureResult?>()
        val errorRef = AtomicReference<Throwable?>()
        val pairLatch = CountDownLatch(2)

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (!acceptFinalImage.get()) {
                image.close()
                return@setOnImageAvailableListener
            }
            if (imageRef.compareAndSet(null, image)) {
                pairLatch.countDown()
            } else {
                image.close()
            }
        }, handler)

        val cameraRef = AtomicReference<CameraDevice?>()
        val sessionRef = AtomicReference<CameraCaptureSession?>()
        val openError = AtomicReference<String?>()
        val openLatch = CountDownLatch(1)

        return try {
            manager.openCamera(lens.openCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraRef.set(camera)
                    openLatch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    openError.set("Camera disconnected during DNG open")
                    openLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    openError.set("Camera DNG open error $error")
                    openLatch.countDown()
                }
            }, handler)

            check(openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out opening DNG camera" }
            openError.get()?.let { error(it) }
            val camera = cameraRef.get() ?: error("DNG camera did not open")
            val characteristics = safeCharacteristics(lens.physicalCameraId ?: lens.cameraId)
                ?: safeCharacteristics(lens.openCameraId)
                ?: error("CameraCharacteristics unavailable for DNG")

            val output = OutputConfiguration(reader.surface).apply {
                if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                    lens.physicalCameraId?.let(::setPhysicalCameraId)
                }
            }
            val sessionError = AtomicReference<String?>()
            val sessionLatch = CountDownLatch(1)
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(output),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        sessionRef.set(session)
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        sessionError.set("RAW_SENSOR DNG session configuration rejected")
                        sessionLatch.countDown()
                    }
                },
            )
            camera.createCaptureSession(config)
            check(sessionLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out configuring DNG session" }
            sessionError.get()?.let { error(it) }
            val session = sessionRef.get() ?: error("DNG session unavailable")

            // New CameraDevice/session state is not guaranteed to inherit the focus/exposure state
            // from the preview session that was just closed. Drive a few disposable RAW requests so
            // AF/AE/AWB can settle before the one frame that becomes the DNG.
            warmThreeA(
                camera = camera,
                session = session,
                rawTarget = reader.surface,
                lens = lens,
                characteristics = characteristics,
                handler = handler,
            )

            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val captureOrientationDegrees = CameraOrientation.sensorToDeviceDegrees(
                sensorOrientation = sensorOrientation,
                isFrontFacing = lens.isFrontFacing,
                surfaceRotationDegrees = surfaceRotationDegrees,
            )

            acceptFinalImage.set(true)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyThreeAControls(this, characteristics, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                runCatching { set(CaptureRequest.JPEG_ORIENTATION, captureOrientationDegrees) }
                runCatching {
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON,
                    )
                }
            }.build()

            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    if (resultRef.get() == null) {
                        resultRef.set(selectPhysicalResult(result, lens.physicalCameraId))
                        pairLatch.countDown()
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    errorRef.compareAndSet(null, IllegalStateException("RAW/DNG capture failed: ${failure.reason}"))
                    pairLatch.countDown()
                }
            }, handler)

            check(pairLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for final RAW image/result pair" }
            errorRef.get()?.let { throw it }
            val image = imageRef.get() ?: error("Final RAW image buffer missing")
            val captureResult = resultRef.get() ?: error("Final CaptureResult metadata missing")
            val resultTimestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: error("CaptureResult.SENSOR_TIMESTAMP missing")
            check(resultTimestamp == image.timestamp) {
                "RAW/DNG metadata mismatch: image=${image.timestamp}, result=$resultTimestamp"
            }

            AuroraDngWriter.write(
                context = appContext,
                lens = lens,
                image = image,
                characteristics = characteristics,
                captureResult = captureResult,
                surfaceRotationDegrees = surfaceRotationDegrees,
            )
        } finally {
            runCatching { sessionRef.get()?.close() }
            runCatching { cameraRef.get()?.close() }
            runCatching { imageRef.getAndSet(null)?.close() }
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(1_000) }
        }
    }

    private fun warmThreeA(
        camera: CameraDevice,
        session: CameraCaptureSession,
        rawTarget: android.view.Surface,
        lens: LensCapability,
        characteristics: CameraCharacteristics,
        handler: Handler,
    ) {
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toSet().orEmpty()
        val hasAutofocus = afModes.any { it != CaptureRequest.CONTROL_AF_MODE_OFF }

        for (attempt in 0 until THREE_A_MAX_FRAMES) {
            val resultRef = AtomicReference<CaptureResult?>()
            val latch = CountDownLatch(1)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawTarget)
                val trigger = if (
                    attempt == 0 &&
                    CaptureRequest.CONTROL_AF_MODE_AUTO in afModes
                ) {
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                } else {
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                }
                applyThreeAControls(this, characteristics, trigger)
            }.build()

            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    resultRef.set(selectPhysicalResult(result, lens.physicalCameraId))
                    latch.countDown()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    latch.countDown()
                }
            }, handler)

            if (!latch.await(THREE_A_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) continue
            val result = resultRef.get() ?: continue
            if (threeAReady(result, hasAutofocus)) return
            Thread.sleep(THREE_A_SETTLE_DELAY_MS)
        }
    }

    private fun applyThreeAControls(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        afTrigger: Int,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toSet().orEmpty()
        when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            CaptureRequest.CONTROL_AF_MODE_AUTO in afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            else -> builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        }
        if (afTrigger != CaptureRequest.CONTROL_AF_TRIGGER_IDLE || CaptureRequest.CONTROL_AF_MODE_AUTO in afModes) {
            runCatching { builder.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger) }
        }
    }

    private fun threeAReady(result: CaptureResult, hasAutofocus: Boolean): Boolean {
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val afReady = !hasAutofocus || afState == null || when (afState) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> true
            else -> false
        }

        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
        val aeReady = aeState == null || when (aeState) {
            CaptureResult.CONTROL_AE_STATE_CONVERGED,
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
            CaptureResult.CONTROL_AE_STATE_LOCKED -> true
            else -> false
        }

        val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
        val awbReady = awbState == null || when (awbState) {
            CaptureResult.CONTROL_AWB_STATE_CONVERGED,
            CaptureResult.CONTROL_AWB_STATE_LOCKED -> true
            else -> false
        }
        return afReady && aeReady && awbReady
    }

    @Suppress("DEPRECATION")
    private fun selectPhysicalResult(result: TotalCaptureResult, physicalId: String?): CaptureResult {
        if (physicalId == null) return result
        return if (Build.VERSION.SDK_INT >= 31) {
            result.physicalCameraTotalResults[physicalId] ?: result
        } else {
            result.physicalCameraResults[physicalId] ?: result
        }
    }

    private fun safeCharacteristics(cameraId: String): CameraCharacteristics? =
        runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()

    private fun chooseRawSize(sizes: List<Size>): Size =
        sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: error("No RAW_SENSOR size")

    private companion object {
        const val OPEN_TIMEOUT_MS = 4_000L
        const val SESSION_TIMEOUT_MS = 4_000L
        const val CAPTURE_TIMEOUT_MS = 8_000L
        const val THREE_A_MAX_FRAMES = 4
        const val THREE_A_FRAME_TIMEOUT_MS = 1_500L
        const val THREE_A_SETTLE_DELAY_MS = 80L
    }
}
