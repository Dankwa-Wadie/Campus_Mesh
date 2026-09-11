package com.campusmesh.android.net

import android.content.Context
import android.util.Log
import com.campusmesh.android.model.RoutedPacket
import com.campusmesh.android.protocol.BitchatPacket
import com.campusmesh.android.protocol.MessageType
import com.campusmesh.android.protocol.SpecialRecipients
import com.campusmesh.android.service.TransportBridgeService
import com.campusmesh.android.util.AppConstants
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.close
import io.ktor.websocket.CloseReason
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Embedded Ktor Server running on port 8080.
 * Serves the Web PWA client to iPhones and bridges WebSocket connections into the mesh network.
 */
object KtorGatewayManager : TransportBridgeService.TransportLayer {
    private const val TAG = "KtorGatewayManager"
    private const val PORT = 8080

    private var server: NettyApplicationEngine? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Default)

    // Active WebSocket sessions mapping: Session -> ClientInfo
    private val webSessions = ConcurrentHashMap<io.ktor.websocket.WebSocketSession, String>() // Session -> Peer ID

    private lateinit var appContext: Context

    fun start(context: Context) {
        if (server != null) {
            Log.d(TAG, "Ktor Server is already running.")
            return
        }
        appContext = context.applicationContext
        Log.i(TAG, "🚀 Starting Ktor Server on port $PORT...")

        // Port 8080 may already be taken by another app; an unhandled BindException here would
        // take down whatever called start().
        try {
            server = embeddedServer(Netty, port = PORT) {
                configurePlugins()
                configureRouting()
            }.start(wait = false)
        } catch (e: Exception) {
            server = null
            Log.e(TAG, "❌ Failed to start Ktor Server on port $PORT: ${e.message}", e)
            return
        }

        // Register as a transport layer in the mesh bridge
        TransportBridgeService.register("KTOR", this)
        Log.i(TAG, "✅ Ktor Server started and registered as 'KTOR' transport layer.")
    }

    /**
     * Closes every live WebSocket session, without stopping the server.
     *
     * The pairing token is only checked when a socket is opened, so rotating it does nothing to
     * clients that are already connected - they would keep receiving relayed mesh traffic on a
     * credential the user just revoked. Revocation has to hang up on them too.
     */
    fun disconnectAllClients() {
        val sessions = webSessions.keys.toList()
        webSessions.clear()
        if (sessions.isEmpty()) return
        Log.i(TAG, "🔌 Disconnecting ${sessions.size} web client(s) after pairing reset")
        scope.launch {
            sessions.forEach { session ->
                try {
                    session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pairing reset"))
                } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        if (server == null) return
        Log.i(TAG, "Stopping Ktor Server...")
        
        // Close all active web sessions
        scope.launch {
            webSessions.keys.forEach { session ->
                try {
                    session.close()
                } catch (_: Exception) {}
            }
            webSessions.clear()
        }

        TransportBridgeService.unregister("KTOR")
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "🛑 Ktor Server stopped.")
    }

    private fun Application.configurePlugins() {
        install(WebSockets)
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
            }
        }
    }

    /**
     * Rejects the call with 401 unless it carries a valid pairing token, returning whether it may
     * proceed. Static PWA assets stay open - the client has to load the app before it can present
     * a token - but anything carrying mesh or build data goes through here.
     */
    private suspend fun ApplicationCall.isPaired(): Boolean {
        val token = request.queryParameters["t"]
        if (GatewayAuth.isValid(appContext, token)) return true
        Log.w(TAG, "🚫 Rejected unpaired request to ${request.local.uri}")
        respond(HttpStatusCode.Unauthorized, "Pairing token required")
        return false
    }

    private fun Application.configureRouting() {
        routing {
            // Serve Main PWA landing
            get("/") {
                val html = readAssetString("web_pwa/index.html")
                if (html != null) {
                    call.respondText(html, ContentType.Text.Html)
                } else {
                    call.respondText("Campus Mesh PWA not found. Please verify assets.", ContentType.Text.Plain, HttpStatusCode.NotFound)
                }
            }

            // Serve static files (JS, CSS, SVGs)
            get("/{static-file}") {
                val filename = call.parameters["static-file"] ?: ""
                // Path params arrive percent-decoded, so "%2e%2e%2f" reaches us as "../".
                // AssetManager confines reads to the APK either way, but don't hand it traversal.
                if (filename.contains("..") || filename.contains('/') || filename.contains('\\')) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val content = readAssetString("web_pwa/$filename")
                if (content != null) {
                    val contentType = when {
                        filename.endsWith(".js") -> ContentType.Application.JavaScript
                        filename.endsWith(".css") -> ContentType.Text.CSS
                        filename.endsWith(".svg") -> ContentType.Image.SVG
                        filename.endsWith(".html") || filename.endsWith(".htm") -> ContentType.Text.Html
                        else -> ContentType.Text.Plain
                    }
                    call.respondText(content, contentType)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // Serve offline OpenStreetMap tiles from assets
            get("/tiles/{z}/{x}/{y}.png") {
                val z = call.parameters["z"] ?: ""
                val x = call.parameters["x"] ?: ""
                val y = call.parameters["y"] ?: ""
                var bytes = readAssetBytes("web_pwa/tiles/$z/$x/$y.png")
                if (bytes == null) {
                    // Fallback to default dark-grid tile to ensure maps always render
                    bytes = readAssetBytes("web_pwa/tiles/tile.png")
                }
                if (bytes != null) {
                    call.respondBytes(bytes, ContentType.Image.PNG)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // Serve OTA version information
            get("/api/ota/info") {
                if (!call.isPaired()) return@get
                val ota = OtaUpdateManager(appContext)
                val info = mapOf(
                    "versionCode" to ota.getCurrentVersionCode(),
                    "versionHash" to ota.getCurrentVersionHash(),
                    "chunkSize" to OtaUpdateManager.CHUNK_SIZE,
                    "totalChunks" to ota.getChunkCount(),
                    "apkSize" to (ota.getLocalApkFile()?.length() ?: 0L)
                )
                call.respond(info)
            }

            // Serve individual OTA chunks
            get("/api/ota/chunk/{index}") {
                if (!call.isPaired()) return@get
                val ota = OtaUpdateManager(appContext)
                val indexStr = call.parameters["index"] ?: ""
                val index = indexStr.toIntOrNull()
                if (index != null) {
                    val bytes = ota.getApkChunk(index)
                    if (bytes != null) {
                        call.respondBytes(bytes, ContentType.Application.OctetStream)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Chunk not found or out of bounds")
                    }
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Invalid chunk index")
                }
            }

            // WebSockets endpoint for P2P bridging
            webSocket("/chat-ws") {
                // Authenticate before this session can send or receive anything. Without it, any
                // device on the same Wi-Fi could open this socket and passively read every mesh
                // message relayed through this phone - see GatewayAuth.
                if (!GatewayAuth.isValid(appContext, call.request.queryParameters["t"])) {
                    Log.w(TAG, "🚫 Rejected unpaired WebSocket client (bad or missing token)")
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "pairing required"))
                    return@webSocket
                }

                Log.d(TAG, "🔌 Web client connected via WebSocket.")
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            handleIncomingWebMessage(this, text)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in WebSocket session: ${e.message}")
                } finally {
                    webSessions.remove(this)
                    Log.d(TAG, "🔌 Web client disconnected.")
                }
            }
        }
    }

    private fun handleIncomingWebMessage(session: io.ktor.websocket.WebSocketSession, text: String) {
        try {
            val data = gson.fromJson(text, Map::class.java) as Map<*, *>
            val type = data["type"] as? String ?: return

            when (type) {
                "handshake" -> {
                    val peerID = data["peerID"] as? String ?: "web-unknown"
                    webSessions[session] = peerID
                    Log.d(TAG, "🤝 Received handshake from web client: $peerID")
                    
                    // Reply with confirmation only. This endpoint is reachable by anything on the
                    // same network, so it must not hand out this device's identity fingerprint or
                    // the list of mesh peers around it - that is a deanonymisation primitive for
                    // any unauthenticated LAN client.
                    val response = mapOf(
                        "type" to "handshake_ack",
                        "peerCount" to getConnectedMeshPeers().size
                    )
                    scope.launch {
                        session.send(Frame.Text(gson.toJson(response)))
                    }
                }
                "ghost_mode" -> {
                    val peerID = data["peerID"] as? String ?: webSessions[session] ?: return
                    val enabled = data["enabled"] as? Boolean ?: false
                    Log.i(TAG, "👻 Web peer $peerID set Ghost Mode = $enabled (map broadcast suppression)")
                    // Real peer-location mesh broadcast doesn't exist yet (see AppUpdateNotice-style
                    // packet work needed for that); this just records intent so it's ready once it does.
                }
                "chat_msg" -> {
                    val peerID = webSessions[session] ?: return
                    val content = data["content"] as? String ?: ""
                    val channel = data["channel"] as? String // Optional channel name

                    if (content.isNotEmpty()) {
                        Log.d(TAG, "💬 Web message from $peerID: $content (channel: $channel)")
                        
                        // Construct BitchatPacket to flood into BLE & Wi-Fi Aware mesh
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.MESSAGE.value,
                            senderID = hexStringToByteArray(peerID.removePrefix("web-").take(16).padEnd(16, '0')),
                            recipientID = SpecialRecipients.BROADCAST,
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = content.toByteArray(Charsets.UTF_8),
                            signature = null,
                            ttl = AppConstants.SYNC_TTL_HOPS
                        )

                        // Deliberately NOT signed with this device's Ed25519 identity key.
                        // Anything on the local network can open this socket, so signing its
                        // content here would let a stranger emit packets that the mesh attributes
                        // to this device's identity. Web-bridged traffic goes out unsigned and is
                        // treated as untrusted by receiving peers.
                        TransportBridgeService.broadcast("KTOR", RoutedPacket(packet))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming web message: ${e.message}")
        }
    }

    /**
     * Relays a mesh packet to all active WebSocket clients.
     */
    override fun send(packet: RoutedPacket) {
        val bitchatPacket = packet.packet
        if (bitchatPacket.type == MessageType.MESSAGE.value) {
            scope.launch {
                val content = String(bitchatPacket.payload, Charsets.UTF_8)
                val senderHex = bitchatPacket.senderID.joinToString("") { "%02x".format(it) }
                val channel = if (bitchatPacket.recipientID?.contentEquals(SpecialRecipients.BROADCAST) == true) {
                    "#general" // Standard broadcast channel
                } else null

                val messageData = mapOf(
                    "type" to "chat_msg",
                    "sender" to "peer-$senderHex",
                    "content" to content,
                    "channel" to channel,
                    "timestamp" to bitchatPacket.timestamp.toLong()
                )

                val jsonText = gson.toJson(messageData)
                webSessions.keys.forEach { session ->
                    try {
                        session.send(Frame.Text(jsonText))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun getConnectedMeshPeers(): List<String> {
        return com.campusmesh.android.services.AppStateStore.peers.value
    }

    // --- Helper Utilities ---

    private fun readAssetString(path: String): String? {
        return try {
            appContext.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Asset text file not found: $path")
            null
        }
    }

    private fun readAssetBytes(path: String): ByteArray? {
        return try {
            appContext.assets.open(path).use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        return result
    }
}
