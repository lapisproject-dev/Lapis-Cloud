package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.MotionStatus
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.193 -- "Wie soll entschieden werden?": the pure state table, the radio-group semantics and the merit form's wire labels. */
class MotionDecisionSelectorDomTest {
    private val amendment = motionDto().copy(id = "a1", amendsMotionId = "m1", status = MotionStatus.SUBMITTED)

    private fun HTMLElement.render(
        root: io.kvision.panel.Root,
        amendments: List<network.lapis.cloud.shared.domain.MotionDto> = emptyList(),
        consensuses: List<network.lapis.cloud.shared.domain.SystemicConsensusDto> = emptyList(),
    ) = renderResolutionSection(
        panel = root,
        motion = motionDto(),
        pendingAmendments = amendments,
        canManage = true,
        activeVote = null,
        onSelectMotion = {},
        onChanged = {},
        committees = emptyList(),
        consensuses = consensuses,
    )

    private fun HTMLElement.press(
        segment: String,
        key: String,
    ) {
        val target = buttonNamed(segment)
        target.focus()
        target.dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
    }

    private fun HTMLElement.checked(): List<String> =
        allOf("[role=radio]").filter { it.getAttribute("aria-checked") == "true" }.map { it.textContent.orEmpty().trim() }

    // ---- pure state table -------------------------------------------------------------------------------------------------

    @Test
    fun states_noObstacle_allFourOpen_inSelectorOrder() {
        val states = decisionMethodStates(emptyList(), emptyList())
        assertEquals(DecisionMethod.entries, states.map { it.method })
        assertTrue(states.all { it.enabled && it.disabledReason == null })
        assertEquals(DecisionMethod.QUORUM, firstEnabledMethod(states))
    }

    @Test
    fun states_openAmendments_closeAllFour_withAReason() {
        val states = decisionMethodStates(listOf(amendment), emptyList())
        assertTrue(states.none { it.enabled })
        assertTrue(states.all { !it.disabledReason.isNullOrBlank() })
        assertNull(firstEnabledMethod(states))
    }

    @Test
    fun states_anEvaluatedConsensus_closesOnlyAnotherConsensus_anAbortedOneNothing() {
        val evaluated =
            decisionMethodStates(
                emptyList(),
                listOf(consensus(status = network.lapis.cloud.shared.domain.SystemicConsensusStatus.EVALUATED)),
            )
        assertEquals(listOf(true, true, true, false), evaluated.map { it.enabled })
        assertFalse(evaluated.last().disabledReason.isNullOrBlank())
        val aborted =
            decisionMethodStates(emptyList(), listOf(consensus(status = network.lapis.cloud.shared.domain.SystemicConsensusStatus.ABORTED)))
        assertTrue(aborted.all { it.enabled })
    }

    @Test
    fun voteOptionDisplayLabel_translatesOnlyYesAndNo_andSanitizesTheRest() {
        assertEquals("Ja", voteOptionDisplayLabel("YES"))
        assertEquals("Nein", voteOptionDisplayLabel(" no "))
        assertEquals("Vielleicht", voteOptionDisplayLabel("Vielleicht"))
        assertFalse(voteOptionDisplayLabel("###KvI18nS###Ja").contains("###KvI18nS###"), "a forged marker never reaches the DOM")
    }

    // ---- DOM --------------------------------------------------------------------------------------------------------------

    @Test
    fun theGroup_isARadiogroup_withOnePreselectedSegment_andOnlyItsFormInTheDom(): Promise<Unit> =
        formTest {
            mountedForm("decision-radiogroup") { root, element ->
                element().render(root)
                val el = element()
                val group = assertNotNull(el.querySelector("[role=radiogroup]"))
                assertEquals("motion-decision-hint", group.getAttribute("aria-describedby"))
                assertEquals(listOf("Quorum-Beschluss des Gremiums"), el.checked())
                assertTrue(el.hasButton("Ergebnis festhalten"))
                assertFalse(el.hasButton("Abstimmung eröffnen") || el.hasButton("Wahl eröffnen") || el.hasButton("Konsensieren eröffnen"))
                assertTrue(el.flatText().contains("Das Gremium hat in der Sitzung abgestimmt"))
                assertTrue(el.flatText().contains("kann nicht mehr geändert werden"))
                assertEquals(1, el.allOf("[role=radio][tabindex='0']").size, "roving tabindex")

                el.buttonNamed("Meritokratische Abstimmung").click()
                awaitUntil("the merit form") { element().hasButton("Abstimmung eröffnen") }
                assertFalse(element().hasButton("Ergebnis festhalten"), "switching replaces the form")
                assertTrue(element().flatText().contains("gewichten ihre Stimme"))
                assertEquals(listOf("Meritokratische Abstimmung"), element().checked())
            }
        }

