package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ArticleDraftInput
import network.lapis.cloud.shared.domain.ArticleDto
import network.lapis.cloud.shared.domain.ArticleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- covers [ArticleAutoSaveController].
 * Same `Dispatchers.Unconfined` + `CompletableDeferred` idiom [DataLoadControllerTest] establishes
 * for deterministic, event-loop-free coroutine tests. The injected [ArticleAutoSaveSchedule] fires
 * its action SYNCHRONOUSLY (no real timer) -- this suite tests the state machine's own
 * serialization/coalescing discipline, not real debounce timing.
 */
class ArticleAutoSaveControllerTest {
    private fun dto(
        id: String,
        updatedAt: LocalDateTime = LocalDateTime(2026, 9, 28, 10, 0),
    ) = ArticleDto(
        id = id,
        slug = null,
        title = "t",
        excerpt = "e",
        body = "b",
        coverImageUrl = null,
        status = ArticleStatus.DRAFT,
        submittedAt = null,
        reviewedAt = null,
        rejectionReason = null,
        publishedAt = null,
        updatedAt = updatedAt,
    )

    private class ImmediateSchedule : ArticleAutoSaveSchedule {
        override fun schedule(
            delayMs: Int,
            action: () -> Unit,
        ): ArticleAutoSaveSchedule.Handle {
            action()
            return ArticleAutoSaveSchedule.Handle { }
        }
    }

    private class Harness(
        val gates: ArrayDeque<CompletableDeferred<ArticleDto>>,
    ) {
        val saveCalls = mutableListOf<Pair<String?, ArticleDraftInput>>()
        val states = mutableListOf<ArticleAutoSaveController.SaveState>()
        val controller =
            ArticleAutoSaveController(
                scope = CoroutineScope(Dispatchers.Unconfined),
                save = { id, input ->
                    saveCalls += id to input
                    gates.removeFirst().await()
                },
                schedule = ImmediateSchedule(),
                onStateChange = { states += it },
            )
    }

    private fun input(body: String) = ArticleDraftInput(title = "Titel", excerpt = "Auszug", body = body)

    @Test
    fun twoRapidChangesWhileTheFirstSaveIsInFlight_yieldExactlyOneFollowUpSaveWithTheLatestInput() {
        val firstGate = CompletableDeferred<ArticleDto>()
        val secondGate = CompletableDeferred<ArticleDto>()
        val harness = Harness(gates = ArrayDeque(listOf(firstGate, secondGate)))

        harness.controller.onChange(input("v1")) // fires save(id=null, v1) immediately (Unconfined + synchronous schedule)
        assertEquals(1, harness.saveCalls.size)
        assertEquals(null to input("v1"), harness.saveCalls[0])

        harness.controller.onChange(input("v2")) // in flight -- captured, no new save call
        harness.controller.onChange(input("v3")) // in flight -- captured, no new save call
        assertEquals(1, harness.saveCalls.size)

        firstGate.complete(dto(id = "X")) // completes synchronously under Unconfined
        // Exactly one immediate follow-up, with the FRESHEST input (v3), now that an id exists.
        assertEquals(2, harness.saveCalls.size)
        assertEquals("X" to input("v3"), harness.saveCalls[1])

        secondGate.complete(dto(id = "X"))
        assertEquals(2, harness.saveCalls.size) // no further, unrequested save.
    }

    @Test
    fun emptyTitleAndBody_neverTriggersASave_whileNoArticleIdExistsYet() {
        val harness = Harness(gates = ArrayDeque())
        harness.controller.onChange(ArticleDraftInput(title = "", excerpt = "", body = ""))
        assertTrue(harness.saveCalls.isEmpty())
    }

    @Test
    fun onChangeWithBlankInput_thenFlushNowAndAwait_neverCreatesAnEmptyDraft() {
        // Regression test (Round-2 review finding 1, MAJOR): KVision's `subscribe` invokes its
        // observer IMMEDIATELY with the field's current (blank) value when a brand-new editor
        // wires up `titleField.subscribe { controller.onChange(...) } }` -- so `onChange` runs
        // with blank input, and its OWN "no article yet, both blank" guard only skips ARMING the
        // debounce timer; it still sets `dirty = true`/`pendingInput`. Before this fix, a
        // `flushNowAndAwait()` right after (e.g. the "Zurück"-button closing a still-empty
        // editor) called `attemptSave()` directly, bypassing that guard, and created an empty
        // draft row. `attemptSave` must repeat the same blank check.
        val harness = Harness(gates = ArrayDeque())
        harness.controller.onChange(ArticleDraftInput(title = "", excerpt = "", body = ""))

        var completed = false
        CoroutineScope(Dispatchers.Unconfined).launch {
            harness.controller.flushNowAndAwait()
            completed = true
        }
        assertTrue(completed)
        assertTrue(harness.saveCalls.isEmpty())
    }

