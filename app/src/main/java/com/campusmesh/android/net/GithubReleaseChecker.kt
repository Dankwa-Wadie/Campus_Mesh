package com.campusmesh.android.net

import android.content.Context
import android.util.Log
import com.campusmesh.android.config.ConfigLoader
import com.campusmesh.android.data.AppUpdateNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub Releases API for updates to Campus Mesh.
 *
 * When a device has internet access, it queries the API and downloads the latest
 * signed APK to local cache. When offline, [AppUpdateNotice] packets propagate
 * update awareness via BLE epidemic broadcast.
 */
object GithubReleaseChecker {
    private const val TAG = "GithubReleaseChecker"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    data class ReleaseInfo(
        val tagName: String,
        val versionCode: Int,
        val apkDownloadUrl: String?,
        val apkSizeBytes: Long,
        val sha256Hash: String?
    )

    /**
     * Queries GitHub Releases API and parses the latest release.
     * Returns null if network is unavailable or parsing fails.
     */
    suspend fun fetchLatestRelease(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        val config = ConfigLoader.load(context)
        val apiUrl = config.githubReleasesApiUrl

        try {
            Log.d(TAG, "🌐 Checking GitHub Releases: $apiUrl")
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CampusMesh-Android/${android.os.Build.VERSION.SDK_INT}")
            }

            if (conn.responseCode != 200) {
                Log.w(TAG, "GitHub API returned: ${conn.responseCode}")
                return@withContext null
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            return@withContext parseRelease(json)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub release check failed (likely offline): ${e.message}")
            null
        }
    }

    /**
     * Parses GitHub API JSON response without external dependencies.
     * Extracts tag_name, version code, APK download URL, and SHA-256 hash.
     */
    private fun parseRelease(json: String): ReleaseInfo? {
        return try {
            // Extract tag_name (e.g. "v1.3.0")
            val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")
                .find(json)?.groupValues?.get(1) ?: return null

            // Convert tag to versionCode: "v1.3.0" -> 130
            val versionCode = parseVersionCode(tagName)

            // Find the APK download URL from assets array
            var apkUrl: String? = null
            var apkSize = 0L
            val assetBlocks = Regex("\\{[^}]*\"browser_download_url\"[^}]*\\}").findAll(json)
            for (block in assetBlocks) {
                val blockStr = block.value
                if (blockStr.contains(".apk")) {
                    apkUrl = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
                        .find(blockStr)?.groupValues?.get(1)
                    apkSize = Regex("\"size\"\\s*:\\s*(\\d+)")
                        .find(blockStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    if (apkUrl?.contains("arm64") == true || apkUrl?.contains("universal") == true) break
                }
            }

            // Extract SHA-256 from release body (look for "sha256: <hash>" pattern)
            val sha256 = Regex("sha256[:\\s]+([a-f0-9]{64})", RegexOption.IGNORE_CASE)
                .find(json)?.groupValues?.get(1)

            Log.i(TAG, "📦 Latest release: $tagName (versionCode: $versionCode), APK: $apkUrl")
            ReleaseInfo(tagName, versionCode, apkUrl, apkSize, sha256)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GitHub release JSON: ${e.message}")
            null
        }
    }

    /**
     * Converts a semver tag to an integer versionCode.
     * "v1.3.0" -> 130, "v2.1.4" -> 214
     */
    fun parseVersionCode(tagName: String): Int {
        val clean = tagName.trimStart('v', 'V')
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        return when {
            parts.size >= 3 -> parts[0] * 100 + parts[1] * 10 + parts[2]
            parts.size == 2 -> parts[0] * 100 + parts[1] * 10
            parts.size == 1 -> parts[0] * 100
            else -> 0
        }
    }

    /**
     * Creates an [AppUpdateNotice] broadcast packet from fetched release info.
     */
    fun toUpdateNotice(release: ReleaseInfo, localPeerId: String): AppUpdateNotice {
        return AppUpdateNotice(
            newVersionCode = release.versionCode,
            newVersionName = release.tagName,
            apkSizeBytes = release.apkSizeBytes,
            sha256Hash = release.sha256Hash ?: "",
            senderPeerId = localPeerId,
            githubReleaseUrl = release.apkDownloadUrl
        )
    }
}
