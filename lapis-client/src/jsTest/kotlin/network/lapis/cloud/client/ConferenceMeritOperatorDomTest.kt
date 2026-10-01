package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import io.kvision.panel.Root
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.27 -- the operator side of a meritocratic vote in the room (open a Yes/No vote, close a running one) and its pure gate.
 * Mounted for real (Karma/Chrome) with the merit RPC seam replaced by a recording world; one test pins the fixed `YES`/`NO` option
 * labels on the wire (the server decides the outcome by the label text).
 */
class ConferenceMeritOperatorDomTest {
    // ── the pure gate ─────────────────────────────────────────────────────────────────────────────────────────

    private fun motion(
        id: String = "m1",
        status: MotionStatus = MotionStatus.SCHEDULED,
        meetingId: String? = "s1",
        committeeId: String = "c1",
        title: String = "Antrag Haushalt",
    ): MotionDto = motionDto(committeeId).copy(id = id, status = status, meetingId = meetingId, title = title)

    private fun voteDto(
        status: VoteStatus,
        id: String = "v1",
        motionId: String = "m1",
    ) = VoteDto(
        id = id,
        motionId = motionId,
        meetingId = "s1",
        title = "Haushalt",
        status = status,
        options = emptyList(),
        winnerOptionId = null,
        secondPriceLtr = null,
        openedById = "chair",
        openedByDisplayName = "Clara Chair",
        openedAt = ELECTION_AT,
        closedAt = null,
        resolutionId = null,
    )

    private fun consensus(status: SystemicConsensusStatus) =
        SystemicConsensusDto(
            id = "sk1",
            motionId = "m1",
            meetingId = "s1",
            title = "SK",
            status = status,
            secret = true,
            scaleMax = 10,
            aggregation = SystemicConsensusAggregation.MEAN,
            tiebreakRule = SystemicConsensusTiebreakRule.REPEAT,
            groupConflictViableThreshold = 0.5.toDecimal(),
            groupConflictWarnThreshold = 0.5.toDecimal(),
            statusQuoOptionAuto = false,
            bindingness = SystemicConsensusBindingness.ADVISORY,
            maxRounds = 1,
            round = 1,
            winnerOptionId = null,
            openedById = "chair",
            openedByDisplayName = "Clara Chair",
            openedAt = ELECTION_AT,
            ratingOpenedAt = null,
            ratingClosedAt = null,
            tallyRunAt = null,
            resolutionId = null,
            options = emptyList(),
            tooManyOptionsWarning = false,
        )

    private fun canOpen(
        motion: MotionDto = motion(),
        roomMeetingId: String? = "s1",
        canRecord: Boolean = true,
        votes: List<VoteDto> = emptyList(),
        elections: List<ElectionDto> = emptyList(),
        consensuses: List<SystemicConsensusDto> = emptyList(),
        amendments: List<MotionDto> = emptyList(),
    ) = meritCanOpen(motion, roomMeetingId, canRecord, votes, elections, consensuses, amendments)

    @Test
    fun meritCanOpen_mirrorsOpenVoteAndCloseVote_everyExclusionOnItsOwn() {
        assertTrue(canOpen(), "a scheduled motion of this Sitzung, nothing in the way")
        assertFalse(canOpen(canRecord = false), "the caller may not record for this committee")
        assertFalse(canOpen(roomMeetingId = null), "the room is not bound")
        assertFalse(canOpen(roomMeetingId = "s2"), "the motion belongs to another Sitzung")
        assertFalse(canOpen(motion = motion(meetingId = null)))
        MotionStatus.entries.filter { it != MotionStatus.SCHEDULED }.forEach {
            assertFalse(canOpen(motion = motion(status = it)), "$it is not SCHEDULED")
        }
        assertFalse(canOpen(votes = listOf(voteDto(VoteStatus.OPEN))), "a vote is already running")
        assertFalse(canOpen(votes = listOf(voteDto(VoteStatus.CLOSED))), "a vote already decided it")
        assertTrue(canOpen(votes = listOf(voteDto(VoteStatus.ABORTED))), "an aborted vote does not block a new one")
        ElectionStatus.entries.forEach { status ->
            val blocked = status != ElectionStatus.TALLIED && status != ElectionStatus.ABORTED
            assertEquals(!blocked, canOpen(elections = listOf(election(status = status))), "election $status")
        }
        SystemicConsensusStatus.entries.forEach { status ->
            assertEquals(status == SystemicConsensusStatus.ABORTED, canOpen(consensuses = listOf(consensus(status))), "consensus $status")
        }
        assertFalse(canOpen(amendments = listOf(motion(id = "a1", status = MotionStatus.SUBMITTED))), "closeVote would refuse later")
        assertTrue(canOpen(amendments = listOf(motion(id = "a1", status = MotionStatus.RESOLVED))), "a decided amendment does not block")
    }

