package com.sahid.camera.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Phase 02A derived preview path.
 *
 * This renderer NEVER replaces or mutates the canonical AURAW record. It consumes the exact
 * RAW_SENSOR packet already destined for AURAW and produces a lightweight JPEG sidecar only so
 * users can inspect what was captured in ordinary Gallery/Photos applications.
 *
 * The derived JPEG is published to the conventional DCIM/Camera album. It is a temporary Phase-02
 * gallery rendition, not Aurora's photographic source of truth and never feeds the computational
 * pipeline.
 *
 * The implementation intentionally stays simple and deterministic: 2x2 Bayer block reconstruction,
 * black/white normalization, capture white-balance gains, optional Camera2 color transform, robust
 * percentile exposure, then sRGB gamma. It is a qualification preview, not Aurora's final photo
 * pipeline. Phase 03+ can replace this renderer without changing the AURAW source contract.
 */
object AuroraRawPreviewRenderer {
    private const val MIME_TYPE_JPEG = "image/jpeg"
    private const val MAX_LONG_EDGE = 1600
    private const val HISTOGRAM_BINS = 2048
    private const val HISTOGRAM_LINEAR_MAX = 4.0

    fun renderAndPublish(
        context: Context,
        fileName: String,
        packet: RawImagePacket,
        staticMetadata: JSONObject,
        dynamicMetadata: JSONObject,
    ): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (packet.pixelStride < 2 || packet.width < 2 || packet.height < 2) return null

        val cfa = staticMetadata.optIntOrNull("colorFilterArrangement") ?: return null
        if (cfa !in 0..5) return null

        val whiteLevel = dynamicMetadata.optDoubleOrNull("dynamicWhiteLevel")
            ?: staticMetadata.optDoubleOrNull("whiteLevel")
            ?: 16383.0
        if (whiteLevel <= 0.0) return null

        val black = parseBlackLevels(dynamicMetadata, staticMetadata)
        val gains = parseGains(dynamicMetadata)
        val transform = parseTransform(dynamicMetadata.optJSONArray("colorCorrectionTransform"))

        val blockWidth = packet.width / 2
        val blockHeight = packet.height / 2
        val sample = max(1, ceil(max(blockWidth, blockHeight).toDouble() / MAX_LONG_EDGE).toInt())
        val outWidth = max(1, blockWidth / sample)
        val outHeight = max(1, blockHeight / sample)

        val histogram = IntArray(HISTOGRAM_BINS)
        var histogramCount = 0
        for (oy in 0 until outHeight) {
            val y = min(packet.height - 2, oy * sample * 2)
            for (ox in 0 until outWidth) {
                val x = min(packet.width - 2, ox * sample * 2)
                val rgb = sampleBlock(packet, x, y, cfa, black, whiteLevel, gains, transform)
                val peak = max(rgb[0], max(rgb[1], rgb[2])).coerceAtLeast(0.0)
                val bucket = ((peak / HISTOGRAM_LINEAR_MAX) * (HISTOGRAM_BINS - 1))
                    .toInt()
                    .coerceIn(0, HISTOGRAM_BINS - 1)
                histogram[bucket]++
                histogramCount++
            }
        }

