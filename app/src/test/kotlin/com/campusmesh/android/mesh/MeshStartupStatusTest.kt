package com.campusmesh.android.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dead mesh and an empty room both show zero peers. These statuses are what lets the UI tell
 * them apart, so the distinction between "user can fix this" and "nothing to say" matters.
 */
class MeshStartupStatusTest {

    @Test
    fun `user-fixable causes are actionable`() {
        assertTrue(MeshStartupStatus.LocationServicesOff.isActionable)
        assertTrue(MeshStartupStatus.BluetoothOff.isActionable)
        assertTrue(MeshStartupStatus.MissingPermissions.isActionable)
    }

    /** A healthy mesh with nobody in range is normal and must not raise a warning. */
    @Test
    fun `running and idle are not actionable`() {
        assertFalse("A running mesh with no peers is normal", MeshStartupStatus.Running.isActionable)
        assertFalse(MeshStartupStatus.Idle.isActionable)
    }

    /** Turning BLE off in settings is the user's own choice; nagging about it is wrong. */
    @Test
    fun `a deliberate opt-out is not flagged as a problem`() {
        assertFalse(MeshStartupStatus.DisabledByUser.isActionable)
    }

    /** A GATT failure is real but there is no single setting to send the user to. */
    @Test
    fun `an internal failure is reported but not actionable`() {
        val failed = MeshStartupStatus.Failed("GATT server did not start")
        assertFalse(failed.isActionable)
        assertTrue(failed.describe().contains("GATT server did not start"))
    }

    @Test
    fun `every status explains itself without leaking internals`() {
        val statuses = listOf(
            MeshStartupStatus.Idle,
            MeshStartupStatus.Running,
            MeshStartupStatus.DisabledByUser,
            MeshStartupStatus.MissingPermissions,
            MeshStartupStatus.BluetoothOff,
            MeshStartupStatus.LocationServicesOff,
            MeshStartupStatus.Failed("boom")
        )
        statuses.forEach { status ->
            val text = status.describe()
            assertTrue("${status::class.simpleName} must describe itself", text.isNotBlank())
            assertFalse(
                "${status::class.simpleName} must not surface a class name to users",
                text.contains("MeshStartupStatus")
            )
        }
    }

    /** The location message must name Location, since that is the non-obvious cause. */
    @Test
    fun `the location message names the setting to change`() {
        val text = MeshStartupStatus.LocationServicesOff.describe()
        assertTrue("Message should mention Location", text.contains("Location", ignoreCase = true))
        assertTrue("Message should mention Bluetooth", text.contains("Bluetooth", ignoreCase = true))
    }
}
