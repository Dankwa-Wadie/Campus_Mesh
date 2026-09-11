package com.campusmesh.android.net

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pairing secret for the local web gateway.
 *
 * [KtorGatewayManager] listens on 0.0.0.0:8080 and is advertised over mDNS, so every endpoint it
 * exposes is reachable by anything on the same Wi-Fi. The WebSocket bridge relays mesh traffic to
 * whoever is connected, which without a check means any device on a campus network can sit and
 * read broadcast messages passing through this phone.
 *
 * The token gates that. It is handed to a client out-of-band - via the QR / gateway URL the user
 * shows to the person they are inviting - and must accompany any request that carries mesh data.
 *
 * Persisted rather than regenerated per start so that a foreground-service restart does not
 * silently unpair every device the user has already invited; [regenerate] revokes them all.
 */
object GatewayAuth {

    private const val PREFS_NAME = "gateway_auth"
    private const val KEY_TOKEN = "pairing_token"
    private const val TOKEN_BYTES = 20 // 160 bits

    @Volatile
    private var cached: String? = null

    /** The current pairing token, creating one on first use. */
    @Synchronized
    fun token(context: Context): String {
        cached?.let { return it }

        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val existing = prefs.getString(KEY_TOKEN, null)
        if (existing != null) {
            cached = existing
            return existing
        }

        val fresh = newToken()
        prefs.edit().putString(KEY_TOKEN, fresh).apply()
        cached = fresh
        return fresh
    }

    /** Issues a new token, immediately invalidating every previously paired client. */
    @Synchronized
    fun regenerate(context: Context): String {
        val fresh = newToken()
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, fresh).apply()
        cached = fresh
        return fresh
    }

    /**
     * Constant-time comparison of [candidate] against the live token.
     *
     * A naive `==` on strings short-circuits at the first differing byte, which leaks the length
     * of the matching prefix to anyone who can time the response - enough to recover the token
     * byte by byte over a LAN.
     */
    fun isValid(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        val expected = token(context)
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        // URL-safe: the token travels as a query parameter in the gateway URL.
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
