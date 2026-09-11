package com.campusmesh.android.geohash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geohash channels used to be Nostr-only, so campus chat silently required internet. This mapping
 * is what lets one logical channel run over BLE as well.
 */
class GeohashBridgeTest {

    @Test
    fun `a geohash channel tag round-trips`() {
        val tag = GeohashBridge.channelTag(MainCampusGeohash.PRIMARY)

        assertEquals("#geo:ebzzffv", tag)
        assertEquals(MainCampusGeohash.PRIMARY, GeohashBridge.geohashFromChannelTag(tag))
        assertTrue(GeohashBridge.isGeohashChannel(tag))
    }

    /**
     * The reason for an explicit prefix. Geohash base32 omits a/i/l/o, but "#test" is entirely
     * valid geohash characters — without the prefix it would be misfiled as a location channel.
     */
    @Test
    fun `ordinary channels are never mistaken for geohash channels`() {
        val ordinary = listOf(
            "#campus-mesh",
            "#gctu-announcements",
            "#general",
            "#test",              // all-valid geohash characters
            "#bcdef",             // ditto
            "geo:ebzzffv",        // storage key, not a channel tag
            "#",
            ""
        )
        ordinary.forEach { channel ->
            assertFalse("'$channel' must not read as a geohash channel", GeohashBridge.isGeohashChannel(channel))
            assertNull(GeohashBridge.geohashFromChannelTag(channel))
        }
        assertNull(GeohashBridge.geohashFromChannelTag(null))
    }

    /** A hostile tag must not be able to create arbitrary channels on a receiver. */
    @Test
    fun `malformed geohash tags are rejected`() {
        val bad = listOf(
            "#geo:",                    // empty
            "#geo:abc",                 // 'a' is not in the geohash alphabet
            "#geo:ebzz!ffv",            // punctuation
            "#geo:" + "b".repeat(13),   // too long
            "#geo:ebzz ffv"             // whitespace
        )
        bad.forEach { channel ->
            assertNull("'$channel' must be rejected", GeohashBridge.geohashFromChannelTag(channel))
        }
    }

    @Test
    fun `tags are case-insensitive and normalise to lowercase`() {
        assertEquals("ebzzffv", GeohashBridge.geohashFromChannelTag("#geo:EBZZFFV"))
        assertEquals("#geo:ebzzffv", GeohashBridge.channelTag("EBZZFFV"))
    }

    /** Must match the key the Nostr path already writes to, or the two transports split the view. */
    @Test
    fun `storage key matches the existing nostr convention`() {
        assertEquals("geo:ebzzffv", GeohashBridge.storageKey(MainCampusGeohash.PRIMARY))
        assertEquals("geo:ebzzffv", GeohashBridge.storageKey("EBZZFFV"))
    }

    @Test
    fun `shared message ids are unique`() {
        val ids = (1..100).map { GeohashBridge.newSharedMessageId() }
        assertEquals("Ids must not collide", ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    /**
     * The dedup mechanism: a message sent over both transports carries one id, so the receiver can
     * collapse the BLE and Nostr copies into one.
     */
    @Test
    fun `the shared id tag is preferred over the nostr event id`() {
        val shared = GeohashBridge.newSharedMessageId()
        val tags = listOf(listOf("g", "ebzzffv"), listOf("cm", shared), listOf("n", "ama"))

        assertEquals(shared, GeohashBridge.incomingMessageId(tags, "nostr-event-hash"))
    }

    /** Senders without the tag still work; they just cannot be deduped against a BLE copy. */
    @Test
    fun `an untagged event falls back to its own id`() {
        val tags = listOf(listOf("g", "ebzzffv"))
        assertEquals("nostr-event-hash", GeohashBridge.incomingMessageId(tags, "nostr-event-hash"))
    }

    @Test
    fun `a blank or oversized shared id is ignored`() {
        assertEquals(
            "fallback",
            GeohashBridge.incomingMessageId(listOf(listOf("cm", "   ")), "fallback")
        )
        assertEquals(
            "fallback",
            GeohashBridge.incomingMessageId(listOf(listOf("cm", "x".repeat(65))), "fallback")
        )
        assertEquals(
            "fallback",
            GeohashBridge.incomingMessageId(listOf(listOf("cm")), "fallback")
        )
    }

    /** The whole point: one message over two transports collapses to one entry. */
    @Test
    fun `the same message over both transports shares an id`() {
        val shared = GeohashBridge.newSharedMessageId()

        val bleId = shared
        val nostrId = GeohashBridge.incomingMessageId(listOf(listOf("cm", shared)), "different-nostr-hash")

        assertEquals("Both copies must collapse to one entry", bleId, nostrId)
        assertNotEquals("...and not to the raw Nostr hash", "different-nostr-hash", nostrId)
    }
}
