package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.26 -- the operator side of the room's voting panel and the secret-ballot wiring, mounted for real (Karma/Chrome) against the stubbed
 * election service. Time runs on a fake scheduler: the 5 s recheck and the 20 s / 60 s stages of a pausing stream are advanced by hand.
 */
class ConferenceVoteOperatorDomTest {
    private class Harness {
        val scheduler = OperatorFakeScheduler()
        val locks = mutableListOf<ConferenceVotingLock>()
        var nudges = 0
        var refreshes = 0
        var stopRequests = 0
        var nudgeFails = false
    }

    private fun ctx(
        boardOrAdmin: Boolean = false,
        moderate: Boolean = false,
    ) = OperatorContext(currentMemberId = "me", isBoardOrAdmin = boardOrAdmin, canModerateRoom = moderate, roomMeetingId = { "s1" })

    private suspend fun <T> withOperatorPanel(
        id: String,
        world: ElectionWorld,
        context: OperatorContext = ctx(),
        harness: Harness = Harness(),
        open: Boolean = true,
        block: suspend (HTMLElement, ConferenceVotePanelHandle, Harness, List<RecordedRequest>, ElectionRoutes) -> T,
    ): T {
        val routes = electionRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root: Root, element ->
                val handle =
                    renderConferenceVotePanel(
                        parent = root,
                        loadElection = { world.election },
                        onLockChanged = { harness.locks += it },
                        onCloseRequested = {},
                        onBoothExited = {},
                        operatorContext = context,
                        sendNudge = {
                            if (harness.nudgeFails) error("data channel gone")
                            harness.nudges++
                        },
                        onStopStreamRequested = if (context.canModerateRoom) ({ harness.stopRequests++ }) else null,
                        onRefreshRoom = { harness.refreshes++ },
                        scheduler = harness.scheduler,
                    )
                handle.setOpen(open)
                try {
                    block(element(), handle, harness, calls, routes)
                } finally {
                    handle.dispose()
                    ConferenceReceiptGate.visible = false
                    ConferenceReceiptGate.casting = false
                }
            }
        }
    }

    private fun answerOf(vararg ballots: RoomBallotDto) = voteRoomReduce(ConferenceVoteRoomState(), roomState(*ballots))

    private fun HTMLElement.chooseTile(text: String) {
        val label = assertNotNull(allOf("label").firstOrNull { it.textContent?.trim() == text }, "no tile '$text'")
        label.click()
    }

    private suspend fun HTMLElement.toReview() {
        buttonNamed("Zur Wahlkabine").click()
        awaitUntil("the booth", 2000) { hasButton("Weiter zur Prüfung") }
        chooseTile("Ja")
        buttonNamed("Weiter zur Prüfung").click()
        awaitUntil("review step", 2000) { hasButton("Stimme endgültig abgeben") }
    }

    private fun openBallot(
        secret: Boolean = true,
        voted: Boolean = false,
    ) = roomBallot("e1", RoomBallotStatus.OPEN, secret = secret, eligible = true, voted = voted)

    private val board = participation(isBoard = true, boardSize = 3, ballotCount = 3)

    // ── who gets controls ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anElectionBoardMember_getsTheCloseButtonAndACounter_aMemberWithoutARoleGetsNothingAndNoElectionRead(): Promise<Unit> =
        formTest {
            withOperatorPanel("op-roles", ElectionWorld(election(status = ElectionStatus.OPEN), board)) { el, handle, _, _, _ ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
                assertTrue(el.flatText().contains("Stimmen bisher: 3"))
                assertEquals(1, el.allOf("button.btn-primary").count { it.textContent?.trim() == "Abstimmung schließen" })
            }
            val plain = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            withOperatorPanel("op-plain", plain) { el, handle, _, calls, routes ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("participation read", 2000) { calls.toRoute(routes.getParticipation).isNotEmpty() }
                delay(150)
                assertEquals(0, calls.toRoute(routes.getElection).size, "no election read without a possible role")
                assertFalse(el.hasButton("Abstimmung schließen"))
                assertTrue(el.hasButton("Zur Wahlkabine"), "the member still votes")
            }
        }

    @Test
    fun aClosedPanel_readsNothing_andOpeningItStartsTheReads(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), board)
            withOperatorPanel("op-closed", world, open = false) { el, handle, _, calls, _ ->
                handle.panel.hide()
                handle.apply(answerOf(openBallot()))
                delay(150)
                assertEquals(0, calls.rpcCount, "a hidden panel reads nothing")
                handle.setOpen(true)
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
            }
        }

    @Test
    fun preparedElections_listOnlyTheSittingsOwn_andOnlyForSomebodyWhoCanOpenOne(): Promise<Unit> =
        formTest {
            val foreign = election(status = ElectionStatus.PREPARATION, title = "Fremde Wahl").copy(id = "e2", meetingId = "s2")
            val world =
                ElectionWorld(
                    election(status = ElectionStatus.PREPARATION),
                    board,
                    elections = listOf(election(status = ElectionStatus.PREPARATION), foreign),
                )
            withOperatorPanel("op-prepared", world) { el, handle, _, _, _ ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("prepared section", 2000) { el.flatText().contains("Vorbereitete Wahlen") }
                assertTrue(el.flatText().contains("Vorstandswahl 2026"))
                assertFalse(el.flatText().contains("Fremde Wahl"), "an election of another Sitzung is not listed")
                assertEquals(1, el.allOf("button").count { it.textContent?.trim() == "Abstimmung öffnen" })
                val link = assertNotNull(el.querySelector("a[href='#/elections']") as? HTMLElement, "the preparation link")
                assertEquals("_blank", link.getAttribute("target"))
                assertTrue(link.getAttribute("rel").orEmpty().contains("noopener"))
            }
            val noRole = world.also { it.participation = participation(isBoard = false) }
            withOperatorPanel("op-prepared-none", noRole) { el, handle, _, calls, routes ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("lists read", 2000) { calls.toRoute(routes.listElections).isNotEmpty() }
                delay(200)
                assertFalse(el.flatText().contains("Vorbereitete Wahlen"), "no role: no section")
                assertFalse(el.hasButton("Abstimmung öffnen"))
            }
        }

    @Test
    fun aTooSmallElectionBoard_showsTheOpenButtonDisabled_withItsReason(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(status = ElectionStatus.PREPARATION),
                    participation(isBoard = true, boardSize = 2),
                    elections = listOf(election(status = ElectionStatus.PREPARATION)),
                )
            withOperatorPanel("op-small", world) { el, handle, _, calls, routes ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("section", 2000) { el.flatText().contains("Der Wahlausschuss braucht mindestens 3 Mitglieder, es sind 2.") }
                assertTrue(el.isButtonDisabled("Abstimmung öffnen"))
                el.buttonNamed("Abstimmung öffnen").click()
                assertEquals(0, calls.toRoute(routes.openVoting).size)
            }
        }

    // ── opening ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun openingASecretElection_asksFirst_naming_theStreamAndTheRecording_andNeverSendsOnCancel(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(status = ElectionStatus.PREPARATION),
                    board,
                    elections = listOf(election(status = ElectionStatus.PREPARATION)),
                )
            withOperatorPanel("op-open-secret", world) { el, handle, h, calls, routes ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("open button", 2000) { el.hasButton("Abstimmung öffnen") }
                el.buttonNamed("Abstimmung öffnen").click()
                var modal = lastOpenModal()
                assertTrue(modal.flatText().contains("In diesem Raum läuft kein Live-Stream."), modal.flatText())
                assertTrue(modal.flatText().contains("Die Aufzeichnung der Sitzung wird nicht angehalten."))
                assertTrue(modal.flatText().contains("Das gilt auch für Live-Streams in anderen Räumen dieser Sitzung."))
                modal.buttonNamed("Abbrechen").click()
                assertEquals(0, calls.toRoute(routes.openVoting).size, "cancelling sends nothing")
                handle.onStreamState(ConferenceStreamStatus.LIVE, null)
                el.buttonNamed("Abstimmung öffnen").click()
                modal = lastOpenModal()
                assertTrue(modal.flatText().contains("wird für die Dauer der Abstimmung angehalten"), modal.flatText())
                modal.buttonNamed("Geheime Wahl öffnen").click()
                awaitUntil("open sent", 1500) { calls.toRoute(routes.openVoting).size == 1 }
                awaitUntil("nudged", 1500) { h.nudges == 1 }
                assertEquals("e1", calls.singleCall(routes.openVoting).rpcParam(0) as String)
                awaitUntil("room reloaded", 1500) { h.refreshes >= 1 }
            }
        }

    @Test
    fun openingANamedElection_needsNoDialog(): Promise<Unit> =
        formTest {
            val named = election(status = ElectionStatus.PREPARATION, secret = false)
            val world = ElectionWorld(named, board, elections = listOf(named))
            withOperatorPanel("op-open-named", world) { el, handle, h, calls, routes ->
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState()))
                awaitUntil("open button", 2000) { el.hasButton("Abstimmung öffnen") }
                el.buttonNamed("Abstimmung öffnen").click()
                awaitUntil("open sent", 1500) { calls.toRoute(routes.openVoting).size == 1 }
                assertEquals(1, h.nudges.also { awaitUntil("nudged", 1500) { h.nudges == 1 } })
            }
        }

    // ── closing, approving, counting ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theCloseDialog_namesTheCount_warnsAtZero_focusesCancel_andCancelSendsNothing(): Promise<Unit> =
        formTest {
            val zero = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = true, ballotCount = 0))
            withOperatorPanel("op-close-zero", zero) { el, handle, _, calls, routes ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
                el.buttonNamed("Abstimmung schließen").click()
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("Bisher 0 Stimmen abgegeben."), modal.flatText())
                assertTrue(modal.flatText().contains("Es wurde noch keine Stimme abgegeben."))
                awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                modal.buttonNamed("Abbrechen").click()
                assertEquals(0, calls.toRoute(routes.closeVoting).size)
            }
            val some = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = true, ballotCount = 1))
            withOperatorPanel("op-close-one", some) { el, handle, _, _, _ ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
                el.buttonNamed("Abstimmung schließen").click()
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("Bisher 1 Stimme abgegeben."), modal.flatText())
                assertFalse(modal.flatText().contains("Es wurde noch keine Stimme abgegeben."))
            }
        }

    @Test
    fun aDoubleClickOnTheCloseAction_sendsExactlyOneRequest_andNudgesOnce(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), board)
            val routes = electionRoutes()
            world.onRoute = { route -> if (route == routes.closeVoting) world.election = election(status = ElectionStatus.CLOSED) }
            withOperatorPanel("op-close-double", world) { el, handle, h, calls, r ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
                el.buttonNamed("Abstimmung schließen").click()
                val confirm = lastOpenModal().buttonNamed("Abstimmung schließen")
                confirm.click()
                confirm.click()
                awaitUntil("close sent", 1500) { calls.toRoute(r.closeVoting).size >= 1 }
                awaitUntil("nudged", 1500) { h.nudges == 1 }
                delay(150)
                assertEquals(1, calls.toRoute(r.closeVoting).size)
                assertEquals(1, h.nudges)
            }
        }

    @Test
    fun aConflict_reloads_saysSoNeutrally_neverShowsTheServerText_andDoesNotNudge(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), board)
            val routes = electionRoutes()
            world.failures[routes.closeVoting] = CONFLICT_EXCEPTION
            withOperatorPanel("op-conflict", world) { el, handle, h, calls, r ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
                val reads = calls.toRoute(r.getParticipation).size
                el.buttonNamed("Abstimmung schließen").click()
                lastOpenModal().buttonNamed("Abstimmung schließen").click()
                awaitUntil("note", 2000) { el.flatText().contains("Der Stand hat sich geändert und wurde neu geladen.") }
                awaitUntil("reloaded", 2000) { calls.toRoute(r.getParticipation).size > reads && h.refreshes >= 1 }
                assertFalse(el.flatText().contains("simulated for a test"), "the server text never reaches the DOM")
                assertEquals(0, h.nudges, "a failed write is not announced")
            }
        }

    @Test
    fun aLostNudge_isIgnored_withoutAnErrorText(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), board)
            val h = Harness().apply { nudgeFails = true }
            withOperatorPanel("op-nudge-lost", world, harness = h) { el, handle, _, calls, routes ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("close button", 2000) { el.hasButton("Abstimmung schließen") }
                el.buttonNamed("Abstimmung schließen").click()
                lastOpenModal().buttonNamed("Abstimmung schließen").click()
                awaitUntil("close sent", 1500) { calls.toRoute(routes.closeVoting).size == 1 }
                awaitUntil("room reloaded", 1500) { h.refreshes >= 1 }
                assertFalse(el.flatText().contains("data channel gone"))
                assertEquals(0, h.nudges)
            }
        }

    @Test
    fun theApprovalCounter_updatesWithThePoll_approvingNudgesNoOne_andCountingNudges(): Promise<Unit> =
        formTest {
            val closed = election(status = ElectionStatus.CLOSED)
            val world = ElectionWorld(closed, participation(isBoard = true, approvals = 1, threshold = 2))
            val closedBallot = roomBallot("e1", RoomBallotStatus.CLOSED_AWAITING_TALLY, voted = true)
            withOperatorPanel("op-approval", world) { el, handle, h, calls, routes ->
                handle.apply(answerOf(closedBallot))
                awaitUntil("counter", 2000) { el.flatText().contains("Freigaben 1 von 2") }
                assertTrue(el.flatText().contains("Noch 1 Freigabe nötig."))
                assertFalse(el.hasButton("Auszählen"), "counting is not offered before the threshold")
                // another board member approved: the next poll answer brings the new counter
                world.participation = participation(isBoard = true, approvals = 2, threshold = 2)
                handle.apply(answerOf(closedBallot))
                awaitUntil("counter updated", 2000) { el.flatText().contains("Freigaben 2 von 2") }
                awaitUntil("count offered", 2000) { el.hasButton("Auszählen") }
                el.buttonNamed("Auszählung freigeben").click()
                awaitUntil("approve sent", 1500) { calls.toRoute(routes.approve).size == 1 }
                delay(100)
                assertEquals(0, h.nudges, "approving sends no nudge")
                el.buttonNamed("Auszählen").click()
                lastOpenModal().buttonNamed("Auszählen").click()
                awaitUntil("tally sent", 1500) { calls.toRoute(routes.tally).size == 1 }
                awaitUntil("nudged", 1500) { h.nudges == 1 }
            }
        }

    @Test
    fun theVoteCounter_followsTheRoomPoll_whileTheElectionIsOpen_andTheCloseDialogShowsIt(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = true, ballotCount = 0))
            withOperatorPanel("op-counter-live", world) { el, handle, _, _, _ ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("counter", 2000) { el.flatText().contains("Stimmen bisher: 0") }
                world.participation = participation(isBoard = true, ballotCount = 15)
                handle.apply(answerOf(openBallot()))
                awaitUntil("counter follows", 2000) { el.flatText().contains("Stimmen bisher: 15") }
                el.buttonNamed("Abstimmung schließen").click()
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("Bisher 15 Stimmen abgegeben."), modal.flatText())
                assertFalse(modal.flatText().contains("Es wurde noch keine Stimme abgegeben."))
            }
        }

    @Test
    fun aCommitteeLeader_whoIsNoOperatorAndNoModerator_getsTheAbortOnTheEmergencyCard(): Promise<Unit> =
        formTest {
            val roster = listOf(rosterEntry("me", CommitteeRole.CHAIR))
            val world =
                ElectionWorld(
                    election(status = ElectionStatus.OPEN, title = "Vorstandswahl 2026"),
                    participation(isBoard = false),
                    roster = roster,
                    targetRoster = roster,
                )
            withOperatorPanel("op-hung-leader", world) { el, handle, h, calls, routes ->
                handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
                handle.apply(answerOf(openBallot()))
                delay(100)
                h.scheduler.advance(60_000)
                awaitUntil("abort offered", 3000) { el.hasButton("Wahl abbrechen") }
                assertFalse(el.hasButton("Stream stoppen"), "no moderation, no stop")
                assertFalse(el.hasButton("Abstimmung schließen"), "the leadership does not operate")
                assertEquals(0, calls.toRoute(routes.abort).size)
            }
        }

    @Test
    fun anElectionBoardMember_withoutBoardOrAdmin_seesTheResultAfterCountingInTheRoom(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.CLOSED), participation(isBoard = true, approvals = 2))
            withOperatorPanel("op-board-result", world) { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot("e1", RoomBallotStatus.CLOSED_AWAITING_TALLY, voted = true)))
                awaitUntil("controls", 2000) { el.hasButton("Auszählen") }
                world.election = election(status = ElectionStatus.TALLIED)
                world.result =
                    ElectionResultDto(
                        "e1",
                        listOf("o-yes"),
                        tie = false,
                        majorityMet = true,
                        perOptionVotes = mapOf("o-yes" to 3, "o-no" to 1),
                    )
                handle.apply(answerOf(roomBallot("e1", RoomBallotStatus.DECIDED, voted = true)))
                awaitUntil("result", 2000) { el.flatText().contains("Die erforderliche Mehrheit wurde erreicht.") }
            }
        }

    @Test
    fun aCountedElection_showsItsResultCompactly_toAnOperator(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(status = ElectionStatus.TALLIED),
                    participation(isBoard = true),
                    result =
                        ElectionResultDto(
                            "e1",
                            listOf("o-yes"),
                            tie = false,
                            majorityMet = true,
                            perOptionVotes =
                                mapOf(
                                    "o-yes" to 3,
                                    "o-no" to 1,
                                ),
                        ),
                )
            withOperatorPanel("op-result", world, ctx(boardOrAdmin = true)) { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot("e1", RoomBallotStatus.DECIDED, voted = true)))
                awaitUntil("result", 2000) { el.flatText().contains("Die erforderliche Mehrheit wurde erreicht.") }
            }
        }

    @Test
    fun aLoadFailure_showsTheRetryState_andRetryLoadsAgain(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), board)
            val routes = electionRoutes()
            world.failures[routes.getParticipation] = FORBIDDEN_EXCEPTION
            withOperatorPanel("op-error", world, ctx(boardOrAdmin = true)) { el, handle, _, _, _ ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("error state", 2000) { el.flatText().contains("Die Daten konnten nicht geladen werden.") }
                assertFalse(el.hasButton("Abstimmung schließen"))
                world.failures.clear()
                el.buttonNamed("Erneut versuchen").click()
                awaitUntil("loaded", 2000) { el.hasButton("Abstimmung schließen") }
            }
        }

    // ── the lock on the cast button ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theCastButton_isLockedWhileTheStreamPauses_withAVisibleReason_andFreeAfterwards(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            withOperatorPanel("op-lock", world) { el, handle, h, calls, routes ->
                handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
                handle.apply(answerOf(openBallot()))
                el.toReview()
                assertTrue(el.isButtonDisabled("Stimme endgültig abgeben"))
                val reason = assertNotNull(el.querySelector("#lapis-booth-lock-reason") as? HTMLElement)
                assertTrue(reason.flatText().contains("Der Live-Stream wird noch angehalten."))
                assertFalse(reason.flatText().contains("Das dauert länger als üblich."))
                assertEquals("lapis-booth-lock-reason", el.buttonNamed("Stimme endgültig abgeben").getAttribute("aria-describedby"))
                el.buttonNamed("Stimme endgültig abgeben").click()
                assertEquals(0, calls.toRoute(routes.cast).size, "a locked button sends nothing")
                h.scheduler.advance(20_000)
                assertTrue(
                    el
                        .querySelector(
                            "#lapis-booth-lock-reason",
                        ).let { (it as HTMLElement).flatText() }
                        .contains("Das dauert länger als üblich."),
                )
                assertTrue(el.isButtonDisabled("Stimme endgültig abgeben"))
                handle.onStreamState(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
                assertFalse(el.isButtonDisabled("Stimme endgültig abgeben"))
                assertNull(el.buttonNamed("Stimme endgültig abgeben").getAttribute("aria-describedby"))
            }
        }

    @Test
    fun theCastButton_isFreeForEveryQuiescedStatus_andForAnOpenElectionWhateverTheStreamDoes(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            listOf(ConferenceStreamStatus.PAUSED, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED, null).forEachIndexed {
                i,
                status,
                ->
                withOperatorPanel("op-free-$i", world) { el, handle, _, _, _ ->
                    handle.onStreamState(status, null)
                    handle.apply(answerOf(openBallot()))
                    el.toReview()
                    assertFalse(el.isButtonDisabled("Stimme endgültig abgeben"), "$status")
                }
            }
            val named = ElectionWorld(election(status = ElectionStatus.OPEN, secret = false), participation(isBoard = false))
            withOperatorPanel("op-free-named", named) { el, handle, _, _, _ ->
                handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
                handle.apply(answerOf(openBallot(secret = false)))
                el.toReview()
                assertFalse(el.isButtonDisabled("Stimme endgültig abgeben"), "an open election is never locked")
            }
        }

    @Test
    fun aConflictOnCast_keepsTheSelection_locksQuietly_andFreesTheButtonAfterFiveSeconds_withoutResubmitting(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            val routes = electionRoutes()
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withOperatorPanel("op-conflict-cast", world) { el, handle, h, calls, r ->
                handle.apply(answerOf(openBallot()))
                el.toReview()
                el.buttonNamed("Stimme endgültig abgeben").click()
                awaitUntil("locked again", 3000) {
                    el.hasButton("Stimme endgültig abgeben") && el.isButtonDisabled("Stimme endgültig abgeben") && h.refreshes >= 1
                }
                assertTrue(el.flatText().contains("Die Stimmabgabe ist noch gesperrt."), el.flatText())
                assertTrue(el.allOf(".lapis-booth-review").any { it.flatText() == "Ja" }, "the choice is still there")
                assertFalse(el.flatText().contains("Stimmabgabe gerade nicht möglich"), "no terminal error")
                assertEquals(1, calls.toRoute(r.cast).size)
                h.scheduler.advance(5_000)
                assertFalse(el.isButtonDisabled("Stimme endgültig abgeben"), "free again after the recheck")
                delay(150)
                assertEquals(1, calls.toRoute(r.cast).size, "never an automatic re-submit")
            }
        }

    // ── the emergency card ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aHungStream_givesTheModerationAndBoardTheEmergencyCard_withTheStopFirst_andAbortNeedsTheTitle(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN, title = "Vorstandswahl 2026"), board)
            withOperatorPanel("op-hung", world, ctx(boardOrAdmin = true, moderate = true)) { el, handle, h, calls, routes ->
                handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
                handle.apply(answerOf(openBallot()))
                awaitUntil("normal card", 2000) { el.hasButton("Abstimmung schließen") }
                h.scheduler.advance(60_000)
                awaitUntil("emergency actions", 3000) { el.hasButton("Stream stoppen") && el.hasButton("Wahl abbrechen") }
                val labels = el.allOf("button").map { it.textContent?.trim() }.filter { it == "Stream stoppen" || it == "Wahl abbrechen" }
                assertEquals(listOf("Stream stoppen", "Wahl abbrechen"), labels, "stop on top, abort below")
                assertEquals(1, el.allOf("button.btn-primary").count { it.textContent?.trim() == "Stream stoppen" })
                assertTrue(el.buttonNamed("Wahl abbrechen").className.contains("text-danger"))
                assertFalse(el.hasButton("Abstimmung schließen"), "the card replaces the normal controls")
                assertTrue(el.flatText().contains("Der Stream lässt sich nicht anhalten."))
                el.buttonNamed("Stream stoppen").click()
                assertEquals(1, h.stopRequests)
                el.buttonNamed("Wahl abbrechen").click()
                val modal = lastOpenModal()
                val input = assertNotNull(modal.querySelector("input") as? HTMLInputElement)
                assertTrue(modal.isButtonDisabled("Wahl abbrechen"))
                modal.buttonNamed("Wahl abbrechen").click()
                assertEquals(0, calls.toRoute(routes.abort).size, "nothing before the title is typed")
                input.value = "Vorstandswahl 2026"
                input.dispatchEvent(Event("input"))
                modal.buttonNamed("Wahl abbrechen").click()
                awaitUntil("abort sent", 2000) { calls.toRoute(routes.abort).size == 1 }
                awaitUntil("nudged", 2000) { h.nudges == 1 }
            }
        }

    @Test
    fun aPlainMember_seesOnlyTheLockText_neverTheEmergencyCard(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            withOperatorPanel("op-hung-member", world) { el, handle, h, _, _ ->
                handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
                handle.apply(answerOf(openBallot()))
                h.scheduler.advance(60_000)
                delay(200)
                assertFalse(el.hasButton("Stream stoppen"))
                assertEquals(0, el.allOf(".border-danger").size)
                assertTrue(el.flatText().contains("Das dauert länger als üblich."), "the slow text is all a member gets")
            }
        }

    @Test
    fun aHungStreamForAModeratorWhoCannotAbort_getsTheStopOnly(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            withOperatorPanel("op-hung-moderator", world, ctx(moderate = true)) { el, handle, h, _, _ ->
                handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
                handle.apply(answerOf(openBallot()))
                h.scheduler.advance(60_000)
                awaitUntil("stop", 2000) { el.hasButton("Stream stoppen") }
                assertFalse(el.hasButton("Wahl abbrechen"))
            }
        }

    // ── booth and casting ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anOperatorInTheBooth_losesTheControls_andReadsWhy(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), board)
            withOperatorPanel("op-booth", world) { el, handle, _, _, _ ->
                handle.apply(answerOf(openBallot()))
                awaitUntil("controls", 2000) { el.hasButton("Abstimmung schließen") }
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("booth", 2000) { el.querySelector(".lapis-booth") != null }
                assertTrue(el.flatText().contains("Sie bedienen diese Wahl. Die Steuerung erscheint nach Ihrer Stimmabgabe wieder."))
                assertFalse(el.hasButton("Abstimmung schließen"), "no controls next to the ballot")
            }
        }

    @Test
    fun whileTheBallotRequestRuns_thePanelIsLockedAsCasting_theLeaveButtonsAreBlocked_andTheBrowserAsks(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false), castDelayMs = 600)
            withBeforeUnloadSpy { active ->
                withOperatorPanel("op-casting", world) { el, handle, h, _, _ ->
                    handle.apply(answerOf(openBallot()))
                    el.toReview()
                    assertEquals(0, active())
                    el.buttonNamed("Stimme endgültig abgeben").click()
                    awaitUntil("casting lock", 1000) { h.locks.lastOrNull() == ConferenceVotingLock.CASTING }
                    assertTrue(ConferenceReceiptGate.casting)
                    assertTrue(ConferenceReceiptGate.blocksUnload)
                    assertTrue(ConferenceVotingLock.CASTING.blocksLeaving())
                    assertEquals(1, active(), "the browser's own question is registered for exactly this time")
                    assertTrue(el.isButtonDisabled("Abstimmen schließen"))
                    assertTrue(el.flatText().contains("Ihre Stimme wird gerade übermittelt …"))
                    awaitUntil("receipt", 3000) { el.allOf(".lapis-receipt-code").isNotEmpty() }
                    assertFalse(ConferenceReceiptGate.casting)
                    assertEquals(ConferenceVotingLock.RECEIPT, h.locks.last())
                    assertTrue(ConferenceReceiptGate.blocksUnload, "the receipt is on screen")
                    (el.controlOf("Ich habe mir die Quittung notiert.") as HTMLInputElement).click()
                    awaitUntil("done enabled", 1500) { !el.isButtonDisabled("Fertig") }
                    el.buttonNamed("Fertig").click()
                    awaitUntil("booth left", 1500) { !handle.isBoothOpen() }
                    assertFalse(ConferenceReceiptGate.blocksUnload)
                    assertEquals(0, active())
                }
            }
        }

    @Test
    fun theCastingGate_blocksUnloadOnlyWhileSet(): Promise<Unit> =
        formTest {
            ConferenceReceiptGate.visible = false
            ConferenceReceiptGate.casting = false
            assertFalse(ConferenceReceiptGate.blocksUnload)
            ConferenceReceiptGate.casting = true
            assertTrue(ConferenceReceiptGate.blocksUnload)
            ConferenceReceiptGate.casting = false
            ConferenceReceiptGate.visible = true
            assertTrue(ConferenceReceiptGate.blocksUnload)
            ConferenceReceiptGate.visible = false
            assertTrue(
                ConferenceVotingLock.entries.filter { it.blocksLeaving() } ==
                    listOf(ConferenceVotingLock.CASTING, ConferenceVotingLock.RECEIPT),
            )
        }

    @Test
    fun disposingThePanel_cancelsTheClockAndTheRecheck_andLeavesNoListener(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(isBoard = false))
            val h = Harness()
            withBeforeUnloadSpy { active ->
                withOperatorPanel("op-dispose", world, harness = h) { _, handle, _, _, _ ->
                    handle.onStreamState(ConferenceStreamStatus.PAUSING, null)
                    assertEquals(1, h.scheduler.pendingTimers)
                    handle.dispose()
                    assertEquals(0, h.scheduler.pendingTimers)
                    assertEquals(0, active())
                }
            }
        }
}
