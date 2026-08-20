package com.sahid.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import com.sahid.camera.aurora.NativeCameraSession
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max

/**
 * Live preview dispatcher optimized for instant cached startup.
 *
 * JAVA_DIRECT and PHYSICAL_VIA_LOGICAL use Java Camera2. NDK_DIRECT stays native all the way
 * through ACameraManager/ACameraDevice/ACameraCaptureSession. A learned Java route that becomes
 * stale is removed immediately and, for a direct camera ID, retried through NDK without forcing a
 * complete rediscovery pass. Routes are written to the learned cache only after a live frame is
 * observed by TextureView or ImageReader.
 */
class CameraPreviewController(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val learnedStore = LearnedLensStore(context)
    private val cameraThread = HandlerThread("CameraPreview").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val executor = Executor { runnable -> handler.post(runnable) }

    private var textureView: TextureView? = null
    private var selectedLens: LensCapability? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeSurface: Surface? = null
    private var yuvReader: ImageReader? = null
    private var nativeSessionHandle: Long = 0L
    private var started = false
    private var generation = 0L
    private var lastYuvRenderNs = 0L
    private var pendingSurfaceProof: LensCapability? = null
    private var lastYuvProofStableId: String? = null
    private var nativeFallbackAttemptedForCameraId: String? = null

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openIfReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            selectedLens?.let { configureTransform(it, width, height) }
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            confirmSurfaceFrame()
        }
    }

    fun attach(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = surfaceListener
        if (view.isAvailable) openIfReady()
    }

    fun setLens(lens: LensCapability?) {
        if (selectedLens?.stableId == lens?.stableId) return
        selectedLens = lens
        nativeFallbackAttemptedForCameraId = null
        restartCamera()
    }

    fun start() {
        started = true
        openIfReady()
    }

    fun stop() {
        started = false
        closeCamera()
    }

    fun release() {
        stop()
        textureView?.surfaceTextureListener = null
        textureView = null
        cameraThread.quitSafely()
    }

    private fun restartCamera() {
        generation += 1
        closeCamera()
        openIfReady()
    }

    private fun openIfReady() {
        val view = textureView ?: return
        val lens = selectedLens ?: return
        if (!started || !view.isAvailable || hasActiveRoute()) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Camera permission required")
            return
        }

        if (lens.accessPath == CameraAccessPath.NDK_DIRECT) {
            val openGeneration = generation
            handler.post {
                if (!started || generation != openGeneration || hasActiveRoute()) return@post
                if (lens.qualification.previewSessionQualified) {
                    startNativeSurfacePreview(lens)
                } else if (lens.qualification.yuvSessionQualified) {
                    startNativeYuvPreview(lens)
                } else {
                    onStatus("${lens.displayName}: no renderable NDK preview path")
                }
            }
            return
        }

        val openGeneration = generation
        onStatus("Opening ${lens.displayName}")

        try {
            // Handler overload is intentionally used instead of the API-28 Executor overload.
            // A number of vendor Camera2 implementations are more reliable on this original path.
            manager.openCamera(lens.openCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (openGeneration != generation || !started) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    if (lens.qualification.previewSessionQualified) {
                        createSurfacePreviewSession(camera, lens)
                    } else if (lens.qualification.yuvSessionQualified) {
                        createJavaYuvPreviewSession(camera, lens)
                    } else {
                        camera.close()
                        cameraDevice = null
                        handleJavaRouteFailure(lens, "No renderable Java preview path")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (openGeneration == generation && started) {
                        handleJavaRouteFailure(lens, "Camera disconnected")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (openGeneration == generation && started) {
                        handleJavaRouteFailure(lens, "Camera error $error")
                    }
                }
            }, handler)
        } catch (security: SecurityException) {
            onStatus("Camera permission denied")
        } catch (access: CameraAccessException) {
            handleJavaRouteFailure(lens, "Camera unavailable: ${access.reason}")
        } catch (illegal: IllegalArgumentException) {
            handleJavaRouteFailure(lens, "Java route rejected")
        } catch (t: Throwable) {
            handleJavaRouteFailure(lens, "Camera open failed: ${t.javaClass.simpleName}")
        }
    }

    private fun handleJavaRouteFailure(lens: LensCapability, reason: String) {
        if (lens.learnedFromCache) {
            learnedStore.removeRoute(lens.stableId)
        }
        if (
            lens.accessPath == CameraAccessPath.JAVA_DIRECT &&
            nativeFallbackAttemptedForCameraId != lens.cameraId &&
            started &&
            selectedLens?.cameraId == lens.cameraId
        ) {
            nativeFallbackAttemptedForCameraId = lens.cameraId
            val fallback = lens.copy(
                logicalCameraId = lens.cameraId,
                physicalCameraId = null,
                accessPath = CameraAccessPath.NDK_DIRECT,
                discoverySources = (lens.discoverySources - CameraDiscoverySource.LEARNED_CACHE) +
                    CameraDiscoverySource.NDK_DIRECT,
                qualification = LensQualification(
                    accessPathOpenQualified = false,
                    previewSessionQualified = lens.previewSizes.isNotEmpty(),
                    yuvSessionQualified = lens.previewSizes.isEmpty() && lens.yuvSizes.isNotEmpty(),
                    rawSessionQualified = false,
                    qualifiedRawSize = null,
                    detail = "Live NDK fallback after Java route failure",
                ),
            )
            onStatus("$reason • trying NDK")
            handler.post {
                if (!started || selectedLens?.cameraId != lens.cameraId || hasActiveRoute()) return@post
                when {
                    fallback.qualification.previewSessionQualified -> startNativeSurfacePreview(fallback)
                    fallback.qualification.yuvSessionQualified -> startNativeYuvPreview(fallback)
                    else -> onStatus("$reason • no NDK preview surface")
                }
            }
        } else {
            onStatus(reason)
        }
    }

    private fun startNativeSurfacePreview(lens: LensCapability) {
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return
        val previewSize = choosePreviewSize(lens.previewSizes, view.width, view.height)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(lens, view.width, view.height, previewSize)

        val surface = Surface(texture)
        val start = NativeCameraSession.startPreview(lens.cameraId, surface)
        if (!start.started) {
            surface.release()
            if (lens.yuvSizes.isNotEmpty()) {
                val yuvFallback = lens.copy(
                    qualification = lens.qualification.copy(
                        previewSessionQualified = false,
                        yuvSessionQualified = true,
                        detail = "NDK Surface failed; trying YUV",
                    )
                )
                startNativeYuvPreview(yuvFallback)
            } else {
                onStatus("NDK preview failed at ${start.stageLabel}: ${start.status}")
            }
            return
        }
        activeSurface = surface
        nativeSessionHandle = start.handle
        pendingSurfaceProof = provenSurfaceRoute(lens)
        onStatus("${lens.displayName} • NDK preview ${previewSize.width}×${previewSize.height}")
    }

    private fun startNativeYuvPreview(lens: LensCapability) {
        val view = textureView ?: return
        val size = chooseYuvPreviewSize(lens.yuvSizes)
            ?: run {
                onStatus("${lens.displayName}: YUV size unavailable")
                return
            }
        configureTransform(lens, view.width, view.height, size)
        val reader = createYuvReader(size, lens) ?: return
        val start = NativeCameraSession.startPreview(lens.cameraId, reader.surface)
        if (!start.started) {
            reader.close()
            onStatus("NDK YUV preview failed at ${start.stageLabel}: ${start.status}")
            return
        }
        yuvReader = reader
        nativeSessionHandle = start.handle
        onStatus("${lens.displayName} • NDK YUV ${size.width}×${size.height}")
    }

    private fun createSurfacePreviewSession(camera: CameraDevice, lens: LensCapability) {
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return
        val previewSize = choosePreviewSize(lens.previewSizes, view.width, view.height)

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(lens, view.width, view.height, previewSize)

        val surface = Surface(texture)
        activeSurface = surface
        val output = physicalOutput(surface, lens)

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice !== camera || !started) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            applyAutoControls(this, lens)
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        pendingSurfaceProof = provenSurfaceRoute(lens)
                        onStatus("${lens.displayName} • Camera2 preview ${previewSize.width}×${previewSize.height}")
                    } catch (t: Throwable) {
                        releaseJavaRoute(camera, surface)
                        handleJavaRouteFailure(lens, "Preview request failed: ${t.javaClass.simpleName}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (captureSession === session) captureSession = null
                    releaseJavaRoute(camera, surface)
                    handleJavaRouteFailure(lens, "Preview session unsupported")
                }
            },
        )

        try {
            camera.createCaptureSession(config)
        } catch (t: Throwable) {
            releaseJavaRoute(camera, surface)
            handleJavaRouteFailure(lens, "Session creation failed: ${t.javaClass.simpleName}")
        }
    }

    private fun createJavaYuvPreviewSession(camera: CameraDevice, lens: LensCapability) {
        val view = textureView ?: return
        val size = chooseYuvPreviewSize(lens.yuvSizes)
            ?: run {
                onStatus("${lens.displayName}: YUV size unavailable")
                return
            }
        configureTransform(lens, view.width, view.height, size)
        val reader = createYuvReader(size, lens) ?: return
        yuvReader = reader
        val output = physicalOutput(reader.surface, lens)

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice !== camera || !started) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            applyAutoControls(this, lens)
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        onStatus("${lens.displayName} • YUV preview ${size.width}×${size.height}")
                    } catch (t: Throwable) {
                        releaseJavaYuvRoute(camera, reader)
                        handleJavaRouteFailure(lens, "YUV request failed: ${t.javaClass.simpleName}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (captureSession === session) captureSession = null
                    releaseJavaYuvRoute(camera, reader)
                    handleJavaRouteFailure(lens, "YUV preview unsupported")
                }
            },
        )

        try {
            camera.createCaptureSession(config)
        } catch (t: Throwable) {
            releaseJavaYuvRoute(camera, reader)
            handleJavaRouteFailure(lens, "YUV session failed: ${t.javaClass.simpleName}")
        }
    }

    private fun releaseJavaRoute(camera: CameraDevice, surface: Surface) {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { camera.close() }
        if (cameraDevice === camera) cameraDevice = null
        runCatching { surface.release() }
        if (activeSurface === surface) activeSurface = null
        pendingSurfaceProof = null
    }

    private fun releaseJavaYuvRoute(camera: CameraDevice, reader: ImageReader) {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { camera.close() }
        if (cameraDevice === camera) cameraDevice = null
        runCatching { reader.setOnImageAvailableListener(null, null) }
        runCatching { reader.close() }
        if (yuvReader === reader) yuvReader = null
    }

    private fun createYuvReader(size: Size, lens: LensCapability): ImageReader? {
        val reader = runCatching {
            ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
        }.getOrElse {
            onStatus("YUV reader failed: ${it.javaClass.simpleName}")
            return null
        }
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                confirmYuvFrame(lens)
                renderYuvFrame(image, lens)
            } finally {
                image.close()
            }
        }, handler)
        return reader
    }

    private fun confirmSurfaceFrame() {
        val route = pendingSurfaceProof ?: return
        pendingSurfaceProof = null
        learnedStore.upsertProvenRoute(route)
    }

    private fun confirmYuvFrame(lens: LensCapability) {
        if (lastYuvProofStableId == lens.stableId) return
        lastYuvProofStableId = lens.stableId
        learnedStore.upsertProvenRoute(
            lens.copy(
                qualification = LensQualification(
                    accessPathOpenQualified = true,
                    previewSessionQualified = false,
                    yuvSessionQualified = true,
                    rawSessionQualified = false,
                    qualifiedRawSize = null,
                    detail = "Live YUV frame received",
                )
            )
        )
    }

    private fun provenSurfaceRoute(lens: LensCapability): LensCapability = lens.copy(
        qualification = LensQualification(
            accessPathOpenQualified = true,
            previewSessionQualified = true,
            yuvSessionQualified = false,
            rawSessionQualified = false,
            qualifiedRawSize = null,
            detail = "Live TextureView frame received",
        )
    )

    private fun renderYuvFrame(image: Image, lens: LensCapability) {
        val now = System.nanoTime()
        if (now - lastYuvRenderNs < YUV_RENDER_INTERVAL_NS) return
        lastYuvRenderNs = now
        val view = textureView ?: return
        if (!view.isAvailable || image.planes.size < 3) return

        val maxDimension = max(image.width, image.height)
        val sample = max(1, (maxDimension + YUV_RENDER_MAX_DIMENSION - 1) / YUV_RENDER_MAX_DIMENSION)
        val outWidth = max(1, image.width / sample)
        val outHeight = max(1, image.height / sample)
        val pixels = IntArray(outWidth * outHeight)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        var outIndex = 0
        for (outY in 0 until outHeight) {
            val sourceY = minOf(image.height - 1, outY * sample)
            val chromaY = sourceY / 2
            for (outX in 0 until outWidth) {
                val sourceX = minOf(image.width - 1, outX * sample)
                val chromaX = sourceX / 2
                val yIndex = sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride
                val uIndex = chromaY * uPlane.rowStride + chromaX * uPlane.pixelStride
                val vIndex = chromaY * vPlane.rowStride + chromaX * vPlane.pixelStride
                val yValue = yBuffer.get(yIndex).toInt() and 0xff
                val uValue = (uBuffer.get(uIndex).toInt() and 0xff) - 128
                val vValue = (vBuffer.get(vIndex).toInt() and 0xff) - 128

                val c = max(0, yValue - 16)
                val r = clamp8((298 * c + 409 * vValue + 128) shr 8)
                val g = clamp8((298 * c - 100 * uValue - 208 * vValue + 128) shr 8)
                val b = clamp8((298 * c + 516 * uValue + 128) shr 8)
                pixels[outIndex++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = runCatching { view.lockCanvas() }.getOrNull()
        if (canvas != null) {
            try {
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1)),
                    null,
                )
            } finally {
                view.unlockCanvasAndPost(canvas)
            }
        }
        bitmap.recycle()
        configureTransform(lens, view.width, view.height, Size(outWidth, outHeight))
    }

    private fun clamp8(value: Int): Int = value.coerceIn(0, 255)

    private fun applyAutoControls(
        builder: CaptureRequest.Builder,
        lens: LensCapability,
    ) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        val afModes = runCatching {
            manager.getCameraCharacteristics(lens.physicalCameraId ?: lens.openCameraId)
                .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.toSet()
        }.getOrNull().orEmpty()
        when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            CaptureRequest.CONTROL_AF_MODE_AUTO in afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        }
    }

    private fun physicalOutput(surface: Surface, lens: LensCapability): OutputConfiguration =
        OutputConfiguration(surface).apply {
            if (lens.accessPath == CameraAccessPath.PHYSICAL_VIA_LOGICAL) {
                lens.physicalCameraId?.let { physicalId ->
                    runCatching { setPhysicalCameraId(physicalId) }
                        .onFailure { onStatus("Physical route failed: ${it.javaClass.simpleName}") }
                }
            }
        }

    private fun choosePreviewSize(sizes: List<Size>, viewWidth: Int, viewHeight: Int): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val targetRatio = if (viewWidth > 0 && viewHeight > 0) {
            max(viewWidth, viewHeight).toDouble() / max(1, minOf(viewWidth, viewHeight)).toDouble()
        } else {
            16.0 / 9.0
        }

        val bounded = sizes.filter { size ->
            max(size.width, size.height) <= 1920 && minOf(size.width, size.height) <= 1080
        }.ifEmpty { sizes }

        return bounded.minWithOrNull(
            compareBy<Size> { size ->
                abs(size.width.toDouble() / size.height.toDouble() - targetRatio)
            }.thenByDescending { size -> size.width.toLong() * size.height.toLong() }
        ) ?: sizes.first()
    }

    private fun chooseYuvPreviewSize(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        val bounded = sizes.filter {
            max(it.width, it.height) <= 1280 && minOf(it.width, it.height) <= 720
        }
        return (bounded.ifEmpty { sizes }).maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun configureTransform(
        lens: LensCapability,
        viewWidth: Int,
        viewHeight: Int,
        previewSize: Size? = null,
    ) {
        val view = textureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val sourceSizes = if (lens.qualification.previewSessionQualified) lens.previewSizes else lens.yuvSizes
        val size = previewSize ?: choosePreviewSize(sourceSizes, viewWidth, viewHeight)
        val chars = runCatching {
            manager.getCameraCharacteristics(lens.physicalCameraId ?: lens.openCameraId)
        }.getOrNull()
        val sensorOrientation = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        val relativeRotation = if (lens.isFrontFacing) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }

        val rotated = relativeRotation == 90 || relativeRotation == 270
        val bufferWidth = if (rotated) size.height.toFloat() else size.width.toFloat()
        val bufferHeight = if (rotated) size.width.toFloat() else size.height.toFloat()

        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, bufferWidth, bufferHeight)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

        val matrix = Matrix()
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(
            viewHeight.toFloat() / bufferHeight,
            viewWidth.toFloat() / bufferWidth,
        )
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(relativeRotation.toFloat(), centerX, centerY)
        if (lens.isFrontFacing) {
            matrix.postScale(-1f, 1f, centerX, centerY)
        }
        view.setTransform(matrix)
    }

    private fun hasActiveRoute(): Boolean =
        cameraDevice != null || nativeSessionHandle != 0L || yuvReader != null

    private fun closeCamera() {
        pendingSurfaceProof = null
        lastYuvProofStableId = null

        if (nativeSessionHandle != 0L) {
            runCatching { NativeCameraSession.stop(nativeSessionHandle) }
            nativeSessionHandle = 0L
        }

        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        runCatching { yuvReader?.setOnImageAvailableListener(null, null) }
        runCatching { yuvReader?.close() }
        yuvReader = null

        runCatching { activeSurface?.release() }
        activeSurface = null
        lastYuvRenderNs = 0L
    }

    private companion object {
        const val YUV_RENDER_MAX_DIMENSION = 640
        const val YUV_RENDER_INTERVAL_NS = 100_000_000L
    }
}
