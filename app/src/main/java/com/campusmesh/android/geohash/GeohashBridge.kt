package com.campusmesh.android.geohash

import java.util.UUID

/**
 * Lets a geohash channel carry traffic over BLE mesh as well as Nostr, so campus channels work
 * with or without internet.
 *
 * Geohash channels were Nostr-only: `ChatViewModel.sendMessage` routed them to relays and there
 * was no Bluetooth path at all, so offline every message queued until connectivity returned. Mesh
 * channels were BLE-only. This is the mapping that lets one logical channel use both.
 *
 * Two problems have to be solved for that to work:
 *
 *  1. **Naming.** A BLE broadcast carries a channel tag. Reusing the bare geohash (`#ebzzffv`,
 *     matching the Nostr convention) would collide with ordinary channels: geohash base32 has no
 *     a/i/l/o, but `#test` is all-valid-geohash characters and would be misfiled as a location
 *     channel. Hence the explicit [PREFIX].
 *
 *  2. **Identity.** The same message arriving over both transports must appear once. Nostr assigns
 *     its own `event.id` (a hash of the event) and BLE assigns its own, so they never match. The
 *     sender therefore mints one [newSharedMessageId] and carries it on both paths — as the BLE
 *     message id, and as a [SHARED_ID_TAG] tag on the Nostr event — so the receiver can collapse
 *     them.
 */
object GeohashBridge {

    /** Marks a BLE channel tag as belonging to a geohash channel rather than a named one. */
    const val PREFIX = "#geo:"

    /** Nostr tag name carrying the cross-transport message id. */
    const val SHARED_ID_TAG = "cm"

    /** Geohash base32: no a, i, l or o. */
    private const val GEOHASH_ALPHABET = "0123456789bcdefghjkmnpqrstuvwxyz"

    private const val MAX_GEOHASH_LENGTH = 12

    /** BLE channel tag for [geohash], e.g. "ebzzffv" -> "#geo:ebzzffv". */
    fun channelTag(geohash: String): String = PREFIX + geohash.lowercase()

    /**
     * The geohash inside a BLE channel tag, or null if this is an ordinary channel.
     *
     * Rejects anything that is not a plausible geohash so a malformed or hostile tag cannot create
     * arbitrary location channels on a receiver.
     */
    fun geohashFromChannelTag(channel: String?): String? {
        if (channel == null || !channel.startsWith(PREFIX)) return null
        val candidate = channel.removePrefix(PREFIX).lowercase()
        if (candidate.isEmpty() || candidate.length > MAX_GEOHASH_LENGTH) return null
        if (!candidate.all { it in GEOHASH_ALPHABET }) return null
        return candidate
    }

    /** True when this BLE channel tag addresses a geohash channel. */
    fun isGeohashChannel(channel: String?): Boolean = geohashFromChannelTag(channel) != null

    /**
     * Key under which a geohash channel's messages are stored, matching the existing convention
     * used by the Nostr path (`messageManager.addChannelMessage("geo:<geohash>", ...)`).
     */
    fun storageKey(geohash: String): String = "geo:" + geohash.lowercase()

    /** A fresh cross-transport message id, to be sent identically over both transports. */
    fun newSharedMessageId(): String = UUID.randomUUID().toString()

    /**
     * Content prefix for a BLE broadcast belonging to a geohash channel.
     *
     * Extends the existing `[#channel]content` convention that `MessageHandler` already parses,
     * adding an optional `|<id>` segment. The id has to travel in the payload because a BLE
     * message's id is derived from the packet bytes (`PacketIdUtil.computeIdHex`), not chosen by
     * the sender — so without it there is no way to match the BLE copy to the Nostr one.
     *
     * Backward compatible: a prefix with no `|` parses as before, with a null id.
     */
    fun encodeChannelPrefix(geohash: String, sharedMessageId: String?): String {
        val tag = channelTag(geohash)
        return if (sharedMessageId.isNullOrBlank()) "[$tag]" else "[$tag|$sharedMessageId]"
    }

    /** Channel tag and optional shared id parsed out of a `[#geo:xxx|id]` prefix body. */
    data class ChannelPrefix(val channelTag: String, val sharedMessageId: String?)

    /**
     * Splits the text between the brackets into its channel tag and optional shared id.
     *
     * [body] is what the existing parser already extracts, i.e. `#geo:ebzzffv|abc-123`.
     */
    fun parseChannelPrefixBody(body: String): ChannelPrefix {
        val separator = body.indexOf('|')
        if (separator < 0) return ChannelPrefix(body, null)
        val tag = body.substring(0, separator)
        val id = body.substring(separator + 1).trim()
        return ChannelPrefix(tag, id.takeIf { it.isNotEmpty() && it.length <= 64 })
    }

    /**
     * Picks the id to file an incoming Nostr event under: the sender's shared id when present,
     * otherwise the Nostr event id.
     *
     * Falling back keeps interoperability with senders that do not set the tag — other clients, or
     * older builds of this app — at the cost of not being able to deduplicate those against a BLE
     * copy. Better than dropping them.
     */
    fun incomingMessageId(tags: List<List<String>>, fallbackEventId: String): String {
        val shared = tags.firstOrNull { it.size >= 2 && it[0] == SHARED_ID_TAG }?.get(1)
        return if (!shared.isNullOrBlank() && shared.length <= 64) shared else fallbackEventId
    }
}
