package com.campusmesh.android.onboarding

import android.content.Context
import com.campusmesh.android.services.AppStateStore

/**
 * Preference manager for the campus map "Ghost Mode" toggle.
 *
 * When enabled, this device's position is never broadcast into the map layer and never
 * rendered as a marker on anyone's map — same behavior as Snapchat's Ghost Mode. This is a
 * broadcast-suppression switch on the sender side, not a viewer-side filter: when ghosted,
 * nothing about this device's location goes out, so there's nothing for a peer to withhold.
 *
 * Ghost Mode only affects the map layer. It doesn't hide the user from chat/channels and is
 * independent of campus-mode geofencing (a ghosted user can still be in MAIN_CAMPUS mode and
 * see the map, just not appear on it).
 */
object GhostModePreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_GHOST_MODE = "map_ghost_mode_enabled"

    /** Call once at process start (e.g. Application.onCreate) to hydrate [AppStateStore]. */
    fun hydrate(context: Context) {
        AppStateStore.setGhostMode(isEnabled(context))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_GHOST_MODE, enabled).apply()
        AppStateStore.setGhostMode(enabled)
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_GHOST_MODE, false) // default OFF (visible), per plan
    }
}
