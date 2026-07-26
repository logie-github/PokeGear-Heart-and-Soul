package com.logie.pgearhs.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val versionName: String,
    val downloadUrl: String
)

/**
 * Checks GitHub Releases for a newer build than the one currently installed,
 * downloads the APK asset, and hands it to the system installer.
 */
class AppUpdater(private val context: Context) {

    /** Looks up the latest GitHub release and returns it if newer than [currentVersionName]. */
    fun check(currentVersionName: String): AppRelease? {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PokeGearHeartSoul-Updater")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        val body = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(body)
        val tagName = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        var downloadUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                downloadUrl = asset.getString("browser_download_url")
                break
            }
        }

        val url = downloadUrl ?: return null
        return if (isNewerVersion(tagName, currentVersionName)) {
            AppRelease(tagName, url)
        } else {
            null
        }
    }

    /** Downloads the release APK into the app's cache dir. */
    fun download(release: AppRelease): File {
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val outFile = File(updatesDir, "PokeGear-HeartSoul-${release.versionName}.apk")

        try {
            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            connection.disconnect()
        }

        return outFile
    }

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            true
        } else {
            context.packageManager.canRequestPackageInstalls()
        }

    fun requestInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".")
        val currentParts = current.split(".")
        val maxLength = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val remoteSegment = remoteParts.getOrNull(i)?.takeWhile(Char::isDigit)?.toLongOrNull() ?: 0L
            val currentSegment = currentParts.getOrNull(i)?.takeWhile(Char::isDigit)?.toLongOrNull() ?: 0L
            if (remoteSegment != currentSegment) {
                return remoteSegment > currentSegment
            }
        }
        return false
    }

    private companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/logie-github/PokeGear-Heart-and-Soul/releases/latest"
    }
}
