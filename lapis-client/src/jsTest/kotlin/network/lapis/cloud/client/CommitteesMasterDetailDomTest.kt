package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeAutoEndNoticeDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.192 -- "Gremien" as a table plus detail page: columns, lazy counts, quorum wording, deep link, roster table, collapsed forms. */
class CommitteesMasterDetailDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(memberId = "caller-1", displayName = "Vera Vorstand", role = role, expiresAt = LocalDateTime(2099, 1, 1, 0, 0))

    private fun committee(
        id: String,
        name: String,
        type: CommitteeType = CommitteeType.WORKING_GROUP,
        percent: Int = 50,
        mode: CommitteeMembershipMode = CommitteeMembershipMode.MANUAL,
    ) = CommitteeDto(
        id = id,
        name = name,
        type = type,
        description = "",
        active = true,
        quorumPercent = percent,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        membershipMode = mode,
    )

    private fun seat(
        n: Int,
        committeeId: String,
        name: String = "Person $n",
    ) = CommitteeMembershipDto(
        id = "seat-$n",
        committeeId = committeeId,
        memberId = "p$n",
        memberDisplayName = name,
        role = CommitteeRole.MEMBER,
        since = LocalDate(2025, 1, 1),
        until = null,
        autoEndReason = null,
    )

    private fun HTMLElement.shows(text: String) = textContent.orEmpty().contains(text)

    private fun HTMLElement.flat() = textContent.orEmpty().replace(Regex("\\s+"), " ")

    private val emptyNotice =
        jsonOf(CommitteeAutoEndNoticeDto.serializer(), CommitteeAutoEndNoticeDto(emptyList(), emptyList(), emptyList(), emptyList()))

    private class R(
        val list: String,
        val members: String,
        val count: String,
        val notice: String,
        val end: String,
    )

    private suspend fun routes() =
        R(
            list = routeOf { rpcService<IGovernanceService>().listCommittees(true) },
            members = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) },
            count = routeOf { rpcService<IGovernanceService>().countCommitteeEligible("c") },
            notice = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() },
            end = routeOf { rpcService<IGovernanceService>().endCommitteeMembership("m", LocalDate(2026, 1, 1)) },
        )

    private fun respond(
        r: R,
        committees: List<CommitteeDto>,
        seats: Map<String, List<CommitteeMembershipDto>>,
        count: String = "9",
    ): (RecordedRequest) -> StubResponse =
        { req ->
            when {
                !req.isRpc -> StubResponse()
                req.rpcRoute == r.list -> req.answerWith(jsonOf(ListSerializer(CommitteeDto.serializer()), committees))
                req.rpcRoute == r.notice -> req.answerWith(emptyNotice)
                req.rpcRoute == r.count -> req.answerWith(count)
                req.rpcRoute == r.members -> {
                    val id = req.rpcParam(0) as String
                    val rows = seats[id]
                    if (rows ==
                        null
                    ) {
                        StubResponse(status = 500)
                    } else {
                        req.answerWith(jsonOf(ListSerializer(CommitteeMembershipDto.serializer()), rows))
                    }
                }
                else -> req.answerWith("null")
            }
        }

    // ---- pure functions ---------------------------------------------------------------------------------------------------

    @Test
    fun quorumText_rounds_up_andShowsADashWhileUnknown() {
        assertEquals("3 von 5 (50 %)", committeeQuorumText(5, 50))
        assertEquals("2 von 4 (50 %)", committeeQuorumText(4, 50))
        assertEquals("7 von 9 (75 %)", committeeQuorumText(9, 75))
        assertEquals("–", committeeQuorumText(null, 50))
        assertEquals(0, committeeQuorumRequired(0, 50))
    }

    @Test
    fun quorumHint_addsTheHalfSentenceOnlyForFiftyPercentAndAnEvenNumber() {
        val five = committeeQuorumHint(5, 50, derived = false)
        assertTrue(five.contains("mindestens 3 von 5 Mitgliedern (50 %)"), five)
        assertFalse(five.contains("Genau die Hälfte"), five)
        val four = committeeQuorumHint(4, 50, derived = false)
        assertTrue(four.contains("Genau die Hälfte (2 von 4) genügt bereits; mehr als die Hälfte wären 3."), four)
        assertFalse(committeeQuorumHint(4, 60, derived = false).contains("Genau die Hälfte"))
        assertFalse(committeeQuorumHint(4, 50, derived = false).contains("aktuellen Stand"))
        assertTrue(committeeQuorumHint(4, 50, derived = true).contains("Die Zahlen zeigen den aktuellen Stand."))
        assertTrue(committeeQuorumHint(null, 50, derived = false).contains("mindestens 50 % der Mitglieder"))
    }

    // ---- list -------------------------------------------------------------------------------------------------------------

    @Test
    fun list_isATable_withCountsLoadedLazily_linksAndAnEditIconForTheBoard(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            val committees =
                listOf(
                    committee("c-a", "Finanzen"),
                    committee("c-b", "Versammlung", type = CommitteeType.GENERAL_ASSEMBLY),
                    committee("c-c", "Ohne Zahl"),
                )
            val seats = mapOf("c-a" to (1..5).map { seat(it, "c-a") })
            withFetchStub(respond = respond(r, committees, seats, count = "10")) { calls ->
                mountedForm("committees-table") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the table with counts") { element().shows("3 von 5 (50 %)") && element().shows("5 von 10 (50 %)") }
                    assertEquals(
                        listOf("Name", "Art", "Mitglieder", "Beschlussfähig ab", "Status", "Aktionen"),
                        element().allOf("thead th").map { it.textContent?.trim() },
                    )
                    val link = element().allOf("a[data-record-id]").first { it.textContent?.trim() == "Finanzen" }
                    assertEquals("#/committees/c-a", link.getAttribute("href"))
                    // a failing count shows a dash and does not break the table
                    awaitUntil("the failed count") {
                        element()
                            .allOf("tbody tr")
                            .last()
                            .flat()
                            .contains("–")
                    }
                    // one request per committee: manual ones list their members, the assembly asks the count (never for a manual one)
                    assertEquals(2, calls.toRoute(r.members).size)
                    assertEquals(1, calls.toRoute(r.count).size)
                    val edit = element().allOf("tbody button").first()
                    assertEquals("Bearbeiten", edit.getAttribute("aria-label"))
                    assertEquals("Bearbeiten", edit.getAttribute("title"))
                    assertEquals(3, element().allOf("tbody button").size)
                    assertFalse(element().shows("Gremium oben auswählen"), "the lower roster section is gone")
                    assertTrue(element().querySelector("[id='lapis-create-committee']") != null, "the create form host exists")
                    assertEquals(null, element().querySelector("[id='lapis-create-committee'] .lapis-form"), "and is collapsed")
                }
            }
        }

    @Test
    fun list_forAPlainMember_hasNoActionsColumn(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val r = routes()
            withFetchStub(respond = respond(r, listOf(committee("c-a", "Finanzen")), mapOf("c-a" to listOf(seat(1, "c-a"))))) { _ ->
                mountedForm("committees-table-member") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the table") { element().shows("1 von 1 (50 %)") }
                    assertFalse(element().allOf("thead th").any { it.textContent?.trim() == "Aktionen" })
                    assertTrue(element().allOf("tbody button").isEmpty())
                }
            }
        }

    @Test
    fun list_emptyTextsDependOnTheRole(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val r = routes()
            withFetchStub(respond = respond(r, emptyList(), emptyMap())) { _ ->
                mountedForm("committees-empty") { root, element ->
                    renderCommitteesScreen(root)
                    awaitUntil("the empty text") { element().shows("Noch keine Gremien angelegt.") }
                }
            }
        }

    // ---- detail -----------------------------------------------------------------------------------------------------------

    @Test
    fun detail_deepLink_showsBackButtonAboveTheTitle_theHint_andTheRosterTable(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val r = routes()
            val c = committee("c-a", "Finanzen")
            withFetchStub(respond = respond(r, listOf(c), mapOf("c-a" to (1..4).map { seat(it, "c-a") }))) { calls ->
                mountedForm("committee-detail") { root, element ->
                    renderCommitteeDetail(root, "c-a")
                    awaitUntil("the hint with the half sentence") { element().shows("Genau die Hälfte (2 von 4) genügt bereits") }
                    val h1 = assertNotNull(element().querySelector("h1"))
                    assertEquals("Gremium: Finanzen", h1.textContent?.trim())
                    val back = element().buttonNamed("Zur Übersicht")
                    // document order: the back button comes BEFORE the h1
                    assertTrue(back.compareDocumentPosition(h1).toInt() and 4 != 0, "back button above the title")
                    awaitUntil("the roster table") { element().allOf("table tbody tr").size == 4 }
                    assertEquals(
                        listOf("Name", "Rolle", "Zeitraum", "Aktionen"),
                        element().allOf("thead th").map { it.textContent?.trim() },
                    )
                    val remove = element().allOf("tbody button").first()
                    assertEquals("Mitgliedschaft von Person 1 in Finanzen beenden", remove.getAttribute("aria-label"))
                    assertEquals("Mitgliedschaft von Person 1 in Finanzen beenden", remove.getAttribute("title"))
                    assertNotNull(remove.querySelector("i"), "icon only")
                    // both forms are collapsed
                    element().awaitButton("Mitglied hinzufügen")
                    element().awaitButton("Gremium bearbeiten")
                    assertFalse(element().shows("Quorum in %"), "edit form collapsed")
                    // end flow: dialog asks for the date, one request on confirm
                    remove.click()
                    awaitUntil("the dialog") { lastOpenModal().textContent.orEmpty().contains("wirklich beenden") }
                    lastOpenModal().buttonNamed("Mitgliedschaft beenden").click()
                    awaitUntil("endCommitteeMembership") { calls.toRoute(r.end).size == 1 }
                }
            }
        }

    @Test
    fun detail_fiveMembers_noHalfSentence(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val r = routes()
            withFetchStub(respond = respond(r, listOf(committee("c-a", "Finanzen")), mapOf("c-a" to (1..5).map { seat(it, "c-a") }))) { _ ->
                mountedForm("committee-detail-five") { root, element ->
                    renderCommitteeDetail(root, "c-a")
                    awaitUntil("the hint") { element().shows("mindestens 3 von 5 Mitgliedern (50 %)") }
                    assertFalse(element().shows("Genau die Hälfte"))
                    assertTrue(element().allOf("tbody button").isEmpty(), "a plain member gets no end button")
                    assertFalse(element().allOf("thead th").any { it.textContent?.trim() == "Aktionen" })
                    assertFalse(element().allOf("button").any { it.textContent?.trim() == "Gremium bearbeiten" })
                }
            }
        }

    @Test
    fun detail_unknownId_saysSo_andKeepsTheBackButton(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val r = routes()
            withFetchStub(respond = respond(r, listOf(committee("c-a", "Finanzen")), emptyMap())) { _ ->
                mountedForm("committee-detail-unknown") { root, element ->
                    renderCommitteeDetail(root, "gibt-es-nicht")
                    awaitUntil("the hint") { element().shows("Dieses Gremium wurde nicht gefunden.") }
                    element().buttonNamed("Zur Übersicht")
                }
            }
        }

    @Test
    fun detail_assembly_usesTheDerivedCount_andMarksItAsCurrent(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val r = routes()
            val assembly = committee("c-g", "Mitgliederversammlung", type = CommitteeType.GENERAL_ASSEMBLY)
            val countAssembly = routeOf { rpcService<IGovernanceService>().countGeneralAssemblyEligible("c") }
            withFetchStub(
                respond = { req ->
                    if (req.isRpc &&
                        req.rpcRoute == countAssembly
                    ) {
                        req.answerWith("100")
                    } else {
                        respond(r, listOf(assembly), mapOf("c-g" to emptyList()), "100")(req)
                    }
                },
            ) { _ ->
                mountedForm("committee-detail-assembly") { root, element ->
                    renderCommitteeDetail(root, "c-g")
                    awaitUntil("the derived hint") { element().shows("mindestens 50 von 100 Mitgliedern (50 %)") }
                    assertTrue(element().shows("Die Zahlen zeigen den aktuellen Stand."))
                    assertTrue(element().shows("Genau die Hälfte (50 von 100)"))
                }
            }
        }

    @Test
    fun detail_title_isSanitized() {
        val forged = KV_I18N_MARKER + "x"
        assertFalse(sanitizeUntrustedI18nText(forged).contains(KV_I18N_MARKER))
    }
}
