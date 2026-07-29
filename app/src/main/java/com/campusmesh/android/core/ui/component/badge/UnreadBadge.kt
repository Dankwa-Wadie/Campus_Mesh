package com.campusmesh.android.core.ui.component.badge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Two-tone unread badge, matching campus_mesh_design_mockups.html and plan §4:
 * gold for channel/group unread counts, blue for direct-message unread counts.
 * The color difference is a deliberate piece of visual language (community vs. personal),
 * not a random per-row color - always pass the correct [kind] rather than picking one.
 *
 * [count] is optional: the existing private-chat state (ChatState.unreadPrivateMessages) only
 * tracks a Set<String> of peer IDs with unread messages, not a real count, so DM rows should pass
 * count = null (renders as a small dot) rather than a fabricated number. Channel unread
 * (ChatState.unreadChannelMessages) IS a real Map<String, Int>, so channel rows can pass the
 * actual count.
 */
enum class UnreadBadgeKind { CHANNEL, DIRECT_MESSAGE }

@Composable
fun UnreadBadge(
    kind: UnreadBadgeKind,
    modifier: Modifier = Modifier,
    count: Int? = null
) {
    if (count != null && count <= 0) return
    val colorScheme = MaterialTheme.colorScheme
    val bg = if (kind == UnreadBadgeKind.CHANNEL) colorScheme.secondary else colorScheme.primary
    val fg = if (kind == UnreadBadgeKind.CHANNEL) colorScheme.onSecondary else colorScheme.onPrimary

    if (count == null) {
        Box(
            modifier = modifier
                .size(10.dp)
                .background(bg, CircleShape)
        )
        return
    }

    val label = if (count > 99) "99+" else count.toString()
    Box(
        modifier = modifier
            .widthIn(min = 18.dp)
            .height(18.dp)
            .background(bg, CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 10.5.sp
        )
    }
}
