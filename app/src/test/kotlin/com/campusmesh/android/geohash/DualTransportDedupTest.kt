package com.campusmesh.android.geohash

import android.os.Build
import com.campusmesh.android.model.BitchatMessage
import com.campusmesh.android.services.AppStateStore
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end identity check for a campus channel running over both transports.
 *
 * A geohash channel now broadcasts over BLE *and* publishes to Nostr, so anyone with both radios
 * receives the same message twice. These tests pin down the two things that keep it appearing
 * once: a shared id carried on both paths, and both paths filing under the same storage key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class DualTransportDedupTest {

    private val geohash = MainCampusGeohash.PRIMARY

    @Before
    fun setup() {
        AppStateStore.clear()
    }

    private fun message(id: String, content: String, sender: String) = BitchatMessage(
        id = id,
        sender = sender,
        content = content,
        timestamp = Date(1_700_000_000_000L),
        channel = "#$geohash"
    )

    /** The whole point: one logical message, two transports, one entry in the channel. */
    @Test
    fun `the same message over both transports appears once`() {
        val sharedId = GeohashBridge.newSharedMessageId()
        val key = GeohashBridge.storageKey(geohash)

        // BLE copy: filed under the storage key derived from its channel tag.
        val bleChannel = GeohashBridge.channelTag(geohash)
        val bleGeohash = GeohashBridge.geohashFromChannelTag(bleChannel)!!
        AppStateStore.addChannelMessage(
            GeohashBridge.storageKey(bleGeohash),
            message(sharedId, "meet at the library", "ama")
        )

        // Nostr copy: same shared id, read from the event's "cm" tag.
        val nostrId = GeohashBridge.incomingMessageId(
            listOf(listOf("g", geohash), listOf("cm", sharedId)),
            "a-different-nostr-event-hash"
        )
        AppStateStore.addChannelMessage(key, message(nostrId, "meet at the library", "ama"))

        assertEquals(
            "One message sent over two transports must not appear twice",
            1,
            AppStateStore.channelMessages.value[key]?.size
        )
    }

    /** Both transports must write to the same key, or the channel splits into two views. */
    @Test
    fun `both transports file under the same storage key`() {
        val bleTag = GeohashBridge.channelTag(geohash)
        val fromBle = GeohashBridge.storageKey(GeohashBridge.geohashFromChannelTag(bleTag)!!)
        val fromNostr = "geo:$geohash"

        assertEquals(fromNostr, fromBle)
    }

    /** Dedup must not be so eager that two genuinely different messages collapse. */
    @Test
    fun `distinct messages are both kept`() {
        val key = GeohashBridge.storageKey(geohash)

        AppStateStore.addChannelMessage(key, message(GeohashBridge.newSharedMessageId(), "first", "ama"))
        AppStateStore.addChannelMessage(key, message(GeohashBridge.newSharedMessageId(), "second", "ama"))

        assertEquals(2, AppStateStore.channelMessages.value[key]?.size)
    }

    /**
     * A sender that does not set the shared tag still gets through; it just cannot be matched to a
     * BLE copy. Dropping it instead would lose interoperability with other clients.
     */
    @Test
    fun `an untagged nostr message is still delivered`() {
        val key = GeohashBridge.storageKey(geohash)
        val id = GeohashBridge.incomingMessageId(listOf(listOf("g", geohash)), "nostr-hash-only")

        AppStateStore.addChannelMessage(key, message(id, "from another client", "someone"))

        assertEquals(1, AppStateStore.channelMessages.value[key]?.size)
        assertEquals("nostr-hash-only", AppStateStore.channelMessages.value[key]?.first()?.id)
    }

    /** The BLE prefix must survive a round trip, or the receiver files the message wrong. */
    @Test
    fun `the ble content prefix round-trips`() {
        val sharedId = GeohashBridge.newSharedMessageId()
        val prefix = GeohashBridge.encodeChannelPrefix(geohash, sharedId)
        val payload = prefix + "meet at the library"

        val closeBracket = payload.indexOf(']')
        val parsed = GeohashBridge.parseChannelPrefixBody(payload.substring(1, closeBracket))

        assertEquals(GeohashBridge.channelTag(geohash), parsed.channelTag)
        assertEquals(sharedId, parsed.sharedMessageId)
        assertEquals("meet at the library", payload.substring(closeBracket + 1))
    }
}
