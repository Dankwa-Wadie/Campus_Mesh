package com.campusmesh.android.ui

import com.campusmesh.android.geohash.ChannelID

/**
 * Which transport the current conversation actually sends over.
 *
 * This is not cosmetic. `ChatViewModel.sendMessage` routes a geohash/location channel through
 * `sendGeohashMessage` -> Nostr relays, which needs the internet and has no Bluetooth path at
 * all; classic channels and the plain mesh view broadcast over BLE and work with no connection.
 *
 * Nothing previously distinguished the two in the UI. In an app called Campus Mesh, whose
 * flagship entry — "Main Campus (Tesano)" — is a geohash channel, that meant a user could sit in
 * what looks like the campus channel, lose internet, and watch every message silently queue until
 * connectivity returned. The messages were never lost; they simply never went anywhere near the
 * mesh.
 */
enum class ConversationTransport {
    /** Nostr relays only. Needs internet, no Bluetooth fallback. */
    INTERNET,

    /** BLE mesh broadcast to nearby devices. Works with no connection. */
    NEARBY_MESH,

    /**
     * Both at once: broadcast over BLE *and* published to Nostr, deduplicated on arrival by a
     * shared message id. Campus/geohash channels use this, so they reach nearby devices offline
     * and everyone else when there is internet.
     */
    DUAL,

    /** Direct messages, routed by MessageRouter: mesh when a route exists, otherwise Nostr. */
    AUTOMATIC;

    val worksOffline: Boolean get() = this != INTERNET

    fun label(): String = when (this) {
        INTERNET -> "Internet"
        NEARBY_MESH -> "Nearby"
        DUAL -> "Nearby + Internet"
        AUTOMATIC -> "Auto"
    }

    fun explain(): String = when (this) {
        INTERNET -> "This channel sends over the internet. Messages wait until you're connected."
        NEARBY_MESH -> "This channel sends over Bluetooth to nearby devices. Works without internet."
        DUAL -> "Sends to nearby devices over Bluetooth and to everyone else over the internet."
        AUTOMATIC -> "Sends over Bluetooth when the person is nearby, otherwise over the internet."
    }

    companion object {
        /**
         * Mirrors the routing decision in `ChatViewModel.sendMessage`, which is deliberately
         * ordered: a joined classic channel wins over an active geohash selection. If that
         * ordering changes, this must change with it or the badge will lie.
         */
        fun forSelection(
            selectedPrivatePeer: String?,
            currentChannel: String?,
            selectedLocationChannel: ChannelID?
        ): ConversationTransport = when {
            selectedPrivatePeer != null -> AUTOMATIC
            currentChannel != null -> NEARBY_MESH
            // Geohash/campus channels now go out over both transports, so they keep working
            // offline. They were Nostr-only, which is why messages sent without internet silently
            // queued until connectivity returned.
            selectedLocationChannel is ChannelID.Location -> DUAL
            else -> NEARBY_MESH
        }
    }
}
