package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteStatus
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.28: "Konsensieren eröffnen" in the resolution section of a scheduled motion -- when it is offered, what hides the other ways while a
 * consensus owns the motion, and exactly what the form sends (only the two choices; every other field stays at its default).
 */
class MotionsOpenConsensusDomTest {
    private val committees =
        listOf(CommitteeDto("c1", "Mitgliederversammlung", CommitteeType.GENERAL_ASSEMBLY, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0)))

    private fun vote(status: VoteStatus) =
        VoteDto(
            id = "v1",
            motionId = "m1",
            meetingId = "s1",
            title = "V",
            status = status,
            options = emptyList(),
            winnerOptionId = null,
            secondPriceLtr = null,
            openedById = "x",
            openedByDisplayName = "X",
            openedAt = ELECTION_AT,
            closedAt = null,
            resolutionId = null,
        )

    private fun HTMLElement.render(
        root: io.kvision.panel.Root,
        consensuses: List<SystemicConsensusDto> = emptyList(),
        canManage: Boolean = true,
        activeVote: VoteDto? = null,
        elections: List<network.lapis.cloud.shared.domain.ElectionDto> = emptyList(),
        motion: network.lapis.cloud.shared.domain.MotionDto = motionDto(),
        amendments: List<network.lapis.cloud.shared.domain.MotionDto> = emptyList(),
    ) = renderResolutionSection(
        panel = root,
        motion = motion,
        pendingAmendments = amendments,
        canManage = canManage,
        activeVote = activeVote,
        onSelectMotion = {},
        onChanged = {},
        elections = elections,
        committees = committees,
        consensuses = consensuses,
    )

    @Test
    fun theForm_isOfferedNextToTheOtherWays_onlyForManagersOfAScheduledMotion(): Promise<Unit> =
        formTest {
            mountedForm("sk-motion-offer") { root, element ->
                element().render(root)
                assertTrue(element().hasButton("Konsensieren eröffnen"))
                assertTrue(element().hasButton("Wahl eröffnen") && element().hasButton("Entscheidung speichern"))
            }
            mountedForm("sk-motion-no-rights") { root, element ->
                element().render(root, canManage = false)
                assertFalse(element().hasButton("Konsensieren eröffnen"))
            }
        }

    @Test
    fun pendingAmendments_disableTheConsensusButtonToo(): Promise<Unit> =
        formTest {
            mountedForm("sk-motion-amendments") { root, element ->
                element().render(
                    root,
                    amendments = listOf(motionDto().copy(id = "a1", amendsMotionId = "m1", status = MotionStatus.SUBMITTED)),
                )
                assertTrue(element().isButtonDisabled("Konsensieren eröffnen"))
            }
        }

    @Test
    fun aRunningConsensus_replacesTheOtherWaysWithTheWayToIt(): Promise<Unit> =
        formTest {
            mountedForm("sk-motion-running") { root, element ->
                element().render(root, consensuses = listOf(consensus(status = SystemicConsensusStatus.RATING)))
                val el = element()
                assertTrue(
                    el.flatText().contains(
                        "Zu diesem Antrag läuft noch ein Systemisches Konsensieren. Werten Sie es aus oder brechen Sie es ab, bevor Sie entscheiden.",
                    ),
                )
                assertEquals(1, el.allOf("[role=note]").size)
                assertTrue(el.hasButton("Zum Konsensieren"))
                listOf("Konsensieren eröffnen", "Wahl eröffnen", "Entscheidung speichern", "Vote eröffnen").forEach {
                    assertFalse(el.hasButton(it), "'$it' would race the consensus")
                }
            }
            mountedForm("sk-motion-closed-advisory") { root, element ->
                element().render(root, consensuses = listOf(consensus(status = SystemicConsensusStatus.CLOSED)))
                assertTrue(element().hasButton("Zum Konsensieren"), "a closed, not yet evaluated consensus still owns the motion")
                assertFalse(element().hasButton("Wahl eröffnen"), "an election would block the evaluation for good")
                assertFalse(element().hasButton("Ergebnis der Sondierung ansehen"))
            }
            mountedForm("sk-motion-binding-evaluated") { root, element ->
                val binding = consensus(status = SystemicConsensusStatus.EVALUATED, bindingness = SystemicConsensusBindingness.BINDING)
                element().render(root, consensuses = listOf(binding))
                assertTrue(element().hasButton("Zum Konsensieren"))
                assertTrue(
                    element().flatText().contains("Zu diesem Antrag läuft ein Konsensieren."),
                    "an evaluated binding one keeps its text",
                )
                assertEquals(0, element().allOf("[role=note]").size)
                assertFalse(element().hasButton("Wahl eröffnen"))
            }
        }

    @Test
    fun anAdvisoryResult_isJustALink_theOtherWaysStay_butNoSecondConsensus(): Promise<Unit> =
        formTest {
            mountedForm("sk-motion-advisory-done") { root, element ->
                element().render(root, consensuses = listOf(consensus(status = SystemicConsensusStatus.EVALUATED)))
                val el = element()
                assertTrue(el.hasButton("Ergebnis der Sondierung ansehen"))
                assertTrue(el.hasButton("Wahl eröffnen") && el.hasButton("Entscheidung speichern"))
                assertFalse(el.hasButton("Konsensieren eröffnen"), "the server allows one non-aborted consensus per motion")
            }
            mountedForm("sk-motion-aborted") { root, element ->
                element().render(root, consensuses = listOf(consensus(status = SystemicConsensusStatus.ABORTED)))
                assertTrue(element().hasButton("Konsensieren eröffnen"), "an aborted consensus does not block a new one")
            }
        }

    @Test
    fun aClosedVote_alsoHidesTheForm_likeTheServerRefuses(): Promise<Unit> =
        formTest {
            mountedForm("sk-motion-closed-vote") { root, element ->
                element().render(root, activeVote = vote(VoteStatus.CLOSED))
                assertFalse(element().hasButton("Konsensieren eröffnen"))
            }
            mountedForm("sk-motion-election") { root, element ->
                element().render(root, elections = listOf(election(status = ElectionStatus.OPEN)))
                assertFalse(element().hasButton("Konsensieren eröffnen"))
            }
        }

    @Test
    fun theDefaults_areAnonymousAndAdvisory_andBindingNeedsTheTickAndShowsItsWarnings(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(consensus(), opened = consensus())
            var openedId: String? = null
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("sk-motion-form") { root, element ->
                    renderOpenConsensusForm(root, motionDto()) { openedId = it.id }
                    val el = element()
                    assertTrue(el.flatText().contains("Anonym: Es wird gespeichert, dass jemand bewertet hat, aber nicht, wie."))
                    assertFalse(el.flatText().contains("Bei einem Beschluss gibt es keine Wiederabstimmung."), "advisory is the default")
                    // binding: warnings appear, the tick is required
                    el.allOf("label").first { it.textContent?.trim() == "Beschluss" }.click()
                    awaitUntil(
                        "binding warnings",
                        1500,
                    ) { element().flatText().contains("Bei einem Beschluss gibt es keine Wiederabstimmung.") }
                    assertTrue(el.flatText().contains("Gewinnt die Passivlösung, gilt der Antrag als abgelehnt."))
                    el.buttonNamed("Konsensieren eröffnen").click()
                    awaitUntil("needs the tick", 1500) { el.flatText().contains("Bitte bestätigen Sie, dass Sie das verstanden haben.") }
                    assertEquals(0, calls.toRoute(routes.open).size)
                    el.tick("Verstanden")
                    el.allOf("label").first { it.textContent?.trim() == "Offen" }.click()
                    el.buttonNamed("Konsensieren eröffnen").click()
                    awaitUntil("open sent", 1500) { calls.toRoute(routes.open).size == 1 }
                    val input = calls.singleCall(routes.open).rpcParam(0)
                    assertEquals("m1", input.motionId as String)
                    assertEquals(false, input.secret as Boolean)
                    assertEquals("BINDING", input.bindingness as String)
                    // everything else is left to the server's defaults
                    assertTrue(
                        input.scaleMax == null && input.maxRounds == null && input.aggregation == null && input.statusQuoOptionAuto == null,
                    )
                    awaitUntil("navigated on the result", 1500) { openedId == "k1" }
                }
            }
        }

    @Test
    fun theDefaultSubmission_isAnonymousAndAdvisory(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(consensus(), opened = consensus())
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("sk-motion-defaults") { root, element ->
                    renderOpenConsensusForm(root, motionDto()) {}
                    element().buttonNamed("Konsensieren eröffnen").click()
                    awaitUntil("open sent", 1500) { calls.toRoute(routes.open).size == 1 }
                    val input = calls.singleCall(routes.open).rpcParam(0)
                    assertTrue(input.secret == null || input.secret == true, "anonymous is the default")
                    assertTrue(input.bindingness == null || input.bindingness == "ADVISORY", "advisory is the default")
                }
            }
        }
}
