package com.campusmesh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.android.R
import com.campusmesh.android.core.ui.component.avatar.AvatarPalette
import com.campusmesh.android.core.ui.component.avatar.InitialsAvatar
import com.campusmesh.android.core.ui.component.avatar.avatarColorForIndex
import com.campusmesh.android.net.ArtiTorManager
import com.campusmesh.android.net.TorMode
import com.campusmesh.android.net.TorPreferenceManager
import com.campusmesh.android.nostr.NostrProofOfWork
import com.campusmesh.android.nostr.PoWPreferenceManager
import com.campusmesh.android.ui.theme.AvatarPreferenceManager
import com.campusmesh.android.ui.theme.ThemePreference
import com.campusmesh.android.ui.theme.ThemePreferenceManager

/**
 * Settings & Profile - new consolidated screen (docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md Phase 5).
 * Consolidates nickname editing, avatar picker, theme, and everything that used to live in the
 * separate AboutSheet.kt bottom sheet (app info/features, mesh/network toggles, emergency data
 * deletion) - moved here for discoverability, since AboutSheet was only reachable by tapping the
 * app title, and emergency deletion was a hidden triple-tap gesture with no confirmation at all.
 * The role pill in the profile card is read-only display only - the separate Student/Lecturer
 * editable toggle row was removed per user request. Deliberately does NOT include Ghost Mode -
 * that lives on the Map screen per the earlier design decision.
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

            item { SectionLabel("About App") }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        } catch (e: Exception) {
                            "1.0.0"
                        }
                    }
                    Text(
                        text = stringResource(id = R.string.app_name),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "v${versionName ?: ""}",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = stringResource(id = R.string.about_tagline),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        FeatureRow(
                            icon = Icons.Filled.Bluetooth,
                            title = stringResource(id = R.string.about_offline_mesh_title),
                            subtitle = stringResource(id = R.string.about_offline_mesh_desc)
                        )
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                        FeatureRow(
                            icon = Icons.Filled.Public,
                            title = stringResource(id = R.string.about_online_geohash_title),
                            subtitle = stringResource(id = R.string.about_online_geohash_desc)
                        )
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                        FeatureRow(
                            icon = Icons.Filled.Lock,
                            title = stringResource(id = R.string.about_e2e_title),
                            subtitle = stringResource(id = R.string.about_e2e_desc)
                        )
                    }
                }
            }

            item { SectionLabel("Appearance") }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DarkMode,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Theme",
                            modifier = Modifier.padding(start = 10.dp),
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    // Three-way system/light/dark picker, replacing the old binary "Dark theme"
                    // switch which had no way to express "follow system" at all.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ThemeChip(
                                label = stringResource(id = R.string.about_system),
                                selected = themePreference.isSystem,
                                onClick = { ThemePreferenceManager.set(context, ThemePreference.System) },
                                modifier = Modifier.weight(1f)
                            )
                            ThemeChip(
                                label = stringResource(id = R.string.about_light),
                                selected = themePreference.isLight,
                                onClick = { ThemePreferenceManager.set(context, ThemePreference.Light) },
                                modifier = Modifier.weight(1f)
                            )
                            ThemeChip(
                                label = stringResource(id = R.string.about_dark),
                                selected = themePreference.isDark,
                                onClick = { ThemePreferenceManager.set(context, ThemePreference.Dark) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Mesh & Network") }

            item {
                LaunchedEffect(Unit) { PoWPreferenceManager.init(context) }
                val powEnabled by PoWPreferenceManager.powEnabled.collectAsState()
                val powDifficulty by PoWPreferenceManager.powDifficulty.collectAsState()
                var backgroundEnabled by remember { mutableStateOf(com.campusmesh.android.service.MeshServicePreferences.isBackgroundEnabled(true)) }
                val torMode = remember { mutableStateOf(TorPreferenceManager.get(context)) }
                val torProvider = remember { ArtiTorManager.getInstance() }
                val torStatus by torProvider.statusFlow.collectAsState()
                val torAvailable = remember { torProvider.isTorAvailable() }
                val isDark = MaterialTheme.colorScheme.background.red + MaterialTheme.colorScheme.background.green + MaterialTheme.colorScheme.background.blue < 1.5f

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column {
                            SettingsToggleRow(
                                icon = Icons.Filled.Bluetooth,
                                title = stringResource(id = R.string.about_background_title),
                                subtitle = stringResource(id = R.string.about_background_desc),
                                checked = backgroundEnabled,
                                onCheckedChange = { enabled ->
                                    backgroundEnabled = enabled
                                    com.campusmesh.android.service.MeshServicePreferences.setBackgroundEnabled(enabled)
                                    if (!enabled) {
                                        com.campusmesh.android.service.MeshForegroundService.stop(context)
                                    } else {
                                        com.campusmesh.android.service.MeshForegroundService.start(context)
                                    }
                                }
                            )
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            )
                            SettingsToggleRow(
                                icon = Icons.Filled.Speed,
                                title = stringResource(id = R.string.about_pow),
                                subtitle = stringResource(id = R.string.about_pow_tip),
                                checked = powEnabled,
                                onCheckedChange = { PoWPreferenceManager.setPowEnabled(it) }
                            )
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                            )
                            SettingsToggleRow(
                                icon = Icons.Filled.Security,
                                title = "Tor Network",
                                subtitle = stringResource(id = R.string.about_tor_route),
                                checked = torMode.value == TorMode.ON,
                                onCheckedChange = { enabled ->
                                    if (torAvailable) {
                                        torMode.value = if (enabled) TorMode.ON else TorMode.OFF
                                        TorPreferenceManager.set(context, torMode.value)
                                    }
                                },
                                enabled = torAvailable,
                                statusIndicator = if (torMode.value == TorMode.ON) {
                                    {
                                        val statusColor = when {
                                            torStatus.running && torStatus.bootstrapPercent >= 100 -> if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                            torStatus.running -> Color(0xFFFF9500)
                                            else -> Color(0xFFFF3B30)
                                        }
                                        Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
                                    }
                                } else null
                            )
                        }
                    }
                    if (!torAvailable) {
                        Text(
                            text = stringResource(id = R.string.tor_not_available_in_this_build),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                        )
                    }
                    if (powEnabled) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Difficulty",
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$powDifficulty bits • ${NostrProofOfWork.estimateMiningTime(powDifficulty)}",
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Slider(
                                    value = powDifficulty.toFloat(),
                                    onValueChange = { PoWPreferenceManager.setPowDifficulty(it.toInt()) },
                                    valueRange = 0f..32f,
                                    steps = 31,
                                    colors = SliderDefaults.colors(
                                        thumbColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D),
                                        activeTrackColor = if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                    )
                                )
                            }
                        }
                    }
                    if (torMode.value == TorMode.ON && torStatus.running) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val statusColor = if (torStatus.bootstrapPercent >= 100) {
                                    if (isDark) Color(0xFF32D74B) else Color(0xFF248A3D)
                                } else Color(0xFFFF9500)
                                Surface(color = statusColor, shape = CircleShape, modifier = Modifier.size(10.dp)) {}
                                Text(
                                    text = "Connected (${torStatus.bootstrapPercent}%)",
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
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

            item { SectionLabel("App updates") }

            item {
                val updateStatus by com.campusmesh.android.net.GithubUpdateChecker.statusFlow.collectAsStateWithLifecycle()
                val installedVersion = remember { com.campusmesh.android.net.GithubUpdateChecker.getInstalledVersionName(context) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp)
                        ) {
                            Text(
                                text = "Installed Version",
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "v$installedVersion (Campus Mesh)",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        androidx.compose.material3.TextButton(
                            onClick = {
                                com.campusmesh.android.net.GithubUpdateChecker.checkForUpdates(context, force = true)
                            }
                        ) {
                            Text(
                                text = if (updateStatus is com.campusmesh.android.net.UpdateStatus.Checking) "Checking..." else "Check now",
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    when (val status = updateStatus) {
                        is com.campusmesh.android.net.UpdateStatus.UpdateAvailable -> {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "🚀 New Version Available: v${status.latestVersion}",
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = "Running: v${status.currentVersion} → GitHub Latest: v${status.latestVersion}" +
                                                if (status.apkSizeBytes > 0) " (${status.apkSizeBytes / (1024 * 1024)} MB)" else "",
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    Text(
                                        text = status.releaseNotes.take(200),
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                                    )
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            com.campusmesh.android.net.GithubUpdateChecker.downloadAndInstall(
                                                context = context,
                                                downloadUrl = status.downloadUrl,
                                                apkFileName = status.apkFileName,
                                                expectedSha256 = status.sha256
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "Download & Install Update",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                        is com.campusmesh.android.net.UpdateStatus.Downloading -> {
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                Text(
                                    text = "Downloading update... ${status.progressPercent}%",
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { status.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                )
                            }
                        }
                        is com.campusmesh.android.net.UpdateStatus.ReadyToInstall -> {
                            androidx.compose.material3.Button(
                                onClick = {
                                    // Re-verify the signing certificate: this file has sat on disk
                                    // since the download completed.
                                    val intent = com.campusmesh.android.net.OtaUpdateManager(context)
                                        .verifyAndPrepareInstall(status.apkFile, expectedHash = null)
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    } else {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Update rejected: APK failed signature verification.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Tap to Launch Installer",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                        is com.campusmesh.android.net.UpdateStatus.UpToDate -> {
                            Text(
                                text = "✅ Installed release (v${status.currentVersion}) is up to date with GitHub",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        is com.campusmesh.android.net.UpdateStatus.Error -> {
                            Text(
                                text = "Unable to check updates: ${status.message}",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        else -> {}
                    }
                }
            }

            item { SectionLabel("Sensitive Settings") }

            item {
                var showDeleteDialog by remember { mutableStateOf(false) }
                var showDebugSheet by remember { mutableStateOf(false) }

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(id = R.string.about_emergency_title),
                                        fontFamily = FontFamily.SansSerif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "Permanently deletes all messages, keys, and settings from this device. " +
                                                "This cannot be undone - there is no backup and no recovery once it's done.",
                                        fontFamily = FontFamily.SansSerif,
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                    )
                                }
                            }
                            androidx.compose.material3.Button(
                                onClick = { showDeleteDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteForever,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onError
                                )
                                Text(
                                    text = "Delete Everything",
                                    modifier = Modifier.padding(start = 6.dp),
                                    fontFamily = FontFamily.SansSerif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDebugSheet = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(id = R.string.about_debug_settings),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 10.dp),
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (showDeleteDialog) {
                    EmergencyDeleteConfirmDialog(
                        onConfirm = {
                            viewModel.panicClearAllData()
                            showDeleteDialog = false
                        },
                        onDismiss = { showDeleteDialog = false }
                    )
                }

                if (showDebugSheet) {
                    com.campusmesh.android.ui.debug.DebugSettingsSheet(
                        isPresented = showDebugSheet,
                        onDismiss = { showDebugSheet = false },
                        meshService = viewModel.meshService
                    )
                }
            }
        }
    }
}

/**
 * GitHub-repo-delete-style confirmation: explains the deletion is permanent, and requires the
 * user to type an exact confirmation phrase before the destructive action is even enabled -
 * replaces the old triple-tap-the-app-title gesture, which fired instantly with no confirmation
 * at all.
 */
@Composable
private fun EmergencyDeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmText by remember { mutableStateOf("") }
    val requiredPhrase = "DELETE"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = "Delete all data?",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = "This permanently deletes all messages, encryption keys, and settings on this device. " +
                            "This action cannot be undone - once deleted, this data cannot be recovered.",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Type \"$requiredPhrase\" to confirm.",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    singleLine = true,
                    placeholder = { Text(requiredPhrase) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmText == requiredPhrase
            ) {
                Text(
                    text = "Delete Everything",
                    color = if (confirmText == requiredPhrase) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
