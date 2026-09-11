package com.campusmesh.android.mesh

import android.util.Log
import com.campusmesh.android.crypto.EncryptionService
import com.campusmesh.android.protocol.BitchatPacket
import com.campusmesh.android.protocol.MessageType
import com.campusmesh.android.protocol.SpecialRecipients
import com.campusmesh.android.model.RoutedPacket
import com.campusmesh.android.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.mutableSetOf

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class SecurityManager(private val encryptionService: EncryptionService, private val myPeerID: String) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = com.campusmesh.android.util.AppConstants.Security.MESSAGE_TIMEOUT_MS // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = com.campusmesh.android.util.AppConstants.Security.CLEANUP_INTERVAL_MS // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = com.campusmesh.android.util.AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = com.campusmesh.android.util.AppConstants.Security.MAX_PROCESSED_KEY_EXCHANGES

        /** Public-channel flood control: at most this many broadcasts per sender per window. */
        const val PUBLIC_BROADCAST_LIMIT = 3
        const val PUBLIC_BROADCAST_WINDOW_MS = 10_000L
    }
    
    // Public-broadcast send times per authenticated sender, for flood control.
    private val publicBroadcastTimes = Collections.synchronizedMap(mutableMapOf<String, ArrayDeque<Long>>())

    // Security tracking
    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedKeyExchanges = Collections.synchronizedSet(mutableSetOf<String>())
    private val messageTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    
    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Validate packet security (timestamp, replay attacks, duplicates, signatures)
     */
    fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Skipping validation for our own packet")
            return false
        }
        
        // Replay attack protection (same 5-minute window as iOS)
        val currentTime = System.currentTimeMillis()
        val messageType = MessageType.fromValue(packet.type)

        // Duplicate detection
        val messageID = generateMessageID(packet, peerID)
        
        if (processedMessages.contains(messageID)) {
            // Check for ANNOUNCE exception: allow if it looks like a direct neighbor (max TTL)
            // This ensures we catch the "first announce" on a new connection for binding,
            // while still dropping looped/relayed duplicates.
            val isFreshAnnounce = messageType == MessageType.ANNOUNCE &&
                    packet.ttl >= com.campusmesh.android.util.AppConstants.MESSAGE_TTL_HOPS

            if (!isFreshAnnounce) {
                Log.d(TAG, "Dropping duplicate packet: $messageID")
                return false
            }
            Log.d(TAG, "Allowing duplicate ANNOUNCE from direct neighbor: $messageID")
        }

        // Enforce mandatory signature verification BEFORE the ID is recorded.
        //
        // Recording first made the dedup cache poisonable by anyone within radio range: capture a
        // legitimate packet, flip one byte of its signature, and race it to nearby peers. The
        // forgery is dropped here, but its messageID is already cached - so when the genuine
        // packet arrives moments later it is discarded as a duplicate. That is silent, targeted
        // message suppression requiring no key material at all. Only packets that actually
        // authenticate are allowed to consume a dedup slot.
        if (!verifyPacketSignature(packet, peerID)) {
            Log.w(TAG, "Dropping packet from $peerID due to signature verification failure")
            return false
        }

        // Flood control on public broadcasts, applied only to a sender we have just
        // authenticated. Rate-limiting on an unverified sender ID would let an attacker silence
        // someone else by spoofing their ID and burning their budget - the same ordering mistake
        // that made the dedup cache poisonable.
        //
        // A throttled packet deliberately does NOT consume a dedup slot, so an honest resend once
        // the window clears is still accepted.
        if (isRateLimitedPublicBroadcast(packet, peerID, currentTime)) {
            Log.w(TAG, "Dropping public broadcast from $peerID: exceeded $PUBLIC_BROADCAST_LIMIT per ${PUBLIC_BROADCAST_WINDOW_MS}ms")
            return false
        }

        // Add to processed messages
        processedMessages.add(messageID)
        messageTimestamps[messageID] = currentTime
        
        Log.d(TAG, "Packet validation passed for $peerID, messageID: $messageID")
        return true
    }
    
    /**
     * Handle Noise handshake packet - SIMPLIFIED iOS-compatible version
     * Single handshake type with automatic response handling
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Skip handshakes not addressed to us
        if (packet.recipientID?.toHexString() != myPeerID) {
            Log.d(TAG, "Skipping handshake not addressed to us: $peerID")
            return false
        }
            
        // Skip our own handshake messages
        if (peerID == myPeerID) return false

        if (packet.payload.isEmpty()) {
            Log.w(TAG, "Noise handshake packet has empty payload")
            return false
        }
        
        // Prevent duplicate handshake processing
        val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"
        
        if (processedKeyExchanges.contains(exchangeKey)) {
            Log.d(TAG, "Already processed handshake: $exchangeKey")
            return false
        }
        Log.d(TAG, "Processing Noise handshake from $peerID (${packet.payload.size} bytes)")
        processedKeyExchanges.add(exchangeKey)
        
        try {
            // Process the Noise handshake through the updated EncryptionService
            val previousHash = encryptionService.getHandshakeHash(peerID)
            val response = encryptionService.processHandshakeMessage(packet.payload, peerID)
            
            if (response != null) {
                Log.d(TAG, "Successfully processed Noise handshake from $peerID, sending response")
                // Send handshake response through delegate
                delegate?.sendHandshakeResponse(peerID, response)
            }
            // Check if session is now established (handshake complete)
            val currentHash = encryptionService.getHandshakeHash(peerID)
            if (currentHash != null && !currentHash.contentEquals(previousHash)) {
                Log.d(TAG, "✅ Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(peerID, packet.payload)
            }
            return true

            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
            return false
        }
    }

    /**
     * Sign packet payload
     */
    fun signPacket(payload: ByteArray): ByteArray? {
        return try {
            encryptionService.sign(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
            null
        }
    }
    
    /**
     * Encrypt payload for specific peer
     */
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Decrypt payload from specific peer
     */
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
        return try {
            encryptionService.decrypt(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Get combined public key data for key exchange
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }
    
    /**
     * Sliding-window flood check for public (broadcast) chat messages.
     *
     * Only MESSAGE packets addressed to the broadcast recipient count. Private messages, control
     * traffic and handshakes are unaffected — throttling those would break session setup.
     *
     * Note: this limits the *originating sender*. Limiting the forwarding neighbour as well needs
     * `RoutedPacket.relayAddress` plumbed through `PacketProcessorDelegate.validatePacketSecurity`,
     * which currently passes only the sender. Tracked as follow-up.
     */
    private fun isRateLimitedPublicBroadcast(packet: BitchatPacket, peerID: String, now: Long): Boolean {
        if (MessageType.fromValue(packet.type) != MessageType.MESSAGE) return false
        val recipient = packet.recipientID ?: return false
        if (!recipient.contentEquals(SpecialRecipients.BROADCAST)) return false

        synchronized(publicBroadcastTimes) {
            val times = publicBroadcastTimes.getOrPut(peerID) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() >= PUBLIC_BROADCAST_WINDOW_MS) {
                times.removeFirst()
            }
            if (times.size >= PUBLIC_BROADCAST_LIMIT) return true
            times.addLast(now)
            return false
        }
    }

    /**
     * Generate message ID for duplicate detection
     */
    private fun generateMessageID(packet: BitchatPacket, peerID: String): String {
        return when (MessageType.fromValue(packet.type)) {
            MessageType.FRAGMENT -> {
                // For fragments, include the payload hash to distinguish different fragments
                "${packet.timestamp}-$peerID-${packet.type}-${payloadDigest(packet.payload)}"
            }
            else -> {
                // For other messages, use a truncated payload hash
                val payloadHash = payloadDigest(packet.payload.sliceArray(0 until minOf(64, packet.payload.size)))
                "${packet.timestamp}-$peerID-$payloadHash"
            }
        }
    }

    /**
     * Collision-resistant digest of a payload, for duplicate detection.
     *
     * This used to be [ByteArray.contentHashCode], i.e. a 32-bit `Arrays.hashCode`. Colliding
     * against that is arithmetic, not cryptanalysis - so an attacker could craft a payload whose
     * ID matched a message they wanted silenced and have the real one dropped as a duplicate.
     * SHA-256 (truncated to 128 bits, which is ample for a 5-minute dedup window) removes that.
     */
    private fun payloadDigest(payload: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
        return digest.sliceArray(0 until 16).joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Verify packet signature using peer's signing public key
     * Returns true only if signature is present and valid
     */
    private fun verifyPacketSignature(packet: BitchatPacket, peerID: String): Boolean {
        try {
            // only verify ANNOUNCE, MESSAGE, and FILE_TRANSFER
            if (MessageType.fromValue(packet.type) !in setOf(
                    MessageType.ANNOUNCE,
                    MessageType.MESSAGE,
                    MessageType.FILE_TRANSFER
                )) {
                return true
            }
            // 1. Mandatory Signature Check
            if (packet.signature == null) {
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return false
            }
            
            // 2. Get Signing Public Key
            var signingPublicKey: ByteArray? = null
            
            if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {
                // Special Case: ANNOUNCE packets carry their own signing key
                try {
                    val announcement = com.campusmesh.android.model.IdentityAnnouncement.decode(packet.payload)
                    signingPublicKey = announcement?.signingPublicKey
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode announcement for key extraction: ${e.message}")
                }
            } else {
                // Standard Case: Get key from known peer info
                val peerInfo = delegate?.getPeerInfo(peerID)
                signingPublicKey = peerInfo?.signingPublicKey
            }
            
            if (signingPublicKey == null) {
                // If we don't have a key (and it's not an announce), we can't verify.
                // For security, we must reject packets from unknown peers unless it's an announce.
                Log.w(TAG, "❌ Signature check for $peerID: NO_SIGNING_KEY_AVAILABLE (packet type ${packet.type})")
                return false
            }
            
            // 3. Get Canonical Data
            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "❌ Signature check for $peerID: ENCODING_ERROR (packet type ${packet.type})")
                return false
            }
            
            // 4. Verify Signature
            val signature = packet.signature!!
            val isSignatureValid = encryptionService.verifyEd25519Signature(
                signature,
                packetDataForSigning,
                signingPublicKey
            )
            
            if (isSignatureValid) {
                // Log.v(TAG, "✅ Signature verified for $peerID (type ${packet.type})")
                return true
            } else {
                Log.w(TAG, "❌ Signature INVALID for $peerID (type ${packet.type})")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Signature verification error for $peerID: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedMessages.size}")
            appendLine("Processed Key Exchanges: ${processedKeyExchanges.size}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")
            
            if (processedKeyExchanges.isNotEmpty()) {
                appendLine("Key Exchange History:")
                processedKeyExchanges.take(10).forEach { exchange ->
                    appendLine("  - $exchange")
                }
                if (processedKeyExchanges.size > 10) {
                    appendLine("  ... and ${processedKeyExchanges.size - 10} more")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldData()
            }
        }
    }
    
    /**
     * Clean up old processed messages and timestamps
     */
    private fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - MESSAGE_TIMEOUT
        var removedCount = 0
        
        // Clean up old message timestamps and corresponding processed messages
        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }
        
        messagesToRemove.forEach { messageId ->
            messageTimestamps.remove(messageId)
            if (processedMessages.remove(messageId)) {
                removedCount++
            }
        }
        
        // Limit the size of processed messages set
        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val excess = processedMessages.size - MAX_PROCESSED_MESSAGES
            val toRemove = processedMessages.take(excess)
            processedMessages.removeAll(toRemove.toSet())
            removeFromMessageTimestamps(toRemove)
            removedCount += excess
        }
        
        // Drop flood-control buckets for senders that have gone quiet, so the map does not grow
        // unbounded with every peer ever seen.
        synchronized(publicBroadcastTimes) {
            val now = System.currentTimeMillis()
            val idle = publicBroadcastTimes.filter { (_, times) ->
                times.isEmpty() || now - times.last() >= PUBLIC_BROADCAST_WINDOW_MS
            }.keys.toList()
            idle.forEach { publicBroadcastTimes.remove(it) }
        }

        // Limit the size of processed key exchanges set
        if (processedKeyExchanges.size > MAX_PROCESSED_KEY_EXCHANGES) {
            val excess = processedKeyExchanges.size - MAX_PROCESSED_KEY_EXCHANGES
            val toRemove = processedKeyExchanges.take(excess)
            processedKeyExchanges.removeAll(toRemove.toSet())
        }
        
        if (removedCount > 0) {
            Log.d(TAG, "Cleaned up $removedCount old processed messages")
        }
    }
    
    /**
     * Helper to remove entries from messageTimestamps
     */
    private fun removeFromMessageTimestamps(messageIds: List<String>) {
        messageIds.forEach { messageId ->
            messageTimestamps.remove(messageId)
        }
    }
    
    /**
     * Clear all security data
     */
    fun clearAllData() {
        processedMessages.clear()
        processedKeyExchanges.clear()
        messageTimestamps.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray)
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo? // NEW: For signature verification
}
