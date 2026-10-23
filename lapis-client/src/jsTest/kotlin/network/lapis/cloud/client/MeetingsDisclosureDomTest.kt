package network.lapis.cloud.client

import io.kvision.html.p
import io.kvision.navbar.NavbarExpand
import io.kvision.navbar.navbar
import io.kvision.offcanvas.OffPlacement
import io.kvision.offcanvas.offcanvas
import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AttendanceDto
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PostalDeliveryLogDto
import network.lapis.cloud.shared.domain.PostalDeliveryStatus
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService
import network.lapis.cloud.shared.rpc.IPostalMailService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.182 -- the meeting page's progressive disclosure: the [disclosure] control, the agenda table, the collapsed forms ("Punkt
 * hinzufügen", "Anwesenheit erfassen", "Einladung versenden"), the selection summary of the invitation, its fold-back after a
 * complete dispatch, and the friend badge in the navbar.
 */
class MeetingsDisclosureDomTest {
    private val three =
        listOf(
            MemberSummaryDto(id = "m1", displayName = "Amara Okafor"),
            MemberSummaryDto(id = "m2", displayName = "Kofi Mensah"),
            MemberSummaryDto(id = "m3", displayName = "Lena Berg"),
        )

    private fun answering(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request -> if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "null") }
    }

    private fun HTMLElement?.isShown(): Boolean = this != null && this.offsetParent != null

    private fun meeting() =
        MeetingDto(
            id = "meeting-9",
            committeeId = "committee-1",
            committeeName = "Vorstand",
            title = "Sitzung",
            scheduledAt = LocalDateTime(2026, 9, 12, 18, 30),
            location = null,
            format = MeetingFormat.IN_PERSON,
            status = MeetingStatus.PLANNED,
            calledById = null,
            calledByDisplayName = null,
            calledAt = null,
            chairMemberId = null,
            chairDisplayName = null,
            minuteTakerMemberId = null,
            minuteTakerDisplayName = null,
            protocolDocumentId = null,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    private fun detail(
        agenda: List<AgendaItemDto> = emptyList(),
        attendance: List<AttendanceDto> = emptyList(),
    ) = MeetingDetailDto(
        meeting = meeting(),
        agenda = agenda,
        attendance = attendance,
        resolutions = emptyList(),
        quorum = QuorumResultDto("meeting-9", 3, 2, 2, 50, met = true),
    )

    private fun agendaItem(
        n: Int,
        title: String = "Punkt $n",
        presenter: String? = "Vortragende $n",
    ) = AgendaItemDto("ai-$n", "meeting-9", n, title, "Beschreibung $n", null, presenter)

    private fun orgSettingsJson(postal: Boolean) =
        jsonOf(
            OrganizationSettingsDto.serializer(),
            OrganizationSettingsDto(
                id = "org",
                name = "Verein",
                street = null,
                postalCode = null,
                city = null,
                country = null,
                bankIban = null,
                bankBic = null,
                taxExemptionAuthority = null,
                taxExemptionDate = null,
                postalMailEnabled = postal,
            ),
        )

    // ── disclosure() ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun disclosure_isClosedByDefault_togglesAriaExpanded_andKeepsItsLabel(): Promise<Unit> =
        formTest {
            mountedForm("disclosure-toggle") { root, element ->
                root.vPanel { disclosure("Mehr anzeigen", "disclosure-body") { p("Der Inhalt") } }
                val toggle = element().awaitButton("Mehr anzeigen")

                fun body() = element().querySelector("#disclosure-body") as? HTMLElement
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                assertEquals("disclosure-body", toggle.getAttribute("aria-controls"))
                assertFalse(body().isShown(), "closed by default")
                toggle.click()
                awaitUntil("the body opens") { body().isShown() }
                assertEquals("true", toggle.getAttribute("aria-expanded"))
                assertEquals("Mehr anzeigen", toggle.textContent?.trim(), "the label does not change with the state")
                toggle.click()
                awaitUntil("the body closes") { !body().isShown() }
                assertEquals("false", toggle.getAttribute("aria-expanded"))
            }
        }

    // ── agenda table ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun agendaTable_hasItsColumns_andNoActionColumnWithoutRights(): Promise<Unit> =
        formTest {
            mountedForm("agenda-table-columns") { root, element ->
                renderAgendaTable(root, listOf(agendaItem(1), agendaItem(2)), canManage = false, onChanged = {})
                awaitUntil("the table") { element().querySelector("table") != null }
                val headers = element().allOf("th[scope='col']").map { it.textContent.orEmpty().trim() }
                assertEquals(listOf("Nr.", "Titel", "Vortragende"), headers)
                assertEquals(2, element().allOf("th[scope='row']").size, "the title is the row header")
                assertTrue(element().allOf("button").isEmpty())
            }
        }

    @Test
    fun agendaTable_removeAsksFirst_andSendsExactlyOneRequestOnConfirm(): Promise<Unit> =
        formTest {
            val remove = routeOf { rpcService<IGovernanceService>().removeAgendaItem("ai-1") }
            withFetchStub(respond = answering(remove to "{}")) { calls ->
                mountedForm("agenda-table-remove") { root, element ->
                    var changed = 0
                    renderAgendaTable(root, listOf(agendaItem(1, title = "Haushalt")), canManage = true, onChanged = { changed++ })
                    awaitUntil("the icon button") {
                        element().querySelector("button[aria-label='Tagesordnungspunkt \"Haushalt\" entfernen']") !=
                            null
                    }
                    val button = element().querySelector("button[aria-label='Tagesordnungspunkt \"Haushalt\" entfernen']") as HTMLElement
                    button.click()
                    awaitUntil("the confirmation") { lastOpenModalOrNull()?.textContent.orEmpty().contains("\"Haushalt\"") }
                    delay(80)
                    assertEquals(0, calls.toRoute(remove).size, "nothing is sent before the confirmation")
                    val confirm = lastOpenModal().buttonNamed("Entfernen")
                    confirm.click()
                    confirm.click()
                    awaitUntil("removeAgendaItem") { calls.toRoute(remove).isNotEmpty() }
                    delay(100)
                    assertEquals(1, calls.toRoute(remove).size, "a double click is one request")
                    assertEquals("ai-1", calls.singleCall(remove).rpcParam(0) as String)
                    awaitUntil("the page reloads") { changed == 1 }
                }
            }
        }

    @Test
    fun agendaTable_aForgedMarkerInTheTitleStaysInertText(): Promise<Unit> =
        formTest {
            val forged = KV_I18N_MARKER + I18N_VALUE_SENTINEL + MONEY_KIND_LTR + "9999"
            mountedForm("agenda-table-forged") { root, element ->
                renderAgendaTable(root, listOf(agendaItem(1, title = forged, presenter = forged)), canManage = false, onChanged = {})
                awaitUntil("the table") { element().querySelector("table") != null }
                val text = element().textContent.orEmpty()
                assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
                assertFalse(text.contains(I18N_VALUE_SENTINEL), "sentinel must not survive: $text")
                assertFalse(text.contains("€"), "no formatted amount: $text")
            }
        }

    // ── collapsed forms ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun addAgendaItem_isOneButton_thatOpensTheFormAndCancelClosesItAgain(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answering()) { _ ->
                mountedForm("agenda-add-collapsed") { root, element ->
                    renderAgendaSection(root, detail(), canManage = true, audience = MeetingAudience.Roster(three), onChanged = {})
                    val open = element().awaitButton("Punkt hinzufügen")
                    assertEquals("false", open.getAttribute("aria-expanded"))
                    assertEquals(0, element().allOf(".lapis-form").size, "closed: no form")
                    open.click()
                    awaitUntil("the form") { element().allOf(".lapis-form").size == 1 }
                    assertEquals("true", open.getAttribute("aria-expanded"))
                    element().buttonNamed("Abbrechen").click()
                    awaitUntil("the form folds back") { element().allOf(".lapis-form").isEmpty() }
                    assertEquals("false", open.getAttribute("aria-expanded"))
                }
            }
        }

    @Test
    fun attendance_theListIsBehindADisclosure_andTheRecordingFormBehindOneButton(): Promise<Unit> =
        formTest {
            val att =
                AttendanceDto(
                    "a1",
                    "meeting-9",
                    "m1",
                    "Amara Okafor",
                    AttendanceStatus.PRESENT,
                    null,
                    null,
                    null,
                    LocalDateTime(2026, 9, 12, 18, 0),
                )
            withFetchStub(respond = answering()) { _ ->
                mountedForm("attendance-collapsed") { root, element ->
                    renderAttendanceSection(
                        root,
                        detail(attendance = listOf(att)),
                        canManage = true,
                        audience = MeetingAudience.Roster(three),
                        onChanged = {},
                    )
                    val list = element().awaitButton("Anwesende anzeigen")
                    assertEquals("false", list.getAttribute("aria-expanded"))
                    assertFalse((element().querySelector("#lapis-attendance-list") as? HTMLElement).isShown(), "the list is closed")
                    list.click()
                    awaitUntil("the list opens") { (element().querySelector("#lapis-attendance-list") as? HTMLElement).isShown() }
                    assertEquals(0, element().allOf(".lapis-form").size, "the recording form is closed")
                    element().buttonNamed("Anwesenheit erfassen").click()
                    awaitUntil("one mini form per person") { element().allOf(".lapis-form").size == 3 }
                }
            }
        }

    // ── invitation ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun invitation_selectionSummaryFollowsTheTicks_isDescribedByTheButton_andResetsOnClose(): Promise<Unit> =
        formTest {
            withFetchStub(respond = answering()) { _ ->
                mountedForm("invite-summary") { root, element ->
                    renderEinladungSection(
                        root,
                        meeting(),
                        canManage = true,
                        isBoardOrAdminGlobal = true,
                        audience = MeetingAudience.Roster(three),
                    )
                    val open = element().awaitButton("Einladung versenden")
                    val summary = assertNotNull(element().querySelector("#lapis-send-invitation-summary") as? HTMLElement)
                    assertEquals("lapis-send-invitation-summary", open.getAttribute("aria-describedby"))
                    assertEquals("0 von 3 ausgewählt", summary.textContent?.trim())
                    open.click()
                    awaitUntil("the form") { element().querySelector(".lapis-form") != null }
                    element().tick("Kofi Mensah")
                    element().tick("Lena Berg")
                    awaitUntil("the summary follows") { summary.textContent?.trim() == "2 von 3 ausgewählt" }
                    element().buttonNamed("Abbrechen").click()
                    // two ticks changed the form: closing asks first
                    awaitUntil("the discard question") { lastOpenModalOrNull()?.textContent.orEmpty().contains("Eingaben verwerfen?") }
                    lastOpenModal().buttonNamed("Verwerfen").click()
                    awaitUntil("the summary is back to its default") { summary.textContent?.trim() == "0 von 3 ausgewählt" }
                    assertTrue(element().querySelector(".lapis-form") == null)
                }
            }
        }

    private fun postalLog(status: PostalDeliveryStatus) =
        PostalDeliveryLogDto(
            id = "log-1",
            recipientMemberId = "m2",
            recipientDisplayName = "Kofi Mensah",
            documentReference = "ref",
            dispatchedAt = LocalDateTime(2026, 9, 1, 10, 0),
            status = status,
            providerReference = null,
            errorMessage = if (status == PostalDeliveryStatus.FAILED) "Fehler" else null,
        )

    private suspend fun sendPostalInvitation(
        id: String,
        status: PostalDeliveryStatus,
        afterwards: suspend (HTMLElement) -> Unit,
    ) {
        val dispatch =
            routeOf {
                rpcService<IPostalMailService>().dispatchEinladungByPost(
                    network.lapis.cloud.shared.domain
                        .PostalInvitationDispatchInput("t", LocalDateTime(2026, 1, 1, 1, 1), "l", "b", emptyList()),
                )
            }
        val settings = routeOf { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
        val log = jsonOf(ListSerializer(PostalDeliveryLogDto.serializer()), listOf(postalLog(status)))
        withFetchStub(respond = answering(settings to orgSettingsJson(postal = true), dispatch to log)) { calls ->
            mountedForm(id) { root, element ->
                renderEinladungSection(
                    root,
                    meeting(),
                    canManage = true,
                    isBoardOrAdminGlobal = true,
                    audience = MeetingAudience.Roster(three),
                )
                element().openInvitation()
                element().awaitButton("Per Post versenden")
                element().typeInto("Titel", "Sommerfest")
                element().typeDateTimeInto("Termin", "2026-09-05T15:30")
                element().typeInto("Ort", "Gemeindehaus")
                element().typeInto("Einladungstext", "Wir freuen uns auf Sie.")
                element().tick("Kofi Mensah")
                element().buttonNamed("Per Post versenden").click()
                awaitUntil("the confirmation dialog") { lastOpenModalOrNull()?.textContent.orEmpty().contains("Jetzt per Post versenden") }
                lastOpenModal().buttonNamed("Jetzt per Post versenden").click()
                awaitUntil("dispatchEinladungByPost") { calls.toRoute(dispatch).isNotEmpty() }
                afterwards(element())
            }
        }
    }

    @Test
    fun invitation_foldsBackAfterACompleteDispatch(): Promise<Unit> =
        formTest {
            sendPostalInvitation("invite-sent", PostalDeliveryStatus.SENT) { element ->
                awaitUntil("the form folds back") { element.querySelector(".lapis-form") == null }
            }
        }

    @Test
    fun invitation_staysOpenWhenALetterFailed_becauseTheOutcomeLinesLiveInTheForm(): Promise<Unit> =
        formTest {
            sendPostalInvitation("invite-failed", PostalDeliveryStatus.FAILED) { element ->
                awaitUntil("the failure is shown") { element.textContent.orEmpty().contains("Kofi Mensah") }
                delay(150)
                assertTrue(element.querySelector(".lapis-form") != null, "a failed dispatch keeps the form open")
            }
        }

    // ── navbar: the friend badge ───────────────────────────────────────────────────────────────────────────

    private fun navbarSession(
        status: MemberStatus,
        guest: Boolean = false,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Dana Keller",
        role = AccountRole.MEMBER,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        isGuest = guest,
        homeserverUrl = if (guest) "https://home.example.org" else null,
        status = status,
    )

    private fun <T> withNavbar(
        id: String,
        session: SessionInfoDto,
        block: (() -> HTMLElement) -> T,
    ): T =
        withMountedRoot(id) { root, element ->
            UnreadMessages.rpc = UnreadCountRpc { 0 }
            AppState.setSession(session)
            try {
                val navbar = root.navbar(label = "Lapis", expand = NavbarExpand.ALWAYS, className = "lapis-navbar")
                val sidebar = root.offcanvas(placement = OffPlacement.START, className = "lapis-sidebar")
                refreshNavbar(navbar, sidebar, onLanguageChange = {})
                block(element)
            } finally {
                AppState.setSession(null)
                UnreadMessages.resetForTest()
            }
        }

    @Test
    fun navbar_aFriendGetsTheFriendBadgeInTheMenu_aMemberAndAGuestDoNot() {
        withNavbar("navbar-friend", navbarSession(MemberStatus.FRIEND)) { element ->
            val badge = assertNotNull(element().querySelector(".dropdown-menu [role='img']") as? HTMLElement)
            assertEquals("Freund", badge.getAttribute("aria-label"))
        }
        withNavbar("navbar-member", navbarSession(MemberStatus.ACTIVE)) { element ->
            assertTrue(element().querySelector(".dropdown-menu [role='img']") == null, "no badge for a member")
        }
        withNavbar("navbar-guest", navbarSession(MemberStatus.GUEST, guest = true)) { element ->
            val badge = assertNotNull(element().querySelector(".dropdown-menu [role='img']") as? HTMLElement)
            assertTrue(badge.getAttribute("aria-label").orEmpty().startsWith("Gast von"))
        }
    }
}

private fun lastOpenModalOrNull(): HTMLElement? {
    val modals = kotlinx.browser.document.querySelectorAll(".modal")
    return modals.item(modals.length - 1) as? HTMLElement
}
