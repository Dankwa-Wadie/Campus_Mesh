package com.campusmesh.android.geohash

import com.campusmesh.android.data.AppMode
import org.junit.Assert.*
import org.junit.Test

class CampusPresenceTrackerTest {
    private fun boundary(distance: Float) = listOf(CampusPresenceTracker.Boundary(AppMode.MAIN_CAMPUS, "Main", distance, 300.0))
    @Test fun `accurate inside fix grants presence and silence expires it`() {
        val tracker = CampusPresenceTracker()
        assertEquals(CampusPresence.Status.INSIDE, tracker.update(boundary(100f), 20f, 1000, 1000).status)
        assertEquals(CampusPresence.Status.GRACE, tracker.snapshot(62000).status)
        assertEquals(AppMode.GENERAL_MESH, tracker.snapshot(181000).mode)
    }
    @Test fun `uncertain boundary cannot grant access or renew old evidence`() {
        val tracker = CampusPresenceTracker()
        assertEquals(AppMode.GENERAL_MESH, tracker.update(boundary(290f), 40f, 1000, 1000).mode)
        tracker.update(boundary(100f), 10f, 2000, 2000)
        assertEquals(CampusPresence.Status.GRACE, tracker.update(boundary(290f), 40f, 90000, 90000).status)
        assertEquals(AppMode.GENERAL_MESH, tracker.snapshot(182000).mode)
    }
    @Test fun `outside fixes allow grace then remove access`() {
        val tracker = CampusPresenceTracker()
        tracker.update(boundary(100f), 10f, 1000, 1000)
        assertEquals(CampusPresence.Status.GRACE, tracker.update(boundary(500f), 10f, 2000, 2000).status)
        tracker.update(boundary(500f), 10f, 100000, 100000)
        assertEquals(CampusPresence.Status.OUTSIDE, tracker.snapshot(122000).status)
    }
    @Test fun `stale missing and inaccurate fixes cannot grant access`() {
        val tracker = CampusPresenceTracker()
        assertEquals(AppMode.GENERAL_MESH, tracker.update(boundary(0f), 10f, 1000, 100000).mode)
        assertEquals(AppMode.GENERAL_MESH, tracker.update(boundary(0f), null, 100001, 100001).mode)
        assertEquals(AppMode.GENERAL_MESH, tracker.update(boundary(0f), 500f, 100002, 100002).mode)
    }
    @Test fun `replayed fixes do not extend presence and fresh reentry restores it`() {
        val tracker = CampusPresenceTracker()
        tracker.update(boundary(100f), 10f, 1000, 1000)
        assertEquals(AppMode.GENERAL_MESH, tracker.update(boundary(100f), 10f, 1000, 181000).mode)
        assertEquals(CampusPresence.Status.INSIDE, tracker.update(boundary(100f), 10f, 182000, 182000).status)
    }
}
