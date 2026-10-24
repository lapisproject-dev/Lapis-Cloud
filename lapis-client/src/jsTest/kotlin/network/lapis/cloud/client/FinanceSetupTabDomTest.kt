package network.lapis.cloud.client

import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.FinanceSetupStatusDto
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.StandardChartResultDto
import network.lapis.cloud.shared.domain.financeSetupStatusOf
import network.lapis.cloud.shared.domain.toSetupView
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.206: the setup tab in a real DOM -- statuses, filtered selects, safe defaults, the chart dialog and the role modes. */
class FinanceSetupTabDomTest {
    private fun acc(
        id: String,
        number: String,
        type: LedgerAccountType,
        active: Boolean = true,
        cash: Boolean = false,
    ) = LedgerAccountDto(id, number, "Konto $number", number.take(1).toInt(), type, active, null, cash)

    private val bank = acc("bank", "18000", LedgerAccountType.ASSET)
    private val cashRegister = acc("cash", "16000", LedgerAccountType.ASSET, cash = true)
    private val contribution = acc("contribution", "40000", LedgerAccountType.INCOME)
    private val donation = acc("donation", "40450", LedgerAccountType.INCOME)
    private val event = acc("event", "42010", LedgerAccountType.INCOME)
    private val fee = acc("fee", "68550", LedgerAccountType.EXPENSE)
    private val fullChart = listOf(bank, cashRegister, contribution, donation, event, fee)

    private fun settings(
        bank: String? = null,
        contribution: String? = null,
    ) = OrganizationSettingsDto(
        id = "org-1",
        name = "Testverein",
        street = null,
        postalCode = null,
        city = null,
        country = null,
        bankIban = null,
        bankBic = null,
        taxExemptionAuthority = null,
        taxExemptionDate = null,
        eventIncomeSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
        paymentBankAccountId = bank,
        contributionIncomeAccountId = contribution,
    )

    private fun statusOf(
        accounts: List<LedgerAccountDto>,
        s: OrganizationSettingsDto,
    ): FinanceSetupStatusDto =
        financeSetupStatusOf(
            accounts.map { it.toSetupView() },
            s.paymentBankAccountId,
            s.contributionIncomeAccountId,
            s.donationIncomeAccountId,
            s.eventIncomeAccountId,
            s.paymentFeeAccountId,
        )

