package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.LtrLedgerBalanceDto
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotOptionDto
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.RoomVotingStateDto
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.27 -- the meritocratic vote in the room: the live card, the bid view inside the panel's booth host, and what a stale or hostile
 * input does to them. Mounted for real (Karma/Chrome); the panel runs on fakes for the balance, the cast and the fresh room state, so
 * what is observed is the DOM and the exact input of the one write.
 */
class ConferenceMeritVoteDomTest {
    private val motionId = "123e4567-e89b-12d3-a456-426614174001"
    private val voteId = "v1"

    private val yes = RoomBallotOptionDto("o-yes", "YES", 0)
    private val no = RoomBallotOptionDto("o-no", "NO", 1)

    private fun HTMLElement.shown(): Boolean = style.display != "none"

    private fun vote(
        status: RoomBallotStatus = RoomBallotStatus.OPEN,
        eligible: Boolean = true,
        voted: Boolean = false,
        winner: String? = null,
        title: String = "Haushalt 2027",
        motionTitle: String = "Antrag Haushalt",
        options: List<RoomBallotOptionDto> = listOf(yes, no),
        motion: String = motionId,
    ): RoomBallotDto =
        roomBallot(
            id = voteId,
            status = status,
            kind = RoomBallotKind.VOTE,
            eligible = eligible,
            voted = voted,
            secret = false,
            title = title,
            motionTitle = motionTitle,
            options = options,
            winnerOptionId = winner,
            motionId = motion,
        )

    private class Probe {
        val locks = mutableListOf<ConferenceVotingLock>()
        val casts = mutableListOf<VoteBallotInput>()
        var refreshes = 0
        var nudges = 0
        var balance: LtrLedgerBalanceDto? = LtrLedgerBalanceDto("me", 100.0.toDecimal())
        var fresh: RoomVotingStateDto? = null
        var castFails: Throwable? = null
        var castDelayMs = 0
        var closeRequests = 0
    }

    private suspend fun <T> withMeritPanel(
        id: String,
        probe: Probe = Probe(),
        block: suspend (HTMLElement, ConferenceVotePanelHandle, Probe) -> T,
    ): T =
        mountedForm(id) { root, element ->
            val handle =
                renderConferenceVotePanel(
                    parent = root,
                    onLockChanged = { probe.locks += it },
                    onCloseRequested = { probe.closeRequests++ },
                    onBoothExited = {},
                    sendNudge = { probe.nudges++ },
                    onRefreshRoom = { probe.refreshes++ },
                    meritRpc = MeritOperatorRpc(listScheduledMotions = { emptyList() }),
                    loadBalance = { probe.balance },
                    castVote = { input ->
                        probe.casts += input
                        if (probe.castDelayMs > 0) delay(probe.castDelayMs.toLong())
                        probe.castFails?.let { throw it }
                        VoteBallotDto(
                            id = "b1",
                            voteId = input.voteId,
                            optionId = input.optionId,
                            memberId = "me",
                            memberDisplayName = "ich",
                            stakeLtr = input.stakeLtr,
                            settledLtr = null,
                            castAt = ELECTION_AT,
                        )
                    },
                    loadRoomState = { probe.fresh },
                )
            handle.panel.show()
            try {
                block(element(), handle, probe)
            } finally {
                handle.dispose()
            }
        }

    private fun answerOf(vararg ballots: RoomBallotDto) = voteRoomReduce(ConferenceVoteRoomState(), roomState(*ballots))

    // ── the card ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCard_showsTitleKindStateAndStanding_andOffersTheBidOnlyToAMemberWhoCanStillBid(): Promise<Unit> =
        formTest {
            data class Case(
                val ballot: RoomBallotDto,
                val standing: String,
                val primary: Int,
            )
            val cases =
                listOf(
                    Case(vote(), "Sie sind stimmberechtigt", 1),
                    Case(vote(voted = true), "Sie haben geboten", 0),
                    Case(vote(eligible = false), "Nicht stimmberechtigt", 0),
                    Case(vote(RoomBallotStatus.DECIDED, winner = yes.id), "Sie sind stimmberechtigt", 0),
                )
            cases.forEachIndexed { index, case ->
                mountedForm("merit-card-$index") { root, element ->
                    renderMeritVoteLiveCard(root, case.ballot) {}
                    val el = element()
                    val text = el.flatText()
                    assertTrue(text.contains("Haushalt 2027") && text.contains("Antrag Haushalt"), text)
                    assertTrue(text.contains("Meritokratische Abstimmung"), "the kind is named: $text")
                    assertTrue(text.contains("offen – namentlich"), "a vote is never secret: $text")
                    assertFalse(text.contains("geheim"))
                    assertTrue(text.contains(case.standing), "case $index: $text")
                    assertEquals(case.primary, el.allOf("button.btn-primary").size, "case $index: one primary action, only when it applies")
                    if (case.primary == 1) assertTrue(el.hasButton("Gebot abgeben"))
                }
            }
        }

