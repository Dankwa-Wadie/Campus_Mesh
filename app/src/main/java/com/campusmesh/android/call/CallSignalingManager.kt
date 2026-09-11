package com.campusmesh.android.call

import android.content.Context
import android.util.Log
import com.campusmesh.android.mesh.MeshService
import com.campusmesh.android.wifiaware.WifiAwareController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * State machine for a single 1:1 voice call. Only one call can be active at a time (no call
 * waiting/multi-call MVP - see WIFI_VOICE_CALLING_IMPLEMENTATION_PLAN.md Phase 3 for group calls).
 */
sealed class CallState {
    object Idle : CallState()
    data class Outgoing(val peerID: String, val peerName: String) : CallState()
    data class IncomingRinging(val peerID: String, val peerName: String) : CallState()
    data class Connected(val peerID: String, val peerName: String, val startedAt: Long) : CallState()
    data class Ended(val peerID: String, val peerName: String, val reason: String, val durationMs: Long) : CallState()
}

/**
 * Who currently holds the floor in a connected call. Calls are half-duplex (push-to-talk): a
 * full-duplex UDP stream over a single Wi-Fi Aware radio proved unreliable in testing (both sides
 * transmitting/receiving at once fights for the same radio and garbles audio in both directions),
 * so only one side's [AudioCaptureThread] is ever running at a time - see [beginTalking]/
 * [endTalking]. [AudioPlaybackThread] runs continuously for the whole call on both sides; it's
 * simply idle (no packets arriving) when the floor isn't held by the remote peer.
 */
enum class TalkFloor { IDLE, LOCAL_TALKING, REMOTE_TALKING }

/**
 * Builds/parses CALL_SIGNAL Noise payloads (offer/ringing/accept/reject/busy/end) and drives the
 * call state machine. Signaling rides the existing Noise-encrypted private-message pipe (same
 * path as a normal DM) via [MeshService.sendCallSignal] - no new crypto or routing needed.
 *
 * A call only ever happens over Wi-Fi Aware: the "Call" affordance in the UI is only enabled when
 * [isCallable] is true for that peer, so an OFFER never originates without a usable data path.
 * Audio itself (see [CallAudioSession]) is a UDP DatagramSocket bound to that same Wi-Fi Aware
 * Network - a completely separate path from this Noise-encrypted signaling.
 *
 * This is a process-wide singleton (like [WifiAwareController]) rather than something owned by
 * ChatViewModel, since an incoming call can arrive while the user is anywhere in the app (or the
 * app is backgrounded with the mesh foreground service running) and the in-call UI needs to be
 * reachable regardless of which screen/ViewModel instance is currently alive.
 */
object CallSignalingManager {
    private const val TAG = "CallSignalingManager"

    // Ring/offer timeout - if nobody answers within this window, treat it as a missed call.
    private const val RING_TIMEOUT_MS = 45_000L
    // How long the "Ended" state lingers in the UI (e.g. showing call duration) before resetting
    // to Idle so a new call can be placed/received.
    private const val ENDED_LINGER_MS = 3_000L

    // Signal type bytes - our own tiny binary format, opaque to the mesh layer (which just carries
    // NoisePayloadType.CALL_SIGNAL's raw bytes). Not related to MessageType/NoisePayloadType values.
    private const val SIG_OFFER: Byte = 0x01     // [SIG_OFFER][2B caller's local UDP port]
    private const val SIG_RINGING: Byte = 0x02   // [SIG_RINGING] - informational only
    private const val SIG_ACCEPT: Byte = 0x03    // [SIG_ACCEPT][2B callee's local UDP port]
    private const val SIG_REJECT: Byte = 0x04    // [SIG_REJECT]
    private const val SIG_BUSY: Byte = 0x05      // [SIG_BUSY] - we were already in a call
    private const val SIG_END: Byte = 0x06       // [SIG_END]
    private const val SIG_TALK_START: Byte = 0x07 // [SIG_TALK_START] - sender is starting to transmit (push-to-talk)
    private const val SIG_TALK_END: Byte = 0x08   // [SIG_TALK_END] - sender stopped transmitting

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var meshService: MeshService? = null
    private var nicknameProvider: (() -> String?)? = null
    private var peerNicknameProvider: ((String) -> String?)? = null
    private var appContext: Context? = null

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _talkFloor = MutableStateFlow(TalkFloor.IDLE)
    /** Who currently holds the floor - only meaningful while [callState] is [CallState.Connected]. */
    val talkFloor: StateFlow<TalkFloor> = _talkFloor.asStateFlow()

    private var audioSession: CallAudioSession? = null
    private var ringOrLingerJob: Job? = null
    private var callStartedAt: Long = 0L
    private var onCallEnded: ((peerID: String, reason: String, durationMs: Long) -> Unit)? = null

