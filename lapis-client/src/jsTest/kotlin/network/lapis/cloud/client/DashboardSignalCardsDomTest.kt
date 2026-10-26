package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.224 -- "Für Sie": the cards of the dashboard, driven with a fake RPC and a fake tab visibility. Replaces the old "next event" card test:
 * the next event is a signal now.
 */
class DashboardSignalCardsDomTest {
    private fun session(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Person",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = status,
        aiAssistantEnabled = true,
        mcpEnabled = true,
    )

    private fun signal(
        module: String,
        reason: String,
        count: Int = 1,
        priority: Int = DashboardPriority.ACTION,
        params: Map<String, String> = emptyMap(),
        objectId: String? = null,
        deadline: String? = null,
    ) = DashboardSignalDto(
        moduleKey = module,
        reasonKey = reason,
        count = count,
        params = params,
        objectId = objectId,
        priority = priority,
        deadline = deadline,
    )

    private class FakeRpc(
        private val answer: suspend () -> List<DashboardSignalDto>,
    ) : DashboardSignalsRpc {
        var calls = 0

        override suspend fun signals(): List<DashboardSignalDto> {
            calls++
            return answer()
        }
    }

    private class FakeVisibility : DashboardVisibility {
        var visible = true
        var now = 1_000_000.0
        val listeners = mutableListOf<(Event) -> Unit>()

        override fun addListener(listener: (Event) -> Unit) {
            listeners += listener
        }

        override fun removeListener(listener: (Event) -> Unit) {
            listeners -= listener
        }

        override fun isVisible() = visible

        override fun nowMs() = now

        fun fire() = listeners.toList().forEach { it(Event("visibilitychange")) }
    }

    private fun HTMLElement.cards(): List<HTMLElement> =
        (0 until querySelectorAll(".lapis-signal-card").length).map { querySelectorAll(".lapis-signal-card").item(it) as HTMLElement }

    private fun run(
        id: String,
        rpc: DashboardSignalsRpc,
        session: SessionInfoDto = session(),
        visibility: FakeVisibility = FakeVisibility(),
        block: suspend (HTMLElement, FakeVisibility, io.kvision.panel.Root) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            withFetchStub {
                mountedForm(id) { root, element ->
                    renderDashboardScreen(root, OnlineCountsRpc { error("unused") }, WindowOnlineCountsScheduler, rpc, visibility)
                    block(element(), visibility, root)
                }
            }
        }

    @Test
    fun theCardsAreSorted_urgentFirst_thenEarlierDeadline_thenNoDeadline(): Promise<Unit> {
        val rpc =
            FakeRpc {
                listOf(
                    signal(
                        DashboardModuleKeys.MEETING,
                        DashboardReasonKeys.MEETING_UPCOMING,
                        priority = DashboardPriority.INFO,
                        params = mapOf("date" to "2030-06-15"),
                    ),
                    signal(
                        DashboardModuleKeys.CONTRIBUTIONS,
                        DashboardReasonKeys.CONTRIBUTIONS_OVERDUE,
                        priority = DashboardPriority.URGENT,
                    ),
                    signal(
                        DashboardModuleKeys.AUCTION,
                        DashboardReasonKeys.AUCTION_OUTBID,
                        priority = DashboardPriority.URGENT,
                        deadline = "2030-06-11T12:00:00",
                    ),
                    signal(
                        DashboardModuleKeys.TENDER,
                        DashboardReasonKeys.TENDER_DEADLINE_SOON,
                        priority = DashboardPriority.URGENT,
                        deadline = "2030-06-12T12:00:00",
                    ),
                    signal(DashboardModuleKeys.SURVEY, DashboardReasonKeys.SURVEY_NOT_ANSWERED),
                )
            }
        return run("signals-sorted", rpc) { el, _, _ ->
            awaitUntil("the cards appear") { el.cards().size == 5 }
            val hrefs = el.cards().map { it.getAttribute("href")!!.removePrefix("#") }
            assertEquals(
                listOf(Routes.AUCTION, Routes.TENDERS, Routes.CONTRIBUTIONS, Routes.POLLS, Routes.MEETINGS),
                hrefs,
            )
            assertTrue(
                el
                    .cards()
                    .first()
                    .classList
                    .contains("lapis-signal-card--urgent"),
            )
            assertFalse(
                el
                    .cards()
                    .last()
                    .classList
                    .contains("lapis-signal-card--urgent"),
            )
        }
    }

