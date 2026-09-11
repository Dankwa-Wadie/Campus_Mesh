package com.campusmesh.android.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Peer -> speaker. Receives UDP frames ([4B seq][8B timestampMs][PCM payload]) and writes them to
 * an AudioTrack. MVP has no jitter buffer (see implementation plan Phase 2) - it just drops
 * frames that arrive badly out of order (older than the last several played), which is the
 * correct failure mode for real-time voice (better to skip than to stall waiting for a retry).
 */
internal class AudioPlaybackThread(private val socket: DatagramSocket) : Thread("CallAudioPlayback") {

    companion object {
        private const val TAG = "AudioPlaybackThread"
        // How far behind the highest sequence number seen so far a frame can be and still play.
        // Anything older than this is almost certainly a stale/reordered duplicate this late in
        // the stream - drop it rather than let audio play out of order.
        private const val MAX_REORDER_WINDOW = 3
        private const val RECV_BUFFER_BYTES = 2048
    }

    @Volatile private var running = true

    fun shutdown() {
        running = false
        // Unblocks the blocking socket.receive() call below (CallAudioSession.stop() also closes
        // the socket itself, but do it defensively here too in case shutdown() is ever called
        // without a matching CallAudioSession.stop()).
        try { socket.close() } catch (_: Exception) { }
    }

    override fun run() {
        val sampleRate = CallAudioSession.SAMPLE_RATE_HZ
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0 || minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unsupported AudioTrack config (sampleRate=$sampleRate)")
            return
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, CallAudioSession.FRAME_SAMPLES * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack: ${e.message}", e)
            return
        }

        val recvBytes = ByteArray(RECV_BUFFER_BYTES)
        var highestSeqSeen = -1

        try {
            track.play()
            while (running) {
                val packet = DatagramPacket(recvBytes, recvBytes.size)
                try {
                    socket.receive(packet)
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "receive failed/socket closed: ${e.message}")
                    break
                }
                if (packet.length < CallAudioSession.HEADER_SIZE_BYTES) continue

                val bb = ByteBuffer.wrap(packet.data, packet.offset, packet.length)
                val seq = bb.int
                bb.long // timestamp - unused for now (no jitter buffer yet, see Phase 2)

                if (highestSeqSeen >= 0 && seq < highestSeqSeen - MAX_REORDER_WINDOW) continue
                if (seq > highestSeqSeen) highestSeqSeen = seq

                val payloadBytes = packet.length - CallAudioSession.HEADER_SIZE_BYTES
                val samples = ShortArray(payloadBytes / 2)
                for (i in samples.indices) samples[i] = bb.short
                if (samples.isNotEmpty()) {
                    track.write(samples, 0, samples.size)
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Playback loop error: ${e.message}", e)
        } finally {
            try { track.stop() } catch (_: Exception) { }
            try { track.release() } catch (_: Exception) { }
        }
    }
}
