package com.campusmesh.android.crypto

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RoleAttestationManager.
 */
class RoleAttestationTest {

    private val gson = Gson()

    /**
     * Builds an announcement carrying a fabricated lecturer certificate with stub signatures.
     *
     * This used to live in RoleAttestationManager as `createMockAnnouncementPayload` — a forged
     * lecturer credential reachable from production code. It belongs here, and only as the input
     * to a negative test.
     */
    private fun forgedLecturerAnnouncement(content: String): String {
        val certificate = RoleAttestationManager.RoleCertificate(
            subjectPublicKey = "MOCK_PUB_KEY",
            role = "Lecturer",
            name = "Dr. Kofi Mensah",
            signature = "aGFja2F0aG9uX2FkbWluX3NpZ25hdHVyZV9tb2NrX3NhZmFyaV9wcmVzZW50YXRpb24="
        )
        val announcement = RoleAttestationManager.SignedAnnouncement(
            content = content,
            timestamp = System.currentTimeMillis(),
            certificate = certificate,
            messageSignature = "bW9ja19sZWN0dXJlcl9zaWduYXR1cmV="
        )
        return gson.toJson(announcement)
    }

    /**
     * The point of the check: a certificate whose signature is a stub must not verify. The previous
     * version of this test only asserted the payload *contained* "Lecturer" and the fake name, so
     * it would have passed even if verification were entirely broken.
     */
    @Test
    fun `forged lecturer announcement is rejected`() {
        val payload = forgedLecturerAnnouncement("This is a GCTU campus-wide offline alert!")
        assertFalse(
            "A stub signature must never verify as a lecturer announcement",
            RoleAttestationManager.verifyAnnouncement(payload)
        )
    }

    @Test
    fun `malformed announcement payloads are rejected`() {
        assertFalse(RoleAttestationManager.verifyAnnouncement(""))
        assertFalse(RoleAttestationManager.verifyAnnouncement("not json"))
        assertFalse(RoleAttestationManager.verifyAnnouncement("{}"))
    }

    @Test
    fun testKeyRevocation() {
        val testPublicKey = "MCowBQYDK2VwAyEAfv/aEwX9sVzXwNqS13lK8J5t13hY2xS87lIq0bK4A0k="

        assertFalse(RoleAttestationManager.isKeyRevoked(testPublicKey))
        RoleAttestationManager.revokeKey(testPublicKey)
        assertTrue(RoleAttestationManager.isKeyRevoked(testPublicKey))
    }
}
