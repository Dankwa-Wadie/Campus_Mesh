package com.campusmesh.android.identity

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The complete offline check two phones perform on each other: verify the school's signature on a
 * credential, then prove the peer actually holds its key. Either half alone is insufficient, and
 * these tests pin down why.
 */
class OfflineVerificationFlowTest {

    private val issuer = Ed25519PrivateKeyParameters(SecureRandom())
    private val schoolId = "gctu"

    private val verifier = SchoolCredentialVerifier(
        schoolId = schoolId,
        issuerKeys = mapOf("issuer-1" to issuer.generatePublicKey().encoded),
        maxLifetimeSeconds = 14 * 24 * 3600          // the short lifetime the plan calls for
    )

    private val student = Ed25519PrivateKeyParameters(SecureRandom())
    private val studentPublic = student.generatePublicKey().encoded

    private val issuedAt = 1_767_225_600L
    private val expiresAt = issuedAt + 7 * 24 * 3600
    private val now = issuedAt + 3600

    private fun credentialFor(subjectPublic: ByteArray, id: String = "cred-1"): SchoolCredential {
        val unsigned = SchoolCredential(
            version = 1,
            schoolId = schoolId,
            issuerKeyId = "issuer-1",
            credentialId = id,
            subjectSigningKey = Base64.getEncoder().encodeToString(subjectPublic),
            role = "Student",
            issuedAtSeconds = issuedAt,
            expiresAtSeconds = expiresAt,
            signature = ""
        )
        val data = unsigned.signingBytes()
        val sig = Ed25519Signer().apply {
            init(true, issuer)
            update(data, 0, data.size)
        }.generateSignature()
        return unsigned.copy(signature = Base64.getEncoder().encodeToString(sig))
    }

    @Test
    fun `a genuine student passes both halves`() {
        val credential = credentialFor(studentPublic)
        val sessionHash = ByteArray(32) { 4 }
        val nonce = PeerVerification.newChallenge()

        assertEquals(
            SchoolCredentialVerifier.Result.VALID,
            verifier.verify(credential, studentPublic, now, emptySet())
        )
        assertEquals(
            PeerVerification.Result.VALID,
            PeerVerification.verify(
                sessionHash, nonce, nonce,
                PeerVerification.sign(sessionHash, nonce, student),
                studentPublic
            )
        )
    }

    /**
     * The headline attack. An eavesdropper relays someone else's valid credential — the credential
     * check passes, because the credential really is genuine. Only possession stops them.
     */
    @Test
    fun `a relayed credential passes the certificate check but fails possession`() {
        val victimCredential = credentialFor(studentPublic)
        val attacker = Ed25519PrivateKeyParameters(SecureRandom())
        val sessionHash = ByteArray(32) { 4 }
        val nonce = PeerVerification.newChallenge()

        assertEquals(
            "The stolen credential is genuine, so the certificate check cannot catch this",
            SchoolCredentialVerifier.Result.VALID,
            verifier.verify(victimCredential, studentPublic, now, emptySet())
        )

        assertEquals(
            "Possession is the only thing standing between a replayed credential and a badge",
            PeerVerification.Result.BAD_SIGNATURE,
            PeerVerification.verify(
                sessionHash, nonce, nonce,
                PeerVerification.sign(sessionHash, nonce, attacker),
                studentPublic
            )
        )
    }

    /** Swapping in your own key breaks the issuer signature, so the certificate check catches it. */
    @Test
    fun `substituting your own subject key invalidates the credential`() {
        val attacker = Ed25519PrivateKeyParameters(SecureRandom())
        val attackerPublic = attacker.generatePublicKey().encoded
        val tampered = credentialFor(studentPublic).copy(
            subjectSigningKey = Base64.getEncoder().encodeToString(attackerPublic)
        )

        assertEquals(
            SchoolCredentialVerifier.Result.INVALID_SIGNATURE,
            verifier.verify(tampered, attackerPublic, now, emptySet())
        )
    }

    /** A rolled-back clock must not revive an expired credential once a floor has been observed. */
    @Test
    fun `an expired credential stays expired under a rewound clock`() {
        val credential = credentialFor(studentPublic)
        val afterExpiry = expiresAt + 3600

        assertEquals(
            SchoolCredentialVerifier.Result.EXPIRED,
            verifier.verify(credential, studentPublic, afterExpiry, emptySet())
        )

        // Attacker rewinds the device clock to before expiry.
        val rewound = issuedAt + 60
        assertEquals(
            "Raw device time would accept it again",
            SchoolCredentialVerifier.Result.VALID,
            verifier.verify(credential, studentPublic, rewound, emptySet())
        )

        // With a floor that has already seen post-expiry time, the rewind has no effect.
        val effective = maxOf(rewound, afterExpiry)
        assertNotEquals(rewound, effective)
        assertEquals(
            SchoolCredentialVerifier.Result.EXPIRED,
            verifier.verify(credential, studentPublic, effective, emptySet())
        )
    }

    @Test
    fun `a revoked credential is refused even with valid possession`() {
        val credential = credentialFor(studentPublic, id = "cred-revoked")

        assertEquals(
            SchoolCredentialVerifier.Result.REVOKED,
            verifier.verify(credential, studentPublic, now, setOf("cred-revoked"))
        )
    }

    /** The plan's short-lifetime rule, enforced by the verifier rather than by convention. */
    @Test
    fun `a credential exceeding the configured lifetime is malformed`() {
        val longLived = credentialFor(studentPublic).copy(
            expiresAtSeconds = issuedAt + 365 * 24 * 3600
        )

        assertEquals(
            SchoolCredentialVerifier.Result.MALFORMED,
            verifier.verify(longLived, studentPublic, now, emptySet())
        )
    }
}
