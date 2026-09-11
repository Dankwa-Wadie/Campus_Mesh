package com.campusmesh.android.ui

import com.campusmesh.android.geohash.ChannelID
import com.campusmesh.android.geohash.GeohashChannel
import com.campusmesh.android.geohash.GeohashChannelLevel
import com.campusmesh.android.geohash.MainCampusGeohash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the routing decision in ChatViewModel.sendMessage. If that ordering changes and this is
 * not updated, the badge above the composer starts lying about where messages go — which is worse
 * than showing nothing.
 *
 * Motivating bug: "Main Campus (Tesano)" is a geohash channel, so it routes over Nostr and has no
 * Bluetooth path. Offline, every message silently queued until the internet returned, and nothing
 * in the UI said so.
 */
class ConversationTransportTest {

    private val mainCampus = ChannelID.Location(MainCampusGeohash.CHANNEL)

    /**
     * Main Campus used to be Nostr-only, so messages sent offline silently queued until the
     * internet returned. It now goes out over both transports.
     */
    @Test
    fun `main campus sends over both transports and works offline`() {
        val transport = ConversationTransport.forSelection(
            selectedPrivatePeer = null,
            currentChannel = null,
            selectedLocationChannel = mainCampus
        )

        assertEquals(ConversationTransport.DUAL, transport)
        assertTrue("A campus channel must reach nearby devices with no internet", transport.worksOffline)
    }

    /**
     * sendMessage checks `currentChannelValue == null && selectedLocationChannel is Location`, so a
     * joined classic channel wins even while a geohash channel is still selected. That ordering is
     * load-bearing: tapping Main Campus then a sub-channel under it is the normal flow.
     */
    @Test
    fun `a joined classic channel beats an active geohash selection`() {
        val transport = ConversationTransport.forSelection(
            selectedPrivatePeer = null,
            currentChannel = "#campus-mesh",
            selectedLocationChannel = mainCampus
        )

        assertEquals(ConversationTransport.NEARBY_MESH, transport)
        assertTrue(transport.worksOffline)
    }

    @Test
    fun `campus sub-channels work offline`() {
        MainCampusGeohash.SUB_CHANNELS.forEach { (channel, _) ->
            val transport = ConversationTransport.forSelection(
                selectedPrivatePeer = null,
                currentChannel = channel,
                selectedLocationChannel = mainCampus
            )
            assertTrue("$channel should work offline over BLE", transport.worksOffline)
        }
    }

    /** Private messages go through MessageRouter, which picks mesh or Nostr per recipient. */
    @Test
    fun `direct messages route automatically`() {
        val transport = ConversationTransport.forSelection(
            selectedPrivatePeer = "aaaabbbbccccdddd",
            currentChannel = null,
            selectedLocationChannel = mainCampus
        )

        assertEquals(ConversationTransport.AUTOMATIC, transport)
        assertTrue("A DM can still reach a nearby peer with no internet", transport.worksOffline)
    }

    /** A private chat takes precedence over everything else in sendMessage. */
    @Test
    fun `a private chat wins over a joined channel`() {
        assertEquals(
            ConversationTransport.AUTOMATIC,
            ConversationTransport.forSelection(
                selectedPrivatePeer = "aaaabbbbccccdddd",
                currentChannel = "#campus-mesh",
                selectedLocationChannel = null
            )
        )
    }

    /** Plain mesh view: nothing selected, broadcast over BLE. */
    @Test
    fun `the bare mesh view is nearby mesh`() {
        val transport = ConversationTransport.forSelection(null, null, null)
        assertEquals(ConversationTransport.NEARBY_MESH, transport)
        assertTrue(transport.worksOffline)
    }

    @Test
    fun `every geohash channel is dual transport, not just main campus`() {
        val elsewhere = ChannelID.Location(GeohashChannel(GeohashChannelLevel.CITY, "ebzz"))
        assertEquals(
            ConversationTransport.DUAL,
            ConversationTransport.forSelection(null, null, elsewhere)
        )
    }

    /** Only a genuinely internet-only conversation may claim messages have to wait. */
    @Test
    fun `nothing reachable offline claims messages must wait`() {
        ConversationTransport.entries
            .filter { it.worksOffline }
            .forEach { transport ->
                assertFalse(
                    "${'$'}transport works offline and must not say messages wait for a connection",
                    transport.explain().contains("wait", ignoreCase = true)
                )
            }
    }

    @Test
    fun `every transport explains itself in plain words`() {
        ConversationTransport.entries.forEach { transport ->
            assertTrue(transport.label().isNotBlank())
            assertTrue("Explanation should be a sentence", transport.explain().length > 20)
        }
        assertTrue(
            "The internet channel must say messages wait",
            ConversationTransport.INTERNET.explain().contains("wait", ignoreCase = true)
        )
    }
}
