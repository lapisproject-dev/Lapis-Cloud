package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.22: "Wahl eröffnen" in the resolution section of a scheduled motion -- when it is offered, when an election already runs, what
 * the form shows per election type, the live majority explanation, and exactly what it sends.
 */
class MotionsOpenElectionDomTest {
    private val committees =
        listOf(
            CommitteeDto("c1", "Mitgliederversammlung", CommitteeType.GENERAL_ASSEMBLY, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0)),
            CommitteeDto("c9", "Vorstand", CommitteeType.EXECUTIVE_BOARD, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0)),
        )

    private fun hasLabel(
        el: HTMLElement,
        label: String,
    ) = el.allOf("label").any {
        it.textContent
            .orEmpty()
            .trim()
            .removeSuffix("*")
            .trim()
            .startsWith(label)
    }

    private fun runningVote() =
        VoteDto(
            id = "v1",
            motionId = "m1",
            meetingId = "s1",
            title = "V",
            status = VoteStatus.OPEN,
            options = emptyList(),
            winnerOptionId = null,
            secondPriceLtr = null,
            openedById = "x",
            openedByDisplayName = "X",
            openedAt = ELECTION_AT,
            closedAt = null,
            resolutionId = null,
        )

    @Test
    fun theElectionForm_isOfferedNextToTheTwoExistingWays(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-offer") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = emptyList(),
                    canManage = true,
                    activeVote = null,
                    onSelectMotion = {},
                    onChanged = {},
                    elections = emptyList(),
                    committees = committees,
                )
                val text = element().flatText()
                assertTrue(text.contains("Committee-Quorum entscheiden") && text.contains("Meritokratische Vote eröffnen"))
                assertTrue(element().hasButton("Wahl eröffnen"))
                assertTrue(hasLabel(element(), "Wahlart"))
            }
        }

    @Test
    fun withoutManagementRights_nothingIsOffered(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-no-rights") { root, element ->
                renderResolutionSection(
                    root,
                    motionDto(),
                    emptyList(),
                    canManage = false,
                    activeVote = null,
                    onSelectMotion = {},
                    onChanged = {},
                )
                assertFalse(element().hasButton("Wahl eröffnen"))
            }
        }

    @Test
    fun aRunningElection_replacesAllThreeWaysWithTheWayBackToIt(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-running") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = emptyList(),
                    canManage = true,
                    activeVote = null,
                    onSelectMotion = {},
                    onChanged = {},
                    elections = listOf(election(status = ElectionStatus.OPEN)),
                    committees = committees,
                )
                val el = element()
                // V1.9.54: while it runs the hint says what to do first, as a note
                assertTrue(
                    el.flatText().contains(
                        "Zu diesem Antrag läuft noch eine Wahl. Werten Sie sie aus oder brechen Sie sie ab, bevor Sie entscheiden.",
                    ),
                )
                assertEquals(1, el.allOf("[role=note]").size)
                assertTrue(el.hasButton("Zur Wahl"))
                assertFalse(el.hasButton("Wahl eröffnen"))
                assertFalse(el.hasButton("Entscheidung speichern"), "the quorum path would race the election")
                assertFalse(el.hasButton("Vote eröffnen"), "the meritocratic path would race the election")
            }
            listOf(ElectionStatus.PREPARATION, ElectionStatus.CANDIDATE_LIST_RELEASED, ElectionStatus.CLOSED).forEach { status ->
                mountedForm("motion-election-running-$status") { root, element ->
                    renderResolutionSection(
                        panel = root,
                        motion = motionDto(),
                        pendingAmendments = emptyList(),
                        canManage = true,
                        activeVote = null,
                        onSelectMotion = {},
                        onChanged = {},
                        elections = listOf(election(status = status)),
                        committees = committees,
                    )
                    assertTrue(element().flatText().contains("Zu diesem Antrag läuft noch eine Wahl."), "$status")
                    assertFalse(element().hasButton("Entscheidung speichern"), "$status")
                }
            }
            mountedForm("motion-election-tallied") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = emptyList(),
                    canManage = true,
                    activeVote = null,
                    onSelectMotion = {},
                    onChanged = {},
                    elections = listOf(election(status = ElectionStatus.TALLIED)),
                    committees = committees,
                )
                assertTrue(element().flatText().contains("Zu diesem Antrag läuft eine Wahl."), "a tallied election keeps its old text")
                assertEquals(0, element().allOf("[role=note]").size)
            }
            mountedForm("motion-election-aborted-only") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = emptyList(),
                    canManage = true,
                    activeVote = null,
                    onSelectMotion = {},
                    onChanged = {},
                    elections = listOf(election(status = ElectionStatus.ABORTED)),
                    committees = committees,
                )
                assertTrue(element().hasButton("Wahl eröffnen"), "an aborted election does not block a new one")
                assertTrue(element().hasButton("Entscheidung speichern"))
            }
        }

    @Test
    fun aRunningMeritocraticVote_keepsItsHint_andOffersNoElection(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-vote-running") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = emptyList(),
                    canManage = true,
                    activeVote = runningVote(),
                    onSelectMotion = {},
                    onChanged = {},
                    elections = emptyList(),
                    committees = committees,
                )
                // V1.9.54: an OPEN vote blocks resolveMotion on the server, so the form is not offered; the hint says what to do first
                assertTrue(
                    element().flatText().contains(
                        "Zu diesem Antrag läuft noch eine meritokratische Abstimmung. Schließen Sie sie oder brechen Sie sie ab, bevor Sie entscheiden.",
                    ),
                )
                assertEquals(1, element().allOf("[role=note]").size)
                assertFalse(element().hasButton("Wahl eröffnen"))
                assertFalse(element().hasButton("Entscheidung speichern"))
            }
        }

    @Test
    fun aClosedMeritocraticVote_keepsTheOldHint(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-vote-closed") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = emptyList(),
                    canManage = true,
                    activeVote = runningVote().copy(status = VoteStatus.CLOSED),
                    onSelectMotion = {},
                    onChanged = {},
                    elections = emptyList(),
                    committees = committees,
                )
                assertTrue(element().flatText().contains("Es läuft bereits eine meritokratische Vote"))
                assertEquals(0, element().allOf("[role=note]").size)
            }
        }

    @Test
    fun pendingAmendments_disableTheElectionButtonToo(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-amendments") { root, element ->
                renderResolutionSection(
                    panel = root,
                    motion = motionDto(),
                    pendingAmendments = listOf(motionDto().copy(id = "a1", amendsMotionId = "m1", status = MotionStatus.SUBMITTED)),
                    canManage = true,
                    activeVote = null,
                    onSelectMotion = {},
                    onChanged = {},
                    elections = emptyList(),
                    committees = committees,
                )
                assertTrue(element().hasButton("Wahl eröffnen") && element().isButtonDisabled("Wahl eröffnen"))
                assertEquals(4, element().allOf("button[disabled]").size, "quorum, vote, election and consensus are all disabled")
            }
        }

    @Test
    fun theFormShowsPerElectionTypeOnlyWhatApplies(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-types") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                assertFalse(hasLabel(el, "Zielgremium"), "a yes/no election seats nobody")
                assertFalse(hasLabel(el, "Rolle im Zielgremium"))
                assertFalse(hasLabel(el, "Sitze"))
                assertTrue(hasLabel(el, "Erforderliche Mehrheit"))
                assertFalse(hasLabel(el, "Mindestens"), "the custom fraction only shows for the custom majority")

                el.chooseIn("Wahlart", ElectionType.SINGLE_CHOICE.name)
                assertTrue(hasLabel(el, "Zielgremium") && hasLabel(el, "Rolle im Zielgremium"))
                assertFalse(hasLabel(el, "Sitze"), "a single-choice election has exactly one seat (shown as text)")
                assertTrue(el.flatText().contains("Zu besetzende Sitze: 1"))
                assertTrue(hasLabel(el, "Erforderliche Mehrheit"))

                el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                assertTrue(hasLabel(el, "Sitze"))
                assertFalse(hasLabel(el, "Erforderliche Mehrheit"), "plurality needs no required share")
                assertFalse(el.flatText().contains("Zu besetzende Sitze: 1"))
            }
        }

    @Test
    fun theMajorityExplanation_isLive(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-explain") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                assertTrue(el.flatText().contains("Bei 3 Ja- und Nein-Stimmen sind mindestens 2 davon Ja nötig."))
                el.chooseIn("Erforderliche Mehrheit", "TWO_THIRDS")
                awaitUntil("explanation follows the field", 1500) {
                    element().flatText().contains("Bei 10 Ja- und Nein-Stimmen sind mindestens 7 davon Ja nötig.")
                }
                assertTrue(el.flatText().contains("Bei Stimmengleichheit ist nichts entschieden"))
            }
        }

    @Test
    fun anOpenElection_warnsThatVotesAreStoredWithNames(): Promise<Unit> =
        formTest {
            mountedForm("motion-election-open-warning") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                assertFalse(
                    el.flatText().contains("Bei einer offenen Wahl werden die Stimmen mit Namen gespeichert"),
                    "secret is the default",
                )
                (el.controlOf("Geheime Wahl") as HTMLInputElement).click()
                awaitUntil(
                    "warning shown",
                    1500,
                ) { element().flatText().contains("Bei einer offenen Wahl werden die Stimmen mit Namen gespeichert") }
            }
        }

    @Test
    fun aSecretElection_explainsTheMinimumParticipation_andAnOpenOneDoesNot(): Promise<Unit> =
        formTest {
            val hint =
                "Bei weniger als 5 abgegebenen Stimmzetteln werden nur das Ergebnis und die Beteiligung angezeigt, keine Stimmenzahlen."
            mountedForm("motion-election-min-participation") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                assertTrue(el.flatText().contains(hint), "secret is the default, so the rule is shown")
                (el.controlOf("Geheime Wahl") as HTMLInputElement).click()
                awaitUntil("hint hidden for an open election", 15_000) { !element().flatText().contains(hint) }
                (el.controlOf("Geheime Wahl") as HTMLInputElement).click()
                awaitUntil("hint back for a secret election", 15_000) { element().flatText().contains(hint) }
            }
        }

    @Test
    fun aYesNoElection_sendsTypeSecrecyMajorityAndApprovals_andNoCommittee(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(), opened = election(status = ElectionStatus.PREPARATION))
            var openedId: String? = null
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("motion-election-send-yesno") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) { openedId = it.id }
                    val el = element()
                    (el.controlOf("Geheime Wahl") as HTMLInputElement).click() // open election
                    el.chooseIn("Erforderliche Mehrheit", "CUSTOM")
                    el.typeInto("Mindestens", " 3 ")
                    el.typeInto("von", "5")
                    el.typeInto("Erforderliche Freigaben der Auszählung", " 3 ")
                    el.buttonNamed("Wahl eröffnen").click()
                    awaitUntil("openElection sent", 1500) { calls.toRoute(routes.openElection).size == 1 }
                    val input = calls.singleCall(routes.openElection).rpcParam(0)
                    assertEquals("m1", input.motionId as String)
                    assertEquals("YES_NO", input.electionType as String)
                    assertEquals(false, input.secret as Boolean)
                    assertEquals(60, input.requiredMajorityPercent as Int, "the legacy display percent is ceil(3 * 100 / 5)")
                    assertEquals(3, input.requiredMajorityNumerator as Int)
                    assertEquals(5, input.requiredMajorityDenominator as Int)
                    assertEquals(3, input.tallyThreshold as Int)
                    assertTrue(input.targetCommitteeId == null, "a yes/no election has no target committee")
                    assertTrue(input.targetRole == null)
                    awaitUntil("opened callback", 1500) { openedId == "e1" }
                }
            }
        }

    @Test
    fun aMultiChoiceElection_sendsSeatsCommitteeAndRole_andNeedsACommittee(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(), opened = election(type = ElectionType.MULTI_CHOICE, status = ElectionStatus.PREPARATION))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("motion-election-send-multi") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    val el = element()
                    el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                    el.typeInto("Sitze", "3")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size, "no target committee, no request")
                    awaitUntil("committee required", 1500) { element().flatText().contains("Bitte ein Zielgremium wählen.") }
                    el.chooseIn("Zielgremium", "c9")
                    el.chooseIn("Rolle im Zielgremium", "CHAIR")
                    el.buttonNamed("Wahl eröffnen").click()
                    awaitUntil("openElection sent", 1500) { calls.toRoute(routes.openElection).size == 1 }
                    val input = calls.singleCall(routes.openElection).rpcParam(0)
                    assertEquals("MULTI_CHOICE", input.electionType as String)
                    // secret = true and the neutral 50 percent are the defaults of ElectionOpenInput and are not encoded at all
                    assertTrue(input.secret == null || input.secret == true)
                    assertEquals(3, input.seatCount as Int)
                    assertEquals("c9", input.targetCommitteeId as String)
                    assertEquals("CHAIR", input.targetRole as String)
                    assertTrue(
                        input.requiredMajorityPercent == null || input.requiredMajorityPercent == 50,
                        "plurality sends the neutral 50",
                    )
                    assertTrue(
                        input.requiredMajorityNumerator == null && input.requiredMajorityDenominator == null,
                        "plurality has no fraction",
                    )
                }
            }
        }

    @Test
    fun theValidationBounds_areEnforcedBeforeAnyRequest(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election())
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("motion-election-bounds") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    val el = element()
                    el.typeInto("Erforderliche Freigaben der Auszählung", "1")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size, "one approval is below the four-eyes minimum")
                    el.typeInto("Erforderliche Freigaben der Auszählung", "2")
                    el.chooseIn("Erforderliche Mehrheit", "CUSTOM")
                    el.typeInto("Mindestens", "101")
                    el.typeInto("von", "100")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size, "101 of 100 is out of range")
                    el.typeInto("Mindestens", "1")
                    el.typeInto("von", "3")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size, "a third is below the required half")
                    awaitUntil(
                        "below-half message",
                        1500,
                    ) { element().flatText().contains("Die Mehrheit muss mindestens die Hälfte betragen.") }
                    el.chooseIn("Erforderliche Mehrheit", "HALF")
                    el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                    el.chooseIn("Zielgremium", "c9")
                    el.typeInto("Sitze", "1")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size, "a multiple-choice election needs 2 to 25 seats")
                }
            }
        }

    @Test
    fun theMajorityPresets_sendTheExactFractionAndTheLegacyPercent(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(), opened = election(status = ElectionStatus.PREPARATION))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("motion-election-send-presets") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    val el = element()
                    el.chooseIn("Erforderliche Mehrheit", "TWO_THIRDS")
                    el.buttonNamed("Wahl eröffnen").click()
                    awaitUntil("openElection sent", 1500) { calls.toRoute(routes.openElection).size == 1 }
                    val input = calls.singleCall(routes.openElection).rpcParam(0)
                    assertEquals(2, input.requiredMajorityNumerator as Int)
                    assertEquals(3, input.requiredMajorityDenominator as Int)
                    assertEquals(67, input.requiredMajorityPercent as Int)
                }
            }
        }

    @Test
    fun theDefaultMajority_isTheSimpleMajority(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(), opened = election(status = ElectionStatus.PREPARATION))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("motion-election-send-default-majority") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    element().buttonNamed("Wahl eröffnen").click()
                    awaitUntil("openElection sent", 1500) { calls.toRoute(routes.openElection).size == 1 }
                    val input = calls.singleCall(routes.openElection).rpcParam(0)
                    assertEquals(1, input.requiredMajorityNumerator as Int)
                    assertEquals(2, input.requiredMajorityDenominator as Int)
                    assertTrue(input.requiredMajorityPercent == null || input.requiredMajorityPercent == 50)
                }
            }
        }
}
