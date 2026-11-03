package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.272 -- the state sentence of the guest call view tells the truth about microphone and camera. */
class EventGuestStateLineTest {
    @Test
    fun connected_namesTheRealStateOfMicrophoneAndCamera() {
        assertEquals(
            "Sie sind in der Videokonferenz. Ihr Mikrofon ist aus. Ihre Kamera ist aus.",
            guestStateLine(GuestCallPhase.CONNECTED, micOn = false, cameraOn = false),
        )
        assertEquals(
            "Sie sind in der Videokonferenz. Ihr Mikrofon ist an. Ihre Kamera ist aus.",
            guestStateLine(GuestCallPhase.CONNECTED, micOn = true, cameraOn = false),
        )
        assertEquals(
            "Sie sind in der Videokonferenz. Ihr Mikrofon ist aus. Ihre Kamera ist an.",
            guestStateLine(GuestCallPhase.CONNECTED, micOn = false, cameraOn = true),
        )
        assertEquals(
            "Sie sind in der Videokonferenz. Ihr Mikrofon ist an. Ihre Kamera ist an.",
            guestStateLine(GuestCallPhase.CONNECTED, micOn = true, cameraOn = true),
        )
    }

    @Test
    fun theOtherPhases_doNotMentionMicrophoneOrCamera() {
        for (phase in GuestCallPhase.entries.filter { it != GuestCallPhase.CONNECTED }) {
            val text = guestStateLine(phase, micOn = true, cameraOn = true)
            assertTrue(text.isNotBlank(), "$phase")
            assertTrue(!text.contains("Mikrofon") && !text.contains("Kamera"), "$phase: $text")
        }
        assertEquals("Verbindung wird aufgebaut …", guestStateLine(GuestCallPhase.CONNECTING, false, false))
        assertEquals("Die Verbindung wird wiederhergestellt …", guestStateLine(GuestCallPhase.RECONNECTING, false, false))
        assertEquals("Die Verbindung wurde beendet.", guestStateLine(GuestCallPhase.ENDED, false, false))
    }

    @Test
    fun theElement_isAnAtomicPoliteStatus_andFollowsTheState(): Promise<Unit> =
        formTest {
            mountedForm("guest-state-line") { root, element ->
                val line = GuestStateLine(root)
                line.render(GuestCallPhase.CONNECTED, micOn = false, cameraOn = false)
                val el = assertNotNull(element().querySelector("[role=status]") as? HTMLElement)
                assertEquals("polite", el.getAttribute("aria-live"))
                assertEquals("true", el.getAttribute("aria-atomic"))
                assertTrue(el.classList.contains("fw-bold"))
                awaitUntil("text") { el.textContent.orEmpty().contains("Ihr Mikrofon ist aus.") }
                line.render(GuestCallPhase.CONNECTED, micOn = true, cameraOn = false)
                awaitUntil("changed") { el.textContent.orEmpty().contains("Ihr Mikrofon ist an.") }
                assertTrue(!el.textContent.orEmpty().contains("Ihr Mikrofon ist aus."))
            }
        }
}
