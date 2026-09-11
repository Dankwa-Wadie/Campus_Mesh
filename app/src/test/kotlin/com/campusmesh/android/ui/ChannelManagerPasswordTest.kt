package com.campusmesh.android.ui

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the channel password gate, which was previously stubbed to `return true` - any password
 * admitted anyone to a password-protected channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class ChannelManagerPasswordTest {

    private lateinit var channelManager: ChannelManager
    private lateinit var state: ChatState

    private val myPeerID = "1111222233334444"
    private val otherPeerID = "aaaabbbbccccdddd"
    private val channel = "#secret"

    @Before
    fun setup() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        state = ChatState(scope)
        val dataManager = DataManager(ApplicationProvider.getApplicationContext())
        channelManager = ChannelManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = dataManager,
            coroutineScope = scope
        )
    }

    /**
     * The reachable gate: the password branch in joinChannel only runs when the channel is marked
     * password-protected AND no key is held. Leaving a channel drops the key but keeps the
     * commitment, so rejoining is exactly that state.
     */
    @Test
    fun `rejoin with the wrong password is refused`() {
        channelManager.setChannelPassword(channel, "correct horse battery staple")
        channelManager.leaveChannel(channel)

        val joined = channelManager.joinChannel(channel, password = "guess", myPeerID = otherPeerID)

        assertFalse("A wrong password must not admit the user", joined)
        assertFalse(
            "The channel must not appear in the joined set",
            state.getJoinedChannelsValue().contains(channel)
        )
    }

    @Test
    fun `rejoin with the correct password is admitted`() {
        val password = "correct horse battery staple"
        channelManager.setChannelPassword(channel, password)
        channelManager.leaveChannel(channel)

        val joined = channelManager.joinChannel(channel, password = password, myPeerID = otherPeerID)

        assertTrue("The correct password must admit the user", joined)
        assertTrue(
            "The channel should now be joined",
            state.getJoinedChannelsValue().contains(channel)
        )
    }

    /** A rejoin with the right password must restore a working key, not just set a flag. */
    @Test
    fun `rejoin with the correct password restores a working key`() {
        val password = "correct horse battery staple"
        channelManager.setChannelPassword(channel, password)

        var ciphertext: ByteArray? = null
        channelManager.sendEncryptedChannelMessage(
            content = "meet at the library",
            mentions = emptyList(),
            channel = channel,
            senderNickname = "tester",
            myPeerID = myPeerID,
            onEncryptedPayload = { ciphertext = it },
            onFallback = { }
        )
        assertTrue("Message should have been encrypted", ciphertext != null)

        channelManager.leaveChannel(channel)
        assertTrue(channelManager.joinChannel(channel, password = password, myPeerID = myPeerID))

        assertTrue(
            "Rejoined member should be able to read channel traffic again",
            channelManager.decryptChannelMessage(ciphertext!!, channel) == "meet at the library"
        )
    }

}
