package com.campusmesh.android.onboarding

import android.content.Context

/**
 * Tracks whether the first-run [com.campusmesh.android.ui.SetupWizardScreen] (campus mode,
 * nickname, role) has been completed, and persists the chosen role since there's no dedicated
 * role/profile store yet ([com.campusmesh.android.data.SchoolProfile] exists as a data class but
 * isn't wired to persistence anywhere in the app).
 */
object CampusSetupPreferenceManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_SETUP_COMPLETE = "campus_setup_wizard_complete"
    private const val KEY_ROLE = "campus_setup_role"

    fun isSetupComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    }

    fun markSetupComplete(context: Context, role: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_SETUP_COMPLETE, true)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun getRole(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ROLE, "Student") ?: "Student"
    }
}
