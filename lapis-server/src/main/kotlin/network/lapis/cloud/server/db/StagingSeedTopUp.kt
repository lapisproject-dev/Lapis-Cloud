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
        val parts: List<StagingSeedPartReport> = emptyList(),
    ) : StagingTopUpOutcome
}

/**
 * Additive test data for the fictitious test members, for instances that were seeded before (staging, demo) and so cannot be seeded again
 * because the `member` table is not empty: LTR balances (V1.9.197) and, since V1.9.205, sample data for the LTR economy (crowdfunding,
 * auctions, social network, politician ranking), governance (committees, meetings, motions), tenders, events and the price-oracle history.
 *
 * Called ONLY from `main()` in `Application.kt` right after [StagingSeedData.seedIfEmpty], never from `module()`.
 * Two independent guards, both required: `LAPIS_STAGING_MODE=true` with a valid seed password ([StagingSeedTopUpGuard.envGate])
 * and the instance check inside the transaction ([StagingSeedTopUpGuard.instanceCheck], the first statement, before any write).
 * Every instance that passes both is fictitious by construction -- this includes the demo instance, which runs the same seed.
 *
 * The LTR top-up is one transaction; every part of [PARTS] then runs in its OWN transaction that again starts with the instance check, so a
 * failing part is rolled back alone while the others and the server start go on. No external effect, idempotent (fixed ids and skip-if-exists,
 * safe under Exposed's retry). [synchronized] is enough: the seed runs once per process at start-up, and a second process would only find the rows
 * already there.
 */
internal object StagingSeedTopUp {
    /** Order is load-bearing (governance before tenders: the tenders bind to the motions); pinned by a test. */
    val PARTS: List<StagingSeedPart> =
        listOf(
            StagingSeedCrowdfunding,
            StagingSeedAuctions,
            StagingSeedSocial,
            StagingSeedPoliticians,
            StagingSeedGovernanceTopUp,
            StagingSeedTenders,
            StagingSeedEvents,
            StagingSeedOracle,
        )

    fun topUpIfEligible(
        env: (String) -> String? = System::getenv,
        database: Database? = null,
    ): StagingTopUpOutcome {
        if (StagingSeedTopUpGuard.envGate(env) != null) return StagingTopUpOutcome.Skipped(reason = "staging mode is not enabled")
        // The test data is optional: a failure is logged and rolled back (nothing half-written), the server keeps starting.
        return runCatching { topUpWith(database = database) }
            .getOrElse { failure ->
                logger.error(failure) { "Staging-LTR-Aufstockung fehlgeschlagen -- Transaktion zurueckgerollt, Start wird fortgesetzt." }
                StagingTopUpOutcome.Skipped(reason = "failed")
            }
    }

    /** Test entry WITHOUT the env gate but WITH the instance check (never bypassed). Propagates exceptions of the LTR step; a failing part is reported. */
    internal fun topUpWith(
        database: Database?,
        parts: List<StagingSeedPart> = PARTS,
    ): StagingTopUpOutcome =
        synchronized(this) {
            when (val step = ltrStep(database)) {
                is LtrStep.Stop -> step.outcome
                is LtrStep.Done ->
                    StagingTopUpOutcome.Applied(
                        result = step.result,
                        parts = parts.map { part -> runPart(database = database, part = part, clock = step.clock) },
                    )
            }
        }

    private sealed interface LtrStep {
        data class Stop(
            val outcome: StagingTopUpOutcome,
        ) : LtrStep

        data class Done(
            val clock: SeedClock,
            val result: StagingSeedLtr.Result,
        ) : LtrStep
    }

    private fun ltrStep(database: Database?): LtrStep =
        transaction(database) {
            when (val decision = instanceCheck()) {
                is StagingTopUpDecision.Eligible -> {
                    val clock = SeedClock.capture(orgZone = with(StagingSeedData) { readOrganizationZone() })
                    val result = applyTopUp(clock)
                    logger.info {
                        "Staging-LTR-Aufstockung: ${result.mintsInserted} Gutschriften, ${result.transfersInserted} Uebertragungen, " +
                            "${result.skippedIneligible} uebersprungen."
                    }
                    LtrStep.Done(clock = clock, result = result)
                }
                is StagingTopUpDecision.NotStagingInstance -> {
                    logger.info { "Staging-LTR-Aufstockung uebersprungen: ${decision.reason}." }
                    LtrStep.Stop(StagingTopUpOutcome.Skipped(reason = decision.reason))
                }
                is StagingTopUpDecision.EnvDisabled -> LtrStep.Stop(StagingTopUpOutcome.Skipped(reason = "env disabled"))
            }
        }

    /** One part in its own transaction; instance check first. A failure rolls this part back and is reported, never thrown. */
    private fun runPart(
        database: Database?,
        part: StagingSeedPart,
        clock: SeedClock,
    ): StagingSeedPartReport =
        try {
            transaction(database) {
                when (val decision = instanceCheck()) {
                    is StagingTopUpDecision.Eligible -> {
                        val result = with(part) { apply(clock) }
                        logger.info { "Staging-Seed-Teil '${part.name}': ${result.inserted} geschrieben, ${result.skipped} uebersprungen." }
                        StagingSeedPartReport(part = part.name, inserted = result.inserted, skipped = result.skipped, failed = false)
                    }
                    else -> {
                        logger.info { "Staging-Seed-Teil '${part.name}' uebersprungen: Instanzpruefung nicht bestanden." }
                        StagingSeedPartReport(part = part.name, inserted = 0, skipped = 0, failed = false)
                    }
                }
            }
        } catch (failure: Exception) {
            // The data is optional: only the part's name, no member names or ids; the transaction is rolled back, the other parts go on.
            logger.error(
                failure,
            ) { "Staging-Seed-Teil '${part.name}' fehlgeschlagen -- Transaktion zurueckgerollt, uebrige Teile laufen weiter." }
            StagingSeedPartReport(part = part.name, inserted = 0, skipped = 0, failed = true)
        }
}
