package com.campusmesh.android.call

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Mic -> peer. Captures raw 16kHz mono 16-bit PCM in 20ms frames and sends each frame as one UDP
 * datagram: [4B seq][8B timestampMs][payload]. No compression (MVP - see implementation plan's
 * codec decision), no retransmission (real-time audio should drop, not retry, a late frame).
 *
 * VOICE_COMMUNICATION source is used (not MIC) so the platform applies hardware/software AEC and
 * noise suppression where available, matching how a normal phone call captures audio.
 */
internal class AudioCaptureThread(
    private val socket: DatagramSocket,
    private val remoteAddress: InetAddress,
    private val remotePort: Int,
    private val sequence: java.util.concurrent.atomic.AtomicInteger
) : Thread("CallAudioCapture") {

    companion object {
        private const val TAG = "AudioCaptureThread"
    }

    @Volatile private var running = true

    fun shutdown() {
        running = false
        try { interrupt() } catch (_: Exception) { }
    }

    override fun run() {
        val sampleRate = CallAudioSession.SAMPLE_RATE_HZ
        val frameSamples = CallAudioSession.FRAME_SAMPLES
        val frameBytes = frameSamples * 2

        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0 || minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Unsupported AudioRecord config (sampleRate=$sampleRate)")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, frameBytes * 4)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted: ${e.message}")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord: ${e.message}", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            try { record.release() } catch (_: Exception) { }
            return
        }

        val pcmBuf = ShortArray(frameSamples)
        val packetBytes = ByteArray(CallAudioSession.HEADER_SIZE_BYTES + frameBytes)
        // Sequence belongs to the call, not this individual talk turn.

        try {
            record.startRecording()
            while (running) {
                val read = record.read(pcmBuf, 0, frameSamples)
                if (!running) break
                if (read <= 0) {
                    if (read < 0) Log.w(TAG, "AudioRecord.read error code $read")
                    continue
                }
                val bb = ByteBuffer.wrap(packetBytes)
                bb.putInt(sequence.getAndIncrement())
                bb.putLong(System.currentTimeMillis())
                for (i in 0 until read) bb.putShort(pcmBuf[i])
                val len = CallAudioSession.HEADER_SIZE_BYTES + read * 2
                try {
                    socket.send(DatagramPacket(packetBytes, len, remoteAddress, remotePort))
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "send failed: ${e.message}")
                }

            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Capture loop error: ${e.message}", e)
        } finally {
            try { record.stop() } catch (_: Exception) { }
            try { record.release() } catch (_: Exception) { }
        }
    }
}
