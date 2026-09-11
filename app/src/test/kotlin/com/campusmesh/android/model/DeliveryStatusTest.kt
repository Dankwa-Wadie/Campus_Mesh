package com.campusmesh.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Locks in the distinction the mesh UI depends on: handing a packet to a neighbour is not the same
 * as the recipient receiving it. A previous version reported [DeliveryStatus.Delivered] as soon as
 * a local file transfer completed, which told the user something untrue.
 */
class DeliveryStatusTest {

    @Test
    fun `sent to mesh does not claim delivery`() {
        val text = DeliveryStatus.SentToMesh.getDisplayText()
        assertEquals("Sent to mesh", text)
        assertTrue(
            "A handed-to-neighbour state must not read as delivered",
            !text.lowercase().contains("delivered")
        )
    }

    @Test
    fun `delivered names the recipient that acknowledged`() {
        val status = DeliveryStatus.Delivered(to = "ama", at = Date())
        assertEquals("Delivered to ama", status.getDisplayText())
    }

    @Test
    fun `queued is distinct from sending`() {
        assertEquals("Queued", DeliveryStatus.Queued.getDisplayText())
        assertEquals("Sending...", DeliveryStatus.Sending.getDisplayText())
    }

    @Test
    fun `partial delivery reports real reach`() {
        assertEquals(
            "Delivered to 3/7",
            DeliveryStatus.PartiallyDelivered(reached = 3, total = 7).getDisplayText()
        )
    }

    @Test
    fun `failure carries a reason for actionable retry`() {
        assertEquals("Failed: no route", DeliveryStatus.Failed("no route").getDisplayText())
    }
}
