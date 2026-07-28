package com.campusmesh.android.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RoleAttestationManager.
 */
class RoleAttestationTest {

    @Test
    fun testMockAnnouncementVerification() {
        val payload = RoleAttestationManager.createMockAnnouncementPayload(
            content = "This is a GCTU campus-wide offline alert!",
            lecturerPrivateKeyBase64 = "MOCK_PRIV_KEY",
            lecturerPublicKeyBase64 = "MOCK_PUB_KEY"
        )
        assertNotNull(payload)
        assertTrue(payload.contains("Dr. Kofi Mensah"))
        assertTrue(payload.contains("Lecturer"))
    }

    @Test
    fun testKeyRevocation() {
        val testPublicKey = "MCowBQYDK2VwAyEAfv/aEwX9sVzXwNqS13lK8J5t13hY2xS87lIq0bK4A0k="
        
        // Should not be revoked initially
        assertFalse(RoleAttestationManager.isKeyRevoked(testPublicKey))
        
        // Perform revocation
        RoleAttestationManager.revokeKey(testPublicKey)
        
        // Should be blacklisted now
        assertTrue(RoleAttestationManager.isKeyRevoked(testPublicKey))
    }
}
