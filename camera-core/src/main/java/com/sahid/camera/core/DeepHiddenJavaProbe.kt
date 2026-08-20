package com.sahid.camera.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class JavaDirectOpenProbe(
    val cameraId: String,
    val opened: Boolean,
    val detail: String,
)

/**
 * Deliberate Camera-Lab probe for numeric IDs that are not discoverable through metadata.
 *
 * This is intentionally independent from getCameraCharacteristics(). An OEM may reject or
 * filter characteristics for a hidden ID while still allowing CameraDevice open on that ID.
 */
class DeepHiddenJavaProbe(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("DeepHiddenJavaProbe").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }

    fun probeDirectOpen(cameraId: String): JavaDirectOpenProbe {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return JavaDirectOpenProbe(cameraId, false, "camera permission missing")
        }

        val latch = CountDownLatch(1)
        val cameraRef = AtomicReference<CameraDevice?>()
        val detailRef = AtomicReference("open timed out")

        try {
            manager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraRef.set(camera)
                    detailRef.set("opened")
                    latch.countDown()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    detailRef.set("disconnected")
                    runCatching { camera.close() }
                    latch.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    detailRef.set("error $error")
                    runCatching { camera.close() }
                    latch.countDown()
                }
            })
        } catch (t: Throwable) {
            return JavaDirectOpenProbe(
                cameraId = cameraId,
                opened = false,
                detail = "${t.javaClass.simpleName}: ${t.message.orEmpty()}",
            )
        }

        val signalled = latch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val camera = cameraRef.getAndSet(null)
        runCatching { camera?.close() }
        return JavaDirectOpenProbe(
            cameraId = cameraId,
            opened = signalled && camera != null,
            detail = if (signalled) detailRef.get() else "open timed out",
        )
    }

    override fun close() {
        thread.quitSafely()
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 1_200L
    }
}
