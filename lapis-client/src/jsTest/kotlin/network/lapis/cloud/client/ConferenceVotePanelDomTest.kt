package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.button
import io.kvision.panel.Root
import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
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
 * V1.9.25 -- the room voting panel, mounted for real (Karma/Chrome). The booth inside it is the real `ElectionBooth` talking to the
 * stubbed election service of `ElectionDomSupport`, so "the receipt exists only in two DOM nodes and holds the room" is observed, not
 * assumed.
 */
class ConferenceVotePanelDomTest {
    private val electionId = "123e4567-e89b-12d3-a456-426614174000"

    private fun HTMLElement.shown(): Boolean = style.display != "none"

    private fun HTMLElement.byClass(name: String): HTMLElement = assertNotNull(querySelector(".$name") as? HTMLElement, "no .$name")

    private fun HTMLElement.badgeTexts(): List<String> = allOf(".badge").map { it.flatText() }

    private fun openElection(
        secret: Boolean = true,
        type: ElectionType = ElectionType.YES_NO,
    ): ElectionDto = election(type = type, status = ElectionStatus.OPEN, secret = secret)

    private class Probe {
        val locks = mutableListOf<ConferenceVotingLock>()
        var closeRequests = 0
        var boothExits = 0
        var loads = 0
    }

    private suspend fun <T> withPanel(
        id: String,
        world: ElectionWorld = ElectionWorld(election(status = ElectionStatus.OPEN)),
        load: suspend (String) -> ElectionDto = { world.election },
        block: suspend (HTMLElement, ConferenceVotePanelHandle, Probe, List<RecordedRequest>, ElectionRoutes) -> T,
    ): T {
        val routes = electionRoutes()
        val probe = Probe()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                val handle =
                    renderConferenceVotePanel(
                        parent = root,
                        loadElection = {
                            probe.loads++
                            load(it)
                        },
                        onLockChanged = { probe.locks += it },
                        onCloseRequested = { probe.closeRequests++ },
                        onBoothExited = { probe.boothExits++ },
                        meritRpc = MeritOperatorRpc(listScheduledMotions = { emptyList() }),
                    )
                handle.panel.show()
                try {
                    block(element(), handle, probe, calls, routes)
                } finally {
                    handle.dispose()
                    ConferenceReceiptGate.visible = false
                }
            }
        }
    }

    private fun answerOf(
        vararg ballots: RoomBallotDto,
        truncated: Boolean = false,
    ) = voteRoomReduce(ConferenceVoteRoomState(), roomState(*ballots, truncated = truncated))

    private fun HTMLElement.chooseTile(text: String) {
        val label = assertNotNull(allOf("label").firstOrNull { it.textContent?.trim() == text }, "no tile '$text'")
        label.click()
    }

    private suspend fun HTMLElement.castUntilReceipt() {
        awaitUntil("the booth", 2000) { hasButton("Weiter zur Prüfung") }
        chooseTile("Ja")
        buttonNamed("Weiter zur Prüfung").click()
        awaitUntil("review step", 2000) { hasButton("Stimme endgültig abgeben") }
        buttonNamed("Stimme endgültig abgeben").click()
        awaitUntil("the receipt", 3000) { allOf(".lapis-receipt-code").isNotEmpty() }
    }

    // ── the card ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCard_showsTitleStateAndStanding_andOffersTheBoothOnlyToAMemberWhoCanStillVote(): Promise<Unit> =
        formTest {
            data class Case(
                val status: RoomBallotStatus,
                val secret: Boolean,
                val eligible: Boolean,
                val voted: Boolean,
                val badges: List<String>,
                val standing: String,
                val primary: Int,
            )
            val cases =
                listOf(
                    Case(RoomBallotStatus.OPEN, true, true, false, listOf("offen", "geheim"), "Sie sind stimmberechtigt", 1),
                    Case(RoomBallotStatus.OPEN, false, true, false, listOf("offen", "offen – namentlich"), "Sie sind stimmberechtigt", 1),
                    Case(RoomBallotStatus.OPEN, true, true, true, listOf("offen", "geheim"), "Sie haben abgestimmt", 0),
                    Case(RoomBallotStatus.OPEN, true, false, false, listOf("offen", "geheim"), "Nicht stimmberechtigt", 0),
                    Case(
                        RoomBallotStatus.CLOSED_AWAITING_TALLY,
                        true,
                        true,
                        true,
                        listOf("geschlossen, wartet auf Auszählung", "geheim"),
                        "Sie haben abgestimmt",
                        0,
                    ),
                    Case(
                        RoomBallotStatus.DECIDED,
                        false,
                        true,
                        false,
                        listOf("ausgezählt", "offen – namentlich"),
                        "Sie sind stimmberechtigt",
                        0,
                    ),
                )
            cases.forEachIndexed { index, case ->
                mountedForm("vote-card-$index") { root, element ->
                    renderElectionLiveCard(
                        root,
                        roomBallot(
                            id = electionId,
                            status = case.status,
                            secret = case.secret,
                            eligible = case.eligible,
                            voted = case.voted,
                            title = "Vorstandswahl 2026",
                            motionTitle = "Antrag zur Wahl",
                        ),
                    ) {}
                    val el = element()
                    val text = el.flatText()
                    assertTrue(text.contains("Vorstandswahl 2026"), "title: $text")
                    assertTrue(text.contains("Antrag zur Wahl"), "the motion title is shown too when it differs")
                    assertEquals(case.badges, el.badgeTexts(), "case $index")
                    assertTrue(text.contains(case.standing), "case $index: $text")
                    assertEquals(
                        case.primary,
                        el.allOf("button.btn-primary").size,
                        "case $index: exactly one primary action, only when it applies",
                    )
                    if (case.primary == 1) assertTrue(el.hasButton("Zur Wahlkabine"))
                }
            }
        }

    @Test
    fun theCard_ofAClosedOrCountedElection_linksToItsDetailsInANewTabWithoutLeakingTheOpener(): Promise<Unit> =
        formTest {
            mountedForm("vote-card-link") { root, element ->
                renderElectionLiveCard(root, roomBallot(electionId, RoomBallotStatus.DECIDED)) {}
                val link = assertNotNull(element().querySelector("a") as? HTMLElement, "no link")
                assertEquals("#/elections/$electionId", link.getAttribute("href"))
                assertEquals("_blank", link.getAttribute("target"))
                val rel = link.getAttribute("rel").orEmpty()
                assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), "rel=$rel")
                assertEquals("Details in neuem Tab", link.flatText())
            }
            mountedForm("vote-card-link-badid") { root, element ->
                renderElectionLiveCard(root, roomBallot("../../evil", RoomBallotStatus.DECIDED)) {}
                assertNull(element().querySelector("a"), "an id that does not look like ours gets no link")
            }
            mountedForm("vote-card-link-open") { root, element ->
                renderElectionLiveCard(root, roomBallot(electionId, RoomBallotStatus.OPEN, eligible = false)) {}
                assertNull(element().querySelector("a"), "an open election is voted in the booth, not read in a tab")
            }
        }

    @Test
    fun aConsensusBallot_hasItsOwnCard_noReadOnlyRowAnyMore_andAVoteHasItsOwnMeritCard(): Promise<Unit> =
        formTest {
            withPanel("vote-readonly") { el, handle, _, _, _ ->
                handle.apply(
                    answerOf(
                        roomBallot("c1", RoomBallotStatus.DECIDED, kind = RoomBallotKind.CONSENSUS, title = "Konsens"),
                        roomBallot("c2", kind = RoomBallotKind.CONSENSUS, title = "Konsens offen"),
                    ),
                )
                val text = el.flatText()
                assertTrue(text.contains("Konsens") && text.contains("Konsens offen"))
                assertFalse(text.contains("Abstimmung läuft – Stimmabgabe im Raum folgt"), "the reserved read-only row is gone")
                assertTrue(text.contains("Wie funktioniert Konsensieren?"), "a consensus card explains itself")
                assertEquals(0, el.allOf("button.btn-primary").size)
                handle.apply(answerOf(roomBallot("v1", kind = RoomBallotKind.VOTE, secret = false, title = "Haushalt")))
                assertTrue(el.flatText().contains("Meritokratische Abstimmung"), "a vote is not a read-only row any more")
            }
        }

    @Test
    fun theOverview_saysWhenThereIsNothing_andWhenThereIsMore(): Promise<Unit> =
        formTest {
            withPanel("vote-empty") { el, handle, _, _, _ ->
                assertTrue(el.flatText().contains("Wird geladen …"), "before the first answer")
                handle.apply(voteRoomReduce(ConferenceVoteRoomState(), roomState(bound = false)))
                assertTrue(el.flatText().contains("Zurzeit keine Abstimmungen."))
                handle.apply(answerOf(roomBallot("e1"), truncated = true))
                assertTrue(el.flatText().contains("Weitere Abstimmungen – alle Details in der Sitzung"))
            }
        }

    // ── the booth inside the panel ────────────────────────────────────────────────────────────────────────────

    @Test
    fun enteringTheBooth_embedsTheRealBooth_hidesTheOverview_andLocksThePanel(): Promise<Unit> =
        formTest {
            withPanel("vote-enter") { el, handle, probe, _, _ ->
                handle.apply(answerOf(roomBallot(electionId, title = "Vorstandswahl 2026")))
                assertTrue(el.byClass("lapis-vote-overview").shown())
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth") != null }
                assertEquals(1, probe.loads)
                assertTrue(handle.isBoothOpen())
                assertEquals(ConferenceVotingLock.BOOTH, probe.locks.last())
                // KVision renders a hidden widget as no element at all: the overview is out of the DOM while the booth is up
                assertNull(el.querySelector(".lapis-vote-overview"), "the overview is hidden while the booth is open")
                assertTrue(el.byClass("lapis-vote-booth-host").shown())
                assertEquals(1, el.allOf(".lapis-booth").size, "one booth, never a copy")
                assertTrue(el.flatText().contains("Ihre Stimme ist geheim"), "a secret election keeps its own explanation")
                assertFalse(el.flatText().contains("namentlich gespeichert"))
                assertTrue(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"), "the panel cannot be closed from the booth")
                assertEquals("true", el.buttonNamed("Abstimmen schließen").getAttribute("aria-disabled"))
            }
        }

    @Test
    fun anOpenElection_getsTheNamedBallotBanner_inTheBooth(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(openElection(secret = false))
            withPanel("vote-banner", world) { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot(electionId, secret = false)))
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth") != null }
                assertTrue(el.flatText().contains("Diese Abstimmung ist offen und wird namentlich gespeichert"))
                assertFalse(
                    el.flatText().contains("Diese Wahl ist offen: Ihre Stimme"),
                    "the banner replaces the muted line, never doubles it",
                )
            }
        }

    @Test
    fun anElectionThatIsNoLongerOpen_isNotEnteredAndSaysSo_andALoadFailureSaysSoCalmly(): Promise<Unit> =
        formTest {
            val closed = election(status = ElectionStatus.CLOSED)
            withPanel("vote-notopen", load = { closed }) { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot(electionId)))
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the note", 2000) { el.flatText().contains("Diese Wahl ist nicht mehr offen.") }
                assertNull(el.querySelector(".lapis-booth"))
                assertFalse(handle.isBoothOpen())
            }
            withPanel("vote-loadfail", load = { error("secret detail about member 1234") }) { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot(electionId)))
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the note", 2000) {
                    el.flatText().contains("Die Wahlkabine konnte nicht geladen werden. Bitte erneut versuchen.")
                }
                assertFalse(el.flatText().contains("1234"), "no error text is ever shown")
                assertNull(el.querySelector(".lapis-booth"))
            }
        }

    @Test
    fun aPollAnswer_whileTheBoothIsOpen_neverTouchesTheBooth(): Promise<Unit> =
        formTest {
            withPanel("vote-quiet") { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot(electionId)))
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth") != null }
                val boothHost = el.byClass("lapis-vote-booth-host")
                val booth = el.byClass("lapis-booth")
                val before = booth.innerHTML
                handle.apply(
                    voteRoomReduce(
                        voteRoomReduce(ConferenceVoteRoomState(), roomState(roomBallot(electionId))).state,
                        roomState(roomBallot(electionId), roomBallot("e2", title = "Eine zweite Wahl")),
                    ),
                )
                assertSame(boothHost, el.byClass("lapis-vote-booth-host"))
                assertSame(booth, el.byClass("lapis-booth"), "the booth node is the same node")
                assertEquals(before, booth.innerHTML, "and its content did not change")
                assertNull(el.querySelector(".lapis-vote-overview"), "the new list is built, but stays hidden")
            }
        }

    @Test
    fun backToTheOverview_leavesTheBooth_releasesTheLock_andAsksTheServerAgain(): Promise<Unit> =
        formTest {
            withPanel("vote-back") { el, handle, probe, _, _ ->
                handle.apply(answerOf(roomBallot(electionId)))
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the booth", 2000) { el.querySelector(".lapis-booth") != null }
                el.buttonNamed("Zurück zur Übersicht").click()
                assertFalse(handle.isBoothOpen())
                assertTrue(el.byClass("lapis-vote-overview").shown())
                assertNull(el.querySelector(".lapis-vote-booth-host"), "the booth host is hidden again")
                assertEquals(ConferenceVotingLock.NONE, probe.locks.last())
                assertEquals(1, probe.boothExits)
                assertFalse(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"))
            }
        }

    // ── the receipt ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theReceipt_locksThePanel_staysOutOfEveryStore_andIsReleasedByDone(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(openElection(secret = true))
            withPanel("vote-receipt", world) { el, handle, probe, calls, routes ->
                handle.apply(answerOf(roomBallot(electionId)))
                withConsoleSpy { consoleCalls ->
                    el.buttonNamed("Zur Wahlkabine").click()
                    el.castUntilReceipt()
                    assertEquals(1, calls.toRoute(routes.cast).size)
                    // V1.9.46: no ballot list, no ballot table and no chosen option of the own ballot anywhere in the panel
                    assertEquals(0, calls.toRoute(routes.listBallots).size, "the panel never asks for single ballots")
                    assertEquals(0, el.allOf("table").size + el.allOf(".lapis-card-list").size, "no ballot table in the panel")
                    // the hook carried one Boolean; the panel is locked and says why
                    assertEquals(ConferenceVotingLock.RECEIPT, probe.locks.last())
                    assertTrue(ConferenceReceiptGate.visible, "the unload guard and the leave buttons read this")
                    assertTrue(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"))
                    assertTrue(el.buttonNamed("Zurück zur Übersicht").hasAttribute("disabled"))
                    assertEquals("true", el.buttonNamed("Zurück zur Übersicht").getAttribute("aria-disabled"))
                    assertTrue(el.flatText().contains("Bitte notieren Sie zuerst Ihre Quittung."))
                    assertEquals(
                        el.buttonNamed("Abstimmen schließen").getAttribute("aria-describedby"),
                        "lapis-vote-lock-reason",
                    )
                    // a click on a blocked button does nothing
                    el.buttonNamed("Abstimmen schließen").click()
                    el.buttonNamed("Zurück zur Übersicht").click()
                    assertEquals(0, probe.closeRequests)
                    assertTrue(handle.isBoothOpen())
                    // the code exists in exactly the two nodes of the booth and nowhere else
                    val codeNodes = el.allOf(".lapis-receipt-code")
                    assertEquals(2, codeNodes.size)
                    codeNodes.forEach { assertEquals(TEST_RECEIPT, it.textContent) }
                    val body = document.body?.textContent.orEmpty()
                    assertEquals(2, Regex(Regex.escape(TEST_RECEIPT)).findAll(body).count())
                    assertFalse(window.location.href.contains(TEST_RECEIPT))
                    assertNoStoredCode()
                    assertEquals(0, consoleCalls(), "nothing about a ballot is ever logged")
                    // note it down, finish
                    (el.controlOf("Ich habe mir die Quittung notiert.") as org.w3c.dom.HTMLInputElement).click()
                    awaitUntil("done enabled", 1500) { !el.isButtonDisabled("Fertig") }
                    el.buttonNamed("Fertig").click()
                    awaitUntil("booth left", 1500) { !handle.isBoothOpen() }
                    assertEquals(ConferenceVotingLock.NONE, probe.locks.last())
                    assertFalse(ConferenceReceiptGate.visible)
                    assertEquals(0, el.allOf(".lapis-receipt-code").size, "the code is gone with the booth")
                    assertEquals(1, probe.boothExits)
                    assertFalse(el.buttonNamed("Abstimmen schließen").hasAttribute("disabled"))
                    assertNoStoredCode()
                }
            }
        }

    @Test
    fun theBackButton_isDisabledWhileTheBallotIsBeingSent(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(openElection(secret = true), castDelayMs = 400)
            withPanel("vote-busy", world) { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot(electionId)))
                el.buttonNamed("Zur Wahlkabine").click()
                awaitUntil("the booth", 2000) { el.hasButton("Weiter zur Prüfung") }
                el.chooseTile("Ja")
                el.buttonNamed("Weiter zur Prüfung").click()
                awaitUntil("review", 2000) { el.hasButton("Stimme endgültig abgeben") }
                el.buttonNamed("Stimme endgültig abgeben").click()
                awaitUntil("busy", 1000) { el.isButtonDisabled("Zurück zur Übersicht") }
                el.buttonNamed("Zurück zur Übersicht").click()
                assertTrue(handle.isBoothOpen(), "leaving mid-request would leave the receipt without a place to appear")
                awaitUntil("receipt", 3000) { el.allOf(".lapis-receipt-code").isNotEmpty() }
            }
        }

    // ── hook, unload, lock, runtime ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theHookScope_givesBackThePreviousHook_butNeverOverwritesAHookSomebodyElseSetMeanwhile() {
        val saved = electionReceiptVisibilityHook
        try {
            val previous: (Boolean) -> Unit = {}
            electionReceiptVisibilityHook = previous
            val scope = ConferenceReceiptHookScope {}
            scope.install()
            assertNotSame(previous, electionReceiptVisibilityHook)
            scope.restore()
            assertSame(previous, electionReceiptVisibilityHook)

            val other: (Boolean) -> Unit = {}
            val second = ConferenceReceiptHookScope {}
            second.install()
            electionReceiptVisibilityHook = other
            second.restore()
            assertSame(other, electionReceiptVisibilityHook, "a hook set in the meantime is kept")
            second.restore() // idempotent
            assertSame(other, electionReceiptVisibilityHook)
        } finally {
            electionReceiptVisibilityHook = saved
        }
    }

    @Test
    fun disposingThePanel_restoresThePreviousHook(): Promise<Unit> =
        formTest {
            val saved = electionReceiptVisibilityHook
            val previous: (Boolean) -> Unit = {}
            try {
                electionReceiptVisibilityHook = previous
                withPanel("vote-dispose") { _, handle, _, _, _ ->
                    assertNotSame(previous, electionReceiptVisibilityHook, "the panel owns the hook while it lives")
                    handle.dispose()
                    assertSame(previous, electionReceiptVisibilityHook)
                }
            } finally {
                electionReceiptVisibilityHook = saved
            }
        }

    @Test
    fun theUnloadGuard_disconnectsOnBeforeUnloadOnlyWithoutAReceipt_andAlwaysOnPageHide() {
        var receipt = false
        var disconnects = 0
        val guard = ConferenceUnloadGuard(receiptVisible = { receipt }, disconnect = { disconnects++ })
        guard.beforeUnload(Event("beforeunload"))
        assertEquals(1, disconnects, "no receipt: disconnect as before")
        receipt = true
        guard.beforeUnload(Event("beforeunload"))
        assertEquals(1, disconnects, "a receipt is on screen and the browser may ask: the member may stay, so the room is kept")
        guard.pageHide(Event("pagehide"))
        assertEquals(2, disconnects, "the page really goes: disconnect")
    }

    @Test
    fun theUnloadGuard_registersBothListeners_andUninstallRemovesThem(): Promise<Unit> =
        formTest {
            withBeforeUnloadSpy { active ->
                val guard = ConferenceUnloadGuard(receiptVisible = { false }, disconnect = {})
                guard.install()
                assertEquals(1, active())
                guard.uninstall()
                assertEquals(0, active())
            }
        }

    @Test
    fun theLockApplier_disablesTheLeaveButtonsWithAReason_andDoesNotUndoARunningLeave(): Promise<Unit> =
        formTest {
            mountedForm("vote-lockapplier") { root: Root, element ->
                val leave: Button = root.button("Verlassen")
                val end: Button = root.button("Für alle beenden")
                val applier = ConferenceReceiptLockApplier()
                applier.apply(locked = true, leaving = false, buttons = listOf(leave, end, null))
                assertTrue(element().isButtonDisabled("Verlassen"))
                assertTrue(element().isButtonDisabled("Für alle beenden"))
                assertEquals("true", element().buttonNamed("Verlassen").getAttribute("aria-disabled"))
                assertEquals("lapis-vote-lock-reason", element().buttonNamed("Verlassen").getAttribute("aria-describedby"))
                applier.apply(locked = false, leaving = false, buttons = listOf(leave, end))
                assertFalse(element().isButtonDisabled("Verlassen"))
                assertNull(element().buttonNamed("Verlassen").getAttribute("aria-describedby"))
                // a leave that is already running keeps its button disabled
                applier.apply(locked = true, leaving = true, buttons = listOf(leave, end))
                applier.apply(locked = false, leaving = true, buttons = listOf(leave, end))
                assertTrue(element().isButtonDisabled("Verlassen"))
            }
        }

    @Test
    fun theRuntime_disposesThePreviousRegistration_andEveryOneOnlyOnce() {
        var first = 0
        var second = 0
        ConferenceVoteRuntime.register { first++ }
        val registration = ConferenceVoteRuntime.register { second++ }
        assertEquals(1, first, "a new call ends the old one's registration")
        ConferenceVoteRuntime.disposeActive()
        ConferenceVoteRuntime.disposeActive()
        registration.dispose()
        assertEquals(1, second)
        assertEquals(1, first)
    }

    // ── roles ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun onlyAnActiveMemberGetsThePanel_aGuestOrFriendDoesNot() {
        fun session(
            status: MemberStatus,
            guest: Boolean,
        ) = SessionInfoDto(
            memberId = "m1",
            displayName = "X",
            role = AccountRole.MEMBER,
            expiresAt = ELECTION_AT,
            isGuest = guest,
            status = status,
        )
        assertTrue(conferenceIsVotingMember(session(MemberStatus.ACTIVE, guest = false)))
        assertFalse(conferenceIsVotingMember(session(MemberStatus.ACTIVE, guest = true)))
        assertFalse(conferenceIsVotingMember(session(MemberStatus.FRIEND, guest = false)))
        assertFalse(conferenceIsVotingMember(session(MemberStatus.GUEST, guest = true)))
        assertFalse(conferenceIsVotingMember(null))
    }

    @Test
    fun theGuestStatusLine_isHiddenUntilShown_andNamesNoBallot(): Promise<Unit> =
        formTest {
            mountedForm("vote-guestline") { root, element ->
                val line = renderGuestVotingStatusLine(root)
                assertEquals("", element().flatText(), "nothing is shown until ballots are running")
                line.show()
                awaitUntil("shown", 1000) { element().flatText() == "Abstimmungen laufen – nur Mitglieder können abstimmen" }
                assertNull(element().querySelector(".btn"), "a guest gets no button")
            }
        }

    // ── untrusted text ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aMaliciousTitle_isShownAsPlainText_inTheCard_andInTheAnnouncement(): Promise<Unit> =
        formTest {
            val evil = "###KvI18nS###Evil###KvI18nE###<img src=x onerror=alert(1)>"
            withPanel("vote-evil") { el, handle, _, _, _ ->
                handle.apply(answerOf(roomBallot(electionId, title = evil, motionTitle = "x$evil")))
                assertNull(el.querySelector("img"), "no element is ever built from a title")
                assertTrue(el.flatText().contains("<img src=x onerror=alert(1)>"), "it is shown as text")
                assertFalse(el.flatText().contains("###KvI18nS###"), "the marker is stripped, not interpreted")
                handle.announce(evil)
                awaitUntil("announced", 1000) { el.parentElementText().contains("Neue Abstimmung geöffnet:") }
                val live = assertNotNull(document.querySelector("[aria-live=polite].visually-hidden") as? HTMLElement)
                assertNull(live.querySelector("img"))
                assertFalse(live.textContent.orEmpty().contains("###KvI18nS###"))
                assertTrue(live.textContent.orEmpty().contains("<img src=x"))
            }
        }

    private fun HTMLElement.parentElementText(): String = (parentElement as? HTMLElement ?: this).flatText()

    @Test
    fun announcing_doesNotMoveTheFocus_andTheLiveRegionLivesOutsideThePanel(): Promise<Unit> =
        formTest {
            withPanel("vote-announce") { el, handle, _, _, _ ->
                val outside = document.createElement("button") as HTMLElement
                document.body?.appendChild(outside)
                try {
                    outside.focus()
                    handle.announce("Vorstandswahl")
                    awaitUntil("announced", 1000) {
                        (document.querySelector("[aria-live=polite].visually-hidden") as? HTMLElement)
                            ?.textContent
                            .orEmpty()
                            .contains("Neue Abstimmung geöffnet: Vorstandswahl")
                    }
                    assertSame(outside, document.activeElement, "an announcement never takes the focus")
                    assertNull(
                        el.byClass("lapis-conference-voting").querySelector("[aria-live]"),
                        "the live region is not inside the panel",
                    )
                } finally {
                    outside.remove()
                }
            }
        }

    // ── the toggle button ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theToggle_carriesTheBadgeAndAnAccessibleName_andHidesWhenAsked(): Promise<Unit> =
        formTest {
            mountedForm("vote-toggle") { root, element ->
                val toggle = root.conferenceVotingToggle()
                assertTrue(element().allOf("button").isEmpty(), "hidden until the room is bound: not rendered at all")
                toggle.update(openCount = 2, pressed = true, visible = true)
                awaitUntil("badge built", 1000) { element().querySelector(".lapis-conference-control-badge") != null }
                val badge = element().byClass("lapis-conference-control-badge")
                val button = element().allOf("button").single()
                awaitUntil("labelled", 1000) { button.getAttribute("aria-label") == "Abstimmen, offen: 2" }
                assertEquals("2", badge.textContent)
                assertEquals("flex", badge.style.display)
                assertEquals("true", button.getAttribute("aria-pressed"))
                assertTrue(button.classList.contains("active"))
                assertTrue(button.shown())
                toggle.update(openCount = 0, pressed = false, visible = true)
                awaitUntil("plain label", 1000) { button.getAttribute("aria-label") == "Abstimmen" }
                assertEquals("none", badge.style.display)
                assertEquals("false", button.getAttribute("aria-pressed"))
                assertFalse(button.classList.contains("active"))
                toggle.update(openCount = 0, pressed = false, visible = false)
                awaitUntil("hidden", 1000) { element().allOf("button").isEmpty() }
                toggle.update(openCount = 3, pressed = false, visible = true)
                awaitUntil("badge again", 1000) { element().querySelector(".lapis-conference-control-badge")?.textContent == "3" }
            }
        }

    @Test
    fun theToggleButtonHasAnAccessibleNameFromTheFirstRender(): Promise<Unit> =
        formTest {
            mountedForm("vote-toggle-name") { root, element ->
                root.conferenceVotingToggle().update(openCount = 0, pressed = false, visible = true)
                awaitUntil("rendered", 1000) { element().allOf("button").size == 1 }
                val button = element().allOf("button").single()
                assertEquals("Abstimmen", button.getAttribute("aria-label"))
                assertEquals("Abstimmen", button.title)
            }
        }
}
