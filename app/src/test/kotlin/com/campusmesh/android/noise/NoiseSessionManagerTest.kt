package com.campusmesh.android.noise

import com.campusmesh.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.*
import org.junit.Test

class NoiseSessionManagerTest {
    private fun manager(id: String): NoiseSessionManager {
        val dh = Noise.createDH("25519")
        dh.generateKeyPair()
        val privateKey = ByteArray(32)
        val publicKey = ByteArray(32)
        dh.getPrivateKey(privateKey, 0)
        dh.getPublicKey(publicKey, 0)
        dh.destroy()
        return NoiseSessionManager(privateKey, publicKey, id)
    }

    private fun connect(a: NoiseSessionManager, b: NoiseSessionManager) {
        val first = a.initiateHandshake("b")!!
        val second = b.processHandshakeMessage("a", first)!!
        val third = a.processHandshakeMessage("b", second)!!
        b.processHandshakeMessage("a", third)
    }

    @Test fun `malformed replacement preserves established transport`() {
        val a = manager("a")
        val b = manager("b")
        try {
            connect(a, b)
            val original = b.getSession("a")
            b.processHandshakeMessage("a", byteArrayOf())
            assertSame(original, b.getSession("a"))
            val attacker = manager("a")
            val offer = attacker.initiateHandshake("b")!!
            b.processHandshakeMessage("a", offer)
            try { b.processHandshakeMessage("a", ByteArray(48)) } catch (_: Throwable) { }
            assertSame(original, b.getSession("a"))
            assertArrayEquals("still works".toByteArray(), b.decrypt(a.encrypt("still works".toByteArray(), "b"), "a"))
            attacker.shutdown()
        } finally { a.shutdown(); b.shutdown() }
    }

    @Test fun `replacement must prove same identity and legitimate reconnect succeeds`() {
        val a = manager("a")
        val b = manager("b")
        val attacker = manager("a")
        try {
            connect(a, b)
            val original = b.getSession("a")
            try { connect(attacker, b); fail("Different identity accepted") } catch (_: IllegalArgumentException) { }
            assertSame(original, b.getSession("a"))
            a.removeSession("b")
            connect(a, b)
            assertNotSame(original, b.getSession("a"))
            assertArrayEquals("reconnected".toByteArray(), b.decrypt(a.encrypt("reconnected".toByteArray(), "b"), "a"))
        } finally { a.shutdown(); b.shutdown(); attacker.shutdown() }
    }
}
