package com.campusmesh.android.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.campusmesh.android.MainActivity
import com.campusmesh.android.R
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

import com.campusmesh.android.config.ConfigLoader

/**
 * Data structures for GitHub Release API responses.
 */
data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GithubAsset>?
)

data class GithubAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("size") val size: Long
)

/**
 * Sealed class representing update check status.
 */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkFileName: String,
        val apkSizeBytes: Long = 0L,
        val publishedAt: String? = null
    ) : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Downloading(val progressPercent: Int) : UpdateStatus()
    data class ReadyToInstall(val apkFile: File) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

/**
 * Manages checking GitHub Releases for app updates, comparing versions against installed app,
 * periodic background checks when connected to internet, downloading, and prompting installation.
 */
object GithubUpdateChecker {
    private const val TAG = "GithubUpdateChecker"

    private const val PREFS_NAME = "campus_mesh_update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_timestamp"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours

    private const val NOTIFICATION_CHANNEL_ID = "campus_mesh_updates"
    private const val NOTIFICATION_ID = 20002

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var periodicJob: Job? = null

    private val _statusFlow = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val statusFlow: StateFlow<UpdateStatus> = _statusFlow.asStateFlow()

    fun init(context: Context) {
        startPeriodicCheck(context)
    }

    /**
     * Checks if the device has an active internet connection.
     */
    fun isInternetConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Obtains the currently installed app version string (e.g., "1.0.0").
     */
    fun getInstalledVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Manually or automatically trigger an update check against GitHub Releases.
     */
    fun checkForUpdates(context: Context, force: Boolean = false) {
        if (_statusFlow.value is UpdateStatus.Checking) return
        if (!force && !isInternetConnected(context)) {
            Log.d(TAG, "No internet connection available; skipping update check.")
            return
        }

        val currentVersion = getInstalledVersionName(context)
        _statusFlow.value = UpdateStatus.Checking

        scope.launch {
            try {
                val config = ConfigLoader.load(context)
                val apiUrl = config.githubReleasesApiUrl
                Log.d(TAG, "Checking GitHub Releases at $apiUrl (Current running release: v$currentVersion)")

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "CampusMeshApp-Android")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 404) {
                            Log.i(TAG, "No releases found on GitHub repo yet.")
                            _statusFlow.value = UpdateStatus.UpToDate(currentVersion)
                        } else {
                            val err = "HTTP error ${response.code} fetching release info"
                            Log.e(TAG, err)
                            _statusFlow.value = UpdateStatus.Error(err)
                        }
                        return@launch
                    }

                    val json = response.body?.string() ?: ""
                    val release = gson.fromJson(json, GithubRelease::class.java)

                    val rawTag = release.tagName.trim()
                    val latestVersion = rawTag.removePrefix("v").removePrefix("V")

                    Log.i(TAG, "GitHub release comparison: Running=$currentVersion vs GitHub Latest=$latestVersion (tag=$rawTag)")

                    // Record check timestamp
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                        .apply()

                    if (isNewerVersion(currentVersion, latestVersion)) {
                        // Find suitable APK asset (prefer universal or arm64 or release APK)
                        val apkAsset = release.assets?.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                            ?: release.assets?.firstOrNull { it.name.contains("apk", ignoreCase = true) }

                        if (apkAsset != null) {
                            val notes = release.body ?: "New release available on GitHub!"
                            _statusFlow.value = UpdateStatus.UpdateAvailable(
                                latestVersion = latestVersion,
                                currentVersion = currentVersion,
                                releaseNotes = notes,
                                downloadUrl = apkAsset.downloadUrl,
                                apkFileName = apkAsset.name,
                                apkSizeBytes = apkAsset.size,
                                publishedAt = release.publishedAt
                            )
                            showUpdateNotification(context, latestVersion)
                        } else {
                            Log.w(TAG, "New release version $latestVersion exists but no APK asset found in release.")
                            _statusFlow.value = UpdateStatus.UpToDate(currentVersion)
                        }
                    } else {
                        _statusFlow.value = UpdateStatus.UpToDate(currentVersion)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for GitHub updates: ${e.message}", e)
                _statusFlow.value = UpdateStatus.Error(e.message ?: "Failed to reach GitHub")
            }
        }
    }

    /**
     * Compare two semantic version strings (e.g. "1.0.0" vs "1.0.1").
     * Returns true if [latest] is strictly greater than [current].
     */
    fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val currentClean = current.trim().removePrefix("v").removePrefix("V")
            val latestClean = latest.trim().removePrefix("v").removePrefix("V")

            val currentParts = currentClean.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latestClean.split("-")[0].split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val c = currentParts.getOrElse(i) { 0 }
                val l = latestParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing version strings: current=$current, latest=$latest", e)
            return false
        }
    }

    /**
     * Downloads the APK file from GitHub Releases and prompts the Android Package Installer.
     */
    fun downloadAndInstall(context: Context, downloadUrl: String, apkFileName: String) {
        if (_statusFlow.value is UpdateStatus.Downloading) return
        _statusFlow.value = UpdateStatus.Downloading(0)

        scope.launch {
            try {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "CampusMeshApp-Android")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _statusFlow.value = UpdateStatus.Error("Failed to download APK: HTTP ${response.code}")
                        return@launch
                    }

                    val body = response.body ?: run {
                        _statusFlow.value = UpdateStatus.Error("Empty APK response body")
                        return@launch
                    }

                    val totalBytes = body.contentLength()
                    val destDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
                    val destFile = File(destDir, apkFileName)

                    if (destFile.exists()) {
                        destFile.delete()
                    }

                    body.byteStream().use { inputStream ->
                        FileOutputStream(destFile).use { outputStream ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastReportedPercent = -1

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                if (totalBytes > 0) {
                                    val percent = ((totalRead * 100) / totalBytes).toInt()
                                    if (percent != lastReportedPercent) {
                                        lastReportedPercent = percent
                                        _statusFlow.value = UpdateStatus.Downloading(percent)
                                    }
                                }
                            }
                        }
                    }

                    Log.i(TAG, "APK download complete: ${destFile.absolutePath} (${destFile.length()} bytes)")
                    _statusFlow.value = UpdateStatus.ReadyToInstall(destFile)

                    // Launch Android Package Installer via FileProvider
                    val otaManager = OtaUpdateManager(context)
                    val installIntent = otaManager.buildInstallIntent(destFile)
                    context.startActivity(installIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading APK update: ${e.message}", e)
                _statusFlow.value = UpdateStatus.Error("Download failed: ${e.message}")
            }
        }
    }

    /**
     * Starts periodic background update checks (runs every 6 hours if connected to internet).
     */
    fun startPeriodicCheck(context: Context) {
        if (periodicJob?.isActive == true) return

        periodicJob = scope.launch {
            while (isActive) {
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
                    val now = System.currentTimeMillis()

                    if (now - lastCheck >= CHECK_INTERVAL_MS) {
                        if (isInternetConnected(context)) {
                            Log.i(TAG, "Running periodic GitHub update check...")
                            checkForUpdates(context, force = false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic update check loop: ${e.message}")
                }
                delay(15 * 60 * 1000L) // Check eligibility every 15 mins
            }
        }
    }

    private fun showUpdateNotification(context: Context, version: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for new Campus Mesh app releases"
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Campus Mesh Update Available")
                .setContentText("Version v$version is available to download and install.")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show update notification: ${e.message}")
        }
    }
}
