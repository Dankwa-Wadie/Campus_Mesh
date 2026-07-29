package com.campusmesh.android

import android.app.Application
import com.campusmesh.android.nostr.RelayDirectory
import com.campusmesh.android.ui.theme.ThemePreferenceManager
import com.campusmesh.android.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class CampusMeshApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.campusmesh.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.campusmesh.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.campusmesh.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize avatar color preference (redesign Phase 5 - Settings & Profile screen)
        try { com.campusmesh.android.ui.theme.AvatarPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize debug preference manager (persists debug toggles)
        try { com.campusmesh.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = com.campusmesh.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.campusmesh.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.campusmesh.android.nostr.GeohashAliasRegistry.initialize(this)
            com.campusmesh.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.campusmesh.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Hydrate Ghost Mode (map location broadcast suppression) from persisted preference
        try { com.campusmesh.android.onboarding.GhostModePreferenceManager.hydrate(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.campusmesh.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
