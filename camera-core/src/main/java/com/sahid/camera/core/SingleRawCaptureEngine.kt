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
 * The dedicated RAW session now performs a real autofocus lock before the final exposure and keeps
 * that exact focus position through the capture. This avoids the previous failure mode where the
 * warm-up request used continuous AF and the final still request could start another lens scan just
 * as the RAW frame was exposed.
 *
 * Tap-to-focus is represented as a normalized [FocusMeteringPoint]. The point is applied both to
 * the live preview session and again here after the preview CameraDevice has been released, so a
 * selected auxiliary/physical lens focuses the same subject for the canonical RAW exposure.
 */
class SingleRawCaptureEngine(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)

    fun capture(
        lens: LensCapability,
        focusPoint: FocusMeteringPoint? = null,
    ): RawCaptureOutcome {
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
            RawCaptureOutcome.Success(captureJavaDng(lens, surfaceRotationDegrees, focusPoint?.clamped()))
        } catch (t: Throwable) {
            RawCaptureOutcome.Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private fun captureJavaDng(
        lens: LensCapability,
        surfaceRotationDegrees: Int,
        focusPoint: FocusMeteringPoint?,
    ): RawCaptureRecord {
        val rawSize = chooseRawSize(lens.rawSizes)
        val thread = HandlerThread("AuroraDngCapture").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { runnable -> handler.post(runnable) }
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3)

        val acceptFinalImage = AtomicBoolean(false)
        val warmDrainLatchRef = AtomicReference<CountDownLatch?>()
        val imageRef = AtomicReference<Image?>()
        val resultRef = AtomicReference<CaptureResult?>()
        val errorRef = AtomicReference<Throwable?>()
        val pairLatch = CountDownLatch(2)

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (!acceptFinalImage.get()) {
                image.close()
                warmDrainLatchRef.getAndSet(null)?.countDown()
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

            val threeA = warmThreeA(
                camera = camera,
                session = session,
                rawTarget = reader.surface,
                lens = lens,
                characteristics = characteristics,
                handler = handler,
                warmDrainLatchRef = warmDrainLatchRef,
                focusPoint = focusPoint,
            )

            // Do not knowingly persist a defocused RAW. Fixed-focus lenses bypass this gate, and
            // OEMs that omit AF state are accepted, but an explicit scanning/failed state must lock.
            if (threeA.autofocusSupported && !threeA.autofocusLocked) {
                error("Autofocus did not lock; tap the subject and try again")
            }

            // No discarded warm-up image is allowed to remain queued when the final gate opens.
            warmDrainLatchRef.getAndSet(null)?.await(THREE_A_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val captureOrientationDegrees = CameraOrientation.sensorToDeviceDegrees(
                sensorOrientation = sensorOrientation,
                isFrontFacing = lens.isFrontFacing,
                surfaceRotationDegrees = surfaceRotationDegrees,
            )

            acceptFinalImage.set(true)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyFinalCaptureControls(
                    builder = this,
                    characteristics = characteristics,
                    lens = lens,
                    focusPoint = focusPoint,
                    lockedFocusDistance = threeA.lockedFocusDistance,
                )
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
        warmDrainLatchRef: AtomicReference<CountDownLatch?>,
        focusPoint: FocusMeteringPoint?,
    ): ThreeAState {
        val autofocusSupported = FocusMetering.supportsAutofocus(characteristics)
        var latestResult: CaptureResult? = null

        for (attempt in 0 until THREE_A_MAX_FRAMES) {
            val resultRef = AtomicReference<CaptureResult?>()
            val resultLatch = CountDownLatch(1)
            val imageDrainLatch = CountDownLatch(1)
            warmDrainLatchRef.set(imageDrainLatch)

            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawTarget)
                val trigger = if (autofocusSupported && (attempt == 0 || attempt == THREE_A_RETRIGGER_FRAME)) {
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                } else {
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                }
                applyThreeAControls(
                    builder = this,
                    characteristics = characteristics,
                    lens = lens,
                    afTrigger = trigger,
                    focusPoint = focusPoint,
                )
            }.build()

            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    resultRef.set(selectPhysicalResult(result, lens.physicalCameraId))
                    resultLatch.countDown()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    resultLatch.countDown()
                }
            }, handler)

            val resultArrived = resultLatch.await(THREE_A_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            imageDrainLatch.await(THREE_A_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            warmDrainLatchRef.compareAndSet(imageDrainLatch, null)
            if (!resultArrived) continue

            val result = resultRef.get() ?: continue
            latestResult = result
            if (threeAReady(result, autofocusSupported)) {
                return ThreeAState(
                    autofocusSupported = autofocusSupported,
                    autofocusLocked = autofocusReady(result, autofocusSupported),
                    lockedFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                )
            }
            Thread.sleep(THREE_A_SETTLE_DELAY_MS)
        }

        return ThreeAState(
            autofocusSupported = autofocusSupported,
            autofocusLocked = autofocusReady(latestResult, autofocusSupported),
            lockedFocusDistance = latestResult?.get(CaptureResult.LENS_FOCUS_DISTANCE),
        )
    }

    private fun applyThreeAControls(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        lens: LensCapability,
        afTrigger: Int,
        focusPoint: FocusMeteringPoint?,
    ) {
        val physicalId = lens.physicalCameraId
        FocusMetering.set(builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO, physicalId)
        FocusMetering.set(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON, physicalId)
        FocusMetering.set(builder, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO, physicalId)

        val afMode = if (FocusMetering.supportsAutofocus(characteristics)) {
            FocusMetering.lockAfMode(characteristics)
        } else {
            CaptureRequest.CONTROL_AF_MODE_OFF
        }
        FocusMetering.set(builder, CaptureRequest.CONTROL_AF_MODE, afMode, physicalId)
        FocusMetering.applyRegions(builder, characteristics, physicalId, focusPoint)
        if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
            FocusMetering.set(builder, CaptureRequest.CONTROL_AF_TRIGGER, afTrigger, physicalId)
        }
        applyOpticalStabilization(builder, characteristics, physicalId)
    }

    private fun applyFinalCaptureControls(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        lens: LensCapability,
        focusPoint: FocusMeteringPoint?,
        lockedFocusDistance: Float?,
    ) {
        val physicalId = lens.physicalCameraId
        FocusMetering.set(builder, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO, physicalId)
        FocusMetering.set(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON, physicalId)
        FocusMetering.set(builder, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO, physicalId)
        FocusMetering.applyRegions(builder, characteristics, physicalId, focusPoint)
        applyOpticalStabilization(builder, characteristics, physicalId)

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val canFreezeDistance = lockedFocusDistance != null && CaptureRequest.CONTROL_AF_MODE_OFF in afModes
        if (canFreezeDistance) {
            // Freeze the exact distance reported by the successful AF lock. This is still sensor/lens
            // control only; no sharpening or rendered-image processing is applied to the RAW buffer.
            FocusMetering.set(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF, physicalId)
            FocusMetering.set(builder, CaptureRequest.LENS_FOCUS_DISTANCE, lockedFocusDistance!!, physicalId)
        } else {
            val afMode = if (FocusMetering.supportsAutofocus(characteristics)) {
                FocusMetering.lockAfMode(characteristics)
            } else {
                CaptureRequest.CONTROL_AF_MODE_OFF
            }
            FocusMetering.set(builder, CaptureRequest.CONTROL_AF_MODE, afMode, physicalId)
            if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) {
                // IDLE preserves the existing AUTO lock; unlike the previous continuous-picture
                // final request it does not deliberately start a fresh focus scan at exposure time.
                FocusMetering.set(
                    builder,
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE,
                    physicalId,
                )
            }
        }
    }

    private fun applyOpticalStabilization(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        physicalCameraId: String?,
    ) {
        val modes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toSet().orEmpty()
        if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in modes) {
            FocusMetering.set(
                builder,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                physicalCameraId,
            )
        }
    }

    private fun threeAReady(result: CaptureResult, autofocusSupported: Boolean): Boolean {
        val afReady = autofocusReady(result, autofocusSupported)

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

    private fun autofocusReady(result: CaptureResult?, autofocusSupported: Boolean): Boolean {
        if (!autofocusSupported) return true
        val afState = result?.get(CaptureResult.CONTROL_AF_STATE) ?: return true
        return when (afState) {
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> true
            else -> false
        }
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

    private data class ThreeAState(
        val autofocusSupported: Boolean,
        val autofocusLocked: Boolean,
        val lockedFocusDistance: Float?,
    )

    private companion object {
        const val OPEN_TIMEOUT_MS = 4_000L
        const val SESSION_TIMEOUT_MS = 4_000L
        const val CAPTURE_TIMEOUT_MS = 8_000L
        const val THREE_A_MAX_FRAMES = 7
        const val THREE_A_RETRIGGER_FRAME = 4
        const val THREE_A_FRAME_TIMEOUT_MS = 1_500L
        const val THREE_A_DRAIN_TIMEOUT_MS = 1_500L
        const val THREE_A_SETTLE_DELAY_MS = 90L
    }
}
