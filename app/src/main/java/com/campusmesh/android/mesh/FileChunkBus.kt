package com.campusmesh.android.mesh

import com.campusmesh.android.model.FileChunkProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * A chunk-protocol sub-message ([FileChunkProtocol.Parsed]) received from [peerID].
 *
 * [MessageHandler] lives below [MeshService] (it's shared by both BluetoothMeshService and
 * MeshCore/WifiAwareMeshService) and has no reference to a [MeshService] it could use to send a
 * reply -- but replying (requesting missing chunks, resending them) needs exactly that. Routing
 * through this bus lets MessageHandler stay a thin, low-risk detector while
 * [com.campusmesh.android.mesh.FileTransferManager] (constructed with full MeshService access,
 * alongside the rest of ChatViewModel's managers) does the actual session/resume logic.
 */
data class IncomingChunkEvent(
    val peerID: String,
    val isPrivate: Boolean,
    val parsed: FileChunkProtocol.Parsed
)

object FileChunkBus {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<IncomingChunkEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<IncomingChunkEvent> = _events

    fun emit(event: IncomingChunkEvent) {
        scope.launch { _events.emit(event) }
    }
}
