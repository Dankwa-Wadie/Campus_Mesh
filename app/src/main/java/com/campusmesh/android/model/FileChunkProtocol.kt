package com.campusmesh.android.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chunked, resumable general-file-transfer sub-protocol.
 *
 * Deliberately layered on top of the existing [BitchatFilePacket] TLV format instead of a new
 * wire-level [com.campusmesh.android.protocol.MessageType] -- that lets it reuse the
 * already-working, already-dual-transport send/receive/sign/fragment pipeline
 * ([com.campusmesh.android.mesh.MeshService.sendFileBroadcast]/[com.campusmesh.android.mesh.MeshService.sendFilePrivate],
 * `MessageType.FILE_TRANSFER`) instead of re-plumbing BluetoothMeshService/WifiAwareMeshService/
 * UnifiedMeshService for a brand new packet type. Sub-messages are ordinary [BitchatFilePacket]s
 * whose [BitchatFilePacket.mimeType] is one of the reserved marker strings below; the real payload
 * (transfer id, chunk index, chunk bytes, etc.) lives in [BitchatFilePacket.content].
 *
 * Split rationale: a whole file sent as a single [BitchatFilePacket] (the pre-existing path, still
 * used for small files) is one big packet that only gets split at the *transport* fragmentation
 * layer -- if the connection drops mid-send, nothing is checkpointed and the whole thing has to be
 * resent from scratch. This protocol instead splits a large file into independently-complete
 * [CHUNK_SIZE] pieces, each its own full packet. The receiver writes each chunk to disk as it
 * lands and persists which indices it has, so if the same two devices reconnect later, the
 * receiver can ask for only the chunks it's still missing via [Request] instead of restarting.
 */
object FileChunkProtocol {
    /** Files at or below this size keep using the original single-packet path unchanged. */
    const val CHUNK_THRESHOLD = 96 * 1024

    /** Size of each chunk for files above [CHUNK_THRESHOLD]. */
    const val CHUNK_SIZE = 16 * 1024

    const val MIME_INIT = "application/x-campusmesh-chunk-init"
    const val MIME_CHUNK = "application/x-campusmesh-chunk-data"
    const val MIME_REQUEST = "application/x-campusmesh-chunk-request"

    /** Sent once at the start of a chunked transfer, describing what's coming. */
    data class Init(
        val transferId: String, // hex SHA-256 of the full file content; also used as the final integrity check
        val fileName: String,
        val realMimeType: String,
        val fileSize: Long,
        val chunkSize: Int,
        val totalChunks: Int
    )

    /** One piece of the file. */
    data class Chunk(
        val transferId: String,
        val chunkIndex: Int,
        val data: ByteArray
    )

    /** Sent by a receiver (proactively, on reconnect, or right after an Init) to ask for the chunks it's missing. */
    data class Request(
        val transferId: String,
        val missing: List<Int>
    )

    sealed class Parsed {
        data class InitMsg(val init: Init) : Parsed()
        data class ChunkMsg(val chunk: Chunk) : Parsed()
        data class RequestMsg(val request: Request) : Parsed()
    }

    /** Returns null if [file] isn't one of this protocol's sub-messages (i.e. it's a normal file/image/voice note). */
    fun tryParse(file: BitchatFilePacket): Parsed? {
        return try {
            when (file.mimeType) {
                MIME_INIT -> decodeInit(file.content)?.let { Parsed.InitMsg(it) }
                MIME_CHUNK -> decodeChunk(file.content)?.let { Parsed.ChunkMsg(it) }
                MIME_REQUEST -> decodeRequest(file.content)?.let { Parsed.RequestMsg(it) }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun buildInitPacket(init: Init): BitchatFilePacket =
        BitchatFilePacket(fileName = init.fileName, fileSize = init.fileSize, mimeType = MIME_INIT, content = encodeInit(init))

    fun buildChunkPacket(chunk: Chunk): BitchatFilePacket =
        BitchatFilePacket(fileName = "", fileSize = chunk.data.size.toLong(), mimeType = MIME_CHUNK, content = encodeChunk(chunk))

    fun buildRequestPacket(request: Request): BitchatFilePacket =
        BitchatFilePacket(fileName = "", fileSize = 0, mimeType = MIME_REQUEST, content = encodeRequest(request))

    // --- wire format: simple length-prefixed fields, big-endian ---

    private fun ByteBuffer.putStr(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        putShort(b.size.toShort())
        put(b)
    }

    private fun ByteBuffer.getStr(): String {
        val len = short.toInt() and 0xFFFF
        val b = ByteArray(len)
        get(b)
        return String(b, Charsets.UTF_8)
    }

    private fun encodeInit(i: Init): ByteArray {
        val idBytes = i.transferId.toByteArray(Charsets.UTF_8)
        val nameBytes = i.fileName.toByteArray(Charsets.UTF_8)
        val mimeBytes = i.realMimeType.toByteArray(Charsets.UTF_8)
        val size = (2 + idBytes.size) + (2 + nameBytes.size) + (2 + mimeBytes.size) + 8 + 4 + 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putStr(i.transferId)
        buf.putStr(i.fileName)
        buf.putStr(i.realMimeType)
        buf.putLong(i.fileSize)
        buf.putInt(i.chunkSize)
        buf.putInt(i.totalChunks)
        return buf.array()
    }

    private fun decodeInit(b: ByteArray): Init? = try {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val id = buf.getStr()
        val name = buf.getStr()
        val mime = buf.getStr()
        val size = buf.long
        val chunkSize = buf.int
        val total = buf.int
        if (id.isBlank() || total <= 0 || chunkSize <= 0) null else Init(id, name, mime, size, chunkSize, total)
    } catch (_: Exception) {
        null
    }

    private fun encodeChunk(c: Chunk): ByteArray {
        val idBytes = c.transferId.toByteArray(Charsets.UTF_8)
        val size = (2 + idBytes.size) + 4 + 4 + c.data.size
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putStr(c.transferId)
        buf.putInt(c.chunkIndex)
        buf.putInt(c.data.size)
        buf.put(c.data)
        return buf.array()
    }

    private fun decodeChunk(b: ByteArray): Chunk? = try {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val id = buf.getStr()
        val idx = buf.int
        val len = buf.int
        if (id.isBlank() || idx < 0 || len < 0 || len > buf.remaining()) null else {
            val data = ByteArray(len)
            buf.get(data)
            Chunk(id, idx, data)
        }
    } catch (_: Exception) {
        null
    }

    private fun encodeRequest(r: Request): ByteArray {
        val idBytes = r.transferId.toByteArray(Charsets.UTF_8)
        val size = (2 + idBytes.size) + 4 + r.missing.size * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buf.putStr(r.transferId)
        buf.putInt(r.missing.size)
        r.missing.forEach { buf.putInt(it) }
        return buf.array()
    }

    private fun decodeRequest(b: ByteArray): Request? = try {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val id = buf.getStr()
        val count = buf.int
        if (id.isBlank() || count < 0 || count > buf.remaining() / 4) null else {
            val missing = (0 until count).map { buf.int }
            Request(id, missing)
        }
    } catch (_: Exception) {
        null
    }
}
