package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * V1.9.32 -- the systemic consensus in the room panel, mounted for real (Karma/Chrome). The booth inside the panel is the real
 * `ConsensusBooth` talking to the stubbed consensus service of `ConsensusDomSupport`; the operator steps run against a fake RPC seam
 * (`ConferenceConsensusRpc`) so every write is counted exactly. Time runs on a fake scheduler.
 */
class ConferenceConsensusPanelDomTest {
    private val kId = "123e4567-e89b-12d3-a456-426614174002"

    private fun HTMLElement.shown(): Boolean = style.display != "none"

    private fun HTMLElement.byClass(name: String): HTMLElement = assertNotNull(querySelector(".$name") as? HTMLElement, "no .$name")

    private fun HTMLElement.badgeTexts(): List<String> = allOf(".badge").map { it.flatText() }

    private fun ballot(
        phase: SystemicConsensusStatus,
        secret: Boolean = true,
        eligible: Boolean = true,
        rated: Boolean = false,
        title: String = "Neues Vereinsheim",
    ): RoomBallotDto =
        roomBallot(
            id = kId,
            status =
                when (phase) {
                    SystemicConsensusStatus.COLLECTION, SystemicConsensusStatus.RATING -> RoomBallotStatus.OPEN
                    SystemicConsensusStatus.CLOSED -> RoomBallotStatus.CLOSED_AWAITING_TALLY
                    else -> RoomBallotStatus.DECIDED
                },
            kind = RoomBallotKind.CONSENSUS,
            eligible = eligible,
            voted = rated,
            secret = secret,
            title = title,
            consensusPhase = phase,
        )

    private fun detail(
        phase: SystemicConsensusStatus,
        secret: Boolean = true,
        canManage: Boolean = false,
        canRate: Boolean = true,
        rated: Boolean = false,
        noOptions: Boolean = false,
        options: List<SystemicConsensusOptionDto>? = null,
    ): ConsensusDetailData =
        ConsensusDetailData(
            consensus =
                consensus(status = phase, secret = secret, options = if (noOptions) emptyList() else options ?: skOptions()).copy(id = kId),
            participation = skParticipation(canManage = canManage, canRate = canRate, hasRated = rated),
            motion = null,
            result = if (phase == SystemicConsensusStatus.EVALUATED && canManage) skResult() else null,
        )

    private class Harness {
        val scheduler = OperatorFakeScheduler()
        val locks = mutableListOf<ConferenceVotingLock>()
        val writes = mutableListOf<String>()
        var detail: ConsensusDetailData? = null
        var detailLoads = 0
        var writeFailure: Throwable? = null
        var nudges = 0
        var refreshes = 0
        var stopRequests = 0
        var closeRequests = 0
        var width = 400
        var nudgeFails = false

        fun write(
            name: String,
            id: String,
        ) {
            writes += "$name:$id"
            writeFailure?.let { throw it }
        }
    }

    private fun ctx(
        moderate: Boolean = false,
        boardOrAdmin: Boolean = false,
    ) = OperatorContext(currentMemberId = "me", isBoardOrAdmin = boardOrAdmin, canModerateRoom = moderate, roomMeetingId = { "s1" })

