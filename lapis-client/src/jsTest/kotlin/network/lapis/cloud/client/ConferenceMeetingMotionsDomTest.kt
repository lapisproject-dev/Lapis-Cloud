package network.lapis.cloud.client

import io.kvision.panel.Root
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.MeetingSessionPhase
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SessionAgendaItemDto
import network.lapis.cloud.shared.domain.SessionMotionDto
import network.lapis.cloud.shared.domain.SessionMotionRefDto
import network.lapis.cloud.shared.domain.SessionMotionVoteDto
import network.lapis.cloud.shared.domain.SessionMotionVoteStatus
import network.lapis.cloud.shared.domain.SessionResolutionDto
import network.lapis.cloud.shared.domain.SessionRosterEntryDto
import network.lapis.cloud.shared.rpc.AgendaMovedMeanwhileException
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.238 -- the motion part of the Sitzung panel mounted for real: who sees which buttons, the banner, the two dialogs and the resolution card. */
class ConferenceMeetingMotionsDomTest {
    private val agenda = listOf(SessionAgendaItemDto("a1", 1, "Haushalt"), SessionAgendaItemDto("a2", 2, "Verschiedenes"))

    private fun roster() =
        (0 until 5).map {
            SessionRosterEntryDto(memberId = "p$it", displayName = "Person ${'A' + it}", status = AttendanceStatus.PRESENT)
        }

    private fun motion(
        id: String = "m1",
        status: MotionStatus = MotionStatus.SCHEDULED,
        text: String = "Der Verein kauft ein Fahrrad.",
        vote: SessionMotionVoteDto? = null,
        resolution: SessionResolutionDto? = null,
        item: String = "a1",
    ) = SessionMotionDto(id, item, "Fahrrad kaufen", text, status, vote, resolution)

    private fun vote(
        status: SessionMotionVoteStatus = SessionMotionVoteStatus.OPEN,
        secret: Boolean = false,
        ballots: Int? = null,
        eligible: Int? = null,
        approvals: Int? = null,
        threshold: Int? = null,
        canClose: Boolean = true,
    ) = SessionMotionVoteDto("e1", "m1", "Fahrrad kaufen", status, secret, ballots, eligible, approvals, threshold, canClose)

    private fun resolution(
        status: ResolutionStatus = ResolutionStatus.ADOPTED,
        withheld: Boolean = false,
        quorum: Boolean = true,
    ) = SessionResolutionDto("r1", "2030-001", status, 5, 1, 1, withheld, quorum, LocalDateTime(2030, 5, 4, 19, 40))

    private fun state(
        role: MeetingSessionRole = MeetingSessionRole.LEAD,
        manage: Boolean = role == MeetingSessionRole.LEAD,
        motions: List<SessionMotionDto> = listOf(motion()),
        running: SessionMotionVoteDto? = null,
        attachable: List<SessionMotionRefDto> = emptyList(),
        liveMet: Boolean = true,
        established: Boolean = true,
    ) = ConferenceMeetingStateDto(
        meetingId = "mt1",
        meetingTitle = "Vorstandssitzung",
        committeeId = "c1",
        phase = MeetingSessionPhase.OPEN,
        openedAt = LocalDateTime(2030, 5, 4, 19, 5),
        callerRole = role,
        ruleKind = AttendanceRuleKind.NAME_LIST,
        liveQuorum =
            if (role ==
                MeetingSessionRole.PARTICIPANT
            ) {
                null
            } else {
                QuorumResultDto("mt1", 5, if (liveMet) 4 else 1, 3, 50, met = liveMet)
            },
        establishedQuorum =
            if (established) {
                network.lapis.cloud.shared.domain
                    .EstablishedQuorumDto(LocalDateTime(2030, 5, 4, 19, 10), 4, 3, 5, false)
            } else {
                null
            },
        agenda = agenda,
        currentAgendaItemId = "a1",
        roster = if (role == MeetingSessionRole.PARTICIPANT) emptyList() else roster(),
        agendaMotions = motions,
        runningMotionVote = running,
        canManageMotions = manage,
        attachableMotions = attachable,
    )

    private class Calls {
        val attached = mutableListOf<List<String>>()
        val opened = mutableListOf<List<Any>>()
        val closed = mutableListOf<String>()
        var next: ConferenceMeetingStateDto? = null
        var openMoved = false
        var stale = 0
        var votingPanel = 0
    }

