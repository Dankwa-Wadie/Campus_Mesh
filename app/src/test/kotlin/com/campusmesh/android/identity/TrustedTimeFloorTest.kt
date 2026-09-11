package com.campusmesh.android.identity

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.campusmesh.testing.FakeAndroidKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Credential expiry is checked against a caller-supplied `nowSeconds`, which on Android traces to a
 * user-settable clock. Without a floor, a holder could set the date back a month and keep using an
 * expired or revoked credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class TrustedTimeFloorTest {

    private lateinit var floor: TrustedTimeFloor

    private val jan2026 = 1_767_225_600L
    private val feb2026 = 1_769_904_000L

    @Before
    fun setup() {
        FakeAndroidKeyStore.install()
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = SecureIdentityStateManager(context)
        store.storeSecureValue("trusted_time_floor_seconds", "")
        floor = TrustedTimeFloor(store)
        floor.observe(jan2026)
    }

    @Test
    fun `time moves forward freely`() {
        assertEquals(feb2026, floor.effectiveNowSeconds(feb2026))
        floor.observe(feb2026)
        assertEquals(feb2026, floor.floorSeconds())
    }

    @Test
    fun `a rolled back clock cannot rewind past observed time`() {
        val rolledBack = jan2026 - 30 * 24 * 3600

        assertTrue("Rollback should be detected", floor.isClockRolledBack(rolledBack))
        assertEquals(
            "Verification must see the floor, not the rewound clock",
            jan2026,
            floor.effectiveNowSeconds(rolledBack)
        )
    }

    @Test
    fun `the floor never decreases`() {
        floor.observe(feb2026)
        floor.observe(jan2026)          // older
        floor.observe(-5L)              // nonsense
        floor.observe(0L)

        assertEquals(feb2026, floor.floorSeconds())
    }

    @Test
    fun `an honest clock is reported unchanged`() {
        val later = feb2026 + 3600
        assertFalse(floor.isClockRolledBack(later))
        assertEquals(later, floor.effectiveNowSeconds(later))
    }

    @Test
    fun `the floor survives a new instance`() {
        floor.observe(feb2026)

        val context: Context = ApplicationProvider.getApplicationContext()
        val reloaded = TrustedTimeFloor(SecureIdentityStateManager(context))

        assertEquals(
            "A restart must not reset the floor, or rollback becomes trivial",
            feb2026,
            reloaded.floorSeconds()
        )
    }
}