    private suspend fun <T> withConsensusPanel(
        id: String,
        world: ConsensusWorld = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING).copy(id = kId)),
        harness: Harness = Harness(),
        context: OperatorContext? = ctx(),
        isVotingMember: Boolean = true,
        block: suspend (HTMLElement, ConferenceVotePanelHandle, Harness, List<RecordedRequest>, ConsensusRoutes) -> T,
    ): T {
        val routes = consensusRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root: Root, element ->
                val rpc =
                    ConferenceConsensusRpc(
                        loadDetail = {
                            harness.detailLoads++
                            harness.detail
                        },
                        freeze = { harness.write("freeze", it) },
                        closeRating = { harness.write("close", it) },
                        evaluate = {
                            harness.write("evaluate", it)
                            skResult()
                        },
                        reopen = { harness.write("reopen", it) },
                    )
                val handle =
                    renderConferenceVotePanel(
                        parent = root,
                        onLockChanged = { harness.locks += it },
                        onCloseRequested = { harness.closeRequests++ },
                        onBoothExited = {},
                        operatorContext = context,
                        operatorRpc = OperatorRpc(listPrepared = { emptyList() }),
                        sendNudge = {
                            if (harness.nudgeFails) error("data channel gone")
                            harness.nudges++
                        },
                        onStopStreamRequested = if (context?.canModerateRoom == true) ({ harness.stopRequests++ }) else null,
                        onRefreshRoom = { harness.refreshes++ },
                        scheduler = harness.scheduler,
                        meritRpc = MeritOperatorRpc(listScheduledMotions = { emptyList() }),
                        consensusRpc = rpc,
                        isVotingMember = isVotingMember,
                        measureWidth = { harness.width },
                    )
                handle.setOpen(true)
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

    /** Rates the option whose legend contains [legend] with [value] the way a person does: click the label of that digit. */
    private fun HTMLElement.rate(
        legend: String,
        value: Int,
    ) {
        val fieldset =
            assertNotNull(
                allOf("fieldset").firstOrNull {
                    it
                        .querySelector("legend")
                        ?.textContent
                        .orEmpty()
                        .contains(legend)
                },
                "no '$legend'",
            )
        val label = assertNotNull(fieldset.allOf("label").firstOrNull { it.textContent?.trim() == value.toString() }, "no digit $value")
        label.click()
    }

    private suspend fun HTMLElement.rateAllAndReview() {
        awaitUntil("the booth", 2000) { querySelector(".lapis-booth") != null }
        rate("Option A", 2)
        rate("Option B", 5)
        rate("Alles bleibt wie bisher", 8)
        awaitUntil("review enabled", 1500) { !isButtonDisabled("Prüfen") }
        buttonNamed("Prüfen").click()
        awaitUntil("review step", 1500) { hasButton("Endgültig abgeben") }
    }

    // ── the card ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCard_showsTitlePhaseAnonymityAndStanding_andOffersTheRatingOnlyToAMemberWhoCanStillRate(): Promise<Unit> =
        formTest {
            data class Case(
                val ballot: RoomBallotDto,
                val badges: List<String>,
                val standing: String?,
                val primary: Int,
            )
            val cases =
                listOf(
                    Case(ballot(SystemicConsensusStatus.COLLECTION), listOf("Optionen werden gesammelt", "anonym"), null, 0),
                    Case(ballot(SystemicConsensusStatus.RATING), listOf("Bewertung läuft", "anonym"), "Sie können bewerten", 1),
                    Case(
                        ballot(SystemicConsensusStatus.RATING, secret = false, rated = true),
                        listOf("Bewertung läuft", "offen – namentlich"),
                        "Sie haben bewertet",
                        0,
                    ),
                    Case(
                        ballot(SystemicConsensusStatus.RATING, eligible = false),
                        listOf("Bewertung läuft", "anonym"),
                        "Nicht stimmberechtigt",
                        0,
                    ),
                    Case(ballot(SystemicConsensusStatus.CLOSED), listOf("Bewertung geschlossen", "anonym"), null, 0),
                    Case(ballot(SystemicConsensusStatus.EVALUATED, secret = false), listOf("Ausgewertet", "offen – namentlich"), null, 0),
                )
            cases.forEachIndexed { index, case ->
                mountedForm("consensus-card-$index") { root, element ->
                    renderConsensusLiveCard(parent = root, ballot = case.ballot, canAct = true, onRate = {})
                    val el = element()
                    val text = el.flatText()
                    assertTrue(text.contains("Neues Vereinsheim"), "title: $text")
                    assertEquals(case.badges, el.badgeTexts(), "case $index")
                    for (standing in listOf("Sie können bewerten", "Sie haben bewertet", "Nicht stimmberechtigt")) {
                        assertEquals(standing == case.standing, text.contains(standing), "case $index: '$standing' in $text")
                    }
                    assertEquals(case.primary, el.allOf("button.btn-primary").size, "case $index: one primary action, only when it applies")
                    if (case.primary == 1) assertTrue(el.hasButton("Bewerten"))
                    val help = assertNotNull(el.allOf("a").firstOrNull { it.flatText() == "Wie funktioniert Konsensieren?" }, "case $index")
                    assertEquals("#/consensus", help.getAttribute("href"))
                    assertEquals("_blank", help.getAttribute("target"))
                    val rel = help.getAttribute("rel").orEmpty()
                    assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), "rel=$rel")
                }
            }
        }

    @Test
    fun aGuest_seesOnlyTheStatusLine_noStanding_noButton_noLinkIntoTheMemberArea(): Promise<Unit> =
        formTest {
            mountedForm("consensus-guest-row") { root, element ->
                renderConsensusGuestRow(root, ballot(SystemicConsensusStatus.RATING))
                val el = element()
                assertTrue(el.flatText().contains("Neues Vereinsheim"))
                assertEquals(listOf("Bewertung läuft", "anonym"), el.badgeTexts())
                assertEquals(0, el.allOf("button").size)
                assertNull(el.querySelector("a"))
                assertFalse(el.flatText().contains("Sie können bewerten"))
            }
            withConsensusPanel("consensus-guest-panel", context = null, isVotingMember = false) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                assertTrue(el.flatText().contains("Neues Vereinsheim"))
                assertEquals(0, el.allOf("button.btn-primary").size)
                assertFalse(el.hasButton("Bewerten"))
                assertNull(el.querySelector(".lapis-vote-overview a"))
                assertEquals(0, h.detailLoads, "a guest never reads a consensus")
            }
        }

    @Test
    fun hostileTitles_areShownLiterally_neverTranslatedNeverAsHtml(): Promise<Unit> =
        formTest {
            val marker = "###KvI18nS###Abstimmen"
            mountedForm("consensus-hostile") { root, element ->
                renderConsensusLiveCard(
                    parent = root,
                    ballot = ballot(SystemicConsensusStatus.RATING, title = "$marker <img src=x onerror=alert(1)>"),
                    canAct = true,
                    onRate = {},
                )
                val el = element()
                assertNull(el.querySelector("img"), "a title is text, never markup")
                assertFalse(el.flatText().contains("###KvI18nS###"), "a forged i18n marker is neutralised")
                assertTrue(el.flatText().contains("<img src=x onerror=alert(1)>"), el.flatText())
            }
        }

    // ── the booth inside the panel ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theBooth_replacesTheOverview_locksTheClose_andIsCompactWithNothingAboutARatingInTheDom(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-enter") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                awaitUntil("the operator's own read of the card", 2000) { h.detailLoads >= 1 }
                val readsBefore = h.detailLoads
                el.buttonNamed("Bewerten").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth-compact") != null }
                assertTrue(handle.isBoothOpen())
                assertNull(el.querySelector(".lapis-vote-overview"), "the overview is hidden while the booth is open")
                assertEquals(1, el.allOf(".lapis-booth").size, "one booth, never a copy")
                assertEquals(ConferenceVotingLock.BOOTH, h.locks.last())
                assertTrue(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"), "the panel cannot be closed from the booth")
                assertTrue(el.flatText().contains("Gewählt wird die Option mit dem geringsten Gesamtwiderstand."))
                assertTrue(el.flatText().contains("0 = kein Widerstand, ich kann gut damit leben · 10 = für mich nicht tragbar."))
                // the counter sits next to "Prüfen", not in the sticky head
                assertNull(el.querySelector(".sticky-top [role=status]"), "no counter in the head")
                awaitUntil("counter", 1500) { el.flatText().contains("0 von 3 Optionen bewertet") }
                val radios = el.allOf("input[type=radio]")
                assertEquals(3 * 11, radios.size)
                assertTrue(
                    radios.all {
                        Regex("^sk-r-\\d+$").matches(it.id) &&
                            Regex("^sk-g-\\d$").matches(it.getAttribute("name").orEmpty())
                    },
                )
                val dataAttributes =
                    el.allOf(".lapis-booth, .lapis-booth *").filter { node ->
                        (0 until node.attributes.length).any {
                            node.attributes
                                .item(it)
                                ?.name
                                .orEmpty()
                                .startsWith("data-")
                        }
                    }
                assertEquals(0, dataAttributes.size, "no data-* attribute in the booth")
                assertEquals(readsBefore + 1, h.detailLoads, "one fresh read before the booth opens")
            }
        }

    @Test
    fun aPanelNarrowerThan290px_offersTheBoothInANewTab_andAWiderOneOpensIt(): Promise<Unit> =
        formTest {
            val harness = Harness().also { it.width = 280 }
            withConsensusPanel("consensus-narrow", harness = harness) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.buttonNamed("Bewerten").click()
                awaitUntil("tab link", 2000) { el.allOf("a").any { it.flatText() == "In neuem Tab bewerten" } }
                val link = el.allOf("a").first { it.flatText() == "In neuem Tab bewerten" }
                assertEquals("#/consensus/$kId", link.getAttribute("href"))
                assertEquals("_blank", link.getAttribute("target"))
                val rel = link.getAttribute("rel").orEmpty()
                assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), "rel=$rel")
                assertNull(el.querySelector(".lapis-booth"), "no booth in a panel that is too narrow")
                assertFalse(handle.isBoothOpen())
                assertFalse(el.hasButton("Bewerten"))
            }
            withConsensusPanel("consensus-wide", harness = Harness().also { it.width = 320 }) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.buttonNamed("Bewerten").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth-compact") != null }
                assertNull(el.allOf("a").firstOrNull { it.flatText() == "In neuem Tab bewerten" })
            }
        }

    @Test
    fun aConsensusThatIsNoLongerRating_isNotEntered_andALoadFailureSaysSoCalmly(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-notopen") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.CLOSED)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.buttonNamed("Bewerten").click()
                awaitUntil("the note", 2000) { el.flatText().contains("Diese Bewertung ist nicht mehr offen.") }
                assertNull(el.querySelector(".lapis-booth"))
                assertTrue(h.refreshes >= 1, "the room is read again")
            }
            withConsensusPanel("consensus-loadfail") { el, handle, h, _, _ ->
                h.detail = null
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.buttonNamed("Bewerten").click()
                awaitUntil("the note", 2000) {
                    el.flatText().contains("Die Bewertung konnte nicht geladen werden. Bitte erneut versuchen.")
                }
                assertNull(el.querySelector(".lapis-booth"))
            }
        }

    // ── the stream lock ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anAnonymousConsensus_locksTheFinalSubmitWhileTheStreamRuns_andFreesItWithoutRebuildingTheBooth(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-lock") { el, handle, h, calls, routes ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.onStreamState(ConferenceStreamStatus.LIVE, null)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                // the card already says why a rating would be locked
                assertTrue(el.flatText().contains("Ihr Bildschirm wird gerade übertragen."), el.flatText())
                el.buttonNamed("Bewerten").click()
                el.rateAllAndReview()
                assertTrue(el.isButtonDisabled("Endgültig abgeben"))
                val reason =
                    "Ihr Bildschirm wird gerade übertragen. Damit niemand Ihre Bewertung sieht, ist die Abgabe gesperrt, " +
                        "bis die Übertragung angehalten ist."
                assertTrue(el.flatText().contains(reason), el.flatText())
                assertEquals("lapis-consensus-lock-reason", el.buttonNamed("Endgültig abgeben").getAttribute("aria-describedby"))
                el.buttonNamed("Endgültig abgeben").click()
                delay(150)
                assertEquals(0, calls.toRoute(routes.cast).size, "a locked submit sends nothing")
                val booth = el.byClass("lapis-booth")
                handle.onStreamState(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
                awaitUntil("freed", 1500) { !el.isButtonDisabled("Endgültig abgeben") }
                assertSame(booth, el.byClass("lapis-booth"), "the booth node is the same node: nothing was rebuilt")
                assertFalse(el.flatText().contains("Ihr Bildschirm wird gerade übertragen."))
            }
        }

    @Test
    fun anOpenConsensus_neverLocksTheSubmit_whateverTheStreamDoes(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, secret = false).copy(id = kId))
            withConsensusPanel("consensus-open-live", world) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING, secret = false)
                handle.onStreamState(ConferenceStreamStatus.LIVE, null)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING, secret = false)))
                assertFalse(el.flatText().contains("Ihr Bildschirm wird gerade übertragen."))
                el.buttonNamed("Bewerten").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth") != null }
                assertTrue(el.flatText().contains("Dieses Konsensieren ist offen: Ihre Bewertung wird mit Ihrem Namen gespeichert."))
                el.rateAllAndReview()
                assertFalse(el.isButtonDisabled("Endgültig abgeben"))
            }
        }

    // ── the receipt ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theReceipt_locksThePanel_staysOutOfEveryStore_andIsReleasedByDone(): Promise<Unit> =
        formTest {
            val saved = consensusReceiptVisibilityHook
            val previous: (Boolean) -> Unit = {}
            try {
                consensusReceiptVisibilityHook = previous
                withConsensusPanel("consensus-receipt") { el, handle, h, calls, routes ->
                    assertNotSame(previous, consensusReceiptVisibilityHook, "the panel owns the consensus hook while it lives")
                    h.detail = detail(SystemicConsensusStatus.RATING)
                    handle.onStreamState(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
                    handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                    withConsoleSpy { consoleCalls ->
                        el.buttonNamed("Bewerten").click()
                        el.rateAllAndReview()
                        el.buttonNamed("Endgültig abgeben").click()
                        awaitUntil("the receipt", 3000) { el.allOf(".lapis-receipt-code").isNotEmpty() }
                        assertEquals(1, calls.toRoute(routes.cast).size)
                        assertEquals(ConferenceVotingLock.RECEIPT, h.locks.last())
                        assertTrue(ConferenceReceiptGate.visible, "the unload guard and the leave buttons read this")
                        assertTrue(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"))
                        assertTrue(el.buttonNamed("Zurück zur Übersicht").hasAttribute("disabled"))
                        assertTrue(el.flatText().contains("Bitte notieren Sie zuerst Ihre Quittung."))
                        el.buttonNamed("Abstimmen schließen").click()
                        assertEquals(0, h.closeRequests)
                        val codeNodes = el.allOf(".lapis-receipt-code")
                        assertEquals(2, codeNodes.size)
                        val body = document.body?.textContent.orEmpty()
                        assertEquals(2, Regex(Regex.escape(TEST_RECEIPT)).findAll(body).count())
                        assertNoStoredCode()
                        assertEquals(0, consoleCalls(), "nothing about a rating is ever logged")
                        (el.controlOf("Ich habe mir die Quittung notiert.") as HTMLInputElement).click()
                        awaitUntil("done enabled", 1500) { !el.isButtonDisabled("Fertig") }
                        el.buttonNamed("Fertig").click()
                        awaitUntil("booth left", 1500) { !handle.isBoothOpen() }
                        assertEquals(ConferenceVotingLock.NONE, h.locks.last())
                        assertFalse(ConferenceReceiptGate.visible)
                        assertEquals(0, el.allOf(".lapis-receipt-code").size, "the code is gone with the booth")
                        assertNoStoredCode()
                    }
                }
                assertSame(previous, consensusReceiptVisibilityHook, "disposing the panel gives the previous hook back")
            } finally {
                consensusReceiptVisibilityHook = saved
            }
        }

    @Test
    fun theConsensusHookScope_givesBackThePreviousHook_butNeverOverwritesAHookSomebodyElseSetMeanwhile() {
        val saved = consensusReceiptVisibilityHook
        try {
            val previous: (Boolean) -> Unit = {}
            consensusReceiptVisibilityHook = previous
            val scope = ConferenceConsensusReceiptHookScope {}
            scope.install()
            assertNotSame(previous, consensusReceiptVisibilityHook)
            scope.restore()
            assertSame(previous, consensusReceiptVisibilityHook)

            val other: (Boolean) -> Unit = {}
            val second = ConferenceConsensusReceiptHookScope {}
            second.install()
            consensusReceiptVisibilityHook = other
            second.restore()
            assertSame(other, consensusReceiptVisibilityHook, "a hook set in the meantime is kept")
            second.restore()
            assertSame(other, consensusReceiptVisibilityHook)
        } finally {
            consensusReceiptVisibilityHook = saved
        }
    }

    // ── a poll answer while the booth is open ─────────────────────────────────────────────────────────────────

    @Test
    fun aPollAnswer_whileTheBoothIsOpen_neverTouchesTheBooth_andKeepsTheChosenRatings(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-quiet") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.buttonNamed("Bewerten").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth") != null }
                el.rate("Option A", 3)
                val booth = el.byClass("lapis-booth")
                val checkedBefore = el.allOf("input[type=radio]").count { (it as HTMLInputElement).checked }
                val prev = voteRoomReduce(ConferenceVoteRoomState(), roomState(ballot(SystemicConsensusStatus.RATING))).state
                handle.apply(voteRoomReduce(prev, roomState(ballot(SystemicConsensusStatus.CLOSED), roomBallot("e2", title = "Eine Wahl"))))
                handle.onStreamState(ConferenceStreamStatus.PAUSED, ConferenceStreamPauseReason.SECRET_BALLOT)
                assertSame(booth, el.byClass("lapis-booth"), "the booth node is the same node")
                assertEquals(checkedBefore, el.allOf("input[type=radio]").count { (it as HTMLInputElement).checked })
                assertNull(el.querySelector(".lapis-vote-overview"), "the new list is built, but stays hidden")
            }
        }

    // ── the operator ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun withoutCanManage_theRoomModeratorGetsNoSteps_andNobodyGetsStepsForARatingTheyCannotManage(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-op-none", context = ctx(moderate = true, boardOrAdmin = true)) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.COLLECTION, canManage = false)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.COLLECTION)))
                awaitUntil("read", 2000) { h.detailLoads >= 1 }
                delay(100)
                assertFalse(el.hasButton("Optionen festschreiben"), "room moderation alone is no canManage")
                assertFalse(el.flatText().contains("Auf der Konsensieren-Seite bearbeiten"))
            }
        }

    @Test
    fun aManager_getsTheStepOfThePhase_andAlwaysTheLinkToTheConsensusPage(): Promise<Unit> =
        formTest {
            data class Case(
                val phase: SystemicConsensusStatus,
                val button: String,
            )
            val cases =
                listOf(
                    Case(SystemicConsensusStatus.COLLECTION, "Optionen festschreiben"),
                    Case(SystemicConsensusStatus.RATING, "Bewertung schließen"),
                    Case(SystemicConsensusStatus.CLOSED, "Auswerten"),
                    Case(SystemicConsensusStatus.EVALUATED, "Erneut bewerten"),
                )
            cases.forEachIndexed { index, case ->
                withConsensusPanel("consensus-op-$index") { el, handle, h, _, _ ->
                    h.detail = detail(case.phase, canManage = true)
                    handle.apply(answerOf(ballot(case.phase)))
                    awaitUntil("button ${case.button}", 2000) { el.hasButton(case.button) }
                    val link = assertNotNull(el.allOf("a").firstOrNull { it.flatText() == "Auf der Konsensieren-Seite bearbeiten" })
                    assertEquals("#/consensus/$kId", link.getAttribute("href"))
                    assertEquals("_blank", link.getAttribute("target"))
                    assertTrue(link.getAttribute("rel").orEmpty().contains("noopener"))
                }
            }
        }

    @Test
    fun theCollectionStep_countsTheOptions_andNeedsAtLeastOne(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-op-count") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.COLLECTION, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.COLLECTION)))
                awaitUntil("freeze", 2000) { el.hasButton("Optionen festschreiben") }
                assertTrue(el.flatText().contains("3 Optionen"), el.flatText())
                assertFalse(el.isButtonDisabled("Optionen festschreiben"))
            }
            withConsensusPanel("consensus-op-empty") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.COLLECTION, canManage = true, noOptions = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.COLLECTION)))
                awaitUntil("freeze", 2000) { el.hasButton("Optionen festschreiben") }
                assertTrue(el.isButtonDisabled("Optionen festschreiben"))
                assertTrue(el.flatText().contains("Es gibt noch keine Option."), "the reason is shown")
            }
        }

    @Test
    fun theEvaluatedStep_showsRankAndWinner_withoutDistributionAndWithoutNames(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-op-result") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.EVALUATED, secret = false, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.EVALUATED, secret = false)))
                awaitUntil("result", 2000) { el.flatText().contains("Geringster Widerstand") }
                val text = el.flatText()
                assertTrue(text.contains("Option A") && text.contains("Ø 2,4 von 10"), text)
                assertTrue(text.indexOf("Option A") < text.indexOf("Option B"), "ranked by mean resistance")
                assertTrue(
                    text.contains("Alles bleibt wie bisher (Passivlösung)"),
                    "the status quo option is translated, never the server label",
                )
                assertFalse(text.contains("Status quo (no change)"))
                assertFalse(text.contains("Verteilung"))
                assertEquals(1, el.badgeTexts().count { it == "Geringster Widerstand" }, "one winner badge")
            }
        }

    @Test
    fun everyDestructiveStep_asksFirst_withTheStreamLinesOfAnAnonymousConsensus_andCancelSendsNothing(): Promise<Unit> =
        formTest {
            data class Case(
                val phase: SystemicConsensusStatus,
                val button: String,
                val message: String,
                val write: String,
            )
            val cases =
                listOf(
                    Case(
                        SystemicConsensusStatus.COLLECTION,
                        "Optionen festschreiben",
                        "Danach können keine Optionen mehr hinzukommen.",
                        "freeze",
                    ),
                    Case(SystemicConsensusStatus.RATING, "Bewertung schließen", "Danach sind keine Bewertungen mehr möglich.", "close"),
                    Case(SystemicConsensusStatus.EVALUATED, "Erneut bewerten", "Es beginnt eine neue Bewertungsrunde.", "reopen"),
                )
            cases.forEachIndexed { index, case ->
                withConsensusPanel("consensus-op-dialog-$index") { el, handle, h, _, _ ->
                    h.detail = detail(case.phase, canManage = true)
                    handle.apply(answerOf(ballot(case.phase)))
                    awaitUntil("button", 2000) { el.hasButton(case.button) }
                    el.buttonNamed(case.button).click()
                    var modal = lastOpenModal()
                    assertTrue(modal.flatText().contains(case.message), modal.flatText())
                    if (case.write != "close") {
                        assertTrue(
                            modal.flatText().contains("In diesem Raum läuft kein Live-Stream."),
                            "the stream lines of an anonymous consensus",
                        )
                        assertTrue(modal.flatText().contains("Die Aufzeichnung der Sitzung wird nicht angehalten."))
                    }
                    awaitUntil("focus on Abbrechen", 1000) { document.activeElement === modal.buttonNamed("Abbrechen") }
                    modal.buttonNamed("Abbrechen").click()
                    assertEquals(emptyList(), h.writes, "cancelling sends nothing")
                    el.buttonNamed(case.button).click()
                    modal = lastOpenModal()
                    modal.buttonNamed(case.button).click()
                    awaitUntil("write", 1500) { h.writes.size == 1 }
                    assertEquals("${case.write}:$kId", h.writes.single())
                    awaitUntil("nudged", 1500) { h.nudges == 1 }
                }
            }
        }

    @Test
    fun anOpenConsensus_saysTheStreamKeepsRunning_inTheDialogOfTheStepThatWouldPauseIt(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(status = SystemicConsensusStatus.COLLECTION, secret = false).copy(id = kId))
            withConsensusPanel("consensus-op-open-dialog", world) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.COLLECTION, secret = false, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.COLLECTION, secret = false)))
                awaitUntil("button", 2000) { el.hasButton("Optionen festschreiben") }
                el.buttonNamed("Optionen festschreiben").click()
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("Offen und namentlich: Der Live-Stream läuft weiter."), modal.flatText())
                assertFalse(modal.flatText().contains("In diesem Raum läuft kein Live-Stream."))
                modal.buttonNamed("Abbrechen").click()
            }
        }

    @Test
    fun evaluating_needsNoDialog_andADoubleClickOrADoubleConfirm_sendsExactlyOneRequest(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-op-double-evaluate") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.CLOSED, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.CLOSED)))
                awaitUntil("button", 2000) { el.hasButton("Auswerten") }
                val button = el.buttonNamed("Auswerten")
                button.click()
                button.click()
                awaitUntil("write", 1500) { h.writes.isNotEmpty() }
                awaitUntil("nudged", 1500) { h.nudges == 1 }
                delay(150)
                assertEquals(listOf("evaluate:$kId"), h.writes)
                assertEquals(1, h.nudges)
            }
            withConsensusPanel("consensus-op-double-freeze") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.COLLECTION, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.COLLECTION)))
                awaitUntil("button", 2000) { el.hasButton("Optionen festschreiben") }
                el.buttonNamed("Optionen festschreiben").click()
                val confirm = lastOpenModal().buttonNamed("Optionen festschreiben")
                confirm.click()
                confirm.click()
                awaitUntil("write", 1500) { h.writes.isNotEmpty() }
                delay(150)
                assertEquals(listOf("freeze:$kId"), h.writes)
            }
        }

    @Test
    fun aConflict_reloads_saysSoNeutrally_neverShowsTheServerText_andDoesNotNudge(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-op-conflict") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.CLOSED, canManage = true)
                h.writeFailure = ConflictException("Member 1234 secret server text")
                handle.apply(answerOf(ballot(SystemicConsensusStatus.CLOSED)))
                awaitUntil("button", 2000) { el.hasButton("Auswerten") }
                el.buttonNamed("Auswerten").click()
                awaitUntil("note", 2000) { el.flatText().contains("Der Stand hat sich geändert und wurde neu geladen.") }
                awaitUntil("reloaded", 2000) { h.refreshes >= 1 }
                assertFalse(el.flatText().contains("1234"), "the server text never reaches the DOM")
                assertEquals(0, h.nudges, "a failed write is not announced")
            }
        }

    @Test
    fun aLostNudge_doesNotStopTheReload(): Promise<Unit> =
        formTest {
            val harness = Harness().also { it.nudgeFails = true }
            withConsensusPanel("consensus-op-nudge-lost", harness = harness) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.CLOSED, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.CLOSED)))
                awaitUntil("button", 2000) { el.hasButton("Auswerten") }
                el.buttonNamed("Auswerten").click()
                awaitUntil("reloaded", 2000) { h.refreshes >= 1 }
                assertEquals(0, h.nudges)
            }
        }

    // ── the emergency card ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun HTMLElement.untilHungCard(
        handle: ConferenceVotePanelHandle,
        h: Harness,
    ) {
        handle.onStreamState(ConferenceStreamStatus.PAUSING, ConferenceStreamPauseReason.SECRET_BALLOT)
        awaitUntil("the snapshot", 2000) { h.detailLoads >= 1 }
        delay(50)
        h.scheduler.advance(60_000)
    }

    @Test
    fun aStreamThatDoesNotStop_givesTheModerationTheEmergencyCard_andAMemberOnlyTheSlowHint(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-hung-moderator", context = ctx(moderate = true)) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.untilHungCard(handle, h)
                awaitUntil("hung card", 2000) { el.flatText().contains("Der Stream lässt sich nicht anhalten.") }
                assertTrue(el.hasButton("Stream stoppen"))
                assertTrue(el.allOf("a").any { it.flatText() == "Auf der Konsensieren-Seite bearbeiten" })
                el.buttonNamed("Stream stoppen").click()
                assertEquals(1, h.stopRequests)
            }
            withConsensusPanel("consensus-hung-member") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.untilHungCard(handle, h)
                delay(100)
                assertFalse(el.flatText().contains("Der Stream lässt sich nicht anhalten."), "a plain member gets no emergency card")
                assertFalse(el.hasButton("Stream stoppen"))
                assertTrue(el.flatText().contains("Das dauert länger als üblich."), el.flatText())
            }
        }

    @Test
    fun aManagerWithoutModeration_getsTheEmergencyCard_onceTheSnapshotSaysCanManage(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-hung-manager") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                el.untilHungCard(handle, h)
                awaitUntil("hung card", 2000) { el.flatText().contains("Der Stream lässt sich nicht anhalten.") }
                assertFalse(el.hasButton("Stream stoppen"), "stopping the stream is the moderation's, not the manager's")
                assertTrue(el.allOf("a").any { it.flatText() == "Auf der Konsensieren-Seite bearbeiten" })
            }
        }

    @Test
    fun theOverview_isNotBuiltForAHiddenPanel_andOpeningItStartsTheReads(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-hidden") { el, handle, h, _, _ ->
                handle.setOpen(false)
                h.detail = detail(SystemicConsensusStatus.COLLECTION, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.COLLECTION)))
                delay(150)
                assertEquals(0, h.detailLoads, "a hidden panel reads nothing")
                handle.setOpen(true)
                awaitUntil("freeze", 2000) { el.hasButton("Optionen festschreiben") }
                assertTrue(el.byClass("lapis-vote-overview").shown())
            }
        }

    @Test
    fun theCompactBooth_showsNumbers_andKeepsTheRationaleCollapsedUntilClicked(): Promise<Unit> =
        formTest {
            val options = skOptions().map { if (it.id == "o-a") it.copy(rationale = "Darum der Vorschlag") else it }
            val world = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, options = options).copy(id = kId))
            withConsensusPanel("consensus-compact-why", world = world) { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.RATING, options = options)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.RATING)))
                awaitUntil("the operator's own read of the card", 2000) { h.detailLoads >= 1 }
                el.buttonNamed("Bewerten").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth-compact") != null }
                assertEquals(listOf("P", "1", "2"), el.allOf(".lapis-booth-compact .lapis-sk-num").map { it.textContent.orEmpty().trim() })
                val toggle = el.allOf("button").first { it.textContent?.trim() == "Begründung" }
                val body = assertNotNull(el.querySelector("#" + assertNotNull(toggle.getAttribute("aria-controls"))) as? HTMLElement)
                assertEquals(
                    "none",
                    kotlinx.browser.window
                        .getComputedStyle(body)
                        .display,
                    "collapsed until the member asks: the narrow panel stays narrow",
                )
                toggle.click()
                awaitUntil("opened", 1500) { toggle.getAttribute("aria-expanded") == "true" }
                assertEquals("Darum der Vorschlag", body.textContent)
                assertEquals(0, el.allOf("[title]").size)
            }
        }

    @Test
    fun theOperatorResultList_showsTheOptionNumbers(): Promise<Unit> =
        formTest {
            withConsensusPanel("consensus-op-numbers") { el, handle, h, _, _ ->
                h.detail = detail(SystemicConsensusStatus.EVALUATED, secret = false, canManage = true)
                handle.apply(answerOf(ballot(SystemicConsensusStatus.EVALUATED, secret = false)))
                awaitUntil("result", 2000) { el.flatText().contains("Geringster Widerstand") }
                assertEquals(listOf("1", "2", "P"), el.allOf(".lapis-sk-num").map { it.textContent.orEmpty().trim() })
                assertFalse(el.flatText().contains("Begründung"), "the operator's short list carries no rationale")
            }
        }
}
