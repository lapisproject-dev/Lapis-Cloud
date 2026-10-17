package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.156 -- the ADMIN setting of recording and live streaming in event rooms (the "Veranstaltungen" part of the "Videokonferenz" card): switching ON goes
 * through the operator notice (the four paragraphs, the version in the foot, the focus on "Abbrechen", Escape cancels without a call, "Einschalten" locked until
 * the legal-check box is ticked); switching OFF asks first; while the consent text is still the placeholder the switch is locked and says why, with a way to
 * the agreement texts; a stale acknowledgment is shown with a way to repeat it.
 */
class EventCaptureSettingsDomTest {
    private var settings = base()
    private var enableRoute = ""
    private var getRoute = ""

    private fun base(
        enabled: Boolean = false,
        acknowledged: String? = null,
        placeholder: Boolean = false,
    ) = OrganizationSettingsDto(
        id = "00000000-0000-0000-0000-0000000000f2",
        name = "Verein",
        street = null,
        postalCode = null,
        city = null,
        country = null,
        bankIban = null,
        bankBic = null,
        taxExemptionAuthority = null,
        taxExemptionDate = null,
        eventCaptureEnabled = enabled,
        eventCaptureNoticeVersion = acknowledged,
        eventCaptureTextIsPlaceholder = placeholder,
    )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private suspend fun withCard(
        dto: OrganizationSettingsDto,
        block: suspend (HTMLElement, List<RecordedRequest>) -> Unit,
    ) {
        settings = dto
        getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
        enableRoute = routeOf { rpcService<IOrganizationSettingsService>().updateEventCaptureEnabled(true, "1") }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    request.rpcRoute == enableRoute -> {
                        val on = request.rpcParam(0) as Boolean
                        settings =
                            settings.copy(
                                eventCaptureEnabled = on,
                                eventCaptureNoticeVersion = if (on) EVENT_CAPTURE_NOTICE_VERSION else settings.eventCaptureNoticeVersion,
                            )
                        request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    }
                    else -> StubResponse()
                }
            },
        ) { requests ->
            mountedForm("event-capture-settings") { root, element ->
                renderConferenceDefaultsCard(root)
                awaitUntil("the section is shown") {
                    element().text().contains("Aufzeichnung und Übertragung in Veranstaltungsräumen erlauben")
                }
                block(element(), requests)
            }
        }
    }

    private fun HTMLElement.captureSwitch(): HTMLElement =
        assertNotNull(
            allOf(
                "button[role=switch]",
            ).firstOrNull { it.getAttribute("aria-labelledby").orEmpty().startsWith("lapis-event-capture-label") },
            "no switch",
        )

    @Test
    fun switchingOn_goesThroughTheNotice_andOnlyAfterTheBoxIsTicked(): Promise<Unit> =
        formTest {
            withCard(base()) { element, requests ->
                val switch = element.captureSwitch()
                assertEquals("false", switch.getAttribute("aria-checked"))
                assertFalse((switch as HTMLButtonElement).disabled)
                switch.click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                val text = modal.text()
                listOf(
                    "Recht Ihres Landes",
                    "nicht nur der Vortragenden",
                    "Als Betreiber sind Sie dafür verantwortlich",
                    "Bildschirmfotos",
                    "vor dem Einschalten veröffentlicht werden",
                ).forEach { assertTrue(text.contains(it), it) }
                assertTrue(text.contains("Hinweis Version $EVENT_CAPTURE_NOTICE_VERSION"))
                val enable = modal.buttonNamed("Einschalten") as HTMLButtonElement
                assertTrue(enable.disabled, "locked until the legal check is ticked")
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                escape(modal)
                awaitUntil("Esc closes the dialog") { document.querySelector(".modal.show") == null }
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == enableRoute }, "Esc switched nothing on")

                switch.click()
                awaitUntil("the dialog again") { document.querySelector(".modal.show") != null }
                val again = lastOpenModal()
                again.allOf("input[type=checkbox]").single().click()
                awaitUntil("the button is unlocked") { !(again.buttonNamed("Einschalten") as HTMLButtonElement).disabled }
                again.buttonNamed("Einschalten").click()
                awaitUntil("the call") { requests.count { it.isRpc && it.rpcRoute == enableRoute } == 1 }
                val call = requests.single { it.isRpc && it.rpcRoute == enableRoute }
                assertEquals(true, call.rpcParam(0) as Boolean)
                assertEquals(EVENT_CAPTURE_NOTICE_VERSION, call.rpcParam(1) as String)
                awaitUntil("the card shows the new state") { element.captureSwitch().getAttribute("aria-checked") == "true" }
            }
        }

    @Test
    fun switchingOff_asksFirst_andCancellingChangesNothing(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = EVENT_CAPTURE_NOTICE_VERSION)) { element, requests ->
                element.captureSwitch().click()
                awaitUntil("the question") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("Aufzeichnung in Veranstaltungsräumen ausschalten?"))
                assertTrue(modal.text().contains("kann in keinem Veranstaltungsraum mehr aufgezeichnet oder übertragen werden"))
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                modal.buttonNamed("Abbrechen").click()
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == enableRoute })

                element.captureSwitch().click()
                awaitUntil("the question again") { document.querySelector(".modal.show") != null }
                lastOpenModal().buttonNamed("Ausschalten").click()
                awaitUntil("the call") { requests.count { it.isRpc && it.rpcRoute == enableRoute } == 1 }
                val call = requests.single { it.isRpc && it.rpcRoute == enableRoute }
                assertEquals(false, call.rpcParam(0) as Boolean)
                awaitUntil("the card shows the new state") { element.captureSwitch().getAttribute("aria-checked") == "false" }
            }
        }

    @Test
    fun whileTheConsentTextIsThePlaceholder_theSwitchIsLockedAndSaysWhy(): Promise<Unit> =
        formTest {
            withCard(base(placeholder = true)) { element, requests ->
                assertTrue((element.captureSwitch() as HTMLButtonElement).disabled)
                assertTrue(element.text().contains("Der Einwilligungstext für Veranstaltungen ist noch der Platzhalter."))
                val link =
                    assertNotNull(element.allOf("a").firstOrNull { it.text() == "Zu den Vertragstexten" }, "no link to the agreement texts")
                assertTrue(
                    link.getAttribute("href").orEmpty().endsWith("/agreement-texts") ||
                        link.getAttribute("href").orEmpty().contains("agreement"),
                )
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == enableRoute })
            }
        }

    @Test
    fun aRunningFeatureCanAlwaysBeSwitchedOff_evenIfTheTextIsPlaceholderAgain(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = EVENT_CAPTURE_NOTICE_VERSION, placeholder = true)) { element, _ ->
                assertFalse((element.captureSwitch() as HTMLButtonElement).disabled)
            }
        }

    @Test
    fun anOldAcknowledgment_isShownWithAWayToRepeatIt(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = "0")) { element, _ ->
                assertTrue(
                    element.text().contains(
                        "Bestätigt für Hinweis Version 0, aktuell Version $EVENT_CAPTURE_NOTICE_VERSION. Bitte erneut bestätigen.",
                    ),
                )
                // the pulpit section has the same button: the one of THIS section sits right after its message
                val again = element.allOf("button").filter { it.text().trim() == "Erneut bestätigen" }
                assertTrue(again.isNotEmpty())
                again.last().click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                assertTrue(lastOpenModal().text().contains("Aufzeichnung in Veranstaltungsräumen erlauben?"))
                escape(lastOpenModal())
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
            }
        }
}