    @Test
    fun theBadge_showsOnlyForMoreThanOneObject_withAnAccessibleCount(): Promise<Unit> {
        val rpc =
            FakeRpc {
                listOf(
                    signal(DashboardModuleKeys.AUCTION, DashboardReasonKeys.AUCTION_OUTBID, count = 3),
                    signal(DashboardModuleKeys.SURVEY, DashboardReasonKeys.SURVEY_NOT_ANSWERED),
                )
            }
        return run("signals-badge", rpc) { el, _, _ ->
            awaitUntil("the cards appear") { el.cards().isNotEmpty() }
            val outbid = el.cards().first { it.getAttribute("href") == "#${Routes.AUCTION}" }
            val badge = assertNotNull(outbid.querySelector(".badge") as HTMLElement?)
            assertEquals("3", badge.textContent?.trim())
            assertEquals("3 Einträge", badge.getAttribute("aria-label"))
            val survey = el.cards().first { it.getAttribute("href") == "#${Routes.POLLS}" }
            assertNull(survey.querySelector(".badge"), "one object: no badge")
        }
    }

    @Test
    fun anUnknownReason_unsafeParameters_andAModuleOutsideTheMenu_arePrinted_never(): Promise<Unit> {
        val rpc =
            FakeRpc {
                listOf(
                    signal(DashboardModuleKeys.AUCTION, "auction.fromTheFuture"),
                    signal(
                        DashboardModuleKeys.SURVEY,
                        DashboardReasonKeys.SURVEY_NOT_ANSWERED,
                        params = mapOf("date" to "Meine Geheimnisse"),
                    ),
                    // a member has no treasury menu: the card would lead nowhere
                    signal(DashboardModuleKeys.TREASURY, DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING),
                    signal(DashboardModuleKeys.AUCTION, DashboardReasonKeys.AUCTION_WON),
                )
            }
        return run("signals-filtered", rpc) { el, _, _ ->
            awaitUntil("the one valid card appears") { el.cards().size == 1 }
            assertEquals("#${Routes.AUCTION}", el.cards().single().getAttribute("href"))
            delay(100)
            assertEquals(1, el.cards().size)
        }
    }

    @Test
    fun theTreasuryCard_showsForATreasurer_whoseMenuHasTheDunningList(): Promise<Unit> =
        run(
            "signals-treasurer",
            FakeRpc { listOf(signal(DashboardModuleKeys.TREASURY, DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING, count = 4)) },
            session(role = AccountRole.TREASURER),
        ) { el, _, _ ->
            awaitUntil("the card appears") { el.cards().size == 1 }
            assertEquals("#${Routes.DUNNING_CASES}", el.cards().single().getAttribute("href"))
        }

    @Test
    fun theRoute_isBuiltFromAFixedTable_aWrongIdLeadsToTheList_aRightIdToTheDetail_andAnAuctionHasNoDetail(): Promise<Unit> {
        val id = "0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val rpc =
            FakeRpc {
                listOf(
                    signal(DashboardModuleKeys.ELECTION, DashboardReasonKeys.ELECTION_NOT_VOTED, objectId = id),
                    signal(DashboardModuleKeys.SURVEY, DashboardReasonKeys.SURVEY_NOT_ANSWERED, objectId = "javascript:alert(1)"),
                    signal(DashboardModuleKeys.AUCTION, DashboardReasonKeys.AUCTION_OUTBID, objectId = id),
                )
            }
        return run("signals-routes", rpc) { el, _, _ ->
            awaitUntil("the cards appear") { el.cards().size == 3 }
            val hrefs = el.cards().map { it.getAttribute("href")!! }.toSet()
            assertEquals(setOf("#/elections/$id", "#${Routes.POLLS}", "#${Routes.AUCTION}"), hrefs)
        }
    }

