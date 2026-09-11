package com.campusmesh.android.mesh

/**
 * Why the BLE mesh is or is not running.
 *
 * Startup previously failed silently: `startServices()` logged at error level and returned false,
 * and nothing surfaced to the user. Internet-backed chat kept working, so the app looked healthy
 * while mesh messaging was entirely dead — the hardest kind of failure to diagnose in the field.
 */
sealed class MeshStartupStatus {

    /** Not attempted yet. */
    object Idle : MeshStartupStatus()

    /** Running. Says nothing about whether any peer is in range. */
    object Running : MeshStartupStatus()

    /** Turned off deliberately in debug settings. */
    object DisabledByUser : MeshStartupStatus()

    /** One or more of BLUETOOTH_SCAN / CONNECT / ADVERTISE is not granted. */
    object MissingPermissions : MeshStartupStatus()

    /** The Bluetooth adapter is off. */
    object BluetoothOff : MeshStartupStatus()

    /**
     * Location services are switched off at the system level.
     *
     * This is the one that surprises people. Below Android 12 — and on 12+ whenever
     * BLUETOOTH_SCAN is not declared with `neverForLocation` — Android requires location
     * *services* (not merely the permission) to be enabled before BLE scan results are delivered.
     * With them off, the scan starts successfully, no error is raised, and no callback ever fires.
     * The mesh simply never discovers anyone. minSdk here is 26, so a large share of target
     * devices are affected regardless of the manifest flag.
     */
    object LocationServicesOff : MeshStartupStatus()

    /** The GATT server or client failed to start. [detail] carries whatever the stack reported. */
    data class Failed(val detail: String) : MeshStartupStatus()

    /** True when the mesh is not running and the user could plausibly fix it. */
    val isActionable: Boolean
        get() = this is MissingPermissions || this is BluetoothOff || this is LocationServicesOff

    /** Short explanation suitable for a UI banner. */
    fun describe(): String = when (this) {
        is Idle -> "Mesh not started"
        is Running -> "Mesh running"
        is DisabledByUser -> "Bluetooth mesh turned off in settings"
        is MissingPermissions -> "Bluetooth permissions needed for nearby chat"
        is BluetoothOff -> "Turn on Bluetooth to reach nearby people"
        is LocationServicesOff -> "Turn on Location to find nearby people over Bluetooth"
        is Failed -> "Bluetooth mesh could not start: $detail"
    }
}