        val exposure = estimateExposure(histogram, histogramCount)
        val pixels = IntArray(outWidth * outHeight)
        var index = 0
        for (oy in 0 until outHeight) {
            val y = min(packet.height - 2, oy * sample * 2)
            for (ox in 0 until outWidth) {
                val x = min(packet.width - 2, ox * sample * 2)
                val rgb = sampleBlock(packet, x, y, cfa, black, whiteLevel, gains, transform)
                val r = toSrgb8(rgb[0] * exposure)
                val g = toSrgb8(rgb[1] * exposure)
                val b = toSrgb8(rgb[2] * exposure)
                pixels[index++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888)
        return try {
            publishJpeg(context, fileName, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun sampleBlock(
        packet: RawImagePacket,
        x: Int,
        y: Int,
        cfa: Int,
        black: DoubleArray,
        whiteLevel: Double,
        gains: Gains,
        transform: Array<DoubleArray>,
    ): DoubleArray {
        val s00 = normalize(readRaw16(packet, x, y), black[0], whiteLevel)
        val s10 = normalize(readRaw16(packet, x + 1, y), black[1], whiteLevel)
        val s01 = normalize(readRaw16(packet, x, y + 1), black[2], whiteLevel)
        val s11 = normalize(readRaw16(packet, x + 1, y + 1), black[3], whiteLevel)

        val sensor = when (cfa) {
            0 -> doubleArrayOf(s00 * gains.red, ((s10 + s01) * 0.5) * gains.green, s11 * gains.blue)
            1 -> doubleArrayOf(s10 * gains.red, ((s00 + s11) * 0.5) * gains.green, s01 * gains.blue)
            2 -> doubleArrayOf(s01 * gains.red, ((s00 + s11) * 0.5) * gains.green, s10 * gains.blue)
            3 -> doubleArrayOf(s11 * gains.red, ((s10 + s01) * 0.5) * gains.green, s00 * gains.blue)
            4 -> {
                val avg = (s00 + s10 + s01 + s11) * 0.25
                doubleArrayOf(avg * gains.red, avg * gains.green, avg * gains.blue)
            }
            else -> {
                val avg = (s00 + s10 + s01 + s11) * 0.25
                doubleArrayOf(avg, avg, avg)
            }
        }

        return doubleArrayOf(
            transform[0][0] * sensor[0] + transform[0][1] * sensor[1] + transform[0][2] * sensor[2],
            transform[1][0] * sensor[0] + transform[1][1] * sensor[1] + transform[1][2] * sensor[2],
            transform[2][0] * sensor[0] + transform[2][1] * sensor[1] + transform[2][2] * sensor[2],
        )
    }

    private fun readRaw16(packet: RawImagePacket, x: Int, y: Int): Int {
        val offset = y * packet.rowStride + x * packet.pixelStride
        if (offset < 0 || offset + 1 >= packet.bytes.size) return 0
        val a = packet.bytes[offset].toInt() and 0xFF
        val b = packet.bytes[offset + 1].toInt() and 0xFF
        return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            a or (b shl 8)
        } else {
            (a shl 8) or b
        }
    }

    private fun normalize(value: Int, black: Double, white: Double): Double {
        val denominator = max(1.0, white - black)
        return ((value.toDouble() - black) / denominator).coerceAtLeast(0.0)
    }

    private fun parseBlackLevels(dynamic: JSONObject, static: JSONObject): DoubleArray {
        val dynamicLevels = dynamic.optJSONArray("dynamicBlackLevel")
        if (dynamicLevels != null && dynamicLevels.length() >= 4) {
            return DoubleArray(4) { i -> dynamicLevels.optDouble(i, 0.0) }
        }
        val staticLevels = static.optJSONArray("blackLevelPattern")
        if (staticLevels != null && staticLevels.length() >= 4) {
            return DoubleArray(4) { i -> staticLevels.optDouble(i, 0.0) }
        }
        return DoubleArray(4)
    }

    private fun parseGains(dynamic: JSONObject): Gains {
        val json = dynamic.optJSONObject("colorCorrectionGains") ?: return Gains(1.0, 1.0, 1.0)
        val green = (json.optDouble("greenEven", 1.0) + json.optDouble("greenOdd", 1.0)) * 0.5
        return Gains(
            red = json.optDouble("red", 1.0).coerceAtLeast(0.0),
            green = green.coerceAtLeast(0.0),
            blue = json.optDouble("blue", 1.0).coerceAtLeast(0.0),
        )
    }

    private fun parseTransform(json: JSONArray?): Array<DoubleArray> {
        val identity = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
        if (json == null || json.length() < 3) return identity
        return runCatching {
            Array(3) { row ->
                val values = json.getJSONArray(row)
                DoubleArray(3) { column -> rationalToDouble(values.getJSONObject(column)) }
            }
        }.getOrDefault(identity)
    }

    private fun rationalToDouble(json: JSONObject): Double {
        val denominator = json.optDouble("denominator", 1.0)
        if (denominator == 0.0) return 0.0
        return json.optDouble("numerator", 0.0) / denominator
    }

    private fun estimateExposure(histogram: IntArray, count: Int): Double {
        if (count <= 0) return 1.0
        val target = (count * 0.995).toInt().coerceAtLeast(1)
        var cumulative = 0
        var index = histogram.lastIndex
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= target) {
                index = i
                break
            }
        }
        val percentile = (index.toDouble() / (HISTOGRAM_BINS - 1)) * HISTOGRAM_LINEAR_MAX
        if (percentile <= 1e-6) return 1.0
        return (0.90 / percentile).coerceIn(0.25, 16.0)
    }

    private fun toSrgb8(linear: Double): Int {
        val x = linear.coerceIn(0.0, 1.0)
        val srgb = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (srgb * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishJpeg(context: Context, fileName: String, bitmap: Bitmap): File {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE_JPEG)
            put(MediaStore.Images.Media.RELATIVE_PATH, CameraStoragePolicy.VISIBLE_ALBUM_RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore Images refused Camera album destination")
        try {
            val stream = resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException("Unable to open Camera album destination")
            stream.use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "JPEG preview encoder failed" }
            }
            val updated = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            check(updated == 1) { "Unable to publish Camera album rendition" }
            return File("/${CameraStoragePolicy.VISIBLE_ALBUM_RELATIVE_PATH}", fileName)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null

    private data class Gains(val red: Double, val green: Double, val blue: Double)
}
