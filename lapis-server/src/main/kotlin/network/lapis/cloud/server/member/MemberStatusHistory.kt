package network.lapis.cloud.server.member

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.temporal.ChronoUnit
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Where a row of `member_status_history` came from (the SQL column `source`). */
enum class MemberStatusHistorySource {
    /** A service wrote it while changing the status. */
    LIVE,

    /** The CSV member import. */
    IMPORT,

    /** Dev / staging seed data. */
    SEED,

    /** Welle V1.9.73: a member created on the first Keycloak login (just-in-time provisioning). Counted by the hourly rate limit. */
    KEYCLOAK_JIT,

    /** V72 backfill: reconstructed from the hash-chained audit log. */
    BACKFILL_AUDIT,

    /** V72 backfill: reconstructed from a record (acknowledgment, `reviewed_at`, `date_of_death`, `friend_since`). */
    BACKFILL_RECORD,

    /** V72 backfill: assumed (no evidence of an earlier status). */
    BACKFILL_ASSUMED,
}

/**
 * Welle V1.9.59 "Mitgliederzahlen ueber Zeit" -- the **only writer of `member_status_history`** (the source-scan tripwire
 * `MemberStatusWriteTripwireTest` pins that every writer of `member.status` calls [recordLocked]). The log is append-only:
 * one row per change of `member.status`, `previous_status` = the status of the member's previous row, so a count at any
 * instant is a sum of deltas (see [MemberCountAggregation]).
 */
object MemberStatusHistory {
    /**
     * Appends `(memberId, newStatus)` unless the latest row of the member already has [newStatus] (then nothing is written
     * and `false` is returned). **Requires** that the caller holds the member row lock in THIS transaction (`forMemberUpdate()`
     * or a preceding INSERT / UPDATE of that member row), and that the status write itself already succeeded -- call it AFTER
     * the write and after every `updated == 0` check, so a failed change leaves no row.
     *
     * `effective_from = max(min(effectiveFrom, now), latest.effective_from + 1 microsecond)`, truncated to the database
     * precision. The upper bound is needed because several paths compute `now` BEFORE their transaction: without it a slower
     * request could write an older instant behind a younger one and break the chain. `recorded_at` is [now].
     *
     * Never catches an exception: a primary key violation here is a bug (the member lock serialises the writers) and must roll back.
     */
    fun recordLocked(
        memberId: Uuid,
        newStatus: MemberStatus,
        now: LocalDateTime,
        source: MemberStatusHistorySource,
        effectiveFrom: LocalDateTime = now,
    ): Boolean {
        require(
            source == MemberStatusHistorySource.LIVE ||
                source == MemberStatusHistorySource.IMPORT ||
                source == MemberStatusHistorySource.SEED ||
                source == MemberStatusHistorySource.KEYCLOAK_JIT,
        ) {
            "only LIVE, IMPORT, SEED and KEYCLOAK_JIT rows are written at runtime"
        }
        val latest =
            MemberStatusHistoryTable
                .selectAll()
                .where { MemberStatusHistoryTable.memberId eq memberId }
                .orderBy(MemberStatusHistoryTable.effectiveFrom to SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        if (latest != null && latest[MemberStatusHistoryTable.status] == newStatus.name) return false
        val requested = minOf(effectiveFrom, now).truncatedToDbPrecision()
        val from =
            if (latest == null) {
                requested
            } else {
                maxOf(requested, latest[MemberStatusHistoryTable.effectiveFrom].plusOneMicro())
            }
        val stampedAt = now.truncatedToDbPrecision()
        MemberStatusHistoryTable.insert {
            it[MemberStatusHistoryTable.memberId] = memberId
            it[MemberStatusHistoryTable.effectiveFrom] = from
            it[MemberStatusHistoryTable.status] = newStatus.name
            it[MemberStatusHistoryTable.previousStatus] = latest?.get(MemberStatusHistoryTable.status)
            it[MemberStatusHistoryTable.sourceKind] = source.name
            it[MemberStatusHistoryTable.recordedAt] = stampedAt
        }
        return true
    }

    private fun LocalDateTime.plusOneMicro(): LocalDateTime = toJavaLocalDateTime().plus(1, ChronoUnit.MICROS).toKotlinLocalDateTime()
}

/** Cross-check of the log against `member.status`; an operations signal, never a gate. */
object MemberStatusHistoryConsistency {
    /**
     * The number of members whose latest history row does not carry their current status, or who have no row at all.
     * Must run inside a transaction.
     */
    fun countMismatches(): Long {
        // A fixed diagnostic SELECT without any input: one aggregate over the members, no row per member leaves the database.
        val sql =
            "SELECT count(*) FROM member m WHERE NOT EXISTS (SELECT 1 FROM member_status_history x WHERE x.member_id = m.id) " +
                "OR (SELECT y.status FROM member_status_history y WHERE y.member_id = m.id AND y.effective_from = " +
                "(SELECT MAX(z.effective_from) FROM member_status_history z WHERE z.member_id = m.id)) <> m.status"
        var count = 0L
        TransactionManager.current().exec(sql) { rs -> if (rs.next()) count = rs.getLong(1) }
        return count
    }

    /** Startup signal: a warning with only the NUMBER of members (never an id). */
    fun warnIfInconsistent(mismatches: Long) {
        if (mismatches > 0) {
            logger.warn { "member_status_history is inconsistent with member.status for $mismatches member(s)" }
        }
    }
}
