package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.ElectionBallotCastResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.22: the voting booth, mounted for real. It is an irreversible single-shot action, so these tests pin the exact number of
 * requests (never two), what the booth says after every kind of failure, and that the receipt exists only on screen and only until
 * "Fertig".
 */
class ElectionBoothDomTest {
    private fun peopleOptions() = listOf(option("o1", "Anna", 0, "k1"), option("o2", "Boris", 1, "k2"), option("o3", "Cleo", 2, "k3"))

    private suspend fun <T> withBooth(
        world: ElectionWorld,
        id: String,
        exits: MutableList<Boolean> = mutableListOf(),
        block: suspend (HTMLElement, List<RecordedRequest>, ElectionRoutes) -> T,
    ): T {
        val routes = electionRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderElectionBooth(root, world.election) { refresh -> exits += refresh }
                block(element(), calls, routes)
            }
        }
    }

    /** Picks the radio tile named [text] the way a person does: click its label. */
    private fun HTMLElement.chooseTile(text: String) {
        val label = assertNotNull(allOf("label").firstOrNull { it.textContent?.trim() == text }, "no tile '$text'")
        label.click()
    }

    private suspend fun HTMLElement.castNow() {
        buttonNamed("Weiter zur Prüfung").click()
        awaitUntil("review step", 1500) { hasButton("Stimme endgültig abgeben") }
        buttonNamed("Stimme endgültig abgeben").click()
    }

    // ── what is sent ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun yesNo_sendsTheAnswer_notOptionIds_afterAReviewStep(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN))
            withBooth(world, "booth-yesno") { el, calls, routes ->
                assertTrue(el.flatText().contains("Ihre Stimme ist geheim"))
                el.buttonNamed("Weiter zur Prüfung").click()
                assertTrue(el.hasButton("Weiter zur Prüfung"), "no choice yet: the booth stays on the choice step")
                assertEquals(0, calls.toRoute(routes.cast).size)
                el.chooseTile("Nein")
                el.buttonNamed("Weiter zur Prüfung").click()
                awaitUntil("review", 1500) { el.hasButton("Stimme endgültig abgeben") }
                assertTrue(el.flatText().contains("Nein"), "the review lists the choice")
                assertEquals(0, calls.toRoute(routes.cast).size, "nothing is sent before the final button")
                el.buttonNamed("Stimme endgültig abgeben").click()
                awaitUntil("cast sent", 1500) { calls.toRoute(routes.cast).size == 1 }
                val input = calls.singleCall(routes.cast).rpcParam(0)
                assertEquals("e1", input.electionId as String)
                assertEquals("NO", input.answer as String)
                // an empty list is the default and is not encoded at all
                assertTrue(input.selectedOptionIds == null || (input.selectedOptionIds as Array<dynamic>).isEmpty())
            }
        }

    @Test
    fun singleChoice_sendsTheOneOptionId_andNoAnswer(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.OPEN, options = peopleOptions()))
            withBooth(world, "booth-single") { el, calls, routes ->
                el.chooseTile("Boris")
                el.castNow()
                awaitUntil("cast sent", 1500) { calls.toRoute(routes.cast).size == 1 }
                val input = calls.singleCall(routes.cast).rpcParam(0)
                assertEquals(listOf("o2"), (input.selectedOptionIds as Array<String>).toList())
                assertTrue(input.answer == null, "a people ballot leaves the answer empty")
            }
        }

    @Test
    fun multiChoice_locksTheRestAtTheLimit_unlocksOnUntick_andRefusesAnEmptyBallot(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.MULTI_CHOICE, status = ElectionStatus.OPEN, seatCount = 2, options = peopleOptions()),
                )
            withBooth(world, "booth-multi") { el, calls, routes ->
                assertTrue(el.flatText().contains("0 von höchstens 2 gewählt"))
                el.tick("Anna")
                el.tick("Boris")
                assertTrue((el.controlOf("Cleo") as HTMLInputElement).disabled, "the remaining option is locked at the limit")
                assertTrue(el.flatText().contains("2 von höchstens 2 gewählt. Die übrigen Optionen sind gesperrt."))
                (el.controlOf("Boris") as HTMLInputElement).click() // untick
                awaitUntil("unlocked", 1500) { !(el.controlOf("Cleo") as HTMLInputElement).disabled }
                (el.controlOf("Anna") as HTMLInputElement).click() // untick: nothing chosen
                el.buttonNamed("Weiter zur Prüfung").click()
                assertFalse(el.hasButton("Stimme endgültig abgeben"), "an empty ballot never reaches the review step")
                awaitUntil("error shown", 1500) { el.flatText().contains("Bitte wählen Sie mindestens eine und höchstens 2 Optionen.") }
                el.tick("Anna")
                el.tick("Cleo")
                el.castNow()
                awaitUntil("cast sent", 1500) { calls.toRoute(routes.cast).size == 1 }
                val input = calls.singleCall(routes.cast).rpcParam(0)
                assertEquals(listOf("o1", "o3"), (input.selectedOptionIds as Array<String>).toList())
                assertTrue(input.answer == null)
            }
        }

    @Test
    fun theBackButton_returnsToTheChoice_withTheSelectionKept(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.MULTI_CHOICE, status = ElectionStatus.OPEN, seatCount = 2, options = peopleOptions()),
                )
            withBooth(world, "booth-back") { el, calls, routes ->
                el.tick("Cleo")
                el.buttonNamed("Weiter zur Prüfung").click()
                awaitUntil("review", 1500) { el.hasButton("Zurück") }
                el.buttonNamed("Zurück").click()
                awaitUntil("choice again", 1500) { el.hasButton("Weiter zur Prüfung") }
                assertTrue((el.controlOf("Cleo") as HTMLInputElement).checked, "the selection survives a step back")
                assertEquals(0, calls.toRoute(routes.cast).size)
            }
        }

    @Test
    fun aCandidateNameWithAnI18nMarker_isShownSanitized_inTheChoiceAndInTheReview(): Promise<Unit> =
        formTest {
            val options = listOf(option("o1", "###KvI18nS###Wahlausschuss", 0, "k1"), option("o2", "Boris", 1, "k2"))
            val world = ElectionWorld(election(type = ElectionType.SINGLE_CHOICE, status = ElectionStatus.OPEN, options = options))
            withBooth(world, "booth-marker") { el, _, _ ->
                assertFalse(el.innerHTML.contains("###KvI18n"), "choice step: ${el.flatText()}")
                el.allOf("label").first { it.textContent.orEmpty().contains("Wahlausschuss") }.click()
                el.buttonNamed("Weiter zur Prüfung").click()
                awaitUntil("review", 1500) { el.hasButton("Stimme endgültig abgeben") }
                assertFalse(el.innerHTML.contains("###KvI18n"), "review step: ${el.flatText()}")
            }
        }

    // ── single shot ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aDoubleClickOnTheFinalButton_sendsExactlyOneBallot(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN), castDelayMs = 250)
            withBooth(world, "booth-double") { el, calls, routes ->
                el.chooseTile("Ja")
                el.buttonNamed("Weiter zur Prüfung").click()
                awaitUntil("review", 1500) { el.hasButton("Stimme endgültig abgeben") }
                val cast = el.buttonNamed("Stimme endgültig abgeben")
                cast.click()
                cast.click()
                cast.click()
                awaitUntil("receipt shown", 3000) { el.allOf(".lapis-receipt-code").isNotEmpty() }
                assertEquals(1, calls.toRoute(routes.cast).size, "three clicks, one ballot")
            }
        }

    // ── receipt ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theReceipt_isShownInGroups_needsTheTickBeforeDone_andIsGoneAfterwards(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN))
            val exits = mutableListOf<Boolean>()
            withBeforeUnloadSpy { guards ->
                withConsoleSpy { consoleCalls ->
                    withBooth(world, "booth-receipt", exits) { el, _, _ ->
                        el.chooseTile("Ja")
                        el.castNow()
                        awaitUntil("receipt shown", 1500) { el.allOf(".lapis-receipt-code").isNotEmpty() }
                        val codeBox = el.allOf(".lapis-receipt-code").first()
                        assertEquals(TEST_RECEIPT, codeBox.textContent.orEmpty(), "the code is drawn character by character, unchanged")
                        assertEquals(7, codeBox.allOf("span").size, "27 characters in groups of four")
                        assertTrue(el.flatText().contains("Sie wird nur jetzt angezeigt und nirgends gespeichert."))
                        assertTrue(el.hasButton("Kopieren") && el.hasButton("Drucken"))
                        assertTrue(el.isButtonDisabled("Fertig"), "done is disabled until the member confirms having noted the receipt")
                        assertFalse(window.location.href.contains(TEST_RECEIPT))
                        assertNoStoredCode()

                        // leaving the page now asks first (exactly one guard is registered)
                        awaitUntil("leave guard installed", 1500) { guards() == 1 }

                        el.tick("Ich habe mir die Quittung notiert.")
                        awaitUntil("done enabled", 1500) { !el.isButtonDisabled("Fertig") }
                        el.buttonNamed("Fertig").click()
                        awaitUntil("left the booth", 1500) { exits == listOf(true) }
                        assertFalse(el.innerHTML.contains(TEST_RECEIPT), "the receipt is gone from the DOM after 'Fertig'")
                        assertFalse(document.body!!.innerHTML.contains(TEST_RECEIPT), "and from the whole document")
                        assertEquals(0, guards(), "the leave guard is removed with the receipt")
                        assertNoStoredCode()
                    }
                    assertEquals(0, consoleCalls(), "nothing about a ballot is logged")
                }
            }
        }

    @Test
    fun aMalformedReceiptCode_isNeverDrawn(): Promise<Unit> =
        formTest {
            val bad = "<img src=x onerror=alert(1)>"
            val world =
                ElectionWorld(
                    election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN),
                    castResult = ElectionBallotCastResultDto(id = "b1", castAt = ELECTION_AT, receiptCode = bad),
                )
            withBooth(world, "booth-bad-receipt") { el, _, _ ->
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil("explained", 1500) { el.flatText().contains("Die Quittung konnte nicht angezeigt werden.") }
                assertFalse(el.innerHTML.contains("onerror"))
                assertEquals(0, el.allOf(".lapis-receipt-code").size)
            }
        }

    @Test
    fun anOpenElection_hasNoReceipt_justAThankYou(): Promise<Unit> =
        formTest {
            val world =
                ElectionWorld(
                    election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN, secret = false),
                    castResult = ElectionBallotCastResultDto(id = "b1", castAt = ELECTION_AT, receiptCode = null),
                )
            withBooth(world, "booth-open-election") { el, _, _ ->
                assertTrue(el.flatText().contains("Diese Wahl ist offen"))
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil("thanks", 1500) { el.flatText().contains("Vielen Dank. Ihre Stimme wurde mit Ihrem Namen gespeichert.") }
                assertEquals(0, el.allOf(".lapis-receipt-code").size)
            }
        }

    // ── failures ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aConflict_afterWhichTheMemberHasVoted_saysAlreadyVoted(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN), participation(hasVoted = true))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "booth-conflict-voted") { el, calls, _ ->
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil("explained", 2000) { el.flatText().contains("Sie haben bereits abgestimmt. Ihre Stimme wurde gezählt.") }
                assertEquals(1, calls.toRoute(routes.cast).size)
                assertFalse(el.flatText().contains("simulated"), "the server's text is never shown")
            }
        }

    @Test
    fun aLostConnection_afterWhichTheBallotWasStored_saysSo(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN), participation(hasVoted = true))
            world.castNetworkError = true
            withBooth(world, "booth-network-voted") { el, _, _ ->
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil("explained", 2000) { el.flatText().contains("Verbindungsabbruch") }
                assertTrue(el.flatText().contains("Ihre Stimme wurde gezählt."))
            }
        }

    @Test
    fun aLostConnection_withNoBallotStored_andOpenVoting_allowsATryAgain(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN), participation(hasVoted = false))
            world.castNetworkError = true
            withBooth(world, "booth-network-retry") { el, _, _ ->
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil("explained", 2000) { el.flatText().contains("Stimmabgabe gerade nicht möglich") }
                assertTrue(el.hasButton("Erneut abstimmen"))
                el.buttonNamed("Erneut abstimmen").click()
                awaitUntil("choice step again", 1500) { el.hasButton("Weiter zur Prüfung") }
            }
        }

    @Test
    fun aConflict_whileVotingIsStillOpen_isNotPresentedAsAlreadyVoted(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN), participation(hasVoted = false))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "booth-conflict-paused") { el, _, _ ->
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil("explained", 2000) { el.flatText().contains("Stimmabgabe gerade nicht möglich") }
                assertFalse(el.flatText().contains("bereits abgestimmt"), "a paused stream is not 'already voted'")
                assertTrue(el.flatText().contains("Übertragung"))
            }
        }

    @Test
    fun aConflict_afterVotingEnded_saysVotingIsNoLongerOpen(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN), participation(hasVoted = false))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "booth-conflict-closed") { el, _, _ ->
                el.chooseTile("Ja")
                world.election = election(type = ElectionType.YES_NO, status = ElectionStatus.CLOSED)
                el.castNow()
                awaitUntil("explained", 2000) { el.flatText().contains("Die Abstimmung ist nicht mehr offen.") }
            }
        }

    @Test
    fun aRefusal_saysThereIsNoRightToVote(): Promise<Unit> =
        formTest {
            val routes = electionRoutes()
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN))
            world.failures[routes.cast] = FORBIDDEN_EXCEPTION
            val exits = mutableListOf<Boolean>()
            withBooth(world, "booth-forbidden", exits) { el, _, _ ->
                el.chooseTile("Ja")
                el.castNow()
                awaitUntil(
                    "explained",
                    2000,
                ) { el.flatText().contains("Sie stehen nicht im Wählerverzeichnis dieser Wahl und können nicht abstimmen.") }
                el.buttonNamed("Zurück zur Wahl").click()
                assertEquals(listOf(true), exits)
            }
        }

    @Test
    fun cancelling_leavesTheBoothWithoutAReload_andWithoutARequest(): Promise<Unit> =
        formTest {
            val world = ElectionWorld(election(type = ElectionType.YES_NO, status = ElectionStatus.OPEN))
            val exits = mutableListOf<Boolean>()
            withBooth(world, "booth-cancel", exits) { el, calls, routes ->
                el.chooseTile("Ja")
                el.buttonNamed("Abbrechen").click()
                assertEquals(listOf(false), exits)
                assertEquals(0, calls.toRoute(routes.cast).size)
                delay(30)
            }
        }
}
