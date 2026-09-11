package com.campusmesh.android.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.campusmesh.android.features.voice.VoiceRecorder
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun VoiceRecordButton(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    onStart: () -> Unit,
    onAmplitude: (amplitude: Int, elapsedMs: Long) -> Unit,
    onFinish: (filePath: String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }
    var recordingStart by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    var ampJob by remember { mutableStateOf<Job?>(null) }

    // Ensure latest callbacks are used inside gesture coroutine
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnAmplitude = rememberUpdatedState(onAmplitude)
    val latestOnFinish = rememberUpdatedState(onFinish)

    Box(
        modifier = modifier
            .size(48.dp) // was 32.dp - below Android's recommended 48dp min touch target, made it
                         // easy for a finger to drift off the button mid-hold (see gesture note below)
            .background(backgroundColor, CircleShape)
            .pointerInput(Unit) {
                // Was detectTapGestures(onPress = { ... awaitRelease() ... }), which is built for
                // discrete tap/long-press disambiguation: it cancels once a pointer drifts past
                // Android's touch-slop threshold, treating that as "not a tap anymore" even though
                // nothing else on screen was competing for the gesture. That's exactly what a
                // sustained hold-to-record button doesn't want - a finger naturally wanders a few
                // pixels during a multi-second hold, which was ending the recording after just a
                // second or two. This tracks press/first-down and release/all-up directly instead,
                // ignoring movement entirely in between.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    if (!isRecording) {
                        if (micPermission.status !is PermissionStatus.Granted) {
                            micPermission.launchPermissionRequest()
                            // Drain the rest of this gesture (wait for the finger to lift) so the
                            // same down event doesn't immediately re-trigger the next iteration.
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        val rec = VoiceRecorder(context)
                        val f = rec.start()
                        recorder = rec
                        isRecording = f != null
                        recordedFilePath = f?.absolutePath
                        recordingStart = System.currentTimeMillis()
                        if (isRecording) {
                            latestOnStart.value()
                            // Haptic "knock" when recording starts
                            try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                            // Start amplitude polling loop
                            ampJob?.cancel()
                            ampJob = scope.launch {
                                while (isActive && isRecording) {
                                    val amp = recorder?.pollAmplitude() ?: 0
                                    val elapsedMs = (System.currentTimeMillis() - recordingStart).coerceAtLeast(0L)
                                    latestOnAmplitude.value(amp, elapsedMs)
                                    // Auto-stop after 10 seconds
                                    if (elapsedMs >= 10_000 && isRecording) {
                                        val file = recorder?.stop()
                                        isRecording = false
                                        recorder = null
                                        val path = file?.absolutePath
                                        if (!path.isNullOrBlank()) {
                                            // Haptic "knock" on auto stop
                                            try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                            latestOnFinish.value(path)
                                        }
                                        break
                                    }
                                    delay(80)
                                }
                            }
                        }
                    }

                    try {
                        // Wait for every pointer to lift, regardless of how far any of them move
                        // in the meantime - see the comment above for why this replaced awaitRelease().
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        ampJob?.cancel()
                        ampJob = null
                        if (isRecording) {
                            // AwaitPointerEventScope (awaitEachGesture's receiver) is a restricted
                            // suspend scope - it can't call a plain kotlinx.coroutines.delay()
                            // directly, so the "extend recording briefly to avoid clipping the
                            // tail" delay has to happen in a normal coroutine instead. This also
                            // means the gesture detector itself isn't blocked for those 500ms, so
                            // a new press can start immediately if the user taps again right away.
                            scope.launch {
                                delay(500)
                                if (isRecording) {
                                    val file = recorder?.stop()
                                    isRecording = false
                                    recorder = null
                                    val path = (file?.absolutePath ?: recordedFilePath)
                                    recordedFilePath = null
                                    if (!path.isNullOrBlank()) {
                                        // Haptic "knock" when recording stops
                                        try { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } catch (_: Exception) {}
                                        latestOnFinish.value(path)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = stringResource(com.campusmesh.android.R.string.cd_record_voice),
            tint = Color.Black,
            modifier = Modifier.size(24.dp)
        )
    }
}
