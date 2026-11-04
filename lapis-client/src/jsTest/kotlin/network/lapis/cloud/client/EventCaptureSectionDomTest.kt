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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun theBoxAppearsOnlyForAConferenceWithTheInstanceSwitchOn_andIsTickedByDefaultForANewEvent(): Promise<Unit> =
        formTest {
            withSection(
                prefill = null,
                data = EventCaptureFormData(instanceEnabled = true, lock = EventCaptureChangeLock.NONE),
            ) { _, online, capture, page ->
                awaitAppScopeIdle("the section is loaded")
                // a new event starts as NONE ("Vor Ort"): the box is not in the picture, and there is no choice to pass on
                assertFalse(isVisible(capture))
                assertNull(capture.createValue())
                online.kindRadio.value = EventOnlineKind.LAPIS_CONFERENCE.name
                awaitUntil("the box is shown") { isVisible(capture) }
                assertTrue(page.text().contains("Aufzeichnung und Übertragung erlauben"))
                // V1.9.280: ticked by default; the release travels WITH the new event, so saving calls nothing afterwards
                assertTrue(page.box()!!.checked)
                assertTrue(capture.wanted())
                // the untouched default tick is no explicit choice: it travels as "no choice", so the audit records source = default
                assertNull(capture.createValue())
                assertFalse(capture.changed())
                // the person's own choice wins and stays
                page.box()!!.click()
                assertFalse(page.box()!!.checked)
                assertEquals(false, capture.createValue())
                // ticking it again is an explicit "yes"
                page.box()!!.click()
                assertTrue(page.box()!!.checked)
                assertEquals(true, capture.createValue())
                page.box()!!.click()
                assertFalse(page.box()!!.checked)
                online.kindRadio.value = EventOnlineKind.NONE.name
                awaitUntil("the box is gone again") { !isVisible(capture) }
                assertNull(capture.createValue())
                online.kindRadio.value = EventOnlineKind.LAPIS_CONFERENCE.name
                awaitUntil("the box is back") { isVisible(capture) }
                // touched: the default is not applied again over the person's "no"
                assertFalse(page.box()!!.checked)
                assertEquals(false, capture.createValue())
            }
        }

    @Test
    fun withTheInstanceSwitchOff_aNewEventsBoxIsNotTicked_andPassesNoChoice(): Promise<Unit> =
        formTest {
            val off = EventCaptureFormData(instanceEnabled = false, lock = EventCaptureChangeLock.INSTANCE_OFF)
            withSection(prefill = null, data = off) { _, online, capture, page ->
                awaitAppScopeIdle("loaded")
                online.kindRadio.value = EventOnlineKind.LAPIS_CONFERENCE.name
                awaitAppScopeIdle("kind switched")
                // the box is hidden while the instance switch is off for a new event: nothing is ticked, nothing is passed on
                assertFalse(isVisible(capture))
                assertNull(capture.createValue())
                assertFalse(page.box()?.checked == true)
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
    fun aRunningRecording_locksSwitchingOff_withTheReasonInWords_andTheOldFreezeTextIsGone(): Promise<Unit> =
        formTest {
            val running = EventCaptureFormData(instanceEnabled = true, lock = EventCaptureChangeLock.CAPTURE_RUNNING, roomOpen = true)
            withSection(prefill = event(released = true), data = running) { _, _, capture, page ->
                awaitUntil("the locked box") { isVisible(capture) && page.box()?.disabled == true }
                assertTrue(page.text().contains("Ausschalten ist erst möglich, wenn Aufzeichnung und Übertragung beendet sind."))
                assertFalse(page.text().contains("Die Freigabe kann nicht mehr geändert werden"))
                val box = page.box()!!
                assertTrue(box.checked)
                val reason = assertNotNull(page.querySelector("[id='${box.getAttribute("aria-describedby")}']") as? HTMLElement)
                assertTrue(reason.text().contains("Ausschalten ist erst möglich"))
                // a locked box cannot be changed by a click, and saving the form changes nothing
                box.click()
                assertTrue(capture.wanted())
                assertFalse(capture.changed())
                // the explanation stays visible next to the reason
                assertTrue(page.text().contains("Dann können Bild, Ton und Name aller Personen"))
            }
        }

    @Test
    fun anOpenRoom_doesNotLockTheBox_andAHintSaysThatPeopleInsideAreAsked(): Promise<Unit> =
        formTest {
            val started = EventCaptureFormData(instanceEnabled = true, lock = EventCaptureChangeLock.NONE, roomOpen = true)
            withSection(prefill = event(released = false), data = started) { _, _, capture, page ->
                awaitUntil("the box") { isVisible(capture) && page.box() != null }
                val box = page.box()!!
                assertFalse(box.disabled)
                val hint = "Die Veranstaltung hat begonnen. Wer schon im Raum ist und noch nicht zugestimmt hat, wird sofort gefragt."
                assertTrue(page.text().contains(hint))
                // the hint is part of what describes the box (a screen reader reads it with the explanation)
                val described = box.getAttribute("aria-describedby").orEmpty().split(' ')
                assertTrue(described.size == 2)
                assertTrue(described.all { id -> page.querySelector("[id='$id']") != null })
                // ticking the box answers the question: the hint goes, the change is wanted
                box.click()
                assertTrue(capture.wanted())
                assertTrue(capture.changed())
                assertFalse(page.text().contains(hint))
            }
        }

    @Test
    fun beforeTheStart_noHint_andTheExplanationIsAlwaysVisible(): Promise<Unit> =
        formTest {
            val before = EventCaptureFormData(instanceEnabled = true, lock = EventCaptureChangeLock.NONE, roomOpen = false)
            withSection(prefill = event(released = false), data = before) { _, _, capture, page ->
                awaitUntil("the box") { isVisible(capture) && page.box() != null }
                assertFalse(page.text().contains("Die Veranstaltung hat begonnen."))
                assertTrue(page.text().contains("Dann können Bild, Ton und Name aller Personen"))
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
