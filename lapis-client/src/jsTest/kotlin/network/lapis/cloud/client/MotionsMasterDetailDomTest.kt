package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.193 -- "Anträge" as a table plus detail page: columns, real links, amendment note, deep link, withdraw button, unknown id. */
class MotionsMasterDetailDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(memberId = "caller-1", displayName = "Vera Vorstand", role = role, expiresAt = LocalDateTime(2099, 1, 1, 0, 0))

    private fun motion(
        id: String,
        title: String,
        status: MotionStatus = MotionStatus.SUBMITTED,
        amends: String? = null,
    ) = motionDto().copy(id = id, title = title, status = status, amendsMotionId = amends)

    private val committee =
        CommitteeDto(
            id = "c1",
            name = "Mitgliederversammlung",
            type = CommitteeType.GENERAL_ASSEMBLY,
            description = "",
            active = true,
            quorumPercent = 50,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    private fun HTMLElement.shows(text: String) = textContent.orEmpty().contains(text)

    // ---- ordering ---------------------------------------------------------------------------------------------------------

    @Test
    fun orderMotionsForTable_mainFirst_itsAmendmentsDirectlyAfter_orphansLast() {
        val a = motion("a", "A")
        val b = motion("b", "B")
        val a1 = motion("a1", "A1", amends = "a")
        val orphan = motion("o", "Waise", amends = "fehlt")
        val b1 = motion("b1", "B1", amends = "b")
        assertEquals(listOf("a", "a1", "b", "b1", "o"), orderMotionsForTable(listOf(a, orphan, b1, b, a1)).map { it.id })
    }

    // ---- list -------------------------------------------------------------------------------------------------------------

    @Test
    fun list_isATable_withRealLinks_anIconActionAndTheAmendmentNote(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val list = routeOf { rpcService<IGovernanceService>().listMotions(null, null) }
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            val members = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
            val motions = listOf(motion("m-1", "Haushalt"), motion("m-2", "Haushalt ändern", amends = "m-1"))
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == list -> req.answerWith(jsonOf(ListSerializer(MotionDto.serializer()), motions))
                        req.rpcRoute == committees -> req.answerWith(jsonOf(ListSerializer(CommitteeDto.serializer()), listOf(committee)))
                        req.rpcRoute == members -> req.answerWith("[]")
                        else -> req.answerWith("null")
                    }
                },
            ) { _ ->
                mountedForm("motions-table") { root, element ->
                    renderMotionsScreen(root)
                    awaitUntil("the table") { element().allOf("a[data-record-id]").size == 2 }
                    assertEquals(
                        listOf("Titel", "Gremium", "Antragsteller", "Status", "Datum", "Aktionen"),
                        element().allOf("thead th").map { it.textContent?.trim() },
                    )
                    val rows = element().allOf("tbody tr")
                    assertEquals(2, rows.size)
                    assertTrue(rows[0].shows("Haushalt") && rows[0].shows("Mitgliederversammlung") && rows[0].shows("Clara Chair"))
                    assertTrue(rows[1].shows("Änderung zu: Haushalt"), "the amendment names its parent")
                    assertEquals("#/motions/m-1", element().allOf("a[data-record-id]").first().getAttribute("href"))
                    val action = rows[0].allOf("button").single()
                    assertEquals("Details zu Haushalt", action.getAttribute("aria-label"))
                    assertEquals("", action.textContent?.trim(), "icon only")
                    action.click()
                    assertEquals("m-1", MasterDetailFocus.peek(Routes.MOTIONS), "the list gives the focus back to the opened record")
                    MasterDetailFocus.clearAll()
                    assertFalse(element().shows("Antrag oben auswählen"), "the detail section is gone")
                    assertTrue(element().allOf("select").size >= 2, "both filters stay")
                }
            }
        }

    @Test
    fun list_emptyState_keepsItsSentence(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val list = routeOf { rpcService<IGovernanceService>().listMotions(null, null) }
            val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
            withFetchStub(
                respond = { req ->
                    when {
                        !req.isRpc -> StubResponse()
                        req.rpcRoute == list -> req.answerWith("[]")
                        req.rpcRoute == committees -> req.answerWith("[]")
                        else -> req.answerWith("null")
                    }
                },
            ) { _ ->
                mountedForm("motions-empty") { root, element ->
                    renderMotionsScreen(root)
                    awaitUntil("the sentence") { element().shows("Noch keine Anträge") }
                    assertTrue(element().allOf("table").isEmpty())
                }
            }
        }

    // ---- detail -----------------------------------------------------------------------------------------------------------

    private suspend fun detailRoutes(
        shown: MotionDto,
        respondFor: (String) -> String? = { null },
    ): (RecordedRequest) -> StubResponse {
        val get = routeOf { rpcService<IGovernanceService>().getMotion("m") }
        val committees = routeOf { rpcService<IGovernanceService>().listCommittees(false) }
        val members = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", true) }
        val motions = routeOf { rpcService<IGovernanceService>().listMotions(null, null) }
        return { req ->
            when {
                !req.isRpc -> StubResponse()
                req.rpcRoute == get -> req.answerWith(jsonOf(MotionDto.serializer(), shown))
                req.rpcRoute == committees -> req.answerWith(jsonOf(ListSerializer(CommitteeDto.serializer()), listOf(committee)))
                req.rpcRoute == members -> req.answerWith("[]")
                req.rpcRoute == motions -> req.answerWith("[]")
                else -> req.answerWith(respondFor(req.rpcRoute) ?: "null")
            }
        }
    }

    @Test
    fun detail_deepLink_hasTheBackButtonAboveTheTitle_andAQuietWithdrawButtonAtTheEnd(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            val shown = motion("m-1", "Haushalt 2027")
            val respond = detailRoutes(shown)
            withFetchStub(respond = { req -> respond(req) }) { _ ->
                mountedForm("motion-detail") { root, element ->
                    renderMotionDetailScreen(root, "m-1")
                    awaitUntil("the button") { element().allOf("button").any { it.textContent?.trim() == "Antrag zurückziehen" } }
                    val h1 = assertNotNull(element().querySelector("h1"))
                    assertEquals("Antrag: Haushalt 2027", h1.textContent?.trim())
                    val back = element().buttonNamed("Zur Übersicht")
                    assertTrue(back.compareDocumentPosition(h1).toInt() and 4 != 0, "back button above the title")
                    val withdraw = element().buttonNamed("Antrag zurückziehen")
                    assertFalse(withdraw.className.contains("btn-outline-danger") || withdraw.className.contains("btn-danger"))
                    assertTrue(withdraw.className.contains("btn-outline-secondary"))
                    assertTrue(h1.compareDocumentPosition(withdraw).toInt() and 4 != 0, "after the title")
                    assertFalse(element().shows("Antrag oben auswählen"))
                }
            }
        }

    @Test
    fun detail_unknownId_leavesAHint_notABlankPage(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val get = routeOf { rpcService<IGovernanceService>().getMotion("m") }
            withFetchStub(respond = { req ->
                if (req.isRpc && req.rpcRoute == get) StubResponse(status = 500) else StubResponse()
            }) { _ ->
                mountedForm("motion-detail-unknown") { root, element ->
                    renderMotionDetailScreen(root, "weg")
                    awaitUntil("the hint") { element().shows("Dieser Antrag wurde nicht gefunden.") }
                    element().buttonNamed("Zur Übersicht")
                }
            }
        }
}
