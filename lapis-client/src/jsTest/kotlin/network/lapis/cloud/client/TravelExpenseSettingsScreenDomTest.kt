package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Welle V1.9.122 -- the ADMIN settings page, driven the way a person does against a stubbed `window.fetch`. */
class TravelExpenseSettingsScreenDomTest {
    private val adminSession =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private val rates =
        TravelExpenseRatesDto(
            mileageRatePerKm = 0.3.toDecimal(),
            rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30),
            contact = TravelExpenseContactDto(name = "Erika"),
            canEditSettings = true,
        )

    private val accounts = listOf(LedgerAccountDto("acc-1", "1500", "Vorschüsse", 1, LedgerAccountType.ASSET, active = true))

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    @Test
    fun theProvisionalNotice_isAlwaysShown_andTheTemplateButtonOnlyFillsTheForm(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val accountsRoute = routeOf { rpcService<IAccountingService>().listLedgerAccounts(true) }
            val saveRoute =
                routeOf {
                    rpcService<ITravelExpenseService>().updateTravelExpenseSettings(
                        TravelExpenseSettingsInput(emptyList(), emptyList(), 0, 0, 0, null, 0),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        accountsRoute to jsonOf(ListSerializer(LedgerAccountDto.serializer()), accounts),
                        saveRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                    ),
            ) { calls ->
                mountedForm("te-settings-template") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil("settings rendered", timeoutMs = 800) {
                        element().allOf("button").any { it.textContent?.trim() == "Standardwerte der PdV-Vorlage übernehmen" }
                    }
                    delay(80)
                    assertTrue(element().textContent.orEmpty().contains("vorläufig"), "the fixed provisional notice")
                    assertTrue(element().textContent.orEmpty().contains("nicht den gesetzlichen Pauschalen (14 € / 28 €)"))

                    element().typeInto("Stufe 3: Betrag (EUR)", "30,00")
                    element().typeInto("Frühstück (% von Stufe 3)", "25")
                    element().buttonNamed("Standardwerte der PdV-Vorlage übernehmen").click()
                    delay(60)
                    assertEquals(
                        "28,00",
                        (element().controlOf("Stufe 3: Betrag (EUR)") as HTMLInputElement).value,
                        "the template is filled in",
                    )
                    assertEquals("20", (element().controlOf("Frühstück (% von Stufe 3)") as HTMLInputElement).value)
                    assertEquals(0, calls.toRoute(saveRoute).size, "nothing is saved by the template button")
                }
            }
        }

    @Test
    fun save_sendsWholeCents_andTheContactTrimmed_orRefusesAnInvalidTierOrder(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession)
            val ratesRoute = routeOf { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val accountsRoute = routeOf { rpcService<IAccountingService>().listLedgerAccounts(true) }
            val saveRoute =
                routeOf {
                    rpcService<ITravelExpenseService>().updateTravelExpenseSettings(
                        TravelExpenseSettingsInput(emptyList(), emptyList(), 0, 0, 0, null, 0),
                    )
                }
            withFetchStub(
                respond =
                    answering(
                        ratesRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                        accountsRoute to jsonOf(ListSerializer(LedgerAccountDto.serializer()), accounts),
                        saveRoute to jsonOf(TravelExpenseRatesDto.serializer(), rates),
                    ),
            ) { calls ->
                mountedForm("te-settings-save") { root, element ->
                    renderTravelExpenseSettingsScreen(root)
                    awaitUntil(
                        "settings rendered",
                        timeoutMs = 800,
                    ) { element().allOf("button").any { it.textContent?.trim() == "Speichern" } }
                    delay(80)

                    // tiers must rise: 14 h before 8 h is refused on the field, nothing is sent
                    element().typeInto("Stufe 1: ab (Stunden)", "15")
                    element().buttonNamed("Speichern").click()
                    delay(80)
                    assertEquals(0, calls.toRoute(saveRoute).size)
                    assertTrue(element().shownErrors().isNotEmpty())

                    element().typeInto("Stufe 1: ab (Stunden)", "6")
                    element().typeInto("Stufe 1: Betrag (EUR)", "5,50")
                    element().typeInto("Name", "  Erika Mustermann  ")
                    element().typeInto("E-Mail", "")
                    element().buttonNamed("Speichern").click()
                    awaitUntil("save", timeoutMs = 800) { calls.toRoute(saveRoute).size == 1 }
                    val input = calls.singleCall(saveRoute).rpcParam(0)
                    assertEquals(6, input.tierHours[0] as Int)
                    assertEquals(550.0, input.tierCents[0] as Double, "the tier amount is whole cents")
                    assertEquals(30.0, input.mileageCentsPerKm as Double)
                    assertEquals("Erika Mustermann", input.contact.name as String, "the contact name arrives trimmed")
                    assertEquals(null, input.contact.email, "a blank contact field is null")
                }
            }
        }
}
