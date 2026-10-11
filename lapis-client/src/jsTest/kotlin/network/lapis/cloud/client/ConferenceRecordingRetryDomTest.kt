package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingFailureCode
import network.lapis.cloud.shared.domain.ConferenceRecordingListQuery
import network.lapis.cloud.shared.domain.ConferenceRecordingPageDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceRecordingService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V1.9.107 -- the recordings panel of the conference lobby: "Umkodierung neu starten" (only for a retryable row, left of "Löschen", 44 px
 * target, confirmation with the time estimate), the PROCESSING row with its estimate line, the 30 s poll (here: a few ms through the
 * [RecordingsPolling] seam) with its three stops, the fixed failure text per code (never the server's `failureReason`) and the absence of
 * the `###KvI18nS###` marker anywhere in the DOM.
 */
class ConferenceRecordingRetryDomTest {
    private val timeoutMs = 15_000
    private val rawReason = "RAW-SERVER-REASON-DO-NOT-SHOW"

    @AfterTest
    fun resetSeams() {
        RecordingsPolling.resetForTest()
    }

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            isGuest = false,
        )

    private fun recording(
        id: String = "rec-1",
        status: ConferenceRecordingStatus = ConferenceRecordingStatus.FAILED,
        code: ConferenceRecordingFailureCode? = ConferenceRecordingFailureCode.COMPOSE_FAILED,
        canRetry: Boolean = true,
        rawFiles: Boolean? = true,
        stoppedAt: LocalDateTime? = LocalDateTime(2026, 10, 3, 11, 8),
        retryStartedAt: LocalDateTime? = null,
        title: String = "Vorstandssitzung",
    ) = ConferenceRecordingDto(
        id = id,
        roomId = "room-1",
        roomTitle = title,
        status = status,
        startedByMemberId = "member-1",
        startedByDisplayName = "Dana Keller",
        startedAt = LocalDateTime(2026, 10, 3, 10, 0),
        stoppedAt = stoppedAt,
        readyAt = null,
        durationSeconds = null,
        accessLevel = DocumentAccessLevel.BOARD_ONLY,
        documentId = null,
        mediaUrl = null,
        fileSizeBytes = null,
        trackCount = 2,
        failureReason = if (status == ConferenceRecordingStatus.FAILED) rawReason else null,
        failureCode = if (status == ConferenceRecordingStatus.FAILED) code else null,
        canRetryComposition = canRetry,
        rawFilesAvailable = rawFiles,
        compositionRetryStartedAt = retryStartedAt,
    )

    private fun page(vararg rows: ConferenceRecordingDto) =
        jsonOf(ConferenceRecordingPageDto.serializer(), ConferenceRecordingPageDto(rows.toList(), rows.size, 25, 0))

    private fun availability() =
        jsonOf(
            ConferenceRecordingAvailabilityDto.serializer(),
            ConferenceRecordingAvailabilityDto(enabled = true, ffmpegAvailable = true, maxDurationMinutes = 120),
        )

    private class Routes(
        val availability: String,
        val list: String,
        val retry: String,
    )

    private suspend fun routes() =
        Routes(
            availability = routeOf { rpcService<IConferenceRecordingService>().getRecordingAvailability() },
            list = routeOf { rpcService<IConferenceRecordingService>().listRecordings(ConferenceRecordingListQuery()) },
            retry = routeOf { rpcService<IConferenceRecordingService>().retryRecordingComposition("x") },
        )

    private fun HTMLElement.shows(text: String) = textContent.orEmpty().contains(text)

    private fun HTMLElement.rowButtons() = allOf("button").map { it.textContent.orEmpty().trim() }

    @Test
    fun retryButton_onlyWhenAllowed_leftOfDelete_with44pxTarget_andAnAccessibleName(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val rows =
                arrayOf(recording(id = "a", title = "Erste Sitzung"), recording(id = "b", canRetry = false, title = "Zweite Sitzung"))
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> request.answerWith(page(*rows))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("recording-retry-button") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("both rows are shown", timeoutMs) { element().shows("Erste Sitzung") && element().shows("Zweite Sitzung") }
                    val retryButtons = element().allOf("button").filter { it.textContent.orEmpty().trim() == "Umkodierung neu starten" }
                    assertEquals(1, retryButtons.size, "only the row with canRetryComposition offers the action")
                    val button = retryButtons.single()
                    val rect = button.getBoundingClientRect()
                    assertTrue(rect.height >= 44.0 && rect.width >= 44.0, "touch target at least 44 px, was ${rect.width} x ${rect.height}")
                    assertEquals("Umkodierung neu starten: Erste Sitzung", button.getAttribute("aria-label"))
                    val header = assertNotNull(button.parentElement as? HTMLElement, "the header row")
                    val names = header.allOf("button").map { it.textContent.orEmpty().trim() }
                    assertTrue(
                        names.indexOf("Umkodierung neu starten") in 0 until names.indexOf("Löschen"),
                        "the retry is left of Löschen: $names",
                    )
                    assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
                }
            }
        }

    @Test
    fun confirmDialog_namesTheMinutes_andWithoutTimesThereIsNoParenthesis(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            for ((label, stopped) in listOf("with" to LocalDateTime(2026, 10, 3, 11, 8), "without" to null)) {
                withFetchStub(
                    respond = { request ->
                        when {
                            !request.isRpc -> StubResponse()
                            request.rpcRoute == r.availability -> request.answerWith(availability())
                            request.rpcRoute == r.list -> request.answerWith(page(recording(stoppedAt = stopped)))
                            else -> request.answerWith("[]")
                        }
                    },
                ) { calls ->
                    mountedForm("recording-retry-dialog-$label") { root, element ->
                        renderConferenceRecordingsPanel(root.vPanel())
                        awaitUntil("[$label] the button is shown", timeoutMs) { element().rowButtons().contains("Umkodierung neu starten") }
                        element().buttonNamed("Umkodierung neu starten").click()
                        awaitUntil("[$label] the dialog is shown", timeoutMs) { document.querySelector(".modal.show") != null }
                        val text = lastOpenModal().textContent.orEmpty()
                        if (stopped != null) {
                            assertTrue(text.contains("34 Minuten"), "68 minutes of recording -> 34 minutes, was: $text")
                        } else {
                            assertFalse(text.contains("("), "no parenthesis without both timestamps: $text")
                            assertFalse(text.contains("Minuten"), "no minutes without both timestamps: $text")
                        }
                        assertEquals(0, calls.toRoute(r.retry).size, "[$label] nothing is sent before the confirmation")
                    }
                }
            }
        }

    @Test
    fun confirming_startsTheRetry_andTheProcessingRowShowsTheEstimateLine(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            var started = false
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list ->
                            request.answerWith(
                                page(
                                    if (started) {
                                        recording(
                                            status = ConferenceRecordingStatus.PROCESSING,
                                            canRetry = false,
                                            rawFiles = null,
                                            retryStartedAt = LocalDateTime(2026, 10, 11, 8, 0),
                                        )
                                    } else {
                                        recording()
                                    },
                                ),
                            )
                        request.rpcRoute == r.retry -> {
                            started = true
                            request.answerWith(
                                jsonOf(
                                    ConferenceRecordingDto.serializer(),
                                    recording(status = ConferenceRecordingStatus.PROCESSING, canRetry = false),
                                ),
                            )
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-confirm") { root, element ->
                    RecordingsPolling.isTabVisible = { false } // the poll is not under test here
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the button is shown", timeoutMs) { element().rowButtons().contains("Umkodierung neu starten") }
                    element().buttonNamed("Umkodierung neu starten").click()
                    awaitUntil("the dialog is shown", timeoutMs) { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Neu starten").click()
                    awaitUntil("the retry was sent", timeoutMs) { calls.toRoute(r.retry).size == 1 }
                    awaitUntil("the processing row with its estimate is shown", timeoutMs) {
                        element().shows("Voraussichtlich fertig gegen") && element().shows("Uhr (Schätzung)")
                    }
                    assertFalse(element().rowButtons().contains("Umkodierung neu starten"), "no second start while processing")
                    assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
                }
            }
        }

    @Test
    fun polling_runsWhileProcessing_thenStopsWhenTheRowSettles(): Promise<Unit> =
        formTest {
            listRequests = 0
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list ->
                            request.answerWith(
                                page(
                                    recording(
                                        status =
                                            if (request.isRpc && listCount(request) >= 3) {
                                                ConferenceRecordingStatus.READY
                                            } else {
                                                ConferenceRecordingStatus.PROCESSING
                                            },
                                        canRetry = false,
                                    ),
                                ),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-poll-stops") { root, element ->
                    RecordingsPolling.intervalMs = 60.0
                    RecordingsPolling.isTabVisible = { true }
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the poll loaded the list at least three times", timeoutMs) { calls.toRoute(r.list).size >= 3 }
                    awaitUntil("the settled row is shown", timeoutMs) { element().shows("Bereit") }
                    awaitAppScopeIdle("the last load finished")
                    val settled = calls.toRoute(r.list).size
                    delay(400)
                    assertEquals(settled, calls.toRoute(r.list).size, "no polling once nothing is PROCESSING")
                }
            }
        }

    @Test
    fun polling_pausesInAHiddenTab_andStopsAfterThePanelIsRemoved(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list ->
                            request.answerWith(page(recording(status = ConferenceRecordingStatus.PROCESSING, canRetry = false)))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-poll-hidden") { root, _ ->
                    RecordingsPolling.intervalMs = 60.0
                    RecordingsPolling.isTabVisible = { false }
                    val panel = root.vPanel()
                    renderConferenceRecordingsPanel(panel)
                    awaitUntil("the first load", timeoutMs) { calls.toRoute(r.list).size == 1 }
                    awaitAppScopeIdle("the first load finished")
                    delay(400)
                    assertEquals(1, calls.toRoute(r.list).size, "no request while the tab is hidden")

                    RecordingsPolling.isTabVisible = { true }
                    awaitUntil("polling resumes with the tab", timeoutMs) { calls.toRoute(r.list).size >= 2 }

                    root.remove(panel)
                    awaitAppScopeIdle("the running load finished")
                    val afterRemoval = calls.toRoute(r.list).size
                    delay(400)
                    assertEquals(afterRemoval, calls.toRoute(r.list).size, "no polling once the panel is gone")
                }
            }
        }

    @Test
    fun poll_withAnUnchangedPage_doesNotRerender_soTheCardAndItsFocusSurvive(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list ->
                            request.answerWith(page(recording(status = ConferenceRecordingStatus.PROCESSING, canRetry = false)))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-poll-no-rerender") { root, element ->
                    RecordingsPolling.intervalMs = 40.0
                    RecordingsPolling.isTabVisible = { true }
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the processing row is shown", timeoutMs) { element().shows("Vorstandssitzung") }
                    val card = assertNotNull(element().querySelector(".border.rounded") as? HTMLElement, "the row card")
                    awaitUntil("several silent polls ran", timeoutMs) { calls.toRoute(r.list).size >= 4 }
                    assertTrue(card.isConnected, "the very same card is still in the DOM after the polls")
                    assertTrue(element().contains(card), "the card was not replaced by a re-render")
                }
            }
        }

    @Test
    fun poll_failure_keepsTheList_andSchedulesTheNextTick(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            var listCalls = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> {
                            listCalls++
                            if (listCalls == 2) {
                                StubResponse(status = 500, text = "boom")
                            } else {
                                request.answerWith(page(recording(status = ConferenceRecordingStatus.PROCESSING, canRetry = false)))
                            }
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-poll-failure") { root, element ->
                    RecordingsPolling.intervalMs = 40.0
                    RecordingsPolling.isTabVisible = { true }
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("a third list request came after the failed second one", timeoutMs) { calls.toRoute(r.list).size >= 3 }
                    assertTrue(element().shows("Vorstandssitzung"), "the row stays visible after the failed poll")
                }
            }
        }

    @Test
    fun pollWindow_isResetAfterAProcessingPhase_soALaterRetryPollsAgain(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            var mode = ConferenceRecordingStatus.PROCESSING
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list ->
                            request.answerWith(page(recording(status = mode, canRetry = mode == ConferenceRecordingStatus.FAILED)))
                        request.rpcRoute == r.retry -> {
                            mode = ConferenceRecordingStatus.PROCESSING
                            request.answerWith(
                                jsonOf(
                                    ConferenceRecordingDto.serializer(),
                                    recording(status = ConferenceRecordingStatus.PROCESSING, canRetry = false),
                                ),
                            )
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-poll-window-reset") { root, element ->
                    var now = 0.0
                    RecordingsPolling.nowMs = { now }
                    RecordingsPolling.intervalMs = 40.0
                    RecordingsPolling.maxDurationMs = 1_000.0
                    RecordingsPolling.isTabVisible = { true }
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the first processing phase polls", timeoutMs) { calls.toRoute(r.list).size >= 2 }
                    now = 900.0 // still inside the first phase's window
                    mode = ConferenceRecordingStatus.FAILED
                    awaitUntil("the failed row is shown", timeoutMs) { element().rowButtons().contains("Umkodierung neu starten") }
                    awaitAppScopeIdle("the phase ended")
                    now = 5_000.0 // far beyond the cap measured from the first phase's start
                    element().buttonNamed("Umkodierung neu starten").click()
                    awaitUntil("the dialog is shown", timeoutMs) { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Neu starten").click()
                    awaitUntil("the retry was sent", timeoutMs) { calls.toRoute(r.retry).size == 1 }
                    awaitAppScopeIdle("the reload after the retry finished")
                    val afterRetry = calls.toRoute(r.list).size
                    awaitUntil("the second processing phase polls again", timeoutMs) { calls.toRoute(r.list).size > afterRetry }
                }
            }
        }

    @Test
    fun poll_duringANonSilentLoad_doesNotLeaveThePanelStuckOnLoading(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            var listCalls = 0
            val bigPage =
                jsonOf(
                    ConferenceRecordingPageDto.serializer(),
                    ConferenceRecordingPageDto(
                        listOf(recording(status = ConferenceRecordingStatus.PROCESSING, canRetry = false)),
                        30,
                        25,
                        0,
                    ),
                )
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> {
                            listCalls++
                            if (listCalls == 2) {
                                // The paging load stays in flight while the poll timer fires and supersedes it.
                                val answer = rpcResult(request.json.id as Int, bigPage)
                                StubResponse(status = answer.status, text = answer.text, delayMs = 400)
                            } else {
                                request.answerWith(bigPage)
                            }
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-retry-poll-vs-paging") { root, element ->
                    RecordingsPolling.intervalMs = 100.0
                    RecordingsPolling.isTabVisible = { true }
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the page is shown", timeoutMs) { element().rowButtons().contains("Weiter ›") }
                    element().buttonNamed("Weiter ›").click()
                    awaitUntil("a poll ran after the paging load", timeoutMs) { calls.toRoute(r.list).size >= 3 }
                    awaitAppScopeIdle("all loads finished")
                    awaitUntil("the list and the pager are back", timeoutMs) {
                        element().shows("Vorstandssitzung") && element().rowButtons().contains("Weiter ›")
                    }
                    assertFalse(element().shows("Wird geladen"), "the panel is not stuck on the loading hint")
                }
            }
        }

    @Test
    fun failureText_isFixedPerCode_neverTheServersReasonText(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val expected =
                mapOf(
                    ConferenceRecordingFailureCode.COMPOSE_FAILED to "konnte aber nicht zu einem Video zusammengesetzt werden",
                    ConferenceRecordingFailureCode.EGRESS_TIMEOUT to "nicht rechtzeitig abgeschlossen",
                    ConferenceRecordingFailureCode.NO_TRACKS to "keine Audio- oder Videospur aufgezeichnet",
                    ConferenceRecordingFailureCode.NO_TRACK_STORED to "keine Spur speichern",
                )
            val rows =
                expected.keys
                    .mapIndexed { i, code -> recording(id = "f$i", code = code, canRetry = false, rawFiles = false, title = "Raum $i") }
                    .toTypedArray() + recording(id = "legacy", code = null, canRetry = false, rawFiles = null, title = "Altbestand")
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> request.answerWith(page(*rows))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("recording-retry-failure-texts") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("all rows are shown", timeoutMs) { element().shows("Altbestand") }
                    val text = element().textContent.orEmpty()
                    expected.values.forEach { assertTrue(text.contains(it), "missing '$it' in: $text") }
                    assertTrue(text.contains("Die Rohaufnahmen sind nicht mehr vorhanden."), "raw files gone is stated")
                    assertTrue(text.contains("Wenden Sie sich an die Administration."))
                    assertTrue(text.contains("Die Aufzeichnung ist fehlgeschlagen."), "a row without a code")
                    assertFalse(text.contains(rawReason), "the server's failureReason text is never rendered")
                    assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
                }
            }
        }

    private var listRequests = 0

    /** Counts list requests for the poll test's scripted answer (the Nth answer settles). */
    private fun listCount(
        @Suppress("UNUSED_PARAMETER") request: RecordedRequest,
    ): Int = ++listRequests
}