    // ── the panel ─────────────────────────────────────────────────────────────────────────────────────────────

    private class World {
        var motions: List<MotionDto> = emptyList()
        var roster: List<CommitteeMembershipDto> = emptyList()
        var votes: Map<String, List<VoteDto>> = emptyMap()
        var reads = 0
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var nudges = 0
        var refreshes = 0
        var openFails: Throwable? = null

        fun rpc(): MeritOperatorRpc =
            MeritOperatorRpc(
                listScheduledMotions = {
                    reads++
                    motions
                },
                getMotion = { id -> motions.first { it.id == id } },
                listCommitteeMembers = { roster },
                listVotesFor = { votes[it].orEmpty() },
                listElectionsFor = { emptyList() },
                listConsensusesFor = { emptyList() },
                listAmendments = { emptyList() },
                openVote = {
                    openFails?.let { e -> throw e }
                    opened += it
                },
                closeVote = { closed += it },
            )
    }

    private fun ctx(
        boardOrAdmin: Boolean = false,
        moderate: Boolean = false,
    ) = OperatorContext(currentMemberId = "me", isBoardOrAdmin = boardOrAdmin, canModerateRoom = moderate, roomMeetingId = { "s1" })

    private suspend fun <T> withMeritOperator(
        id: String,
        world: World,
        context: OperatorContext,
        open: Boolean = true,
        block: suspend (HTMLElement, ConferenceVotePanelHandle) -> T,
    ): T =
        mountedForm(id) { root: Root, element ->
            val handle =
                renderConferenceVotePanel(
                    parent = root,
                    onLockChanged = {},
                    onCloseRequested = {},
                    onBoothExited = {},
                    operatorContext = context,
                    sendNudge = { world.nudges++ },
                    onRefreshRoom = { world.refreshes++ },
                    scheduler = OperatorFakeScheduler(),
                    // the election side of the panel reads nothing here
                    operatorRpc = OperatorRpc(listPrepared = { emptyList() }),
                    meritRpc = world.rpc(),
                )
            handle.setOpen(open)
            try {
                block(element(), handle)
            } finally {
                handle.dispose()
            }
        }

    private fun answerOf(vararg ballots: network.lapis.cloud.shared.domain.RoomBallotDto) =
        voteRoomReduce(ConferenceVoteRoomState(), roomState(*ballots))

    private val openVoteBallot = roomBallot("v1", kind = RoomBallotKind.VOTE, secret = false, motionId = "m1", title = "Haushalt")

    @Test
    fun aCommitteeChair_andBoardOrAdmin_getTheOpenButton_aMemberAndAMereModeratorGetNothing(): Promise<Unit> =
        formTest {
            val chairWorld =
                World().also {
                    it.motions = listOf(motion(id = "123e4567-e89b-12d3-a456-426614174001"))
                    it.roster = listOf(rosterEntry("me", CommitteeRole.CHAIR))
                }
            withMeritOperator("merit-op-chair", chairWorld, ctx()) { el, handle ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("the open button", 2000) { el.hasButton("Ja/Nein-Abstimmung eröffnen") }
                assertTrue(el.flatText().contains("Antrag Haushalt"))
                assertTrue(el.flatText().contains("Vorbereitete Anträge"))
                val other = el.allOf("a").firstOrNull { it.flatText().contains("Andere Optionen") }
                assertNotNull(other, "no link: ${el.allOf("a").map { it.outerHTML }}")
                assertEquals("_blank", other.getAttribute("target"))
                assertTrue(other.getAttribute("rel").orEmpty().contains("noopener"))
                assertEquals("#/motions/123e4567-e89b-12d3-a456-426614174001", other.getAttribute("href"))
            }
            val boardWorld = World().also { it.motions = listOf(motion()) }
            withMeritOperator("merit-op-board", boardWorld, ctx(boardOrAdmin = true)) { el, handle ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("the open button", 2000) { el.hasButton("Ja/Nein-Abstimmung eröffnen") }
            }
            val plain =
                World().also {
                    it.motions = listOf(motion())
                    it.roster = listOf(rosterEntry("me", CommitteeRole.MEMBER))
                }
            withMeritOperator("merit-op-plain", plain, ctx(moderate = true)) { el, handle ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("the read happened", 2000) { plain.reads >= 1 }
                delay(150)
                assertFalse(el.hasButton("Ja/Nein-Abstimmung eröffnen"), "the room moderation is not the right to open a vote")
                assertFalse(el.flatText().contains("Vorbereitete Anträge"))
            }
        }

