package com.campusmesh.android.geohash

import com.campusmesh.android.data.AppMode

/** Local participation hint, not proof of membership or secure proof of physical presence. */
data class CampusPresence(
    val mode: AppMode = AppMode.GENERAL_MESH,
    val campusName: String? = null,
    val status: Status = Status.UNKNOWN
) {
    enum class Status { UNKNOWN, INSIDE, GRACE, OUTSIDE }
}

/** Uses monotonic milliseconds. Uncertain fixes never renew confirmed presence. */
class CampusPresenceTracker {
    data class Boundary(val mode: AppMode, val name: String, val distance: Float, val radius: Double)
    private var confirmed: CampusPresence? = null
    private var confirmedAt = Long.MIN_VALUE
    private var outsideSince: Long? = null
    private var lastFix = Long.MIN_VALUE
    private var current = CampusPresence()

    @Synchronized
    fun update(boundaries: List<Boundary>, accuracy: Float?, fixAt: Long, now: Long): CampusPresence {
        if (fixAt < 0 || fixAt > now || now - fixAt > FRESH_MS || fixAt <= lastFix ||
            accuracy == null || !accuracy.isFinite() || accuracy < 0 || accuracy > MAX_ACCURACY_METERS) {
            return snapshot(now)
        }
        lastFix = fixAt
        val valid = boundaries.filter { it.distance.isFinite() && it.distance >= 0 && it.radius.isFinite() && it.radius > 0 }
        val inside = valid.firstOrNull { it.distance + accuracy <= it.radius }
        if (inside != null) {
            confirmed = CampusPresence(inside.mode, inside.name, CampusPresence.Status.INSIDE)
            confirmedAt = fixAt
            outsideSince = null
            current = confirmed!!
        } else {
            val outside = valid.isNotEmpty() && valid.size == boundaries.size &&
                valid.all { it.distance - accuracy > it.radius + EXIT_BUFFER_METERS }
            if (outside) {
                if (outsideSince == null) outsideSince = fixAt
                current = CampusPresence(status = CampusPresence.Status.OUTSIDE)
            } else {
                outsideSince = null
                current = CampusPresence()
            }
        }
        return snapshot(now)
    }

    @Synchronized
    fun snapshot(now: Long): CampusPresence {
        val previous = confirmed
        if (previous != null) {
            val evidenceAge = now - confirmedAt
            val outsideAge = outsideSince?.let { now - it }
            if (evidenceAge < 0 || evidenceAge >= EXPIRY_MS || (outsideAge != null && outsideAge >= GRACE_MS)) {
                confirmed = null
                if (outsideSince == null || now - lastFix > FRESH_MS) current = CampusPresence()
            } else {
                return previous.copy(status = if (outsideSince == null && current.status == CampusPresence.Status.INSIDE && evidenceAge <= FRESH_MS)
                    CampusPresence.Status.INSIDE else CampusPresence.Status.GRACE)
            }
        }
        if (lastFix == Long.MIN_VALUE || now - lastFix > FRESH_MS) return CampusPresence()
        return current
    }

    companion object {
        const val FRESH_MS = 60_000L
        const val GRACE_MS = 120_000L
        const val EXPIRY_MS = 180_000L
        const val MAX_ACCURACY_METERS = 100f
        const val EXIT_BUFFER_METERS = 30.0
    }
}
