package network.lapis.cloud.client

import kotlinx.browser.document
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AgreementTextKindOverviewDto
import network.lapis.cloud.shared.domain.AgreementTextOverviewDto
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.IAgreementTextAdminService
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
 * V1.9.145 -- the ADMIN setting of the member finder in the "Videokonferenz" card: switching ON goes through the operator notice (four paragraphs, the
 * version in the foot, the focus on "Abbrechen", Escape cancels without a call, "Einschalten" locked until the box is ticked); switching OFF asks first
 * (it deletes data), with the focus on "Abbrechen"; a stale acknowledgment can be repeated; a placeholder consent text is warned about.
 */
class MemberFinderSettingsDomTest {
    private var settings = base()
    private var placeholder = false

    private fun base(
        enabled: Boolean = false,
        acknowledged: String? = null,
    ) = OrganizationSettingsDto(
        id = "00000000-0000-0000-0000-0000000000f3",
        name = "Verein",
        street = null,
        postalCode = null,
        city = null,
        country = null,
        bankIban = null,
        bankBic = null,
        taxExemptionAuthority = null,
        taxExemptionDate = null,
        memberFinderEnabled = enabled,
        memberFinderAcknowledgedNoticeVersion = acknowledged,
    )

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private fun escape(target: HTMLElement) {
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)))
    }

    private fun overview() =
        AgreementTextOverviewDto(
            kinds =
                AgreementTextKind.entries.map {
                    AgreementTextKindOverviewDto(
                        kind = it,
                        placeholderActive = it == AgreementTextKind.FINDER && placeholder,
                        current = null,
                        builtinVersionLabel = "b1",
                        hasDraft = false,
                        olderAcknowledgmentCount = null,
                    )
                },
        )

    private suspend fun withCard(
        dto: OrganizationSettingsDto,
        placeholderActive: Boolean = false,
        block: suspend (HTMLElement, List<RecordedRequest>, String) -> Unit,
    ) {
        settings = dto
        placeholder = placeholderActive
        val getRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
        val switchRoute = routeOf { rpcService<IOrganizationSettingsService>().updateMemberFinderEnabled(true, "1") }
        val overviewRoute = routeOf { rpcService<IAgreementTextAdminService>().getOverview() }
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == getRoute -> request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    request.rpcRoute == overviewRoute -> request.answerWith(jsonOf(AgreementTextOverviewDto.serializer(), overview()))
                    request.rpcRoute == switchRoute -> {
                        val on = request.rpcParam(0) as Boolean
                        settings =
                            settings.copy(
                                memberFinderEnabled = on,
                                memberFinderAcknowledgedNoticeVersion =
                                    if (on) MEMBER_FINDER_NOTICE_VERSION else settings.memberFinderAcknowledgedNoticeVersion,
                            )
                        request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    }
                    else -> StubResponse()
                }
            },
        ) { requests ->
            mountedForm("member-finder-settings") { root, element ->
                renderConferenceDefaultsCard(root)
                awaitUntil("the section is shown") { element().text().contains("Mitglieder-Finder erlauben") }
                block(element(), requests, switchRoute)
            }
        }
    }

    private fun HTMLElement.finderSwitch(): HTMLElement =
        assertNotNull(
            allOf(
                "button[role=switch]",
            ).firstOrNull { it.getAttribute("aria-labelledby").orEmpty().startsWith("lapis-member-finder-label") },
            "no switch",
        )

    @Test
    fun switchingOn_goesThroughTheNotice_andOnlyAfterTheBoxIsTicked(): Promise<Unit> =
        formTest {
            withCard(base()) { element, requests, route ->
                val switch = element.finderSwitch()
                assertEquals("false", switch.getAttribute("aria-checked"))
                switch.click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                val text = modal.text()
                MEMBER_FINDER_NOTICE_PARAGRAPHS
                    .take(
                        3,
                    ).map { resolvedAttributeText(it) }
                    .forEach { assertTrue(text.contains(it), it.take(40)) }
                // the fourth paragraph is the label of the box and stands there once
                assertEquals(1, Regex(Regex.escape(resolvedAttributeText(MEMBER_FINDER_NOTICE_PARAGRAPHS[3]))).findAll(text).count())
                assertTrue(text.contains("Gut zu wissen: Wenn Sie den Mitglieder-Finder später abschalten"))
                assertTrue(text.contains("Hinweis Version $MEMBER_FINDER_NOTICE_VERSION"))
                val enable = modal.buttonNamed("Einschalten") as HTMLButtonElement
                assertTrue(enable.disabled, "locked until the legal check is ticked")
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                escape(modal)
                awaitUntil("Esc closes the dialog") { document.querySelector(".modal.show") == null }
                assertEquals(0, requests.toRoute(route).size, "Esc switched nothing on")

                switch.click()
                awaitUntil("the dialog again") { document.querySelector(".modal.show") != null }
                val again = lastOpenModal()
                again.allOf("input[type=checkbox]").single().click()
                awaitUntil("unlocked") { !(again.buttonNamed("Einschalten") as HTMLButtonElement).disabled }
                again.buttonNamed("Einschalten").click()
                awaitUntil("the call") { requests.toRoute(route).size == 1 }
                val call = requests.toRoute(route).single()
                assertEquals(true, call.rpcParam(0) as Boolean)
                assertEquals(MEMBER_FINDER_NOTICE_VERSION, call.rpcParam(1) as String)
                awaitUntil("the card shows the new state") { element.finderSwitch().getAttribute("aria-checked") == "true" }
            }
        }

    @Test
    fun switchingOff_asksFirst_theFocusIsOnCancel_andEscapeChangesNothing(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = MEMBER_FINDER_NOTICE_VERSION)) { element, requests, route ->
                element.finderSwitch().click()
                awaitUntil("the question") { document.querySelector(".modal.show") != null }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("alle Anfragen und Blockierungen gelöscht und alle Einwilligungen beendet"))
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                escape(modal)
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
                assertEquals(0, requests.toRoute(route).size)

                element.finderSwitch().click()
                awaitUntil("the question again") { document.querySelector(".modal.show") != null }
                lastOpenModal().buttonNamed("Abschalten").click()
                awaitUntil("the call") { requests.toRoute(route).size == 1 }
                val call = requests.toRoute(route).single()
                assertEquals(false, call.rpcParam(0) as Boolean)
                awaitUntil("the switch is off") { element.finderSwitch().getAttribute("aria-checked") == "false" }
            }
        }

    @Test
    fun anOldAcknowledgment_isShownWithAWayToRepeatIt(): Promise<Unit> =
        formTest {
            withCard(base(enabled = true, acknowledged = "0")) { element, _, _ ->
                assertTrue(
                    element.text().contains(
                        "Bestätigt für Hinweis Version 0, aktuell Version $MEMBER_FINDER_NOTICE_VERSION. Bitte erneut bestätigen.",
                    ),
                )
                element
                    .allOf("button")
                    .filter { it.text().trim() == "Erneut bestätigen" }
                    .last()
                    .click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                assertTrue(lastOpenModal().text().contains("Mitglieder-Finder erlauben?"))
                escape(lastOpenModal())
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
            }
        }

    @Test
    fun aPlaceholderConsentText_isWarnedAbout_inTheSectionAndInTheDialog(): Promise<Unit> =
        formTest {
            withCard(base(), placeholderActive = true) { element, _, _ ->
                awaitUntil(
                    "the warning",
                ) { element.text().contains("Platzhaltertext aktiv: Ersetzen Sie den eingebauten Einwilligungstext") }
                val link = assertNotNull(element.querySelector("a[href='#${Routes.AGREEMENT_TEXTS}']"))
                assertTrue(link.textContent.orEmpty().contains("Zu den Vertragstexten"))
                element.finderSwitch().click()
                awaitUntil("the dialog") { document.querySelector(".modal.show") != null }
                assertTrue(lastOpenModal().text().contains("Platzhaltertext aktiv"))
                escape(lastOpenModal())
                awaitUntil("closed") { document.querySelector(".modal.show") == null }
            }
            withCard(base(), placeholderActive = false) { element, _, _ ->
                awaitAppScopeIdle("the overview is loaded")
                assertFalse(element.text().contains("Platzhaltertext aktiv"))
            }
        }
}
