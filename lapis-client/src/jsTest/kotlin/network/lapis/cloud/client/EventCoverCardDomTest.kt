package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventManageScope
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.252 -- the event cover card tells the admin the image is centre-cropped to 4:3, with and without a stored cover. */
class EventCoverCardDomTest {
    private val hint = "Wird mittig auf 4:3 zugeschnitten."

    private fun event(coverImageUrl: String?) =
        EventDto(
            id = "1",
            slug = "sommerfest",
            title = "Sommerfest",
            description = "Beschreibung",
            locationText = "Halle",
            onlineUrl = null,
            startsAt = LocalDateTime(2099, 11, 1, 18, 0),
            endsAt = LocalDateTime(2099, 11, 1, 20, 0),
            capacity = 10,
            feeAmount = 0.0.toDecimal(),
            feeCurrency = "EUR",
            status = EventStatus.PUBLISHED,
            visibility = EventVisibility.MEMBERS_ONLY,
            registrationClosesAt = null,
            occupiedSeats = 0,
            waitlistCount = 0,
            full = false,
            feeEditable = true,
            ownRegistrationStatus = null,
            publicUrl = null,
            manageScope = EventManageScope.FULL,
            coverImageUrl = coverImageUrl,
        )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    @Test
    fun withoutACover_theEmptyTextAndTheCropHintAreVisible(): Promise<Unit> =
        formTest {
            mountedForm("event-cover-card-empty") { root, element ->
                renderEventCoverCard(root, event(coverImageUrl = null))
                val text = element().text()
                assertTrue(text.contains("Kein Titelbild"), "the empty-state text is shown")
                assertTrue(text.contains(hint), "the crop hint is shown")
                assertEquals(0, element().allOf("img").size, "no image without a cover")
            }
        }

    @Test
    fun withACover_theImageAndTheCropHintAreVisible(): Promise<Unit> =
        formTest {
            mountedForm("event-cover-card-url") { root, element ->
                renderEventCoverCard(root, event(coverImageUrl = "https://example.org/veranstaltung/sommerfest/bild?v=abcd1234"))
                assertEquals(1, element().allOf("img").size, "the stored cover is previewed")
                assertTrue(element().text().contains(hint), "the crop hint stays next to the image")
            }
        }
}
