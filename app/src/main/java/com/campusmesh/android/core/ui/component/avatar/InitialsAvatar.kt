package com.campusmesh.android.core.ui.component.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Campus Mesh avatar — initials on a colored circle, no photo upload (matches
 * campus_mesh_design_mockups.html and docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md §3/§4).
 *
 * Color and initials are both deterministic functions of [seed] (nickname or peerID) so every
 * device renders the same avatar for the same person with zero network sync required. This is
 * the fallback path described in the plan: once the avatar-customization TLV (planned addition to
 * model/IdentityAnnouncement.kt) is wired up, pass the peer's broadcast [overrideColor] /
 * [overrideGlyph] here instead of leaving them null — everything else about this composable
 * (size, shape, text styling) stays the same.
 */

// Fixed avatar palette - same hues used across chat list, conversation header, ChatUserSheet,
// and the Map screen's peer markers, so the same person always looks the same everywhere. Public
// (not private) so the Settings & Profile avatar picker can render one swatch per palette entry.
val AvatarPalette = listOf(
    Color(0xFF3A6FD1), // blue
    Color(0xFF8B5CF6), // purple
    Color(0xFFE08A2C), // orange
    Color(0xFF2AA198), // teal
    Color(0xFFDB5C93)  // pink
)

fun avatarColorFor(seed: String): Color {
    if (seed.isEmpty()) return AvatarPalette.first()
    val index = abs(seed.hashCode()) % AvatarPalette.size
    return AvatarPalette[index]
}

/** Safe lookup for a user-picked palette index (see AvatarPreferenceManager), null if out of range. */
fun avatarColorForIndex(index: Int?): Color? {
    if (index == null || index !in AvatarPalette.indices) return null
    return AvatarPalette[index]
}

fun initialsFor(nickname: String): String {
    val trimmed = nickname.trim()
    if (trimmed.isEmpty()) return "?"
    val parts = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        parts[0].length >= 2 -> parts[0].take(2).uppercase()
        else -> parts[0].uppercase()
    }
}

@Composable
fun InitialsAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    displayNickname: String = seed,
    overrideColor: Color? = null,
    goldRing: Boolean = false
) {
    val bg = overrideColor ?: avatarColorFor(seed)
    val fontSize = (size.value * 0.34f).sp

    Box(
        modifier = modifier
            .size(size)
            .background(bg, CircleShape)
            .then(
                if (goldRing) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initialsFor(displayNickname),
            color = Color.White,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize
        )
    }
}
