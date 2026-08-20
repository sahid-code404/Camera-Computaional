package com.sahid.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Rational
import android.util.Size
import androidx.core.content.ContextCompat
import com.sahid.camera.aurora.NativeCameraSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 02A: one immutable RAW_SENSOR frame with timestamp-matched capture metadata.
 *
 * This intentionally uses a dedicated RAW-only capture session. The live preview is released by
 * the UI before capture and restarted afterwards. That gives us a small, auditable RAW truth path
 * before Phase 02B adds simultaneous preview+RAW multi-output sessions.
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

        return try {
            val pair = when (lens.accessPath) {
                CameraAccessPath.JAVA_DIRECT,
                CameraAccessPath.PHYSICAL_VIA_LOGICAL -> captureJava(lens)
                CameraAccessPath.NDK_DIRECT -> captureNative(lens)
            }
            val record = AuroraRawWriter.write(
                context = appContext,
                lens = lens,
                packet = pair.packet,
                captureApi = pair.captureApi,
                staticMetadata = pair.staticMetadata,
                dynamicMetadata = pair.dynamicMetadata,
            )
            RawCaptureOutcome.Success(record)
        } catch (t: Throwable) {
            RawCaptureOutcome.Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }

    private fun captureJava(lens: LensCapability): CapturePair {
        val rawSize = chooseRawSize(lens.rawSizes)
        val thread = HandlerThread("AuroraRawJava").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { runnable -> handler.post(runnable) }
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        val imageRef = AtomicReference<RawImagePacket?>()
        val resultRef = AtomicReference<CaptureResult?>()
        val errorRef = AtomicReference<Throwable?>()
        val pairLatch = CountDownLatch(2)

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (imageRef.get() == null) {
                    imageRef.set(RawImagePacket.copyFrom(image))
                    pairLatch.countDown()
                }
            } catch (t: Throwable) {
                errorRef.compareAndSet(null, t)
                pairLatch.countDown()
            } finally {
                image.close()
            }
        }, handler)

        val cameraRef = AtomicReference<CameraDevice?>()
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
                    openError.set("Camera disconnected during RAW open")
                    openLatch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    openError.set("Camera RAW open error $error")
                    openLatch.countDown()
                }
            }, handler)

            check(openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out opening RAW camera" }
            openError.get()?.let { error(it) }
            val camera = cameraRef.get() ?: error("RAW camera did not open")

            val output = OutputConfiguration(reader.surface).apply {
                if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                    lens.physicalCameraId?.let(::setPhysicalCameraId)
                }
            }
            val sessionRef = AtomicReference<CameraCaptureSession?>()
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
                        sessionError.set("RAW_SENSOR session configuration rejected")
                        sessionLatch.countDown()
                    }
                },
            )
            camera.createCaptureSession(config)
            check(sessionLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out configuring RAW session" }
            sessionError.get()?.let { error(it) }
            val session = sessionRef.get() ?: error("RAW session unavailable")

            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
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
                    errorRef.compareAndSet(null, IllegalStateException("RAW capture failed: ${failure.reason}"))
                    pairLatch.countDown()
                }
            }, handler)

            check(pairLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for RAW image/result pair" }
            errorRef.get()?.let { throw it }
            val packet = imageRef.get() ?: error("RAW image buffer missing")
            val captureResult = resultRef.get() ?: error("CaptureResult metadata missing")
            val resultTimestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP)
                ?: error("CaptureResult.SENSOR_TIMESTAMP missing")
            check(resultTimestamp == packet.timestampNs) {
                "RAW metadata mismatch: image=${packet.timestampNs}, result=$resultTimestamp"
            }

            val characteristics = safeCharacteristics(lens.physicalCameraId ?: lens.cameraId)
                ?: safeCharacteristics(lens.openCameraId)
            CapturePair(
                packet = packet,
                captureApi = if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                    "CAMERA2_PHYSICAL_VIA_LOGICAL"
                } else {
                    "CAMERA2_JAVA_DIRECT"
                },
                staticMetadata = characteristics?.let(::staticMetadataJson) ?: staticMetadataFromLens(lens),
                dynamicMetadata = captureMetadataJson(captureResult),
            )
        } finally {
            runCatching { cameraRef.get()?.close() }
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(1_000) }
        }
    }

    private fun captureNative(lens: LensCapability): CapturePair {
        val rawSize = chooseRawSize(lens.rawSizes)
        val thread = HandlerThread("AuroraRawNdk").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        val imageRef = AtomicReference<RawImagePacket?>()
        val imageLatch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()

        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                if (imageRef.get() == null) imageRef.set(RawImagePacket.copyFrom(image))
            } catch (t: Throwable) {
                errorRef.compareAndSet(null, t)
            } finally {
                image.close()
                imageLatch.countDown()
            }
        }, handler)

        var handle = 0L
        try {
            val start = NativeCameraSession.startSingleCapture(lens.cameraId, reader.surface)
            check(start.started) { "NDK RAW failed at ${start.stageLabel}: ${start.status}" }
            handle = start.handle
            check(imageLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for NDK RAW image" }
            errorRef.get()?.let { throw it }
            val packet = imageRef.get() ?: error("NDK RAW image buffer missing")

            val metadata = waitForNativeCaptureMetadata(handle)
            check(metadata.optBoolean("complete", false)) { "NDK CaptureResult metadata did not complete" }
            val timestamp = metadata.optLong("sensorTimestampNs", Long.MIN_VALUE)
            check(timestamp != Long.MIN_VALUE) { "NDK SENSOR_TIMESTAMP missing" }
            check(timestamp == packet.timestampNs) {
                "NDK RAW metadata mismatch: image=${packet.timestampNs}, result=$timestamp"
            }

            return CapturePair(
                packet = packet,
                captureApi = "CAMERA2_NDK_DIRECT",
                staticMetadata = safeCharacteristics(lens.cameraId)?.let(::staticMetadataJson)
                    ?: staticMetadataFromLens(lens),
                dynamicMetadata = metadata,
            )
        } finally {
            if (handle != 0L) runCatching { NativeCameraSession.stop(handle) }
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            thread.quitSafely()
            runCatching { thread.join(1_000) }
        }
    }

    private fun waitForNativeCaptureMetadata(handle: Long): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(METADATA_TIMEOUT_MS)
        var latest = JSONObject()
        while (System.nanoTime() < deadline) {
            val raw = NativeCameraSession.captureMetadataJson(handle)
            if (!raw.isNullOrBlank()) {
                latest = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                if (latest.optBoolean("complete", false) || latest.optBoolean("failed", false)) return latest
            }
            Thread.sleep(10)
        }
        return latest
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

    private fun staticMetadataFromLens(lens: LensCapability): JSONObject = JSONObject()
        .put("metadataSource", "LENS_CAPABILITY_FALLBACK")
        .put("facing", lens.facing ?: JSONObject.NULL)
        .put("focalLengthMm", lens.focalLengthMm ?: JSONObject.NULL)
        .put("sensorWidthMm", lens.sensorWidthMm ?: JSONObject.NULL)
        .put("sensorHeightMm", lens.sensorHeightMm ?: JSONObject.NULL)
        .put("rawSizes", sizeArray(lens.rawSizes))

    private fun staticMetadataJson(chars: CameraCharacteristics): JSONObject = JSONObject().apply {
        put("metadataSource", "CAMERA_CHARACTERISTICS")
        put("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
        put("facing", chars.get(CameraCharacteristics.LENS_FACING) ?: JSONObject.NULL)
        put("pixelArraySize", chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let(::sizeJson) ?: JSONObject.NULL)
        put("activeArray", chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let(::rectJson) ?: JSONObject.NULL)
        put("preCorrectionActiveArray", chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.let(::rectJson) ?: JSONObject.NULL)
        put("physicalSize", chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
            JSONObject().put("widthMm", it.width).put("heightMm", it.height)
        } ?: JSONObject.NULL)
        put("whiteLevel", chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: JSONObject.NULL)
        put("colorFilterArrangement", chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: JSONObject.NULL)
        put("maxAnalogSensitivity", chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY) ?: JSONObject.NULL)
        put("timestampSource", chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ?: JSONObject.NULL)
        put("availableFocalLengths", floatArrayJson(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
        put("availableApertures", floatArrayJson(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)))
        put("referenceIlluminant1", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: JSONObject.NULL)
        put("referenceIlluminant2", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: JSONObject.NULL)
        put("blackLevelPattern", chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { pattern ->
            JSONArray().apply {
                for (y in 0..1) for (x in 0..1) put(pattern.getOffsetForIndex(x, y))
            }
        } ?: JSONObject.NULL)
        put("colorTransform1", chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let(::colorTransformJson) ?: JSONObject.NULL)
        put("colorTransform2", chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let(::colorTransformJson) ?: JSONObject.NULL)
        put("calibrationTransform1", chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let(::colorTransformJson) ?: JSONObject.NULL)
        put("calibrationTransform2", chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let(::colorTransformJson) ?: JSONObject.NULL)
        put("forwardMatrix1", chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let(::colorTransformJson) ?: JSONObject.NULL)
        put("forwardMatrix2", chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let(::colorTransformJson) ?: JSONObject.NULL)
    }

    private fun captureMetadataJson(result: CaptureResult): JSONObject = JSONObject().apply {
        put("complete", true)
        put("sensorTimestampNs", result.get(CaptureResult.SENSOR_TIMESTAMP) ?: JSONObject.NULL)
        put("exposureTimeNs", result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: JSONObject.NULL)
        put("sensitivityIso", result.get(CaptureResult.SENSOR_SENSITIVITY) ?: JSONObject.NULL)
        put("frameDurationNs", result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: JSONObject.NULL)
        put("rollingShutterSkewNs", result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: JSONObject.NULL)
        put("focusDistanceDiopters", result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: JSONObject.NULL)
        put("aperture", result.get(CaptureResult.LENS_APERTURE) ?: JSONObject.NULL)
        put("focalLengthMm", result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: JSONObject.NULL)
        put("aeState", result.get(CaptureResult.CONTROL_AE_STATE) ?: JSONObject.NULL)
        put("afState", result.get(CaptureResult.CONTROL_AF_STATE) ?: JSONObject.NULL)
        put("awbState", result.get(CaptureResult.CONTROL_AWB_STATE) ?: JSONObject.NULL)
        put("dynamicWhiteLevel", result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: JSONObject.NULL)
        put("dynamicBlackLevel", floatArrayJson(result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)))
        put("neutralColorPoint", result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.let { values ->
            JSONArray().apply { values.forEach { put(rationalJson(it)) } }
        } ?: JSONObject.NULL)
        put("noiseProfile", result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { values ->
            JSONArray().apply {
                values.forEach { pair -> put(JSONArray().put(pair.first).put(pair.second)) }
            }
        } ?: JSONObject.NULL)
        put("colorCorrectionGains", result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let(::gainsJson) ?: JSONObject.NULL)
        put("colorCorrectionTransform", result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let(::colorTransformJson) ?: JSONObject.NULL)
    }

    private fun gainsJson(gains: RggbChannelVector): JSONObject = JSONObject()
        .put("red", gains.red)
        .put("greenEven", gains.greenEven)
        .put("greenOdd", gains.greenOdd)
        .put("blue", gains.blue)

    private fun colorTransformJson(transform: ColorSpaceTransform): JSONArray = JSONArray().apply {
        for (row in 0..2) {
            put(JSONArray().apply {
                for (column in 0..2) put(rationalJson(transform.getElement(column, row)))
            })
        }
    }

    private fun rationalJson(value: Rational): JSONObject = JSONObject()
        .put("numerator", value.numerator)
        .put("denominator", value.denominator)

    private fun sizeJson(size: Size): JSONObject = JSONObject()
        .put("width", size.width)
        .put("height", size.height)

    private fun sizeArray(values: List<Size>): JSONArray = JSONArray().apply {
        values.forEach { put(sizeJson(it)) }
    }

    private fun rectJson(rect: Rect): JSONObject = JSONObject()
        .put("left", rect.left)
        .put("top", rect.top)
        .put("right", rect.right)
        .put("bottom", rect.bottom)

    private fun floatArrayJson(values: FloatArray?): Any =
        values?.let { JSONArray().apply { it.forEach(::put) } } ?: JSONObject.NULL

    private data class CapturePair(
        val packet: RawImagePacket,
        val captureApi: String,
        val staticMetadata: JSONObject,
        val dynamicMetadata: JSONObject,
    )

    private companion object {
        const val OPEN_TIMEOUT_MS = 4_000L
        const val SESSION_TIMEOUT_MS = 4_000L
        const val CAPTURE_TIMEOUT_MS = 7_000L
        const val METADATA_TIMEOUT_MS = 2_000L
    }
}
