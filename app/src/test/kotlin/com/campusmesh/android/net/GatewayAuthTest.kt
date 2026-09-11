package com.campusmesh.android.net

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gateway listens on every interface and is advertised over mDNS, so this token is the only
 * thing standing between a stranger on the same Wi-Fi and the mesh traffic relayed through this
 * device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class GatewayAuthTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("gateway_auth", Context.MODE_PRIVATE)
            .edit().clear().commit()
        GatewayAuth.regenerate(context)
    }

    @Test
    fun `token is stable across calls so a service restart does not unpair clients`() {
        val first = GatewayAuth.token(context)
        assertEquals(first, GatewayAuth.token(context))
        assertTrue("Token should be non-trivial", first.length >= 20)
    }

    @Test
    fun `the issued token authenticates`() {
        assertTrue(GatewayAuth.isValid(context, GatewayAuth.token(context)))
    }

    @Test
    fun `absent empty and wrong tokens are all rejected`() {
        assertFalse("null must not authenticate", GatewayAuth.isValid(context, null))
        assertFalse("empty must not authenticate", GatewayAuth.isValid(context, ""))
        assertFalse("wrong token must not authenticate", GatewayAuth.isValid(context, "not-the-token"))
    }

    /** Guards against a prefix compare: a truncated token must not pass. */
    @Test
    fun `a prefix of the real token is rejected`() {
        val real = GatewayAuth.token(context)
        assertFalse(GatewayAuth.isValid(context, real.substring(0, real.length - 1)))
        assertFalse(GatewayAuth.isValid(context, real + "x"))
    }

    @Test
    fun `regenerate revokes previously paired clients`() {
        val old = GatewayAuth.token(context)
        val fresh = GatewayAuth.regenerate(context)

        assertNotEquals("Regenerate must issue a different token", old, fresh)
        assertFalse("The old token must stop working", GatewayAuth.isValid(context, old))
        assertTrue("The new token must work", GatewayAuth.isValid(context, fresh))
    }
}
