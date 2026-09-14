package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/**
 * Review fix (MEDIUM, round-2 finding: test coverage) -- pins the rotating-future mechanics
 * [Hbci4jFinTsClient.submitTan]/[PendingSetup.currentRoundOutcome] rely on for a SECOND (and
 * later) TAN round. Before this test, NOTHING in this codebase exercised that a second round
 * completes ITS OWN, freshly-installed future rather than the FIRST round's already-finished one --
 * deterministically pinned via the return-value assertions below (`round2Outcome` is
 * [FinTsSetupOutcome.Verified], never the stale [FinTsSetupOutcome.TanRequested] round 1 already
 * received): "would `submitTan` not rotate the future AT ALL" is closed by this test with no
 * timing dependency, because a non-rotating implementation would hand round 2 the ALREADY-COMPLETED
 * round-1 future and [Hbci4jFinTsClient.awaitRound] would return round 1's outcome immediately.
 *
 * **Correction (Review fix, Runde 4) -- narrower than this class's own KDoc previously claimed.**
 * This test does NOT deterministically catch a regression that merely REORDERS `submitTan`'s two
 * lines (pulls `pending.submissions.offer(...)` ahead of `pending.currentRoundOutcome.set(...)`).
 * The identity check below (`round2Future === round1Future` must be `false`) is a
 * best-effort strengthening -- it fails immediately, instead of only after the 60s
 * `dialogTimeoutSeconds` wait, WHENEVER the stub worker thread happens to observe
 * [PendingSetup.currentRoundOutcome] before the (hypothetically reordered) production code has
 * swapped it -- but the stub worker's `submissions.take()` unblocking and the main thread's own
 * `currentRoundOutcome.set()` call are only a few bytecode instructions apart with no synchronization
 * between them; on a lightly loaded CI machine the main thread finishes that `set()` before the OS
 * schedules the worker thread back in far more often than not, REGARDLESS of which line comes
 * first in `submitTan`'s source. A reordering regression could therefore still pass this test
 * silently on any given run -- catching it deterministically would need a test seam inside
 * `submitTan` itself (e.g. an injectable hook between the two lines), which does not exist and is
 * out of scope for this fix. What this test DOES close deterministically is documented above.
 *
 * [Hbci4jFinTsClient.begin]/[Hbci4jFinTsClient.submitTan] cannot be driven through a real
 * multi-round hbci4j dialog in a unit test (this codebase has no fake FinTS bank server) -- same
 * reasoning [Hbci4jFinTsClientHandleSweepTest] already documents. Instead, a stub worker thread
 * mirrors [Hbci4jFinTsClient.runSetupDialogOnDedicatedThread]'s own loop shape (`submissions.take()`
 * then `currentRoundOutcome.get().complete(...)`, repeated) WITHOUT any hbci4j involvement -- the
 * production [Hbci4jFinTsClient.submitTan] itself, and the exact [PendingSetup] fields it touches,
 * are real, not reimplemented, reached through the same `internal` test seam
 * ([Hbci4jFinTsClient.putHandleForTesting]/[Hbci4jFinTsClient.handleCountForTesting]).
 */
class Hbci4jFinTsClientMultiRoundTest :
    FunSpec({
        fun newClient() = Hbci4jFinTsClient(config = FinTsConfig.load { null })

        fun newPendingSetup(): PendingSetup {
            val executor = Executors.newSingleThreadExecutor()
            return PendingSetup(executor = executor, submissions = LinkedBlockingQueue(1), createdAt = Instant.now())
        }

        fun someCredentials() =
            FinTsCredentials(
                bankAccountId = Uuid.random(),
                blz = "10000000",
                url = "https://example.invalid",
                userId = "user",
                pin = "pin",
                iban = "DE00000000000000000000",
            )

        test(
            "a SECOND submitTan round completes its OWN, freshly-installed round future -- not " +
                "the already-finished FIRST round's -- reproducing the exact race submitTan's MAJOR " +
                "fix (swap currentRoundOutcome BEFORE offering the submission) closes",
        ) {
            val client = newClient()
            val pending = newPendingSetup()
            val handle = "h-multi-round"
            client.putHandleForTesting(handle = handle, pending = pending)

            // Stub worker: mirrors runSetupDialogOnDedicatedThread's own loop (take() -> complete
            // whichever `currentRoundOutcome` is CURRENT -> take() again). Round 1 needs a SECOND
            // TAN (e.g. a bank asking NEED_PT_TANMEDIA then NEED_PT_TAN); round 2 finishes the
            // dialog with Verified.
            val worker =
                thread(start = true, name = "test-worker-$handle") {
                    val round1 = pending.submissions.take() as TanSubmission.Submit
                    round1.tan shouldBe "tan-round-1"
                    val round1Future = pending.currentRoundOutcome.get()
                    round1Future.complete(
                        FinTsSetupOutcome.TanRequested(
                            handle = handle,
                            bankPrompt = "Bitte zweite TAN eingeben.",
                            expiresAt = LocalDateTime(2100, 1, 1, 0, 0),
                        ),
                    )

                    val round2 = pending.submissions.take() as TanSubmission.Submit
                    round2.tan shouldBe "tan-round-2"
                    // Best-effort strengthening (Review fix, MINOR, Runde 4) -- see the class KDoc
                    // "Correction" paragraph for why this does NOT deterministically catch a
                    // regression that reorders submitTan's set()/offer() lines: it only fails fast
                    // (instead of only after the 60s dialogTimeoutSeconds wait) on runs where this
                    // read happens to lose that race.
                    val round2Future = pending.currentRoundOutcome.get()
                    (round2Future === round1Future) shouldBe false
                    round2Future.isDone shouldBe false
                    round2Future.complete(
                        FinTsSetupOutcome.Verified(credentials = someCredentials(), statementCount = 0),
                    )
                }

            try {
                val round1Outcome = client.submitTan(handle = handle, tan = "tan-round-1")
                round1Outcome.shouldBeInstanceOf<FinTsSetupOutcome.TanRequested>()
                round1Outcome.handle shouldBe handle
                // TanRequested is NOT terminal -- another round is expected, the handle must stay alive.
                client.handleCountForTesting() shouldBe 1

                val round2Outcome = client.submitTan(handle = handle, tan = "tan-round-2")
                round2Outcome.shouldBeInstanceOf<FinTsSetupOutcome.Verified>()
                // Verified IS terminal -- the handle is cleaned up.
                client.handleCountForTesting() shouldBe 0
            } finally {
                worker.join(TimeUnit.SECONDS.toMillis(5))
                pending.executor.shutdownNow()
            }
        }
    })
