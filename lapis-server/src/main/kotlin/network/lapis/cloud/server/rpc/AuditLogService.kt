package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.audit.AuditHashChain
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditChainVerificationResultDto
import network.lapis.cloud.shared.domain.AuditLogEntryDto
import network.lapis.cloud.shared.domain.AuditLogListQuery
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IAuditLogService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val AUDIT_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/** Server-side cap on [IAuditLogService.listAuditLog]'s `limit` -- DoS guard, see that method's KDoc. */
private const val MAX_PAGE_SIZE = 200

/**
 * Server-side cap on how many rows [IAuditLogService.verifyChainIntegrity] will rehash in a
 * single call -- DoS guard against unbounded rehashing of an ever-growing, never-purged table
 * (see `14-audit-log.kuml.kts`'s "no retention/purge path" note). Applied uniformly to the
 * *matched row count* of whatever filter was supplied -- no bounds at all, only one of
 * `fromSequenceNumber`/`toSequenceNumber`, or an explicit-but-huge range are all counted and
 * capped the same way. There is no way to opt out of this cap by choosing a particular
 * combination of parameters -- every combination is measured against the same limit before any
 * row is loaded or rehashed.
 */
private const val MAX_VERIFY_RANGE = 10_000L

/**
 * GoBD-Revisionssicherheit (V0.5.3) read access. Implements [IAuditLogService] -- see that
 * interface's KDoc and `network.lapis.cloud.server.audit.AuditLogRecorder`'s KDoc for the full
 * write-path/hash-chain design this service only ever reads from (never writes -- there is no
 * write method on [IAuditLogService] at all, by design).
 */
