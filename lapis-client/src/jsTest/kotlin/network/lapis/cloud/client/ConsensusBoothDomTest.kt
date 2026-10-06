package network.lapis.cloud.client

import kotlinx.browser.document
import kotlinx.browser.window
import network.lapis.cloud.shared.domain.SystemicConsensusBallotCastResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
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
 * V1.9.28: the resistance booth, mounted for real. It is an irreversible single-shot action, so these tests pin the exact number of
 * requests (never two), what the booth says after every kind of failure, that nothing about a rating leaks into attributes, storage or the
 * console, and that the receipt exists only on screen and only until "Fertig".
 */
class ConsensusBoothDomTest {
    private suspend fun <T> withBooth(
        world: ConsensusWorld,
        id: String,
        exits: MutableList<Boolean> = mutableListOf(),
        block: suspend (HTMLElement, List<RecordedRequest>, ConsensusRoutes) -> T,
    ): T {
        val routes = consensusRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderConsensusBooth(root, world.consensus) { refresh -> exits += refresh }
                block(element(), calls, routes)
            }
        }
    }

    private fun rating() = consensus(status = SystemicConsensusStatus.RATING)

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

    private fun HTMLElement.rateAll(
        statusQuo: Int = 8,
        a: Int = 2,
        b: Int = 5,
    ) {
        rate("Option A", a)
        rate("Option B", b)
        rate("Alles bleibt wie bisher", statusQuo)
    }

    private suspend fun HTMLElement.reviewAndSubmit() {
        awaitUntil("review enabled", 1500) { !isButtonDisabled("Prüfen") }
        buttonNamed("Prüfen").click()
        awaitUntil("review step", 1500) { hasButton("Endgültig abgeben") }
        buttonNamed("Endgültig abgeben").click()
    }

    @Test
    fun nothingIsPreselected_theCheckButtonWaitsForEveryOption_andTheCounterCounts(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating())
            withBooth(world, "sk-booth-select") { el, calls, routes ->
                assertTrue(el.flatText().contains("Bewerten Sie jede Option: Wie groß ist Ihr Widerstand?"))
                assertTrue(el.flatText().contains("0 = kein Widerstand, ich kann gut damit leben · 10 = für mich nicht tragbar."))
                assertTrue(el.allOf("input[type=radio]").none { (it as HTMLInputElement).checked }, "no rating is preselected")
                assertEquals(3 * 11, el.allOf("input[type=radio]").size)
                awaitUntil("counter", 1500) { el.flatText().contains("0 von 3 Optionen bewertet") }
                assertTrue(el.isButtonDisabled("Prüfen"))
                el.rate("Option A", 2)
                awaitUntil("one rated", 1500) { el.flatText().contains("1 von 3 Optionen bewertet") }
                assertTrue(el.isButtonDisabled("Prüfen"))
                el.rate("Option B", 0)
                el.rate("Alles bleibt wie bisher", 10)
                awaitUntil("all rated", 1500) { !el.isButtonDisabled("Prüfen") }
                assertEquals(0, calls.toRoute(routes.cast).size, "nothing is sent while rating")
                // V1.9.39: the status quo option (P) stands FIRST and is shown translated, never with the server's English label
                val legends = el.allOf("legend .lapis-sk-option__text").map { it.textContent.orEmpty() }
                assertEquals("Alles bleibt wie bisher (Passivlösung)", legends.first())
                assertFalse(el.flatText().contains("Status quo (no change)"))
            }
        }

    @Test
    fun theReview_listsTheRatings_andBackKeepsTheChoice(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating())
            withBooth(world, "sk-booth-review") { el, calls, routes ->
                el.rateAll(statusQuo = 9, a = 1, b = 6)
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                el.buttonNamed("Prüfen").click()
                awaitUntil("review", 1500) { el.hasButton("Endgültig abgeben") }
                val text = el.flatText()
                assertTrue(text.contains("Option A: Widerstand 1 von 10") && text.contains("Option B: Widerstand 6 von 10"))
                assertTrue(text.contains("Alles bleibt wie bisher (Passivlösung): Widerstand 9 von 10"))
                assertEquals(0, calls.toRoute(routes.cast).size)
                el.buttonNamed("Zurück").click()
                awaitUntil("back on the rating step", 1500) { el.hasButton("Prüfen") }
                awaitUntil("choice kept", 1500) { !el.isButtonDisabled("Prüfen") }
                val checked = el.allOf("input[type=radio]").count { (it as HTMLInputElement).checked }
                assertEquals(3, checked, "the three ratings are still chosen")
            }
        }

    @Test
    fun theBallot_carriesEveryOptionExactlyOnce_andNothingElse(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating())
            withBooth(world, "sk-booth-send") { el, calls, routes ->
                el.rateAll(statusQuo = 8, a = 2, b = 5)
                el.reviewAndSubmit()
                awaitUntil("cast sent", 1500) { calls.toRoute(routes.cast).size == 1 }
                val input = calls.singleCall(routes.cast).rpcParam(0)
                assertEquals("k1", input.systemicConsensusId as String)
                val map = input.resistances
                assertEquals(2, map["o-a"] as Int)
                assertEquals(5, map["o-b"] as Int)
                assertEquals(8, map["o-sq"] as Int)
                assertEquals(3, js("Object.keys")(map).unsafeCast<Array<String>>().size)
            }
        }

    @Test
    fun theBoothDom_hasNoDataAttribute_andNoOptionIdOrValueInIdsAndNames(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating())
            withBooth(world, "sk-booth-dom") { el, _, _ ->
                el.rateAll()
                val all = el.allOf("*")
                assertTrue(
                    all.none { node ->
                        (0 until node.attributes.length).any {
                            node.attributes
                                .item(it)!!
                                .name
                                .startsWith("data-")
                        }
                    },
                    "no data-* attribute",
                )
                listOf("o-a", "o-b", "o-sq").forEach { id ->
                    assertTrue(
                        all.none {
                            it.id.contains(id) || it.getAttribute("name").orEmpty().contains(id)
                        },
                        "the option id $id must not be in an id or name",
                    )
                }
                assertTrue(el.allOf("input[type=radio]").none { it.hasAttribute("value") }, "a radio button names no value")
            }
        }

    @Test
    fun aDoubleClickOnTheFinalButton_sendsExactlyOneBallot_andTheFormIsGone(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating(), castDelayMs = 250)
            withBooth(world, "sk-booth-double") { el, calls, routes ->
                el.rateAll()
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                el.buttonNamed("Prüfen").click()
                awaitUntil("review", 1500) { el.hasButton("Endgültig abgeben") }
                val submit = el.buttonNamed("Endgültig abgeben")
                submit.click()
                submit.click()
                submit.click()
                awaitUntil("receipt shown", 3000) { el.allOf(".lapis-receipt-code").isNotEmpty() }
                assertEquals(1, calls.toRoute(routes.cast).size, "three clicks, one ballot")
                assertEquals(0, el.allOf("input[type=radio]").size, "the rating form is thrown away after sending")
            }
        }

    @Test
    fun theReceipt_isShownInGroups_needsTheTick_isGoneAfterwards_andLeavesNoTrace(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating())
            val exits = mutableListOf<Boolean>()
            withBeforeUnloadSpy { guards ->
                withConsoleSpy { consoleCalls ->
                    withBooth(world, "sk-booth-receipt", exits) { el, _, _ ->
                        el.rateAll()
                        el.reviewAndSubmit()
                        awaitUntil("receipt shown", 1500) { el.allOf(".lapis-receipt-code").isNotEmpty() }
                        val codeBox = el.allOf(".lapis-receipt-code").first()
                        assertEquals(TEST_RECEIPT, codeBox.textContent.orEmpty())
                        assertEquals(7, codeBox.allOf("span").size, "27 characters in groups of four")
                        val text = el.flatText()
                        assertTrue(text.contains("in Ihrem Browser nicht gespeichert;"))
                        assertTrue(text.contains("zusammen mit Ihrer anonymen Bewertung"), "says where the code really lives")
                        assertTrue(text.contains("dass Ihre Bewertung mitgezählt wurde."))
                        assertTrue(text.contains("Die Quittung zeigt nicht, welche Werte Sie vergeben haben."))
                        assertFalse(text.contains("nirgends gespeichert"), "V1.9.64 regression: the server does keep the code")
                        assertTrue(el.hasButton("Code kopieren") && el.hasButton("Drucken"))
                        assertTrue(el.isButtonDisabled("Fertig"))
                        assertFalse(window.location.href.contains(TEST_RECEIPT))
                        assertNoStoredCode()
                        awaitUntil("leave guard installed", 1500) { guards() == 1 }
                        el.tick("Ich habe mir die Quittung notiert.")
                        awaitUntil("done enabled", 1500) { !el.isButtonDisabled("Fertig") }
                        el.buttonNamed("Fertig").click()
                        awaitUntil("left the booth", 1500) { exits == listOf(true) }
                        assertFalse(document.body!!.innerHTML.contains(TEST_RECEIPT), "the receipt is gone from the whole document")
                        assertEquals(0, guards())
                        assertNoStoredCode()
                    }
                    assertEquals(0, consoleCalls(), "nothing about a rating is logged")
                }
            }
        }

    @Test
    fun anOpenConsensus_hasNoReceipt_justAThankYou(): Promise<Unit> =
        formTest {
            val world =
                ConsensusWorld(
                    consensus(status = SystemicConsensusStatus.RATING, secret = false),
                    castResult = SystemicConsensusBallotCastResultDto("b1", SK_AT, receiptCode = null),
                )
            withBooth(world, "sk-booth-open") { el, _, _ ->
                assertTrue(el.flatText().contains("Dieses Konsensieren ist offen: Ihre Bewertung wird mit Ihrem Namen gespeichert."))
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("thanks", 1500) { el.flatText().contains("Danke, Ihre Bewertung ist eingegangen.") }
                assertEquals(0, el.allOf(".lapis-receipt-code").size)
            }
        }

    @Test
    fun aMalformedReceiptCode_isNeverDrawn_justAThankYou(): Promise<Unit> =
        formTest {
            val bad = "<img src=x onerror=alert(1)>"
            val world = ConsensusWorld(rating(), castResult = SystemicConsensusBallotCastResultDto("b1", SK_AT, receiptCode = bad))
            withBooth(world, "sk-booth-bad-receipt") { el, _, _ ->
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("thanks", 1500) { el.flatText().contains("Danke, Ihre Bewertung ist eingegangen.") }
                assertFalse(el.innerHTML.contains("onerror"))
                assertEquals(0, el.allOf(".lapis-receipt-code").size)
            }
        }

    @Test
    fun aConflict_afterWhichTheMemberHasRated_saysSo(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(rating(), skParticipation(hasRated = true, canRate = false))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "sk-booth-conflict-rated") { el, calls, _ ->
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("explained", 2000) { el.flatText().contains("Ihre Bewertung ist bereits eingegangen.") }
                assertFalse(el.flatText().contains("simulated"), "no server message is ever shown")
                assertEquals(1, calls.toRoute(routes.cast).size)
            }
        }

    @Test
    fun aConflict_afterWhichTheRatingIsClosed_saysSo(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING), skParticipation(hasRated = false, canRate = false))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "sk-booth-conflict-closed") { el, _, _ ->
                el.rateAll()
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                world.onRoute = { route -> if (route == routes.get) world.consensus = consensus(status = SystemicConsensusStatus.CLOSED) }
                el.buttonNamed("Prüfen").click()
                awaitUntil("review", 1500) { el.hasButton("Endgültig abgeben") }
                el.buttonNamed("Endgültig abgeben").click()
                awaitUntil("explained", 2000) {
                    el.flatText().contains(
                        "Ihre Bewertung wurde nicht gezählt: Das Konsensieren ist nicht mehr offen. Die Ansicht wurde aktualisiert.",
                    )
                }
                val alert = el.allOf("[role=alert]").first { it.textContent.orEmpty().contains("nicht gezählt") }
                assertTrue(alert.className.contains("alert-warning"))
                kotlinx.coroutines.delay(300)
                assertTrue(el.allOf("[role=alert]").any { it.textContent.orEmpty().contains("nicht gezählt") }, "the message stays")
            }
        }

    @Test
    fun theReviewStepOfAnAnonymousConsensus_announcesTheReceiptAndWhatItDoesNotShow(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating(), skParticipation(hasRated = false, canRate = true))
            withBooth(world, "sk-booth-receipt-hint") { el, _, _ ->
                el.rateAll()
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                el.buttonNamed("Prüfen").click()
                awaitUntil("review", 1500) { el.hasButton("Endgültig abgeben") }
                assertTrue(
                    el.flatText().contains(
                        "Nach der Abgabe erhalten Sie einen Quittungscode. Damit können Sie später prüfen, " +
                            "dass Ihre Bewertung mitgezählt wurde. " +
                            "Welche Werte Sie vergeben haben, zeigt die Quittung nicht an.",
                    ),
                )
            }
        }

    @Test
    fun aConflictWhileStillOpen_keepsTheRatings_asksToWait_andNeverResendsByItself(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(rating(), skParticipation(hasRated = false, canRate = true))
            world.failures[routes.cast] = CONFLICT_EXCEPTION
            withBooth(world, "sk-booth-conflict-wait") { el, calls, _ ->
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("asked to wait", 2000) { el.flatText().contains("Bitte kurz warten und erneut versuchen.") }
                assertTrue(el.hasButton("Endgültig abgeben"), "the review step is back, the member decides")
                kotlinx.coroutines.delay(400)
                assertEquals(1, calls.toRoute(routes.cast).size, "nothing is resent by itself")
                assertTrue(el.flatText().contains("Option A: Widerstand 2 von 10"), "the ratings are kept")
            }
        }

    @Test
    fun aRefusal_saysTheMemberIsNotEligible(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(rating())
            world.failures[routes.cast] = FORBIDDEN_EXCEPTION
            withBooth(world, "sk-booth-forbidden") { el, _, _ ->
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("explained", 2000) { el.flatText().contains("Sie sind für diese Runde nicht stimmberechtigt.") }
                assertTrue(el.hasButton("Zurück zum Konsensieren"))
            }
        }

    @Test
    fun aLostConnection_neverClaimsMoreThanIsKnown(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(rating(), skParticipation(hasRated = true, canRate = false), castNetworkError = true)
            withBooth(world, "sk-booth-network") { el, _, _ ->
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("explained", 2000) {
                    el.flatText().contains(
                        "Ihre Bewertung wurde gezählt. Die Bestätigung ist wegen eines Verbindungsabbruchs nicht bei Ihnen angekommen.",
                    )
                }
            }
        }

    @Test
    fun anOptionTextWithAnI18nMarker_isShownSanitized_inTheChoiceAndInTheReview(): Promise<Unit> =
        formTest {
            val options = listOf(skOption("o-sq", "x", 0, statusQuo = true), skOption("o-a", "###KvI18nS###Wahlausschuss", 1))
            val world = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, options = options))
            withBooth(world, "sk-booth-marker") { el, _, _ ->
                assertFalse(el.innerHTML.contains("###KvI18n"), "choice step: ${el.flatText()}")
                el.rate("Wahlausschuss", 3)
                el.rate("Alles bleibt wie bisher", 3)
                awaitUntil("enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                el.buttonNamed("Prüfen").click()
                awaitUntil("review", 1500) { el.hasButton("Endgültig abgeben") }
                assertFalse(el.innerHTML.contains("###KvI18n"), "review step: ${el.flatText()}")
            }
        }

    // ── V1.9.32: the same booth, with and without the room host ──────────────────────────────────────────────

    @Test
    fun withoutARoomHost_theBoothIsExactlyAsBefore_noCompactGrid_theCounterInTheHead_theOldExitText(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(rating())
            world.failures[routes.cast] = FORBIDDEN_EXCEPTION
            withBooth(world, "sk-booth-no-host") { el, _, _ ->
                assertNull(el.querySelector(".lapis-booth-compact"), "no compact grid on the consensus screen")
                assertNotNull(el.querySelector(".sticky-top [role=status]"), "the counter stays in the sticky head")
                assertFalse(el.flatText().contains("Gewählt wird die Option mit dem geringsten Gesamtwiderstand."))
                el.rateAll()
                el.reviewAndSubmit()
                awaitUntil("explained", 2000) { el.flatText().contains("Sie sind für diese Runde nicht stimmberechtigt.") }
                assertTrue(el.hasButton("Zurück zum Konsensieren"))
                assertNull(el.querySelector("#lapis-consensus-lock-reason"), "no lock note without a host")
            }
        }

    @Test
    fun withARoomHost_theBoothIsCompact_theCounterNextToCheck_andTheTerminalStateUsesTheHostsExitText(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(rating())
            world.failures[routes.cast] = FORBIDDEN_EXCEPTION
            val busy = mutableListOf<Boolean>()
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("sk-booth-host") { root, element ->
                    renderConsensusBooth(
                        panel = root,
                        consensus = world.consensus,
                        roomHost =
                            ConsensusBoothRoomHost(
                                exitLabel = "Zurück zur Übersicht",
                                ballotLock = null,
                                onBusyChanged = { busy += it },
                                compact = true,
                            ),
                    ) {}
                    val el = element()
                    assertNotNull(el.querySelector(".lapis-booth-compact"))
                    assertNull(el.querySelector(".sticky-top [role=status]"), "no counter in the head")
                    assertTrue(el.flatText().contains("Gewählt wird die Option mit dem geringsten Gesamtwiderstand."))
                    el.rateAll()
                    el.reviewAndSubmit()
                    awaitUntil("explained", 2000) { el.flatText().contains("Sie sind für diese Runde nicht stimmberechtigt.") }
                    assertTrue(el.hasButton("Zurück zur Übersicht"))
                    assertFalse(el.hasButton("Zurück zum Konsensieren"))
                    assertEquals(listOf(true, false), busy, "busy is reported around the request")
                }
            }
        }
}
