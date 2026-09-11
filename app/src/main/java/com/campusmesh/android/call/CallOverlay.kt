package com.campusmesh.android.call

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay

/**
 * Process-wide call UI, mounted once at the app root (see MainActivity) so it's visible
 * regardless of which top-level screen (chat list, a conversation, settings, map) is currently
 * showing - an incoming call doesn't care what you were looking at.
 *
 * Deliberately kept to two simple layouts rather than separate full IncomingCallScreen/
 * InCallScreen destinations (per the implementation plan) for the MVP: a centered card while
 * ringing (incoming or outgoing) and a slim bar while connected. Renders nothing in Idle/Ended.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CallOverlay() {
    val state by CallSignalingManager.callState.collectAsStateWithLifecycle()
    val micPermission = rememberPermissionState(android.Manifest.permission.RECORD_AUDIO)

    when (val s = state) {
        is CallState.IncomingRinging -> {
            IncomingCallCard(
                peerName = s.peerName,
                onAccept = {
                    if (micPermission.status !is PermissionStatus.Granted) {
                        micPermission.launchPermissionRequest()
                    } else {
                        CallSignalingManager.acceptCall()
                    }
                },
                onDecline = { CallSignalingManager.rejectCall() }
            )
        }
        is CallState.Outgoing -> {
            OutgoingCallCard(peerName = s.peerName, onCancel = { CallSignalingManager.endCall() })
        }
        is CallState.Connected -> {
            ConnectedCallBar(peerName = s.peerName, startedAt = s.startedAt, onHangUp = { CallSignalingManager.endCall() })
        }
        is CallState.Ended, CallState.Idle -> { /* nothing to show */ }
    }
}

@Composable
private fun IncomingCallCard(peerName: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.padding(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Incoming call",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    CallActionButton(
                        icon = Icons.Filled.CallEnd,
                        backgroundColor = Color(0xFFE53935),
                        contentDescription = "Decline call",
                        onClick = onDecline
                    )
                    CallActionButton(
                        icon = Icons.Filled.Call,
                        backgroundColor = Color(0xFF00C851),
                        contentDescription = "Accept call",
                        onClick = onAccept
                    )
                }
            }
        }
    }
}

@Composable
private fun OutgoingCallCard(peerName: String, onCancel: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.padding(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Calling…",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(24.dp))
                CallActionButton(
                    icon = Icons.Filled.CallEnd,
                    backgroundColor = Color(0xFFE53935),
                    contentDescription = "Cancel call",
                    onClick = onCancel
                )
            }
        }
    }
}

/**
 * Calls are half-duplex (push-to-talk) - see [TalkFloor]'s doc comment for why full duplex was
 * dropped. The top bar (name/duration/hang-up) stays as before; below it is a big hold-to-talk mic
 * button that drives [CallSignalingManager.beginTalking]/[CallSignalingManager.endTalking] on
 * press/release, plus a status line reflecting [TalkFloor] so both sides can see who currently has
 * the floor.
 */
@Composable
private fun ConnectedCallBar(peerName: String, startedAt: Long, onHangUp: () -> Unit) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(startedAt) {
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000
            delay(1000)
        }
    }
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val durationText = "%d:%02d".format(minutes, seconds)
    val talkFloor by CallSignalingManager.talkFloor.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF00C851),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "On call with $peerName", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text(text = durationText, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onHangUp) {
                    Icon(imageVector = Icons.Filled.CallEnd, contentDescription = "Hang up", tint = Color.White)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val statusText = when (talkFloor) {
                TalkFloor.LOCAL_TALKING -> "Talking…"
                TalkFloor.REMOTE_TALKING -> "$peerName is talking…"
                TalkFloor.IDLE -> "Hold to talk"
            }
            Text(
                text = statusText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            TalkButton(talkFloor = talkFloor)
        }
    }
}

@Composable
private fun TalkButton(talkFloor: TalkFloor) {
    val locked = talkFloor == TalkFloor.REMOTE_TALKING
    val backgroundColor = when (talkFloor) {
        TalkFloor.LOCAL_TALKING -> Color(0xFFE53935)
        TalkFloor.REMOTE_TALKING -> MaterialTheme.colorScheme.surfaceVariant
        TalkFloor.IDLE -> Color(0xFF3B82F6)
    }
    Box(
        modifier = Modifier
            .size(88.dp)
            .background(backgroundColor, CircleShape)
            .pointerInput(locked) {
                if (locked) return@pointerInput
                // Deliberately not detectTapGestures(onPress = { ... tryAwaitRelease() ... }):
                // that's built for discrete tap/long-press disambiguation and cancels once a
                // pointer drifts past Android's touch-slop threshold - exactly wrong for a
                // sustained hold, where a finger naturally wanders a bit (this is the same bug
                // that was cutting the chat voice-note recorder off early - see
                // VoiceInputComponents.kt). Track press/release directly instead, ignoring
                // movement in between.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    CallSignalingManager.beginTalking()
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        CallSignalingManager.endTalking()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = if (locked) "Peer is talking" else "Hold to talk",
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(backgroundColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}
