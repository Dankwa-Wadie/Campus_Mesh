package com.campusmesh.android.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.campusmesh.android.R
import kotlinx.coroutines.delay

/**
 * Matrix-style file sending animation with character-by-character reveal
 * Shows a file icon with filename being "typed" out character by character
 * and progress visualization
 */
@Composable
fun FileSendingAnimation(
    modifier: Modifier = Modifier,
    fileName: String,
    progress: Float = 0f
) {
    var revealedChars by remember(fileName) { mutableFloatStateOf(0f) }
    var showCursor by remember { mutableStateOf(true) }

    // Animate character reveal
    val animatedChars by animateFloatAsState(
        targetValue = revealedChars,
        animationSpec = tween(
            durationMillis = 50 * fileName.length,
            easing = LinearEasing
        ),
        label = "fileNameReveal"
    )

    // Cursor blinking
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            showCursor = !showCursor
        }
    }

    // Trigger reveal animation
    LaunchedEffect(fileName) {
        revealedChars = fileName.length.toFloat()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // File icon - old-brand green replaced with the real GCTU primary blue (Phase 8 polish pass)
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = stringResource(R.string.cd_file),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Filename reveal animation (Matrix-style)
            Row(verticalAlignment = Alignment.Bottom) {
                // Revealed part of filename
                // Hardcoded white replaced with onSurface - this renders inside the new message
                // bubble (MessageComponents.kt), whose background is primaryContainer/surfaceVariant
                // in both themes, not the old terminal-black; white text would be low/no-contrast
                // there (Phase 8 polish pass).
                val revealedText = fileName.substring(0, animatedChars.toInt())
                androidx.compose.material3.Text(
                    text = revealedText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(end = 2.dp)
                )

                // Blinking cursor (only if not fully revealed)
                if (animatedChars < fileName.length && showCursor) {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.underscore),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }

            // Progress visualization
            FileProgressBars(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(20.dp)
            )
        }
    }
}

/**
 * File transfer progress - a real Material progress bar with a percentage label, replacing the
 * old ASCII "[####------] 60%" Matrix-green bracket string (Phase 8 polish pass). This is the
 * component the design mockup's file-transfer bubble ("Lab_Assignment_Sem2... - 4.2 MB - Sending
 * 60%") describes re-skinning - see docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md §4.
 */
@Composable
private fun FileProgressBars(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(5.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        androidx.compose.material3.Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}
