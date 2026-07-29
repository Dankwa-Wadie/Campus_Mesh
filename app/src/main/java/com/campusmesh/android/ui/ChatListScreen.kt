package com.campusmesh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.android.core.ui.component.avatar.InitialsAvatar
import com.campusmesh.android.core.ui.component.badge.UnreadBadge
import com.campusmesh.android.core.ui.component.badge.UnreadBadgeKind
import com.campusmesh.android.geohash.ChannelID
import com.campusmesh.android.geohash.MainCampusGeohash
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Chat list - the app's new home screen (docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md Phase 2).
 * Pinned Main Campus + its 3 fixed sub-channels, then every other joined channel, private chat,
 * and the general Mesh timeline, merged into one recency-sorted list - matching
 * campus_mesh_design_mockups.html. Tapping any row opens the existing ChatScreen as a "pushed"
 * conversation detail screen (see ChatViewModel.openConversation/returnToChatList).
 *
 * Sourced entirely from existing ChatViewModel state - no backend changes, per the plan.
 *
 * Known gaps, deliberately left for their own phases rather than half-built here:
 * the search icon is a placeholder (no chat search exists yet anywhere in the app), and there's no
 * settings-gear or map-pin icon yet since Settings & Profile (Phase 5) and Map (Phase 6) don't
 * exist as screens yet yet. Both get added to this header once those phases land.
 */
@Composable
fun ChatListScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val unreadChannelMessages by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val unreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val meshMessages by viewModel.messages.collectAsStateWithLifecycle()

    var showChannelsSheet by remember { mutableStateOf(false) }

    val subChannelNames = remember { MainCampusGeohash.SUB_CHANNELS.map { it.first }.toSet() }

    val otherChannelEntries = joinedChannels
        .filter { it !in subChannelNames }
        .map { name ->
            val last = channelMessages[name]?.lastOrNull()
            ConversationEntry(
                key = name,
                kind = EntryKind.CHANNEL,
                title = name,
                preview = last?.content ?: "No messages yet",
                timestampMillis = last?.timestamp?.time ?: 0L,
                unreadCount = unreadChannelMessages[name] ?: 0,
                hasUnread = false,
                avatarSeed = name
            )
        }

    val privateChatEntries = privateChats.keys.map { peerID ->
        val last = privateChats[peerID]?.lastOrNull()
        val displayName = peerNicknames[peerID] ?: peerID
        ConversationEntry(
            key = peerID,
            kind = EntryKind.PRIVATE,
            title = displayName,
            preview = last?.content ?: "",
            timestampMillis = last?.timestamp?.time ?: 0L,
            unreadCount = null,
            hasUnread = unreadPrivateMessages.contains(peerID),
            avatarSeed = displayName
        )
    }

    val meshEntry = ConversationEntry(
        key = "mesh",
        kind = EntryKind.MESH,
        title = "Mesh",
        preview = "${connectedPeers.size} people nearby",
        timestampMillis = meshMessages.lastOrNull()?.timestamp?.time ?: 0L,
        unreadCount = null,
        hasUnread = false,
        avatarSeed = "mesh"
    )

    val recentEntries = (otherChannelEntries + privateChatEntries + listOf(meshEntry))
        .sortedByDescending { it.timestampMillis }

    fun openEntry(entry: ConversationEntry) {
        when (entry.kind) {
            EntryKind.CHANNEL -> {
                viewModel.switchToChannel(entry.key)
            }
            EntryKind.PRIVATE -> {
                viewModel.startPrivateChat(entry.key)
            }
            EntryKind.MESH -> {
                viewModel.switchToChannel(null)
                viewModel.selectLocationChannel(ChannelID.Mesh)
            }
        }
        viewModel.openConversation()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ChatListHeader(
            peopleNearby = connectedPeers.size,
            onNewChatClick = { showChannelsSheet = true },
            onMapClick = { viewModel.openMap() },
            onSettingsClick = { viewModel.openSettings() }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                PinnedCampusBlock(
                    unreadChannelMessages = unreadChannelMessages,
                    onCampusClick = {
                        viewModel.switchToChannel(null)
                        viewModel.selectLocationChannel(ChannelID.Location(MainCampusGeohash.CHANNEL))
                        viewModel.openConversation()
                    },
                    onSubChannelClick = { name ->
                        viewModel.switchToChannel(name)
                        viewModel.openConversation()
                    }
                )
            }
            items(recentEntries, key = { it.kind.name + it.key }) { entry ->
                ConversationRow(entry = entry, onClick = { openEntry(entry) })
            }
        }
    }

    LocationChannelsSheet(
        isPresented = showChannelsSheet,
        onDismiss = { showChannelsSheet = false },
        viewModel = viewModel,
        onOpenMap = { viewModel.openMap() }
    )
}

private enum class EntryKind { CHANNEL, PRIVATE, MESH }

private data class ConversationEntry(
    val key: String,
    val kind: EntryKind,
    val title: String,
    val preview: String,
    val timestampMillis: Long,
    val unreadCount: Int?,
    val hasUnread: Boolean,
    val avatarSeed: String
)

@Composable
private fun ChatListHeader(
    peopleNearby: Int,
    onNewChatClick: () -> Unit,
    onMapClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            // The theme draws edge-to-edge (transparent status bar, see values/themes.xml), so
            // without this the header row renders underneath the status bar/notch - too high up
            // and with part of its touch targets obscured by system chrome. This pushes the actual
            // content below the status bar, matching the pattern already used in ChatScreen.kt's
            // header.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "  Campus Mesh",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Row {
                IconButton(onClick = onMapClick) {
                    Icon(
                        imageVector = Icons.Filled.Map,
                        contentDescription = "Campus map",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(onClick = { /* chat search - not built yet, tracked in plan */ }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(onClick = onNewChatClick) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Channels and nearby",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings and profile",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        Text(
            text = "$peopleNearby people nearby",
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.5.sp
        )
    }
}

@Composable
private fun PinnedCampusBlock(
    unreadChannelMessages: Map<String, Int>,
    onCampusClick: () -> Unit,
    onSubChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCampusClick() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🏫", fontSize = 17.sp)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Main Campus",
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(12.dp)
                        )
                    }
                    Text(
                        text = "Pinned · 3 channels",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MainCampusGeohash.SUB_CHANNELS.forEach { (name, emoji) ->
                val unread = unreadChannelMessages[name] ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSubChannelClick(name) }
                        .padding(start = 46.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = emoji, fontSize = 14.sp)
                    Text(
                        text = name,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    UnreadBadge(kind = UnreadBadgeKind.CHANNEL, count = unread)
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    entry: ConversationEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.kind == EntryKind.MESH) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            InitialsAvatar(seed = entry.avatarSeed)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Text(
                text = entry.title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.preview,
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            if (entry.timestampMillis > 0L) {
                Text(
                    text = formatRelativeTime(entry.timestampMillis),
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(modifier = Modifier.padding(top = 4.dp)) {
                when (entry.kind) {
                    EntryKind.CHANNEL -> UnreadBadge(kind = UnreadBadgeKind.CHANNEL, count = entry.unreadCount)
                    EntryKind.PRIVATE -> UnreadBadge(
                        kind = UnreadBadgeKind.DIRECT_MESSAGE,
                        count = if (entry.hasUnread) null else 0
                    )
                    EntryKind.MESH -> {}
                }
            }
        }
    }
}

private fun formatRelativeTime(timestampMillis: Long): String {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(java.util.Date(timestampMillis))
}
