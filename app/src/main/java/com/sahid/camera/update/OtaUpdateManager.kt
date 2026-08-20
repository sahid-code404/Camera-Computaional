package com.sahid.camera.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sahid.camera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest


data class OtaUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val packageName: String,
    val apkUrl: String,
    val sha256: String,
)

sealed interface OtaCheckResult {
    data class Available(val update: OtaUpdateInfo) : OtaCheckResult
    data class UpToDate(val versionName: String) : OtaCheckResult
    data class Failed(val detail: String) : OtaCheckResult
}

/**
 * Small sideload OTA client for development builds.
 *
 * Android does not allow a normal third-party app to silently replace itself. Camera can
 * download and verify the next APK, then hand it to Android's package installer. The user
 * still confirms the installation. In-place updates work because every Phase-01 APK uses
 * the same applicationId and the same dedicated development signing certificate.
 */
object OtaUpdateManager {
    suspend fun checkForUpdate(): OtaCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val json = downloadText(BuildConfig.OTA_MANIFEST_URL)
            val root = JSONObject(json)
            val update = OtaUpdateInfo(
                versionCode = root.getLong("versionCode"),
                versionName = root.getString("versionName"),
                packageName = root.getString("packageName"),
                apkUrl = root.getString("apkUrl"),
                sha256 = root.getString("sha256").lowercase(),
            )

            require(update.packageName == BuildConfig.APPLICATION_ID) {
                "Update package mismatch: ${update.packageName}"
            }

            if (update.versionCode > BuildConfig.VERSION_CODE.toLong()) {
                OtaCheckResult.Available(update)
            } else {
                OtaCheckResult.UpToDate(BuildConfig.VERSION_NAME)
            }
        }.getOrElse { error ->
            OtaCheckResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun downloadAndVerify(context: Context, update: OtaUpdateInfo): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val destination = File(updateDir, "Camera-${update.versionCode}.apk")
                downloadFile(update.apkUrl, destination)

                val actualSha = sha256(destination)
                require(actualSha.equals(update.sha256, ignoreCase = true)) {
                    destination.delete()
                    "Downloaded APK checksum mismatch"
                }
                destination
            }
        }

    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.updates",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun downloadText(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (t: Throwable) {
            destination.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8")
            setRequestProperty("User-Agent", "Camera-Aurora/${BuildConfig.VERSION_NAME}")
            connect()
            require(responseCode in 200..299) { "HTTP $responseCode" }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