    @Test
    fun theCard_ofADecidedVote_showsTheWinnersLabel_aTieSaysSo_andAnUnknownWinnerShowsNoResultLine(): Promise<Unit> =
        formTest {
            mountedForm("merit-result") { root, element ->
                renderMeritVoteLiveCard(root, vote(RoomBallotStatus.DECIDED, winner = no.id)) {}
                assertTrue(element().flatText().contains("Ergebnis: NO"), element().flatText())
            }
            mountedForm("merit-tie") { root, element ->
                renderMeritVoteLiveCard(root, vote(RoomBallotStatus.DECIDED, winner = null)) {}
                assertTrue(element().flatText().contains("Unentschieden – kein Gewinner"))
                assertFalse(element().flatText().contains("Belastung"), "no promise about money is made on a tie")
            }
            mountedForm("merit-unknown-winner") { root, element ->
                renderMeritVoteLiveCard(root, vote(RoomBallotStatus.DECIDED, winner = "o-ghost")) {}
                assertFalse(element().flatText().contains("Ergebnis:"), "a winner id that is not an option is never guessed")
                assertFalse(element().flatText().contains("Unentschieden"))
            }
        }

    @Test
    fun theCardLinks_pointToTheMotionPage_inANewTabWithoutLeakingTheOpener_andABadIdGetsNoLink(): Promise<Unit> =
        formTest {
            mountedForm("merit-link-decided") { root, element ->
                renderMeritVoteLiveCard(root, vote(RoomBallotStatus.DECIDED, winner = yes.id)) {}
                val link = assertNotNull(element().querySelector("a") as? HTMLElement, "no link")
                assertEquals("#/motions/$motionId", link.getAttribute("href"))
                assertEquals("_blank", link.getAttribute("target"))
                val rel = link.getAttribute("rel").orEmpty()
                assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), "rel=$rel")
                assertEquals("Ergebnis im Detail", link.flatText())
            }
            mountedForm("merit-link-bid") { root, element ->
                renderMeritVoteLiveCard(root, vote(voted = true)) {}
                assertEquals("Gebot ansehen oder ändern", assertNotNull(element().querySelector("a") as? HTMLElement).flatText())
            }
            mountedForm("merit-link-bad") { root, element ->
                renderMeritVoteLiveCard(root, vote(RoomBallotStatus.DECIDED, winner = yes.id, motion = "../../evil")) {}
                assertNull(element().querySelector("a"), "an id that does not look like ours gets no link")
            }
            mountedForm("merit-link-none-open") { root, element ->
                renderMeritVoteLiveCard(root, vote()) {}
                assertNull(element().querySelector("a"), "an open vote without an own bid is bid on in the room, not read in a tab")
            }
        }

    @Test
    fun hostileTitlesAndLabels_areShownLiterally_neverTranslatedNeverAsHtml(): Promise<Unit> =
        formTest {
            val marker = "###KvI18nS###Abstimmen"
            mountedForm("merit-hostile") { root, element ->
                renderMeritVoteLiveCard(
                    root,
                    vote(
                        RoomBallotStatus.DECIDED,
                        winner = "o-x",
                        title = "$marker <img src=x onerror=alert(1)>",
                        motionTitle = "<b>fett</b> $marker",
                        options = listOf(RoomBallotOptionDto("o-x", "$marker <i>kursiv</i>", 0)),
                    ),
                ) {}
                val el = element()
                assertNull(el.querySelector("img"), "no title becomes markup")
                assertNull(el.querySelector("b"))
                assertNull(el.querySelector("i"))
                assertTrue(el.flatText().contains("<img src=x onerror=alert(1)>"), el.flatText())
                assertTrue(el.flatText().contains("<i>kursiv</i>"), "the winner label is literal text: ${el.flatText()}")
                assertFalse(el.flatText().contains("Abstimmen schließen"), "the marker was not resolved to a catalog entry")
            }
        }

    // ── the overview ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theOverview_saysOnceThatOpenVotesAreSavedByName_andOnlyWhenOneIsOpen(): Promise<Unit> =
        formTest {
            withMeritPanel("merit-notice") { el, handle, _ ->
                val notice = "Diese Abstimmung ist offen und wird namentlich gespeichert"
                handle.apply(answerOf(vote(), roomBallot("v2", kind = RoomBallotKind.VOTE, secret = false, motionId = motionId)))
                assertEquals(1, Regex(notice).findAll(el.flatText()).count(), "exactly once above the open cards: ${el.flatText()}")
                handle.apply(answerOf(vote(RoomBallotStatus.DECIDED, winner = yes.id)))
                assertFalse(el.flatText().contains(notice), "no open vote, no notice")
            }
        }

    // ── the bid view ──────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun HTMLElement.openBid(probe: Probe) {
        probe.fresh = roomState(vote())
        buttonNamed("Gebot abgeben").click()
        awaitUntil("the bid view", 2000) { querySelector(".lapis-merit-bid") != null }
    }

    private fun HTMLElement.fillBid(stake: String = "12.5") {
        chooseIn("Option", no.id)
        typeInto("Einsatz", stake)
    }

    @Test
    fun theBidView_showsTheBalanceAndTheHonestExplanation_inTheBoothHost_andLocksThePanel(): Promise<Unit> =
        formTest {
            withMeritPanel("merit-bid-view") { el, handle, probe ->
                handle.apply(answerOf(vote()))
                el.openBid(probe)
                assertTrue(handle.isBoothOpen())
                assertEquals(ConferenceVotingLock.BOOTH, probe.locks.last())
                assertNull(el.querySelector(".lapis-vote-overview"), "the overview is out of the DOM while the bid view is up")
                assertTrue(el.byClassOrNull("lapis-vote-booth-host")?.shown() == true)
                awaitUntil("the balance", 2000) { el.flatText().contains("Verfügbar") }
                val text = el.flatText()
                assertTrue(text.contains("Ihr Einsatz wird sofort von Ihrem verfügbaren Guthaben abgezogen und bleibt gebunden."), text)
                assertTrue(
                    text.contains("eine Rückgabe nicht verbrauchter Beträge gibt es derzeit noch nicht"),
                    "no false refund promise: $text",
                )
                assertFalse(text.contains("zurück"), "nothing says the stake comes back: $text")
                assertTrue(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"), "the panel cannot be closed from the bid view")
                el.buttonNamed("Zurück zur Übersicht").click()
                assertFalse(handle.isBoothOpen())
                assertEquals(ConferenceVotingLock.NONE, probe.locks.last())
            }
        }

    @Test
    fun aBalanceThatCannotBeRead_showsTheSharedErrorState_withoutAnyExceptionText(): Promise<Unit> =
        formTest {
            val probe = Probe().also { it.balance = null }
            withMeritPanel("merit-no-balance", probe) { el, handle, _ ->
                handle.apply(answerOf(vote()))
                el.openBid(probe)
                awaitUntil("the error state", 2000) { el.hasButton("Erneut versuchen") }
                assertFalse(el.flatText().contains("Verfügbar:"))
                assertTrue(el.hasButton("Gebot abgeben"), "the member can still bid: the server decides")
            }
        }

    @Test
    fun confirming_sendsExactlyOneFirstBid_withTheChosenOptionAndStake_aDoubleClickIsOneBid(): Promise<Unit> =
        formTest {
            withMeritPanel("merit-confirm") { el, handle, probe ->
                handle.apply(answerOf(vote()))
                el.openBid(probe)
                el.fillBid("12.5")
                el.buttonNamed("Gebot abgeben").click()
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("12.5 LTR auf „NO“ setzen?"), modal.flatText())
                assertTrue(modal.flatText().contains("bleibt gebunden"))
                val confirm = modal.buttonNamed("Gebot abgeben")
                confirm.click()
                confirm.click()
                awaitUntil("the cast", 2000) { probe.casts.isNotEmpty() }
                delay(150)
                assertEquals(1, probe.casts.size, "a double click is one bid")
                val input = probe.casts.single()
                assertEquals(voteId, input.voteId)
                assertEquals(no.id, input.optionId)
                assertEquals("12.5", input.stakeLtr.toString())
                assertTrue(input.createOnly, "a bid from the room never overwrites an existing one")
                awaitUntil("back to the overview", 2000) { el.querySelector(".lapis-vote-overview") != null }
                assertEquals(1, probe.nudges, "one empty nudge after a stored bid")
                assertTrue(probe.refreshes >= 1)
            }
        }

    @Test
    fun cancellingTheConfirmation_sendsNothing_andTheFormStaysFilled(): Promise<Unit> =
        formTest {
            withMeritPanel("merit-cancel") { el, handle, probe ->
                handle.apply(answerOf(vote()))
                el.openBid(probe)
                el.fillBid("12.5")
                el.buttonNamed("Gebot abgeben").click()
                lastOpenModal().buttonNamed("Abbrechen").click()
                delay(150)
                assertEquals(0, probe.casts.size, "cancelling sends nothing")
                assertEquals("12.5", (el.controlOf("Einsatz") as org.w3c.dom.HTMLInputElement).value)
                assertTrue(handle.isBoothOpen(), "the member is still in the bid view")
            }
        }

    @Test
    fun aStakeAboveTheFreeBalance_isRefusedBeforeAnyDialog_andNothingIsSent(): Promise<Unit> =
        formTest {
            withMeritPanel("merit-over-balance") { el, handle, probe ->
                handle.apply(answerOf(vote()))
                el.openBid(probe)
                awaitUntil("the balance", 2000) { el.flatText().contains("Verfügbar") }
                el.fillBid("100.01")
                el.buttonNamed("Gebot abgeben").click()
                delay(100)
                assertEquals(0, probe.casts.size)
                assertEquals(0, document_modalCount(), "no confirmation is asked for something that cannot work")
            }
        }

    @Test
    fun aConflict_returnsToTheList_withAFixedNote_andNeverShowsTheServersText(): Promise<Unit> =
        formTest {
            val probe = Probe().also { it.castFails = ConflictException("Member 4f1c-secret-uuid already has a ballot") }
            withMeritPanel("merit-conflict", probe) { el, handle, _ ->
                handle.apply(answerOf(vote()))
                el.openBid(probe)
                el.fillBid("5")
                el.buttonNamed("Gebot abgeben").click()
                lastOpenModal().buttonNamed("Gebot abgeben").click()
                awaitUntil("back to the overview", 2000) { el.querySelector(".lapis-vote-overview") != null }
                assertTrue(el.flatText().contains("Der Stand hat sich geändert und wurde neu geladen."), el.flatText())
                assertFalse(el.flatText().contains("secret-uuid"), "the exception's message is never read")
                assertTrue(probe.refreshes >= 1, "the room is reloaded")
                assertFalse(handle.isBoothOpen())
            }
        }

    @Test
    fun aStaleCard_neverOpensTheBidView_whenTheVoteClosedOrTheMemberAlreadyBid(): Promise<Unit> =
        formTest {
            val probe = Probe()
            withMeritPanel("merit-stale-closed", probe) { el, handle, _ ->
                handle.apply(answerOf(vote()))
                probe.fresh = roomState(vote(RoomBallotStatus.DECIDED, winner = yes.id))
                el.buttonNamed("Gebot abgeben").click()
                awaitUntil("the note", 2000) { el.flatText().contains("Diese Abstimmung ist nicht mehr offen.") }
                assertNull(el.querySelector(".lapis-merit-bid"))
                assertFalse(handle.isBoothOpen())
            }
            withMeritPanel("merit-stale-bid", probe) { el, handle, _ ->
                handle.apply(answerOf(vote()))
                probe.fresh = roomState(vote(voted = true))
                el.buttonNamed("Gebot abgeben").click()
                awaitUntil("the note", 2000) { el.flatText().contains("Sie haben bereits geboten.") }
                assertNull(el.querySelector(".lapis-merit-bid"), "a bid that exists is never overwritten from a stale card")
                assertEquals(0, probe.casts.size)
            }
        }

    @Test
    fun whileTheBidViewIsOpen_aPollAnswerDoesNotTouchIt_andTheBadgeCountsTheVote(): Promise<Unit> =
        formTest {
            val probe = Probe()
            withMeritPanel("merit-quiet", probe) { el, handle, _ ->
                val first = answerOf(vote())
                handle.apply(first)
                assertEquals(1, first.state.badgeCount())
                el.openBid(probe)
                el.fillBid("7")
                val bidView = assertNotNull(el.querySelector(".lapis-merit-bid"))
                handle.apply(voteRoomReduce(first.state, roomState(vote(), roomBallot("e2", title = "Neue Wahl"))))
                assertSame(bidView, el.querySelector(".lapis-merit-bid"), "the bid view is never rebuilt under the member's hand")
                assertEquals("7", (el.controlOf("Einsatz") as org.w3c.dom.HTMLInputElement).value, "what was typed stays")
            }
        }
}

private fun HTMLElement.byClassOrNull(name: String): HTMLElement? = querySelector(".$name") as? HTMLElement

private fun document_modalCount(): Int =
    kotlinx.browser.document
        .querySelectorAll(".modal.show")
        .length
