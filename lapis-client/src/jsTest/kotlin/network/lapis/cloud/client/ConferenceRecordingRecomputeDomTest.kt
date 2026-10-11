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
 * V1.9.109 -- "Neu berechnen" in the recordings panel of the conference lobby: only for a READY row the caller may recompute (left of
 * "Löschen", 44 px target, accessible name with the room title), DISABLED with a visible reason tied to the button when the raw tracks
 * are gone, one action per row (READY: recompute, FAILED: only the retry), the confirmation (minutes, the old video stays), the running
 * recompute ("Wird neu berechnet …" with the download still offered), the failed one, the typed refusals and the poll while a
 * recompute runs. Reuses the seams of [ConferenceRecordingRetryDomTest] (`RecordingsPolling`, the fetch stub).
 */
class ConferenceRecordingRecomputeDomTest {
    private val timeoutMs = 15_000

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

    private fun ready(
        id: String = "rec-1",
        title: String = "Vorstandssitzung",
        canRecompute: Boolean = true,
        rawFiles: Boolean? = true,
        inProgress: Boolean = false,
        lastFailed: Boolean = false,
    ) = ConferenceRecordingDto(
        id = id,
        roomId = "room-1",
        roomTitle = title,
        status = ConferenceRecordingStatus.READY,
        startedByMemberId = "member-1",
        startedByDisplayName = "Dana Keller",
        startedAt = LocalDateTime(2026, 10, 3, 10, 0),
        stoppedAt = LocalDateTime(2026, 10, 3, 11, 8),
        readyAt = LocalDateTime(2026, 10, 3, 11, 20),
        durationSeconds = 4080,
        accessLevel = DocumentAccessLevel.BOARD_ONLY,
        documentId = "doc-1",
        mediaUrl = "/api/conference/recordings/$id/media?v=version-1",
        fileSizeBytes = 123_456,
        trackCount = 2,
        failureReason = null,
        failureCode = null,
        canRetryComposition = false,
        rawFilesAvailable = rawFiles,
        compositionRetryStartedAt = null,
        canRecompute = canRecompute,
        recomputeInProgress = inProgress,
        lastRecomputeFailed = lastFailed,
    )

    private fun failed(id: String = "rec-f") =
        ready(id = id, canRecompute = false, rawFiles = true).copy(
            status = ConferenceRecordingStatus.FAILED,
            documentId = null,
            mediaUrl = null,
            readyAt = null,
            durationSeconds = null,
            fileSizeBytes = null,
            failureReason = "RAW",
            failureCode = ConferenceRecordingFailureCode.COMPOSE_FAILED,
            canRetryComposition = true,
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
        val recompute: String,
    )

    private suspend fun routes() =
        Routes(
            availability = routeOf { rpcService<IConferenceRecordingService>().getRecordingAvailability() },
            list = routeOf { rpcService<IConferenceRecordingService>().listRecordings(ConferenceRecordingListQuery()) },
            recompute = routeOf { rpcService<IConferenceRecordingService>().recomputeRecording("x") },
        )

    private fun HTMLElement.shows(text: String) = textContent.orEmpty().contains(text)

    private fun HTMLElement.rowButtons() = allOf("button").map { it.textContent.orEmpty().trim() }

