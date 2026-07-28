package com.campusmesh.android.data

import android.util.Base64

/**
 * Represents a user's role and identity within Campus Mesh.
 *
 * Roles determine what features a user can access:
 * - STUDENT: Can read/send general messages and view the map.
 * - LECTURER: Can broadcast official announcements with role signature.
 * - ADMIN: Can issue role attestation certificates and manage revocations.
 */
enum class UserRole(val displayName: String, val emoji: String) {
    STUDENT(displayName = "Student", emoji = "🎓"),
    LECTURER(displayName = "Lecturer", emoji = "📚"),
    ADMIN(displayName = "Admin", emoji = "🔑");

    companion object {
        fun fromString(value: String?): UserRole {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STUDENT
        }
    }
}

/**
 * A user profile stored locally on the device.
 *
 * @param nickname          Display name chosen by the user.
 * @param role              The assigned role (Student / Lecturer / Admin).
 * @param publicKeyBase64   Base64-encoded Ed25519 public key.
 * @param appMode           The current campus mode.
 * @param attestedByAdmin   If true, an Admin has co-signed this user's public key certificate.
 */
data class SchoolProfile(
    val nickname: String,
    val role: UserRole,
    val publicKeyBase64: String,
    val appMode: AppMode = AppMode.GENERAL_MESH,
    val attestedByAdmin: Boolean = false
) {
    /** Short identifier shown as peer ID (first 8 chars of Base64 key) */
    val shortPeerId: String
        get() = try {
            val bytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            bytes.take(4).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "unknown"
        }
}
