package network.lapis.cloud.server.db

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.StagingSeedLtr.applyTopUp
import network.lapis.cloud.server.db.StagingSeedTopUpGuard.instanceCheck
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

internal sealed interface StagingTopUpOutcome {
    data class Skipped(
        val reason: String,
    ) : StagingTopUpOutcome

    data class Applied(
        val result: StagingSeedLtr.Result,
    ) : StagingTopUpOutcome
}

/**
 * Additive LTR balances for the fictitious test members (V1.9.197), for instances that were seeded before (staging, demo)
 * and so cannot be seeded again because the `member` table is not empty.
 *
 * Called ONLY from `main()` in `Application.kt` right after [StagingSeedData.seedIfEmpty], never from `module()`.
 * Two independent guards, both required: `LAPIS_STAGING_MODE=true` with a valid seed password ([StagingSeedTopUpGuard.envGate])
 * and the instance check inside the transaction ([StagingSeedTopUpGuard.instanceCheck], the first statement, before any write).
 * Every instance that passes both is fictitious by construction -- this includes the demo instance, which runs the same seed.
 *
 * One transaction, no external effect, idempotent (fixed ids; safe under Exposed's retry). [synchronized] is enough: the
 * seed runs once per process at start-up, and a second process would only find the rows already there.
 */
internal object StagingSeedTopUp {
    fun topUpIfEligible(
        env: (String) -> String? = System::getenv,
        database: Database? = null,
    ): StagingTopUpOutcome {
        if (StagingSeedTopUpGuard.envGate(env) != null) return StagingTopUpOutcome.Skipped(reason = "staging mode is not enabled")
        // The test data is optional: a failure is logged and rolled back (nothing half-written), the server keeps starting.
        return runCatching { topUpWith(database) }
            .getOrElse { failure ->
                logger.error(failure) { "Staging-LTR-Aufstockung fehlgeschlagen -- Transaktion zurueckgerollt, Start wird fortgesetzt." }
                StagingTopUpOutcome.Skipped(reason = "failed")
            }
    }

    /** Test entry WITHOUT the env gate but WITH the instance check (never bypassed). Propagates exceptions. */
    internal fun topUpWith(database: Database?): StagingTopUpOutcome =
        synchronized(this) {
            transaction(database) {
                when (val decision = instanceCheck()) {
                    is StagingTopUpDecision.Eligible -> {
                        val clock = SeedClock.capture(orgZone = with(StagingSeedData) { readOrganizationZone() })
                        val result = applyTopUp(clock)
                        logger.info {
                            "Staging-LTR-Aufstockung: ${result.mintsInserted} Gutschriften, ${result.transfersInserted} Uebertragungen, " +
                                "${result.skippedIneligible} uebersprungen."
                        }
                        StagingTopUpOutcome.Applied(result = result)
                    }
                    is StagingTopUpDecision.NotStagingInstance -> {
                        logger.info { "Staging-LTR-Aufstockung uebersprungen: ${decision.reason}." }
                        StagingTopUpOutcome.Skipped(reason = decision.reason)
                    }
                    is StagingTopUpDecision.EnvDisabled -> StagingTopUpOutcome.Skipped(reason = "env disabled")
                }
            }
        }
}
