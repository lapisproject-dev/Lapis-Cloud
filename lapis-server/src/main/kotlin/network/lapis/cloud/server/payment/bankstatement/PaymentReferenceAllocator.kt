package network.lapis.cloud.server.payment.bankstatement

import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.shared.domain.PaymentReferenceCode
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.uuid.Uuid

/**
 * Welle V1.4.5.1 "Kontoauszugs-Import". Vergibt `contribution.payment_reference` -- see
 * [PaymentReferenceCode] KDoc for the `"LC-XXXXXX"` grammar itself.
 *
 * **Deliberately NOT a Postgres `SEQUENCE`** -- three independent reasons (see the plan's own OF-5
 * discussion): (1) this codebase has no `CREATE SEQUENCE` anywhere, `nextval` portability between
 * H2 (`MODE=PostgreSQL`) and real Postgres would be a new, untested mechanism; (2)
 * `ContributionService.generateContributionsForPeriod` uses `insertIgnore` in a loop -- every
 * skipped row would burn a sequence value; (3) the GF(32) check symbol
 * ([PaymentReferenceCode.checkSymbolFor]) cannot be computed in portable SQL, so a "deterministic
 * migration backfill" was never achievable anyway. Instead: [SecureRandom] (never
 * `kotlin.random.Random`, same discipline [network.lapis.cloud.server.payment.sepa
 * .SepaMandateReferenceGenerator]/[network.lapis.cloud.server.crypto.SecretBox] already establish)
 * plus collision retry against `uq_contribution_payment_reference`. At 2²⁵ ≈ 33.5 million possible
 * values, the odds of exhausting [MAX_ATTEMPTS] retries even with hundreds of thousands of existing
 * references are astronomically small.
 *
 * **No backfill for pre-existing rows.** A reference allocated retroactively for a contribution
 * whose invoice was already sent could never appear in that already-mailed invoice's
 * Verwendungszweck -- see `V20__bank_statement_import.sql`'s own comment. Callers therefore only
 * ever allocate at the two points a reference can still reach a not-yet-printed invoice: when a new
 * contribution row is created ([network.lapis.cloud.server.rpc.ContributionService
 * .generateContributionsForPeriod]) and, lazily, the first time an invoice is actually rendered
 * ([network.lapis.cloud.server.routes.generateBeitragsrechnung], via [ensureReference]).
 */
internal object PaymentReferenceAllocator {
    private const val MAX_ATTEMPTS = 8
    private val secureRandom = SecureRandom()

    /**
     * Must run inside an already-open transaction. Allocates and persists a fresh reference for
     * [contributionId], overwriting nothing else. Throws [IllegalStateException] if [MAX_ATTEMPTS]
     * consecutive collisions occur (practically unreachable, see class KDoc).
     *
     * **Per-attempt SAVEPOINT (Review fix, MEDIUM)** -- on PostgreSQL, a failed `UPDATE` (the
     * `uq_contribution_payment_reference` collision this retry loop exists to handle) aborts the
     * WHOLE surrounding transaction (`SQLSTATE 25P02`, "current transaction is aborted"), not just
     * that one statement. The previous code caught only the [ExposedSQLException] and looped straight
     * into another plain `UPDATE` on the SAME (now-aborted) transaction -- on real Postgres every
     * subsequent attempt throws the SAME abort error, all [MAX_ATTEMPTS] retries are burned on the
     * first genuine collision, `error(...)` fires, and -- because [allocate] is called from inside
     * [network.lapis.cloud.server.rpc.ContributionService.generateContributionsForPeriod]'s own single
     * large transaction (one call per newly created contribution) -- that failure rolls back the
     * ENTIRE period's contribution generation, not just this one reference. H2 (`MODE=PostgreSQL`,
     * the only environment this codebase's tests run against, see `DatabaseConfig`) does not abort
     * the whole transaction on a statement error the way PostgreSQL does, so this bug was invisible
     * to every existing test. Fixed the same way [network.lapis.cloud.server.webhook
     * .WebhookEventPublisher.publish] already established for its own per-endpoint retry: a real JDBC
     * savepoint per attempt (`ExposedConnection.setSavepoint`/`.releaseSavepoint`/`.rollback`) so a
     * collision only discards THIS attempt's failed `UPDATE`, leaving the surrounding transaction (and
     * every other contribution already generated in this same batch) fully intact.
     */
    fun allocate(contributionId: Uuid): String {
        val connection = TransactionManager.current().connection
        repeat(MAX_ATTEMPTS) {
            val payload = secureRandom.nextInt(1 shl 25)
            val candidate = PaymentReferenceCode.PREFIX + PaymentReferenceCode.encode(payload)
            val savepoint = connection.setSavepoint("payment_reference_allocate")
            val updated =
                try {
                    val result =
                        ContributionTable.update({ ContributionTable.id eq contributionId }) {
                            it[paymentReference] = candidate
                        }
                    connection.releaseSavepoint(savepoint)
                    result
                } catch (e: ExposedSQLException) {
                    // uq_contribution_payment_reference collision -- retry with a fresh payload.
                    connection.rollback(savepoint)
                    0
                }
            if (updated > 0) return candidate
        }
        error("PaymentReferenceAllocator: could not allocate a unique payment reference for $contributionId after $MAX_ATTEMPTS attempts")
    }

    /** Idempotent: returns the contribution's existing reference if it already has one, otherwise allocates a fresh one via [allocate]. Must run inside an already-open transaction. */
    fun ensureReference(contributionId: Uuid): String {
        val row =
            ContributionTable
                .selectAll()
                .where { ContributionTable.id eq contributionId }
                .singleOrNull()
                ?: throw NotFoundException("Contribution $contributionId not found")
        return row[ContributionTable.paymentReference] ?: allocate(contributionId)
    }
}
