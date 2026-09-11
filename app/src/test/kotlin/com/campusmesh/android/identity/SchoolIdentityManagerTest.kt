package com.campusmesh.android.identity

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.campusmesh.testing.FakeAndroidKeyStore
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class SchoolIdentityManagerTest {

    private lateinit var context: Context
    private lateinit var identity: SchoolIdentityManager

    @Before
    fun setup() {
        FakeAndroidKeyStore.install()
        context = ApplicationProvider.getApplicationContext()
        val store = SecureIdentityStateManager(context)
        store.storeSecureValue("school_identity_ed25519_private", "")
        store.storeSecureValue("school_credential_json", "")
        identity = SchoolIdentityManager(store)
    }

    /** Rotating this key would invalidate any credential issued against it. */
    @Test
    fun `the signing key is stable across calls and instances`() {
        val first = identity.subjectSigningKeyBase64()
        assertEquals(first, identity.subjectSigningKeyBase64())

        val reloaded = SchoolIdentityManager(SecureIdentityStateManager(context))
        assertEquals(
            "A restart must not rotate the subject key",
            first,
            reloaded.subjectSigningKeyBase64()
        )
    }

    /** The format the credential's `subjectSigningKey` field and the verifier both expect. */
    @Test
    fun `the public key is canonical padded base64 of 32 bytes`() {
        val encoded = identity.subjectSigningKeyBase64()
        val decoded = Base64.getDecoder().decode(encoded)

        assertEquals(32, decoded.size)
        assertEquals(encoded, Base64.getEncoder().encodeToString(decoded))
        assertEquals(44, encoded.length)
    }

    @Test
    fun `possession proofs verify against the published subject key`() {
        val sessionHash = ByteArray(32) { 9 }
        val nonce = PeerVerification.newChallenge()

        val proof = identity.provePossession(sessionHash, nonce)
        assertNotNull("Signing should succeed once a keypair exists", proof)

        assertEquals(
            PeerVerification.Result.VALID,
            PeerVerification.verify(
                sessionHash, nonce, nonce, proof!!, identity.subjectSigningKeyBytes()
            )
        )
    }

    @Test
    fun `credential storage round-trips and can be cleared`() {
        assertFalse(identity.hasCredential())

        identity.storeCredentialJson("""{"credentialId":"id-1"}""")
        assertTrue(identity.hasCredential())
        assertEquals("""{"credentialId":"id-1"}""", identity.storedCredentialJson())

        identity.clearCredential()
        assertFalse(identity.hasCredential())
    }

    /** Clearing a credential must keep the keypair, so the device re-enrolls as the same subject. */
    @Test
    fun `clearing the credential keeps the subject key`() {
        val key = identity.subjectSigningKeyBase64()
        identity.storeCredentialJson("""{"credentialId":"id-1"}""")
        identity.clearCredential()

        assertEquals(key, identity.subjectSigningKeyBase64())
    }

    /** A corrupt stored key must not brick enrollment permanently. */
    @Test
    fun `a corrupt stored key is regenerated rather than failing forever`() {
        val store = SecureIdentityStateManager(context)
        store.storeSecureValue("school_identity_ed25519_private", "not-base64!!")

        val recovered = SchoolIdentityManager(store).subjectSigningKeyBase64()
        assertEquals(32, Base64.getDecoder().decode(recovered).size)
    }
}