    @Test
    fun openingAYesNoVote_sendsTheMotion_nudgesOnce_andReloadsTheRoom(): Promise<Unit> =
        formTest {
            val world = World().also { it.motions = listOf(motion()) }
            withMeritOperator("merit-op-open", world, ctx(boardOrAdmin = true)) { el, handle ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("the open button", 2000) { el.hasButton("Ja/Nein-Abstimmung eröffnen") }
                val button = el.buttonNamed("Ja/Nein-Abstimmung eröffnen")
                button.click()
                button.click()
                awaitUntil("opened", 2000) { world.opened.isNotEmpty() }
                awaitUntil("nudged", 2000) { world.nudges >= 1 }
                delay(150)
                assertEquals(listOf("m1"), world.opened, "a double click is one vote")
                assertEquals(1, world.nudges)
                assertTrue(world.refreshes >= 1)
            }
        }

    @Test
    fun theWireOfOpenVote_carriesTheFixedEnglishLabels_neverTranslatedOnes(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().openVote(VoteOpenInput("m", listOf("a", "b"))) }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else {
                        when (request.rpcRoute) {
                            route -> request.answerWith("null")
                            else -> request.answerWith("[]")
                        }
                    }
                },
            ) { calls ->
                mountedForm("merit-op-wire") { root, element ->
                    val handle =
                        renderConferenceVotePanel(
                            parent = root,
                            onLockChanged = {},
                            onCloseRequested = {},
                            onBoothExited = {},
                            operatorContext = ctx(boardOrAdmin = true),
                            operatorRpc = OperatorRpc(listPrepared = { emptyList() }),
                            meritRpc =
                                MeritOperatorRpc(
                                    listScheduledMotions = { listOf(motion()) },
                                    listVotesFor = { emptyList() },
                                    listElectionsFor = { emptyList() },
                                    listConsensusesFor = { emptyList() },
                                    listAmendments = { emptyList() },
                                ),
                        )
                    handle.setOpen(true)
                    try {
                        handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                        awaitUntil("the open button", 2000) { element().hasButton("Ja/Nein-Abstimmung eröffnen") }
                        element().buttonNamed("Ja/Nein-Abstimmung eröffnen").click()
                        awaitUntil("openVote on the wire", 2000) { calls.toRoute(route).size == 1 }
                        val input = calls.singleCall(route).rpcParam(0)
                        assertEquals("m1", input.motionId as String)
                        // the labels are the server's own default (`YES`,`NO`) and are left off the wire, or sent as exactly these literals
                        val labels = input.optionLabels
                        assertTrue(
                            jsTypeOf(labels) == "undefined" || JSON.stringify(labels) == """["YES","NO"]""",
                            "labels on the wire: ${JSON.stringify(labels)}",
                        )
                    } finally {
                        handle.dispose()
                    }
                }
            }
        }

    @Test
    fun aFailedOpen_showsTheFixedConflictNote_andNeverTheServersText(): Promise<Unit> =
        formTest {
            val world =
                World().also {
                    it.motions = listOf(motion())
                    it.openFails =
                        network.lapis.cloud.shared.rpc
                            .ConflictException("Motion m1 already has a Vote (member 9d8c)")
                }
            withMeritOperator("merit-op-conflict", world, ctx(boardOrAdmin = true)) { el, handle ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("the open button", 2000) { el.hasButton("Ja/Nein-Abstimmung eröffnen") }
                el.buttonNamed("Ja/Nein-Abstimmung eröffnen").click()
                awaitUntil("the note", 2000) { el.flatText().contains("Der Stand hat sich geändert und wurde neu geladen.") }
                assertFalse(el.flatText().contains("9d8c"))
                assertEquals(0, world.nudges, "nothing happened, nobody is nudged")
                assertTrue(world.refreshes >= 1, "the state is reloaded to find out what happened")
            }
        }

    @Test
    fun atMostFiveCandidatesAreOffered_theRestIsPointedToTheMotionPage(): Promise<Unit> =
        formTest {
            val world = World().also { w -> w.motions = (1..7).map { motion(id = "m$it", title = "Antrag $it") } }
            withMeritOperator("merit-op-cap", world, ctx(boardOrAdmin = true)) { el, handle ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("the section", 2000) { el.flatText().contains("Weitere Anträge auf der Antragsseite") }
                assertEquals(5, el.allOf("button").count { it.textContent?.trim() == "Ja/Nein-Abstimmung eröffnen" })
            }
        }

    @Test
    fun aClosedPanel_readsNothing_andOpeningItStartsTheRead(): Promise<Unit> =
        formTest {
            val world = World().also { it.motions = listOf(motion()) }
            withMeritOperator("merit-op-closed", world, ctx(boardOrAdmin = true), open = false) { el, handle ->
                handle.panel.hide()
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                delay(150)
                assertEquals(0, world.reads, "a hidden panel reads nothing")
                handle.setOpen(true)
                awaitUntil("the open button", 2000) { el.hasButton("Ja/Nein-Abstimmung eröffnen") }
            }
        }

    @Test
    fun closingARunningVote_asksFirst_focusesCancel_andSendsExactlyOneCloseWithOneNudge(): Promise<Unit> =
        formTest {
            val world = World().also { it.motions = listOf(motion()) }
            withMeritOperator("merit-op-close", world, ctx(boardOrAdmin = true)) { el, handle ->
                handle.apply(answerOf(openVoteBallot))
                awaitUntil("the close button", 2000) { el.hasButton("Abstimmung schließen") }
                // the panel's own header button has the same name; the card's one is the primary action
                val close = el.allOf("button.btn-primary").first { it.textContent?.trim() == "Abstimmung schließen" }
                close.click()
                var modal = lastOpenModal()
                assertTrue(
                    modal.flatText().contains(
                        "Abstimmung schließen? Danach sind keine Gebote mehr möglich, das Ergebnis wird sofort berechnet.",
                    ),
                    modal.flatText(),
                )
                awaitUntil("focus on Abbrechen", 1000) { kotlinx.browser.document.activeElement === modal.buttonNamed("Abbrechen") }
                modal.buttonNamed("Abbrechen").click()
                delay(100)
                assertEquals(0, world.closed.size, "cancelling sends nothing")

                close.click()
                modal = lastOpenModal()
                val confirm = modal.buttonNamed("Abstimmung schließen")
                confirm.click()
                confirm.click()
                awaitUntil("closed", 2000) { world.closed.isNotEmpty() }
                awaitUntil("nudged", 2000) { world.nudges >= 1 }
                delay(150)
                assertEquals(listOf("v1"), world.closed)
                assertEquals(1, world.nudges)
                assertTrue(world.refreshes >= 1)
            }
        }

    @Test
    fun noCloseButtonForSomebodyWhoMayNotRecord_andNeverOnAFinishedVote(): Promise<Unit> =
        formTest {
            val plain = World().also { it.motions = listOf(motion()) }
            plain.roster = listOf(rosterEntry("me", CommitteeRole.MEMBER))
            withMeritOperator("merit-op-noclose", plain, ctx(moderate = true)) { el, handle ->
                handle.apply(answerOf(openVoteBallot))
                delay(200)
                assertEquals(0, el.allOf("button.btn-primary").count { it.textContent?.trim() == "Abstimmung schließen" })
            }
            val board = World().also { it.motions = listOf(motion()) }
            withMeritOperator("merit-op-finished", board, ctx(boardOrAdmin = true)) { el, handle ->
                handle.apply(
                    answerOf(
                        roomBallot(
                            "v1",
                            network.lapis.cloud.shared.domain.RoomBallotStatus.DECIDED,
                            kind = RoomBallotKind.VOTE,
                            secret = false,
                            motionId = "m1",
                        ),
                    ),
                )
                delay(200)
                assertEquals(0, el.allOf("button.btn-primary").count { it.textContent?.trim() == "Abstimmung schließen" })
            }
        }
}
