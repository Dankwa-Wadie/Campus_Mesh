package com.campusmesh.android.call

import com.campusmesh.android.mesh.MeshService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.*

class CallSignalingManagerTest {
    private val manager = CallSignalingManager
    private lateinit var audio: CallAudioSession
    private lateinit var mesh: MeshService

    private fun set(name: String, value: Any?) {
        manager.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }
    private fun get(name: String): Any? = manager.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(manager)
    @Suppress("UNCHECKED_CAST")
    private fun state(value: CallState) { (get("_callState") as MutableStateFlow<CallState>).value = value }

    @Before fun setup() {
        audio = mock()
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("z")
        set("audioSession", audio)
        set("meshService", mesh)
        set("appContext", null)
        state(CallState.Connected("a", "Alice", 0))
    }
    @After fun cleanup() {
        manager.endTalking()
        (get("ringOrLingerJob") as? Job)?.cancel()
        set("audioSession", null)
        set("meshService", null)
        state(CallState.Idle)
        @Suppress("UNCHECKED_CAST")
        (get("_talkFloor") as MutableStateFlow<TalkFloor>).value = TalkFloor.IDLE
    }
    @Test fun `simultaneous presses stop losing capture and release is always safe`() {
        manager.beginTalking()
        manager.handleIncoming("a", byteArrayOf(7), 0)
        verify(audio).stopTransmitting()
        assertEquals(TalkFloor.REMOTE_TALKING, manager.talkFloor.value)
        manager.endTalking()
        verify(audio, times(2)).stopTransmitting()
        assertEquals(TalkFloor.REMOTE_TALKING, manager.talkFloor.value)
    }
    @Test fun `lower peer keeps floor on simultaneous press`() {
        whenever(mesh.myPeerID).thenReturn("0")
        manager.beginTalking()
        manager.handleIncoming("a", byteArrayOf(7), 0)
        assertEquals(TalkFloor.LOCAL_TALKING, manager.talkFloor.value)
        manager.endTalking()
        verify(audio).stopTransmitting()
    }
    @Test fun `missing route does not connect caller`() {
        state(CallState.Outgoing("a", "Alice"))
        manager.handleIncoming("a", byteArrayOf(3, 1, 1), 0)
        assertTrue(manager.callState.value is CallState.Ended)
        verify(audio).stop()
    }
    @Test fun `missing route does not accept incoming call`() {
        state(CallState.IncomingRinging("a", "Alice"))
        whenever(audio.prepare()).thenReturn(1234)
        whenever(audio.pendingRemotePort).thenReturn(4321)
        manager.acceptCall()
        assertTrue(manager.callState.value is CallState.Ended)
        verify(mesh, never()).sendCallSignal(eq("a"), argThat { firstOrNull() == 3.toByte() })
    }
}
