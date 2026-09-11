package com.campusmesh.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PersonAdd
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

/** Campus conversations with local search and discovery shortcuts. */
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
    val campusPresence by com.campusmesh.android.services.AppStateStore.campusPresence.collectAsStateWithLifecycle()
    val meshMessages by viewModel.messages.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }

    var showChannelsSheet by remember { mutableStateOf(false) }
    var showNewDirectMessageSheet by remember { mutableStateOf(false) }

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
        title = "Nearby chat",
        preview = meshMessages.lastOrNull()?.content ?: "Chat with nearby peers",
        timestampMillis = meshMessages.lastOrNull()?.timestamp?.time ?: 0L,
        unreadCount = null,
        hasUnread = false,
        avatarSeed = "mesh"
    )

    val recentEntries = (otherChannelEntries + privateChatEntries + listOf(meshEntry))
        .sortedByDescending { it.timestampMillis }

    val visibleEntries = recentEntries.filter { entry ->
        val matches = when (filter) {
            "Unread" -> entry.hasUnread || (entry.unreadCount ?: 0) > 0
            "Direct" -> entry.kind == EntryKind.PRIVATE
            "Channels" -> entry.kind != EntryKind.PRIVATE
            else -> true
        }
        matches && (query.isBlank() || entry.title.contains(query.trim(), ignoreCase = true))
    }

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

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatListHeader(
            peopleNearby = connectedPeers.size,
            onNewChatClick = { showChannelsSheet = true },
            onNewMessageClick = { showNewDirectMessageSheet = true },
            onMapClick = { viewModel.openMap() },
            onSettingsClick = { viewModel.openSettings() }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                CampusPresenceCard(campusPresence, onRefresh = {
                    com.campusmesh.android.geohash.LocationChannelManager.getInstance(viewModel.getApplication()).refreshChannels()
                })
            }
            item {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("Search conversations") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    shape = RoundedCornerShape(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Unread", "Direct", "Channels").forEach { label ->
                        FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                    }
                }
            }
            if (query.isBlank() && filter == "All") item {
                PinnedCampusBlock(
                    unreadChannelMessages = unreadChannelMessages,
                    onSubChannelClick = { name ->
                        viewModel.switchToChannel(name)
                        viewModel.openConversation()
                    }
                )
            }
            item {
                Text("Conversations", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            if (visibleEntries.isEmpty()) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (filter == "Unread" && query.isBlank()) "You're all caught up" else "No conversations found",
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (query.isNotBlank()) "Try another conversation name." else "Start a message or explore campus channels.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(visibleEntries, key = { it.kind.name + it.key }) { entry ->
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

    NewDirectMessageSheet(
        isPresented = showNewDirectMessageSheet,
        viewModel = viewModel,
        onDismiss = { showNewDirectMessageSheet = false }
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
    onNewMessageClick: () -> Unit,
    onMapClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CAMPUS MESH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Your campus, connected", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings and profile")
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (peopleNearby == 0) "No nearby peers connected" else "$peopleNearby nearby peer${if (peopleNearby == 1) "" else "s"} connected",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Nearby chat uses the local mesh", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                IconButton(onClick = onMapClick) {
                    Icon(Icons.Filled.Map, contentDescription = "Open campus map", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onNewMessageClick, modifier = Modifier.weight(1f)) { Text("New message") }
            OutlinedButton(onClick = onNewChatClick, modifier = Modifier.weight(1f)) { Text("Explore") }
        }
    }
}

@Composable
private fun PinnedCampusBlock(
    unreadChannelMessages: Map<String, Int>,
    onSubChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Main Campus is a folder, not a chat you open directly - tapping it just expands/collapses
    // its two named channels below. (The underlying Main Campus geohash location channel is still
    // reachable from the location channels sheet/pencil icon if it's ever needed again.)
    var expanded by remember { mutableStateOf(true) }
    val channelCount = MainCampusGeohash.SUB_CHANNELS.size

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
                    .clickable { expanded = !expanded }
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
                            fontSize = 16.sp,
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
                        text = "Pinned · $channelCount channel${if (channelCount == 1) "" else "s"}",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                MainCampusGeohash.SUB_CHANNELS.forEach { (name, emoji) ->
                    val unread = unreadChannelMessages[name] ?: 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSubChannelClick(name) }
                            .heightIn(min = 48.dp).padding(start = 46.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
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
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        UnreadBadge(kind = UnreadBadgeKind.CHANNEL, count = unread)
                    }
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
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
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.preview,
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
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

@Composable
private fun CampusPresenceCard(
    presence: com.campusmesh.android.geohash.CampusPresence,
    onRefresh: () -> Unit
) {
    val title = when (presence.status) {
        com.campusmesh.android.geohash.CampusPresence.Status.INSIDE -> presence.campusName ?: "On campus"
        com.campusmesh.android.geohash.CampusPresence.Status.GRACE -> "Campus location needs a recheck"
        com.campusmesh.android.geohash.CampusPresence.Status.OUTSIDE -> "Outside campus"
        com.campusmesh.android.geohash.CampusPresence.Status.UNKNOWN -> "Campus location unconfirmed"
    }
    val description = when (presence.status) {
        com.campusmesh.android.geohash.CampusPresence.Status.INSIDE -> "Campus detected. Student verification is separate."
        com.campusmesh.android.geohash.CampusPresence.Status.GRACE -> "Campus access is temporarily retained while location is uncertain."
        else -> "School posting needs campus presence. Nearby direct messages remain available when a route exists."
    }
    Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onRefresh) { Text("Recheck campus location") }
        }
    }
}
