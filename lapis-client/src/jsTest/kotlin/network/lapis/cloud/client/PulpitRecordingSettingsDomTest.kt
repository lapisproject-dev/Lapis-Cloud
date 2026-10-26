package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PULPIT_RECORDING_NOTICE_VERSION
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
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
 * V1.9.143 -- the ADMIN setting of the pulpit recording in the "Videokonferenz" card: switching ON goes through the operator notice (the four paragraphs, the
 * version in the foot, the focus on "Abbrechen", Escape cancels without a call, "Einschalten" locked until the legal-check box is ticked), switching OFF is
 * one click, a stale acknowledgment is shown with a way to repeat it, and without a recording service on the server the switch is locked and says why.
 */
class PulpitRecordingSettingsDomTest {
    private var settings = base()
    private var enableRoute = ""
    private var getRoute = ""
    private var confirmRoute = ""

    private fun base(
        enabled: Boolean = false,
        acknowledged: String? = null,
        infrastructure: Boolean = true,
        requireConfirmation: Boolean = true,
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
        pulpitRecordingEnabled = enabled,
        pulpitRecordingAcknowledgedNoticeVersion = acknowledged,
        pulpitRecordingRequireConfirmation = requireConfirmation,
        pulpitRecordingInfrastructureAvailable = infrastructure,
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
        enableRoute = routeOf { rpcService<IOrganizationSettingsService>().updatePulpitRecordingEnabled(true, "1") }
        confirmRoute = routeOf { rpcService<IOrganizationSettingsService>().updatePulpitRecordingRequireConfirmation(true) }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    request.rpcRoute == enableRoute -> {
                        val on = request.rpcParam(0) as Boolean
                        settings =
                            settings.copy(
                                pulpitRecordingEnabled = on,
                                pulpitRecordingAcknowledgedNoticeVersion =
                                    if (on) PULPIT_RECORDING_NOTICE_VERSION else settings.pulpitRecordingAcknowledgedNoticeVersion,
                            )
                        request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    }
                    request.rpcRoute == confirmRoute -> {
                        settings = settings.copy(pulpitRecordingRequireConfirmation = request.rpcParam(0) as Boolean)
                        request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    }
                    else -> StubResponse()
                }
            },
        ) { requests ->
            mountedForm("pulpit-settings") { root, element ->
                renderConferenceRecordingSettings(root)
                awaitUntil("the section is shown") { element().text().contains("Aufzeichnung von Kanzel bzw. Podium erlauben") }
                block(element(), requests)
            }
        }
    }

    private fun HTMLElement.pulpitSwitch(): HTMLElement =
        assertNotNull(
            allOf(
                "button[role=switch]",
            ).firstOrNull { it.getAttribute("aria-labelledby").orEmpty().startsWith("lapis-pulpit-recording-label") },
            "no switch",
        )

    @Test
    fun switchingOn_goesThroughTheNotice_andOnlyAfterTheBoxIsTicked(): Promise<Unit> =
        formTest {
            withCard(base()) { element, requests ->
                val switch = element.pulpitSwitch()
                assertEquals("false", switch.getAttribute("aria-checked"))
                switch.click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                val text = modal.text()
                listOf(
                    "Recht Ihres Landes",
                    "Als Betreiber sind Sie dafür verantwortlich",
                    "Das kann die Software nicht verhindern.",
                ).forEach {
                    assertTrue(text.contains(it), it)
                }
                assertTrue(text.contains("Hinweis Version $PULPIT_RECORDING_NOTICE_VERSION"))
                val enable = modal.buttonNamed("Einschalten") as org.w3c.dom.HTMLButtonElement
                assertTrue(enable.disabled, "locked until the legal check is ticked")
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                escape(modal)
                awaitUntil("Esc closes the dialog") { document.querySelector(".modal.show") == null }
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == enableRoute }, "Esc switched nothing on")

                switch.click()
                awaitUntil("the dialog again") { document.querySelector(".modal.show") != null }
                val again = lastOpenModal()
                (again.allOf("input[type=checkbox]").single()).click()
                awaitUntil("the button is unlocked") { !(again.buttonNamed("Einschalten") as org.w3c.dom.HTMLButtonElement).disabled }
                again.buttonNamed("Einschalten").click()
                awaitUntil("the call") { requests.count { it.isRpc && it.rpcRoute == enableRoute } == 1 }
                val call = requests.single { it.isRpc && it.rpcRoute == enableRoute }
                assertEquals(true, call.rpcParam(0) as Boolean)
                assertEquals(PULPIT_RECORDING_NOTICE_VERSION, call.rpcParam(1) as String)
                awaitUntil("the card shows the new state") { element.pulpitSwitch().getAttribute("aria-checked") == "true" }
            }
        }

    @Test
    fun switchingOff_isOneClickWithoutADialog_andTheSecondSwitchOnlyExistsWhileItIsOn(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = PULPIT_RECORDING_NOTICE_VERSION)) { element, requests ->
                assertTrue(element.text().contains("Zustimmung der Kanzel vor jeder Aufnahme verlangen"))
                element.pulpitSwitch().click()
                awaitUntil("the call") { requests.count { it.isRpc && it.rpcRoute == enableRoute } == 1 }
                assertEquals(false, requests.single { it.isRpc && it.rpcRoute == enableRoute }.rpcParam(0) as Boolean)
                assertEquals(null, document.querySelector(".modal.show"), "no dialog for the safe direction")
                awaitUntil("the second switch is gone") { !element.text().contains("Zustimmung der Kanzel vor jeder Aufnahme verlangen") }
            }
        }

    @Test
    fun anOldAcknowledgment_isShownWithAWayToRepeatIt(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = "0")) { element, _ ->
                assertTrue(
                    element.text().contains(
                        "Bestätigt für Hinweis Version 0, aktuell Version $PULPIT_RECORDING_NOTICE_VERSION. Bitte erneut bestätigen.",
                    ),
                )
                element.buttonNamed("Erneut bestätigen").click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                assertTrue(lastOpenModal().text().contains("Hinweis Version $PULPIT_RECORDING_NOTICE_VERSION"))
                escape(lastOpenModal())
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
            }
        }

    @Test
    fun withoutARecordingService_theSwitchIsLockedAndSaysWhy(): Promise<Unit> =
        formTest {
            withCard(base(infrastructure = false)) { element, _ ->
                assertTrue((element.pulpitSwitch() as org.w3c.dom.HTMLButtonElement).disabled)
                assertTrue(element.text().contains("Der Aufzeichnungsdienst ist auf diesem Server nicht eingerichtet."))
                assertFalse(element.text().contains("Nur die Kamera und das Mikrofon"))
            }
        }
}
