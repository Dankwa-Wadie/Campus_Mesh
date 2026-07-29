package com.campusmesh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.android.core.ui.component.avatar.AvatarPalette
import com.campusmesh.android.core.ui.component.avatar.InitialsAvatar
import com.campusmesh.android.core.ui.component.avatar.avatarColorForIndex
import com.campusmesh.android.ui.theme.AvatarPreferenceManager
import com.campusmesh.android.ui.theme.ThemePreference
import com.campusmesh.android.ui.theme.ThemePreferenceManager

/**
 * Settings & Profile - new consolidated screen (docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md Phase 5).
 * Consolidates nickname editing, avatar picker, role toggle, and dark-theme toggle, which were
 * previously scattered (AboutSheet.kt, wherever ThemePreferenceManager was surfaced). Deliberately
 * does NOT include Ghost Mode - that lives on the Map screen per the earlier design decision.
 *
 * Reached from a gear icon in the chat-list header; back returns to the chat list
 * (ChatViewModel.openSettings/closeSettings).
 */
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val role by viewModel.role.collectAsStateWithLifecycle()
    val themePreference by ThemePreferenceManager.themeFlow.collectAsStateWithLifecycle()
    val avatarColorIndex by AvatarPreferenceManager.colorIndexFlow.collectAsStateWithLifecycle()

    var nicknameField by remember(nickname) { mutableStateOf(nickname) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                // Edge-to-edge theme means this needs explicit status-bar padding, or it renders
                // under the status bar/notch (same fix applied to ChatListScreen.kt's header).
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.closeSettings() }) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
            Text(
                text = "Settings & profile",
                color = MaterialTheme.colorScheme.onPrimary,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    InitialsAvatar(
                        seed = nickname,
                        size = 68.dp,
                        overrideColor = avatarColorForIndex(avatarColorIndex),
                        goldRing = true
                    )
                    Text(
                        text = nickname,
                        modifier = Modifier.padding(top = 8.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Connected to Main Campus mesh",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(999.dp))
                            .padding(horizontal = 11.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🎓 $role · GCTU",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            item { SectionLabel("Profile") }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextField(
                        value = nicknameField,
                        onValueChange = { nicknameField = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (nicknameField.isNotBlank() && nicknameField != nickname) {
                                viewModel.setNickname(nicknameField.trim())
                            }
                        }),
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Palette,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Avatar",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AvatarPalette.forEachIndexed { index, color ->
                            val selected = avatarColorIndex == index
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .background(color, CircleShape)
                                    .clickable {
                                        AvatarPreferenceManager.set(
                                            context,
                                            if (selected) null else index
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.School,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Role",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    listOf("Student", "Lecturer").forEach { option ->
                        val selected = role == option
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable { viewModel.setRole(option) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = option,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Appearance") }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.DarkMode,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Dark theme",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Switch(
                        checked = themePreference == ThemePreference.Dark,
                        onCheckedChange = { checked ->
                            ThemePreferenceManager.set(
                                context,
                                if (checked) ThemePreference.Dark else ThemePreference.Light
                            )
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            item { SectionLabel("Privacy") }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "👻",
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ghost Mode",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Set on Map ›",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 0.6.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
