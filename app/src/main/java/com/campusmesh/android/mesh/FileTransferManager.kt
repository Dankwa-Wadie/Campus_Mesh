package com.campusmesh.android.mesh

import android.content.Context
import android.util.Log
import com.campusmesh.android.model.BitchatFilePacket
import com.campusmesh.android.model.BitchatMessage
import com.campusmesh.android.model.FileChunkProtocol
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates chunked, resumable file transfer: splits large files into [FileChunkProtocol.Chunk]
 * pieces on send, persists incoming chunks to disk as they land (surviving app restarts, not just
 * a single coroutine's lifetime), and asks for only the missing chunks when a transfer resumes
 * with the same peer instead of restarting from scratch.
 *
 * Lives at the same layer as [MediaSendingManager]/`ChatViewModel` (constructed with full
 * [MeshService] access) rather than inside [MessageHandler], because resuming requires *sending*
 * requests and re-sends, and MessageHandler (shared by both BLE and Wi-Fi Aware transports) has no
 * MeshService reference of its own -- see [FileChunkBus] for how incoming sub-messages get here.
 */
class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val getMeshService: () -> MeshService,
    private val getMyNickname: () -> String?
) {
    companion object {
        private const val TAG = "FileTransferManager"
        // Deliberately generous: each chunk itself still gets fragmented into many BLE-MTU-sized
        // pieces by the existing FragmentingPacketSender (paced at ~20ms/fragment), so this just
        // keeps successive chunk sends from queuing on top of each other before that finishes.
        private const val INTER_CHUNK_DELAY_MS = 150L
    }

    private val gson = Gson()
    private val meshService: MeshService get() = getMeshService()

    private val outDir = File(context.filesDir, "transfers/out").apply { mkdirs() }
    private val inDir = File(context.filesDir, "transfers/in").apply { mkdirs() }

    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()
    private val sendJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            FileChunkBus.events.collect { evt ->
                try {
                    when (val p = evt.parsed) {
                        is FileChunkProtocol.Parsed.InitMsg -> handleIncomingInit(evt.peerID, evt.isPrivate, p.init)
                        is FileChunkProtocol.Parsed.ChunkMsg -> handleIncomingChunk(evt.peerID, p.chunk)
                        is FileChunkProtocol.Parsed.RequestMsg -> handleIncomingRequest(evt.peerID, p.request)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle chunk event: ${e.message}", e)
                }
            }
        }
    }

    // Set by ChatViewModel once a message is ready to enter chat -- kept as a settable var rather
    // than a constructor param so this manager can be constructed before the rest of the
    // delegate-routing chain exists, matching how the other managers are wired there.
    var onIncomingMessage: ((BitchatMessage) -> Unit)? = null

    // ============ SENDING ============

    data class OutgoingManifest(
        val transferId: String,
        val filePath: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val chunkSize: Int,
        val totalChunks: Int,
        val toPeerID: String?, // null = broadcast
        var completed: Boolean = false
    )

    /**
     * Starts a chunked send for a file above [FileChunkProtocol.CHUNK_THRESHOLD]. Returns the
     * transferId (hex SHA-256 of the raw file bytes) to use with [TransferProgressManager] /
     * [cancelChunkedTransfer] -- same "hash as id" convention MediaSendingManager already uses
     * for the legacy single-packet path, just hashing the content instead of the TLV envelope.
     */
    fun sendChunked(toPeerIDOrNull: String?, filePath: String, fileName: String, mimeType: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        val bytes = try { file.readBytes() } catch (e: Exception) {
            Log.e(TAG, "Failed to read $filePath for chunked send: ${e.message}")
            return null
        }
        val transferId = sha256Hex(bytes)
        val chunkSize = FileChunkProtocol.CHUNK_SIZE
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)

        val manifest = OutgoingManifest(
            transferId = transferId,
            filePath = filePath,
            fileName = fileName,
            mimeType = mimeType,
            fileSize = bytes.size.toLong(),
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            toPeerID = toPeerIDOrNull
        )
        persistOutgoing(manifest)
        cancelledTransfers.remove(transferId)

        val job = scope.launch(Dispatchers.IO) {
            TransferProgressManager.start(transferId, totalChunks)
            sendPseudoPacket(FileChunkProtocol.buildInitPacket(toInit(manifest)), manifest.toPeerID)
            delay(INTER_CHUNK_DELAY_MS)

            for (index in 0 until totalChunks) {
                if (cancelledTransfers.contains(transferId)) {
                    Log.d(TAG, "Chunked send ${transferId.take(12)} cancelled at $index/$totalChunks")
                    return@launch
                }
                sendChunkIndex(manifest, bytes, index)
                TransferProgressManager.progress(transferId, index + 1, totalChunks)
                if (index < totalChunks - 1) delay(INTER_CHUNK_DELAY_MS)
            }
            markOutgoingCompleted(transferId)
            TransferProgressManager.complete(transferId, totalChunks)
        }
        sendJobs[transferId] = job
        job.invokeOnCompletion { sendJobs.remove(transferId, job) }
        return transferId
    }

    private fun sendChunkIndex(manifest: OutgoingManifest, bytes: ByteArray, index: Int) {
        val start = index * manifest.chunkSize
        val end = minOf(start + manifest.chunkSize, bytes.size)
        if (start >= end) return
        val slice = bytes.copyOfRange(start, end)
        val chunkPacket = FileChunkProtocol.buildChunkPacket(
            FileChunkProtocol.Chunk(transferId = manifest.transferId, chunkIndex = index, data = slice)
        )
        sendPseudoPacket(chunkPacket, manifest.toPeerID)
    }

    private fun sendPseudoPacket(packet: BitchatFilePacket, toPeerID: String?) {
        try {
            if (toPeerID != null) meshService.sendFilePrivate(toPeerID, packet) else meshService.sendFileBroadcast(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chunk-protocol packet (mime=${packet.mimeType}): ${e.message}", e)
        }
    }

    /**
     * Cancels an in-progress chunked send. Deliberately separate from the legacy
     * [MeshService.cancelFileTransfer]: that one cancels a single in-flight fragmenting job keyed
     * by a per-packet hash, but a chunked send is a sequence of many independent packets spread
     * over time, so it needs its own flag checked between chunks.
     */
    fun cancelChunkedTransfer(transferId: String): Boolean {
        cancelledTransfers.add(transferId)
        sendJobs[transferId]?.cancel()
        deleteOutgoingManifest(transferId)
        return true
    }

    fun isChunked(transferId: String): Boolean = loadOutgoing(transferId) != null

    // ============ RECEIVING ============

    data class IncomingManifest(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val fileSize: Long,
        val chunkSize: Int,
        val totalChunks: Int,
        val fromPeerID: String,
        val isPrivate: Boolean
    )

    private fun sessionDir(transferId: String) = File(inDir, safeName(transferId)).apply { mkdirs() }

    private fun handleIncomingInit(peerID: String, isPrivate: Boolean, init: FileChunkProtocol.Init) {
        val dir = sessionDir(init.transferId)
        val metaFile = File(dir, "meta.json")
        val manifest = IncomingManifest(
            transferId = init.transferId,
            fileName = init.fileName,
            mimeType = init.realMimeType,
            fileSize = init.fileSize,
            chunkSize = init.chunkSize,
            totalChunks = init.totalChunks,
            fromPeerID = peerID,
            isPrivate = isPrivate
        )
        if (!metaFile.exists()) {
            try {
                metaFile.writeText(gson.toJson(manifest))
                // Pre-size so out-of-order chunk writes at arbitrary offsets are always in-bounds.
                RandomAccessFile(File(dir, "data.part"), "rw").use { it.setLength(init.fileSize) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start incoming session ${init.transferId.take(12)}: ${e.message}")
                return
            }
        }
        // New session or a re-announced one (e.g. sender resuming after reconnect): tell them what
        // we're still missing. On a fresh session that's everything, which is fine -- the sender is
        // about to stream it all anyway; this just makes the request/stream race harmless.
        val missing = missingIndices(dir, manifest.totalChunks)
        if (missing.isNotEmpty()) {
            sendRequest(manifest, missing)
        }
    }

    private fun handleIncomingChunk(peerID: String, chunk: FileChunkProtocol.Chunk) {
        val dir = sessionDir(chunk.transferId)
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) {
            // Chunk arrived before (or without) its Init -- can't place it without knowing
            // chunkSize/totalChunks. Drops silently; a future reconnect-triggered resume (once we
            // learn about this transfer via a later Init) will recover the gap.
            Log.w(TAG, "Chunk for unknown transfer ${chunk.transferId.take(12)} from ${peerID.take(8)}; dropping")
            return
        }
        val manifest = try {
            gson.fromJson(metaFile.readText(), IncomingManifest::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt session metadata for ${chunk.transferId.take(12)}: ${e.message}")
            return
        } ?: return

        try {
            RandomAccessFile(File(dir, "data.part"), "rw").use { raf ->
                raf.seek(chunk.chunkIndex.toLong() * manifest.chunkSize)
                raf.write(chunk.data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write chunk ${chunk.chunkIndex} for ${chunk.transferId.take(12)}: ${e.message}")
            return
        }
        markReceived(dir, chunk.chunkIndex, manifest.totalChunks)

        if (missingIndices(dir, manifest.totalChunks).isEmpty()) {
            finalizeIncoming(dir, manifest)
        }
    }

    private fun handleIncomingRequest(peerID: String, request: FileChunkProtocol.Request) {
        val manifest = loadOutgoing(request.transferId)
        if (manifest == null) {
            Log.w(TAG, "Resume request for unknown outgoing transfer ${request.transferId.take(12)} from ${peerID.take(8)}")
            return
        }
        val file = File(manifest.filePath)
        if (!file.exists()) {
            Log.w(TAG, "Source file for ${request.transferId.take(12)} no longer on disk; can't resume")
            return
        }
        val bytes = try { file.readBytes() } catch (_: Exception) { return }
        cancelledTransfers.remove(manifest.transferId)

        val job = scope.launch(Dispatchers.IO) {
            val total = manifest.totalChunks
            TransferProgressManager.start(manifest.transferId, total)
            for (index in request.missing.sorted()) {
                if (cancelledTransfers.contains(manifest.transferId)) return@launch
                sendChunkIndex(manifest, bytes, index)
                delay(INTER_CHUNK_DELAY_MS)
            }
            markOutgoingCompleted(manifest.transferId)
            TransferProgressManager.complete(manifest.transferId, total)
        }
        sendJobs[manifest.transferId] = job
        job.invokeOnCompletion { sendJobs.remove(manifest.transferId, job) }
    }

    private fun sendRequest(manifest: IncomingManifest, missing: List<Int>) {
        val packet = FileChunkProtocol.buildRequestPacket(FileChunkProtocol.Request(manifest.transferId, missing))
        sendPseudoPacket(packet, if (manifest.isPrivate) manifest.fromPeerID else null)
    }

    private fun finalizeIncoming(dir: File, manifest: IncomingManifest) {
        val bytes = try { File(dir, "data.part").readBytes() } catch (e: Exception) {
            Log.e(TAG, "Failed to read reassembled file for ${manifest.transferId.take(12)}: ${e.message}")
            return
        }
        val actualHash = sha256Hex(bytes)
        if (!actualHash.equals(manifest.transferId, ignoreCase = true)) {
            Log.e(TAG, "Reassembled file for ${manifest.transferId.take(12)} failed integrity check -- discarding")
            dir.deleteRecursively()
            return
        }

        val filePacket = BitchatFilePacket(fileName = manifest.fileName, fileSize = manifest.fileSize, mimeType = manifest.mimeType, content = bytes)
        val savedPath = com.campusmesh.android.features.file.FileUtils.saveIncomingFile(context, filePacket)
        dir.deleteRecursively()

        val senderName = try { meshService.getPeerNicknames()[manifest.fromPeerID] } catch (_: Exception) { null } ?: "unknown"
        val message = BitchatMessage(
            id = java.util.UUID.randomUUID().toString().uppercase(),
            sender = senderName,
            content = savedPath,
            type = com.campusmesh.android.features.file.FileUtils.messageTypeForMime(manifest.mimeType),
            senderPeerID = manifest.fromPeerID,
            timestamp = Date(),
            isPrivate = manifest.isPrivate,
            recipientNickname = if (manifest.isPrivate) getMyNickname() else null
        )
        Log.d(TAG, "✅ Chunked transfer ${manifest.transferId.take(12)} complete: ${manifest.fileName} -> $savedPath")
        onIncomingMessage?.invoke(message)
    }

    // ============ RESUME ON RECONNECT ============

    /**
     * Call when [peerID] (re)appears in the connected peer list. Checks both directions:
     * receiver-side, ask the reconnected peer for whatever we're still missing from them; sender
     * side, re-announce (cheap: just the Init) any transfer to them that never finished, in case
     * they lost the original Init entirely and have no session to resume on their own.
     */
    fun onPeerReachable(peerID: String) {
        try {
            inDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val manifest = readIncomingMeta(dir) ?: return@forEach
                if (manifest.fromPeerID != peerID) return@forEach
                val missing = missingIndices(dir, manifest.totalChunks)
                if (missing.isNotEmpty()) {
                    Log.d(TAG, "Resuming incoming ${manifest.transferId.take(12)} from reconnected ${peerID.take(8)}: ${missing.size}/${manifest.totalChunks} missing")
                    sendRequest(manifest, missing)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onPeerReachable incoming resume check failed: ${e.message}")
        }
        try {
            outDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
                val manifest = try { gson.fromJson(f.readText(), OutgoingManifest::class.java) } catch (_: Exception) { null } ?: return@forEach
                if (manifest.completed || manifest.toPeerID != peerID) return@forEach
                Log.d(TAG, "Re-announcing incomplete outgoing ${manifest.transferId.take(12)} to reconnected ${peerID.take(8)}")
                sendPseudoPacket(FileChunkProtocol.buildInitPacket(toInit(manifest)), manifest.toPeerID)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onPeerReachable outgoing resume check failed: ${e.message}")
        }
    }

    // ============ persistence helpers ============

    private fun toInit(m: OutgoingManifest) = FileChunkProtocol.Init(
        transferId = m.transferId,
        fileName = m.fileName,
        realMimeType = m.mimeType,
        fileSize = m.fileSize,
        chunkSize = m.chunkSize,
        totalChunks = m.totalChunks
    )

    private fun readIncomingMeta(dir: File): IncomingManifest? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try { gson.fromJson(metaFile.readText(), IncomingManifest::class.java) } catch (_: Exception) { null }
    }

    private fun persistOutgoing(m: OutgoingManifest) {
        try { File(outDir, "${safeName(m.transferId)}.json").writeText(gson.toJson(m)) } catch (_: Exception) { }
    }

    private fun loadOutgoing(transferId: String): OutgoingManifest? {
        val f = File(outDir, "${safeName(transferId)}.json")
        if (!f.exists()) return null
        return try { gson.fromJson(f.readText(), OutgoingManifest::class.java) } catch (_: Exception) { null }
    }

    private fun markOutgoingCompleted(transferId: String) {
        val m = loadOutgoing(transferId) ?: return
        try { File(outDir, "${safeName(transferId)}.json").writeText(gson.toJson(m.copy(completed = true))) } catch (_: Exception) { }
    }

    private fun deleteOutgoingManifest(transferId: String) {
        try { File(outDir, "${safeName(transferId)}.json").delete() } catch (_: Exception) { }
    }

    private fun receivedBitsFile(dir: File) = File(dir, "received.bits")

    private fun markReceived(dir: File, index: Int, totalChunks: Int) {
        val bitsFile = receivedBitsFile(dir)
        val bytesNeeded = (totalChunks + 7) / 8
        val bits = if (bitsFile.exists()) bitsFile.readBytes().copyOf(bytesNeeded) else ByteArray(bytesNeeded)
        val byteIdx = index / 8
        val bitIdx = index % 8
        if (byteIdx in bits.indices) {
            bits[byteIdx] = (bits[byteIdx].toInt() or (1 shl bitIdx)).toByte()
        }
        try { bitsFile.writeBytes(bits) } catch (_: Exception) { }
    }

    private fun missingIndices(dir: File, totalChunks: Int): List<Int> {
        val bitsFile = receivedBitsFile(dir)
        val bytesNeeded = (totalChunks + 7) / 8
        val bits = if (bitsFile.exists()) bitsFile.readBytes().copyOf(bytesNeeded) else ByteArray(bytesNeeded)
        val missing = mutableListOf<Int>()
        for (index in 0 until totalChunks) {
            val byteIdx = index / 8
            val bitIdx = index % 8
            val has = byteIdx < bits.size && (bits[byteIdx].toInt() shr bitIdx) and 1 == 1
            if (!has) missing.add(index)
        }
        return missing
    }

    private fun safeName(id: String): String = id.filter { it.isLetterOrDigit() }.take(64).ifBlank { "unknown" }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
