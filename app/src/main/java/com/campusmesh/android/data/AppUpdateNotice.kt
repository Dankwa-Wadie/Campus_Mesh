package com.campusmesh.android.data

/**
 * Mesh broadcast packet advertising that a newer version of Campus Mesh is available.
 *
 * Transmitted over BLE and Wi-Fi Aware background channels to nearby devices.
 * Receiving devices compare [newVersionCode] against their own installed version.
 * If [newVersionCode] is higher, they display an [UpdatePromptDialog].
 *
 * @param newVersionCode  The versionCode of the update (e.g. 13).
 * @param newVersionName  Human-readable version (e.g. "v1.3.0").
 * @param apkSizeBytes    Total size of the APK for ETA display.
 * @param sha256Hash      SHA-256 hash of the signed APK for verification.
 * @param senderPeerId    Short peer ID of the broadcasting device.
 * @param githubReleaseUrl Optional GitHub release URL if the device has internet.
 * @param timestamp       Unix timestamp when the broadcast was created.
 */
data class AppUpdateNotice(
    val newVersionCode: Int,
    val newVersionName: String,
    val apkSizeBytes: Long,
    val sha256Hash: String,
    val senderPeerId: String,
    val githubReleaseUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val MESH_PACKET_TYPE: Byte = 0x1A.toByte()

        /** True if the notice describes a version newer than the provided installed code. */
        fun isNewer(notice: AppUpdateNotice, installedVersionCode: Int): Boolean {
            return notice.newVersionCode > installedVersionCode
        }
    }

    /** Human-readable formatted size string (e.g. "42.3 MB") */
    val apkSizeDisplay: String
        get() {
            val mb = apkSizeBytes / (1024.0 * 1024.0)
            return "%.1f MB".format(mb)
        }
}
