package com.campusmesh.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.campusmesh.android.data.AppUpdateNotice

/**
 * Top-banner prompt displayed when an [AppUpdateNotice] is received,
 * either via GitHub Release check (online) or epidemic BLE/Wi-Fi Aware broadcast (offline).
 *
 * Tapping "Install" triggers the P2P APK download or direct install flow.
 *
 * @param notice        The update notice to display.
 * @param isVisible     Controls visibility (slides in from top).
 * @param isDownloading True while the APK is being downloaded/assembled.
 * @param progress      0.0–1.0 download progress value.
 * @param onInstall     Called when user taps the install button.
 * @param onDismiss     Called when user dismisses the banner.
 */
@Composable
fun UpdatePromptDialog(
    notice: AppUpdateNotice,
    isVisible: Boolean,
    isDownloading: Boolean = false,
    progress: Float = 0f,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1A237E).copy(alpha = 0.85f),
                            Color(0xFF283593).copy(alpha = 0.85f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📦", fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "New Campus Mesh Update Available",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${notice.newVersionName} · ${notice.apkSizeDisplay} · " +
                                    (if (notice.githubReleaseUrl != null) "Online" else "Received from a nearby peer"),
                            color = Color(0xFF9FA8DA),
                            fontSize = 11.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Color(0xFF7986CB),
                        trackColor = Color(0xFF3949AB),
                    )
                    Text(
                        text = "Downloading… ${(progress * 100).toInt()}%",
                        color = Color(0xFF9FA8DA),
                        fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        textAlign = TextAlign.End
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5C6BC0),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Download & Install", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
