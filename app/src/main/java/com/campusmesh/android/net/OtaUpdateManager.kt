package com.campusmesh.android.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Manages epidemic mesh OTA updates by chunking the APK into 256 KB blocks,
 * computing SHA-256 integrity signatures, and managing assembly/resume state.
 */
class OtaUpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "OtaUpdateManager"
        const val CHUNK_SIZE = 256 * 1024 // 256 KB chunks

        // Hardcoded version metadata for presentation demo
        const val CURRENT_VERSION_CODE = 180
        const val CURRENT_VERSION_HASH = "8f3c7d9a1b5e2f6c0d8b7a4c3e2f1a0d9b8c7a6e5f4d3c2b1a0f9e8d7c6b5a4f"
    }

    /**
     * Obtains the path to the current running APK of the application.
     */
    fun getLocalApkFile(): File? {
        return try {
            val apkPath = context.packageCodePath
            val file = File(apkPath)
            if (file.exists()) file else null
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining local APK file: ${e.message}")
            null
        }
    }

    /**
     * Reads a specific 256 KB chunk from the local APK file.
     */
    fun getApkChunk(chunkIndex: Int): ByteArray? {
        val apkFile = getLocalApkFile() ?: return null
        val fileSize = apkFile.length()
        val offset = chunkIndex.toLong() * CHUNK_SIZE

        if (offset >= fileSize || offset < 0) {
            return null
        }

        val bytesToRead = minOf(CHUNK_SIZE.toLong(), fileSize - offset).toInt()
        val buffer = ByteArray(bytesToRead)

        return try {
            RandomAccessFile(apkFile, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(buffer)
            }
            buffer
        } catch (e: Exception) {
            Log.e(TAG, "Error reading APK chunk $chunkIndex: ${e.message}")
            null
        }
    }

    /**
     * Calculates the total number of 256 KB chunks in the APK.
     */
    fun getChunkCount(): Int {
        val apkFile = getLocalApkFile() ?: return 0
        return ((apkFile.length() + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    /**
     * Computes SHA-256 hash of a file for cryptographic integrity verification.
     */
    fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead = fis.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = fis.read(buffer)
                }
            }
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute SHA-256: ${e.message}")
            ""
        }
    }

    /**
     * Class to track the download status of an OTA update.
     */
    class DownloadSession(
        val versionHash: String,
        val totalChunks: Int,
        val destinationFile: File
    ) {
        val chunkMap = BooleanArray(totalChunks)
        var isCompleted = false

        init {
            // If file already exists, open and check size to resume
            if (destinationFile.exists() && destinationFile.length() == (totalChunks * CHUNK_SIZE).toLong()) {
                // Initialize chunk map as fully downloaded
                chunkMap.fill(true)
                isCompleted = true
            }
        }

        @Synchronized
        fun writeChunk(chunkIndex: Int, data: ByteArray): Boolean {
            if (chunkIndex < 0 || chunkIndex >= totalChunks) return false
            if (chunkMap[chunkIndex]) return true // Already written

            return try {
                RandomAccessFile(destinationFile, "rw").use { raf ->
                    val offset = chunkIndex.toLong() * CHUNK_SIZE
                    raf.seek(offset)
                    raf.write(data)
                }
                chunkMap[chunkIndex] = true
                checkCompletion()
                true
            } catch (e: Exception) {
                Log.e("DownloadSession", "Error writing chunk $chunkIndex: ${e.message}")
                false
            }
        }

        private fun checkCompletion() {
            isCompleted = chunkMap.all { it }
        }
    }

    /**
     * Verifies that the completed OTA file matches the expected SHA-256 signature.
     *
     * NOTE: hash-only verification is not sufficient on its own. A compromised mesh peer could
     * redistribute a re-signed APK and simply broadcast a matching SHA-256 for their own build —
     * the hash proves the bytes weren't corrupted in transit, not that they came from a
     * legitimate Campus Mesh release. Callers MUST also call [verifyApkSignature] before
     * installing; [verifyAndPrepareInstall] does both and is the function that should actually
     * be used by the OTA install flow.
     */
    fun verifyUpdate(session: DownloadSession, expectedHash: String): Boolean {
        if (!session.isCompleted) return false
        val computed = computeSha256(session.destinationFile)
        val matches = computed.equals(expectedHash, ignoreCase = true)
        Log.i(TAG, "OTA verification: computed=$computed, expected=$expectedHash, match=$matches")
        return matches
    }

    /**
     * Verifies that [apkFile]'s signing certificate matches the certificate that signed the
     * currently-installed Campus Mesh app. This is the check that actually defends against a
     * malicious peer distributing a re-signed APK — SHA-256 alone only proves the bytes weren't
     * corrupted in transit, not who built them.
     */
    fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val installedCerts = getSigningCertificates(context.packageName, context.packageManager)
            val candidateCerts = getApkSigningCertificates(apkFile.absolutePath, context.packageManager)

            if (installedCerts.isEmpty() || candidateCerts.isEmpty()) {
                Log.w(TAG, "Signature verification: could not read certificates (installed=${installedCerts.size}, candidate=${candidateCerts.size})")
                return false
            }

            val installedDigests = installedCerts.map { sha256Hex(it) }.toSet()
            val candidateDigests = candidateCerts.map { sha256Hex(it) }.toSet()
            val matches = installedDigests.intersect(candidateDigests).isNotEmpty()

            Log.i(TAG, if (matches) "✅ APK signature matches installed app's signer" else "❌ APK signature does NOT match installed app's signer — refusing to install")
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed with exception (treated as untrusted): ${e.message}", e)
            false
        }
    }

    /**
     * Combined check: SHA-256 integrity + signing-certificate authenticity. Returns an install
     * [Intent] only if both pass; returns null otherwise. This is the function the OTA install
     * flow should call — never install off [verifyUpdate] alone.
     */
    fun verifyAndPrepareInstall(session: DownloadSession, expectedHash: String): Intent? {
        if (!verifyUpdate(session, expectedHash)) {
            Log.w(TAG, "Refusing install: SHA-256 mismatch")
            return null
        }
        if (!verifyApkSignature(session.destinationFile)) {
            Log.w(TAG, "Refusing install: signing certificate mismatch")
            return null
        }
        return buildInstallIntent(session.destinationFile)
    }

    /**
     * Builds the ACTION_VIEW install intent for a verified APK file via FileProvider (required
     * on API 24+ to grant the installer app read access to a file this app owns).
     */
    fun buildInstallIntent(apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Signing certificates (as raw DER bytes) of the currently-installed app. */
    private fun getSigningCertificates(packageName: String, pm: PackageManager): List<ByteArray> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                when {
                    signingInfo == null -> emptyList()
                    signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners.map { it.toByteArray() }
                    else -> signingInfo.signingCertificateHistory?.map { it.toByteArray() } ?: emptyList()
                }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read installed app signing certificates: ${e.message}")
            emptyList()
        }
    }

    /** Signing certificates (as raw DER bytes) declared inside an on-disk (not yet installed) APK. */
    private fun getApkSigningCertificates(apkPath: String, pm: PackageManager): List<ByteArray> {
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info: PackageInfo = pm.getPackageArchiveInfo(apkPath, flags) ?: return emptyList()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory?.map { it.toByteArray() } ?: emptyList()
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read candidate APK signing certificates: ${e.message}")
            emptyList()
        }
    }
}