    @Test
    fun flushNow_savesImmediately() {
        val gate = CompletableDeferred<ArticleDto>()
        val harness = Harness(gates = ArrayDeque(listOf(gate)))
        harness.controller.flushNow() // no pending input yet -- must be a no-op
        assertTrue(harness.saveCalls.isEmpty())

        // Arm a pending change WITHOUT letting the (synchronous, in this harness) schedule fire a
        // save -- flushNow's own point is to bypass the debounce timer, which this harness's
        // ImmediateSchedule already fires eagerly. Simulate a still-pending edit directly instead.
        harness.controller.onChange(input("flushed"))
        assertEquals(1, harness.saveCalls.size) // ImmediateSchedule already fired it -- flushNow would be a no-op here
        gate.complete(dto(id = "Y"))
        assertIs<ArticleAutoSaveController.SaveState.Saved>(harness.controller.saveState)
    }

    @Test
    fun flushNowAndAwait_isANoOp_whenNothingIsPendingOrInFlight() {
        val harness = Harness(gates = ArrayDeque())
        // Same "Unconfined resumes synchronously" idiom the rest of this suite relies on -- no
        // event loop/`runBlocking` needed (unavailable on the `jsTest` target anyway): with
        // nothing pending, `flushNowAndAwait` never suspends, so this `launch` body runs to
        // completion synchronously, before `launch` itself returns.
        var completed = false
        CoroutineScope(Dispatchers.Unconfined).launch {
            harness.controller.flushNowAndAwait()
            completed = true
        }
        assertTrue(completed)
        assertTrue(harness.saveCalls.isEmpty()) // no `gates.removeFirst()` needed -- `save` was never called.
    }

    @Test
    fun flushNowAndAwait_suspendsUntilTheChainSettles_includingTheImmediateFollowUp() {
        val firstGate = CompletableDeferred<ArticleDto>()
        val secondGate = CompletableDeferred<ArticleDto>()
        val harness = Harness(gates = ArrayDeque(listOf(firstGate, secondGate)))

        // ImmediateSchedule fires synchronously, so the first save is already in flight by the time
        // `onChange` returns; a second change while it's running is captured, not started.
        harness.controller.onChange(input("v1"))
        harness.controller.onChange(input("v2"))
        assertEquals(1, harness.saveCalls.size)

        var completed = false
        CoroutineScope(Dispatchers.Unconfined).launch {
            harness.controller.flushNowAndAwait()
            completed = true
        }
        // The awaiter suspends on the first gate -- neither save has resolved yet.
        assertTrue(!completed)

        firstGate.complete(dto(id = "X")) // resolves under Unconfined, immediately chains the v2 follow-up.
        assertEquals(2, harness.saveCalls.size)
        assertTrue(!completed) // the chain is not done yet -- the follow-up is still in flight.

        secondGate.complete(dto(id = "X"))
        assertTrue(completed) // NOW the whole chain (including the chained follow-up) has settled.
    }

    @Test
    fun afterAFailure_stateIsFailed_andRetrySendsTheSameInputAgain() {
        val failing = CompletableDeferred<ArticleDto>()
        val retryGate = CompletableDeferred<ArticleDto>()
        val harness = Harness(gates = ArrayDeque(listOf(failing, retryGate)))

        harness.controller.onChange(input("will-fail"))
        assertEquals(1, harness.saveCalls.size)
        failing.completeExceptionally(RuntimeException("network down"))
        assertIs<ArticleAutoSaveController.SaveState.Failed>(harness.controller.saveState)

        harness.controller.retry()
        assertEquals(2, harness.saveCalls.size)
        assertEquals(null to input("will-fail"), harness.saveCalls[1])
        retryGate.complete(dto(id = "Z"))
        assertIs<ArticleAutoSaveController.SaveState.Saved>(harness.controller.saveState)
    }
}
