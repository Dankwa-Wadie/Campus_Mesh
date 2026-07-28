package com.campusmesh.android.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the secure, offline account migration protocol.
 * Generates rolling 60-second AES encrypted payloads that can be loaded into Safari via QR.
 */
class AccountTransferManager(private val context: Context) {
    companion object {
        private const val TAG = "AccountTransferManager"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BIT = 128
        private const val IV_LENGTH_BYTE = 12
        private const val KEY_LENGTH_BYTE = 16 // AES-128
    }

    private val encryptionService = EncryptionService(context)

    /**
     * Structure representing the encrypted migration package.
     */
    data class MigrationPackage(
        val qrContent: String,        // URL with hash fragment parameters
        val expiresAt: Long
    )

    /**
     * Generates a migration URL containing the encrypted identity private key.
     * The AES decryption key is appended to the hash fragment (#) so it never leaves the browser client.
     */
    fun generateMigrationPackage(gatewayIp: String = "192.168.43.1:8080"): MigrationPackage? {
        try {
            // 1. Fetch raw identity keys from Android KeyStore / secure preferences
            val privateKeyBytes = encryptionService.getRawIdentityPrivateKey() ?: return null
            val username = encryptionService.getIdentityFingerprint().take(8) // Default fallback username

            // 2. Generate random ephemeral AES key and IV
            val secureRandom = SecureRandom()
            val aesKeyBytes = ByteArray(KEY_LENGTH_BYTE)
            val ivBytes = ByteArray(IV_LENGTH_BYTE)
            secureRandom.nextBytes(aesKeyBytes)
            secureRandom.nextBytes(ivBytes)

            // 3. Encrypt the private key using AES-GCM
            val secretKey = SecretKeySpec(aesKeyBytes, "AES")
            val cipher = Cipher.getInstance(ALGORITHM)
            val parameterSpec = GCMParameterSpec(TAG_LENGTH_BIT, ivBytes)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
            val encryptedBytes = cipher.doFinal(privateKeyBytes)

            // 4. Encode components as URL-safe Hex strings
            val aesKeyHex = bytesToHex(aesKeyBytes)
            val ivHex = bytesToHex(ivBytes)
            val encryptedHex = bytesToHex(encryptedBytes)
            
            val peerID = encryptionService.getIdentityFingerprint()

            // 5. Construct URL with parameters in hash fragment (Mega-style local decryption)
            val expiresAt = System.currentTimeMillis() + 60000 // 60-second validity window
            
            // Build the URL targeting the local Ktor server migration page
            val qrContent = "http://$gatewayIp/migrate.html#key=$aesKeyHex&iv=$ivHex&payload=$encryptedHex&peer=$peerID&expires=$expiresAt"
            
            Log.d(TAG, "🔑 Generated account migration URL (expires in 60s)")
            return MigrationPackage(qrContent, expiresAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating migration package: ${e.message}", e)
            return null
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
