package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BankAccountDto
import network.lapis.cloud.shared.domain.EventRoomDto
import network.lapis.cloud.shared.domain.EventRoomStatus
import network.lapis.cloud.shared.domain.FinTsComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.MemberFinancialHistoryDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PostalDeliveryLogDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IEventRoomService
import network.lapis.cloud.shared.rpc.ILtrLedgerService
import network.lapis.cloud.shared.rpc.IMemberFinancialHistoryService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.4.31 audit round, minor findings: the CONTENT paths of the screens that moved into the shared state region (the first W5 tests stubbed
 * every answer with `null`, so they only ever saw loading / error / empty), the filtered-empty state, `aria-busy`, the LTR strip as a live
 * region, the member history's erasure badge and tab title, the FinTS PIN attributes and the  */
class AuditFixesMinorDomTest {
    @AfterTest
    fun reset() {
        AppState.setSession(null)
        PageTitle.reset()
    }

    private fun session(role: AccountRole = AccountRole.ADMIN) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Testperson",
            role = role,
            expiresAt = LocalDateTime(2026, 9, 8, 12, 0),
        )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request ->
            when {
                !request.isRpc -> StubResponse()
                request.rpcRoute in byRoute -> request.answerWith(byRoute.getValue(request.rpcRoute))
                else -> rpcResult(request.json.id as Int, "null")
            }
        }
    }

    private fun settings(postalMailEnabled: Boolean) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto("o1", "Verein", null, null, null, null, null, null, null, null, postalMailEnabled = postalMailEnabled),
        )

    // ---- content paths -------------------------------------------------------------------------------------------------------

    @Test
    fun postalMailLog_showsTheEntriesOfARealAnswer(): Promise<Unit> =
        formTest {
            val logRoute = routeOf { rpcService<IPostalMailService>().listPostalDeliveryLog() }
            val settingsRoute = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
            val entry =
                PostalDeliveryLogDto(
                    id = "l1",
                    recipientMemberId = "m1",
                    recipientDisplayName = "Erika Muster",
                    documentReference = "Beitragsrechnung 2026",
                    dispatchedAt = LocalDateTime(2026, 9, 1, 10, 0),
                    status = PostalDeliveryStatus.SENT,
                    providerReference = "LX-4711",
                    errorMessage = null,
                )
            withFetchStub(
                answering(
                    logRoute to jsonOf(ListSerializer(PostalDeliveryLogDto.serializer()), listOf(entry)),
                    settingsRoute to settings(postalMailEnabled = true),
                ),
            ) { _ ->
                mountedForm("minor-postal") { root, element ->
                    renderPostalMailScreen(root)
                    awaitUntil("the log entry") { element().textContent.orEmpty().contains("Erika Muster") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Beitragsrechnung 2026") && text.contains("LX-4711") && text.contains("Versendet"), text)
                    assertNull(element().querySelector(".alert-danger"))
                    assertNull(element().querySelector(".alert-warning"), "postal mail is enabled: no 'deaktiviert' band")
                }
            }
        }

    @Test
    fun eventRooms_contentShowsTheRooms_andWithInactiveRoomsHiddenAnEmptyListIsTheFilteredEmptyState(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val route = routeOf { rpcService<IEventRoomService>().listRooms(includeInactive = true) }
            val room = EventRoomDto("r1", "Festsaal", 120, listOf("Beamer"), EventRoomStatus.ACTIVE)
            var answer = jsonOf(ListSerializer(EventRoomDto.serializer()), listOf(room))
            withFetchStub(respond = { request ->
                if (request.isRpc && request.rpcRoute == route) request.answerWith(answer) else rpcResult(request.json.id as Int, "null")
            }) { _ ->
                mountedForm("minor-rooms") { root, element ->
                    renderEventRoomsScreen(root)
                    awaitUntil("the room") { element().textContent.orEmpty().contains("Festsaal") }
                    // an empty answer with "Inaktive Räume anzeigen" ticked: nothing exists at all
                    answer = "[]"
                    element().buttonNamed("Aktualisieren").click()
                    awaitUntil("empty") { element().textContent.orEmpty().contains("Noch keine Räume angelegt.") }
                    // the same empty answer with the box UNticked only means "no ACTIVE room"
                    (element().controlOf("Inaktive Räume anzeigen") as HTMLInputElement).click()
                    element().buttonNamed("Aktualisieren").click()
                    awaitUntil("filtered-empty") { element().textContent.orEmpty().contains("Keine aktiven Räume.") }
                    assertTrue(
                        !element().textContent.orEmpty().contains("Noch keine Räume angelegt."),
                        "with inactive rooms hidden, 'no rooms created' would be a false claim",
                    )
                }
            }
        }

    @Test
    fun dataSection_marksItsRegionAriaBusy_whileLoading(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val route = routeOf { rpcService<IEventRoomService>().listRooms(includeInactive = true) }
            withFetchStub(respond = { request ->
                if (request.isRpc && request.rpcRoute == route) {
                    StubResponse(text = """{"id":${request.json.id},"result":"[]"}""", delayMs = 400)
                } else {
                    rpcResult(request.json.id as Int, "null")
                }
            }) { _ ->
                mountedForm("minor-busy") { root, element ->
                    renderEventRoomsScreen(root)
                    awaitUntil("busy while loading") { element().querySelector("[aria-busy=true]") != null }
                    awaitUntil("not busy afterwards") {
                        element().querySelector("[aria-busy=true]") == null && element().querySelector("[aria-busy=false]") != null
                    }
                }
            }
        }

    @Test
    fun ltrBalanceStrip_isALiveRegion_showsTheBalance_andIsNotBusyAfterwards(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<ILtrLedgerService>().getMyBalance() }
            withFetchStub(answering(route to jsonOf(LtrLedgerBalanceDto.serializer(), LtrLedgerBalanceDto("m1", 12.5.toDecimal())))) { _ ->
                mountedForm("minor-ltr") { root, element ->
                    var loaded: String? = "unset"
                    root.renderMyLtrBalanceInline { loaded = it?.toString() }
                    awaitUntil("the balance") { element().textContent.orEmpty().contains("12,50${NBSP}LTR") }
                    val host = element().querySelector("[role=status]") as HTMLElement
                    assertEquals("polite", host.getAttribute("aria-live"))
                    assertEquals("false", host.getAttribute("aria-busy"))
                    assertEquals("12.5", loaded)
                    assertNotNull(host.querySelector("a[href]"), "the link to the LTR account stays")
                }
            }
        }

    // ---- member history: erasure badge next to the name, tab title ---------------------------------------------------------------

    @Test
    fun memberHistory_ofAnotherMember_showsTheErasureBadgeNextToTheName_andTheTabTitleNamesTheMember(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val route = routeOf { rpcService<IMemberFinancialHistoryService>().getMemberFinancialHistory("x") }
            val dto =
                MemberFinancialHistoryDto(
                    memberId = "member-2",
                    memberDisplayName = "Erika Muster",
                    anonymized = true,
                    joinedAt = LocalDate(2020, 1, 1),
                    friendSince = null,
                    contributionsPaid = 0.0.toDecimal(),
                    contributionsWaived = 0.0.toDecimal(),
                    contributionsOutstanding = 0.0.toDecimal(),
                    donationsTotal = 0.0.toDecimal(),
                    years = emptyList(),
                )
            withFetchStub(answering(route to jsonOf(MemberFinancialHistoryDto.serializer(), dto))) { _ ->
                mountedForm("minor-history") { root, element ->
                    renderMemberFinancialHistoryScreen(root, "member-2")
                    awaitUntil("the name") { element().textContent.orEmpty().contains("Erika Muster") }
                    val row = element().querySelector(".lapis-page-header > div.d-flex.align-items-center") as HTMLElement
                    assertTrue(row.textContent.orEmpty().contains("Erika Muster"), "the name is the subtitle")
                    assertTrue(row.textContent.orEmpty().contains("DSGVO-gelöscht"), "the erasure marker stands NEXT to it (same row)")
                    assertEquals("Beitragshistorie – Erika Muster – ${Branding.title}", document_title())
                }
            }
        }

    private fun document_title(): String = kotlinx.browser.document.title

    // ---- FinTS PIN / user id ------------------------------------------------------------------------------------------------------

    @Test
    fun finTsCredentialFields_keepTheirBrowserHardeningAttributes_afterTheMoveToTheFieldParameter(): Promise<Unit> =
        formTest {
            withFetchStub { _ ->
                mountedForm("minor-fints") { _, _ ->
                    showFinTsSetupModal(
                        account =
                            BankAccountDto(
                                id = "ba-1",
                                label = "Hauptkonto",
                                iban = "DE89370400440532013000",
                                ibanMasked = "DE89••3000",
                                bic = "COBADEFF",
                                bankName = "Commerzbank",
                                isDefault = true,
                                createdAt = LocalDateTime(2026, 1, 1, 10, 0),
                                updatedAt = LocalDateTime(2026, 1, 1, 10, 0),
                            ),
                        disclaimer = FinTsComplianceDisclaimerDto(version = "v1", text = "Hinweis", sha256 = "x"),
                        onChanged = {},
                    )
                    val modal = lastOpenModal()
                    listOf("Benutzerkennung", "PIN").forEach { label ->
                        val control = modal.controlOf(label)
                        assertEquals("new-password", control.getAttribute("autocomplete"), "$label: autocomplete")
                        assertEquals("off", control.getAttribute("autocapitalize"), "$label: autocapitalize")
                        assertEquals("false", control.getAttribute("spellcheck"), "$label: spellcheck")
                    }
                }
            }
        }
}
