package com.campusmesh.android.identity

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Test

class SchoolCredentialTest {
    private val issuer = Ed25519PrivateKeyParameters(SecureRandom())
    private val subject = Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded
    private val verifier = SchoolCredentialVerifier("school", mapOf("issuer-1" to issuer.generatePublicKey().encoded), 1000)
    private fun sign(c: SchoolCredential): SchoolCredential {
        val signer = Ed25519Signer()
        signer.init(true, issuer)
        val data = c.signingBytes()
        signer.update(data, 0, data.size)
        return c.copy(signature = Base64.getEncoder().encodeToString(signer.generateSignature()))
    }
    private fun credential() = sign(SchoolCredential(1, "school", "issuer-1", "id-1",
        Base64.getEncoder().encodeToString(subject), "Student", 100, 1000, ""))
    private fun check(c: SchoolCredential, now: Long = 200, revoked: Set<String> = emptySet()) =
        verifier.verify(c, subject, now, revoked)

    @Test fun `school signature validates offline`() {
        assertEquals(SchoolCredentialVerifier.Result.VALID, check(credential()))
    }
    @Test fun `role and expiry tampering fails signature validation`() {
        assertEquals(SchoolCredentialVerifier.Result.INVALID_SIGNATURE, check(credential().copy(role = "Admin")))
        assertEquals(SchoolCredentialVerifier.Result.INVALID_SIGNATURE, check(credential().copy(expiresAtSeconds = 900)))
    }
    @Test fun `validity boundaries and revocation enforced`() {
        val c = credential()
        assertEquals(SchoolCredentialVerifier.Result.NOT_YET_VALID, check(c, 99))
        assertEquals(SchoolCredentialVerifier.Result.VALID, check(c, 100))
        assertEquals(SchoolCredentialVerifier.Result.EXPIRED, check(c, 1000))
        assertEquals(SchoolCredentialVerifier.Result.REVOKED, check(c, revoked = setOf("id-1")))
    }
    @Test fun `wrong school issuer and subject cannot be used`() {
        assertEquals(SchoolCredentialVerifier.Result.WRONG_SCHOOL, check(credential().copy(schoolId = "other")))
        assertEquals(SchoolCredentialVerifier.Result.UNTRUSTED_ISSUER, check(credential().copy(issuerKeyId = "unknown")))
        assertEquals(SchoolCredentialVerifier.Result.WRONG_SUBJECT,
            verifier.verify(credential(), ByteArray(32), 200, emptySet()))
    }
    @Test fun `malformed fields and unbounded lifetime rejected`() {
        assertEquals(SchoolCredentialVerifier.Result.MALFORMED, check(credential().copy(signature = "bad")))
        assertEquals(SchoolCredentialVerifier.Result.MALFORMED, check(credential().copy(credentialId = "a".repeat(513))))
        assertEquals(SchoolCredentialVerifier.Result.MALFORMED, check(credential().copy(expiresAtSeconds = Long.MAX_VALUE)))
        assertEquals(SchoolCredentialVerifier.Result.MALFORMED, check(credential().copy(version = 2)))
    }
    @Test fun `different signing authority cannot forge credential`() {
        val other = SchoolCredentialVerifier("school", mapOf("issuer-1" to Ed25519PrivateKeyParameters(SecureRandom()).generatePublicKey().encoded), 1000)
        assertEquals(SchoolCredentialVerifier.Result.INVALID_SIGNATURE, other.verify(credential(), subject, 200, emptySet()))
    }
}
