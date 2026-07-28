package com.campusmesh.android.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import com.campusmesh.android.model.BitchatMessage
import com.campusmesh.android.protocol.BitchatPacket
import com.google.gson.Gson
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.charset.StandardCharsets

/**
 * Validates role-based identity certificates and signatures to prevent spam or spoofed announcements.
 */
object RoleAttestationManager {
    private const val TAG = "RoleAttestationManager"
    private val gson = Gson()

    // Master GCTU Admin Public Key (32 bytes Ed25519) - hardcoded fallback for verification
    // In a real deployment, this would be loaded from a secure genesis configuration.
    private const val ADMIN_PUBLIC_KEY_BASE64 = "MCowBQYDK2VwAyEAfv/aEwX9sVzXwNqS13lK8J5t13hY2xS87lIq0bK4A0k=" 
    private val ADMIN_PUBLIC_KEY_BYTES by lazy {
        try {
            Base64.decode(ADMIN_PUBLIC_KEY_BASE64, Base64.DEFAULT)
        } catch (e: Exception) {
            ByteArray(32) // Fallback empty
        }
    }

    /**
     * Structure of an Admin-signed Role Attestation Certificate
     */
    data class RoleCertificate(
        val subjectPublicKey: String, // Base64 representation of Lecturer's signing public key
        val role: String,             // "Lecturer" or "Admin"
        val name: String,             // Lecturer display name
        val issuer: String = "GCTU Admin",
        val signature: String         // Base64 Ed25519 signature of the admin over: subjectPublicKey + role + name + issuer
    ) {
        fun toSigningData(): ByteArray {
            return "$subjectPublicKey|$role|$name|$issuer".toByteArray(StandardCharsets.UTF_8)
        }
    }

    /**
     * Structure of a signed message payload sent on official channels
     */
    data class SignedAnnouncement(
        val content: String,
        val timestamp: Long,
        val certificate: RoleCertificate,
        val messageSignature: String // Base64 Ed25519 signature of the lecturer over the content + timestamp
    ) {
        fun toSigningData(): ByteArray {
            return "$content|$timestamp".toByteArray(StandardCharsets.UTF_8)
        }
    }

    /**
     * Structure of a multi-signature announcement (e.g. 2-of-3 staff signatures required for alerts)
     */
    data class MultiSigAnnouncement(
        val content: String,
        val timestamp: Long,
        val certificates: List<RoleCertificate>,
        val signatures: List<String> // base64 signatures matching the certificates index
    ) {
        fun toSigningData(): ByteArray {
            return "$content|$timestamp".toByteArray(StandardCharsets.UTF_8)
        }
    }

    // Thread-safe set of blacklisted/revoked keys
    private val revokedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun revokeKey(publicKeyBase64: String) {
        revokedKeys.add(publicKeyBase64)
        Log.i(TAG, "🚫 Key blacklisted in revocation registry: $publicKeyBase64")
    }

    fun isKeyRevoked(publicKeyBase64: String): Boolean {
        return revokedKeys.contains(publicKeyBase64)
    }

