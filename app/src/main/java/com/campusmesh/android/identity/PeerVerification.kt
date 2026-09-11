package com.campusmesh.android.identity

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Proof that a peer actually holds the private key for the [SchoolCredential] they presented.
 *
 * A credential is public data. Verifying its issuer signature proves the school issued it to
 * *someone*; it proves nothing about who is on the other end of this session. Without this step
 * anyone who observes a credential blob — over the air, or relayed through their own device — can
 * replay it and be shown as verified. **This, not credential verification, is what gates the
 * verified badge.**
 *
 * The proof binds three things together:
 *  - the Noise session (via its handshake hash), so a proof captured on one session cannot be
 *    replayed on another;
 *  - a fresh nonce chosen by the challenger, so a recorded proof cannot be replayed on the same
 *    session later;
 *  - a protocol-specific domain tag, so an Ed25519 signature produced here can never be presented
 *    as a signature from another context (and vice versa).
 *
 * Encoding matches [SchoolCredential.signingBytes]: length-prefixed fields in fixed order. Bare
 * concatenation of `sessionHash + nonce` would be ambiguous if either ever varied in length.
 */
object PeerVerification {

    private const val DOMAIN = "CampusMesh/PeerVerification/v1"

    /** Nonce length in bytes. 32 is well beyond collision concerns for a per-session challenge. */
    const val NONCE_LENGTH = 32

    /** Ed25519 signature length. */
    private const val SIGNATURE_LENGTH = 64

    /** Generates a fresh challenge nonce. The challenger must retain it to check the response. */
    fun newChallenge(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }

    /**
     * Canonical bytes signed by the responder. Exposed so tests and any future transport encode
     * exactly the same thing rather than reimplementing the framing.
     */
    fun proofBytes(sessionHash: ByteArray, nonce: ByteArray): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                fun field(bytes: ByteArray) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                field(DOMAIN.toByteArray(Charsets.UTF_8))
                field(sessionHash)
                field(nonce)
            }
        }.toByteArray()

    /** Signs a received challenge with this device's credential signing key. */
    fun sign(sessionHash: ByteArray, nonce: ByteArray, privateKey: Ed25519PrivateKeyParameters): ByteArray {
        val data = proofBytes(sessionHash, nonce)
        return Ed25519Signer().apply {
            init(true, privateKey)
            update(data, 0, data.size)
        }.generateSignature()
    }

    /** Outcome of checking a response. Distinct values so callers can log why a peer was refused. */
    enum class Result { VALID, WRONG_NONCE, MALFORMED, BAD_SIGNATURE }

    /**
     * Verifies a peer's proof.
     *
     * [expectedNonce] must be the nonce this device generated for this exchange. Accepting the
     * nonce echoed by the peer instead would make the freshness guarantee meaningless — the peer
     * could replay an old (nonce, signature) pair together.
     *
     * [subjectPublicKey] must come from the credential that has *already* passed
     * [SchoolCredentialVerifier.verify]. Verifying a proof against a key from an unverified
     * credential proves only that the peer holds a key, not that the school vouched for it.
     */
    fun verify(
        sessionHash: ByteArray,
        expectedNonce: ByteArray,
        receivedNonce: ByteArray,
        signature: ByteArray,
        subjectPublicKey: ByteArray
    ): Result {
        if (expectedNonce.size != NONCE_LENGTH || receivedNonce.size != NONCE_LENGTH) return Result.MALFORMED
        if (signature.size != SIGNATURE_LENGTH || subjectPublicKey.size != 32) return Result.MALFORMED
        if (sessionHash.isEmpty()) return Result.MALFORMED

        // Constant-time: a short-circuiting compare leaks how much of the nonce matched.
        if (!java.security.MessageDigest.isEqual(expectedNonce, receivedNonce)) return Result.WRONG_NONCE

        return try {
            val data = proofBytes(sessionHash, expectedNonce)
            val ok = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(subjectPublicKey, 0))
                update(data, 0, data.size)
            }.verifySignature(signature)
            if (ok) Result.VALID else Result.BAD_SIGNATURE
        } catch (_: IllegalArgumentException) {
            Result.MALFORMED
        }
    }
}
