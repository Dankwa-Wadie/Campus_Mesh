package com.campusmesh.android.ui
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel


import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.android.model.BitchatMessage
import com.campusmesh.android.ui.media.FullScreenImageViewer

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutSheet: App info and password prompts
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    // #gctu-announcements is lecturer-only and soft-geofenced: students can read it, but posting is lecturer-only.
    // Additionally, reading/posting requires being physically on either campus.
    val currentAppMode by com.campusmesh.android.services.AppStateStore.currentAppMode.collectAsStateWithLifecycle()
    val isUserOnCampus = currentAppMode == com.campusmesh.android.data.AppMode.MAIN_CAMPUS || currentAppMode == com.campusmesh.android.data.AppMode.ABEKA_CAMPUS
    val isAnnouncementsLocked = currentChannel == "#gctu-announcements" && !isUserOnCampus
    val isAnnouncementsReadOnly = currentChannel == "#gctu-announcements" && role != "Lecturer" && isUserOnCampus
    
    // #campus-mesh is soft-geofenced to Main Campus
    val isCampusMeshLocked = currentChannel == "#campus-mesh" && currentAppMode != com.campusmesh.android.data.AppMode.MAIN_CAMPUS

    // #abeka-it-business is soft-geofenced to Abeka Campus
    val isAbekaItLocked = currentChannel == "#abeka-it-business" && currentAppMode != com.campusmesh.android.data.AppMode.ABEKA_CAMPUS

    val isComposerReadOnly = isAnnouncementsReadOnly || isAnnouncementsLocked || isCampusMeshLocked || isAbekaItLocked
    val composerReadOnlyMessage = when {
        isAnnouncementsLocked -> "📍 Only available on campus"
        isAnnouncementsReadOnly -> "🔒 Only lecturers can post here"
        isCampusMeshLocked -> "📍 Only available on campus"
        isAbekaItLocked -> "📍 Only available on campus"
        else -> ""
    }
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val showCommandSuggestions by viewModel.showCommandSuggestions.collectAsStateWithLifecycle()
    val commandSuggestions by viewModel.commandSuggestions.collectAsStateWithLifecycle()
    val showMentionSuggestions by viewModel.showMentionSuggestions.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val showMeshPeerListSheet by viewModel.showMeshPeerList.collectAsStateWithLifecycle()
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()
    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }
    var showLocationNotesSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }
    var showInviteQrDialog by remember { mutableStateOf(false) }

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    // Campus-preset channel auto-join/leave now lives in MainActivity (see its
    // OnboardingState.COMPLETE branch) instead of here, since this composable (ChatScreen, the
    // pushed conversation-detail screen) isn't guaranteed to ever be composed - a user who only
    // ever looks at the chat list would never trigger it, which is exactly how a stale
    // auto-joined #general-mesh channel kept showing up in the chat list even after the preset
    // was removed. MainActivity's branch is a sibling to whichever of the four top-level screens
    // (chat list/conversation/settings/map) is showing, so it always runs regardless.

    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val passwordPromptChannel by viewModel.passwordPromptChannel.collectAsStateWithLifecycle()

    // Get location channel info for timeline switching
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()

    // Determine what messages to show based on current context (unified timelines)
    // Legacy private chat timeline removed - private chats now exclusively use PrivateChatSheet
    val displayMessages = when {
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is com.campusmesh.android.geohash.ChannelID.Location) {
                val geokey = "geo:${locationChannel.channel.geohash}"
                channelMessages[geokey] ?: emptyList()
            } else if (locationChannel is com.campusmesh.android.geohash.ChannelID.Mesh) {
                val localMsgs = messages
                val ecMsgs = channelMessages["geo:ec"] ?: emptyList()
                (localMsgs + ecMsgs).distinctBy { it.id }.sortedBy { it.timestamp }
            } else {
                messages
            }
        }
    }

    // Determine whether to show media buttons (only hide in geohash location chats)
    val showMediaButtons = when {
        currentChannel != null -> true
        else -> selectedLocationChannel !is com.campusmesh.android.geohash.ChannelID.Location
    }

    // Use WindowInsets to handle keyboard properly
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background) // Extend background to fill entire screen including status bar
    ) {
        val headerHeight = 42.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
                .windowInsetsPadding(WindowInsets.navigationBars) // Add bottom padding when keyboard is not expanded
        ) {
            // Header spacer - creates exact space for the floating header (status bar + compact header)
            Spacer(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(headerHeight)
            )

            // Dynamic geohash awareness banner for #ec (Global Mesh)
            val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
            val isMeshChannel = currentChannel == null && selectedLocationChannel is com.campusmesh.android.geohash.ChannelID.Mesh
            if (isMeshChannel) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🌍",
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Welcome to Global Mesh (#ec)! This covers almost the whole of Ghana, allowing you to connect with other Ghanaians and users from other countries.",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Messages area - takes up available space, will compress when keyboard appears
            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                meshService = viewModel.meshServiceFacade,
                modifier = Modifier.weight(1f),
                forceScrollToBottom = forceScrollToBottom,
                onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                onNicknameClick = { fullSenderName ->
                    // Single click - mention user in text input
                    val currentText = messageText.text
                    
                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                    
                    // Check if we're in a geohash channel to include hash suffix
                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                    val mentionText = if (selectedLocationChannel is com.campusmesh.android.geohash.ChannelID.Location && hashSuffix.isNotEmpty()) {
                        // In geohash chat - include the hash suffix from the full display name
                        "@$baseName$hashSuffix"
                    } else {
                        // Regular chat - just the base nickname
                        "@$baseName"
                    }
                    
                    val newText = when {
                        currentText.isEmpty() -> "$mentionText "
                        currentText.endsWith(" ") -> "$currentText$mentionText "
                        else -> "$currentText $mentionText "
                    }
                    
                    messageText = TextFieldValue(
                        text = newText,
                        selection = TextRange(newText.length)
                    )
                },
                onMessageLongPress = { message ->
                    // Message long press - open user action sheet with message context
                    // Extract base nickname from message sender (contains all necessary info)
                    val (baseName, _) = splitSuffix(message.sender)
                    selectedUserForSheet = baseName
                    selectedMessageForSheet = message
                    showUserSheet = true
                },
                onCancelTransfer = { msg ->
                    viewModel.cancelMediaSend(msg.id)
                },
                onImageClick = { currentPath, allImagePaths, initialIndex ->
                    viewerImagePaths = allImagePaths
                    initialViewerIndex = initialIndex
                    showFullScreenImageViewer = true
                }
            )
            // Input area - stays at bottom
        // Bridge file share from lower-level input to ViewModel
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.campusmesh.android.ui.events.FileShareDispatcher.setHandler { peer, channel, path ->
            viewModel.sendFileNote(peer, channel, path)
        }
    }

    ChatInputSection(
        messageText = messageText,
        onMessageTextChange = { newText: TextFieldValue ->
            messageText = newText
            viewModel.updateCommandSuggestions(newText.text)
            viewModel.updateMentionSuggestions(newText.text)
        },
        onSend = {
            if (messageText.text.trim().isNotEmpty()) {
                viewModel.sendMessage(messageText.text.trim())
                messageText = TextFieldValue("")
                forceScrollToBottom = !forceScrollToBottom // Toggle to trigger scroll
            }
        },
        onSendVoiceNote = { peer, onionOrChannel, path ->
            viewModel.sendVoiceNote(peer, onionOrChannel, path)
        },
        onSendImageNote = { peer, onionOrChannel, path ->
            viewModel.sendImageNote(peer, onionOrChannel, path)
        },
        onSendFileNote = { peer, onionOrChannel, path ->
            viewModel.sendFileNote(peer, onionOrChannel, path)
        },
        
        showCommandSuggestions = showCommandSuggestions,
        commandSuggestions = commandSuggestions,
        showMentionSuggestions = showMentionSuggestions,
        mentionSuggestions = mentionSuggestions,
        onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                    val commandText = viewModel.selectCommandSuggestion(suggestion)
                    messageText = TextFieldValue(
                        text = commandText,
                        selection = TextRange(commandText.length)
                    )
                },
                onMentionSuggestionClick = { mention: String ->
                    val mentionText = viewModel.selectMentionSuggestion(mention, messageText.text)
                    messageText = TextFieldValue(
                        text = mentionText,
                        selection = TextRange(mentionText.length)
                    )
                },
                selectedPrivatePeer = null,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme,
                showMediaButtons = showMediaButtons,
                readOnly = isComposerReadOnly,
                readOnlyMessage = composerReadOnlyMessage
            )
        }

        // Floating header - positioned absolutely at top, ignores keyboard
        ChatFloatingHeader(
            headerHeight = headerHeight,
            selectedPrivatePeer = null,
            currentChannel = currentChannel,
            nickname = nickname,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showMeshPeerList() },
            onLocationChannelsClick = { showLocationChannelsSheet = true },
            onLocationNotesClick = { showLocationNotesSheet = true },
            onInvitePeersClick = { showInviteQrDialog = true }
        )

        // Divider under header - positioned after status bar + header height
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(y = headerHeight)
                .zIndex(1f),
            color = colorScheme.outline.copy(alpha = 0.3f)
        )

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 64.dp)
                .zIndex(1.5f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.background,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                // Old-brand green replaced with the real GCTU primary blue (Phase 8 polish pass)
                border = BorderStroke(2.dp, colorScheme.primary)
            ) {
                IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(com.campusmesh.android.R.string.cd_scroll_to_bottom),
                        tint = colorScheme.primary
                    )
                }
            }
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
    }

    // Dialogs and Sheets
    ChatDialogs(
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showLocationChannelsSheet = showLocationChannelsSheet,
        onLocationChannelsSheetDismiss = { showLocationChannelsSheet = false },
        showLocationNotesSheet = showLocationNotesSheet,
        onLocationNotesSheetDismiss = { showLocationNotesSheet = false },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null // Reset message when dismissing
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel,
        showVerificationSheet = showVerificationSheet,
        onVerificationSheetDismiss = viewModel::hideVerificationSheet,
        showSecurityVerificationSheet = showSecurityVerificationSheet,
        onSecurityVerificationSheetDismiss = viewModel::hideSecurityVerificationSheet,
        showMeshPeerListSheet = showMeshPeerListSheet,
        onMeshPeerListDismiss = viewModel::hideMeshPeerList,
    )

    // Invite Nearby Peers QR (Wi-Fi hotspot + PWA gateway, started automatically by
    // MeshForegroundService — this dialog just surfaces the already-running gateway's QR/URL)
    if (showInviteQrDialog) {
        HotspotQrDialog(onDismiss = { showInviteQrDialog = false })
    }
}