    /** Wire this up once (from ChatViewModel/MainActivity init) with the live mesh + nickname sources. */
    fun initialize(context: Context, mesh: MeshService, nicknameProvider: () -> String?, peerNicknameProvider: (String) -> String?) {
        this.appContext = context.applicationContext
        this.meshService = mesh
        this.nicknameProvider = nicknameProvider
        this.peerNicknameProvider = peerNicknameProvider
    }

    /**
     * Called once (from ChatViewModel init - not re-set on panic-recreate, since the callback
     * closes over messageManager/state which don't change across a mesh-service swap) whenever a
     * call finishes, so a "📞 Voice call · 2:14" style system message can be appended to that
     * peer's private chat - reusing the existing private-message list rather than a separate
     * call-history UI.
     */
    fun setOnCallEndedListener(listener: (peerID: String, reason: String, durationMs: Long) -> Unit) {
        this.onCallEnded = listener
    }

    /** Whether a "Call" button should be enabled for this peer right now. */
    fun isCallable(peerID: String): Boolean {
        return try { WifiAwareController.getService()?.isPeerConnected(peerID) == true } catch (_: Exception) { false }
    }

    @Synchronized
    fun startCall(peerID: String) {
        if (_callState.value !is CallState.Idle) {
            Log.w(TAG, "startCall($peerID) ignored - a call is already active")
            return
        }
        if (!isCallable(peerID)) {
            Log.w(TAG, "startCall($peerID) ignored - no live Wi-Fi Aware connection to that peer")
            return
        }
        val session = CallAudioSession(peerID)
        val localPort = session.prepare()
        if (localPort <= 0) {
            Log.e(TAG, "startCall($peerID): failed to prepare local audio socket")
            return
        }
        audioSession = session
        _callState.value = CallState.Outgoing(peerID, displayNameFor(peerID))
        send(peerID, byteArrayOf(SIG_OFFER, *portBytes(localPort)))
        scheduleRingTimeout(peerID)
    }

    @Synchronized
    fun acceptCall() {
        val state = _callState.value
        if (state !is CallState.IncomingRinging) return
        ringOrLingerJob?.cancel()
        val peerID = state.peerID
        val session = audioSession ?: CallAudioSession(peerID).also { audioSession = it }
        val localPort = session.prepare()
        if (localPort <= 0) {
            Log.e(TAG, "acceptCall($peerID): failed to prepare local audio socket")
            endCallInternal(peerID, "audio_setup_failed")
            return
        }
        val remotePort = session.pendingRemotePort
        if (remotePort == null) {
            endCallInternal(peerID, "audio_setup_failed")
            return
        }
        if (!beginStreaming(peerID, session, remotePort)) return
        send(peerID, byteArrayOf(SIG_ACCEPT, *portBytes(localPort)))
        callStartedAt = System.currentTimeMillis()
        _callState.value = CallState.Connected(peerID, displayNameFor(peerID), callStartedAt)
    }

    @Synchronized
    fun rejectCall() {
        val state = _callState.value
        val peerID = (state as? CallState.IncomingRinging)?.peerID ?: return
        send(peerID, byteArrayOf(SIG_REJECT))
        endCallInternal(peerID, "declined")
    }

    @Synchronized
    fun endCall() {
        val peerID = currentPeerID() ?: return
        send(peerID, byteArrayOf(SIG_END))
        endCallInternal(peerID, "ended")
    }

    /** Start capture; simultaneous requests yield to the lower peer ID. */
    @Synchronized
    fun beginTalking() {
        val state = _callState.value
        if (state !is CallState.Connected) return
        if (_talkFloor.value == TalkFloor.REMOTE_TALKING) return
        val peerID = state.peerID
        audioSession?.startTransmitting()
        _talkFloor.value = TalkFloor.LOCAL_TALKING
        send(peerID, byteArrayOf(SIG_TALK_START))
    }

    /** Release-to-stop-talking - the other half of [beginTalking]. */
    @Synchronized
    fun endTalking() {
        val state = _callState.value
        if (state !is CallState.Connected) return
        val peerID = state.peerID
        audioSession?.stopTransmitting()
        if (_talkFloor.value != TalkFloor.LOCAL_TALKING) return
        _talkFloor.value = TalkFloor.IDLE
        send(peerID, byteArrayOf(SIG_TALK_END))
    }

