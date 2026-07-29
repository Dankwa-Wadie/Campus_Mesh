package com.campusmesh.android.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Represents the parsed structure of assets/school_config.json.
 *
 * This file allows other universities to fork Campus Mesh and simply
 * update this JSON to configure their own campus coordinates and GitHub OTA repo.
 */
data class SchoolConfig(
    @SerializedName("school_name")    val schoolName: String = "Ghana Communication Technology University",
    @SerializedName("school_short")   val schoolShort: String = "GCTU",
    @SerializedName("github_repo_owner") val githubRepoOwner: String = "Dankwa-Wadie",
    @SerializedName("github_repo_name")  val githubRepoName: String = "Campus_Mesh",
    @SerializedName("campuses")       val campuses: List<CampusLocation> = emptyList()
) {
    /** Constructs the GitHub Releases latest API URL from config fields. */
    val githubReleasesApiUrl: String
        get() = "https://api.github.com/repos/$githubRepoOwner/$githubRepoName/releases/latest"
}

data class CampusLocation(
    @SerializedName("name")           val name: String,
    @SerializedName("lat")            val lat: Double,
    @SerializedName("lon")            val lon: Double,
    @SerializedName("radius_meters")  val radiusMeters: Int = 300
)

/**
 * Loads and caches the school configuration from `assets/school_config.json`.
 *
 * Usage:
 * ```kotlin
 * val config = ConfigLoader.load(context)
 * Log.i("Config", "School: ${config.schoolName}")
 * ```
 */
object ConfigLoader {
    private const val TAG = "ConfigLoader"
    private const val CONFIG_FILE = "school_config.json"

    @Volatile
    private var cachedConfig: SchoolConfig? = null

    fun load(context: Context): SchoolConfig {
        cachedConfig?.let { return it }

        return try {
            val json = context.assets.open(CONFIG_FILE).bufferedReader().use { it.readText() }
            val parsed = Gson().fromJson(json, SchoolConfig::class.java)
            cachedConfig = parsed
            Log.i(TAG, "✅ Loaded school config: ${parsed.schoolName} (${parsed.schoolShort})")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load school_config.json, using defaults: ${e.message}")
            SchoolConfig().also { cachedConfig = it }
        }
    }
}