@Composable
fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean,
    readOnly: Boolean = false,
    readOnlyMessage: String = "🔒 Only lecturers can post here"
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.background
    ) {
        Column {
            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
            // Command suggestions box
            if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onCommandSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            // Mention suggestions box
            if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsBox(
                    suggestions = mentionSuggestions,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            TransportNotice(
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel
            )
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = onSendFileNote,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = showMediaButtons,
                readOnly = readOnly,
                readOnlyMessage = readOnlyMessage,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    onInvitePeersClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locationManager = remember { com.campusmesh.android.geohash.LocationChannelManager.getInstance(context) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars), // Extend into status bar area
        color = colorScheme.background // Solid background color extending into status bar
    ) {
        TopAppBar(
            title = {
                ChatHeaderContent(
                    selectedPrivatePeer = selectedPrivatePeer,
                    currentChannel = currentChannel,
                    nickname = nickname,
                    viewModel = viewModel,
                    onBackClick = {
                        when {
                            selectedPrivatePeer != null -> viewModel.endPrivateChat()
                            currentChannel != null -> {
                                viewModel.switchToChannel(null)
                                viewModel.returnToChatList()
                            }
                            // Plain Global Mesh timeline - reached via a "push" from the chat
                            // list (ChatListScreen's EntryKind.MESH), so back returns there too.
                            else -> viewModel.returnToChatList()
                        }
                    },
                    onSidebarClick = onSidebarToggle,
                    onLocationChannelsClick = onLocationChannelsClick,
                    onLocationNotesClick = {
                        // Ensure location is loaded before showing sheet
                        locationManager.refreshChannels()
                        onLocationNotesClick()
                    },
                    onInvitePeersClick = onInvitePeersClick
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            ),
            modifier = Modifier.height(headerHeight) // Ensure compact header height
        )
    }
}

/**
 * Joins the preset channels for a campus mode. Off-campus (GENERAL_MESH) doesn't force-leave
 * anything -- it just stops auto-joining campus-only channels going forward. Called from a
 * [com.campusmesh.android.services.AppStateStore.currentAppMode] collector in MainActivity (not
 * this file - see MainActivity's OnboardingState.COMPLETE branch), which reacts to
 * [com.campusmesh.android.geohash.LocationChannelManager]'s geofence detection now that there
 * is no manual mode-picker UI (Main Campus is reached directly via the pinned geohash channel in
 * LocationChannelsSheet). `internal` rather than `private` so MainActivity (a different file) can
 * call it.
 *
 * Only calls [ChatViewModel.joinChannel] for channels not already joined: that function also
 * *switches* the active channel view when the channel is already joined (matches its use
 * elsewhere as a `/join` command), so calling it unconditionally here would yank the user's
 * current view to a preset channel every time the mode re-confirms, not just on first entry.
 */
internal fun applyChannelsForMode(mode: com.campusmesh.android.data.AppMode, viewModel: ChatViewModel) {
    val presets = when (mode) {
        com.campusmesh.android.data.AppMode.MAIN_CAMPUS -> listOf("#gctu-announcements", "#campus-mesh")
        com.campusmesh.android.data.AppMode.ABEKA_CAMPUS -> listOf("#gctu-announcements", "#abeka-it-business")
        // Off-campus no longer auto-joins a preset channel - the plain "Mesh" entry (raw BLE
        // broadcast, already in the chat list) covers this case, so #general-mesh was a
        // redundant duplicate entry. Also leave it below for anyone already joined from before
        // this change.
        com.campusmesh.android.data.AppMode.GENERAL_MESH -> emptyList()
    }
    val alreadyJoined = viewModel.joinedChannels.value
    presets.forEach { channel ->
        if (channel !in alreadyJoined) {
            viewModel.joinChannel(channel)
        }
    }
    if ("#general-mesh" in alreadyJoined) {
        viewModel.leaveChannel("#general-mesh")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
    showLocationChannelsSheet: Boolean,
    onLocationChannelsSheetDismiss: () -> Unit,
    showLocationNotesSheet: Boolean,
    onLocationNotesSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel,
    showVerificationSheet: Boolean,
    onVerificationSheetDismiss: () -> Unit,
    showSecurityVerificationSheet: Boolean,
    onSecurityVerificationSheetDismiss: () -> Unit,
    showMeshPeerListSheet: Boolean,
    onMeshPeerListDismiss: () -> Unit,
) {
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()

    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )

    // App info, theme, mesh/network settings, and emergency data deletion all now live in
    // SettingsScreen.kt instead of a separate AboutSheet - see SettingsScreen's "About App",
    // "Appearance", "Mesh & Network", and "Sensitive Settings" sections. Debug Settings moved
    // there too (bottom of "Sensitive Settings").

    // Location channels sheet
    if (showLocationChannelsSheet) {
        LocationChannelsSheet(
            isPresented = showLocationChannelsSheet,
            onDismiss = onLocationChannelsSheetDismiss,
            viewModel = viewModel,
            onOpenMap = { viewModel.openMap() }
        )
    }
    
    // Location notes sheet (extracted to separate presenter)
    if (showLocationNotesSheet) {
        LocationNotesSheetPresenter(
            viewModel = viewModel,
            onDismiss = onLocationNotesSheetDismiss
        )
    }
    
    // User action sheet
    if (showUserSheet) {
        ChatUserSheet(
            isPresented = showUserSheet,
            onDismiss = onUserSheetDismiss,
            targetNickname = selectedUserForSheet,
            selectedMessage = selectedMessageForSheet,
            viewModel = viewModel
        )
    }
    // MeshPeerList sheet (network view)
    if (showMeshPeerListSheet){
        MeshPeerListSheet(
            isPresented = showMeshPeerListSheet,
            viewModel = viewModel,
            onDismiss = onMeshPeerListDismiss,
            onShowVerification = {
                onMeshPeerListDismiss()
                viewModel.showVerificationSheet(fromSidebar = true)
            }
        )
    }

    if (showVerificationSheet) {
        VerificationSheet(
            isPresented = showVerificationSheet,
            onDismiss = onVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (showSecurityVerificationSheet) {
        SecurityVerificationSheet(
            isPresented = showSecurityVerificationSheet,
            onDismiss = onSecurityVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (privateChatSheetPeer != null) {
        PrivateChatSheet(
            isPresented = true,
            peerID = privateChatSheetPeer!!,
            viewModel = viewModel,
            onDismiss = {
                viewModel.hidePrivateChatSheet()
                viewModel.endPrivateChat()
            }
        )
    }
}


/**
 * A one-line note above the composer saying where this message is about to go.
 *
 * Deliberately placed at the composer rather than the header: the moment that matters is just
 * before someone types, and the failure it prevents ("I sent messages offline and nothing
 * happened") is only visible at send time.
 *
 * Stays quiet when there is nothing surprising to say — a nearby-mesh channel with a healthy mesh
 * needs no commentary.
 */
@Composable
private fun TransportNotice(
    selectedPrivatePeer: String?,
    currentChannel: String?
) {
    val colorScheme = MaterialTheme.colorScheme
    val selectedLocationChannel by com.campusmesh.android.geohash.LocationChannelManager
        .getInstance(LocalContext.current).selectedChannel.collectAsStateWithLifecycle()
    val meshStatus by com.campusmesh.android.services.AppStateStore.meshStatus.collectAsStateWithLifecycle()

    val transport = ConversationTransport.forSelection(
        selectedPrivatePeer = selectedPrivatePeer,
        currentChannel = currentChannel,
        selectedLocationChannel = selectedLocationChannel
    )

    // Worth saying only when this conversation cannot reach anyone right now.
    val meshUnavailable = transport == ConversationTransport.NEARBY_MESH && meshStatus.isActionable
    val show = transport == ConversationTransport.INTERNET || meshUnavailable
    if (!show) return

    val text = if (meshUnavailable) meshStatus.describe() else transport.explain()
    val tint = if (meshUnavailable) colorScheme.error else colorScheme.onSurface.copy(alpha = 0.7f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (transport == ConversationTransport.INTERNET) {
                Icons.Filled.Language
            } else {
                Icons.Filled.Bluetooth
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = tint
        )
    }
}
