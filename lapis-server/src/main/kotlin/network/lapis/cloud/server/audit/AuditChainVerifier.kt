package network.lapis.cloud.server.audit

import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AuditChainVerificationResultDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The hash-chain walk behind `IAuditLogService.verifyChainIntegrity` (V1.9.63: moved here unchanged from `AuditLogService`, so the
 * staging seed's test verifies the chain with the production verifier and not with a test copy). Must run inside a transaction.
 */
internal object AuditChainVerifier {
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
    fun verify(rows: List<ResultRow>): AuditChainVerificationResultDto {
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
}
