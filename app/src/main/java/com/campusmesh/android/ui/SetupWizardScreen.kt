package com.campusmesh.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campusmesh.android.data.AppMode

/**
 * First-run Setup Wizard Screen.
 *
 * Guides new users through three setup steps:
 * 1. Welcome & Brand intro
 * 2. Select campus mode (Main Campus Tesano / Abeka Campus / Off-Campus)
 * 3. Choose nickname and role (Student / Lecturer)
 *
 * @param onComplete  Called when setup is complete with [nickname], [role], and [mode].
 */
@Composable
fun SetupWizardScreen(
    onComplete: (nickname: String, role: String, mode: AppMode) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("Student") }
    var selectedMode by remember { mutableStateOf(AppMode.MAIN_CAMPUS) }

    val gradient = Brush.verticalGradient(listOf(Color(0xFF0D0D1E), Color(0xFF1A1A3E), Color(0xFF0D0D1E)))

    Box(
        modifier = Modifier.fillMaxSize().background(gradient),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(targetState = step, label = "wizard_step") { currentStep ->
            when (currentStep) {
                // Step 0: Welcome
                0 -> WizardWelcome(onNext = { step = 1 })
                // Step 1: Mode selection
                1 -> WizardModeSelect(
                    selected = selectedMode,
                    onSelect = { selectedMode = it },
                    onNext = { step = 2 },
                    onBack = { step = 0 }
                )
                // Step 2: Profile setup
                2 -> WizardProfile(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    selectedRole = selectedRole,
                    onRoleChange = { selectedRole = it },
                    onDone = {
                        if (nickname.isNotBlank()) {
                            onComplete(nickname.trim(), selectedRole, selectedMode)
                        }
                    },
                    onBack = { step = 1 }
                )
            }
        }
    }
}

@Composable
private fun WizardWelcome(onNext: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("📡", fontSize = 64.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "CAMPUS MESH",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 30.sp,
            letterSpacing = 4.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Ghana Communication Technology University",
            color = Color(0xFF9E9EC8),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Connect, collaborate, and stay safe with your campus community — entirely offline, no internet required.",
            color = Color(0xFFBBBBDD),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeaturePill("🔒 Private")
            FeaturePill("📡 Offline")
            FeaturePill("⚡ Free")
        }
        Spacer(modifier = Modifier.height(40.dp))
        WizardButton(text = "Get Started →", onClick = onNext)
    }
}

@Composable
private fun WizardModeSelect(
    selected: AppMode,
    onSelect: (AppMode) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Select Your Campus", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "This helps optimize the mesh for your location",
            color = Color(0xFF9E9EC8),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        AppMode.entries.forEach { mode ->
            ModeCard(
                mode = mode,
                isSelected = selected == mode,
                onClick = { onSelect(mode) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
        WizardButton("Continue →", onNext)
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("← Back", color = Color(0xFF9E9EC8))
        }
    }
}

@Composable
private fun WizardProfile(
    nickname: String,
    onNicknameChange: (String) -> Unit,
    selectedRole: String,
    onRoleChange: (String) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your Profile", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text("How should others on the mesh know you?", color = Color(0xFF9E9EC8), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = nickname,
            onValueChange = onNicknameChange,
            label = { Text("Choose Nickname", color = Color(0xFF9E9EC8)) },
            placeholder = { Text("e.g. Kofi, Ama, Prof. Asante", color = Color(0xFF55557A)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF7C83FD),
                unfocusedBorderColor = Color(0xFF55557A),
                cursorColor = Color(0xFF7C83FD)
            )
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Role selector
        Text("Role", color = Color(0xFF9E9EC8), fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Student" to "🎓", "Lecturer" to "📚").forEach { (role, emoji) ->
                FilterChip(
                    selected = selectedRole == role,
                    onClick = { onRoleChange(role) },
                    label = { Text("$emoji $role", fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF7C83FD),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF16213E),
                        labelColor = Color(0xFF9E9EC8)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        WizardButton(
            text = "Join Campus Mesh 🚀",
            onClick = onDone,
            enabled = nickname.isNotBlank()
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("← Back", color = Color(0xFF9E9EC8))
        }
    }
}

@Composable
private fun ModeCard(mode: AppMode, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) Color(0xFF7C83FD) else Color(0xFF2A2A4A)
    val bgColor     = if (isSelected) Color(0xFF1E1E4A) else Color(0xFF16213E)
    val icon = when (mode) {
        AppMode.MAIN_CAMPUS  -> "🏫"
        AppMode.ABEKA_CAMPUS -> "🏢"
        AppMode.GENERAL_MESH -> "🌍"
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(mode.displayName, color = Color.White, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 15.sp)
            if (isSelected) {
                Spacer(modifier = Modifier.weight(1f))
                Text("✓", color = Color(0xFF7C83FD), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WizardButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF7C83FD),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF3A3A5A)
        )
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun FeaturePill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF2A2A4A))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color(0xFF9E9EC8), fontSize = 12.sp)
    }
}