    /** Called by ChatViewModel's didReceiveCallSignal (forwarded from MeshDelegate). */
    @Synchronized
    fun handleIncoming(peerID: String, payload: ByteArray, timestampMs: Long) {
        if (payload.isEmpty()) return
        when (payload[0]) {
            SIG_OFFER -> {
                if (_callState.value !is CallState.Idle) {
                    send(peerID, byteArrayOf(SIG_BUSY))
                    return
                }
                val callerPort = portFrom(payload, 1) ?: return
                val session = CallAudioSession(peerID)
                session.pendingRemotePort = callerPort
                audioSession = session
                _callState.value = CallState.IncomingRinging(peerID, displayNameFor(peerID))
                send(peerID, byteArrayOf(SIG_RINGING))
                scheduleRingTimeout(peerID, isIncoming = true)
            }
            SIG_RINGING -> {
                // Purely informational - UI already shows Outgoing/"Ringing..." from startCall().
            }
            SIG_ACCEPT -> {
                val state = _callState.value
                if (state !is CallState.Outgoing || state.peerID != peerID) return
                val calleePort = portFrom(payload, 1) ?: return
                ringOrLingerJob?.cancel()
                val session = audioSession ?: return
                if (!beginStreaming(peerID, session, calleePort)) return
                callStartedAt = System.currentTimeMillis()
                _callState.value = CallState.Connected(peerID, displayNameFor(peerID), callStartedAt)
            }
            SIG_REJECT -> if (currentPeerID() == peerID) endCallInternal(peerID, "declined")
            SIG_BUSY -> if (currentPeerID() == peerID) endCallInternal(peerID, "busy")
            SIG_END -> if (currentPeerID() == peerID) endCallInternal(peerID, "ended")
            SIG_TALK_START -> if (currentPeerID() == peerID && _callState.value is CallState.Connected) {
                if (_talkFloor.value == TalkFloor.LOCAL_TALKING) {
                    if ((meshService?.myPeerID ?: "") < peerID) return
                    audioSession?.stopTransmitting()
                    send(peerID, byteArrayOf(SIG_TALK_END))
                }
                _talkFloor.value = TalkFloor.REMOTE_TALKING
            }
            SIG_TALK_END -> if (currentPeerID() == peerID && _talkFloor.value == TalkFloor.REMOTE_TALKING) {
                _talkFloor.value = TalkFloor.IDLE
            }
        }
    }

    private fun beginStreaming(peerID: String, session: CallAudioSession, remotePort: Int): Boolean {
        try {
            val remoteAddress = WifiAwareController.getService()?.getPeerInetAddress(peerID)
            if (remoteAddress == null) {
                Log.e(TAG, "beginStreaming($peerID): no known peer address, aborting call")
                send(peerID, byteArrayOf(SIG_END))
                endCallInternal(peerID, "no_route")
                return false
            }
            session.startStreaming(remoteAddress, remotePort)
            appContext?.let { CallForegroundService.start(it) }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "beginStreaming($peerID) failed: ${e.message}", e)
            send(peerID, byteArrayOf(SIG_END))
            endCallInternal(peerID, "audio_setup_failed")
            return false
        }
    }

    private fun scheduleRingTimeout(peerID: String, isIncoming: Boolean = false) {
        ringOrLingerJob?.cancel()
        ringOrLingerJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val state = _callState.value
            val stillRinging = if (isIncoming) state is CallState.IncomingRinging else state is CallState.Outgoing
            if (stillRinging) {
                send(peerID, byteArrayOf(SIG_END))
                endCallInternal(peerID, if (isIncoming) "missed" else "no_answer")
            }
        }
    }

    @Synchronized
    private fun endCallInternal(peerID: String, reason: String) {
        ringOrLingerJob?.cancel()
        val duration = if (callStartedAt > 0) System.currentTimeMillis() - callStartedAt else 0L
        try { audioSession?.stop() } catch (_: Exception) { }
        audioSession = null
        _talkFloor.value = TalkFloor.IDLE
        try { appContext?.let { CallForegroundService.stop(it) } } catch (_: Exception) { }
        val name = displayNameFor(peerID)
        callStartedAt = 0L
        _callState.value = CallState.Ended(peerID, name, reason, duration)
        try { onCallEnded?.invoke(peerID, reason, duration) } catch (e: Exception) {
            Log.e(TAG, "onCallEnded listener threw: ${e.message}")
        }
        ringOrLingerJob = scope.launch {
            delay(ENDED_LINGER_MS)
            if (_callState.value is CallState.Ended) _callState.value = CallState.Idle
        }
    }

    private fun currentPeerID(): String? = when (val s = _callState.value) {
        is CallState.Outgoing -> s.peerID
        is CallState.IncomingRinging -> s.peerID
        is CallState.Connected -> s.peerID
        else -> null
    }

    private fun displayNameFor(peerID: String): String {
        return try { peerNicknameProvider?.invoke(peerID) ?: peerID.take(12) } catch (_: Exception) { peerID.take(12) }
    }

    private fun send(peerID: String, data: ByteArray) {
        try { meshService?.sendCallSignal(peerID, data) } catch (e: Exception) {
            Log.e(TAG, "Failed to send call signal to $peerID: ${e.message}")
        }
    }

    private fun portBytes(port: Int): ByteArray =
        ByteBuffer.allocate(2).putShort(port.toShort()).array()

    private fun portFrom(data: ByteArray, offset: Int): Int? {
        if (data.size < offset + 2) return null
        val hi = data[offset].toInt() and 0xFF
        val lo = data[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).takeIf { it > 0 }
    }
}
