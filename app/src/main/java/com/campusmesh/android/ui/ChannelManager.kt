package com.campusmesh.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.campusmesh.android.model.BitchatMessage
import java.util.*

/**
 * Handles channel management including creation, joining, leaving, and encryption
 */
class ChannelManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope
) {
    
    // Channel encryption and security
    private val channelKeys = mutableMapOf<String, SecretKeySpec>()
    private val channelPasswords = mutableMapOf<String, String>()
    private val channelKeyCommitments = mutableMapOf<String, String>()
    private val retentionEnabledChannels = mutableSetOf<String>()
    
    // MARK: - Channel Lifecycle
    
    fun joinChannel(channel: String, password: String? = null, myPeerID: String): Boolean {
        val channelTag = if (channel.startsWith("#")) channel else "#$channel"
        
        // Check if already joined
        if (state.getJoinedChannelsValue().contains(channelTag)) {
            if (state.getPasswordProtectedChannelsValue().contains(channelTag) && !channelKeys.containsKey(channelTag)) {
                // Need password verification
                if (password != null) {
                    return verifyChannelPassword(channelTag, password)
                } else {
                    state.setPasswordPromptChannel(channelTag)
                    state.setShowPasswordPrompt(true)
                    return false
                }
            }
            switchToChannel(channelTag)
            return true
        }
        
        // If password protected and no key yet
        if (state.getPasswordProtectedChannelsValue().contains(channelTag) && !channelKeys.containsKey(channelTag)) {
            if (dataManager.isChannelCreator(channelTag, myPeerID)) {
                // Channel creator bypass
            } else if (password != null) {
                if (!verifyChannelPassword(channelTag, password)) {
                    return false
                }
            } else {
                state.setPasswordPromptChannel(channelTag)
                state.setShowPasswordPrompt(true)
                return false
            }
        }
        
        // Join the channel
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.add(channelTag)
        state.setJoinedChannels(updatedChannels)
        
        // Set as creator if new channel
        if (!dataManager.channelCreators.containsKey(channelTag) && !state.getPasswordProtectedChannelsValue().contains(channelTag)) {
            dataManager.addChannelCreator(channelTag, myPeerID)
        }
        
        // Add ourselves as member
        dataManager.addChannelMember(channelTag, myPeerID)
        
        // Initialize channel messages if needed
        if (!state.getChannelMessagesValue().containsKey(channelTag)) {
            val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
            updatedChannelMessages[channelTag] = emptyList()
            state.setChannelMessages(updatedChannelMessages)
        }
        
        switchToChannel(channelTag)
        saveChannelData()
        return true
    }
    
    fun leaveChannel(channel: String) {
        val updatedChannels = state.getJoinedChannelsValue().toMutableSet()
        updatedChannels.remove(channel)
        state.setJoinedChannels(updatedChannels)
        
        // Exit channel if currently in it
        if (state.getCurrentChannelValue() == channel) {
            state.setCurrentChannel(null)
        }
        
        // Cleanup
        messageManager.removeChannelMessages(channel)
        dataManager.removeChannelMembers(channel)
        channelKeys.remove(channel)
        channelPasswords.remove(channel)
        // The key commitment is deliberately kept: it holds no key material (it is a hash), and
        // retaining it is what lets verifyChannelPassword actually reject a wrong password when
        // this user rejoins later. Only clearAllChannels (panic wipe) discards it.
        dataManager.removeChannelCreator(channel)
        
        saveChannelData()
    }
    
    fun switchToChannel(channel: String?) {
        state.setCurrentChannel(channel)
        state.setSelectedPrivateChatPeer(null)
        
        // Clear unread count
        channel?.let { ch ->
            messageManager.clearChannelUnreadCount(ch)
        }
    }
    
    // MARK: - Channel Password and Encryption
    
    /**
     * Checks [password] against the key commitment recorded for [channel] and, on success,
     * installs the derived key so channel traffic can actually be decrypted.
     *
     * Previously this was stubbed to `return true`, so any password admitted anyone - and because
     * the join path never derived a key either, the "member" then sat in a channel whose messages
     * they could never read.
     *
     * A commitment is SHA-256 of the derived key, so it can be compared without keeping the
     * password or the key itself around for comparison. We only hold commitments for channels
     * whose password this device has set or previously verified; for a channel created by someone
     * else there is nothing local to check against, so the key is derived and installed and
     * decryption becomes the real gate - a wrong password yields a key that simply never decrypts
     * anything. That is weaker than a commitment check but it is honest, and it is strictly better
     * than admitting everyone with no key at all.
     */
    private fun verifyChannelPassword(channel: String, password: String): Boolean {
        val derivedKey = deriveChannelKey(password, channel)
        val commitment = channelKeyCommitments[channel]

        if (commitment != null && commitment != keyCommitment(derivedKey)) {
            return false
        }

        channelKeys[channel] = derivedKey
        channelPasswords[channel] = password
        if (commitment == null) {
            // First successful use on this device; record it so later attempts are checked.
            channelKeyCommitments[channel] = keyCommitment(derivedKey)
        }
        return true
    }

    /** SHA-256 of the derived key - lets us check a password without storing key material. */
    private fun keyCommitment(key: SecretKeySpec): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(key.encoded)
            .joinToString("") { "%02x".format(it) }
    }
    
    private fun deriveChannelKey(password: String, channelName: String): SecretKeySpec {
        // PBKDF2 key derivation (same as iOS version)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            channelName.toByteArray(),
            100000, // 100,000 iterations (same as iOS)
            256 // 256-bit key
        )
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }
    
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return decryptChannelMessage(encryptedContent, channel, null)
    }
    
    private fun decryptChannelMessage(encryptedContent: ByteArray, channel: String, testKey: SecretKeySpec?): String? {
        val key = testKey ?: channelKeys[channel] ?: return null
        
        try {
            if (encryptedContent.size < 16) return null // 12 bytes IV + minimum ciphertext
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = encryptedContent.sliceArray(0..11)
            val ciphertext = encryptedContent.sliceArray(12 until encryptedContent.size)
            
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            val decryptedData = cipher.doFinal(ciphertext)
            return String(decryptedData, Charsets.UTF_8)
            
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Encrypts [content] for [channel] and hands the ciphertext to [onEncryptedPayload].
     *
     * Exactly one of [onEncryptedPayload] / [onFallback] is always invoked - returning without
     * calling either strands the message in the local list while never putting it on the wire,
     * which looks to the sender exactly like a successful send.
     */
    fun sendEncryptedChannelMessage(
        content: String,
        mentions: List<String>,
        channel: String,
        senderNickname: String?,
        myPeerID: String,
        onEncryptedPayload: (ByteArray) -> Unit,
        onFallback: () -> Unit
    ) {
        val key = channelKeys[channel]
        if (key == null) {
            onFallback()
            return
        }

        val encrypted = encryptChannelContent(content, key)
        if (encrypted == null) {
            onFallback()
            return
        }

        onEncryptedPayload(encrypted)
    }

    /**
     * AES-256-GCM with a fresh 12-byte IV prefixed to the ciphertext - the exact wire format
     * [decryptChannelMessage] expects, so the two stay symmetric.
     */
    private fun encryptChannelContent(content: String, key: SecretKeySpec): ByteArray? {
        return try {
            val iv = ByteArray(12)
            java.security.SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(content.toByteArray(Charsets.UTF_8))

            iv + ciphertext
        } catch (e: Exception) {
            null
        }
    }
    
    // MARK: - Channel Management
    
    fun addChannelMessage(channel: String, message: BitchatMessage, senderPeerID: String?) {
        messageManager.addChannelMessage(channel, message)
        
        // Track as channel member
        senderPeerID?.let { peerID ->
            dataManager.addChannelMember(channel, peerID)
        }
    }
    
    fun removeChannelMember(channel: String, peerID: String) {
        dataManager.removeChannelMember(channel, peerID)
    }
    
    fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) {
        dataManager.cleanupAllDisconnectedMembers(connectedPeers, myPeerID)
    }
    
    // MARK: - Channel Information
    
    fun isChannelPasswordProtected(channel: String): Boolean {
        return state.getPasswordProtectedChannelsValue().contains(channel)
    }
    
    fun hasChannelKey(channel: String): Boolean {
        return channelKeys.containsKey(channel)
    }
    
    fun getChannelPassword(channel: String): String? {
        return channelPasswords[channel]
    }
    
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        return dataManager.isChannelCreator(channel, peerID)
    }
    
    fun getJoinedChannelsList(): List<String> {
        return state.getJoinedChannelsValue().toList().sorted()
    }
    
    // MARK: - Data Persistence
    
    private fun saveChannelData() {
        dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue())
    }
    
    fun loadChannelData(): Pair<Set<String>, Set<String>> {
        return dataManager.loadChannelData()
    }
    
    // MARK: - Password Management
    
    fun hidePasswordPrompt() {
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
    }

    fun setChannelPassword(channel: String, password: String) {

        channelPasswords[channel] = password

        val derivedKey = deriveChannelKey(password, channel)
        channelKeys[channel] = derivedKey
        // Record the commitment so verifyChannelPassword has something to check against.
        channelKeyCommitments[channel] = keyCommitment(derivedKey)

        state.setPasswordProtectedChannels(
            state.getPasswordProtectedChannelsValue().toMutableSet().apply { add(channel) }
        )

        dataManager.saveChannelData(
            state.getJoinedChannelsValue(),
            state.getPasswordProtectedChannelsValue()
        )
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllChannels() {
        state.setJoinedChannels(emptySet())
        state.setCurrentChannel(null)
        state.setPasswordProtectedChannels(emptySet())
        state.setShowPasswordPrompt(false)
        state.setPasswordPromptChannel(null)
        
        channelKeys.clear()
        channelPasswords.clear()
        channelKeyCommitments.clear()
        retentionEnabledChannels.clear()
    }
}
