package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptResistanceDto
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptVerificationDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.Event
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.28: the list and the detail view of a consensus, mounted for real with a stubbed server -- who sees which action in which phase,
 * what each write sends, what a conflict never shows, and what the result may and may not reveal.
 */
class ConsensusDetailDomTest {
    private val ctx = ConsensusUiContext(currentMemberId = "m-1")

    private fun session(status: MemberStatus = MemberStatus.ACTIVE) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Mitglied",
            role = AccountRole.MEMBER,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = status,
        )

    private suspend fun <T> withDetail(
        world: ConsensusWorld,
        id: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        block: suspend (HTMLElement, List<RecordedRequest>, ConsensusRoutes) -> T,
    ): T {
        AppState.setSession(session(status))
        val routes = consensusRoutes()
        return withFetchStub(respond = world.respond(routes)) { calls ->
            mountedForm(id) { root, element ->
                renderConsensusDetail(root, "k1", ctx)
                awaitUntil("detail rendered", timeoutMs = 3000) { element().flatText().contains(world.consensus.title) }
                block(element(), calls, routes)
            }
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theList_showsBadgesRoundAndTheViewersOwnState_newestFirst_withOneBatchCall(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = consensusRoutes()
            val older = consensus(status = SystemicConsensusStatus.EVALUATED, title = "Ältere Sache")
            val newer =
                consensus(status = SystemicConsensusStatus.RATING, title = "Neuere Sache", round = 2, secret = false).copy(
                    id = "k2",
                    openedAt = LocalDateTime(2026, 7, 1, 12, 0),
                )
            val world =
                ConsensusWorld(
                    older,
                    all = listOf(older, newer),
                    participations = listOf(skParticipation(id = "k1"), skParticipation(id = "k2", canRate = true)),
                )
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("sk-list") { root, element ->
                    renderConsensusScreen(root)
                    awaitUntil("list rendered", 3000) { element().flatText().contains("Neuere Sache") }
                    val text = element().flatText()
                    assertTrue(text.indexOf("Neuere Sache") < text.indexOf("Ältere Sache"), "newest first: $text")
                    assertTrue(text.contains("Bewertung läuft") && text.contains("Ausgewertet"))
                    assertTrue(text.contains("Runde 2 von 3") && text.contains("Anonym") && text.contains("Offen"))
                    assertTrue(text.contains("Offen für Sie"), "the viewer's own state of the running rating")
                    assertEquals(1, calls.toRoute(routes.listParticipations).size, "one batch call, not one per row")
                    assertTrue(element().hasButton("Zu den Anträgen"))
                }
            }
        }

    @Test
    fun theStatusFilter_isSentToTheServer_andAnEmptyListExplainsWhereAConsensusComesFrom(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = consensusRoutes()
            val world = ConsensusWorld(consensus(), all = emptyList())
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("sk-filter") { root, element ->
                    renderConsensusScreen(root)
                    awaitUntil("empty state", 3000) {
                        element().flatText().contains(
                            "Noch kein Konsensieren vorhanden. Ein Konsensieren wird aus einem terminierten Antrag heraus eröffnet.",
                        )
                    }
                    val select = element().controlOf("Status") as HTMLSelectElement
                    select.value = SystemicConsensusStatus.RATING.name
                    select.dispatchEvent(Event("change"))
                    element().buttonNamed("Aktualisieren").click()
                    awaitUntil("second load", 3000) { calls.toRoute(routes.list).size == 2 }
                    assertEquals("RATING", calls.toRoute(routes.list).last().rpcParam(1) as String)
                }
            }
        }

    @Test
    fun aTitleWithAnI18nMarker_isShownSanitized_inTheListAndInTheDetail(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = consensusRoutes()
            val evil = consensus(title = "###KvI18nS###Wahlausschuss <img src=x onerror=alert(1)>")
            val world = ConsensusWorld(evil, all = listOf(evil), participations = listOf(skParticipation()))
            withFetchStub(respond = world.respond(routes)) { _ ->
                mountedForm("sk-evil-list") { root, element ->
                    renderConsensusScreen(root)
                    awaitUntil("list rendered", 3000) { element().flatText().contains("Wahlausschuss") }
                    assertFalse(element().innerHTML.contains("###KvI18n"))
                    assertEquals(0, element().allOf("img").size)
                }
                mountedForm("sk-evil-detail") { root, element ->
                    renderConsensusDetail(root, "k1", ctx)
                    awaitUntil("detail rendered", 3000) { element().flatText().contains("Wahlausschuss") }
                    assertFalse(element().innerHTML.contains("###KvI18n"))
                    assertEquals(0, element().allOf("img").size)
                }
            }
        }

    @Test
    fun theDetailRoute_hasABackButton_andLoadsNoList(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val routes = consensusRoutes()
            val world = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, title = "Detailsache"))
            withFetchStub(respond = world.respond(routes)) { calls ->
                mountedForm("sk-detail-route") { root, element ->
                    renderConsensusScreen(root, initialConsensusId = "k1")
                    awaitUntil("detail rendered", 3000) { element().flatText().contains("Detailsache") }
                    assertTrue(element().hasButton("Zur Übersicht"))
                    assertEquals(0, calls.toRoute(routes.list).size)
                }
            }
        }

    // ── COLLECTION ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun inCollection_theStatusQuoOptionIsLast_withoutRemoveButton_andOwnOptionsCanBeRemoved(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(), skParticipation(canPropose = true, canManage = false))
            withDetail(world, "sk-collection") { el, _, _ ->
                val items = el.allOf("li.list-group-item")
                assertEquals(3, items.size)
                assertTrue(
                    items
                        .last()
                        .textContent
                        .orEmpty()
                        .contains("Alles bleibt wie bisher (Passivlösung)"),
                )
                assertTrue(
                    items
                        .last()
                        .textContent
                        .orEmpty()
                        .contains("Immer dabei"),
                )
                assertFalse(
                    items.last().allOf("button").any { it.textContent?.trim() == "Entfernen" },
                    "the status quo option cannot be removed",
                )
                assertTrue(
                    items.first { it.textContent.orEmpty().contains("Option B") }.allOf("button").any {
                        it.textContent?.trim() ==
                            "Entfernen"
                    },
                )
                assertFalse(
                    items.first { it.textContent.orEmpty().contains("Option A") }.allOf("button").any {
                        it.textContent?.trim() ==
                            "Entfernen"
                    },
                )
                assertFalse(el.innerHTML.contains("Status quo (no change)"))
                assertTrue(el.hasButton("Option hinzufügen"))
                assertFalse(el.hasButton("Optionen einfrieren"), "a plain member does not freeze")
            }
        }

    @Test
    fun addingAnOption_isBlockedForBlankAndTooLongText_andSendsTheTrimmedText(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(), skParticipation(canPropose = true))
            withDetail(world, "sk-add") { el, calls, routes ->
                el.typeInto("Neue Option", "   ")
                el.buttonNamed("Option hinzufügen").click()
                el.typeInto("Neue Option", "x".repeat(201))
                el.buttonNamed("Option hinzufügen").click()
                awaitUntil("error", 1500) { el.flatText().contains("Bitte geben Sie 1 bis 200 Zeichen ein.") }
                assertEquals(0, calls.toRoute(routes.addOption).size)
                el.typeInto("Neue Option", "  Mehr Platz  ")
                el.buttonNamed("Option hinzufügen").click()
                awaitUntil("sent", 1500) { calls.toRoute(routes.addOption).size == 1 }
                val call = calls.singleCall(routes.addOption)
                assertEquals("k1", call.rpcParam(0) as String)
                assertEquals("Mehr Platz", call.rpcParam(1).label as String)
            }
        }

    @Test
    fun aMemberWithoutProposalRights_seesNoAddForm_andAGuestIsToldWhy(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(consensus(), skParticipation(canPropose = false, eligible = null, canRate = false))
            withDetail(world, "sk-guest", status = MemberStatus.GUEST) { el, _, _ ->
                assertFalse(el.hasButton("Option hinzufügen"))
                assertTrue(el.flatText().contains("Nur Mitglieder dieses Servers können bewerten."))
            }
        }

    @Test
    fun freeze_isDisabledWithoutOptions_andOtherwiseAsksForConfirmationWithTheCount(): Promise<Unit> =
        formTest {
            val none = ConsensusWorld(consensus(options = emptyList()), skParticipation(canManage = true))
            withDetail(none, "sk-freeze-none") { el, calls, routes ->
                assertTrue(el.isButtonDisabled("Optionen einfrieren"))
                assertTrue(el.flatText().contains("Es gibt noch keine Option."))
                el.buttonNamed("Optionen einfrieren").click()
                assertEquals(0, calls.toRoute(routes.freeze).size)
            }
            val world = ConsensusWorld(consensus(), skParticipation(canManage = true))
            withDetail(world, "sk-freeze") { el, calls, routes ->
                el.buttonNamed("Optionen einfrieren").click()
                val modal = lastOpenModal()
                assertTrue(
                    modal.textContent.orEmpty().contains(
                        "3 Optionen werden zur Bewertung freigegeben. Danach kann keine Option mehr ergänzt werden.",
                    ),
                )
                assertEquals(0, calls.toRoute(routes.freeze).size, "the dialog comes first")
                modal.buttonNamed("Einfrieren").click()
                awaitUntil("freeze sent", 1500) { calls.toRoute(routes.freeze).size == 1 }
                assertEquals("k1", calls.singleCall(routes.freeze).rpcParam(0) as String)
            }
        }

    @Test
    fun aConflictOnFreeze_reloads_andShowsOnlyTheFixedText(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world = ConsensusWorld(consensus(), skParticipation(canManage = true))
            world.failures[routes.freeze] = CONFLICT_EXCEPTION
            withDetail(world, "sk-freeze-conflict") { el, calls, _ ->
                el.buttonNamed("Optionen einfrieren").click()
                lastOpenModal().buttonNamed("Einfrieren").click()
                awaitUntil("reloaded", 3000) { calls.toRoute(routes.get).size >= 2 }
                assertFalse(el.innerHTML.contains("simulated"), "no server message in the DOM")
            }
        }

    // ── RATING / CLOSED ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun inRating_aMemberGetsTheBooth_aManagerAlsoClosesTheRating(): Promise<Unit> =
        formTest {
            val member =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING), skParticipation(canRate = true, ballotCount = 1))
            withDetail(member, "sk-rating-member") { el, _, _ ->
                assertTrue(el.hasButton("Zur Bewertung"))
                assertFalse(el.hasButton("Bewertung schließen"))
                assertTrue(el.flatText().contains("1 von 4 haben bewertet"))
            }
            val routes = consensusRoutes()
            val manager =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING), skParticipation(canRate = true, canManage = true))
            withDetail(manager, "sk-rating-manager") { el, calls, _ ->
                assertTrue(el.hasButton("Zur Bewertung") && el.hasButton("Bewertung schließen"))
                el.buttonNamed("Bewertung schließen").click()
                lastOpenModal().buttonNamed("Bewertung schließen").click()
                awaitUntil("close sent", 1500) { calls.toRoute(routes.close).size == 1 }
            }
        }

    @Test
    fun aMemberWhoHasRated_orIsNotEligible_isToldInPlace_andGetsNoBooth(): Promise<Unit> =
        formTest {
            val rated =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING), skParticipation(hasRated = true, canRate = false))
            withDetail(rated, "sk-rated") { el, _, _ ->
                assertTrue(el.flatText().contains("Ihre Bewertung ist bereits eingegangen."))
                assertFalse(el.hasButton("Zur Bewertung"))
            }
            val outsider =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING), skParticipation(eligible = false, canRate = false))
            withDetail(outsider, "sk-outsider") { el, _, _ ->
                assertTrue(el.flatText().contains("Sie sind für diese Runde nicht stimmberechtigt."))
                assertFalse(el.hasButton("Zur Bewertung"))
            }
        }

    @Test
    fun inClosed_theOnlyMainActionIsEvaluate_noRevote(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.CLOSED), skParticipation(canManage = true, canRate = false))
            withDetail(world, "sk-closed") { el, calls, _ ->
                assertTrue(el.hasButton("Auswerten"))
                assertFalse(el.allOf("button").any { it.textContent.orEmpty().startsWith("Diskutieren und erneut bewerten") })
                el.buttonNamed("Auswerten").click()
                awaitUntil("evaluate sent", 1500) { calls.toRoute(routes.evaluate).size == 1 }
            }
        }

    @Test
    fun evaluatingABindingConsensus_asksFirst_andSaysItCannotBeUndone(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val binding = consensus(status = SystemicConsensusStatus.CLOSED, bindingness = SystemicConsensusBindingness.BINDING)
            val world = ConsensusWorld(binding, skParticipation(canManage = true, canRate = false))
            withDetail(world, "sk-binding-evaluate") { el, calls, _ ->
                assertTrue(
                    el.flatText().contains("Das Ergebnis wird als Beschluss protokolliert und kann danach nicht mehr geändert werden."),
                )
                el.buttonNamed("Auswerten").click()
                assertEquals(0, calls.toRoute(routes.evaluate).size, "a binding evaluation needs the dialog first")
                lastOpenModal().buttonNamed("Auswerten").click()
                awaitUntil("evaluate sent", 1500) { calls.toRoute(routes.evaluate).size == 1 }
            }
        }

    // ── abort ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun abortingARunningConsensus_needsTheTitleTyped_aSimpleDialogSufficesBefore(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val running =
                ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, title = "Vereinsheim"), skParticipation(canManage = true))
            withDetail(running, "sk-abort-rating") { el, calls, _ ->
                el.buttonNamed("Konsensieren abbrechen").click()
                val modal = lastOpenModal()
                val input = assertNotNull(modal.querySelector("input") as? HTMLInputElement)
                assertTrue(modal.isButtonDisabled("Konsensieren abbrechen"))
                input.value = "Vereinsheim"
                input.dispatchEvent(Event("input"))
                awaitUntil("enabled", 1500) { !modal.isButtonDisabled("Konsensieren abbrechen") }
                modal.buttonNamed("Konsensieren abbrechen").click()
                awaitUntil("abort sent", 1500) { calls.toRoute(routes.abort).size == 1 }
            }
            val collecting = ConsensusWorld(consensus(), skParticipation(canManage = true))
            withDetail(collecting, "sk-abort-collection") { el, calls, _ ->
                el.buttonNamed("Konsensieren abbrechen").click()
                val modal = lastOpenModal()
                assertEquals(null, modal.querySelector("input"))
                modal.buttonNamed("Abbrechen").click()
                assertEquals(0, calls.toRoute(routes.abort).size)
            }
        }

    // ── EVALUATED ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun evaluated(
        secret: Boolean = true,
        binding: SystemicConsensusBindingness = SystemicConsensusBindingness.ADVISORY,
        round: Int = 1,
    ) = consensus(status = SystemicConsensusStatus.EVALUATED, secret = secret, bindingness = binding, round = round, winnerOptionId = "o-a")

    @Test
    fun theResult_isRankedByLowestResistance_withWordsHintAndACollapsedDistribution(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(evaluated(), skParticipation(canRate = false), result = skResult())
            withDetail(world, "sk-result") { el, calls, routes ->
                val text = el.flatText()
                assertTrue(text.indexOf("Option A") < text.indexOf("Option B"), "lowest resistance first")
                assertTrue(text.contains("Geringster Widerstand: Option A"))
                assertTrue(text.contains("Ø 2,4 von 10") && text.contains("Ø 4,0 von 10"))
                assertTrue(text.contains("Gruppenkonflikt: Tragfähiger Konsens (0,24)".replace("Tragfähiger Konsens", "Mit Bedenken")))
                assertTrue(text.contains("Gruppenkonflikt: Warnsignal (0,75)"))
                assertTrue(
                    text.contains("Höchster Einzelwert: 10. Das ist ein starker Einwand."),
                    "a strong objection is not hidden by the mean",
                )
                assertEquals(1, calls.toRoute(routes.getResult).size, "the result is read through the read-only call")
                assertEquals(0, el.allOf(".lapis-sk-hist").size, "the distribution is collapsed by default (not even in the DOM)")
                el.allOf("button").first { it.textContent.orEmpty().startsWith("Verteilung") }.click()
                awaitUntil("distribution opened", 1500) { el.allOf(".lapis-sk-hist").isNotEmpty() }
                val hist = el.allOf(".lapis-sk-hist").first()
                assertEquals(11, hist.children.length, "eleven columns 0..10, gaps filled with 0")
                assertTrue(hist.flatText().startsWith("0012203040"), "column 0 has count 0, column 1 has count 2: ${hist.flatText()}")
                assertEquals(0, calls.toRoute(routes.listBallots).size, "an anonymous consensus never asks for single ratings")
            }
        }

    @Test
    fun theDistributionToggle_opensAndCloses(): Promise<Unit> =
        formTest {
            val world = ConsensusWorld(evaluated(), skParticipation(canRate = false), result = skResult())
            withDetail(world, "sk-hist-toggle") { el, _, _ ->
                val toggle = el.allOf("button").first { it.textContent.orEmpty().startsWith("Verteilung") }
                toggle.click()
                awaitUntil("expanded", 1500) { toggle.getAttribute("aria-expanded") == "true" }
                assertEquals("Verteilung ausblenden", toggle.textContent?.trim())
                toggle.click()
                awaitUntil("collapsed", 1500) { toggle.getAttribute("aria-expanded") == "false" }
            }
        }

    @Test
    fun noRatings_andATieWithoutWinner_sayWhatHappened(): Promise<Unit> =
        formTest {
            val none =
                ConsensusWorld(
                    evaluated(),
                    skParticipation(canRate = false),
                    result = skResult(winner = null, noRatings = true, results = emptyList()),
                )
            withDetail(none, "sk-no-ratings") { el, _, _ -> assertTrue(el.flatText().contains("Es wurde keine Bewertung abgegeben.")) }
            val tie = ConsensusWorld(evaluated(), skParticipation(canRate = false), result = skResult(winner = null))
            withDetail(
                tie,
                "sk-tie",
            ) { el, _, _ -> assertTrue(el.flatText().contains("Gleichstand ohne Entscheidung. Bitte erneut diskutieren.")) }
        }

    @Test
    fun aTiedMean_ranksTheTiebreakWinnerFirst_andExplainsTheTiebreak(): Promise<Unit> =
        formTest {
            // o-a precedes o-b by id and has the same mean, but the server decided for o-b (lower maximum).
            val world =
                ConsensusWorld(
                    evaluated().copy(winnerOptionId = "o-b"),
                    skParticipation(canRate = false),
                    result =
                        skResult(
                            winner = "o-b",
                            tiebreak = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
                            results =
                                listOf(
                                    skOptionResult("o-a", mean = 3.0, index = 0.3, max = 9),
                                    skOptionResult("o-b", mean = 3.0, index = 0.3, max = 5),
                                    skOptionResult("o-sq", mean = 7.5, index = 0.75, max = 10),
                                ),
                        ),
                )
            withDetail(world, "sk-tiebreak-order") { el, _, _ ->
                val text = el.flatText()
                assertTrue(text.contains("entschieden durch den geringsten Höchstwert"))
                val first = el.allOf(".lapis-sk-rank").first()
                assertTrue(first.classList.contains("lapis-sk-rank--winner"), "the tiebreak winner is rank 1")
            }
        }

    @Test
    fun aBindingResult_saysItWasRecorded_andAWinningStatusQuoMeansRejected(): Promise<Unit> =
        formTest {
            val world =
                ConsensusWorld(
                    evaluated(binding = SystemicConsensusBindingness.BINDING).copy(winnerOptionId = "o-sq"),
                    skParticipation(canRate = false, canManage = true),
                    result = skResult(winner = "o-sq"),
                )
            withDetail(world, "sk-binding-result") { el, _, _ ->
                assertTrue(el.flatText().contains("Als Beschluss protokolliert."))
                assertTrue(el.flatText().contains("Die Passivlösung hat gewonnen: Der Antrag gilt als abgelehnt."))
                assertFalse(
                    el.allOf("button").any { it.textContent.orEmpty().startsWith("Diskutieren und erneut bewerten") },
                    "no revote for a resolution",
                )
            }
        }

    @Test
    fun theRevote_isPrimaryAboveTheWarnThreshold_secondaryBelow_andAsksFirst(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val hot =
                ConsensusWorld(
                    evaluated(),
                    skParticipation(canRate = false, canManage = true),
                    result = skResult(results = listOf(skOptionResult("o-a", 6.0, 0.6))),
                )
            withDetail(hot, "sk-revote-hot") { el, calls, _ ->
                val button = el.allOf("button").first { it.textContent.orEmpty().startsWith("Diskutieren und erneut bewerten") }
                assertEquals("Diskutieren und erneut bewerten (Runde 2 von 3)", button.textContent?.trim())
                assertTrue(button.className.contains("btn-primary"))
                button.click()
                assertEquals(0, calls.toRoute(routes.reopen).size, "the dialog comes first")
                lastOpenModal().buttonNamed("Neue Runde starten").click()
                awaitUntil("reopen sent", 1500) { calls.toRoute(routes.reopen).size == 1 }
            }
            val calm =
                ConsensusWorld(
                    evaluated(),
                    skParticipation(canRate = false, canManage = true),
                    result = skResult(results = listOf(skOptionResult("o-a", 1.0, 0.1))),
                )
            withDetail(calm, "sk-revote-calm") { el, _, _ ->
                val button = el.allOf("button").first { it.textContent.orEmpty().startsWith("Diskutieren und erneut bewerten") }
                assertTrue(button.className.contains("btn-outline-primary"))
            }
            val last = ConsensusWorld(evaluated(round = 3), skParticipation(canRate = false, canManage = true), result = skResult())
            withDetail(last, "sk-revote-last") { el, _, _ ->
                assertFalse(
                    el.allOf("button").any { it.textContent.orEmpty().startsWith("Diskutieren und erneut bewerten") },
                    "no round left",
                )
            }
        }

    @Test
    fun anOpenConsensus_listsTheNamedRatings_onlyThere(): Promise<Unit> =
        formTest {
            val ballots =
                listOf(
                    SystemicConsensusBallotDto("b1", "k1", "m-1", "Mia Mitglied", mapOf("o-a" to 2, "o-b" to 5, "o-sq" to 9), SK_AT, 1),
                )
            val world = ConsensusWorld(evaluated(secret = false), skParticipation(canRate = false), result = skResult(), ballots = ballots)
            withDetail(world, "sk-named") { el, calls, routes ->
                // Wait for a ROW of the named table, not for the name anywhere: "Mia Mitglied" also proposed option B, and the option
                // list ("Vorgeschlagen von Mia Mitglied") is on the page as soon as the detail is -- before the ballots are even
                // requested. Waiting for the bare name let the count below run before `listResistanceBallots` had gone out (flaky
                // under CPU load: "Expected <1>, actual <0>").
                awaitUntil("named table row", 3000) {
                    el.allOf("table tbody tr, .lapis-data-card").any { it.textContent.orEmpty().contains("Mia Mitglied") }
                }
                assertEquals(1, calls.toRoute(routes.listBallots).size)
                assertTrue(el.flatText().contains("Namentliche Bewertungen"))
            }
        }

    // ── receipt check ────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun HTMLElement.check(code: String) {
        typeInto("Quittungscode", code)
        buttonNamed("Prüfen").click()
    }

    @Test
    fun theReceiptCheck_saysFoundNotFoundAndOldRound_andShowsValuesOnlyWhenTheServerSendsThem(): Promise<Unit> =
        formTest {
            val routes = consensusRoutes()
            val world =
                ConsensusWorld(
                    consensus(status = SystemicConsensusStatus.RATING, round = 2),
                    skParticipation(canRate = false, hasRated = true),
                )
            withDetail(world, "sk-verify") { el, calls, _ ->
                world.verification =
                    SystemicConsensusReceiptVerificationDto(found = false, round = null, countedInCurrentResult = false, resistances = null)
                el.check(" $TEST_RECEIPT ")
                awaitUntil("not found", 2000) { el.flatText().contains("Zu diesem Code wurde keine Bewertung gefunden.") }
                assertEquals(TEST_RECEIPT, calls.singleCall(routes.verify).rpcParam(1) as String, "whitespace is removed before sending")
                assertEquals("", (el.controlOf("Quittungscode") as HTMLInputElement).value, "the field is cleared after checking")

                world.verification =
                    SystemicConsensusReceiptVerificationDto(found = true, round = 1, countedInCurrentResult = false, resistances = null)
                el.check(TEST_RECEIPT)
                awaitUntil(
                    "old round",
                    2000,
                ) { el.flatText().contains("Diese Bewertung stammt aus Runde 1 und zählt im aktuellen Ergebnis nicht mehr.") }

                world.verification =
                    SystemicConsensusReceiptVerificationDto(found = true, round = 2, countedInCurrentResult = false, resistances = null)
                el.check(TEST_RECEIPT)
                awaitUntil("stored", 2000) {
                    el.flatText().contains("Ihre Bewertung ist gespeichert. Die Werte werden erst nach der Auswertung angezeigt.")
                }

                world.verification =
                    SystemicConsensusReceiptVerificationDto(
                        found = true,
                        round = 2,
                        countedInCurrentResult = true,
                        resistances =
                            listOf(
                                SystemicConsensusReceiptResistanceDto("o-a", false, "Option A", 2),
                                SystemicConsensusReceiptResistanceDto("o-sq", true, SK_SERVER_STATUS_QUO_LABEL, 9),
                            ),
                    )
                el.check(TEST_RECEIPT)
                awaitUntil("values", 2000) { el.flatText().contains("Ihre Bewertung ist gespeichert und lautet:") }
                assertTrue(el.flatText().contains("Option A: Widerstand 2 von 10"))
                assertTrue(el.flatText().contains("Alles bleibt wie bisher (Passivlösung): Widerstand 9 von 10"))
                assertFalse(el.flatText().contains("Status quo (no change)"))
            }
        }

    @Test
    fun theReceiptCheck_existsOnlyForAnAnonymousConsensus(): Promise<Unit> =
        formTest {
            val open = ConsensusWorld(consensus(status = SystemicConsensusStatus.RATING, secret = false), skParticipation())
            withDetail(
                open,
                "sk-no-verify",
            ) { el, _, _ -> assertFalse(el.hasButton("Prüfen") && el.flatText().contains("Quittung prüfen")) }
            val collecting = ConsensusWorld(consensus(), skParticipation())
            withDetail(collecting, "sk-no-verify-collection") { el, _, _ -> assertFalse(el.flatText().contains("Quittung prüfen")) }
        }
}
