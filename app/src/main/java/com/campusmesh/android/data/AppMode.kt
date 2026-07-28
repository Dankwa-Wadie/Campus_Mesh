package com.campusmesh.android.data

/**
 * Represents the current operational mode of Campus Mesh.
 *
 * - MAIN_CAMPUS: Active at GCTU Tesano campus (lat: 5.6115, lon: -0.2290)
 * - ABEKA_CAMPUS: Active at GCTU Abeka/SITB campus (lat: 5.6025, lon: -0.2425)
 * - GENERAL_MESH: Off-campus, worldwide P2P mesh mode
 */
enum class AppMode(val displayName: String, val channelSuffix: String) {
    MAIN_CAMPUS(displayName = "Main Campus (Tesano)", channelSuffix = "tesano"),
    ABEKA_CAMPUS(displayName = "Abeka Campus (SITB)", channelSuffix = "abeka"),
    GENERAL_MESH(displayName = "General Mesh (Off-Campus)", channelSuffix = "global");

    companion object {
        fun fromString(value: String?): AppMode {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: GENERAL_MESH
        }
    }
}
