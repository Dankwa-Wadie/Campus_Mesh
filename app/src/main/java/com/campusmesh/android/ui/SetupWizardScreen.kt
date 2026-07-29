package com.campusmesh.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusmesh.android.R
import com.campusmesh.android.data.AppMode

/**
 * First-run Setup Wizard Screen — shown once, immediately after the permission/Bluetooth/
 * Location/Battery "granting access" screens in [com.campusmesh.android.MainActivity]'s
 * onboarding flow, right before landing on [ChatScreen].
 *
 * Deliberately styled with [MaterialTheme.colorScheme] and monospace type instead of custom
 * colors, using the same header/card/button patterns as the screens before it (see
 * `onboarding/BatteryOptimizationScreen.kt`, `onboarding/PermissionExplanationScreen.kt`,
 * `onboarding/BackgroundLocationPermissionScreen.kt`) so the transition into this screen doesn't
 * look like a different app — no separate background, no separate palette.
 *
 * Guides new users through two setup steps:
 * 1. Welcome & Brand intro
 * 2. Choose nickname and role (Student / Lecturer)
 *
 * There is deliberately no campus-selection step here: campus membership is handled by the
 * pinned Main Campus geohash channel in LocationChannelsSheet (join it directly, no picker) plus
 * [com.campusmesh.android.geohash.LocationChannelManager]'s automatic geofence detection, not a
 * one-time choice made during first-run setup.
 *
 * @param onComplete  Called when setup is complete with [nickname] and [role]. [AppMode] always
 *   starts at [AppMode.GENERAL_MESH] here -- the geofence check corrects it automatically once a
 *   location fix comes in, if the device is actually within a campus radius.
 */
@Composable
fun SetupWizardScreen(
    modifier: Modifier = Modifier,
    onComplete: (nickname: String, role: String, mode: AppMode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var step by remember { mutableIntStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("Student") }

    Box(modifier = modifier) {
        AnimatedContent(targetState = step, label = "wizard_step") { currentStep ->
            when (currentStep) {
                // Step 0: Welcome
                0 -> WizardWelcome(colorScheme = colorScheme, onNext = { step = 1 })
                // Step 1: Profile setup
                1 -> WizardProfile(
                    colorScheme = colorScheme,
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    selectedRole = selectedRole,
                    onRoleChange = { selectedRole = it },
                    onDone = {
                        if (nickname.isNotBlank()) {
                            onComplete(nickname.trim(), selectedRole, AppMode.GENERAL_MESH)
                        }
                    },
                    onBack = { step = 0 }
                )
            }
        }
    }
}

/**
 * Same header shape used by every other onboarding screen: app name in monospace headline,
 * subtitle underneath at reduced alpha. Keeping this identical is most of what makes the
 * transition from the previous "granting access" screens feel seamless.
 */
@Composable
private fun WizardHeader(colorScheme: ColorScheme, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp
            ),
            color = colorScheme.onBackground
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            fontFamily = FontFamily.SansSerif,
            color = colorScheme.onBackground.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun WizardWelcome(colorScheme: ColorScheme, onNext: () -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        WizardHeader(colorScheme, "Ghana Communication Technology University")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colorScheme.surfaceVariant.copy(alpha = 0.25f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Connect, collaborate, and stay safe with your campus community — entirely offline, no internet required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onBackground.copy(alpha = 0.85f),
                    fontFamily = FontFamily.SansSerif
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeaturePill(colorScheme, "🔒 Private")
                    FeaturePill(colorScheme, "📡 Offline")
                    FeaturePill(colorScheme, "⚡ Free")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary)
        ) {
            Text("Get Started →", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold))
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun WizardProfile(
    colorScheme: ColorScheme,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    selectedRole: String,
    onRoleChange: (String) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        WizardHeader(colorScheme, "How should others on the mesh know you?")

        OutlinedTextField(
            value = nickname,
            onValueChange = onNicknameChange,
            label = { Text("Nickname", fontFamily = FontFamily.SansSerif) },
            placeholder = { Text("e.g. Kofi, Ama, Prof. Asante", fontFamily = FontFamily.SansSerif, color = colorScheme.onBackground.copy(alpha = 0.4f)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colorScheme.onBackground,
                unfocusedTextColor = colorScheme.onBackground,
                focusedBorderColor = colorScheme.primary,
                unfocusedBorderColor = colorScheme.onBackground.copy(alpha = 0.3f),
                cursorColor = colorScheme.primary,
                focusedLabelColor = colorScheme.primary,
                unfocusedLabelColor = colorScheme.onBackground.copy(alpha = 0.6f)
            )
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Role", fontSize = 12.sp, fontFamily = FontFamily.SansSerif, color = colorScheme.onBackground.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Student" to "🎓", "Lecturer" to "📚").forEach { (role, emoji) ->
                    FilterChip(
                        selected = selectedRole == role,
                        onClick = { onRoleChange(role) },
                        label = { Text("$emoji $role", fontSize = 13.sp, fontFamily = FontFamily.SansSerif) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorScheme.primary,
                            selectedLabelColor = colorScheme.onPrimary,
                            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            labelColor = colorScheme.onBackground.copy(alpha = 0.8f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onDone,
            enabled = nickname.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary,
                contentColor = colorScheme.onPrimary,
                disabledContainerColor = colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
        ) {
            Text("Join Campus Mesh 🚀", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold))
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("← Back", color = colorScheme.onBackground.copy(alpha = 0.7f), fontFamily = FontFamily.SansSerif)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FeaturePill(colorScheme: ColorScheme, label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Text(
            text = label,
            color = colorScheme.onBackground.copy(alpha = 0.8f),
            fontSize = 12.sp,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            textAlign = TextAlign.Center
        )
    }
}
