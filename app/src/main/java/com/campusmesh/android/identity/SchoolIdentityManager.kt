package com.campusmesh.android.identity

import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

/**
 * This device's credential identity: the Ed25519 keypair a [SchoolCredential] is issued against.
 *
 * Deliberately separate from the Noise X25519 transport keys held by `NoiseEncryptionService`.
 * They are different algorithms for different jobs — transport confidentiality versus attestable
 * identity — and conflating them would let a transport key masquerade as a credential-signing key.
 * Enrollment binds the school's signature to [subjectSigningKeyBase64] and nothing else.
 *
 * The private key never leaves the device and is never sent over the mesh. What peers receive is
 * the credential (which contains only the public key) plus a per-session proof of possession —
 * see [PeerVerification].
 */
class SchoolIdentityManager(private val store: SecureIdentityStateManager) {

    companion object {
        private const val TAG = "SchoolIdentityManager"
        private const val KEY_PRIVATE = "school_identity_ed25519_private"
        private const val KEY_CREDENTIAL = "school_credential_json"
    }

    private val lock = Any()

    @Volatile
    private var cached: Ed25519PrivateKeyParameters? = null

    /**
     * The device signing keypair, generated on first use and persisted in encrypted storage.
     *
     * Rotating this key invalidates any credential bound to it, so it is created once and reused
     * until the identity is cleared.
     */
    fun signingKeyPair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        synchronized(lock) {
            cached?.let { return it to it.generatePublicKey() }

            val stored = store.getSecureValue(KEY_PRIVATE)
            if (stored != null) {
                runCatching {
                    val bytes = Base64.getDecoder().decode(stored)
                    require(bytes.size == 32) { "stored signing key has wrong length: ${bytes.size}" }
                    Ed25519PrivateKeyParameters(bytes, 0)
                }.onSuccess { key ->
                    cached = key
                    return key to key.generatePublicKey()
                }.onFailure {
                    // A corrupt key is unrecoverable; regenerate rather than leaving the device
                    // permanently unable to enroll. Any credential bound to the old key is dead
                    // anyway, so nothing verifiable is lost.
                    Log.w(TAG, "Stored signing key unusable (${it.message}); regenerating")
                    store.storeSecureValue(KEY_CREDENTIAL, "")
                }
            }

            val fresh = Ed25519PrivateKeyParameters(SecureRandom())
            store.storeSecureValue(KEY_PRIVATE, Base64.getEncoder().encodeToString(fresh.encoded))
            cached = fresh
            Log.i(TAG, "Generated new school identity signing key")
            return fresh to fresh.generatePublicKey()
        }
    }

    /**
     * Public key in the canonical padded base64 form [SchoolCredential.subjectSigningKey] uses.
     * This is what goes into an enrollment request and what a peer's verifier compares against.
     */
    fun subjectSigningKeyBase64(): String =
        Base64.getEncoder().encodeToString(signingKeyPair().second.encoded)

    /** Raw 32-byte public key, for passing as `expectedSubjectKey` to the verifier. */
    fun subjectSigningKeyBytes(): ByteArray = signingKeyPair().second.encoded

    /** Signs a peer's verification challenge. Returns null before a keypair exists. */
    fun provePossession(sessionHash: ByteArray, nonce: ByteArray): ByteArray? = runCatching {
        PeerVerification.sign(sessionHash, nonce, signingKeyPair().first)
    }.onFailure { Log.e(TAG, "Failed to sign possession challenge: ${it.message}") }.getOrNull()

    /** The stored credential as issued, or null when this device has not enrolled. */
    fun storedCredentialJson(): String? = store.getSecureValue(KEY_CREDENTIAL)?.takeIf { it.isNotBlank() }

    /** Persists an issued credential. Callers must verify it before storing. */
    fun storeCredentialJson(json: String) = store.storeSecureValue(KEY_CREDENTIAL, json)

    /** True when this device has enrolled. Says nothing about whether the credential is still valid. */
    fun hasCredential(): Boolean = storedCredentialJson() != null

    /** Clears the credential but keeps the keypair, so the device can re-enroll as the same subject. */
    fun clearCredential() = store.storeSecureValue(KEY_CREDENTIAL, "")
}
