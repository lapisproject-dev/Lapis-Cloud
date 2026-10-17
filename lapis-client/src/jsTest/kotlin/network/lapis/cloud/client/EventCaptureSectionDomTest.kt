package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventCaptureChangeLock
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun HTMLElement.text(): String = textContent.orEmpty()

/**
 * V1.9.156 -- the release of recording and live streaming in the event form: ONE box, only for a Lapis conference and only while the instance switch is on
 * (an event that is still released shows the box locked), a locked box says WHY in words and that text is what describes the box, and a locked or hidden box
 * wants what the event already has (so saving the form can never change a release by accident).
 */
class EventCaptureSectionDomTest {
    private fun event(
        released: Boolean,
        kind: EventOnlineKind = EventOnlineKind.LAPIS_CONFERENCE,
    ) = EventDto(
        id = "00000000-0000-0000-0000-0000000000aa",
        slug = "e",
        title = "E",
        description = "d",
        locationText = "Saal",
        onlineUrl = null,
        startsAt = LocalDateTime(2030, 6, 15, 18, 0),
        endsAt = LocalDateTime(2030, 6, 15, 20, 0),
        capacity = null,
        feeAmount = 0.0.toDecimal(),
        feeCurrency = "EUR",
        status = EventStatus.PUBLISHED,
        visibility = EventVisibility.PUBLIC,
        registrationClosesAt = null,
        occupiedSeats = 0,
        waitlistCount = 0,
        full = false,
        feeEditable = true,
        ownRegistrationStatus = null,
        publicUrl = null,
        onlineKind = kind,
        captureAllowed = released,
    )

    private suspend fun withSection(
        prefill: EventDto?,
        data: EventCaptureFormData?,
        block: suspend (SimplePanel, EventOnlineKindRefs, EventCaptureRefs, HTMLElement) -> Unit,
    ) {
        mountedForm("event-capture-section") { root, element ->
            val panel = root.vPanel(spacing = 4)
            val online =
                buildEventOnlineKindSection(
                    panel = panel,
                    prefill = prefill,
                    context =
                        EventOnlineFormContext(
                            visibility = { EventVisibility.PUBLIC },
                            hasRegistrationDeadline = { false },
                            capacity = { null },
                        ),
                    loader =
                        EventOnlineFormLoader {
                            EventOnlineFormData(
                                spaces = emptyList(),
                                conferencingEnabled = true,
                                eventMaxParticipants = 100,
                            )
                        },
                )
            val capture =
                buildEventCaptureSection(panel = panel, prefill = prefill, online = online, loader = EventCaptureFormLoader { data })
            block(panel, online, capture, element())
        }
    }

    private fun HTMLElement.box(): HTMLInputElement? =
        allOf("input[type=checkbox]").map { it as HTMLInputElement }.firstOrNull {
            it.getAttribute("aria-describedby").orEmpty().startsWith("lapis-event-capture")
        }

    @Test
    fun theBoxAppearsOnlyForAConferenceWithTheInstanceSwitchOn(): Promise<Unit> =
        formTest {
            withSection(
                prefill = null,
                data = EventCaptureFormData(instanceEnabled = true, lock = EventCaptureChangeLock.NONE),
            ) { _, online, capture, page ->
                awaitAppScopeIdle("the section is loaded")
                // a new event starts as NONE ("Vor Ort"): the box is not in the picture
                assertTrue(page.text().contains("Aufzeichnung und Übertragung erlauben").not() || !isVisible(capture))
                online.kindRadio.value = EventOnlineKind.LAPIS_CONFERENCE.name
                awaitUntil("the box is shown") { isVisible(capture) }
                assertTrue(page.text().contains("Aufzeichnung und Übertragung erlauben"))
                assertFalse(capture.wanted())
                assertFalse(capture.changed())
                page.box()!!.click()
                assertTrue(capture.wanted())
                assertTrue(capture.changed())
                online.kindRadio.value = EventOnlineKind.NONE.name
                awaitUntil("the box is gone again") { !isVisible(capture) }
                // a hidden box wants what the event has: an unreleased event stays unreleased
                assertFalse(capture.wanted())
            }
        }

    @Test
    fun withTheInstanceSwitchOff_anUnreleasedEventShowsNoBox_aReleasedOneShowsItLockedWithTheReason(): Promise<Unit> =
        formTest {
            val off = EventCaptureFormData(instanceEnabled = false, lock = EventCaptureChangeLock.INSTANCE_OFF)
            withSection(prefill = event(released = false), data = off) { _, _, capture, _ ->
                awaitAppScopeIdle("loaded")
                assertFalse(isVisible(capture))
            }
            withSection(prefill = event(released = true), data = off) { _, _, capture, page ->
                awaitAppScopeIdle("loaded")
                awaitUntil("the locked box") { isVisible(capture) }
                val box = assertNotNull(page.box())
                assertTrue(box.disabled)
                assertTrue(page.text().contains("Die Aufzeichnung in Veranstaltungsräumen ist auf dieser Instanz nicht eingeschaltet."))
                // the reason is what describes the box
                val reason = assertNotNull(page.querySelector("#${box.getAttribute("aria-describedby")}") as? HTMLElement)
                assertTrue(reason.text().contains("nicht eingeschaltet"))
                // the stored release is what it wants (saving the form changes nothing)
                assertTrue(capture.wanted())
                assertFalse(capture.changed())
            }
        }

    @Test
    fun anActiveRoom_locksTheBox_withTheReasonInWords(): Promise<Unit> =
        formTest {
            val active = EventCaptureFormData(instanceEnabled = true, lock = EventCaptureChangeLock.ROOM_ACTIVE)
            withSection(prefill = event(released = false), data = active) { _, _, capture, page ->
                awaitUntil("the locked box") { isVisible(capture) && page.box()?.disabled == true }
                assertTrue(page.text().contains("Die Freigabe kann nicht mehr geändert werden"))
                val box = page.box()!!
                val reason = assertNotNull(page.querySelector("[id='${box.getAttribute("aria-describedby")}']") as? HTMLElement)
                assertTrue(reason.text().contains("nicht mehr geändert"))
                // a locked box cannot be changed by a click
                box.click()
                assertFalse(capture.wanted())
                assertFalse(capture.changed())
            }
        }

    @Test
    fun beforeTheServerAnswered_theBoxStaysHidden(): Promise<Unit> =
        formTest {
            withSection(prefill = event(released = false), data = null) { _, _, capture, _ ->
                awaitAppScopeIdle("the load is over")
                assertFalse(isVisible(capture))
            }
        }

    private fun isVisible(refs: EventCaptureRefs): Boolean = refs.box.visible
}