    /**
     * Verifies if a message received on a read-only channel or an alert is authentic.
     * Checks:
     * 1. The Admin's signature on the role certificate.
     * 2. The Lecturer's signature on the message payload.
     * 3. The validity of the role (must be Lecturer or Admin).
     * 4. Multi-Sig threshold requirements if applicable (e.g., 2 valid signers).
     * 5. That no signing keys have been blacklisted/revoked.
     */
    fun verifyAnnouncement(payload: String): Boolean {
        try {
            if (payload.contains("\"certificates\"")) {
                // Multi-Sig Case (e.g. 2-of-3 signatures required for panic alerts)
                val multiSig = gson.fromJson(payload, MultiSigAnnouncement::class.java) ?: return false
                val threshold = 2
                var validSignaturesCount = 0
                val msgData = multiSig.toSigningData()

                for (i in multiSig.certificates.indices) {
                    val cert = multiSig.certificates[i]
                    
                    // Skip if key is revoked
                    if (isKeyRevoked(cert.subjectPublicKey)) {
                        Log.w(TAG, "⚠️ Skipping revoked signer: ${cert.name}")
                        continue
                    }

                    // Verify cert is signed by Admin
                    val adminSignatureBytes = Base64.decode(cert.signature, Base64.DEFAULT)
                    val certData = cert.toSigningData()
                    if (!verifyEd25519(ADMIN_PUBLIC_KEY_BYTES, certData, adminSignatureBytes)) {
                        continue
                    }

                    // Verify role is lecturer or admin
                    if (cert.role != "Lecturer" && cert.role != "Admin") {
                        continue
                    }

                    // Verify signature of this specific signer
                    val lecturerPubKeyBytes = Base64.decode(cert.subjectPublicKey, Base64.DEFAULT)
                    val sigBytes = Base64.decode(multiSig.signatures[i], Base64.DEFAULT)
                    if (verifyEd25519(lecturerPubKeyBytes, msgData, sigBytes)) {
                        validSignaturesCount++
                    }
                }

                Log.i(TAG, "🔒 Multi-sig verification: $validSignaturesCount valid signers (Threshold: $threshold)")
                return validSignaturesCount >= threshold
            } else {
                // Standard Single-Sig Case
                val announcement = gson.fromJson(payload, SignedAnnouncement::class.java) ?: return false
                val cert = announcement.certificate

                // Check if key is blacklisted
                if (isKeyRevoked(cert.subjectPublicKey)) {
                    Log.w(TAG, "🚫 Dropping announcement: key is revoked/stolen for ${cert.name}")
                    return false
                }

                // Verify Admin's Signature on the Role Certificate
                val adminSignatureBytes = Base64.decode(cert.signature, Base64.DEFAULT)
                val certData = cert.toSigningData()
                val isAdminSignatureValid = verifyEd25519(ADMIN_PUBLIC_KEY_BYTES, certData, adminSignatureBytes)
                
                if (!isAdminSignatureValid) {
                    Log.w(TAG, "❌ Admin signature verification failed on certificate.")
                    return false
                }

                // Verify the role is authorized
                if (cert.role != "Lecturer" && cert.role != "Admin") {
                    Log.w(TAG, "❌ Unauthorized role in certificate: ${cert.role}")
                    return false
                }

                // Verify Lecturer's Signature on the Announcement Message
                val lecturerPubKeyBytes = Base64.decode(cert.subjectPublicKey, Base64.DEFAULT)
                val msgSignatureBytes = Base64.decode(announcement.messageSignature, Base64.DEFAULT)
                val msgData = announcement.toSigningData()
                
                val isLecturerSignatureValid = verifyEd25519(lecturerPubKeyBytes, msgData, msgSignatureBytes)
                if (!isLecturerSignatureValid) {
                    Log.w(TAG, "❌ Lecturer signature verification failed on announcement content.")
                    return false
                }

                Log.i(TAG, "✅ Verified announcement from ${cert.name} [${cert.role}]")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during announcement verification: ${e.message}")
            return false
        }
    }

    /**
     * Checks if a packet targeting a read-only channel is valid before letting it reach the UI.
     */
    fun isPacketAllowedForChannel(packet: BitchatPacket, channel: String): Boolean {
        if (channel != "#gctu-announcements") return true

        return try {
            val payloadString = String(packet.payload, StandardCharsets.UTF_8)
            verifyAnnouncement(payloadString)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Utility method to verify Ed25519 signature
     */
    private fun verifyEd25519(publicKeyBytes: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            // Check key format
            val cleanPubKey = if (publicKeyBytes.size > 32) {
                publicKeyBytes.takeLast(32).toByteArray()
            } else publicKeyBytes

            val publicKey = Ed25519PublicKeyParameters(cleanPubKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Ed25519 verification: ${e.message}")
            false
        }
    }

    /**
     * Helper to create a signed mock announcement payload (useful for presentation demos)
     */
    fun createMockAnnouncementPayload(content: String, lecturerPrivateKeyBase64: String, lecturerPublicKeyBase64: String): String {
        val mockCert = RoleCertificate(
            subjectPublicKey = lecturerPublicKeyBase64,
            role = "Lecturer",
            name = "Dr. Kofi Mensah",
            signature = "aGFja2F0aG9uX2FkbWluX3NpZ25hdHVyZV9tb2NrX3NhZmFyaV9wcmVzZW50YXRpb24=" // Pre-signed stub
        )
        
        val announcement = SignedAnnouncement(
            content = content,
            timestamp = System.currentTimeMillis(),
            certificate = mockCert,
            messageSignature = "bW9ja19sZWN0dXJlcl9zaWduYXR1cmV=" // Mock signature
        )
        
        return gson.toJson(announcement)
    }
}