    @Test
    fun whileLoading_oneLineStands_noSkeleton_andThenTheEmptySentence(): Promise<Unit> {
        val gate = CompletableDeferred<List<DashboardSignalDto>>()
        return run("signals-loading", FakeRpc { gate.await() }) { el, _, _ ->
            val status = assertNotNull(el.querySelector(".lapis-dashboard-signals__status") as HTMLElement?)
            assertTrue(status.textContent.orEmpty().contains("Wird geladen"))
            assertNull(el.querySelector(".placeholder"), "no skeleton")
            gate.complete(emptyList())
            awaitUntil("the empty sentence") { status.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
            assertEquals(0, el.cards().size)
        }
    }

    @Test
    fun aFailure_endsAsTheEmptySentence_withoutAToast(): Promise<Unit> =
        run("signals-failure", FakeRpc { error("boom") }) { el, _, _ ->
            awaitUntil("the empty sentence") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
            assertEquals(0, el.ownerDocument!!.querySelectorAll(".toast").length, "no toast")
            assertEquals(0, el.cards().size)
        }

    @Test
    fun anAnswerAfterLeavingTheScreen_changesNothing(): Promise<Unit> {
        val gate = CompletableDeferred<List<DashboardSignalDto>>()
        return run("signals-late", FakeRpc { gate.await() }) { el, _, root ->
            val grid = assertNotNull(el.querySelector(".lapis-dashboard-signals") as HTMLElement?)
            root.removeAll()
            gate.complete(listOf(signal(DashboardModuleKeys.AUCTION, DashboardReasonKeys.AUCTION_OUTBID)))
            delay(150)
            assertEquals(0, grid.querySelectorAll(".lapis-signal-card").length)
        }
    }

    @Test
    fun theTabBecomingVisible_asksAgainOnlyAfterAMinute_neverWhileHidden_andLeavesNoListenerBehind(): Promise<Unit> {
        val rpc = FakeRpc { listOf(signal(DashboardModuleKeys.AUCTION, DashboardReasonKeys.AUCTION_OUTBID)) }
        return run("signals-visibility", rpc) { el, visibility, root ->
            awaitUntil("the first answer") { el.cards().size == 1 }
            assertEquals(1, rpc.calls)
            assertEquals(1, visibility.listeners.size)
            visibility.now += 30_000
            visibility.fire()
            delay(50)
            assertEquals(1, rpc.calls, "30 s: not again")
            visibility.now += 40_000
            visibility.visible = false
            visibility.fire()
            delay(50)
            assertEquals(1, rpc.calls, "hidden: not asked")
            visibility.visible = true
            visibility.fire()
            awaitUntil("the second request") { rpc.calls == 2 }
            root.removeAll()
            awaitUntil("the listener is released") { visibility.listeners.isEmpty() }
        }
    }

    @Test
    fun aMarkerInsideAParameter_isInert_theCardIsSimplyNotDrawn(): Promise<Unit> =
        run(
            "signals-marker",
            FakeRpc {
                listOf(
                    signal(
                        DashboardModuleKeys.SURVEY,
                        DashboardReasonKeys.SURVEY_NOT_ANSWERED,
                        params =
                            mapOf(
                                "date" to KV_I18N_MARKER + "x",
                            ),
                    ),
                )
            },
        ) { el, _, _ ->
            awaitUntil("the empty sentence") { el.textContent.orEmpty().contains("Im Moment gibt es nichts Neues für Sie.") }
            assertEquals(0, el.cards().size)
        }
}
