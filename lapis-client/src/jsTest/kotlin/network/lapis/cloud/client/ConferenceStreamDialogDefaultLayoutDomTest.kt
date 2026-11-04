package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.9.262 -- the stream dialog is pre-filled with the layout of the room's view (which a new room takes from the organisation default),
 * and the person can still pick any layout, "Einzelne Person" included.
 */
class ConferenceStreamDialogDefaultLayoutDomTest {
    @Test
    fun theDialog_preSelectsTheSpeakerLayout_andStillOffersTheSinglePerson(): Promise<Unit> =
        formTest {
            mountedForm("stream-dialog-default-layout") { _, _ ->
                startStreamDialog(
                    targets = emptyList(),
                    maxDestinations = 3,
                    participantOptions = listOf("p1" to "Anna"),
                    defaultLayout = ConferenceStreamLayout.SPEAKER,
                ) { _, _, _, _, _ -> }
                awaitUntil("the dialog is shown") { document.querySelector(".modal.show") != null }
                val select = lastOpenModal().allOf("select").first()
                val values = select.querySelectorAll("option")
                val offered = (0 until values.length).map { (values.item(it) as HTMLElement).getAttribute("value") }
                assertTrue("SINGLE_PARTICIPANT" in offered, "Einzelne Person is offered: $offered")
                assertEquals("SPEAKER", (select as HTMLSelectElement).value)
            }
        }
}
