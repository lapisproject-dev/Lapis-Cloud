package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.VatSettingsDto
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IVatService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W4d (this batch): the Kleinunternehmer-Regelung checkbox in `NonprofitComplianceReportsScreen.kt`'s
 * "USt-Voranmeldung -- Vorschau" tab ([renderVatGateSummary]) -- a raw, unlabelled-form IMMEDIATE SWITCH
 * (R24B_JUSTIFIED, same reason as `StatuteQaScreen.kt`'s consent checkbox, see
 * `ClientUiGuidelineTripwireTest.kt`), not a `lapisForm` field. Driven the way a person does (tick, wait
 * for the round trip) against a stubbed `window.fetch`, same idiom `FormGrammarPart3DomTest.kt` already
 * establishes. ADMIN role is required for [renderVatAdminGateSection] to render at all.
 */
class NonprofitComplianceReportsScreenFormDomTest {
    private val adminSession =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin-Testperson",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val settings =
        OrganizationSettingsDto(
            id = "org-1",
            name = "Verein Testverein e.V.",
            street = null,
            postalCode = null,
            city = null,
            country = null,
            bankIban = null,
            bankBic = null,
            taxExemptionAuthority = null,
            taxExemptionDate = null,
            isKleinunternehmer = false,
        )

    private val vatSettings = VatSettingsDto(vatEnabled = true, isKleinunternehmer = false)

    /** Every RPC answers `null`, except the ones [answers] names (same pattern as `FormGrammarPart3DomTest.kt`). */
    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private suspend fun switchToVatTab(element: () -> org.w3c.dom.HTMLElement) {
        element().buttonNamed("USt-Voranmeldung — Vorschau").click()
        awaitUntil("Kleinunternehmer checkbox rendered", timeoutMs = 800) {
            element().allOf("label").any {
                it.textContent
                    .orEmpty()
                    .trim()
                    .startsWith("Kleinunternehmer nach § 19 UStG")
            }
        }
    }

    @Test
    fun kleinunternehmerToggle_whenTicked_sendsTheFlagSetToTrueAndKeepsTheOrgUnchangedOtherwise(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val getVatSettingsRoute = routeOf { rpcService<IVatService>().getVatSettings() }
            val getOrgRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val updateOrgRoute =
                routeOf { rpcService<IOrganizationSettingsService>().updateOrganizationSettings(settings.toInput()) }
            withFetchStub(
                respond =
                    answering(
                        getVatSettingsRoute to jsonOf(VatSettingsDto.serializer(), vatSettings),
                        getOrgRoute to jsonOf(OrganizationSettingsDto.serializer(), settings),
                        updateOrgRoute to jsonOf(OrganizationSettingsDto.serializer(), settings.copy(isKleinunternehmer = true)),
                    ),
            ) { calls ->
                mountedForm("nonprofit-kleinunternehmer-happy") { root, element ->
                    renderNonprofitComplianceReportsScreen(root)
                    switchToVatTab(element)
                    element().tick("Kleinunternehmer nach § 19 UStG")
                    awaitUntil("updateOrganizationSettings", timeoutMs = 800) { calls.toRoute(updateOrgRoute).size == 1 }
                    val call = calls.singleCall(updateOrgRoute)
                    val input = call.rpcParam(0)
                    assertEquals(true, input.isKleinunternehmer as Boolean, "the flag is now set")
                    assertEquals("Verein Testverein e.V.", input.name as String, "every other field is forwarded unchanged")
                }
            }
        }

    @Test
    fun kleinunternehmerToggle_whenTheServerRejectsTheUpdate_revertsTheCheckboxToItsPriorValue(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val getVatSettingsRoute = routeOf { rpcService<IVatService>().getVatSettings() }
            val getOrgRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val updateOrgRoute =
                routeOf { rpcService<IOrganizationSettingsService>().updateOrganizationSettings(settings.toInput()) }
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == getVatSettingsRoute -> request.answerWith(jsonOf(VatSettingsDto.serializer(), vatSettings))
                        request.rpcRoute == getOrgRoute -> request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                        request.rpcRoute == updateOrgRoute -> StubResponse(status = 500)
                        else -> request.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("nonprofit-kleinunternehmer-failure") { root, element ->
                    renderNonprofitComplianceReportsScreen(root)
                    switchToVatTab(element)
                    element().tick("Kleinunternehmer nach § 19 UStG")
                    awaitUntil("updateOrganizationSettings attempted", timeoutMs = 800) { calls.toRoute(updateOrgRoute).size == 1 }
                    delay(150)
                    val checkbox = element().controlOf("Kleinunternehmer nach § 19 UStG") as org.w3c.dom.HTMLInputElement
                    assertFalse(checkbox.checked, "a rejected update reverts the checkbox to its prior (unchecked) value")
                    assertTrue(!checkbox.disabled, "the checkbox is usable again after the failed round trip")
                }
            }
        }
}
