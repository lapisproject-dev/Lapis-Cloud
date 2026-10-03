package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.39 -- "Praxis-Abgleich, Teil 1": the status quo option first, the numbers (P, 1..n), the rationale of a proposal (show, edit, remove,
 * add), the highest-value count and the scale-dependent strong-objection threshold, on the options list, the booth and the result.
 */
class ConsensusPracticeDomTest {
    private val ctx = ConsensusUiContext(currentMemberId = "m-1")

    private fun session() =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Mitglied",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    private suspend fun <T> withDetail(
        world: ConsensusWorld,
        id: String,
        block: suspend (HTMLElement, List<RecordedRequest>, ConsensusRoutes) -> T,
    ): T {
        AppState.setSession(session())
        val routes = consensusRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderConsensusDetail(root, "k1", ctx)
                awaitUntil("detail rendered", timeoutMs = 3000) { element().flatText().contains(world.consensus.title) }
                block(element(), calls, routes)
            }
        }
    }

    private suspend fun <T> withBooth(
        world: ConsensusWorld,
        id: String,
        block: suspend (HTMLElement) -> T,
    ): T {
        val routes = consensusRoutes()
        return withFetchStub(respond = world.respond(routes)) {
            mountedForm(id) { root, element ->
                renderConsensusBooth(root, world.consensus) { _ -> }
                block(element())
            }
        }
    }

    private fun HTMLElement.plaques(): List<String> = allOf(".lapis-sk-num").map { it.textContent.orEmpty().trim() }

    private fun withRationales(vararg rationales: Pair<String, String?>): List<SystemicConsensusOptionDto> {
        val byId = rationales.toMap()
        return skOptions().map { it.copy(rationale = byId[it.id]) }
    }

    // ── order and numbers ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theOptionsList_putsTheStatusQuoFirst_numbersTheRealOptionsGapFree_andExplainsWhenTheyAreFinal(): Promise<Unit> =
        formTest {
            // positions with gaps (3 and 7): the numbers are ranks, not positions
            val options =
                listOf(
                    skOption("o-c", "Gamma", 7),
                    skOption("o-sq", SK_SERVER_STATUS_QUO_LABEL, 0, statusQuo = true, createdById = "chair-1"),
                    skOption("o-a", "Alpha", 3),
                )
            val world = ConsensusWorld(consensus(options = options), skParticipation(canPropose = true))
            withDetail(world, "sk-numbers") { el, _, _ ->
                val items = el.allOf("li.list-group-item")
                assertTrue(items[0].flatText().contains("Passivlösung"))
                assertTrue(items[1].flatText().contains("Alpha") && items[2].flatText().contains("Gamma"))
                assertEquals(listOf("P", "1", "2"), el.plaques())
                assertEquals(0, el.allOf(".list-group-numbered").size, "no CSS numbering any more")
                assertEquals(0, items[0].allOf(".badge").size, "'Immer dabei' is a sub-line, not a badge")
                assertTrue(items[0].flatText().contains("Immer dabei"))
                assertTrue(items[1].flatText().contains("Option 1"), "the screen-reader name carries the number")
                assertFalse(items[0].flatText().contains("Option 0"))
                assertTrue(el.flatText().contains("Die Nummern stehen fest, sobald die Optionen festgeschrieben sind."))
            }
            val rating = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, options = options), skParticipation())
            withDetail(rating, "sk-numbers-rating") { el, _, _ ->
                assertEquals(listOf("P", "1", "2"), el.plaques())
                assertFalse(el.flatText().contains("Die Nummern stehen fest"), "only the collection phase says the numbers can still move")
            }
        }

    @Test
    fun equalPositions_areOrderedById() {
        val options =
            listOf(
                skOption("o-b", "B", 1),
                skOption("o-a", "A", 1),
                skOption("o-sq", "x", 0, statusQuo = true),
            )
        assertEquals(listOf("o-sq", "o-a", "o-b"), consensusOrderedOptions(options).map { it.id })
        assertEquals(mapOf("o-sq" to "P", "o-a" to "1", "o-b" to "2"), consensusOptionNumbers(options))
    }

    // ── rationale ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theRationale_isShownVerbatimWithLineBreaks_andOnlyWhereThereIsOne(): Promise<Unit> =
        formTest {
            val world =
                ConsensusWorld(
                    consensus(options = withRationales("o-a" to "Weil es geht.\nUnd es ist günstig.")),
                    skParticipation(canPropose = true),
                )
            withDetail(world, "sk-why-show") { el, _, _ ->
                val why = el.allOf(".lapis-sk-why")
                assertEquals(1, why.size)
                assertEquals("Weil es geht.\nUnd es ist günstig.", why[0].textContent)
                assertEquals(0, el.allOf("[title]").size, "the rationale never goes into a title")
            }
        }

    @Test
    fun onlyTheProposerOrAManager_seesTheEditButton_neverForTheStatusQuoOption(): Promise<Unit> =
        formTest {
            val plain = ConsensusWorld(consensus(options = withRationales("o-b" to "alt")), skParticipation(canPropose = true))
            withDetail(plain, "sk-why-edit-plain") { el, _, _ ->
                val items = el.allOf("li.list-group-item")
                assertEquals(1, items.count { it.hasButton("Begründung bearbeiten") }, "only the own proposal (o-b, m-1)")
                assertTrue(items.first { it.flatText().contains("Option B") }.hasButton("Begründung bearbeiten"))
                assertFalse(items.first { it.flatText().contains("Option A") }.hasButton("Begründung hinzufügen"))
            }
            val manager = ConsensusWorld(consensus(), skParticipation(canPropose = true, canManage = true))
            withDetail(manager, "sk-why-edit-manager") { el, _, _ ->
                val items = el.allOf("li.list-group-item")
                assertFalse(items[0].hasButton("Begründung hinzufügen"), "never for the status quo option")
                assertTrue(items[1].hasButton("Begründung hinzufügen") && items[2].hasButton("Begründung hinzufügen"))
            }
            val rating = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING), skParticipation(canManage = true))
            withDetail(rating, "sk-why-edit-rating") { el, _, _ ->
                assertEquals(0, el.allOf("button").count { it.textContent.orEmpty().startsWith("Begründung") }, "frozen options are final")
            }
        }

    @Test
    fun saving_sendsTheTrimmedText_once_andRemovingAsksFirst(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(options = withRationales("o-b" to "alt")), skParticipation(canPropose = true))
            withDetail(world, "sk-why-save") { el, calls, routes ->
                val item = el.allOf("li.list-group-item").first { it.flatText().contains("Option B") }
                item.buttonNamed("Begründung bearbeiten").click()
                awaitUntil("editor", 1500) { item.hasButton("Speichern") }
                item.typeInto("Begründung", "  Neuer Grund  ")
                val save = item.buttonNamed("Speichern")
                save.click()
                save.click()
                awaitUntil("sent", 1500) { calls.toRoute(routes.setRationale).isNotEmpty() }
                awaitUntil("settled", 1500) { calls.toRoute(routes.get).size >= 2 }
                assertEquals(1, calls.toRoute(routes.setRationale).size, "a double click sends once")
                val call = calls.singleCall(routes.setRationale)
                assertEquals("o-b", call.rpcParam(0) as String)
                assertEquals("Neuer Grund", call.rpcParam(1) as String)
            }
            withDetail(world, "sk-why-remove") { el, calls, routes ->
                val item = el.allOf("li.list-group-item").first { it.flatText().contains("Option B") }
                item.buttonNamed("Begründung bearbeiten").click()
                awaitUntil("editor", 1500) { item.hasButton("Begründung entfernen") }
                item.buttonNamed("Begründung entfernen").click()
                assertEquals(0, calls.toRoute(routes.setRationale).size, "the dialog comes first")
                lastOpenModal().buttonNamed("Begründung entfernen").click()
                awaitUntil("remove sent", 1500) { calls.toRoute(routes.setRationale).size == 1 }
                assertTrue(calls.singleCall(routes.setRationale).rpcParam(1) == null)
            }
        }

    @Test
    fun anOverlongRationale_isBlockedBeforeSending_andAConflictShowsOnlyTheFixedText(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(consensus(), skParticipation(canPropose = true))
            withDetail(world, "sk-why-invalid") { el, calls, _ ->
                val item = el.allOf("li.list-group-item").first { it.flatText().contains("Option B") }
                item.buttonNamed("Begründung hinzufügen").click()
                awaitUntil("editor", 1500) { item.hasButton("Speichern") }
                item.typeInto("Begründung", "x".repeat(1001))
                item.buttonNamed("Speichern").click()
                awaitUntil("error", 1500) { item.flatText().contains("Bitte geben Sie höchstens 1000 Zeichen ein.") }
                assertEquals(0, calls.toRoute(routes.setRationale).size)
            }
            world.failures[routes.setRationale] = CONFLICT_EXCEPTION
            withDetail(world, "sk-why-conflict") { el, calls, _ ->
                val item = el.allOf("li.list-group-item").first { it.flatText().contains("Option B") }
                item.buttonNamed("Begründung hinzufügen").click()
                awaitUntil("editor", 1500) { item.hasButton("Speichern") }
                item.typeInto("Begründung", "Ein Grund")
                item.buttonNamed("Speichern").click()
                awaitUntil("reloaded", 3000) { calls.toRoute(routes.get).size >= 2 }
                assertFalse(el.innerHTML.contains("simulated"), "no server message in the DOM")
            }
        }

    @Test
    fun theAddForm_sendsTheRationale_orNothing(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(), skParticipation(canPropose = true))
            withDetail(world, "sk-add-why") { el, calls, routes ->
                el.typeInto("Neue Option", "Mehr Platz")
                el.typeInto("Begründung (optional)", "  Weil wir wachsen  ")
                el.buttonNamed("Option hinzufügen").click()
                awaitUntil("sent", 1500) { calls.toRoute(routes.addOption).size == 1 }
                val input = calls.singleCall(routes.addOption).rpcParam(1)
                assertEquals("Mehr Platz", input.label as String)
                assertEquals("Weil wir wachsen", input.rationale as String)
            }
            withDetail(world, "sk-add-nowhy") { el, calls, routes ->
                el.typeInto("Neue Option", "Ohne Grund")
                el.buttonNamed("Option hinzufügen").click()
                awaitUntil("sent", 1500) { calls.toRoute(routes.addOption).size == 1 }
                assertTrue(calls.singleCall(routes.addOption).rpcParam(1).rationale == null)
            }
        }

    @Test
    fun aHostileRationale_isShownLiterally_everywhere_neverTranslatedNeverAsMarkup(): Promise<Unit> =
        formTest {
            val hostile = "###KvI###KvI18nS###18nS###Mehr anzeigen <img src=x onerror=alert(1)>"
            val options = withRationales("o-b" to hostile, "o-a" to hostile)

            fun assertLiteral(el: HTMLElement) {
                assertFalse(el.innerHTML.contains("###KvI"), "no forged i18n marker survives")
                assertNull(el.querySelector("img"))
                assertTrue(el.flatText().contains("Mehr anzeigen <img src=x onerror=alert(1)>"))
            }
            val world = ConsensusWorld(consensus(options = options), skParticipation(canPropose = true))
            withDetail(world, "sk-hostile-list") { el, _, _ ->
                assertLiteral(el)
                val item = el.allOf("li.list-group-item").first { it.flatText().contains("Option B") }
                item.buttonNamed("Begründung bearbeiten").click()
                awaitUntil("editor", 1500) { item.hasButton("Speichern") }
                val area = item.controlOf("Begründung") as org.w3c.dom.HTMLTextAreaElement
                assertEquals("Mehr anzeigen <img src=x onerror=alert(1)>", area.value, "the editor is prefilled with the sanitized text")
            }
            val rating = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, options = options))
            withBooth(rating, "sk-hostile-booth") { el -> assertLiteral(el) }
            val evaluated =
                ConsensusWorld(
                    consensus(status = SystemicConsensusStatus.EVALUATED, options = options, winnerOptionId = "o-a"),
                    skParticipation(canRate = false),
                    result = skResult(),
                )
            withDetail(evaluated, "sk-hostile-result") { el, _, _ -> assertLiteral(el) }
        }

    // ── booth ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theBooth_listsTheStatusQuoFirst_withPlaques_andAScaleDependentLegend(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, scaleMax = 7))
            withBooth(world, "sk-booth-scale7") { el ->
                assertEquals(listOf("P", "1", "2"), el.plaques())
                assertEquals(
                    "Alles bleibt wie bisher (Passivlösung)",
                    el.allOf("legend .lapis-sk-option__text").first().textContent,
                )
                assertTrue(el.flatText().contains("0 = kein Widerstand, ich kann gut damit leben · 7 = für mich nicht tragbar."))
                assertEquals(3 * 8, el.allOf("input[type=radio]").size, "eight radio buttons 0..7 per option")
            }
        }

    @Test
    fun theBooth_clampsALongRationale_withAnAccessibleSwitch_andNeverExposesIdsOrTitles(): Promise<Unit> =
        formTest {
            val long = "Sehr ausführlich. ".repeat(30)
            val world =
                ConsensusWorld(
                    consensus(status = SystemicConsensusStatus.RATING, options = withRationales("o-a" to long, "o-b" to "Kurz")),
                )
            withBooth(world, "sk-booth-why") { el ->
                val toggles = el.allOf("button").filter { it.textContent?.trim() == "Mehr anzeigen" }
                assertEquals(1, toggles.size, "only the long text gets the switch")
                val toggle = toggles.single()
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                val bodyId = assertNotNull(toggle.getAttribute("aria-controls"))
                assertTrue(bodyId.startsWith("sk-why-"), bodyId)
                val body = assertNotNull(el.querySelector("#$bodyId") as? HTMLElement)
                assertTrue(body.classList.contains("lapis-sk-why--clamped"))
                toggle.click()
                awaitUntil("expanded", 1500) { toggle.getAttribute("aria-expanded") == "true" }
                assertFalse(body.classList.contains("lapis-sk-why--clamped"))
                assertEquals("Weniger anzeigen", toggle.textContent?.trim())
                el.allOf("[id]").forEach { node ->
                    listOf("o-a", "o-b", "o-sq").forEach { optionId ->
                        assertFalse(node.id.contains(optionId), "id ${node.id} leaks an option id")
                    }
                }
                assertEquals(0, el.allOf("[title]").size)
                assertEquals(0, el.allOf("fieldset [aria-label]").size)
            }
        }

    @Test
    fun theReviewStep_showsThePlaque_butNeverTheRationale(): Promise<Unit> =
        formTest {
            val world =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, options = withRationales("o-a" to "Mein Grund")))
            withBooth(world, "sk-booth-review-why") { el ->
                el.allOf("fieldset").forEach { fieldset -> fieldset.allOf("label").first { it.textContent?.trim() == "3" }.click() }
                awaitUntil("review enabled", 1500) { !el.isButtonDisabled("Prüfen") }
                el.buttonNamed("Prüfen").click()
                awaitUntil("review step", 1500) { el.hasButton("Endgültig abgeben") }
                assertEquals(listOf("P", "1", "2"), el.allOf(".lapis-booth-review .lapis-sk-num").map { it.textContent.orEmpty().trim() })
                assertEquals(0, el.allOf(".lapis-sk-why").size)
                assertFalse(el.flatText().contains("Mein Grund"))
            }
        }

    // ── result ───────────────────────────────────────────────────────────────────────────────────────────────────

    private fun evaluated(
        scaleMax: Int,
        maxOfA: Int,
        maxOfB: Int,
        topCountOfA: Int = 0,
        options: List<SystemicConsensusOptionDto> = skOptions(),
    ) = ConsensusWorld(
        consensus(status = SystemicConsensusStatus.EVALUATED, scaleMax = scaleMax, options = options, winnerOptionId = "o-a"),
        skParticipation(canRate = false),
        result =
            skResult(
                results =
                    listOf(
                        skOptionResult("o-sq", mean = 5.0, index = 0.5, max = 1, distribution = mapOf(1 to 3)),
                        skOptionResult(
                            "o-a",
                            mean = 2.0,
                            index = 0.2,
                            max = maxOfA,
                            distribution =
                                mapOf(1 to 2) + (scaleMax to topCountOfA),
                        ),
                        skOptionResult("o-b", mean = 3.0, index = 0.3, max = maxOfB, distribution = mapOf(1 to 3)),
                    ),
            ),
    )

    @Test
    fun theStrongObjectionThreshold_followsTheScale(): Promise<Unit> =
        formTest {
            data class Case(
                val scaleMax: Int,
                val belowMax: Int,
                val atMax: Int,
            )
            listOf(Case(10, 8, 9), Case(7, 6, 7), Case(5, 4, 5)).forEachIndexed { index, case ->
                // o-a stays below the threshold, o-b reaches it; the status quo option (max 1) never does
                val world = evaluated(case.scaleMax, maxOfA = case.belowMax, maxOfB = case.atMax)
                withDetail(world, "sk-threshold-$index") { el, _, _ ->
                    val strong = "Höchster Einzelwert: %1. Das ist ein starker Einwand."
                    assertEquals(
                        1,
                        el.flatText().split("Das ist ein starker Einwand.").size - 1,
                        "scale ${case.scaleMax}: exactly one option is a strong objection",
                    )
                    assertTrue(el.flatText().contains(strong.replace("%1", case.atMax.toString())), "scale ${case.scaleMax}")
                    assertFalse(el.flatText().contains(strong.replace("%1", case.belowMax.toString())), "scale ${case.scaleMax}")
                }
            }
        }

    @Test
    fun theResult_showsThePlaqueInsteadOfTheRank_andHowOftenTheTopValueWasGiven_evenWhenZero(): Promise<Unit> =
        formTest {
            val world = evaluated(scaleMax = 10, maxOfA = 5, maxOfB = 5, topCountOfA = 2)
            withDetail(world, "sk-result-plaque") { el, _, _ ->
                val ranks = el.allOf(".lapis-sk-rank")
                assertEquals(listOf("1"), ranks[0].plaques(), "first row is option 1 (o-a)")
                assertEquals(listOf("2"), ranks[1].plaques(), "second row is option 2 (o-b)")
                assertEquals(listOf("P"), ranks[2].plaques())
                assertTrue(ranks[0].flatText().contains("Platz 1"), "the rank is read out")
                assertFalse(ranks[0].allOf(".lapis-num").any { it.textContent?.trim() == "1" }, "no visible rank digit any more")
                assertTrue(ranks[0].flatText().contains("Höchstwert 10 vergeben: 2-mal"), ranks[0].flatText())
                assertTrue(ranks[1].flatText().contains("Höchstwert 10 vergeben: 0-mal"), "zero is shown too")
            }
        }

    @Test
    fun theResultRationale_isCollapsedBehindAToggle(): Promise<Unit> =
        formTest {
            val world = evaluated(scaleMax = 10, maxOfA = 5, maxOfB = 5, options = withRationales("o-a" to "Darum"))
            withDetail(world, "sk-result-why") { el, _, _ ->
                val toggle = el.allOf("button").first { it.textContent?.trim() == "Begründung anzeigen" }
                val body = assertNotNull(el.querySelector("#" + assertNotNull(toggle.getAttribute("aria-controls"))) as? HTMLElement)
                assertEquals(
                    "none",
                    kotlinx.browser.window
                        .getComputedStyle(body)
                        .display,
                    "collapsed by default",
                )
                toggle.click()
                awaitUntil("opened", 1500) { toggle.getAttribute("aria-expanded") == "true" }
                assertEquals("Begründung ausblenden", toggle.textContent?.trim())
                assertEquals("Darum", body.textContent)
            }
        }
}