    @Test
    fun recomputeButton_forAnAllowedReadyRow_isLeftOfDelete_44px_andNamesTheRoom(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val rows =
                arrayOf(
                    ready(id = "a", title = "Erste Sitzung"),
                    ready(id = "b", title = "Zweite Sitzung", canRecompute = false, rawFiles = null),
                )
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
                mountedForm("recording-recompute-button") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("both rows are shown", timeoutMs) { element().shows("Erste Sitzung") && element().shows("Zweite Sitzung") }
                    val buttons = element().allOf("button").filter { it.textContent.orEmpty().trim() == "Neu berechnen" }
                    assertEquals(
                        1,
                        buttons.size,
                        "only the row the caller may recompute offers it (the other has no file system state at all)",
                    )
                    val button = buttons.single()
                    assertFalse(button.hasAttribute("disabled"), "enabled")
                    val rect = button.getBoundingClientRect()
                    assertTrue(rect.height >= 44.0 && rect.width >= 44.0, "touch target at least 44 px, was ${rect.width} x ${rect.height}")
                    assertEquals("Neu berechnen: Erste Sitzung", button.getAttribute("aria-label"))
                    val header = assertNotNull(button.parentElement as? HTMLElement, "the header row")
                    val names = header.allOf("button").map { it.textContent.orEmpty().trim() }
                    assertTrue(names.indexOf("Neu berechnen") in 0 until names.indexOf("Löschen"), "left of Löschen: $names")
                    assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
                }
            }
        }

    @Test
    fun withoutRawTracks_theButtonIsDisabled_andTheReasonIsVisibleAndTiedToIt(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> request.answerWith(page(ready(canRecompute = false, rawFiles = false)))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-recompute-disabled") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the row is shown", timeoutMs) { element().shows("Vorstandssitzung") }
                    val button = element().buttonNamed("Neu berechnen")
                    assertTrue(button.hasAttribute("disabled"), "disabled")
                    val hintId = assertNotNull(button.getAttribute("aria-describedby"), "aria-describedby")
                    val hint = assertNotNull(document.getElementById(hintId) as? HTMLElement, "the described element exists")
                    assertEquals("Die Rohspuren sind nicht mehr vorhanden.", hint.textContent.orEmpty().trim())
                    assertEquals("Die Rohspuren sind nicht mehr vorhanden.", button.getAttribute("title"))
                    button.click()
                    delay(150)
                    assertEquals(0, calls.toRoute(r.recompute).size, "a disabled button sends nothing")
                    assertTrue(document.querySelector(".modal.show") == null, "and opens no dialog")
                }
            }
        }

    @Test
    fun oneActionPerRow_readyRecomputes_failedOnlyRetries(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list ->
                            request.answerWith(
                                page(ready(id = "a", title = "Fertige Sitzung"), failed("f").copy(roomTitle = "Kaputte Sitzung")),
                            )
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("recording-recompute-one-action") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil(
                        "both rows are shown",
                        timeoutMs,
                    ) { element().shows("Fertige Sitzung") && element().shows("Kaputte Sitzung") }
                    val all = element().rowButtons()
                    assertEquals(1, all.count { it == "Neu berechnen" }, "recompute: $all")
                    assertEquals(1, all.count { it == "Umkodierung neu starten" }, "retry: $all")
                    val recomputeRow = element().buttonNamed("Neu berechnen").parentElement as HTMLElement
                    assertFalse(recomputeRow.shows("Umkodierung neu starten"), "the READY row has no retry")
                    val retryRow = element().buttonNamed("Umkodierung neu starten").parentElement as HTMLElement
                    assertFalse(retryRow.shows("Neu berechnen"), "the FAILED row has no recompute")
                }
            }
        }

    @Test
    fun confirmDialog_statesTheMinutesAndThatTheOldVideoStays_andNothingIsSentBeforeTheConfirmation(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> request.answerWith(page(ready()))
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-recompute-dialog") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the button is shown", timeoutMs) { element().rowButtons().contains("Neu berechnen") }
                    element().buttonNamed("Neu berechnen").click()
                    awaitUntil("the dialog is shown", timeoutMs) { document.querySelector(".modal.show") != null }
                    val text = lastOpenModal().textContent.orEmpty()
                    assertTrue(text.contains("Vorstandssitzung"), "names the recording: $text")
                    assertTrue(text.contains("34 Minuten"), "68 minutes of recording -> 34 minutes: $text")
                    assertTrue(text.contains("bleibt bis dahin abrufbar"), "the old video stays: $text")
                    assertTrue(text.contains("frühere Version"), "the previous version is kept: $text")
                    assertEquals(0, calls.toRoute(r.recompute).size, "nothing is sent before the confirmation")
                }
            }
        }

    @Test
    fun confirming_startsTheRecompute_theRowShowsItRunning_theDownloadStays_andThePanelPollsUntilItEnds(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            var started = false
            var polls = 0
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> {
                            if (started) polls++
                            request.answerWith(page(if (started && polls < 3) ready(canRecompute = false, inProgress = true) else ready()))
                        }
                        request.rpcRoute == r.recompute -> {
                            started = true
                            request.answerWith(jsonOf(ConferenceRecordingDto.serializer(), ready(canRecompute = false, inProgress = true)))
                        }
                        else -> request.answerWith("[]")
                    }
                },
            ) { calls ->
                mountedForm("recording-recompute-running") { root, element ->
                    RecordingsPolling.intervalMs = 60.0
                    RecordingsPolling.isTabVisible = { true }
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the button is shown", timeoutMs) { element().rowButtons().contains("Neu berechnen") }
                    element().buttonNamed("Neu berechnen").click()
                    awaitUntil("the dialog is shown", timeoutMs) { document.querySelector(".modal.show") != null }
                    lastOpenModal().buttonNamed("Neu starten").click()
                    awaitUntil("the recompute was sent", timeoutMs) { calls.toRoute(r.recompute).size == 1 }
                    awaitUntil("the running recompute is shown", timeoutMs) { element().shows("Wird neu berechnet") }
                    assertFalse(element().rowButtons().contains("Neu berechnen"), "no second start while it runs")
                    assertTrue(
                        element().allOf("a").any { it.getAttribute("href").orEmpty().contains("/media?v=version-1") },
                        "the download stays available",
                    )
                    assertTrue(element().querySelector("video") != null || element().allOf("a").isNotEmpty(), "the old file stays playable")
                    // the poll goes on while it runs and stops when the row settles
                    awaitUntil("the poll saw the end of the recompute", timeoutMs) { polls >= 3 && !element().shows("Wird neu berechnet") }
                    assertTrue(element().rowButtons().contains("Neu berechnen"), "the button is back")
                    awaitAppScopeIdle("the last load finished")
                    val settled = calls.toRoute(r.list).size
                    delay(400)
                    assertEquals(settled, calls.toRoute(r.list).size, "no polling once nothing runs")
                    assertFalse(element().innerHTML.contains("###KvI18nS###"), "no i18n marker leaked into the DOM")
                }
            }
        }

    @Test
    fun aFailedRecompute_isShownAsText_andTheOldFileIsStillThere(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            withFetchStub(
                respond = { request ->
                    when {
                        !request.isRpc -> StubResponse()
                        request.rpcRoute == r.availability -> request.answerWith(availability())
                        request.rpcRoute == r.list -> request.answerWith(page(ready(lastFailed = true)))
                        else -> request.answerWith("[]")
                    }
                },
            ) { _ ->
                mountedForm("recording-recompute-failed") { root, element ->
                    renderConferenceRecordingsPanel(root.vPanel())
                    awaitUntil("the failure hint is shown", timeoutMs) { element().shows("Neuberechnung fehlgeschlagen") }
                    assertTrue(element().shows("die bisherige Datei ist unverändert"))
                    assertTrue(element().rowButtons().contains("Neu berechnen"), "it can be tried again")
                    assertTrue(
                        element().allOf("a").any { it.getAttribute("href").orEmpty().contains("/media") },
                        "the old file is still offered",
                    )
                }
            }
        }

    @Test
    fun everyTypedRefusal_reloadsTheList_andTheButtonComesBack(): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val r = routes()
            val faults =
                listOf(
                    "network.lapis.cloud.shared.rpc.RecordingRecomputeInProgressException",
                    "network.lapis.cloud.shared.rpc.RecordingRecomputeRateLimitedException",
                    "network.lapis.cloud.shared.rpc.RecordingRecomputeInsufficientDiskException",
                    "network.lapis.cloud.shared.rpc.RecordingRecomputeNotAllowedException",
                    "network.lapis.cloud.shared.rpc.RecordingRawFilesMissingException",
                )
            for (fqcn in faults) {
                withFetchStub(
                    respond = { request ->
                        when {
                            !request.isRpc -> StubResponse()
                            request.rpcRoute == r.availability -> request.answerWith(availability())
                            request.rpcRoute == r.list -> request.answerWith(page(ready()))
                            request.rpcRoute == r.recompute -> serviceExceptionResult(request.json.id as Int, fqcn)
                            else -> request.answerWith("[]")
                        }
                    },
                ) { calls ->
                    mountedForm("recording-recompute-fault-${fqcn.substringAfterLast('.')}") { root, element ->
                        renderConferenceRecordingsPanel(root.vPanel())
                        awaitUntil("[$fqcn] the button is shown", timeoutMs) { element().rowButtons().contains("Neu berechnen") }
                        awaitAppScopeIdle("[$fqcn] the first load finished")
                        val before = calls.toRoute(r.list).size
                        element().buttonNamed("Neu berechnen").click()
                        awaitUntil("[$fqcn] the dialog is shown", timeoutMs) { document.querySelector(".modal.show") != null }
                        lastOpenModal().buttonNamed("Neu starten").click()
                        awaitUntil("[$fqcn] the recompute was sent", timeoutMs) { calls.toRoute(r.recompute).size == 1 }
                        awaitUntil("[$fqcn] the list was reloaded", timeoutMs) { calls.toRoute(r.list).size > before }
                        awaitAppScopeIdle("[$fqcn] the reload finished")
                        assertFalse(element().buttonNamed("Neu berechnen").hasAttribute("disabled"), "[$fqcn] the button is usable again")
                    }
                }
            }
        }
}
