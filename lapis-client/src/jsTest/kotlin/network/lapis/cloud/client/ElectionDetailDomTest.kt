package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionBallotDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MemberSummaryDto
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.22: the detail view of an election, mounted for real with a stubbed server -- who sees which action, what each write sends,
 * what a conflict never shows, and what the result and the ballot list may and may not reveal.
 */
class ElectionDetailDomTest {
    // V1.9.38: these values are class-A system timestamps (UTC), now shown in the organization zone. This test is about something else
    // (labels, table/card parity), so it pins the zone to UTC and keeps its literal expectations; the conversion itself is covered by
    // OrganizationTimeTest/OrganizationTimeDomTest.
    @BeforeTest
    fun pinOrganizationZoneToUtc() {
        OrganizationTime.zoneId = "UTC"
    }

    @AfterTest
    fun restoreOrganizationZone() {
        OrganizationTime.zoneId = DEFAULT_ORGANIZATION_ZONE_ID
    }

    private val manager = ElectionUiContext(currentMemberId = "admin-1", isBoardOrAdmin = true)
    private val member = ElectionUiContext(currentMemberId = "m-1", isBoardOrAdmin = false)

    private suspend fun <T> withDetail(
        world: ElectionWorld,
        ctx: ElectionUiContext,
        id: String,
        block: suspend (HTMLElement, List<RecordedRequest>, ElectionRoutes) -> T,
    ): T {
        val routes = electionRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderElectionDetail(root, "e1", ctx)
                awaitUntil("detail rendered", timeoutMs = 3000) { element().flatText().contains(world.election.title) }
                block(element(), calls, routes)
            }
        }
    }

    // ── who sees which action ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun anAdminWhoIsNoElectionCommitteeMember_cannotApprove_andTheTallyIsDisabledWithTheMissingCount(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(election(status = ElectionStatus.CLOSED), participation(isBoard = false, approvals = 0, threshold = 2))
            withDetail(world, manager, "el-admin-closed") { el, _, _ ->
                assertFalse(el.hasButton("Auszählung freigeben"), "no BOARD/ADMIN bypass on the four-eyes approval")
                assertTrue(el.hasButton("Auszählen") && el.isButtonDisabled("Auszählen"))
                assertTrue(el.flatText().contains("Noch 2 Freigaben nötig."), "the reason is visible text, not only a tooltip")
                assertTrue(el.flatText().contains("0 von 2 erforderlichen Freigaben erteilt"))
                val bar = assertNotNull(el.querySelector("[role=progressbar]") as? HTMLElement)
                assertEquals("0", bar.getAttribute("aria-valuenow"))
                assertEquals("2", bar.getAttribute("aria-valuemax"))
            }
        }

    @Test
    fun anElectionCommitteeMember_approvesOnce_thenSeesThatTheyHave(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.CLOSED), participation(isBoard = true, approvals = 0, threshold = 2))
            val routes = electionRoutes()
            world.onRoute = { route ->
                if (route ==
                    routes.approve
                ) {
                    world.participation = participation(isBoard = true, hasApproved = true, approvals = 1, threshold = 2)
                }
            }
            withDetail(world, member, "el-board-approve") { el, calls, _ ->
                el.buttonNamed("Auszählung freigeben").click()
                awaitUntil("approve sent", 1500) { calls.toRoute(routes.approve).size == 1 }
                awaitUntil("reloaded with the own approval", 3000) { el.flatText().contains("Sie haben die Auszählung freigegeben.") }
                assertFalse(el.hasButton("Auszählung freigeben"))
                assertEquals(1, calls.toRoute(routes.approve).size)
                assertEquals("e1", calls.singleCall(routes.approve).rpcParam(0) as String)
            }
        }

    @Test
    fun aPlainMember_seesNoManagementAction_inPreparation(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.PREPARATION), participation(isBoard = false, boardSize = 0))
            withDetail(world, member, "el-plain-member") { el, _, _ ->
                listOf("Abstimmung öffnen", "Wahl abbrechen", "Wahlausschuss speichern", "Kandidatenliste freigeben", "Auszählen").forEach {
                    assertFalse(el.hasButton(it), "a plain member must not see '$it'")
                }
                assertTrue(el.hasButton("Zum Antrag"))
                assertTrue(el.flatText().contains("Noch kein Wahlausschuss bestellt."))
            }
        }

    @Test
    fun openVoting_isDisabledWithItsReasonForATooSmallCommittee_andWorksOnceItIsBigEnough(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.PREPARATION), participation(boardSize = 2))
            val routes = electionRoutes()
            withDetail(world, manager, "el-open-voting") { el, calls, _ ->
                assertTrue(el.isButtonDisabled("Abstimmung öffnen"))
                assertTrue(el.flatText().contains("Der Wahlausschuss braucht mindestens 3 Mitglieder, es sind 2."))
                el.buttonNamed("Abstimmung öffnen").click()
                assertEquals(0, calls.toRoute(routes.openVoting).size, "a disabled button sends nothing")
            }
            val ready = ElectionWorld(election(status = ElectionStatus.PREPARATION), participation(boardSize = 3))
            withDetail(ready, manager, "el-open-voting-ok") { el, calls, _ ->
                assertFalse(el.isButtonDisabled("Abstimmung öffnen"))
                el.buttonNamed("Abstimmung öffnen").click()
                awaitUntil("openVoting sent", 1500) { calls.toRoute(routes.openVoting).size == 1 }
                assertEquals("e1", calls.singleCall(routes.openVoting).rpcParam(0) as String)
            }
        }

    @Test
    fun aMemberWhoIsNotOnTheElectoralRoll_isToldSo_andWhoHasVoted_isToldThat(): Promise<Unit> =
        formTest {
            val notEligible = ElectionWorld(election(status = ElectionStatus.OPEN), participation(eligible = false))
            withDetail(notEligible, member, "el-not-eligible") { el, _, _ ->
                assertTrue(el.flatText().contains("Sie stehen nicht im Wählerverzeichnis dieser Wahl und können nicht abstimmen."))
                assertFalse(el.hasButton("Zur Stimmabgabe"))
            }
            val voted = ElectionWorld(election(status = ElectionStatus.OPEN), participation(eligible = true, hasVoted = true))
            withDetail(voted, member, "el-has-voted") { el, _, _ ->
                assertTrue(el.flatText().contains("Sie haben bereits abgestimmt."))
                assertFalse(el.hasButton("Zur Stimmabgabe"))
            }
            val may = ElectionWorld(election(status = ElectionStatus.OPEN), participation(eligible = true))
            withDetail(may, member, "el-may-vote") { el, _, _ -> assertTrue(el.hasButton("Zur Stimmabgabe")) }
        }

    // ── abort ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun abortingARunningElection_needsTheTitleTyped_aSimpleDialogSufficesBefore(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN, title = "Vorstandswahl 2026"), participation(eligible = false))
            val routes = electionRoutes()
            withDetail(world, manager, "el-abort-open") { el, calls, _ ->
                el.buttonNamed("Wahl abbrechen").click()
                val modal = lastOpenModal()
                val input = assertNotNull(modal.querySelector("input") as? HTMLInputElement, "the typed confirmation field")
                assertTrue(modal.isButtonDisabled("Wahl abbrechen"), "disabled until the title is typed")
                modal.buttonNamed("Wahl abbrechen").click()
                assertEquals(0, calls.toRoute(routes.abort).size)
                input.value = "Vorstandswahl 2026"
                input.dispatchEvent(Event("input"))
                awaitUntil("confirm enabled", 1500) { !modal.isButtonDisabled("Wahl abbrechen") }
                modal.buttonNamed("Wahl abbrechen").click()
                awaitUntil("abort sent", 1500) { calls.toRoute(routes.abort).size == 1 }
            }
            val prep = ElectionWorld(election(status = ElectionStatus.PREPARATION), participation(boardSize = 0))
            withDetail(prep, manager, "el-abort-prep") { el, calls, _ ->
                el.buttonNamed("Wahl abbrechen").click()
                val modal = lastOpenModal()
                assertEquals(null, modal.querySelector("input"), "no typed confirmation before voting has opened")
                modal.buttonNamed("Abbrechen").click()
                assertEquals(0, calls.toRoute(routes.abort).size, "cancelling the dialog sends nothing")
            }
        }

    // ── election committee ────────────────────────────────────────────────────────────────────────────────────────

    private fun members(count: Int = 4) = (1..count).map { MemberSummaryDto(id = "m$it", displayName = "Mitglied $it") }

    private fun HTMLElement.addBoardMember(id: String) {
        chooseIn("Mitglied hinzufügen", id)
        buttonNamed("Hinzufügen").click()
    }

    private suspend fun HTMLElement.awaitBoardForm() =
        awaitUntil("board form built", 3000) { allOf("label").any { it.textContent.orEmpty().startsWith("Mitglied hinzufügen") } }

    @Test
    fun theBoardForm_enforcesThreeToTwentyFive_offersNoDuplicate_andSendsTheChosenIdsInOrder(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.PREPARATION), participation(boardSize = 0), members = members())
            val routes = electionRoutes()
            withDetail(world, manager, "el-board-form") { el, calls, _ ->
                el.awaitBoardForm()
                el.addBoardMember("m1")
                el.addBoardMember("m2")
                assertFalse("m1" in el.comboOptionIds("Mitglied hinzufügen"), "a chosen member is no longer offered")
                el.buttonNamed("Wahlausschuss speichern").click()
                assertEquals(0, calls.toRoute(routes.appoint).size, "two members are too few")
                awaitUntil("count error", 1500) { el.flatText().contains("Der Wahlausschuss braucht 3 bis 25 Mitglieder.") }
                el.addBoardMember("m3")
                assertTrue(el.flatText().contains("Ausgewählt: 3"))
                el.buttonNamed("Wahlausschuss speichern").click()
                awaitUntil("appoint sent", 1500) { calls.toRoute(routes.appoint).size == 1 }
                val call = calls.singleCall(routes.appoint)
                assertEquals("e1", call.rpcParam(0) as String)
                assertEquals(listOf("m1", "m2", "m3"), (call.rpcParam(1) as Array<String>).toList())
            }
        }

    @Test
    fun aMemberOfTheTargetExecutiveBoard_isMarkedBeforeSaving_andSavingIsBlocked(): Promise<Unit> =
        formTest {
            val boardCommittee =
                CommitteeDto("c-board", "Vorstand", CommitteeType.EXECUTIVE_BOARD, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0))
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION, targetCommitteeId = "c-board"),
                    participation(boardSize = 0),
                    members = members(),
                    committees = listOf(boardCommittee),
                    targetRoster = listOf(rosterEntry("m3", CommitteeRole.MEMBER, "c-board")),
                )
            val routes = electionRoutes()
            withDetail(world, manager, "el-board-conflict") { el, calls, _ ->
                el.awaitBoardForm()
                el.addBoardMember("m1")
                el.addBoardMember("m2")
                el.addBoardMember("m3")
                assertTrue(
                    el.flatText().contains("Mitglieder des Vorstands-Gremiums dürfen nicht im Wahlausschuss sitzen: Mitglied 3."),
                    el.flatText(),
                )
                el.buttonNamed("Wahlausschuss speichern").click()
                assertEquals(0, calls.toRoute(routes.appoint).size, "the known conflict is blocked client-side")
            }
        }

    @Test
    fun aMemberWhoIsAlsoACandidate_isWarnedAbout_withoutBlocking(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 0),
                    members = members(),
                    candidacies = listOf(candidacy("k1", "m2", "Mitglied 2")),
                )
            val routes = electionRoutes()
            withDetail(world, manager, "el-board-candidate") { el, calls, _ ->
                el.awaitBoardForm()
                el.addBoardMember("m1")
                el.addBoardMember("m2")
                el.addBoardMember("m3")
                assertTrue(el.flatText().contains("Zugleich als Kandidatur eingetragen: Mitglied 2."))
                el.buttonNamed("Wahlausschuss speichern").click()
                awaitUntil("saved despite the warning", 1500) { calls.toRoute(routes.appoint).size == 1 }
            }
        }

    @Test
    fun aServerConflictWhileAppointing_neverPutsTheServerTextIntoThePage(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.PREPARATION), participation(boardSize = 0), members = members())
            val routes = electionRoutes()
            world.failures[routes.appoint] = CONFLICT_EXCEPTION
            withDetail(world, manager, "el-board-server-conflict") { el, calls, _ ->
                el.awaitBoardForm()
                el.addBoardMember("m1")
                el.addBoardMember("m2")
                el.addBoardMember("m3")
                el.buttonNamed("Wahlausschuss speichern").click()
                awaitUntil("appoint attempted", 1500) { calls.toRoute(routes.appoint).size == 1 }
                kotlinx.coroutines.delay(150)
                assertFalse(
                    el.flatText().contains("simulated for a test") ||
                        kotlinx.browser.document.body!!
                            .textContent
                            .orEmpty()
                            .contains("simulated for a test"),
                    "the server's own message must never be rendered",
                )
                assertTrue(el.hasButton("Wahlausschuss speichern"), "the form is still usable after the conflict")
            }
        }

    // ── candidacies ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCandidacyForm_existsOnlyForAPeopleElectionInPreparation_andNotAfterOwnCandidacy(): Promise<Unit> =
        formTest {
            val people =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 0),
                )
            withDetail(people, member, "el-cand-form") { el, _, _ ->
                assertTrue(el.allOf("label").any { it.textContent.orEmpty().startsWith("Motivation (optional)") })
                assertTrue(el.hasButton("Kandidatur einreichen"))
            }
            val yesNo =
                ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.PREPARATION), participation(boardSize = 0))
            withDetail(yesNo, member, "el-cand-yesno") { el, _, _ ->
                assertFalse(el.hasButton("Kandidatur einreichen"))
                assertFalse(el.flatText().contains("Kandidaturen"))
            }
            val already =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 0),
                    candidacies = listOf(candidacy("k1", "m-1", "Ich selbst")),
                )
            withDetail(already, member, "el-cand-own") { el, _, _ -> assertFalse(el.hasButton("Kandidatur einreichen")) }
        }

    @Test
    fun theMotivation_isLimitedToOneThousandCharacters_andSentTrimmed(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 0),
                )
            val routes = electionRoutes()
            withDetail(world, member, "el-cand-motivation") { el, calls, _ ->
                assertEquals("1000", el.controlOf("Motivation (optional)").getAttribute("maxlength"))
                el.typeInto("Motivation (optional)", "x".repeat(1001))
                el.buttonNamed("Kandidatur einreichen").click()
                assertEquals(0, calls.toRoute(routes.submitCandidacy).size, "1001 characters are refused before any request")
                el.typeInto("Motivation (optional)", "  Ich möchte mitgestalten.  ")
                el.buttonNamed("Kandidatur einreichen").click()
                awaitUntil("candidacy sent", 1500) { calls.toRoute(routes.submitCandidacy).size == 1 }
                val call = calls.singleCall(routes.submitCandidacy)
                assertEquals("e1", call.rpcParam(0) as String)
                assertEquals("Ich möchte mitgestalten.", call.rpcParam(1).motivationText as String)
            }
        }

    @Test
    fun withdrawingOwnCandidacy_needsTheConfirmation_andSendsTheCandidacyId(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 0),
                    candidacies = listOf(candidacy("k1", "m-1", "Ich selbst"), candidacy("k2", "m-2", "Andere")),
                )
            val routes = electionRoutes()
            withDetail(world, member, "el-cand-withdraw") { el, calls, _ ->
                assertEquals(
                    1,
                    el.allOf("button").count { it.textContent?.trim() == "Zurückziehen" },
                    "only the own candidacy can be withdrawn here",
                )
                el.buttonNamed("Zurückziehen").click()
                lastOpenModal().buttonNamed("Abbrechen").click()
                assertEquals(0, calls.toRoute(routes.withdraw).size, "no confirmation, no request")
                el.buttonNamed("Zurückziehen").click()
                lastOpenModal().buttonNamed("Zurückziehen").click()
                awaitUntil("withdraw sent", 1500) { calls.toRoute(routes.withdraw).size == 1 }
                assertEquals("k1", calls.singleCall(routes.withdraw).rpcParam(0) as String)
            }
        }

    @Test
    fun releasingTheCandidateList_countsOnlyActiveCandidacies_andNeedsTheConfirmation(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 3),
                    candidacies =
                        listOf(
                            candidacy("k1", "m-1", "Anna"),
                            candidacy("k2", "m-2", "Boris"),
                            candidacy("k3", "m-3", "Cleo", withdrawn = true),
                        ),
                )
            val routes = electionRoutes()
            withDetail(world, manager, "el-release") { el, calls, _ ->
                el.buttonNamed("Kandidatenliste freigeben").click()
                val modal = lastOpenModal()
                assertTrue(modal.flatText().contains("Die Kandidatenliste mit 2 Kandidaturen wird freigegeben."), modal.flatText())
                modal.buttonNamed("Abbrechen").click()
                assertEquals(0, calls.toRoute(routes.release).size)
                el.buttonNamed("Kandidatenliste freigeben").click()
                lastOpenModal().buttonNamed("Freigeben").click()
                awaitUntil("release sent", 1500) { calls.toRoute(routes.release).size == 1 }
            }
            val none =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 3),
                )
            withDetail(none, manager, "el-release-none") { el, _, _ ->
                assertTrue(el.isButtonDisabled("Kandidatenliste freigeben"))
                assertTrue(el.flatText().contains("Es gibt noch keine aktive Kandidatur."))
            }
        }

    @Test
    fun aCandidateNameCarryingAnI18nMarker_isShownSanitized(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION),
                    participation(boardSize = 0),
                    candidacies = listOf(candidacy("k1", "m-2", "###KvI18nS###Wahlausschuss", motivation = "###KvI18nS###Wahlausschuss")),
                )
            withDetail(world, member, "el-cand-marker") { el, _, _ ->
                assertFalse(el.flatText().contains("###KvI18n"), "a forged marker must not survive into the page: ${el.flatText()}")
            }
        }

    // ── result ────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun peopleOptions() = listOf(option("o1", "Anna", 0, "k1"), option("o2", "Boris", 1, "k2"), option("o3", "Cleo", 2, "k3"))

    @Test
    fun thePeopleResult_listsByVotes_marksTheWinner_andLinksTheResolution(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(
                        type = ElectionType.MULTI_CHOICE,
                        status = ElectionStatus.TALLIED,
                        seatCount = 1,
                        options = peopleOptions(),
                        resolutionId = "r1",
                    ),
                    participation(boardSize = 3, ballotCount = 9),
                    result = ElectionResultDto("e1", listOf("o2"), false, null, mapOf("o1" to 2, "o2" to 6, "o3" to 1)),
                )
            withDetail(world, member, "el-result-people") { el, _, _ ->
                val text = el.flatText()
                assertTrue(
                    text.indexOf("Boris") < text.indexOf("Anna") && text.indexOf("Anna") < text.indexOf("Cleo"),
                    "ordered by votes: $text",
                )
                assertEquals(1, el.allOf(".badge").count { it.textContent?.trim() == "Gewählt" })
                assertFalse(text.contains("Es wurde niemand gewählt"))
                assertTrue(el.hasButton("Beschluss im Beschlussbuch"))
                assertTrue(text.contains("Beteiligung: 9 von 4 Wahlberechtigten"))
            }
        }

    @Test
    fun aPeopleTie_saysNobodyWasElected_andShowsNoWinnerBadge(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.TALLIED, options = peopleOptions()),
                    participation(boardSize = 3),
                    result = ElectionResultDto("e1", emptyList(), true, null, mapOf("o1" to 3, "o2" to 3, "o3" to 0)),
                )
            withDetail(world, member, "el-result-tie") { el, _, _ ->
                assertTrue(el.flatText().contains("Es wurde niemand gewählt"))
                assertEquals(0, el.allOf(".badge").count { it.textContent?.trim() == "Gewählt" })
                assertFalse(el.hasButton("Beschluss im Beschlussbuch"), "no resolution id, no link")
            }
        }

    @Test
    fun theYesNoResult_usesTranslatedLabels_andNamesMajorityOrTie(): Promise<Unit> =
        formTest {
            fun yesNoWorld(result: ElectionResultDto) =
                ElectionWorld(
                    election(type = ElectionType.YES_NO, status = ElectionStatus.TALLIED),
                    participation(boardSize = 3),
                    result = result,
                )
            withDetail(
                yesNoWorld(ElectionResultDto("e1", listOf("o-yes"), false, true, mapOf("o-yes" to 3, "o-no" to 1, "o-abstain" to 1))),
                member,
                "el-result-yes",
            ) { el, _, _ ->
                val text = el.flatText()
                assertTrue(text.contains("Die erforderliche Mehrheit wurde erreicht."))
                assertTrue(text.contains("Ja") && text.contains("Nein") && text.contains("Enthaltung"))
                assertFalse(text.contains("YES") || text.contains("ABSTAIN"), "enum names are never shown: $text")
            }
            withDetail(
                yesNoWorld(ElectionResultDto("e1", listOf("o-no"), false, false, mapOf("o-yes" to 1, "o-no" to 3, "o-abstain" to 0))),
                member,
                "el-result-no",
            ) { el, _, _ -> assertTrue(el.flatText().contains("Die erforderliche Mehrheit wurde nicht erreicht.")) }
            withDetail(
                yesNoWorld(ElectionResultDto("e1", emptyList(), true, false, mapOf("o-yes" to 0, "o-no" to 0, "o-abstain" to 2))),
                member,
                "el-result-tie-yesno",
            ) { el, _, _ ->
                val text = el.flatText()
                assertTrue(text.contains("Gleichstand oder keine entscheidenden Stimmen: Der Antrag wurde zurückgestellt."))
                assertFalse(text.contains("nicht erreicht"), "a tie is never shown as 'rejected'")
            }
        }

    // ── ballots and secrecy ───────────────────────────────────────────────────────────────────────────────────────

    private val secretCastAt = LocalDateTime(2026, 5, 2, 7, 45)

    private fun ballot(
        id: String,
        labels: List<String>,
        memberName: String? = null,
    ) = ElectionBallotDto(
        id = id,
        electionId = "e1",
        memberId = memberName?.let { "m-x" },
        memberDisplayName = memberName,
        selectedOptionLabels = labels,
        castAt = secretCastAt,
    )

    private val secrecySentence =
        "Aus Gründen des Wahlgeheimnisses werden bei geheimen Wahlen keine einzelnen Stimmzettel angezeigt; " +
            "Sie können mit Ihrer Quittung prüfen, dass Ihre Stimme gezählt wurde."

    @Test
    fun aSecretElection_neverShowsSingleBallots_neverCallsTheListRoute_andPointsToTheReceipt(): Promise<Unit> =
        formTest {
            val cases =
                listOf(ElectionStatus.OPEN, ElectionStatus.CLOSED, ElectionStatus.TALLIED).flatMap { status ->
                    listOf(0, 1, 5).map { status to it }
                }
            cases.forEach { (status, count) ->
                val world =
                    ElectionWorld(
                        election(type = ElectionType.YES_NO, status = status, secret = true),
                        participation(boardSize = 3, ballotCount = count, eligible = false),
                        // the server would never send these; the client must not even ask, and must not show them if it did
                        ballots =
                            listOf(
                                ballot("ballot-secret-id-1", listOf("YES"), memberName = "Heimlich Waehler"),
                                ballot("ballot-secret-id-2", listOf("NO")),
                            ),
                        result =
                            if (status == ElectionStatus.TALLIED) {
                                ElectionResultDto("e1", listOf("o-yes"), false, true, mapOf("o-yes" to 1, "o-no" to 1, "o-abstain" to 0))
                            } else {
                                null
                            },
                    )
                withDetail(world, member, "el-secret-ballots-$status-$count") { el, calls, routes ->
                    val listCalls = calls.toRoute(routes.listBallots).size
                    assertEquals(0, listCalls, "no listElectionBallots request for a secret election ($status)")
                    val text = el.flatText()
                    assertFalse(el.innerHTML.contains("ballot-secret-id"), "no ballot id in the DOM")
                    assertFalse(text.contains(formatDateTime(secretCastAt).replace(Regex("\\s+"), " ")), "no cast time")
                    assertFalse(text.contains("Heimlich"), "no name")
                    assertFalse(text.contains("Bei einer geheimen Wahl werden die Stimmzettel"), "the old table hint is gone")
                    assertTrue(text.contains("Stimmzettel"), "heading")
                    val countLine =
                        when (count) {
                            0 -> "Es wurden keine Stimmzettel abgegeben."
                            1 -> "1 Stimmzettel abgegeben."
                            else -> "$count Stimmzettel abgegeben."
                        }
                    assertTrue(text.contains(countLine), "count line '$countLine' ($status): $text")
                    val sentenceParagraph = el.allOf("p").firstOrNull { it.flatText().trim() == secrecySentence }
                    val sentence = assertNotNull(sentenceParagraph, "the explanatory sentence")
                    assertFalse(sentence.className.contains("text-muted"), "a normal paragraph")
                    assertFalse(sentence.className.contains("small"), "a normal paragraph")
                    assertTrue(text.indexOf(secrecySentence) < text.indexOf("Quittung prüfen"), "the receipt check follows the sentence")
                    assertEquals(
                        0,
                        el.allOf("h2").filter { it.flatText().contains("Stimmzettel") }.sumOf { h ->
                            generateSequence(h.nextElementSibling) { it.nextElementSibling }
                                .takeWhile { it.tagName != "H2" }
                                .count { it.matches("table, .lapis-card-list") || it.querySelector("table, .lapis-card-list") != null }
                        },
                        "no table in the ballot section",
                    )
                }
            }
        }

    @Test
    fun anOpenElectionsBallotTable_showsNameSelectionAndTime(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.YES_NO, status = ElectionStatus.TALLIED, secret = false),
                    participation(boardSize = 3),
                    ballots = listOf(ballot("b1", listOf("YES"), memberName = "Offene Wählerin")),
                    result = ElectionResultDto("e1", listOf("o-yes"), false, true, mapOf("o-yes" to 1, "o-no" to 0, "o-abstain" to 0)),
                )
            withDetail(world, member, "el-open-ballots") { el, _, _ ->
                assertTrue(el.flatText().contains("Offene Wählerin"))
                assertTrue(el.flatText().contains(formatDateTime(secretCastAt).replace(Regex("\\s+"), " ")), el.flatText())
            }
        }

    @Test
    fun beforeTheTally_onlyTheNumberOfBallotsIsShown(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(eligible = false, ballotCount = 3))
            withDetail(world, member, "el-ballot-count") { el, _, _ ->
                assertTrue(el.flatText().contains("3 Stimmzettel abgegeben."))
                assertEquals(0, el.allOf("table").size + el.allOf(".lapis-card-list").size, "no ballot table while voting is open")
            }
        }

    @Test
    fun checkingAReceipt_sendsTheCodeWithoutSpaces_clearsTheField_andLeavesNoTraceInUrlStorageOrConsole(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.YES_NO, status = ElectionStatus.TALLIED, secret = true),
                    participation(boardSize = 3),
                    result = ElectionResultDto("e1", listOf("o-yes"), false, true, mapOf("o-yes" to 1, "o-no" to 0, "o-abstain" to 0)),
                )
            val routes = electionRoutes()
            world.verification =
                network.lapis.cloud.shared.domain
                    .ReceiptVerificationDto(found = true, optionLabel = "YES")
            withConsoleSpy { consoleCalls ->
                withDetail(world, member, "el-receipt-check") { el, calls, _ ->
                    el.typeInto("Quittungscode", "  Abc1 23_-  Abc123_- Abc123_-Abc ")
                    el.buttonNamed("Prüfen").click()
                    awaitUntil("verify sent", 1500) { calls.toRoute(routes.verify).size == 1 }
                    val call = calls.singleCall(routes.verify)
                    assertEquals("e1", call.rpcParam(0) as String)
                    assertEquals(TEST_RECEIPT, call.rpcParam(1) as String, "whitespace is stripped before the request")
                    awaitUntil("outcome shown", 1500) { el.flatText().contains("Ihre Stimme ist gespeichert und lautet: Ja") }
                    assertEquals("", (el.controlOf("Quittungscode") as HTMLInputElement).value, "the field is cleared after the check")
                    assertFalse(el.innerHTML.contains(TEST_RECEIPT), "the code is not echoed anywhere")
                    assertFalse(
                        kotlinx.browser.window.location.href
                            .contains(TEST_RECEIPT),
                    )
                    assertNoStoredCode()
                }
                assertEquals(0, consoleCalls(), "nothing is logged while a receipt is checked")
            }
        }

    @Test
    fun aReceiptThatIsNotFound_andOneBeforeTheTally_areToldApart(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(status = ElectionStatus.OPEN), participation(eligible = false))
            world.verification =
                network.lapis.cloud.shared.domain
                    .ReceiptVerificationDto(found = false, optionLabel = null)
            withDetail(world, member, "el-receipt-missing") { el, calls, _ ->
                el.typeInto("Quittungscode", TEST_RECEIPT)
                el.buttonNamed("Prüfen").click()
                awaitUntil("not found", 1500) { el.flatText().contains("Zu diesem Code wurde keine Stimme gefunden.") }
                world.verification =
                    network.lapis.cloud.shared.domain
                        .ReceiptVerificationDto(found = true, optionLabel = null)
                el.typeInto("Quittungscode", TEST_RECEIPT)
                el.buttonNamed("Prüfen").click()
                awaitUntil("before tally", 1500) {
                    el.flatText().contains("Ihre Stimme ist gespeichert. Die Auswahl wird erst nach der Auszählung angezeigt.")
                }
                assertEquals(2, calls.toRoute(routes.verify).size)
            }
            val open = ElectionWorld(election(status = ElectionStatus.OPEN, secret = false), participation(eligible = false))
            withDetail(open, member, "el-receipt-open-election") { el, _, _ ->
                assertFalse(el.flatText().contains("Quittung prüfen"), "an open election has no receipts")
            }
        }
}
