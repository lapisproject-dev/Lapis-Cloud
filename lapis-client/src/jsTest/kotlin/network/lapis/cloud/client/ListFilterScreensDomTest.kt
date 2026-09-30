package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.BoardMembershipDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.rpc.IBoardMembershipService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IPoliticianService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The name filter ([listFilterField]) wired into the four screens whose person lists are loaded completely: committee roster,
 * board roster, mailing-list subscribers and politician profiles. [ListFilterDomTest] covers the component; this pins the
 * wiring -- the filter narrows the REAL rendered list, survives a reload of the list, and says so when nothing matches.
 */
class ListFilterScreensDomTest {
    private val filterLabel = "Nach Name filtern"
    private val start = LocalDate(2026, 1, 1)
    private val at = LocalDateTime(2026, 1, 1, 12, 0)

    private suspend fun narrow(
        element: HTMLElement,
        text: String,
    ) {
        element.typeInto(filterLabel, text)
        delay(ListFilter.DEBOUNCE_MS + 150L)
    }

    private fun HTMLElement.shows(name: String): Boolean = textContent.orEmpty().contains(name)

    private fun stub(vararg answers: Pair<String, String>): (RecordedRequest) -> StubResponse {
        val byRoute = answers.toMap()
        return { request ->
            if (!request.isRpc) StubResponse() else request.answerWith(byRoute[request.rpcRoute] ?: "[]")
        }
    }

    // ---- board roster ---------------------------------------------------------------------------------------------------

    @Test
    fun boardRoster_isFilteredByName_andTheFilterSurvivesAReload(): Promise<Unit> =
        formTest {
            val listBoard = routeOf { rpcService<IBoardMembershipService>().listCurrentBoard() }
            var board =
                listOf(
                    BoardMembershipDto("b1", "m1", "Anna Berg", CommitteeRole.CHAIR, start, null),
                    BoardMembershipDto("b2", "m2", "Hans Müller", CommitteeRole.MEMBER, start, null),
                    BoardMembershipDto("b3", "m3", "Karl Roth", CommitteeRole.SECRETARY, start, null),
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == listBoard -> request.answerWith(jsonOf(ListSerializer(BoardMembershipDto.serializer()), board))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("filter-board") { root, element ->
                    renderBoardMembershipScreen(root)
                    awaitUntil("the board is shown", timeoutMs = 1500) { element().shows("Karl Roth") }
                    narrow(element(), "mueller")
                    assertTrue(element().shows("Hans Müller"))
                    assertFalse(element().shows("Anna Berg"), "a non-matching member is gone")
                    assertFalse(element().shows("Karl Roth"))
                    narrow(element(), "zzz")
                    assertTrue(element().shows("Kein Eintrag passt zu \"zzz\"."))
                    narrow(element(), "")
                    assertTrue(element().shows("Anna Berg") && element().shows("Karl Roth"))
                }
            }
        }

    // ---- subscribers ----------------------------------------------------------------------------------------------------

    @Test
    fun subscribers_areFilteredByName(): Promise<Unit> =
        formTest {
            val listSubscribers = routeOf { rpcService<IMailingService>().listSubscribers("list-1") }
            val subscribers =
                listOf("Anna Berg", "Hans Müller", "Johanna Klein").mapIndexed { i, name ->
                    MailingListSubscriptionDto("s$i", "list-1", "m$i", name, at, null)
                }
            withFetchStub(
                respond =
                    stub(listSubscribers to jsonOf(ListSerializer(MailingListSubscriptionDto.serializer()), subscribers)),
            ) { _ ->
                mountedForm("filter-subscribers") { root, element ->
                    renderMailingListDetail(
                        root,
                        MailingListDto("list-1", "Newsletter", null, "admin", 3, false),
                        refreshSelfService = {},
                    )
                    awaitUntil("the subscribers are shown", timeoutMs = 1500) { element().shows("Johanna Klein") }
                    narrow(element(), "anna")
                    assertTrue(element().shows("Anna Berg") && element().shows("Johanna Klein"))
                    assertFalse(element().shows("Hans Müller"))
                    assertEquals("2 von 3", element().querySelector(".lapis-list-filter [role=status]")?.textContent?.trim())
                }
            }
        }

    // ---- committee roster -----------------------------------------------------------------------------------------------

    @Test
    fun committeeRoster_isFilteredByName(): Promise<Unit> =
        formTest {
            val listMembers = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c1", activeOnly = true) }
            val memberships =
                listOf("Anna Berg", "Hans Müller", "Karl Roth").mapIndexed { i, name ->
                    CommitteeMembershipDto("cm$i", "c1", "m$i", name, CommitteeRole.MEMBER, start, null)
                }
            withFetchStub(
                respond = stub(listMembers to jsonOf(ListSerializer(CommitteeMembershipDto.serializer()), memberships)),
            ) { _ ->
                mountedForm("filter-committee") { root, element ->
                    val committee = CommitteeDto("c1", "Vorstand", CommitteeType.EXECUTIVE_BOARD, "", true, 50, at)
                    renderCommitteeRoster(root, committee, canManage = false)
                    awaitUntil("the roster is shown", timeoutMs = 1500) { element().shows("Karl Roth") }
                    narrow(element(), "roth")
                    assertTrue(element().shows("Karl Roth"))
                    assertFalse(element().shows("Anna Berg"))
                }
            }
        }

    // ---- politicians ----------------------------------------------------------------------------------------------------

    @Test
    fun politicians_areFilteredByName(): Promise<Unit> =
        formTest {
            val listPoliticians = routeOf { rpcService<IPoliticianService>().listPoliticians(false) }
            val profiles =
                listOf("Anna Berg", "Hans Müller").mapIndexed { i, name ->
                    PoliticianProfileDto(
                        id = "p$i",
                        memberId = "m$i",
                        displayName = name,
                        status = PoliticianProfileStatus.ACTIVE,
                        mandateText = null,
                        grantedAt = at,
                        grantedByDisplayName = "Admin",
                        revokedAt = null,
                        revokedByDisplayName = null,
                        memberTrustWeight = 1.0.toDecimal(),
                        memberLikeCount = 0,
                        memberDislikeCount = 0,
                        guestTrustWeight = 0.0.toDecimal(),
                        guestLikeCount = 0,
                        guestDislikeCount = 0,
                        combinedTrustWeight = 1.0.toDecimal(),
                    )
                }
            withFetchStub(
                respond = stub(listPoliticians to jsonOf(ListSerializer(PoliticianProfileDto.serializer()), profiles)),
            ) { _ ->
                mountedForm("filter-politicians") { root, element ->
                    renderPoliticianScreen(root)
                    awaitUntil("the profiles are shown", timeoutMs = 1500) { element().shows("Hans Müller") }
                    narrow(element(), "berg")
                    assertTrue(element().shows("Anna Berg"))
                    assertFalse(element().shows("Hans Müller"))
                }
            }
        }
}
