package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AuditChainVerificationResultDto
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditLogListQuery

/**
 * GoBD-Revisionssicherheit (V0.5.3) read access -- see
 * `network.lapis.cloud.server.rpc.AuditLogService`/`network.lapis.cloud.server.audit
 * .AuditLogRecorder` KDoc for the full write-path/hash-chain design and
 * `lapis-server/src/main/kuml/14-audit-log.kuml.kts` for the bounded-scope rationale. There is
 * deliberately no write/mutate method on this interface at all -- every audit-log row is written
 * exclusively by [AuditLogRecorder] from inside the fachlich transaction it accompanies, never via
 * a directly callable RPC method (GoBD Unveraenderbarkeit: nothing about this log is a client-
 * triggerable mutation).
 *
 * Every method requires TREASURER/BOARD/ADMIN (same read tier `IAccountingService`'s own
 * bookkeeping-read methods already require) -- the audit log itself is treasury-/governance-
 * sensitive, not member-public.
 */
@RpcService
interface IAuditLogService {
    /**
     * Role: TREASURER/BOARD/ADMIN. Keyset-paginated (via [AuditLogListQuery.beforeSequenceNumber],
     * never `OFFSET` -- driftsafe under concurrent inserts), newest-first
     * ([AuditLogEntryDto.sequenceNumber] descending) audit-trail listing, filterable by
     * [AuditLogListQuery]'s fields. [AuditLogListQuery.limit] (default 50) is capped server-side at
     * a maximum page size regardless of what is requested -- DoS guard against an unbounded scan of
     * a log that only ever grows.
     */
    suspend fun listAuditLog(query: AuditLogListQuery = AuditLogListQuery()): List<AuditLogEntryDto>

    /** Role: TREASURER/BOARD/ADMIN. Throws [NotFoundException] if [id] does not resolve to a row. */
    suspend fun getAuditLogEntry(id: String): AuditLogEntryDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Re-walks the hash chain over
     * `[fromSequenceNumber, toSequenceNumber]` (either or both `null` = open on that side, up to
     * the entire chain) and reports whether every row's stored hash still matches a fresh
     * recomputation and every row's `previousEntryHash` still matches its predecessor's
     * `entryHash` -- see [AuditChainVerificationResultDto] KDoc for the exact result shape.
     *
     * The number of rows *matched* by whatever combination of bounds is supplied -- no bounds, one
     * bound, or an explicit two-sided range -- is capped server-side at a fixed maximum before any
     * row is loaded or rehashed; exceeding it throws [BadRequestException] demanding a narrower
     * range. This is a DoS guard against unbounded rehashing of an ever-growing, never-purged
     * table, and applies uniformly regardless of which parameters are `null` -- there is no
     * parameter combination that bypasses it.
     */
    suspend fun verifyChainIntegrity(
        fromSequenceNumber: Long? = null,
        toSequenceNumber: Long? = null,
    ): AuditChainVerificationResultDto
}
