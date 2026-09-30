package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.domain.MembershipTierOverviewDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IContributionService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Welle V1.9.18 -- the tier administration screen, driven in a REAL mounted root with a stubbed `window.fetch` (the pattern of
 * `RegionalChaptersScreenDomTest` and the `FormSubmitBody*` tests): what it shows, what it refuses before any request, and what
 * goes over the wire.
 */
class MembershipTiersScreenDomTest {
    private fun session(role: AccountRole = AccountRole.TREASURER) =
        SessionInfoDto(memberId = "treasurer-1", displayName = "Schatzmeister", role = role, expiresAt = LocalDateTime(2030, 1, 1, 12, 0))

    private fun tier(
        id: String,
        name: String,
        amount: Double = 12.5,
        interval: BillingInterval = BillingInterval.MONTHLY,
        active: Boolean = true,
        description: String = "",
        term: Int = 14,
    ) = MembershipTierDto(id, name, description, amount.toDecimal(), interval, active, term)

    private fun overview(
        tiers: List<MembershipTierDto>,
        counts: Map<String, Int> = emptyMap(),
        withoutTier: Int = 0,
    ) = MembershipTierOverviewDto(tiers, counts, withoutTier)

    private data class Routes(
        val overview: String,
        val create: String,
        val update: String,
        val generate: String,
    )

    private suspend fun routes(): Routes =
        Routes(
            overview = routeOf { rpcService<IContributionService>().listMembershipTierOverview() },
            create =
                routeOf {
                    rpcService<IContributionService>().createMembershipTier(
                        MembershipTierInput("n", "", 1.0.toDecimal(), BillingInterval.MONTHLY),
                    )
                },
            update =
                routeOf {
                    rpcService<IContributionService>().updateMembershipTier(
                        "t",
                        MembershipTierInput("n", "", 1.0.toDecimal(), BillingInterval.MONTHLY),
                    )
                },
            generate =
                routeOf {
                    rpcService<IContributionService>()
                        .generateContributionsForPeriod(
                            "t",
                            kotlinx.datetime.LocalDate(2027, 1, 1),
                            kotlinx.datetime.LocalDate(2027, 1, 31),
                        )
                },
        )

    /** Answers the overview with [overview] and every write with [writeResult] (default: the created/updated tier echoed as [echo]). */
    private fun responder(
        r: Routes,
        overview: MembershipTierOverviewDto,
        echo: MembershipTierDto = overview.tiers.firstOrNull() ?: tier("echo", "Echo"),
        created: Int = 3,
    ): (RecordedRequest) -> StubResponse =
        { request ->
            when {
                !request.isRpc -> StubResponse()
                request.rpcRoute == r.overview -> request.answerWith(jsonOf(MembershipTierOverviewDto.serializer(), overview))
                request.rpcRoute == r.create || request.rpcRoute == r.update ->
                    request.answerWith(jsonOf(MembershipTierDto.serializer(), echo))
                request.rpcRoute == r.generate -> request.answerWith(created.toString())
                else -> request.answerWith("null")
            }
        }

    /** One text per list row: a table row on a wide viewport, a card below the breakpoint -- the test does not depend on the window size. */
    private fun HTMLElement.rowTexts(): List<String> {
        val tableRows = allOf("tbody tr")
        return (if (tableRows.isNotEmpty()) tableRows else allOf(".lapis-data-card")).map { it.textContent.orEmpty() }
    }

    private fun HTMLElement.actionButton(
        label: String,
        nth: Int = 0,
    ): HTMLElement = allOf("button[aria-label='$label']")[nth]