    private suspend fun withSetup(
        id: String,
        access: FinanceSetupAccess,
        accounts: List<LedgerAccountDto>,
        settings: OrganizationSettingsDto = settings(),
        block: suspend (screen: HTMLElement, calls: List<RecordedRequest>, routes: Routes206) -> Unit,
    ) {
        val routes =
            Routes206(
                list = routeOf { rpcService<IAccountingService>().listLedgerAccounts(false) },
                settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() },
                status = routeOf { rpcService<IAccountingService>().getFinanceSetupStatus() },
                create = routeOf { rpcService<IAccountingService>().createStandardChart() },
                update = routeOf { rpcService<IOrganizationSettingsService>().updateOrganizationSettings(settings.toInput()) },
            )
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == routes.list -> request.answerWith(jsonOf(ListSerializer(LedgerAccountDto.serializer()), accounts))
                    request.rpcRoute == routes.settings -> request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    request.rpcRoute == routes.status ->
                        request.answerWith(jsonOf(FinanceSetupStatusDto.serializer(), statusOf(accounts, settings)))
                    request.rpcRoute == routes.create ->
                        request.answerWith(
                            jsonOf(StandardChartResultDto.serializer(), StandardChartResultDto(listOf("18000"), listOf("40000"))),
                        )
                    request.rpcRoute == routes.update -> request.answerWith(jsonOf(OrganizationSettingsDto.serializer(), settings))
                    else -> request.answerWith("[]")
                }
            },
        ) { calls ->
            mountedForm(id) { root, element ->
                renderFinanceSetupTab(root, access, null) {}
                awaitUntil("the six step cards") { element().allOf(".lapis-setup-step").size == 6 }
                block(element(), calls, routes)
            }
        }
    }

    private class Routes206(
        val list: String,
        val settings: String,
        val status: String,
        val create: String,
        val update: String,
    )

    private fun HTMLElement.card(slug: String) = assertNotNull(querySelector("#finance-setup-step-$slug") as? HTMLElement, "card $slug")

    private fun HTMLElement.selectOptions(): List<String> =
        (querySelector("select") as HTMLSelectElement).let { s ->
            (0 until s.options.length).map { (s.options.item(it) as org.w3c.dom.HTMLOptionElement).value }
        }

    private fun HTMLElement.chooseValue(value: String) {
        val select = querySelector("select") as HTMLSelectElement
        select.value = value
        select.dispatchEvent(Event("change"))
    }

    @Test
    fun sixCards_withStatusText_firstOpenIsAccentuated_andTheProgressIsCounted(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-cards", FinanceSetupAccess.MANAGE_ALL, fullChart, settings(bank = "bank")) { screen, _, _ ->
                assertEquals(
                    setOf("chart", "bank", "contribution", "donation", "event", "fee"),
                    screen
                        .allOf(".lapis-setup-step")
                        .map {
                            it.id.removePrefix("finance-setup-step-")
                        }.toSet(),
                )
                assertTrue(
                    screen
                        .card("chart")
                        .textContent
                        .orEmpty()
                        .contains("Erledigt"),
                )
                assertTrue(
                    screen
                        .card("bank")
                        .textContent
                        .orEmpty()
                        .contains("Erledigt"),
                )
                assertTrue(
                    screen
                        .card("contribution")
                        .textContent
                        .orEmpty()
                        .contains("Offen"),
                )
                assertTrue(
                    screen
                        .card("fee")
                        .textContent
                        .orEmpty()
                        .contains("Optional"),
                )
                assertEquals(listOf("finance-setup-step-contribution"), screen.allOf(".lapis-setup-step--next").map { it.id })
                assertTrue(screen.textContent.orEmpty().contains("2 von 5 Pflichtschritten erledigt"))
            }
        }

    @Test
    fun selects_offerNoWrongTypes_noCashRegister_noInactive(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            val inactive = acc("old", "18100", LedgerAccountType.ASSET, active = false)
            withSetup("fs-filter", FinanceSetupAccess.MANAGE_ALL, fullChart + inactive) { screen, _, _ ->
                assertEquals(listOf("", "bank"), screen.card("bank").selectOptions())
                assertEquals(listOf("", "contribution", "donation", "event"), screen.card("contribution").selectOptions())
                assertEquals(listOf("", "contribution", "donation", "event"), screen.card("event").selectOptions())
                assertEquals(listOf("", "fee"), screen.card("fee").selectOptions())
            }
        }

    @Test
    fun useStandard_setsTheDefault_marksNotSaved_andCallsNothing(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-standard", FinanceSetupAccess.MANAGE_ALL, fullChart) { screen, calls, routes ->
                val card = screen.card("bank")
                card.buttonNamed("Standard verwenden").click()
                awaitUntil("chosen") { (card.querySelector("select") as HTMLSelectElement).value == "bank" }
                assertTrue(card.textContent.orEmpty().contains("Nicht gespeichert"))
                assertTrue(calls.toRoute(routes.update).isEmpty(), "nothing is saved by choosing")
            }
        }

    @Test
    fun proposeAll_fillsOnlyEmptyFields(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup(
                "fs-propose",
                FinanceSetupAccess.MANAGE_ALL,
                fullChart,
                settings(contribution = "donation"),
            ) { screen, calls, routes ->
                screen.buttonNamed("Alle Standardkonten vorschlagen").click()
                awaitUntil("bank filled") { (screen.card("bank").querySelector("select") as HTMLSelectElement).value == "bank" }
                assertEquals("donation", (screen.card("contribution").querySelector("select") as HTMLSelectElement).value, "kept")
                assertEquals("event", (screen.card("event").querySelector("select") as HTMLSelectElement).value)
                assertTrue(calls.toRoute(routes.update).isEmpty())
            }
        }

    @Test
    fun missingStandardAccount_hidesTheButton_andSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-missing", FinanceSetupAccess.MANAGE_ALL, listOf(acc("other", "19000", LedgerAccountType.ASSET))) { screen, _, _ ->
                val card = screen.card("bank")
                assertFalse(card.allOf("button").any { it.textContent?.trim() == "Standard verwenden" })
                assertTrue(card.textContent.orEmpty().contains("Standardkonto 18000 nicht im Kontenplan."))
            }
        }

    @Test
    fun emptyTypeList_showsASentenceInsteadOfASelect(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-emptylist", FinanceSetupAccess.MANAGE_ALL, listOf(bank)) { screen, _, _ ->
                val card = screen.card("contribution")
                assertTrue(card.querySelector("select") == null)
                assertTrue(card.textContent.orEmpty().contains("Noch kein Erlöskonto im Kontenplan."))
            }
        }

    @Test
    fun deactivatedMappedAccount_isAnErrorWithAnEmptySelect(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            val inactive = acc("old", "40000", LedgerAccountType.INCOME, active = false)
            withSetup(
                "fs-error",
                FinanceSetupAccess.MANAGE_ALL,
                listOf(bank, inactive, donation),
                settings(contribution = "old"),
            ) { screen, _, _ ->
                val card = screen.card("contribution")
                assertTrue(card.textContent.orEmpty().contains("Fehler"))
                assertTrue(card.textContent.orEmpty().contains("ist deaktiviert"))
                assertEquals("", (card.querySelector("select") as HTMLSelectElement).value)
                assertTrue(card.classList.contains("lapis-setup-step--error"))
            }
        }

    @Test
    fun chartButton_onlyWithoutActiveAccounts_dialogListsTheAccounts_cancelCallsNothing_confirmCreates(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-chart", FinanceSetupAccess.MANAGE_ALL, emptyList()) { screen, calls, routes ->
                val card = screen.card("chart")
                card.buttonNamed("Standard-Kontenplan SKR42 für Vereine anlegen").click()
                val dialog = lastOpenModalAwaited()
                assertTrue(dialog.textContent.orEmpty().contains("Es werden 20 Konten angelegt:"))
                assertTrue(dialog.textContent.orEmpty().contains("Steuerberatung"))
                assertTrue(dialog.textContent.orEmpty().contains("18000"))
                dialog.buttonNamed("Abbrechen").click()
                assertTrue(calls.toRoute(routes.create).isEmpty())
                card.buttonNamed("Standard-Kontenplan SKR42 für Vereine anlegen").click()
                lastOpenModalAwaited().buttonNamed("Kontenplan anlegen").click()
                awaitUntil("createStandardChart called once") { calls.toRoute(routes.create).size == 1 }
            }
        }

    @Test
    fun chartButton_isAbsentWhenAnAccountExists(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-chart-absent", FinanceSetupAccess.MANAGE_ALL, listOf(bank)) { screen, _, _ ->
                assertFalse(screen.allOf("button").any { it.textContent.orEmpty().contains("Standard-Kontenplan SKR42") })
            }
        }

    @Test
    fun treasurer_hasNoSelects_butTheChartButtonAndTheSentence(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-treasurer", FinanceSetupAccess.CHART_ONLY, emptyList()) { screen, _, _ ->
                assertTrue(screen.querySelector("select") == null)
                assertTrue(screen.textContent.orEmpty().contains("Die Zuordnung kann nur ein Administrator ändern."))
                assertTrue(screen.allOf("button").any { it.textContent.orEmpty().contains("Standard-Kontenplan SKR42") })
                assertFalse(screen.allOf("button").any { it.textContent?.trim() == "Zuordnung speichern" })
            }
        }

    @Test
    fun board_seesTheStateButNoControls(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-board", FinanceSetupAccess.READ_ONLY, emptyList()) { screen, _, _ ->
                assertTrue(screen.querySelector("select") == null)
                assertTrue(screen.allOf("button").isEmpty(), "no operable control at all")
                assertEquals(6, screen.allOf(".lapis-setup-step").size)
            }
        }

    @Test
    fun saving_sendsTheChoice_andKeepsTheAdvancedFields(): Promise<Unit> =
        formTest {
            AppState.setSession(adminSession())
            withSetup("fs-save", FinanceSetupAccess.MANAGE_ALL, fullChart) { screen, calls, routes ->
                screen.card("bank").chooseValue("bank")
                screen.buttonNamed("Zuordnung speichern").click()
                awaitUntil("updateOrganizationSettings called") { calls.toRoute(routes.update).size == 1 }
                val body = calls.toRoute(routes.update).single().body
                assertTrue(body.contains("bank"), body)
            }
        }

    private suspend fun lastOpenModalAwaited(): HTMLElement {
        awaitUntil("a dialog is shown") { kotlinx.browser.document.querySelector(".modal.show") != null }
        return lastOpenModal()
    }

    private fun adminSession() =
        network.lapis.cloud.shared.domain.SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = network.lapis.cloud.shared.domain.AccountRole.ADMIN,
            status = network.lapis.cloud.shared.domain.MemberStatus.ACTIVE,
            expiresAt = kotlinx.datetime.LocalDateTime(2099, 1, 1, 0, 0),
        )
}
