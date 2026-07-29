package com.campusmesh.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local-only avatar color override (redesign Phase 5 - see docs/UI_REDESIGN_IMPLEMENTATION_PLAN.md
 * §3). Lets the user pick their own avatar color from the fixed palette instead of the default
 * deterministic hash-based color, using the same SharedPreferences-backed StateFlow pattern as
 * ThemePreference.kt.
 *
 * Scope note - deliberately local-only for now: this changes what YOUR device shows for YOUR
 * avatar everywhere it renders locally (chat list, this Settings screen), but does not yet
 * broadcast the choice to peers. The plan's IdentityAnnouncement TLV extension needed for peers to
 * see this same color (rather than falling back to their own hash of your nickname/peerID) is
 * real protocol work, not something to bolt on safely alongside the rest of this redesign pass
 * without a compiler to verify it - tracked as a follow-up, not attempted here.
 */
object AvatarPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_AVATAR_COLOR_INDEX = "avatar_color_index"
    private const val NONE = -1

    private val _colorIndexFlow = MutableStateFlow<Int?>(null)
    val colorIndexFlow: StateFlow<Int?> = _colorIndexFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_AVATAR_COLOR_INDEX, NONE)
        _colorIndexFlow.value = if (saved == NONE) null else saved
    }

    /** Pass null to clear the override and go back to the deterministic hash-based color. */
    fun set(context: Context, colorIndex: Int?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_AVATAR_COLOR_INDEX, colorIndex ?: NONE).apply()
        _colorIndexFlow.value = colorIndex
    }
}
