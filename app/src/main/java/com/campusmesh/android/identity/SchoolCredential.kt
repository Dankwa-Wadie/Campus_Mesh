package com.campusmesh.android.identity

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Public enrollment certificate. Email and student number deliberately stay off the mesh. */
data class SchoolCredential(
    val version: Int,
    val schoolId: String,
    val issuerKeyId: String,
    val credentialId: String,
    val subjectSigningKey: String,
    val role: String,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long,
    val signature: String
) {
    /** Fixed field order, length-prefixed UTF-8 strings, big-endian integers; signature excluded. */
    fun signingBytes(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 512)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            field("CampusMesh/SchoolCredential/v1")
            out.writeInt(version)
            field(schoolId)
            field(issuerKeyId)
            field(credentialId)
            field(subjectSigningKey)
            field(role)
            out.writeLong(issuedAtSeconds)
            out.writeLong(expiresAtSeconds)
        }
    }.toByteArray()
}

/**
 * Validates a certificate, NOT live peer possession or campus presence. Callers must separately
 * authenticate the subject key before showing a peer as verified. Trust and revocations must
 * come from school-controlled configuration, never from fields supplied by a remote peer.
 */
class SchoolCredentialVerifier(
    private val schoolId: String,
    issuerKeys: Map<String, ByteArray>,
    private val maxLifetimeSeconds: Long
) {
    private val issuerKeys = issuerKeys.mapValues { it.value.copyOf() }
    init {
        require(schoolId.isNotBlank())
        require(maxLifetimeSeconds > 0)
        require(this.issuerKeys.values.all { it.size == 32 })
    }

    enum class Result { VALID, MALFORMED, WRONG_SCHOOL, UNTRUSTED_ISSUER, INVALID_SIGNATURE, NOT_YET_VALID, EXPIRED, REVOKED, WRONG_SUBJECT }

    fun verify(
        credential: SchoolCredential,
        expectedSubjectKey: ByteArray,
        nowSeconds: Long,
        revokedCredentialIds: Set<String>
    ): Result {
        return try {
            if (credential.version != 1 || nowSeconds < 0 || expectedSubjectKey.size != 32 ||
                credential.credentialId.isBlank() || credential.issuerKeyId.isBlank() ||
                credential.role !in setOf("Student", "Lecturer", "Admin") ||
                credential.issuedAtSeconds < 0 || credential.expiresAtSeconds <= credential.issuedAtSeconds ||
                credential.expiresAtSeconds - credential.issuedAtSeconds > maxLifetimeSeconds) return Result.MALFORMED
            val data = credential.signingBytes()
            if (credential.schoolId != schoolId) return Result.WRONG_SCHOOL
            val issuer = issuerKeys[credential.issuerKeyId] ?: return Result.UNTRUSTED_ISSUER
            val subject = decodeCanonical(credential.subjectSigningKey, 32) ?: return Result.MALFORMED
            val signature = decodeCanonical(credential.signature, 64) ?: return Result.MALFORMED
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(issuer, 0))
            verifier.update(data, 0, data.size)
            if (!verifier.verifySignature(signature)) return Result.INVALID_SIGNATURE
            if (!java.security.MessageDigest.isEqual(subject, expectedSubjectKey)) return Result.WRONG_SUBJECT
            if (credential.credentialId in revokedCredentialIds) return Result.REVOKED
            if (nowSeconds < credential.issuedAtSeconds) return Result.NOT_YET_VALID
            if (nowSeconds >= credential.expiresAtSeconds) return Result.EXPIRED
            Result.VALID
        } catch (_: IllegalArgumentException) {
            Result.MALFORMED
        }
    }

    private fun decodeCanonical(value: String, size: Int): ByteArray? {
        if (value.length != ((size + 2) / 3) * 4) return null
        val bytes = Base64.getDecoder().decode(value)
        return bytes.takeIf { it.size == size && Base64.getEncoder().encodeToString(it) == value }
    }
}
