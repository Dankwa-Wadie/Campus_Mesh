package com.campusmesh.android.identity

import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Proof-of-possession is what gates the verified badge: a credential alone is public data that
 * anyone can replay. These tests cover the replay paths that would otherwise let an observer
 * present someone else's credential as their own.
 */
class PeerVerificationTest {

    private val holderKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val holderPublic = holderKey.generatePublicKey().encoded
    private val attackerKey = Ed25519PrivateKeyParameters(SecureRandom())

    private val sessionHash = ByteArray(32) { it.toByte() }
    private val otherSessionHash = ByteArray(32) { (it + 7).toByte() }

    @Test
    fun `a genuine holder proves possession`() {
        val nonce = PeerVerification.newChallenge()
        val signature = PeerVerification.sign(sessionHash, nonce, holderKey)

        assertEquals(
            PeerVerification.Result.VALID,
            PeerVerification.verify(sessionHash, nonce, nonce, signature, holderPublic)
        )
    }

    /** The core attack: presenting a credential you observed but hold no key for. */
    @Test
    fun `an observer holding only the credential cannot prove possession`() {
        val nonce = PeerVerification.newChallenge()
        val forged = PeerVerification.sign(sessionHash, nonce, attackerKey)

        assertEquals(
            PeerVerification.Result.BAD_SIGNATURE,
            PeerVerification.verify(sessionHash, nonce, nonce, forged, holderPublic)
        )
    }

    /** A proof captured on one session must not be usable on another. */
    @Test
    fun `a proof is bound to its session`() {
        val nonce = PeerVerification.newChallenge()
        val signature = PeerVerification.sign(sessionHash, nonce, holderKey)

        assertEquals(
            PeerVerification.Result.BAD_SIGNATURE,
            PeerVerification.verify(otherSessionHash, nonce, nonce, signature, holderPublic)
        )
    }

    /**
     * The challenger must check against the nonce it generated, not the one echoed back. Otherwise
     * a peer could replay an old (nonce, signature) pair together and it would verify.
     */
    @Test
    fun `a replayed nonce and signature pair is refused`() {
        val oldNonce = PeerVerification.newChallenge()
        val oldSignature = PeerVerification.sign(sessionHash, oldNonce, holderKey)

        val freshNonce = PeerVerification.newChallenge()
        assertNotEquals(oldNonce.toList(), freshNonce.toList())

        assertEquals(
            PeerVerification.Result.WRONG_NONCE,
            PeerVerification.verify(sessionHash, freshNonce, oldNonce, oldSignature, holderPublic)
        )
    }

    @Test
    fun `malformed inputs are refused rather than thrown`() {
        val nonce = PeerVerification.newChallenge()
        val signature = PeerVerification.sign(sessionHash, nonce, holderKey)

        assertEquals(
            PeerVerification.Result.MALFORMED,
            PeerVerification.verify(sessionHash, nonce, nonce, ByteArray(10), holderPublic)
        )
        assertEquals(
            PeerVerification.Result.MALFORMED,
            PeerVerification.verify(sessionHash, nonce, nonce, signature, ByteArray(10))
        )
        assertEquals(
            PeerVerification.Result.MALFORMED,
            PeerVerification.verify(sessionHash, ByteArray(4), nonce, signature, holderPublic)
        )
        assertEquals(
            PeerVerification.Result.MALFORMED,
            PeerVerification.verify(ByteArray(0), nonce, nonce, signature, holderPublic)
        )
    }

    /**
     * The domain tag must make these bytes unusable as any other signed structure — otherwise a
     * signature harvested here could be presented as, say, a credential signature.
     */
    @Test
    fun `proof bytes are domain separated and unambiguously framed`() {
        val nonce = ByteArray(PeerVerification.NONCE_LENGTH) { 1 }
        val bytes = PeerVerification.proofBytes(sessionHash, nonce)
        val asText = String(bytes, Charsets.ISO_8859_1)

        assert(asText.contains("CampusMesh/PeerVerification/v1")) {
            "proof bytes must carry the protocol domain separator"
        }
        assert(!asText.contains("CampusMesh/SchoolCredential/v1")) {
            "proof bytes must not collide with the credential domain"
        }

        // Same fields, different split between them, must produce different bytes - which is what
        // length prefixing buys over bare concatenation.
        val a = PeerVerification.proofBytes(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val b = PeerVerification.proofBytes(byteArrayOf(1, 2), byteArrayOf(3, 4, 5))
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `challenges are fresh each time`() {
        val a = PeerVerification.newChallenge()
        val b = PeerVerification.newChallenge()
        assertEquals(PeerVerification.NONCE_LENGTH, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `proof bytes are stable for identical inputs`() {
        val nonce = ByteArray(PeerVerification.NONCE_LENGTH) { 3 }
        assertArrayEquals(
            PeerVerification.proofBytes(sessionHash, nonce),
            PeerVerification.proofBytes(sessionHash, nonce)
        )
    }
}
