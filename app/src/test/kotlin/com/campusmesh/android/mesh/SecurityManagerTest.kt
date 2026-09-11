package com.campusmesh.android.mesh

import android.os.Build
import com.campusmesh.android.crypto.EncryptionService
import com.campusmesh.android.model.IdentityAnnouncement
import com.campusmesh.android.protocol.BitchatPacket
import com.campusmesh.android.protocol.MessageType
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class SecurityManagerTest {

    private lateinit var securityManager: SecurityManager
    private lateinit var fakeEncryptionService: FakeEncryptionService
    private lateinit var mockDelegate: SecurityManagerDelegate
    
    private val myPeerID = "1111222233334444"
    private val otherPeerID = "aaaabbbbccccdddd"
    private val unknownPeerID = "9999888877776666"

    private val dummyPayload = "Hello World".toByteArray()
    private val validSignature = ByteArray(64) { 1 }
    private val invalidSignature = ByteArray(64) { 0 }
    
    // Key pairs (using dummy bytes for mock verification)
    private val otherSigningKey = ByteArray(32) { 0xA }
    private val otherNoiseKey = ByteArray(32) { 0xB }

    // Fake implementation to bypass initialization issues in tests
    open class FakeEncryptionService : EncryptionService(RuntimeEnvironment.getApplication()) {
        var shouldVerify: Boolean = true
        var lastVerifySignature: ByteArray? = null
        var lastVerifyKey: ByteArray? = null

        override fun initialize() {
            // Do nothing to avoid KeyStore access in tests
        }

        override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
            lastVerifySignature = signature
            lastVerifyKey = publicKeyBytes
            
            // Simple logic: if configured to verify, check if signature matches validSignature
            // We use the signature bytes passed in setup()
            if (shouldVerify) {
                 return signature.contentEquals(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1))
            }
            return false
        }
    }

    @Before
    fun setup() {
        fakeEncryptionService = FakeEncryptionService()
        mockDelegate = mock()
        
        securityManager = SecurityManager(fakeEncryptionService, myPeerID)
        securityManager.delegate = mockDelegate
    }

    @After
    fun tearDown() {
        if (::securityManager.isInitialized) {
            securityManager.shutdown()
        }
    }

    @Test
    fun `validatePacket - rejects packet with missing signature`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = null

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertFalse("Packet without signature should be rejected", result)
    }

    @Test
    fun `validatePacket - rejects packet with invalid signature`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = invalidSignature

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertFalse("Packet with invalid signature should be rejected", result)
    }

    @Test
    fun `validatePacket - rejects packet from unknown peer (no key)`() {
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertFalse("Packet from unknown peer should be rejected (cannot verify signature)", result)
    }

    @Test
    fun `validatePacket - accepts packet with valid signature from known peer`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, otherPeerID)
        
        assertTrue("Valid signed packet from known peer should be accepted", result)
    }

    @Test
    fun `validatePacket - accepts ANNOUNCE packet from unknown peer (extracts key)`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = payload
        )
        packet.signature = validSignature

        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)
        
        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertTrue("ANNOUNCE from unknown peer should be accepted (key extracted from payload)", result)
        // Verify we used the correct key
        assertTrue("Should have used extracted key for verification", 
            fakeEncryptionService.lastVerifyKey.contentEquals(otherSigningKey))
    }

    @Test
    fun `validatePacket - rejects ANNOUNCE packet with invalid signature`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = payload
        )
        packet.signature = invalidSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertFalse("ANNOUNCE with invalid signature should be rejected", result)
    }
    
    @Test
    fun `validatePacket - rejects ANNOUNCE packet with malformed payload`() {
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 10u,
            senderID = unknownPeerID,
            payload = byteArrayOf(0x00, 0x01, 0x02)
        )
        packet.signature = validSignature

        val result = securityManager.validatePacket(packet, unknownPeerID)
        
        assertFalse("ANNOUNCE with malformed payload should be rejected (cannot extract key)", result)
    }

    @Test
    fun `validatePacket - ignores own packets`() {
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = myPeerID,
            payload = dummyPayload
        )
        packet.signature = null

        val result = securityManager.validatePacket(packet, myPeerID)
        
        assertFalse("Own packets should return false (skipped)", result)
    }
    
    @Test
    fun `validatePacket - detects duplicates`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = otherPeerID,
            payload = dummyPayload
        )
        packet.signature = validSignature

        val result1 = securityManager.validatePacket(packet, otherPeerID)
        assertTrue("First packet should be accepted", result1)

        val result2 = securityManager.validatePacket(packet, otherPeerID)
        assertFalse("Duplicate packet should be rejected", result2)
    }

    @Test
    fun `validatePacket - handles ANNOUNCE duplicates correctly`() {
        val announcement = IdentityAnnouncement(
            nickname = "New User",
            noisePublicKey = otherNoiseKey,
            signingPublicKey = otherSigningKey
        )
        val payload = announcement.encode()!!
        
        // 1. Initial Announce (Fresh)
        val packet1 = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = com.campusmesh.android.util.AppConstants.MESSAGE_TTL_HOPS, // 7u
            senderID = unknownPeerID,
            payload = payload
        )
        packet1.signature = validSignature
        
        whenever(mockDelegate.getPeerInfo(unknownPeerID)).thenReturn(null)

        assertTrue("First ANNOUNCE should be accepted", securityManager.validatePacket(packet1, unknownPeerID))
        
        // 2. Relayed Duplicate (Lower TTL)
        val packet2 = packet1.copy(ttl = (com.campusmesh.android.util.AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte())
        assertFalse("Relayed duplicate ANNOUNCE should be rejected", securityManager.validatePacket(packet2, unknownPeerID))
        
        // 3. Direct Duplicate (Max TTL)
        val packet3 = packet1.copy(ttl = com.campusmesh.android.util.AppConstants.MESSAGE_TTL_HOPS)
        assertTrue("Fresh duplicate ANNOUNCE should be accepted", securityManager.validatePacket(packet3, unknownPeerID))
    }

    /**
     * Regression test: the dedup cache must not be poisonable by an unauthenticated packet.
     *
     * validatePacket used to record the messageID before verifying the signature, so an attacker
     * within radio range could capture a legitimate packet, corrupt one byte of its signature and
     * race it ahead of the real one. The forgery was dropped, but its ID was already cached - and
     * the genuine packet that followed was then silently discarded as a duplicate.
     */
    @Test
    fun `validatePacket - rejected forgery does not suppress the genuine packet`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        val timestamp = 1_700_000_000_000UL

        // Attacker replays the same sender/timestamp/payload with a broken signature.
        val forged = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            timestamp = timestamp,
            payload = dummyPayload,
            ttl = 10u
        )
        forged.signature = invalidSignature
        assertFalse("Forged packet must be rejected", securityManager.validatePacket(forged, otherPeerID))

        // The real packet - identical content, valid signature - must still get through.
        val genuine = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            timestamp = timestamp,
            payload = dummyPayload,
            ttl = 10u
        )
        genuine.signature = validSignature

        assertTrue(
            "Genuine packet must not be suppressed by the earlier forgery",
            securityManager.validatePacket(genuine, otherPeerID)
        )
    }

    /** Dedup itself must still work: a replay of an already-accepted packet is dropped. */
    @Test
    fun `validatePacket - rejects replay of an already accepted packet`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        val timestamp = 1_700_000_000_001UL
        fun packet() = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            timestamp = timestamp,
            payload = dummyPayload,
            ttl = 10u
        ).apply { signature = validSignature }

        assertTrue("First delivery should be accepted", securityManager.validatePacket(packet(), otherPeerID))
        assertFalse("Replay should be dropped as duplicate", securityManager.validatePacket(packet(), otherPeerID))
    }

    /**
     * Message IDs are derived from a SHA-256 digest rather than ByteArray.contentHashCode(), whose
     * 32-bit output can be collided by construction - which would let an attacker craft a payload
     * that evicts a message they want silenced.
     */
    @Test
    fun `validatePacket - distinct payloads are not treated as duplicates`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        val timestamp = 1_700_000_000_002UL
        fun packetWith(payload: ByteArray) = BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
            timestamp = timestamp,
            payload = payload,
            ttl = 10u
        ).apply { signature = validSignature }

        assertTrue(securityManager.validatePacket(packetWith("first".toByteArray()), otherPeerID))
        assertTrue(
            "A different payload at the same timestamp is a different message",
            securityManager.validatePacket(packetWith("second".toByteArray()), otherPeerID)
        )
    }

    // --- public broadcast flood control ---

    private fun broadcastPacket(payload: String, timestamp: ULong) = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
        recipientID = com.campusmesh.android.protocol.SpecialRecipients.BROADCAST,
        timestamp = timestamp,
        payload = payload.toByteArray(),
        ttl = 10u
    ).apply { signature = validSignature }

    @Test
    fun `public broadcasts are throttled past the per-sender limit`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        repeat(SecurityManager.PUBLIC_BROADCAST_LIMIT) { i ->
            assertTrue(
                "Broadcast ${i + 1} should be within the limit",
                securityManager.validatePacket(broadcastPacket("msg-$i", (2_000_000L + i).toULong()), otherPeerID)
            )
        }

        assertFalse(
            "The broadcast after the limit should be dropped",
            securityManager.validatePacket(broadcastPacket("over", 2_000_099UL), otherPeerID)
        )
    }

    /**
     * A throttled packet must not consume a dedup slot, or an honest resend after the window
     * clears would be discarded as a duplicate.
     */
    @Test
    fun `a throttled broadcast can be resent once the window clears`() {
        setupKnownPeer(otherPeerID, otherSigningKey)
        repeat(SecurityManager.PUBLIC_BROADCAST_LIMIT) { i ->
            securityManager.validatePacket(broadcastPacket("fill-$i", (3_000_000L + i).toULong()), otherPeerID)
        }

        val throttled = broadcastPacket("important", 3_000_099UL)
        assertFalse("Should be throttled", securityManager.validatePacket(throttled, otherPeerID))

        Thread.sleep(SecurityManager.PUBLIC_BROADCAST_WINDOW_MS + 200)

        assertTrue(
            "The same message must be accepted once the window has passed",
            securityManager.validatePacket(broadcastPacket("important", 3_000_099UL), otherPeerID)
        )
    }

    /** Private messages and control traffic must never be throttled — that would break sessions. */
    @Test
    fun `direct messages are not throttled`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        repeat(SecurityManager.PUBLIC_BROADCAST_LIMIT * 3) { i ->
            val dm = BitchatPacket(
                type = MessageType.MESSAGE.value,
                senderID = MeshPacketUtils.hexStringToByteArray(otherPeerID),
                recipientID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                timestamp = (4_000_000L + i).toULong(),
                payload = "dm-$i".toByteArray(),
                ttl = 10u
            ).apply { signature = validSignature }
            assertTrue("Direct message ${i + 1} must not be throttled",
                securityManager.validatePacket(dm, otherPeerID))
        }
    }

    /**
     * Flood control runs only after the signature verifies. Otherwise an attacker could spoof a
     * victim's sender ID, burn their budget with unsigned packets, and silence them.
     */
    @Test
    fun `unsigned packets cannot burn another peer's broadcast budget`() {
        setupKnownPeer(otherPeerID, otherSigningKey)

        repeat(SecurityManager.PUBLIC_BROADCAST_LIMIT * 2) { i ->
            val forged = broadcastPacket("spoof-$i", (5_000_000L + i).toULong())
            forged.signature = invalidSignature
            assertFalse("Forged packet must be rejected", securityManager.validatePacket(forged, otherPeerID))
        }

        assertTrue(
            "The real peer must still be able to broadcast after the spoofing attempt",
            securityManager.validatePacket(broadcastPacket("genuine", 5_000_099UL), otherPeerID)
        )
    }

    private fun setupKnownPeer(peerID: String, signingKey: ByteArray) {
        val info = PeerInfo(
            id = peerID,
            nickname = "Test User",
            isConnected = true,
            isDirectConnection = true,
            noisePublicKey = ByteArray(32),
            signingPublicKey = signingKey,
            isVerifiedNickname = false,
            lastSeen = System.currentTimeMillis()
        )
        whenever(mockDelegate.getPeerInfo(peerID)).thenReturn(info)
    }
}
