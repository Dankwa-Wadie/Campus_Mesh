package com.campusmesh.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.android.core.ui.component.sheet.BitchatBottomSheet
import com.campusmesh.android.core.ui.component.sheet.BitchatSheetTopBar
import com.campusmesh.android.core.ui.component.sheet.BitchatSheetTitle

/**
 * Standalone "start a new direct message" entry point, reachable straight from the chat list
 * (home screen) rather than requiring the user to first open some channel/the Mesh timeline and
 * dig into its sidebar. Before this, [PrivateChatManager]/[ChatViewModel.startPrivateChat] were
 * only reachable via [MeshPeerListSheet]'s People section, itself only reachable via the sidebar
 * toggle inside [ChatScreen] - i.e. you could only DM someone you'd already seen post in a shared
 * channel/the mesh timeline. This reuses the exact same peer list ([PeopleSection], already public
 * and already handling connected peers + offline favorites + Nostr-only conversations, sorting,
 * unread/favorite/verified badges) so nothing about peer discovery or display needed duplicating -
 * only the entry point was missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewDirectMessageSheet(
    isPresented: Boolean,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val wifiAwareConnected by com.campusmesh.android.wifiaware.WifiAwareController.connectedPeers.collectAsStateWithLifecycle()
    val wifiAwarePeerIDs = remember(wifiAwareConnected) { wifiAwareConnected.keys.toSet() }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Search query, entered by name search - usernames aren't unique in this app (no central
    // server to enforce it), so this is a filter over the same People list rather than a lookup
    // that resolves to one guaranteed person; matching entries each show their own #hash suffix
    // (see PeopleSection/splitSuffix) so the user can tell duplicates apart before picking one.
    var query by remember { mutableStateOf("") }

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 64.dp, bottom = 20.dp)
                ) {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            placeholder = { Text("Search by username") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                        )
                    }
                    item(key = "people") {
                        PeopleSection(
                            connectedPeers = connectedPeers,
                            peerNicknames = peerNicknames,
                            peerRSSI = peerRSSI,
                            nickname = nickname,
                            colorScheme = colorScheme,
                            selectedPrivatePeer = selectedPrivatePeer,
                            wifiAwarePeerIDs = wifiAwarePeerIDs,
                            viewModel = viewModel,
                            filterQuery = query,
                            onPrivateChatStart = { peerID ->
                                // Full-screen conversation push, matching how ChatListScreen opens
                                // any other row (see openEntry's EntryKind.PRIVATE case) - not the
                                // overlay sheet MeshPeerListSheet uses, so a DM started here looks
                                // and behaves exactly like tapping an existing DM row afterwards.
                                viewModel.startPrivateChat(peerID)
                                viewModel.openConversation()
                                onDismiss()
                            }
                        )
                    }
                }

                BitchatSheetTopBar(
                    title = { BitchatSheetTitle(text = "New message") },
                    modifier = modifier.align(Alignment.TopCenter),
                    onClose = onDismiss,
                )
            }
        }
    }
}
