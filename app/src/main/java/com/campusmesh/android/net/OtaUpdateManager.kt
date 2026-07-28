package com.campusmesh.android.net

import android.content.Context
import android.util.Log
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
     */
    fun verifyUpdate(session: DownloadSession, expectedHash: String): Boolean {
        if (!session.isCompleted) return false
        val computed = computeSha256(session.destinationFile)
        val matches = computed.equals(expectedHash, ignoreCase = true)
        Log.i(TAG, "OTA verification: computed=$computed, expected=$expectedHash, match=$matches")
        return matches
    }
}
