package network.lapis.cloud.client

import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.IncumbentPolicy
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.103: the "Bisherige Inhaber dieser Rolle" part of the election form -- the fieldset with its two radio buttons, the live
 * consequence sentence in an `aria-live` region, the single-holder rule, and exactly what the form sends.
 */
class ElectionOpenFormIncumbentDomTest {
    private val committees =
        listOf(
            CommitteeDto("c1", "Mitgliederversammlung", CommitteeType.GENERAL_ASSEMBLY, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0)),
            CommitteeDto("c9", "Vorstand", CommitteeType.EXECUTIVE_BOARD, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0)),
            CommitteeDto("c5", "Arbeitsgruppe", CommitteeType.WORKING_GROUP, "", true, 50, LocalDateTime(2020, 1, 1, 0, 0)),
        )

    private fun shown(el: HTMLElement) = window.getComputedStyle(el).display != "none"

    private fun HTMLElement.radio(label: String) = controlOf(label) as HTMLInputElement

    private fun HTMLElement.consequence() =
        assertNotNull(querySelector("#election-policy-consequence") as? HTMLElement, "no consequence region")

    @Test
    fun aPersonnelElection_showsTheFieldsetWithNeuwahlPreselected_andAnAriaLiveSentence(): Promise<Unit> =
        formTest {
            mountedForm("election-incumbent-default") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                // a yes/no election seats nobody: KVision does not render hidden widgets at all
                assertNull(el.querySelector("fieldset.lapis-election-policy"), "a yes/no election has no policy")
                assertNull(el.querySelector("#election-policy-consequence"))

                el.chooseIn("Wahlart", ElectionType.SINGLE_CHOICE.name)
                val fieldset = assertNotNull(el.querySelector("fieldset.lapis-election-policy") as? HTMLElement)
                assertTrue(shown(fieldset))
                assertEquals("Bisherige Inhaber dieser Rolle", fieldset.querySelector("legend")?.textContent?.trim())
                val replace = el.radio("Enden mit der Auszählung (Neuwahl)")
                val keep = el.radio("Bleiben im Amt (Nachwahl)")
                assertTrue(replace.checked, "Neuwahl is preselected")
                assertFalse(keep.checked)
                assertFalse(keep.disabled, "MEMBER has many holders: a by-election is possible")
                assertEquals("polite", el.consequence().getAttribute("aria-live"))
                assertEquals(2, fieldset.allOf("input[type=radio]").size)
                assertEquals(
                    1,
                    fieldset
                        .allOf("input[type=radio]")
                        .map { it.getAttribute("name") }
                        .toSet()
                        .size,
                    "one radio group",
                )
            }
        }

    @Test
    fun theSentence_followsCommittee_role_seats_andPolicy(): Promise<Unit> =
        formTest {
            mountedForm("election-incumbent-sentence") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                el.chooseIn("Zielgremium", "c9")
                el.chooseIn("Rolle im Zielgremium", "ASSESSOR")
                el.typeInto("Sitze", "3")
                awaitUntil("the sentence names role, committee and seats", 1500) {
                    val text = element().consequence().textContent.orEmpty()
                    text.contains("die 3 Gewählten") && text.contains("„Beisitz“") && text.contains("„Vorstand“")
                }
                assertTrue(
                    el
                        .consequence()
                        .textContent
                        .orEmpty()
                        .contains("endet am Tag der Auszählung"),
                )
                el.radio("Bleiben im Amt (Nachwahl)").click()
                awaitUntil(
                    "the sentence follows the policy",
                    1500,
                ) {
                    element()
                        .consequence()
                        .textContent
                        .orEmpty()
                        .contains("bleibt im Amt")
                }
                assertFalse(
                    el
                        .consequence()
                        .textContent
                        .orEmpty()
                        .contains("endet am Tag"),
                )
            }
        }

    @Test
    fun aRoleWithExactlyOneHolder_forbidsNachwahl_andFixesTheSeatToOne(): Promise<Unit> =
        formTest {
            mountedForm("election-incumbent-single-holder") { root, element ->
                renderOpenElectionForm(root, motionDto(), committees) {}
                val el = element()
                el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                el.chooseIn("Zielgremium", "c9")
                el.radio("Bleiben im Amt (Nachwahl)").click()
                el.chooseIn("Rolle im Zielgremium", "CHAIR")
                awaitUntil("Nachwahl is switched off", 1500) { element().radio("Bleiben im Amt (Nachwahl)").disabled }
                assertTrue(el.radio("Enden mit der Auszählung (Neuwahl)").checked, "forced back to Neuwahl")
                val keep = el.radio("Bleiben im Amt (Nachwahl)")
                val why = assertNotNull(el.querySelector("#" + keep.getAttribute("aria-describedby")) as? HTMLElement, "no explanation")
                assertTrue(shown(why))
                assertTrue(why.textContent.orEmpty().contains("genau einem Inhaber"))
                assertFalse(el.flatText().contains("Sitze *"), "no seat input for a single-holder role")
                assertTrue(el.flatText().contains("Zu besetzende Sitze: 1"))
                assertTrue(
                    el
                        .consequence()
                        .textContent
                        .orEmpty()
                        .contains("übernimmt die gewählte Person"),
                )
            }
        }

    @Test
    fun theForm_sendsRoleAndPolicy_andNothingForYesNo(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(), opened = election(type = ElectionType.MULTI_CHOICE, status = ElectionStatus.PREPARATION))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("election-incumbent-send") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    val el = element()
                    el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                    el.chooseIn("Zielgremium", "c9")
                    el.chooseIn("Rolle im Zielgremium", "ASSESSOR")
                    el.typeInto("Sitze", "3")
                    el.radio("Bleiben im Amt (Nachwahl)").click()
                    el.buttonNamed("Wahl eröffnen").click()
                    awaitUntil("openElection sent", 1500) { calls.toRoute(routes.openElection).size == 1 }
                    val input = calls.singleCall(routes.openElection).rpcParam(0)
                    assertEquals("KEEP", input.incumbentPolicy as String)
                    assertEquals("ASSESSOR", input.targetRole as String)
                    assertEquals(3, input.seatCount as Int)
                }
            }
        }

    @Test
    fun aSingleHolderRole_isSentWithReplaceAndOneSeat(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(), opened = election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.PREPARATION))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("election-incumbent-send-chair") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    val el = element()
                    el.chooseIn("Wahlart", ElectionType.MULTI_CHOICE.name)
                    el.chooseIn("Zielgremium", "c9")
                    el.chooseIn("Rolle im Zielgremium", "CHAIR")
                    el.buttonNamed("Wahl eröffnen").click()
                    awaitUntil("openElection sent", 1500) { calls.toRoute(routes.openElection).size == 1 }
                    val input = calls.singleCall(routes.openElection).rpcParam(0)
                    assertEquals(IncumbentPolicy.REPLACE.name, input.incumbentPolicy as String)
                    assertEquals(CommitteeRole.CHAIR.name, input.targetRole as String)
                    assertTrue(input.seatCount == null || input.seatCount == 1, "one seat for a single-holder role")
                }
            }
        }

    @Test
    fun theGeneralAssemblyHasNoMemberSeats_andAForeignCommitteeIsRefusedBeforeAnyRequest(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election())
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("election-incumbent-rules") { root, element ->
                    renderOpenElectionForm(root, motionDto(), committees) {}
                    val el = element()
                    el.chooseIn("Wahlart", ElectionType.SINGLE_CHOICE.name)
                    el.chooseIn("Zielgremium", "c1")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size)
                    awaitUntil(
                        "assembly rule",
                        1500,
                    ) { element().flatText().contains("Die Mitgliederversammlung hat keine Mitglieder-Sitze") }
                }
                // a working-group motion may only seat its own committee
                mountedForm("election-incumbent-rules-foreign") { root, element ->
                    val groupMotion = motionDto("c5").copy(targetCommitteeType = CommitteeType.WORKING_GROUP)
                    renderOpenElectionForm(root, groupMotion, committees) {}
                    val el = element()
                    el.chooseIn("Wahlart", ElectionType.SINGLE_CHOICE.name)
                    el.chooseIn("Zielgremium", "c9")
                    el.chooseIn("Rolle im Zielgremium", "CHAIR")
                    el.buttonNamed("Wahl eröffnen").click()
                    assertEquals(0, calls.toRoute(routes.openElection).size)
                    awaitUntil(
                        "foreign committee rule",
                        1500,
                    ) { element().flatText().contains("Eine Wahl kann nur das Gremium des Antrags besetzen") }
                }
            }
        }

    @Test
    fun consequenceText_singularAndPlural_areSeparateTexts_withoutGenderMarks() {
        val single = incumbentConsequenceText("Vorstand", CommitteeRole.CHAIR, 1, IncumbentPolicy.REPLACE)
        assertTrue(single.contains("übernimmt die gewählte Person die Rolle „Vorsitz“ im Gremium „Vorstand“"), single)
        assertTrue(single.endsWith("endet am Tag der Auszählung."), single)
        val singleKeep = incumbentConsequenceText("Vorstand", CommitteeRole.MEMBER, 1, IncumbentPolicy.KEEP)
        assertTrue(singleKeep.endsWith("bleibt im Amt."), singleKeep)
        val plural = incumbentConsequenceText("Vorstand", CommitteeRole.ASSESSOR, 3, IncumbentPolicy.REPLACE)
        assertTrue(plural.contains("übernehmen die 3 Gewählten die Rolle „Beisitz“"), plural)
        assertTrue(plural.contains("nicht wiedergewählt wird, endet am Tag der Auszählung."), plural)
        val pluralKeep = incumbentConsequenceText("", CommitteeRole.ASSESSOR, 2, IncumbentPolicy.KEEP)
        assertTrue(pluralKeep.contains("(noch kein Gremium gewählt)"), pluralKeep)
        listOf(single, singleKeep, plural, pluralKeep).forEach { text ->
            assertFalse(text.contains("*") || text.contains(":in") || text.contains("_in") || text.contains("/in"), text)
        }
    }
}
