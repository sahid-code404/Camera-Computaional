package com.sahid.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
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
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max

/**
 * Thin Camera2 preview controller for the foundation milestone.
 *
 * It deliberately routes a Surface to a physical camera ID through its logical parent
 * when a physical ID was discovered. Final capture logic belongs in a later dedicated
 * CaptureSessionManager and must not be mixed into UI code.
 */
class CameraPreviewController(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("CameraPreview").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val executor = Executor { runnable -> handler.post(runnable) }

    private var textureView: TextureView? = null
    private var selectedLens: LensCapability? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var activeSurface: Surface? = null
    private var started = false
    private var generation = 0L

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

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    fun attach(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = surfaceListener
        if (view.isAvailable) openIfReady()
    }

    fun setLens(lens: LensCapability?) {
        if (selectedLens?.stableId == lens?.stableId) return
        selectedLens = lens
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
        if (!started || !view.isAvailable || cameraDevice != null) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Camera permission required")
            return
        }

        val openGeneration = generation
        onStatus("Opening ${lens.displayName}")

        try {
            manager.openCamera(lens.logicalCameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (openGeneration != generation || !started) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createPreviewSession(camera, lens)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    onStatus("Camera disconnected")
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    onStatus("Camera error $error")
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
            })
        } catch (security: SecurityException) {
            onStatus("Camera permission denied")
        } catch (access: CameraAccessException) {
            onStatus("Camera unavailable: ${access.reason}")
        } catch (t: Throwable) {
            onStatus("Camera open failed: ${t.javaClass.simpleName}")
        }
    }

    private fun createPreviewSession(camera: CameraDevice, lens: LensCapability) {
        val view = textureView ?: return
        val texture = view.surfaceTexture ?: return
        val previewSize = choosePreviewSize(lens.previewSizes, view.width, view.height)

        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(lens, view.width, view.height, previewSize)

        val surface = Surface(texture)
        activeSurface = surface

        val output = OutputConfiguration(surface).apply {
            lens.physicalCameraId?.let { physicalId ->
                runCatching { setPhysicalCameraId(physicalId) }
                    .onFailure { onStatus("Physical stream unsupported; trying logical preview") }
            }
        }

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
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                            // Do not force an AF mode that a fixed-focus/auxiliary lens does not advertise.
                            val afModes = runCatching {
                                manager.getCameraCharacteristics(lens.physicalCameraId ?: lens.logicalCameraId)
                                    .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                    ?.toSet()
                            }.getOrNull().orEmpty()
                            when {
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                CaptureRequest.CONTROL_AF_MODE_AUTO in afModes ->
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                            }
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        onStatus("${lens.displayName} preview • ${previewSize.width}×${previewSize.height}")
                    } catch (t: Throwable) {
                        onStatus("Preview request failed: ${t.javaClass.simpleName}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onStatus("Preview session unsupported")
                    session.close()
                }
            },
        )

        try {
            camera.createCaptureSession(config)
        } catch (t: Throwable) {
            onStatus("Session creation failed: ${t.javaClass.simpleName}")
            surface.release()
            if (activeSurface === surface) activeSurface = null
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

    private fun configureTransform(
        lens: LensCapability,
        viewWidth: Int,
        viewHeight: Int,
        previewSize: Size? = null,
    ) {
        val view = textureView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val size = previewSize ?: choosePreviewSize(lens.previewSizes, viewWidth, viewHeight)
        val chars = runCatching {
            manager.getCameraCharacteristics(lens.physicalCameraId ?: lens.logicalCameraId)
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

    private fun closeCamera() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null

        runCatching { cameraDevice?.close() }
        cameraDevice = null

        runCatching { activeSurface?.release() }
        activeSurface = null
    }
}