    // ── list ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun list_showsOpenTiersFirstAlphabetically_thenClosed_withBadgesAndActiveMemberCounts(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val ov =
                overview(
                    tiers =
                        listOf(
                            tier("t-closed", "Alt-Tarif", active = false),
                            tier("t-b", "Vollmitglied", amount = 120.0, interval = BillingInterval.YEARLY, term = 30),
                            tier("t-a", "Ermäßigt", amount = 60.0, interval = BillingInterval.QUARTERLY),
                        ),
                    counts = mapOf("t-b" to 7, "t-a" to 2),
                )
            withFetchStub(respond = responder(r, ov)) {
                mountedForm("tiers-list") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("three rows") { element().rowTexts().size == 3 }
                    val rows = element().rowTexts()
                    assertTrue(rows[0].contains("Ermäßigt"), "open tiers first, alphabetical: ${rows[0]}")
                    assertTrue(rows[1].contains("Vollmitglied"), rows[1])
                    assertTrue(rows[2].contains("Alt-Tarif"), "a closed tier comes last: ${rows[2]}")
                    assertTrue(rows[0].contains("Vierteljährlich") && rows[0].contains("Wählbar") && rows[0].contains("2"))
                    assertTrue(rows[1].contains("Jährlich") && rows[1].contains("30") && rows[1].contains("7"))
                    assertTrue(rows[2].contains("Geschlossen"), rows[2])
                    // the page title and the primary action exist exactly once
                    assertEquals(1, element().allOf("h1").size)
                    assertNotNull(element().allOf("button").firstOrNull { it.textContent?.trim() == "Mitgliedschaftsstufe anlegen" })
                }
            }
        }

    @Test
    fun list_offersGenerateContributions_onlyForTiersWithAnAmountAbove0(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val ov = overview(listOf(tier("t-free", "Frei", amount = 0.0), tier("t-paid", "Zahler", amount = 10.0)))
            withFetchStub(respond = responder(r, ov)) {
                mountedForm("tiers-free") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("two rows") { element().rowTexts().size == 2 }
                    assertEquals(2, element().allOf("button[aria-label='Bearbeiten']").size)
                    assertEquals(1, element().allOf("button[aria-label='Beiträge erzeugen']").size, "the free tier has no generate action")
                }
            }
        }

    @Test
    fun emptyOverview_showsTheNoticeAndTheHintForActiveMembersWithoutATier(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(respond = responder(r, overview(emptyList(), withoutTier = 4))) {
                mountedForm("tiers-empty") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("the notice") { element().textContent.orEmpty().contains("Noch keine Mitgliedschaftsstufe angelegt.") }
                    val text = element().textContent.orEmpty()
                    assertTrue(
                        text.contains("Aktive Mitglieder ohne Mitgliedschaftsstufe: 4"),
                        "the callout still shows with no tier at all: $text",
                    )
                    assertEquals(0, element().allOf("table").size + element().allOf(".lapis-data-card").size)
                }
            }
        }

    @Test
    fun noCalloutWhenEveryActiveMemberHasATier(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(respond = responder(r, overview(listOf(tier("t", "Standard")), withoutTier = 0))) {
                mountedForm("tiers-nocallout") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("a row") { element().rowTexts().size == 1 }
                    assertFalse(element().textContent.orEmpty().contains("ohne Mitgliedschaftsstufe"))
                }
            }
        }

    @Test
    fun nameAndDescription_areRenderedAsInertText_notAsMarkupOrI18nMarkers(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val forgedAmount = KV_I18N_MARKER + I18N_VALUE_SENTINEL + MONEY_KIND_LTR + "9999"
            val ov =
                overview(
                    listOf(
                        tier("t-1", "<script>window.__pwned = 1</script>", description = "<img src=x onerror=alert(1)>"),
                        tier("t-2", forgedAmount, description = KV_I18N_MARKER + "Ja"),
                    ),
                )
            withFetchStub(respond = responder(r, ov)) {
                mountedForm("tiers-untrusted") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("two rows") { element().rowTexts().size == 2 }
                    assertEquals(0, element().allOf("table script").size)
                    assertEquals(0, element().allOf("table img").size)
                    assertTrue(element().textContent.orEmpty().contains("<script>window.__pwned = 1</script>"), "shown literally")
                    assertEquals(undefined, js("window.__pwned"))
                    val text = element().textContent.orEmpty()
                    assertFalse(text.contains(KV_I18N_MARKER), "no i18n marker reaches the DOM")
                    assertFalse(text.contains(I18N_VALUE_SENTINEL), "no value sentinel reaches the DOM")
                    assertTrue(
                        text.contains(sanitizeUntrustedI18nText(forgedAmount)),
                        "a forged amount marker is shown as the sanitized, inert text -- never turned into a formatted amount",
                    )
                    assertFalse(text.contains("9.999") || text.contains("9,999"), "no formatted forged amount: $text")
                }
            }
        }

    // ── create dialog ─────────────────────────────────────────────────────────────────────────────────

    private suspend fun openCreate(element: () -> HTMLElement): HTMLElement {
        awaitUntil("the primary action") {
            element().allOf("button").any { it.textContent?.trim() == "Mitgliedschaftsstufe anlegen" }
        }
        element().buttonNamed("Mitgliedschaftsstufe anlegen").click()
        awaitUntil("the dialog") { document_modalCount() > 0 }
        return lastOpenModal()
    }

    private fun document_modalCount(): Int =
        kotlinx.browser.document
            .querySelectorAll(".modal.show")
            .length

    @Test
    fun createDialog_refusesInvalidInputBeforeAnyRequest_withFieldMessages(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val existing = tier("t-exists", "Standard")
            withFetchStub(respond = responder(r, overview(listOf(existing)))) { calls ->
                mountedForm("tiers-create-invalid") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("the list") { element().rowTexts().size == 1 }
                    val modal = openCreate(element)

                    // empty name and empty amount: nothing is sent
                    modal.buttonNamed("Speichern").click()
                    delay(150)
                    assertEquals(0, calls.toRoute(r.create).size)
                    assertTrue(modal.shownErrors().isNotEmpty(), "required fields show their error")

                    // a duplicate (other case, padded) is refused at the field
                    modal.typeInto("Name", "  STANDARD ")
                    assertTrue(modal.shownErrors().any { it.contains("existiert bereits") }, "duplicate: ${modal.shownErrors()}")

                    // amounts
                    modal.typeInto("Name", "Neu")
                    listOf("abc", "-1", "100000,01", "1,234").forEach { bad ->
                        modal.typeInto("Betrag", bad)
                        assertTrue(modal.shownErrors().isNotEmpty(), "'$bad' must be refused")
                    }
                    modal.typeInto("Betrag", "12,50")
                    assertFalse(modal.shownErrors().any { it.contains("Betrag") }, "12,50 is valid: ${modal.shownErrors()}")

                    // payment term
                    listOf("366", "1,5", "-1", "abc").forEach { bad ->
                        modal.typeInto("Zahlungsziel (Tage)", bad)
                        assertTrue(modal.shownErrors().isNotEmpty(), "term '$bad' must be refused")
                    }
                    modal.typeInto("Zahlungsziel (Tage)", "14")

                    // still nothing went out
                    assertEquals(0, calls.toRoute(r.create).size)
                }
            }
        }

    @Test
    fun createDialog_sendsTheTrimmedNormalizedInput_andDoubleClickSendsOneRequest(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val created = tier("t-new", "Neu Stufe", amount = 12.5)
            withFetchStub(respond = responder(r, overview(emptyList()), echo = created)) { calls ->
                mountedForm("tiers-create-ok") { root, element ->
                    renderMembershipTiersScreen(root)
                    val modal = openCreate(element)
                    modal.typeInto("Name", "  Neu    Stufe  ")
                    modal.typeInto("Beschreibung", "  für Neue  ")
                    modal.typeInto("Betrag", "12,50")
                    modal.chooseIn("Intervall", "QUARTERLY")
                    modal.typeInto("Zahlungsziel (Tage)", "30")
                    val save = modal.buttonNamed("Speichern")
                    save.click()
                    save.click() // a second click while the first is running must not send a second request
                    awaitUntil("create", timeoutMs = 1500) { calls.toRoute(r.create).isNotEmpty() }
                    delay(200)
                    val call = calls.singleCall(r.create)
                    val input = call.rpcParam(0)
                    assertEquals("Neu Stufe", input.name as String, "trimmed and whitespace collapsed")
                    assertEquals("für Neue", input.description as String)
                    assertEquals(12.5, input.contributionAmount as Double)
                    assertEquals("QUARTERLY", input.billingInterval as String)
                    assertEquals(30, input.paymentTermDays as Int)
                    // the dialog closes and the list reloads after success
                    awaitUntil("the modal closes") { document_modalCount() == 0 }
                    awaitUntil("a reload") { calls.toRoute(r.overview).size >= 2 }
                }
            }
        }

    @Test
    fun createDialog_nameTakenOnTheServer_showsTheFieldErrorAndKeepsTheDialogOpen(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val ov = overview(emptyList())
            val respond: (RecordedRequest) -> StubResponse = { request ->
                if (request.isRpc && request.rpcRoute == r.create) {
                    serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.MembershipTierNameTakenException")
                } else {
                    responder(r, ov)(request)
                }
            }
            withFetchStub(respond = respond) { calls ->
                mountedForm("tiers-create-taken") { root, element ->
                    renderMembershipTiersScreen(root)
                    val modal = openCreate(element)
                    modal.typeInto("Name", "Race")
                    modal.typeInto("Betrag", "5")
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("the field error") { modal.shownErrors().any { it.contains("existiert bereits") } }
                    assertEquals(1, calls.toRoute(r.create).size)
                    assertTrue(document_modalCount() > 0, "the dialog stays open")
                }
            }
        }

    @Test
    fun createDialog_showsTheZeroAmountNotice(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(respond = responder(r, overview(emptyList()))) {
                mountedForm("tiers-create-zero") { root, element ->
                    renderMembershipTiersScreen(root)
                    val modal = openCreate(element)
                    modal.typeInto("Betrag", "0")
                    awaitUntil(
                        "the notice",
                    ) { modal.textContent.orEmpty().contains("Bei einem Betrag von 0 werden keine Beiträge erzeugt.") }
                    modal.typeInto("Betrag", "5")
                    awaitUntil("the notice disappears") { !modal.textContent.orEmpty().contains("Bei einem Betrag von 0") }
                }
            }
        }

    // ── edit dialog ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun editDialog_withActiveMembers_locksTheInterval_sendsTheOriginalOne_andWarnsAboutAChangedAmount(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val t = tier("t-1", "Vollmitglied", amount = 50.0, interval = BillingInterval.YEARLY)
            val ov = overview(listOf(t), counts = mapOf("t-1" to 3))
            withFetchStub(respond = responder(r, ov, echo = t)) { calls ->
                mountedForm("tiers-edit-locked") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("the row") { element().rowTexts().size == 1 }
                    element().actionButton("Bearbeiten").click()
                    awaitUntil("the dialog") { document_modalCount() > 0 }
                    val modal = lastOpenModal()
                    val select = modal.controlOf("Intervall") as HTMLSelectElement
                    assertTrue(select.disabled, "the interval is locked while active members are assigned")
                    assertTrue(modal.textContent.orEmpty().contains("Das Intervall ist gesperrt"), "with the reason and the remedy")
                    assertEquals("50,00", (modal.controlOf("Betrag") as HTMLInputElement).value, "decimal comma, two decimals")

                    // no notice while the amount is unchanged, a notice naming the member count once it changes
                    assertFalse(modal.textContent.orEmpty().contains("künftig erzeugte Beiträge"))
                    modal.typeInto("Betrag", "55")
                    awaitUntil("the change notice") {
                        modal.textContent.orEmpty().contains("Die Änderung gilt für künftig erzeugte Beiträge von 3 aktiven Mitgliedern")
                    }

                    modal.buttonNamed("Speichern").click()
                    awaitUntil("update", timeoutMs = 1500) { calls.toRoute(r.update).isNotEmpty() }
                    val call = calls.singleCall(r.update)
                    assertEquals("t-1", call.rpcParam(0) as String)
                    val input = call.rpcParam(1)
                    assertEquals(
                        "YEARLY",
                        input.billingInterval as String,
                        "a disabled select is not submitted -- the ORIGINAL interval is",
                    )
                    assertEquals(55.0, input.contributionAmount as Double)
                }
            }
        }

    @Test
    fun editDialog_withoutActiveMembers_allowsChangingTheInterval_andShowsNoWarning(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val t = tier("t-1", "Leer", amount = 50.0, interval = BillingInterval.YEARLY)
            withFetchStub(respond = responder(r, overview(listOf(t)), echo = t)) { calls ->
                mountedForm("tiers-edit-free") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("the row") { element().rowTexts().size == 1 }
                    element().actionButton("Bearbeiten").click()
                    awaitUntil("the dialog") { document_modalCount() > 0 }
                    val modal = lastOpenModal()
                    assertFalse((modal.controlOf("Intervall") as HTMLSelectElement).disabled)
                    modal.typeInto("Betrag", "60")
                    assertFalse(modal.textContent.orEmpty().contains("künftig erzeugte Beiträge"), "no members, no notice")
                    modal.chooseIn("Intervall", "MONTHLY")
                    modal.buttonNamed("Speichern").click()
                    awaitUntil("update", timeoutMs = 1500) { calls.toRoute(r.update).isNotEmpty() }
                    assertEquals("MONTHLY", calls.singleCall(r.update).rpcParam(1).billingInterval as String)
                }
            }
        }

    @Test
    fun editDialog_renamingToItsOwnNameInAnotherCase_isNotADuplicate(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val t = tier("t-1", "Standard")
            withFetchStub(respond = responder(r, overview(listOf(t)), echo = t)) {
                mountedForm("tiers-edit-own-name") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("the row") { element().rowTexts().size == 1 }
                    element().actionButton("Bearbeiten").click()
                    awaitUntil("the dialog") { document_modalCount() > 0 }
                    val modal = lastOpenModal()
                    modal.typeInto("Name", "STANDARD")
                    assertFalse(
                        modal.shownErrors().any { it.contains("existiert bereits") },
                        "the tier itself is excluded: ${modal.shownErrors()}",
                    )
                }
            }
        }

    // ── generate dialog ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun generateDialog_refusesAnEndBeforeTheStart_andSendsTheDatesOtherwise(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val t = tier("t-1", "Zahler", amount = 10.0)
            withFetchStub(respond = responder(r, overview(listOf(t), counts = mapOf("t-1" to 5)), created = 5)) { calls ->
                mountedForm("tiers-generate") { root, element ->
                    renderMembershipTiersScreen(root)
                    awaitUntil("the row") { element().rowTexts().size == 1 }
                    element().actionButton("Beiträge erzeugen").click()
                    awaitUntil("the dialog") { document_modalCount() > 0 }
                    val modal = lastOpenModal()

                    // both dates are required
                    modal.buttonNamed("Beiträge erzeugen").click()
                    delay(150)
                    assertEquals(0, calls.toRoute(r.generate).size)

                    // end before start
                    modal.typeInto("Beginn", "2027-02-01")
                    modal.typeInto("Ende", "2027-01-31")
                    assertTrue(modal.shownErrors().any { it.contains("Ende darf nicht vor dem Beginn liegen") }, "${modal.shownErrors()}")
                    modal.buttonNamed("Beiträge erzeugen").click()
                    delay(150)
                    assertEquals(0, calls.toRoute(r.generate).size, "an invalid period is never sent")

                    modal.typeInto("Ende", "2027-02-28")
                    modal.buttonNamed("Beiträge erzeugen").click()
                    awaitUntil("generate", timeoutMs = 1500) { calls.toRoute(r.generate).isNotEmpty() }
                    val call = calls.singleCall(r.generate)
                    assertEquals("t-1", call.rpcParam(0) as String)
                    assertEquals("2027-02-01", call.rpcParam(1) as String)
                    assertEquals("2027-02-28", call.rpcParam(2) as String)
                    awaitUntil("the dialog closes") { document_modalCount() == 0 }
                }
            }
        }
}