    private suspend fun <T> withPanel(
        calls: Calls,
        block: suspend (HTMLElement, ConferenceMeetingPanelHandle) -> T,
    ): T {
        val rpc =
            ConferenceMeetingRpc(
                getState = { calls.next },
                attach = { _, meeting, motion, item ->
                    calls.attached += listOf(meeting, motion, item)
                    calls.next!!
                },
                openVote = { _, meeting, motion, item, secret, board ->
                    if (calls.openMoved) throw AgendaMovedMeanwhileException()
                    calls.opened += listOf<Any>(meeting, motion, item, secret, board)
                    calls.next!!
                },
                closeVoting = { calls.closed += it },
            )
        return mountedForm("meeting-motions") { root: Root, element ->
            val handle =
                renderConferenceMeetingPanel(
                    parent = root,
                    roomId = "r1",
                    rpc = rpc,
                    guestSearch = { { _, _ -> null } },
                    onStaleState = { calls.stale++ },
                    onOpenVotingPanel = { calls.votingPanel++ },
                )
            handle.setOpen(true)
            try {
                block(element(), handle)
            } finally {
                handle.dispose()
            }
        }
    }

    private fun HTMLElement.text(): String = textContent.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun HTMLElement.has(text: String): Boolean = allOf("button").any { it.text() == text }

    private fun HTMLElement.button(text: String): HTMLButtonElement =
        assertNotNull(allOf("button").firstOrNull { it.text() == text }, "no button '$text'") as HTMLButtonElement

