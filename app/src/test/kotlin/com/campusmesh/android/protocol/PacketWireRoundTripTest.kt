package com.campusmesh.android.protocol

import com.campusmesh.android.util.AppConstants
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end check of the path a BLE packet actually takes: sign -> encode -> (wire) -> decode ->
 * verify, including after a relay hop.
 *
 * This is the pipeline that decides whether mesh chat works at all. Unlike the internet/Nostr
 * path, every BLE packet passes SecurityManager.validatePacket, which drops any MESSAGE whose
 * signature does not verify. An asymmetry between encode and decode, or any field that changes in
 * transit but is included in the signed bytes, silently kills mesh messaging while internet chat
 * keeps working.
 */
class PacketWireRoundTripTest {

    private val senderKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val senderPublic: Ed25519PublicKeyParameters = senderKey.generatePublicKey()

    private val senderId = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val recipientId = SpecialRecipients.BROADCAST

    /** Mirrors MeshCore.signPacketBeforeBroadcast: sign over toBinaryDataForSigning(). */
    private fun sign(packet: BitchatPacket): BitchatPacket {
        val data = packet.toBinaryDataForSigning()
        assertNotNull("toBinaryDataForSigning() must not return null for a well-formed packet", data)
        val signature = Ed25519Signer().apply {
            init(true, senderKey)
            update(data!!, 0, data.size)
        }.generateSignature()
        return packet.copy(signature = signature)
    }

    /** Mirrors SecurityManager.verifyPacketSignature. */
    private fun verify(packet: BitchatPacket): Boolean {
        val data = packet.toBinaryDataForSigning() ?: return false
        val signature = packet.signature ?: return false
        return Ed25519Signer().apply {
            init(false, senderPublic)
            update(data, 0, data.size)
        }.verifySignature(signature)
    }

    private fun message(payload: String, ttl: UByte = AppConstants.MESSAGE_TTL_HOPS) = BitchatPacket(
        version = 1u,
        type = MessageType.MESSAGE.value,
        senderID = senderId,
        recipientID = recipientId,
        timestamp = 1_700_000_000_000UL,
        payload = payload.toByteArray(),
        signature = null,
        ttl = ttl
    )

    @Test
    fun `a signed broadcast survives encode and decode`() {
        val signed = sign(message("meet at the library"))

        val wire = BinaryProtocol.encode(signed)
        assertNotNull("Packet must encode", wire)

        val decoded = BitchatPacket.fromBinaryData(wire!!)
        assertNotNull("Encoded packet must decode again", decoded)

        assertEquals(signed.type, decoded!!.type)
        assertArrayEquals(signed.payload, decoded.payload)
        assertArrayEquals(signed.signature, decoded.signature)
        assertTrue(
            "A signature made before encoding must still verify after decoding - if this fails, " +
                "every BLE message is dropped by validatePacket while internet chat keeps working",
            verify(decoded)
        )
    }

    /**
     * TTL is decremented on every hop, so it must not affect the signed bytes. If it does, a
     * message verifies for direct neighbours and fails for everyone one hop further out.
     */
    @Test
    fun `a signature survives a relay hop that decrements TTL`() {
        val signed = sign(message("relayed"))
        val decoded = BitchatPacket.fromBinaryData(BinaryProtocol.encode(signed)!!)!!

        val relayed = decoded.copy(ttl = (decoded.ttl - 1u).toUByte())
        assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), relayed.ttl)

        assertTrue("TTL must be excluded from the signed bytes", verify(relayed))

        // And after a second hop, re-encoded in between as a real relay would.
        val reEncoded = BitchatPacket.fromBinaryData(BinaryProtocol.encode(relayed)!!)!!
        val twoHops = reEncoded.copy(ttl = (reEncoded.ttl - 1u).toUByte())
        assertTrue("Signature must survive repeated relay", verify(twoHops))
    }

    /** Signing bytes must be identical either side of the wire, or nothing verifies. */
    @Test
    fun `signing bytes are stable across encode and decode`() {
        val packet = message("stability")
        val before = packet.toBinaryDataForSigning()
        val after = BitchatPacket.fromBinaryData(BinaryProtocol.encode(packet)!!)!!.toBinaryDataForSigning()

        assertArrayEquals(
            "toBinaryDataForSigning() must be identical before and after a wire round trip",
            before,
            after
        )
    }

    @Test
    fun `tampering with the payload breaks the signature`() {
        val signed = sign(message("original"))
        val decoded = BitchatPacket.fromBinaryData(BinaryProtocol.encode(signed)!!)!!
        val tampered = decoded.copy(payload = "tampered".toByteArray())

        assertTrue("Baseline must verify", verify(decoded))
        assertTrue("Tampered payload must not verify", !verify(tampered))
    }

    /** Directed (non-broadcast) packets take a different encode path; check it too. */
    @Test
    fun `a signed direct packet survives encode and decode`() {
        val direct = message("private").copy(recipientID = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
        val signed = sign(direct)
        val decoded = BitchatPacket.fromBinaryData(BinaryProtocol.encode(signed)!!)

        assertNotNull("Directed packet must decode", decoded)
        assertTrue("Directed packet signature must verify after the wire", verify(decoded!!))
    }

    /** ANNOUNCE is the bootstrap: if it does not verify, no peer key is learned and all messages drop. */
    @Test
    fun `a signed announce survives encode and decode`() {
        val announce = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = senderId,
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1_700_000_000_000UL,
            payload = ByteArray(64) { it.toByte() },
            signature = null,
            ttl = AppConstants.MESSAGE_TTL_HOPS
        )
        val signed = sign(announce)
        val decoded = BitchatPacket.fromBinaryData(BinaryProtocol.encode(signed)!!)

        assertNotNull(decoded)
        assertTrue("ANNOUNCE must verify after the wire, or no peer is ever learned", verify(decoded!!))
    }
}
