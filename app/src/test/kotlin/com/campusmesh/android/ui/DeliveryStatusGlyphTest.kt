package com.campusmesh.android.ui

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.campusmesh.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards a regression in the delivery indicator.
 *
 * Queued, Sending and SentToMesh all rendered as the same hollow circle, so "waiting for a route"
 * and "handed to a neighbour" were indistinguishable; and PartiallyDelivered rendered as the same
 * tick as Delivered, claiming full delivery when only some peers had been reached. On a mesh those
 * are different facts, and collapsing them tells the user something the app does not know.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class DeliveryStatusGlyphTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `each delivery state has its own glyph`() {
        val glyphs = mapOf(
            "queued" to context.getString(R.string.status_queued),
            "sending" to context.getString(R.string.status_sending),
            "sentToMesh" to context.getString(R.string.status_pending),
            "delivered" to context.getString(R.string.status_sent),
            "read" to context.getString(R.string.status_delivered),
            "failed" to context.getString(R.string.status_failed)
        )

        val duplicates = glyphs.entries
            .groupBy { it.value }
            .filterValues { it.size > 1 }
            .mapValues { (_, e) -> e.map { it.key } }

        assertTrue(
            "These states share a glyph and are indistinguishable to the user: $duplicates",
            duplicates.isEmpty()
        )
    }

    /** Partial delivery must show the real reach, not a bare tick that implies everyone got it. */
    @Test
    fun `partial delivery reports the actual counts`() {
        val partial = context.getString(R.string.status_partial, 3, 7)

        assertTrue("Partial delivery must show how many were reached: $partial", partial.contains("3"))
        assertTrue("Partial delivery must show the total: $partial", partial.contains("7"))
        assertTrue(
            "Partial delivery must not render as a bare delivered tick",
            partial != context.getString(R.string.status_sent)
        )
    }

    /** Glyphs alone are meaningless to a screen reader; every state needs a spoken label. */
    @Test
    fun `every state has a spoken description`() {
        val descriptions = listOf(
            context.getString(R.string.status_cd_queued),
            context.getString(R.string.status_cd_sending),
            context.getString(R.string.status_cd_sent_to_mesh),
            context.getString(R.string.status_cd_delivered, "Ama"),
            context.getString(R.string.status_cd_read, "Ama"),
            context.getString(R.string.status_cd_partial, 3, 7),
            context.getString(R.string.status_cd_failed, "no route")
        )

        descriptions.forEach { text ->
            assertTrue("Description must not be blank", text.isNotBlank())
            assertTrue("Description must be words, not a glyph: '$text'", text.length > 3)
        }
    }

    /**
     * The spoken label for a mesh handoff must not claim delivery -- that is the whole distinction
     * SentToMesh exists to preserve.
     */
    @Test
    fun `sent to mesh does not claim the recipient received it`() {
        val text = context.getString(R.string.status_cd_sent_to_mesh).lowercase()
        assertTrue("Should say it is unconfirmed: '$text'", text.contains("not yet confirmed"))
    }

    @Test
    fun `delivered and read name the person`() {
        assertEquals("Delivered to Ama", context.getString(R.string.status_cd_delivered, "Ama"))
        assertEquals("Read by Ama", context.getString(R.string.status_cd_read, "Ama"))
    }
}
