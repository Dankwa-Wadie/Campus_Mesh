package com.campusmesh.android.call

import android.util.Log
import com.campusmesh.android.wifiaware.WifiAwareController
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Owns the UDP media path for one call: a [DatagramSocket] bound to the same Wi-Fi Aware
 * [android.net.Network] already negotiated for this peer's text/file TCP data path (see
 * WifiAwareConnectionTracker.peerNetworks - this is the one real gap Phase 0 closed), plus the
 * capture/playback threads that read/write PCM frames over it.
 *
 * Two-phase because both sides need to exchange local UDP ports over signaling before anyone can
 * start sending: [prepare] opens+binds the socket and returns the local port to put in the
 * OFFER/ACCEPT payload; [startStreaming] is called once the remote port is known (from the peer's
 * ACCEPT, or immediately for the callee since the caller's port arrived with the OFFER).
 *
 * Calls are half-duplex (push-to-talk) - see [TalkFloor]'s doc comment for why. [startStreaming]
 * only starts the [AudioPlaybackThread]; it runs for the whole call on both sides regardless of
 * who's talking; a call with nobody transmitting is just a socket with no packets arriving, which
 * is fine. The [AudioCaptureThread] is a separate lifecycle entirely, gated by [startTransmitting]/
 * [stopTransmitting] which CallSignalingManager's beginTalking()/endTalking() drive from the UI's
 * hold-to-talk button.
 */
class CallAudioSession(private val peerID: String) {
    companion object {
        private const val TAG = "CallAudioSession"
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_MILLIS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE_HZ / 1000 * FRAME_MILLIS // 320 samples @16kHz/20ms
        const val HEADER_SIZE_BYTES = 12 // [4B seq][8B timestamp]
    }

    /** Set by CallSignalingManager when an OFFER carrying the caller's port arrives, before [startStreaming]. */
    var pendingRemotePort: Int? = null

    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    private var socket: DatagramSocket? = null
    private var captureThread: AudioCaptureThread? = null
    private var playbackThread: AudioPlaybackThread? = null
    private var remoteAddress: InetAddress? = null
    private var remotePort: Int = -1

    /** Opens and binds the local UDP socket to this peer's Wi-Fi Aware Network. Returns the local port, or -1 on failure. */
    fun prepare(): Int {
        socket?.takeUnless { it.isClosed }?.let { return it.localPort }
        val network = WifiAwareController.getService()?.getNetworkForPeer(peerID) ?: return -1
        val sock = try { DatagramSocket(0) } catch (_: Exception) { return -1 }
        return try {
            network.bindSocket(sock)
            socket = sock
            sock.localPort
        } catch (e: Exception) {
            sock.close()
            Log.e(TAG, "Failed to bind audio socket for ${peerID.take(8)}", e)
            -1
        }
    }

    /**
     * Starts playback (peer -> speaker) once the peer's UDP port is known, and remembers that
     * address/port so [startTransmitting] can start capture (mic -> peer) later without needing
     * it passed in again. Does NOT start capture - calls are push-to-talk, so nothing transmits
     * until the user actually holds the talk button (see [startTransmitting]).
     */
    fun startStreaming(remoteAddress: InetAddress, remotePort: Int) {
        val sock = checkNotNull(socket) { "Audio socket is not prepared" }
        check(!sock.isClosed) { "Audio socket is closed" }
        require(remotePort in 1..65535)
        this.remoteAddress = remoteAddress
        this.remotePort = remotePort
        Log.i(TAG, "Starting audio playback for call with ${peerID.take(8)} (peer at $remoteAddress:$remotePort)")
        playbackThread = AudioPlaybackThread(sock).also { it.start() }
    }

    /** Starts the mic capture thread. No-op if already transmitting or if called before [startStreaming]. */
    fun startTransmitting() {
        if (captureThread != null) return
        val sock = socket ?: return
        val addr = remoteAddress ?: run {
            Log.w(TAG, "startTransmitting($peerID) called before the remote endpoint was known")
            return
        }
        if (remotePort <= 0) return
        captureThread = AudioCaptureThread(sock, addr, remotePort, sequence).also { it.start() }
    }

    /** Stops the mic capture thread. Playback keeps running - the peer may still be talking to us. */
    fun stopTransmitting() {
        val thread = captureThread ?: return
        thread.shutdown()
        try { thread.join(300) } catch (_: Exception) { }
        captureThread = null
    }

    fun stop() {
        stopTransmitting()
        playbackThread?.shutdown()
        try { socket?.close() } catch (_: Exception) { }
        try { playbackThread?.join(500) } catch (_: Exception) { }
        playbackThread = null
        socket = null
        remoteAddress = null
        remotePort = -1
    }
}