    @Test
    fun arrowKeys_move_homeAndEnd_jump(): Promise<Unit> =
        formTest {
            mountedForm("decision-keys") { root, element ->
                element().render(root)
                element().press("Quorum-Beschluss des Gremiums", "ArrowRight")
                assertEquals(listOf("Meritokratische Abstimmung"), element().checked())
                element().press("Meritokratische Abstimmung", "End")
                assertEquals(listOf("Konsensieren"), element().checked())
                element().press("Konsensieren", "Home")
                assertEquals(listOf("Quorum-Beschluss des Gremiums"), element().checked())
            }
        }

    @Test
    fun aClosedSegment_isSkippedByTheKeyboard_andIgnoresClicks_withItsReasonWrittenOut(): Promise<Unit> =
        formTest {
            mountedForm("decision-skip") { root, element ->
                element().render(
                    root,
                    consensuses = listOf(consensus(status = network.lapis.cloud.shared.domain.SystemicConsensusStatus.EVALUATED)),
                )
                val el = element()
                assertEquals("true", el.buttonNamed("Konsensieren").getAttribute("aria-disabled"))
                assertTrue(el.flatText().contains("Nicht verfügbar: Konsensieren – Pro Antrag ist nur ein Konsensieren möglich"))
                el.press("Wahl", "ArrowRight")
                assertEquals(listOf("Quorum-Beschluss des Gremiums"), el.checked(), "wraps past the closed segment")
                el.buttonNamed("Konsensieren").click()
                assertEquals(listOf("Quorum-Beschluss des Gremiums"), el.checked())
                assertFalse(el.hasButton("Konsensieren eröffnen"))
            }
        }

    @Test
    fun openAmendments_closeAllFour_andShowNoForm(): Promise<Unit> =
        formTest {
            mountedForm("decision-amendments") { root, element ->
                element().render(root, amendments = listOf(amendment))
                val el = element()
                assertEquals(4, el.allOf("[role=radio][aria-disabled=true]").size)
                assertEquals(4, el.flatText().split("Nicht verfügbar:").size - 1, "every reason is written out")
                assertFalse(el.hasButton("Ergebnis festhalten"))
                assertNull(el.querySelector("#motion-decision-hint"), "no sentence without a chosen way")
            }
        }

    @Test
    fun theMeritForm_offersOnlyJaNein_noAbstention_andSendsYesNoUntouched(): Promise<Unit> =
        formTest {
            val open =
                routeOf {
                    rpcService<network.lapis.cloud.shared.rpc.IGovernanceService>().openVote(
                        network.lapis.cloud.shared.domain
                            .VoteOpenInput("m1", listOf("YES")),
                    )
                }
            withFetchStub(respond = { req -> if (req.isRpc && req.rpcRoute == open) req.answerWith("null") else StubResponse() }) { calls ->
                mountedForm("decision-merit") { root, element ->
                    renderOpenVoteForm(root, motionDto(), onChanged = {})
                    val el = element()
                    assertTrue(el.flatText().contains("Ja") && el.flatText().contains("Nein"))
                    assertFalse(el.flatText().contains("Enthaltung"), "an abstention would win as ADOPTED")
                    assertTrue(el.flatText().contains("Achtung: Gewinnt eine andere Antwort als \"NO\", gilt der Antrag als angenommen."))
                    el.buttonNamed("Abstimmung eröffnen").click()
                    awaitUntil("openVote sent") { calls.toRoute(open).size == 1 }
                    val input = calls.singleCall(open).rpcParam(0)
                    // the default list is not encoded; the server's own default is exactly YES,NO
                    assertTrue(input.optionLabels == undefined || JSON.stringify(input.optionLabels) == "[\"YES\",\"NO\"]")
                }
            }
        }

    @Test
    fun theMeritForm_sendsOwnAnswers_onlyWhenTheAdvancedFieldWasChanged(): Promise<Unit> =
        formTest {
            val open =
                routeOf {
                    rpcService<network.lapis.cloud.shared.rpc.IGovernanceService>().openVote(
                        network.lapis.cloud.shared.domain
                            .VoteOpenInput("m1", listOf("YES")),
                    )
                }
            withFetchStub(respond = { req -> if (req.isRpc && req.rpcRoute == open) req.answerWith("null") else StubResponse() }) { calls ->
                mountedForm("decision-merit-own") { root, element ->
                    renderOpenVoteForm(root, motionDto(), onChanged = {})
                    val input0 = assertNotNull(element().querySelector("input")) as org.w3c.dom.HTMLInputElement
                    input0.value = "Rot, Grün, Blau"
                    input0.dispatchEvent(
                        org.w3c.dom.events
                            .Event("input"),
                    )
                    input0.dispatchEvent(
                        org.w3c.dom.events
                            .Event("blur"),
                    )
                    element().buttonNamed("Abstimmung eröffnen").click()
                    awaitUntil("openVote sent") { calls.toRoute(open).size == 1 }
                    val input = calls.singleCall(open).rpcParam(0)
                    assertEquals("[\"Rot\",\"Grün\",\"Blau\"]", JSON.stringify(input.optionLabels))
                }
            }
        }
}
