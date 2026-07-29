package com.campusmesh.android.geohash

/**
 * GCTU Main Campus (Tesano), defined as recognized geohash cells rather than a lat/lon + radius
 * "AppMode" — this plugs Main Campus directly into the app's existing geohash location-channel
 * system (already has a full UI, bookmarking, participant counts, etc.) instead of a separate,
 * parallel mode concept.
 *
 * The campus spans three adjacent block-level (7-char) geohash cells. [PRIMARY] is the one
 * actually used for message routing/selection: geohash channels are keyed by their exact string,
 * so switching between all three would fragment chat into three isolated channels instead of one.
 * [ALL] is kept for future physical-campus matching (e.g. "is this device's current geohash one
 * of these three") without needing to touch routing.
 *
 * Scope note: only Main Campus is wired up for now. Abeka Campus can follow the same pattern
 * once its geohash cells are supplied.
 */
object MainCampusGeohash {
    const val DISPLAY_NAME = "Main Campus (Tesano)"
    const val PRIMARY = "ebzzffv"
    val ALL = listOf("ebzzffv", "ebzzfft", "ebzzffm")
    val CHANNEL = GeohashChannel(GeohashChannelLevel.BLOCK, PRIMARY)

    fun isOnMainCampus(geohash: String): Boolean = ALL.contains(geohash.lowercase())

    /**
     * "Sub-channels" of Main Campus, as discussed in the implementation plan. These are regular
     * IRC-style channels (joined via [com.campusmesh.android.ui.ChatViewModel.joinChannel]), not
     * geohash channels — the geohash/location-channel system has no concept of nested channels,
     * so grouping them under the Main Campus entry is done in the UI (LocationChannelsSheet),
     * not in the channel data model itself.
     */
    val SUB_CHANNELS: List<Pair<String, String>> = listOf(
        "#gctu-announcements" to "📢",
        "#computing-cis" to "💻",
        "#engineering" to "⚙️"
    )
}
