package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.192 -- "Sitzungen" as a table plus detail page: order around "now", no actions column, deep link, agenda icon button. */
class MeetingsMasterDetailDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(memberId = "caller-1", displayName = "Vera Vorstand", role = role, expiresAt = LocalDateTime(2099, 1, 1, 0, 0))

    private fun meeting(
        id: String,
        title: String,
        at: LocalDateTime,
        status: MeetingStatus = MeetingStatus.PLANNED,
        location: String? = null,
    ) = MeetingDto(
        id = id,
        committeeId = "committee-1",
        committeeName = "Vorstand",
        title = title,
        scheduledAt = at,
        location = location,
        format = MeetingFormat.IN_PERSON,
        status = status,
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

    private val committee =
        CommitteeDto(
            id = "committee-1",
            name = "Vorstand",
            type = CommitteeType.EXECUTIVE_BOARD,
            description = "",
            active = true,
            quorumPercent = 50,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    private fun HTMLElement.shows(text: String) = textContent.orEmpty().contains(text)

    // ---- ordering ---------------------------------------------------------------------------------------------------------

    @Test
    fun orderMeetings_upcomingAscending_thenPastDescending_withTheBoundaryAtNow() {
        val now = LocalDateTime(2026, 10, 22, 12, 0)
        val before = meeting("b", "Vorher", LocalDateTime(2026, 10, 22, 11, 59))
        val exactlyNow = meeting("n", "Jetzt", now)
        val later = meeting("l", "Später", LocalDateTime(2026, 11, 1, 18, 0))
        val soon = meeting("s", "Bald", LocalDateTime(2026, 10, 23, 18, 0), status = MeetingStatus.CANCELLED)
        val old = meeting("o", "Alt", LocalDateTime(2026, 1, 1, 18, 0))
        val (upcoming, past) = orderMeetings(listOf(old, later, before, soon, exactlyNow), now)
        assertEquals(listOf("n", "s", "l"), upcoming.map { it.id }, "a cancelled future meeting stays upcoming")
        assertEquals(listOf("b", "o"), past.map { it.id })
    }

    // ---- list -------------------------------------------------------------------------------------------------------------

    @Test
    fun list_isATable_withASeparatorRow_noActionsColumn_andRealLinks(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val list = routeOf { rpcService<IGovernanceService>().listMeetings(null, null) }
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            val members = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            val meetings =
                listOf(
                    meeting("m-old", "Altsitzung", LocalDateTime(2020, 1, 1, 18, 0)),
                    meeting("m-new", "Zukunft", LocalDateTime(2099, 5, 6, 18, 0), location = "Rathaus"),
                )
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == list -> req.answerWith(jsonOf(ListSerializer(MeetingDto.serializer()), meetings))
                        req.rpcRoute == committees -> req.answerWith(jsonOf(ListSerializer(CommitteeDto.serializer()), listOf(committee)))
                        req.rpcRoute == members -> req.answerWith("[]")
                        else -> req.answerWith("null")
                    }
                },
            ) { _ ->
                mountedForm("meetings-table") { root, element ->
                    renderMeetingsScreen(root)
                    awaitUntil("the table") { element().allOf("a[data-record-id]").size == 2 }
                    assertEquals(
                        listOf("Termin", "Titel", "Gremium", "Art/Ort", "Status"),
                        element().allOf("thead th").map { it.textContent?.trim() },
                    )
                    val rows = element().allOf("tbody tr")
                    assertEquals(3, rows.size, "upcoming row, separator, past row")
                    assertTrue(rows[0].shows("Zukunft") && rows[0].shows("Rathaus"))
                    assertTrue(rows[1].shows("Vergangene Sitzungen"))
                    assertTrue(rows[2].shows("Altsitzung"))
                    assertEquals("#/meetings/m-new", element().allOf("a[data-record-id]").first().getAttribute("href"))
                    assertTrue(element().allOf("tbody button").isEmpty(), "no actions column")
                    assertFalse(element().shows("Sitzung oben auswählen"), "the detail section is gone")
                    // both filters stay
                    assertTrue(element().allOf("select").size >= 2)
                }
            }
        }

    // ---- detail -----------------------------------------------------------------------------------------------------------

    private fun detailOf(agenda: List<AgendaItemDto>) =
        MeetingDetailDto(
            meeting = meeting("m-1", "Haushaltssitzung", LocalDateTime(2099, 5, 6, 18, 0)),
            agenda = agenda,
            attendance = emptyList(),
            resolutions = emptyList(),
            quorum = QuorumResultDto("m-1", 3, 2, 2, 50, met = true),
        )

    @Test
    fun detail_deepLink_hasTheBackButtonAboveTheTitle_andAnIconOnlyAgendaRemoval(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val detail = routeOf { rpcService<IGovernanceService>().getMeetingDetail("m") }
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            val members = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            val remove = routeOf { rpcService<IGovernanceService>().removeAgendaItem("a") }
            val item = AgendaItemDto("ai-1", "m-1", 1, "Haushalt", null, null, null)
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == detail -> req.answerWith(jsonOf(MeetingDetailDto.serializer(), detailOf(listOf(item))))
                        req.rpcRoute == committees -> req.answerWith(jsonOf(ListSerializer(CommitteeDto.serializer()), listOf(committee)))
                        req.rpcRoute == members -> req.answerWith("[]")
                        req.rpcRoute == remove -> req.answerWith("{}")
                        else -> req.answerWith("null")
                    }
                },
            ) { calls ->
                mountedForm("meeting-detail") { root, element ->
                    renderMeetingDetailScreen(root, "m-1")
                    awaitUntil("the agenda") { element().shows("Haushalt") && element().querySelector("h1") != null }
                    val h1 = assertNotNull(element().querySelector("h1"))
                    assertEquals("Sitzung: Haushaltssitzung", h1.textContent?.trim())
                    val back = element().buttonNamed("Zur Übersicht")
                    assertTrue(back.compareDocumentPosition(h1).toInt() and 4 != 0, "back button above the title")
                    val button = element().allOf("table tbody button").single()
                    assertEquals("Tagesordnungspunkt \"Haushalt\" entfernen", button.getAttribute("aria-label"))
                    assertEquals("Tagesordnungspunkt \"Haushalt\" entfernen", button.getAttribute("title"))
                    assertEquals("", button.textContent?.trim(), "icon only")
                    // the add form stays collapsed
                    element().awaitButton("Punkt hinzufügen")
                    button.click()
                    awaitUntil("the confirmation") { lastOpenModal().textContent.orEmpty().contains("\"Haushalt\"") }
                    assertEquals(0, calls.toRoute(remove).size, "nothing is sent before the confirmation")
                    lastOpenModal().buttonNamed("Entfernen").click()
                    awaitUntil("removeAgendaItem") { calls.toRoute(remove).size == 1 }
                }
            }
        }

    @Test
    fun detail_unknownId_leavesAHint_notABlankPage(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val detail = routeOf { rpcService<IGovernanceService>().getMeetingDetail("m") }
            withFetchStub(respond = { req ->
                if (req.isRpc &&
                    req.rpcRoute == detail
                ) {
                    StubResponse(status = 500)
                } else {
                    StubResponse()
                }
            }) { _ ->
                mountedForm("meeting-detail-unknown") { root, element ->
                    renderMeetingDetailScreen(root, "weg")
                    awaitUntil("the hint") { element().shows("Diese Sitzung wurde nicht gefunden.") }
                    element().buttonNamed("Zur Übersicht")
                }
            }
        }
}
