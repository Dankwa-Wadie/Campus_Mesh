package com.campusmesh.android.model

import org.junit.Assert.*
import org.junit.Test

class FileChunkProtocolTest {
    private val init = FileChunkProtocol.Init("a".repeat(64), "file.bin", "application/octet-stream", 20000, 16384, 2)

    @Test fun `reject unreasonable or inconsistent metadata on wire`() {
        val invalid = listOf(
            init.copy(fileSize = Long.MAX_VALUE), init.copy(fileSize = -1),
            init.copy(totalChunks = Int.MAX_VALUE), init.copy(totalChunks = 3),
            init.copy(chunkSize = Int.MAX_VALUE), init.copy(transferId = "../bad")
        )
        for (value in invalid) assertNull(FileChunkProtocol.tryParse(FileChunkProtocol.buildInitPacket(value)))
        assertNotNull(FileChunkProtocol.tryParse(FileChunkProtocol.buildInitPacket(init)))
    }

    @Test fun `validate last chunk size and reject writes outside transfer`() {
        assertTrue(FileChunkProtocol.isValidChunk(FileChunkProtocol.Chunk(init.transferId, 1, ByteArray(3616)), init))
        assertFalse(FileChunkProtocol.isValidChunk(FileChunkProtocol.Chunk(init.transferId, 2, ByteArray(1)), init))
        assertFalse(FileChunkProtocol.isValidChunk(FileChunkProtocol.Chunk(init.transferId, Int.MAX_VALUE, ByteArray(1)), init))
        assertFalse(FileChunkProtocol.isValidChunk(FileChunkProtocol.Chunk(init.transferId, 1, ByteArray(16384)), init))
    }

    @Test fun `reject oversized and negative retry requests`() {
        for (missing in listOf(listOf(-1), listOf(Int.MAX_VALUE), List(4097) { 0 })) {
            assertNull(FileChunkProtocol.tryParse(FileChunkProtocol.buildRequestPacket(FileChunkProtocol.Request(init.transferId, missing))))
        }
    }
}
