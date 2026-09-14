package network.lapis.cloud.server.payment.fints

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Review fix (MEDIUM, test coverage) -- pins [Hbci4jFinTsClient.sweepExpiredHandles] against the
 * exact regression the MAJOR follow-up fix closed: before it, the sweeper was keyed on
 * [PendingSetup.createdAt] alone, so a multi-round dialog (e.g. `NEED_PT_TANMEDIA` THEN
 * `NEED_PT_TAN`) could get swept out from under an ADMIN who was still acting well within the
 * deadline the SECOND round had just promised them (`now + HANDLE_TTL` computed at round-start
 * time, not dialog-start time) -- see [PendingSetup.lastActivityAt] KDoc for the full scenario.
 *
 * [Hbci4jFinTsClient.begin]/[Hbci4jFinTsClient.submitTan] cannot be driven through a real
 * multi-round hbci4j dialog in a unit test (this codebase has no fake FinTS bank server), so this
 * test exercises the REAL [Hbci4jFinTsClient.sweepExpiredHandles] function directly against a
 * synthetic [PendingSetup] via the `internal` test seam ([Hbci4jFinTsClient.putHandleForTesting]/
 * [Hbci4jFinTsClient.handleCountForTesting]) -- production code, not a reimplementation of its
 * cutoff logic in the test.
 */
class Hbci4jFinTsClientHandleSweepTest :
    FunSpec({
        fun newClient() = Hbci4jFinTsClient(config = FinTsConfig.load { null })

        fun newPendingSetup(createdAt: Instant): PendingSetup {
            val executor = Executors.newSingleThreadExecutor()
            return PendingSetup(executor = executor, submissions = LinkedBlockingQueue(), createdAt = createdAt)
        }

        // HANDLE_TTL is 300s (private top-level val, mirrored here as the same wall-clock margin
        // every case below needs -- comfortably past/within it, never close to the boundary).
        val ttlSeconds = 300L

        test(
            "a handle whose createdAt is long past HANDLE_TTL SURVIVES the sweep when lastActivityAt " +
                "was touched recently (round 2's just-promised deadline is honoured, not createdAt's)",
        ) {
            val client = newClient()
            val pending = newPendingSetup(createdAt = Instant.now().minusSeconds(ttlSeconds + 100))
            pending.lastActivityAt.set(Instant.now())
            try {
                client.putHandleForTesting(handle = "h1", pending = pending)

                client.sweepExpiredHandles()

                client.handleCountForTesting() shouldBe 1
            } finally {
                pending.executor.shutdownNow()
            }
        }

        test(
            "a handle with NO activity since HANDLE_TTL ago (createdAt == lastActivityAt, both old) " +
                "is correctly swept",
        ) {
            val client = newClient()
            val old = Instant.now().minusSeconds(ttlSeconds + 100)
            val pending = newPendingSetup(createdAt = old)
            // lastActivityAt defaults to createdAt -- no round/submitTan ever touched it, matching
            // an ADMIN who opened the dialog and then genuinely walked away.
            client.putHandleForTesting(handle = "h1", pending = pending)

            client.sweepExpiredHandles()

            client.handleCountForTesting() shouldBe 0
        }

        test("a freshly created handle (createdAt just now) survives the sweep") {
            val client = newClient()
            val pending = newPendingSetup(createdAt = Instant.now())
            try {
                client.putHandleForTesting(handle = "h1", pending = pending)

                client.sweepExpiredHandles()

                client.handleCountForTesting() shouldBe 1
            } finally {
                pending.executor.shutdownNow()
            }
        }
    })
