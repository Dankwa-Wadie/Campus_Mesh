package com.campusmesh.android.identity

/**
 * Monotonic lower bound on trusted wall-clock time, for credential validity checks.
 *
 * [SchoolCredentialVerifier.verify] takes `nowSeconds` from its caller, and on Android that traces
 * back to a user-settable clock. Rolling the device date backwards would otherwise revive an
 * expired credential — the holder simply sets the date to last month and keeps a verified badge
 * after the school revoked or expired it.
 *
 * This records the greatest timestamp ever observed from a source we are willing to trust (a valid
 * credential's `issuedAtSeconds`, a server response, a peer's verified credential) and refuses to
 * report anything earlier. Time can move forward freely; it can never move back.
 *
 * This is the same shape of defence `CampusPresenceTracker` already applies to presence by using
 * `SystemClock.elapsedRealtime()`. Credentials need it more, because presence expires in minutes
 * while a credential is meant to last days.
 *
 * Not a secure clock. It cannot detect a device whose clock was already wrong when the floor was
 * first recorded, and it cannot prove current time to a peer. It only makes rollback ineffective.
 */
class TrustedTimeFloor(private val store: SecureIdentityStateManager) {

    companion object {
        private const val KEY_FLOOR = "trusted_time_floor_seconds"
    }

    private val lock = Any()

    /** Greatest trusted timestamp recorded so far, or 0 if nothing has been observed yet. */
    fun floorSeconds(): Long = synchronized(lock) {
        store.getSecureValue(KEY_FLOOR)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * Records [observedSeconds] if it is later than the current floor. Values at or below the
     * floor, and negative values, are ignored rather than lowering it.
     */
    fun observe(observedSeconds: Long) {
        if (observedSeconds <= 0L) return
        synchronized(lock) {
            val current = store.getSecureValue(KEY_FLOOR)?.toLongOrNull() ?: 0L
            if (observedSeconds > current) {
                store.storeSecureValue(KEY_FLOOR, observedSeconds.toString())
            }
        }
    }

    /**
     * The time to hand to credential verification: [deviceSeconds], or the floor when the device
     * clock reads earlier than time we have already seen pass.
     *
     * Using the floor rather than rejecting outright keeps the app usable on a device whose clock
     * is merely wrong: verification still runs, but against a time that cannot be rewound.
     */
    fun effectiveNowSeconds(deviceSeconds: Long): Long = maxOf(deviceSeconds, floorSeconds())

    /** True when the device clock reads earlier than time we have already observed passing. */
    fun isClockRolledBack(deviceSeconds: Long): Boolean = deviceSeconds < floorSeconds()
}
