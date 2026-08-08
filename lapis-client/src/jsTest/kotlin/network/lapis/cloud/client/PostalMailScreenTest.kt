package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mail-merge/Postal-Dispatch UI wave (design decisions D8/D11) -- covers the pure, DOM-independent
 * [PostalDeliveryStatus] label/color functions factored out of `PostalMailScreen.kt`, same scope
 * posture as [MeetingsScreenTest]/[CommunicationScreenTest] (no DOM/rendering test harness exists
 * in this module).
 */
class PostalMailScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun postalDeliveryStatusLabel_isNonBlankForEveryValue() {
        PostalDeliveryStatus.entries.forEach { status ->
            assertTrue(postalDeliveryStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun postalDeliveryStatusColor_isARealBootstrapHueForEveryValue() {
        PostalDeliveryStatus.entries.forEach { status ->
            val color = postalDeliveryStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun postalDeliveryStatusLabel_sentIsVersendet() {
        assertEquals("Versendet", postalDeliveryStatusLabel(PostalDeliveryStatus.SENT))
    }

    @Test
    fun postalDeliveryStatusColor_failedIsDanger() {
        assertEquals("danger", postalDeliveryStatusColor(PostalDeliveryStatus.FAILED))
    }

    // D11: QUEUED is dead today (PostalMailService.dispatchAndLog only ever writes SENT/FAILED
    // synchronously) but must stay a real, documented, non-blank branch, not silently omitted.
    @Test
    fun postalDeliveryStatusLabel_queuedIsDocumentedNotOmitted() {
        assertEquals("In Bearbeitung", postalDeliveryStatusLabel(PostalDeliveryStatus.QUEUED))
        assertEquals("secondary", postalDeliveryStatusColor(PostalDeliveryStatus.QUEUED))
    }
}