class AuditLogService(
    private val call: ApplicationCall,
) : IAuditLogService {
    override suspend fun listAuditLog(query: AuditLogListQuery): List<AuditLogEntryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*AUDIT_READ_ROLES)
        val entityUuid = query.entityId?.toAuditUuid("AuditLogEntry.entityId")
        val actorUuid = query.actorMemberId?.toAuditUuid("Member")
        val cappedLimit = query.limit.coerceIn(1, MAX_PAGE_SIZE)
        val entityType = query.entityType
        val from = query.from
        val to = query.to
        val beforeSequenceNumber = query.beforeSequenceNumber
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (entityType != null) conditions += (AuditLogEntryTable.entityType eq entityType)
            if (entityUuid != null) conditions += (AuditLogEntryTable.entityId eq entityUuid)
            if (actorUuid != null) conditions += (AuditLogEntryTable.actorMemberId eq actorUuid)
            if (from != null) conditions += (AuditLogEntryTable.occurredAt greaterEq from)
            if (to != null) conditions += (AuditLogEntryTable.occurredAt lessEq to)
            // Keyset pagination, NOT OFFSET -- driftsafe under concurrent inserts (a page already
            // handed to a caller never shifts underneath them as new rows keep appending at the
            // top of the newest-first ordering).
            if (beforeSequenceNumber != null) {
                conditions += (AuditLogEntryTable.sequenceNumber less beforeSequenceNumber)
            }
            val baseQuery = AuditLogEntryTable.selectAll()
            val filtered = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            filtered
                .orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.DESC)
                .limit(cappedLimit)
                .map { it.toAuditLogEntryDto() }
        }
    }

    override suspend fun getAuditLogEntry(id: String): AuditLogEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*AUDIT_READ_ROLES)
        val entryId = id.toAuditUuid("AuditLogEntry")
        return transaction {
            AuditLogEntryTable
                .selectAll()
                .where { AuditLogEntryTable.id eq entryId }
                .singleOrNull()
                ?.toAuditLogEntryDto() ?: throw NotFoundException("AuditLogEntry $id not found")
        }
    }

    override suspend fun verifyChainIntegrity(
        fromSequenceNumber: Long?,
        toSequenceNumber: Long?,
    ): AuditChainVerificationResultDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*AUDIT_READ_ROLES)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (fromSequenceNumber != null) conditions += (AuditLogEntryTable.sequenceNumber greaterEq fromSequenceNumber)
            if (toSequenceNumber != null) conditions += (AuditLogEntryTable.sequenceNumber lessEq toSequenceNumber)

            // DoS guard: count the *matched* rows for whatever combination of bounds was supplied
            // -- no bounds, one bound, or an explicit-but-huge two-sided range are all measured
            // and capped the same way, before any row is loaded or rehashed. See MAX_VERIFY_RANGE's
            // KDoc for why this must not special-case "both bounds null".
            val countQuery = AuditLogEntryTable.selectAll()
            val filteredCount = if (conditions.isEmpty()) countQuery else countQuery.where { conditions.reduce { a, b -> a and b } }
            val matched = filteredCount.count()
            if (matched > MAX_VERIFY_RANGE) {
                throw BadRequestException(
                    "verifyChainIntegrity range matches $matched rows, exceeding the $MAX_VERIFY_RANGE-row cap -- " +
                        "narrow the fromSequenceNumber/toSequenceNumber range",
                )
            }

            val rowsQuery = AuditLogEntryTable.selectAll()
            val filteredRows = if (conditions.isEmpty()) rowsQuery else rowsQuery.where { conditions.reduce { a, b -> a and b } }
            val rows = filteredRows.orderBy(AuditLogEntryTable.sequenceNumber to SortOrder.ASC).toList()
            verifyRows(rows)
        }
    }

    /**
     * Re-walks [rows] (already loaded, ordered ascending by `sequenceNumber`) and reports whether
     * every row's stored `entryHash` matches a fresh recomputation over its own fields, every
     * row's stored `previousEntryHash` matches the immediately preceding row's `entryHash`, and
     * `sequenceNumber` is gapless throughout.
     *
     * When [rows] does not start at `sequenceNumber == 1` (a windowed
     * [network.lapis.cloud.shared.rpc.IAuditLogService.verifyChainIntegrity] call), the row
     * immediately BEFORE the window is also loaded and used as the expected-previous-hash anchor
     * for the window's first row -- a windowed check still verifies the link into the rest of the
     * chain, not just internal consistency of the window in isolation. If that anchor row is
     * itself missing (predecessor was deleted), that is reported as a break at the window's own
     * first `sequenceNumber`.
     */
    private fun verifyRows(rows: List<ResultRow>): AuditChainVerificationResultDto {
        if (rows.isEmpty()) {
            return AuditChainVerificationResultDto(
                valid = true,
                checkedCount = 0,
                firstSequenceNumber = null,
                lastSequenceNumber = null,
                brokenAtSequenceNumber = null,
                reason = null,
            )
        }
        val firstSequenceNumber = rows.first()[AuditLogEntryTable.sequenceNumber]
        val lastSequenceNumber = rows.last()[AuditLogEntryTable.sequenceNumber]

        var expectedPreviousHash: String?
        if (firstSequenceNumber > 1L) {
            val anchor =
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.sequenceNumber eq (firstSequenceNumber - 1) }
                    .singleOrNull()
            if (anchor == null) {
                return AuditChainVerificationResultDto(
                    valid = false,
                    checkedCount = 0,
                    firstSequenceNumber = firstSequenceNumber,
                    lastSequenceNumber = lastSequenceNumber,
                    brokenAtSequenceNumber = firstSequenceNumber,
                    reason =
                        "Predecessor row (sequenceNumber ${firstSequenceNumber - 1}) is missing -- " +
                            "cannot verify the chain link into this range",
                )
            }
            expectedPreviousHash = anchor[AuditLogEntryTable.entryHash]
        } else {
            expectedPreviousHash = null
        }

        rows.forEachIndexed { index, row ->
            val sequenceNumber = row[AuditLogEntryTable.sequenceNumber]
            if (index > 0) {
                val expectedSequenceNumber = rows[index - 1][AuditLogEntryTable.sequenceNumber] + 1
                if (sequenceNumber != expectedSequenceNumber) {
                    return AuditChainVerificationResultDto(
                        valid = false,
                        checkedCount = index,
                        firstSequenceNumber = firstSequenceNumber,
                        lastSequenceNumber = lastSequenceNumber,
                        brokenAtSequenceNumber = sequenceNumber,
                        reason = "Gap in sequenceNumber ($expectedSequenceNumber expected, got $sequenceNumber) -- a row was deleted",
                    )
                }
            }
            if (row[AuditLogEntryTable.previousEntryHash] != expectedPreviousHash) {
                return AuditChainVerificationResultDto(
                    valid = false,
                    checkedCount = index,
                    firstSequenceNumber = firstSequenceNumber,
                    lastSequenceNumber = lastSequenceNumber,
                    brokenAtSequenceNumber = sequenceNumber,
                    reason =
                        "Stored previousEntryHash does not match the preceding row's entryHash -- " +
                            "a row was tampered with, deleted, or reordered",
                )
            }
            val recomputedHash =
                AuditHashChain.computeHash(
                    AuditHashChain.ChainInput(
                        sequenceNumber = sequenceNumber,
                        occurredAt = row[AuditLogEntryTable.occurredAt],
                        actorMemberId = row[AuditLogEntryTable.actorMemberId],
                        actorRole = row[AuditLogEntryTable.actorRole],
                        entityType = row[AuditLogEntryTable.entityType],
                        entityId = row[AuditLogEntryTable.entityId],
                        action = row[AuditLogEntryTable.action],
                        beforeSnapshot = row[AuditLogEntryTable.beforeSnapshot],
                        afterSnapshot = row[AuditLogEntryTable.afterSnapshot],
                        previousEntryHash = row[AuditLogEntryTable.previousEntryHash],
                    ),
                )
            if (recomputedHash != row[AuditLogEntryTable.entryHash]) {
                return AuditChainVerificationResultDto(
                    valid = false,
                    checkedCount = index + 1,
                    firstSequenceNumber = firstSequenceNumber,
                    lastSequenceNumber = lastSequenceNumber,
                    brokenAtSequenceNumber = sequenceNumber,
                    reason =
                        "Stored entryHash does not match a fresh recomputation of this row's own fields -- " +
                            "row content was tampered with",
                )
            }
            expectedPreviousHash = recomputedHash
        }

        return AuditChainVerificationResultDto(
            valid = true,
            checkedCount = rows.size,
            firstSequenceNumber = firstSequenceNumber,
            lastSequenceNumber = lastSequenceNumber,
            brokenAtSequenceNumber = null,
            reason = null,
        )
    }

    private fun ResultRow.toAuditLogEntryDto(): AuditLogEntryDto {
        val actorId = this[AuditLogEntryTable.actorMemberId]
        return AuditLogEntryDto(
            id = this[AuditLogEntryTable.id].toString(),
            sequenceNumber = this[AuditLogEntryTable.sequenceNumber],
            occurredAt = this[AuditLogEntryTable.occurredAt],
            actorMemberId = actorId?.toString(),
            actorMemberDisplayName = actorId?.let { memberDisplayName(it) },
            actorRole = this[AuditLogEntryTable.actorRole],
            entityType = this[AuditLogEntryTable.entityType],
            entityId = this[AuditLogEntryTable.entityId].toString(),
            action = this[AuditLogEntryTable.action],
            beforeSnapshot = this[AuditLogEntryTable.beforeSnapshot],
            afterSnapshot = this[AuditLogEntryTable.afterSnapshot],
            entryHash = this[AuditLogEntryTable.entryHash],
            previousEntryHash = this[AuditLogEntryTable.previousEntryHash],
        )
    }

    private fun memberDisplayName(memberId: Uuid): String? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.displayName)

    private fun String.toAuditUuid(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}
