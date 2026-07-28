package com.campusmesh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Emergency alert banner displayed at the top of the screen when a multi-signed
 * /alert packet is received from a verified Lecturer or Admin.
 *
 * The banner slides in from the top with a red gradient background and can be
 * dismissed by tapping the close (×) button.
 *
 * @param message     The alert message to display.
 * @param senderName  Display name of the verified sender (e.g. "Dr. Kofi Mensah").
 * @param isVisible   Controls whether the banner is shown.
 * @param onDismiss   Callback when the user taps the close button.
 */
@Composable
fun EmergencyBanner(
    message: String,
    senderName: String,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color(0xFFB71C1C), Color(0xFFD32F2F))
                    ),
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Alert icon
                Text(
                    text = "⚠️",
                    fontSize = 22.sp,
                    modifier = Modifier.padding(end = 12.dp, top = 2.dp)
                )

                // Text content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OFFICIAL EMERGENCY ALERT",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message,
                        color = Color(0xFFFFCDD2),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "— $senderName (Verified Staff)",
                        color = Color(0xFFEF9A9A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Close button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(text = "×", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