    @Test
    fun theLead_seesBothButtons_aParticipantAndTheMinuteTakerSeeNone(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(attachable = listOf(SessionMotionRefDto("m9", "Lose", MotionStatus.REVIEWED))))
                awaitUntil("motion card") { el.text().contains("Fahrrad kaufen") }
                assertTrue(el.has("Abstimmung eröffnen"))
                assertTrue(el.has("Antrag zuordnen"))
                assertTrue(el.text().contains("Anträge zu diesem Punkt"))
                listOf(MeetingSessionRole.PARTICIPANT, MeetingSessionRole.MINUTES).forEach { role ->
                    handle.apply(state(role = role))
                    awaitUntil("read-only view of $role") { !el.has("Abstimmung eröffnen") && el.text().contains("Fahrrad kaufen") }
                    assertFalse(el.has("Antrag zuordnen"))
                }
            }
        }

    @Test
    fun theOpenButton_isLockedWhileAVoteRuns_andTheBannerIsALiveRegion(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(motions = listOf(motion(), motion(id = "m2")), running = vote(ballots = 2, eligible = 5)))
                awaitUntil("banner") { el.text().contains("Abstimmung läuft: Fahrrad kaufen") }
                val region = assertNotNull(el.querySelector(".conference-meeting-banner-host") as? HTMLElement)
                assertEquals("polite", region.getAttribute("aria-live"))
                assertTrue(el.text().contains("2 von 5 abgegeben"))
                assertTrue(el.allOf("button").filter { it.text() == "Abstimmung eröffnen" }.all { (it as HTMLButtonElement).disabled })
                assertTrue(el.text().contains("Es läuft bereits eine Abstimmung."))
                el.button("Jetzt abstimmen").click()
                assertEquals(1, calls.votingPanel)
            }
        }

    @Test
    fun aSecretVote_hasNoCounter_andAParticipantGetsTheBannerWithoutControls(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(role = MeetingSessionRole.LEAD, running = vote(secret = true)))
                awaitUntil("banner") { el.text().contains("Abstimmung läuft") }
                assertFalse(el.text().contains("abgegeben"), "no counter at all for a secret vote")
                handle.apply(state(role = MeetingSessionRole.PARTICIPANT, running = vote()))
                awaitUntil("participant banner") { el.has("Jetzt abstimmen") }
                assertFalse(el.has("Abstimmung schließen"))
                assertFalse(el.text().contains("abgegeben"))
            }
        }

    @Test
    fun aLeadWhoIsNotOnTheBoard_getsNoCloseButton(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(running = vote(ballots = 1, eligible = 5, canClose = false)))
                awaitUntil("banner") { el.has("Jetzt abstimmen") }
                assertFalse(el.has("Abstimmung schließen"))
            }
        }

    @Test
    fun anAbortedVote_canBeOpenedAgain_aFinishedOneCannot(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(motions = listOf(motion(vote = vote(status = SessionMotionVoteStatus.ABORTED)))))
                awaitUntil("motion card") { el.text().contains("Fahrrad kaufen") }
                assertTrue(el.has("Abstimmung eröffnen"))
                handle.apply(state(motions = listOf(motion(vote = vote(status = SessionMotionVoteStatus.TALLIED)))))
                awaitUntil("tallied card") { !el.has("Abstimmung eröffnen") && el.text().contains("Fahrrad kaufen") }
            }
        }

    @Test
    fun closing_asksFirst_thenClosesAndReadsTheStateAgain(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(running = vote(ballots = 1, eligible = 5)))
                awaitUntil("close") { el.has("Abstimmung schließen") }
                calls.next = state(running = vote(status = SessionMotionVoteStatus.CLOSED_AWAITING_TALLY, approvals = 0, threshold = 2))
                el.button("Abstimmung schließen").click()
                awaitUntil("dialog") { lastOpenModal().text().contains("keine weiteren Stimmen") }
                assertTrue(calls.closed.isEmpty(), "nothing is sent before the answer")
                lastOpenModal().button("Abstimmung schließen").click()
                awaitUntil("closed") { calls.closed == listOf("e1") }
                awaitUntil("tally line") { el.text().contains("Auszählung: 0 von 2 Freigaben") }
                assertTrue(el.has("Im Panel „Abstimmen“ auszählen"))
            }
        }

    @Test
    fun theOpenDialog_warnsWithoutQuorum_andNeedsThreePeople(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(liveMet = false, established = false))
                awaitUntil("open button") { el.has("Abstimmung eröffnen") }
                calls.next = state(running = vote())
                el.button("Abstimmung eröffnen").click()
                awaitUntil("dialog") { lastOpenModal().text().contains("derzeit nicht beschlussfähig (1 von 5 anwesend, nötig 3)") }
                val modal = lastOpenModal()
                assertTrue(modal.text().contains("Der Beschluss übernimmt diesen Wortlaut unverändert."))
                assertTrue(modal.text().contains("Bei weniger als 5 Stimmen werden die Zahlen nicht angezeigt."))
                val boxes = modal.allOf("input[type='checkbox']").map { it as HTMLInputElement }
                // the secret switch + five people; the first three people are ticked
                assertEquals(6, boxes.size)
                assertEquals(listOf(false, true, true, true, false, false), boxes.map { it.checked })
                val confirm = modal.button("Abstimmung eröffnen")
                assertFalse(confirm.disabled)
                boxes[1].click()
                awaitUntil("locked") { modal.button("Abstimmung eröffnen").disabled }
                assertTrue(modal.text().contains("Bitte wählen Sie mindestens 3 Personen."))
                boxes[4].click()
                awaitUntil("unlocked") { !modal.button("Abstimmung eröffnen").disabled }
                boxes[0].click() // secret
                modal.button("Abstimmung eröffnen").click()
                awaitUntil("sent") { calls.opened.size == 1 }
                val sent = calls.opened.single()
                assertEquals(listOf<Any>("mt1", "m1", "a1", true), sent.take(4))
                assertEquals(listOf("p1", "p2", "p3"), sent[4])
            }
        }

    @Test
    fun withAnEstablishedQuorum_noWarningIsShown_andWithoutOneTheHintIs(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(established = false))
                awaitUntil("button") { el.has("Abstimmung eröffnen") }
                el.button("Abstimmung eröffnen").click()
                awaitUntil("hint") { lastOpenModal().text().contains("Die Beschlussfähigkeit wurde noch nicht festgestellt.") }
                assertFalse(lastOpenModal().text().contains("derzeit nicht beschlussfähig"))
                lastOpenModal().button("Abbrechen").click()
            }
        }

    @Test
    fun attachingAsksWhichMotion_andSendsTheCurrentItem(): Promise<Unit> =
        formTest {
            val calls = Calls()
            withPanel(calls) { el, handle ->
                handle.apply(state(attachable = listOf(SessionMotionRefDto("m9", "Lose Anfrage", MotionStatus.REVIEWED))))
                awaitUntil("button") { el.has("Antrag zuordnen") }
                calls.next = state()
                el.button("Antrag zuordnen").click()
                awaitUntil("question") { lastOpenModal().text().contains("Antrag „Lose Anfrage“ diesem Punkt zuordnen?") }
                assertTrue(calls.attached.isEmpty())
                lastOpenModal().button("Zuordnen").click()
                awaitUntil("sent") { calls.attached == listOf(listOf("mt1", "m9", "a1")) }
            }
        }

    @Test
    fun aStaleAgendaItem_showsTheHint_andAsksForAFreshReading(): Promise<Unit> =
        formTest {
            val calls = Calls().also { it.openMoved = true }
            withPanel(calls) { el, handle ->
                handle.apply(state())
                awaitUntil("button") { el.has("Abstimmung eröffnen") }
                el.button("Abstimmung eröffnen").click()
                awaitUntil("dialog") { lastOpenModal().has("Abstimmung eröffnen") }
                lastOpenModal().button("Abstimmung eröffnen").click()
                awaitUntil("hint") { el.text().contains("Der Tagesordnungspunkt hat sich inzwischen geändert.") }
                assertEquals(1, calls.stale)
            }
        }

    @Test
    fun resolutionCards_sayAdoptedRejectedTieAndWithheldFigures(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                fun shown(
                    status: MotionStatus,
                    resolution: SessionResolutionDto,
                ) = state(
                    motions =
                        listOf(
                            motion(status = status, resolution = resolution, vote = vote(status = SessionMotionVoteStatus.TALLIED)),
                        ),
                )
                handle.apply(shown(MotionStatus.RESOLVED, resolution()))
                awaitUntil("adopted") { el.text().contains("Angenommen") && el.text().contains("Ja 5 · Nein 1 · Enthaltung 1") }
                assertTrue(el.text().contains("Beschluss 2030-001"))
                val card = assertNotNull(el.querySelector(".conference-meeting-resolution") as? HTMLElement)
                assertTrue(card.text().contains("beschlussfähig"))
                assertFalse(card.text().contains("nicht beschlussfähig"))
                handle.apply(shown(MotionStatus.REJECTED, resolution(status = ResolutionStatus.REJECTED, quorum = false)))
                awaitUntil("rejected") { el.text().contains("Abgelehnt") && el.text().contains("nicht beschlussfähig") }
                assertFalse(
                    el
                        .allOf(".conference-meeting-resolution")
                        .first()
                        .text()
                        .contains("Ja 4"),
                )
                handle.apply(shown(MotionStatus.POSTPONED, resolution(status = ResolutionStatus.POSTPONED)))
                awaitUntil("tie") { el.text().contains("Stimmengleichheit") }
                handle.apply(shown(MotionStatus.RESOLVED, resolution(withheld = true)))
                awaitUntil("withheld") { el.text().contains("Zahlen nicht angezeigt (zu wenige Stimmen)") }
                assertFalse(el.text().contains("Ja 5"), "no figure next to the withheld note")
                assertFalse(el.has("Abstimmung eröffnen"), "a decided motion has no open button")
            }
        }

    @Test
    fun aLongText_isClamped_andTheToggleSetsAriaExpanded(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(motions = listOf(motion(text = "Wort ".repeat(60)))))
                awaitUntil("toggle") { el.has("Mehr anzeigen") }
                val toggle = el.button("Mehr anzeigen")
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                val text = assertNotNull(el.querySelector(".conference-meeting-motion-text") as? HTMLElement)
                assertFalse(text.classList.contains("expanded"))
                toggle.click()
                awaitUntil("expanded") { el.button("Weniger anzeigen").getAttribute("aria-expanded") == "true" }
                assertTrue(text.classList.contains("expanded"))
                el.button("Weniger anzeigen").click()
                awaitUntil("collapsed") { el.has("Mehr anzeigen") }
            }
        }

    @Test
    fun otherItems_showOnlyTheCounts_noControls(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(state(motions = listOf(motion(item = "a2", status = MotionStatus.RESOLVED, resolution = resolution()))))
                awaitUntil("counts") { el.text().contains("Anträge: 1 · Beschlüsse: 1") }
                assertEquals(0, el.allOf("article").size, "the cards belong to the current item only")
                assertFalse(el.has("Abstimmung eröffnen"))
            }
        }

    // V1.9.242 -- checked, not changed: "Anträge zu diesem Punkt" lists only motions attached to the CURRENT item; an attachable motion is
    // offered in the dialog of "Antrag zuordnen" and nowhere else.
    @Test
    fun underTheCurrentItem_onlyAttachedMotionsOfThatItemAreListed_theAttachableOneIsOnlyInTheDialog(): Promise<Unit> =
        formTest {
            withPanel(Calls()) { el, handle ->
                handle.apply(
                    state(
                        motions =
                            listOf(
                                motion(id = "m1", item = "a1"),
                                motion(id = "m2", item = "a2").copy(title = "Anderer Punkt"),
                            ),
                        attachable = listOf(SessionMotionRefDto("m9", "Lose Anfrage", MotionStatus.REVIEWED)),
                    ),
                )
                awaitUntil("motion card") { el.text().contains("Fahrrad kaufen") }
                assertFalse(el.text().contains("Anderer Punkt"), "a motion of another item is not listed under the current one")
                assertFalse(el.text().contains("Lose Anfrage"), "an attachable motion is not listed under the item")
                el.button("Antrag zuordnen").click()
                awaitUntil("dialog") { lastOpenModal().text().contains("Antrag „Lose Anfrage“ diesem Punkt zuordnen?") }
            }
        }
}
