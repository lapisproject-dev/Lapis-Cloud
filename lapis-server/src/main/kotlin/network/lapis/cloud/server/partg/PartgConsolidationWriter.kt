package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgConsolidationRules
import network.lapis.cloud.shared.domain.PartgDonorCombinationAuditSnapshot
import network.lapis.cloud.shared.domain.PartgIntraTransferAuditSnapshot
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.290 (R5) -- the ONE write path of the consolidation (intra-party transfer pairs and donor combinations); `PartyReportService` calls
 * nothing else.
 *
 * One transaction per write, always in this order: (1) lock the consolidation header of the year, (2) compare `expectedRevision` under the
 * lock, (3) validate, (4) write, (5) bump the revision in the SAME commit, (6) audit LAST. [result] runs inside the same transaction.
 * Lock order: consolidation header, then the chapter rows of a pair (sorted by unit key) or the annex headers of a combination (sorted by
 * unit key). The annex writer never takes the consolidation header, so there is no cycle. The audit entries carry ids, counters and codes:
 * NEVER a name, a note or an amount. Nothing is logged; no exception carries an input field. NO booking is touched (GoBD).
 */
internal object PartgConsolidationWriter {
    private const val CHANGE_TRANSFER_ADDED = "TRANSFER_ADDED"
    private const val CHANGE_TRANSFER_UPDATED = "TRANSFER_UPDATED"
    private const val CHANGE_TRANSFER_DELETED = "TRANSFER_DELETED"
    private const val CHANGE_COMBINATION_CREATED = "COMBINATION_CREATED"
    private const val CHANGE_COMBINATION_DISSOLVED = "COMBINATION_DISSOLVED"

    private fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw NotFoundException("Row not found")

    fun <T> saveTransfer(
        current: CurrentMember,
        year: Int,
        expectedRevision: Long,
        transfer: PartgIntraTransferInputDto,
        now: LocalDateTime,
        result: () -> T,
    ): T {
        // pure input checks first: nothing is locked for an invalid request
        val payer = PartgUnit.parse(transfer.payerUnitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        val payee = PartgUnit.parse(transfer.payeeUnitKey) ?: throw PartyReportInvalidInputException("Invalid unit")
        if (payer.key() == payee.key()) throw PartyReportInvalidInputException("SAME_UNIT")
        PartgConsolidationRules.validateAmounts(payer = transfer.payerAmountCents, payee = transfer.payeeAmountCents)?.let {
            throw PartyReportInvalidInputException(it)
        }
        val note =
            PartgConsolidationRules.normalizeNote(transfer.note).getOrElse { throw PartyReportInvalidInputException(it.message ?: "NOTE") }
        val adding = transfer.id == null
        val transferId = transfer.id?.let { parseId(it) } ?: Uuid.random()
        return transaction {
            val sheet = PartgConsolidationStore.lockOrCreateSheet(year = year, actor = current.memberId, now = now)
            if (sheet.revision != expectedRevision) throw PartyReportRevisionConflictException()
            // both chapters must exist (NotFound otherwise); the rows are locked so a chapter cannot be deleted under the new pair
            listOf(payer, payee).sortedBy { it.key() }.forEach { PartgReportStore.lockChapter(it) }
            val row =
                PartgConsolidationStore.TransferRow(
                    id = transferId,
                    kind = transfer.kind,
                    payerUnitKey = payer.key(),
                    payeeUnitKey = payee.key(),
                    payerAmountCents = transfer.payerAmountCents,
                    payeeAmountCents = transfer.payeeAmountCents,
                    note = note,
                )
            val sameKey =
                PartgConsolidationStore.findTransferByPair(
                    sheetId = sheet.id,
                    kind = transfer.kind,
                    payerUnitKey = payer.key(),
                    payeeUnitKey = payee.key(),
                )
            if (sameKey != null && sameKey.id != transferId) throw PartyReportInvalidInputException("PAIR_EXISTS")
            try {
                withSavepoint(name = "partg_transfer_write") {
                    if (adding) {
                        if (PartgConsolidationStore.countTransfers(sheet.id) >= PartgConsolidationRules.MAX_TRANSFERS_PER_YEAR) {
                            throw PartyReportInvalidInputException("TOO_MANY")
                        }
                        PartgConsolidationStore.insertTransfer(
                            sheetId = sheet.id,
                            transfer = row,
                            payer = payer,
                            payee = payee,
                            actor = current.memberId,
                            now = now,
                        )
                    } else {
                        PartgConsolidationStore.findTransfer(sheetId = sheet.id, transferId = transferId)
                            ?: throw NotFoundException("Row not found")
                        PartgConsolidationStore.updateTransfer(
                            sheetId = sheet.id,
                            transfer = row,
                            payer = payer,
                            payee = payee,
                            actor = current.memberId,
                            now = now,
                        )
                    }
                }
            } catch (e: Exception) {
                if (e.isUniqueViolation()) throw PartyReportInvalidInputException("PAIR_EXISTS")
                throw e
            }
            PartgConsolidationStore.bumpRevision(sheet = sheet, actor = current.memberId, now = now)
            auditTransfer(
                current = current,
                transferId = transferId,
                action = if (adding) AuditAction.CREATE else AuditAction.UPDATE,
                snapshot =
                    PartgIntraTransferAuditSnapshot(
                        year = year,
                        change = if (adding) CHANGE_TRANSFER_ADDED else CHANGE_TRANSFER_UPDATED,
                        kind = row.kind.name,
                        payerUnit = row.payerUnitKey,
                        payeeUnit = row.payeeUnitKey,
                        hasPayerAmount = row.payerAmountCents != null,
                        hasPayeeAmount = row.payeeAmountCents != null,
                    ),
                now = now,
            )
            result()
        }
    }

    fun <T> deleteTransfer(
        current: CurrentMember,
        year: Int,
        expectedRevision: Long,
        transferId: String,
        now: LocalDateTime,
        result: () -> T,
    ): T {
        val id = parseId(transferId)
        return transaction {
            val sheet = PartgConsolidationStore.lockExistingSheet(year) ?: throw NotFoundException("Row not found")
            if (sheet.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val old = PartgConsolidationStore.findTransfer(sheetId = sheet.id, transferId = id) ?: throw NotFoundException("Row not found")
            PartgConsolidationStore.deleteTransfer(sheetId = sheet.id, transferId = id)
            PartgConsolidationStore.bumpRevision(sheet = sheet, actor = current.memberId, now = now)
            auditTransfer(
                current = current,
                transferId = id,
                action = AuditAction.VOID,
                snapshot =
                    PartgIntraTransferAuditSnapshot(
                        year = year,
                        change = CHANGE_TRANSFER_DELETED,
                        kind = old.kind.name,
                        payerUnit = old.payerUnitKey,
                        payeeUnit = old.payeeUnitKey,
                        hasPayerAmount = old.payerAmountCents != null,
                        hasPayeeAmount = old.payeeAmountCents != null,
                    ),
                now = now,
            )
            result()
        }
    }

    /**
     * Combines 2..20 large-donor rows of DIFFERENT units of [year]. Lock order: consolidation header, then the annex headers of the units
     * involved in `unit_key` order; the rows are read again under those locks. [expectedAnnexRevisions] is keyed by unit key.
     */
    fun <T> createCombination(
        current: CurrentMember,
        year: Int,
        expectedRevision: Long,
        expectedAnnexRevisions: Map<String, Long>,
        largeDonorIds: List<String>,
        now: LocalDateTime,
        result: () -> T,
    ): T {
        val ids = largeDonorIds.map { parseId(it) }
        if (ids.toSet().size != ids.size) throw PartyReportInvalidInputException("DUPLICATE_ROW")
        if (ids.size < PartgConsolidationRules.MIN_ROWS || ids.size > PartgConsolidationRules.MAX_ROWS) {
            throw PartyReportInvalidInputException("ROW_COUNT")
        }
        return transaction {
            val sheet = PartgConsolidationStore.lockOrCreateSheet(year = year, actor = current.memberId, now = now)
            if (sheet.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val first = PartgAnnexStore.donorLocations(ids)
            if (first.size != ids.size || first.values.any { it.year != year }) throw NotFoundException("Row not found")
            val unitKeys = first.values.map { it.unitKey }
            if (unitKeys.toSet().size != unitKeys.size) throw PartyReportInvalidInputException("SAME_UNIT")
            val headers = PartgAnnexStore.lockAnnexHeaders(year = year, unitKeys = unitKeys)
            unitKeys.forEach { unit ->
                val header = headers[unit] ?: throw NotFoundException("Row not found")
                if (expectedAnnexRevisions[unit] != header.revision) throw PartyReportRevisionConflictException()
            }
            // under the locks: a row deleted or moved in the meantime is a clean refusal
            val located = PartgAnnexStore.donorLocations(ids)
            if (located.size != ids.size || located.values.any { it.year != year }) throw NotFoundException("Row not found")
            if (PartgConsolidationStore.alreadyCombined(ids).isNotEmpty()) throw PartyReportInvalidInputException("ALREADY_COMBINED")
            if (PartgConsolidationStore.countCombinations(sheet.id) >= PartgConsolidationRules.MAX_COMBINATIONS_PER_YEAR) {
                throw PartyReportInvalidInputException("TOO_MANY")
            }
            val combinationId =
                PartgConsolidationStore.insertCombination(sheetId = sheet.id, donorIds = ids, actor = current.memberId, now = now)
            PartgConsolidationStore.bumpRevision(sheet = sheet, actor = current.memberId, now = now)
            auditCombination(
                current = current,
                combinationId = combinationId,
                action = AuditAction.CREATE,
                snapshot =
                    PartgDonorCombinationAuditSnapshot(
                        year = year,
                        change = CHANGE_COMBINATION_CREATED,
                        rowCount = ids.size,
                        unitKeys = unitKeys.sorted(),
                        largeDonorIds = ids.map { it.toString() }.sorted(),
                    ),
                now = now,
            )
            result()
        }
    }

    fun <T> dissolveCombination(
        current: CurrentMember,
        year: Int,
        expectedRevision: Long,
        combinationId: String,
        now: LocalDateTime,
        result: () -> T,
    ): T {
        val id = parseId(combinationId)
        return transaction {
            val sheet = PartgConsolidationStore.lockExistingSheet(year) ?: throw NotFoundException("Row not found")
            if (sheet.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val donorIds =
                PartgConsolidationStore.combinationDonorIds(sheetId = sheet.id, combinationId = id)
                    ?: throw NotFoundException("Row not found")
            val unitKeys =
                PartgAnnexStore
                    .donorLocations(donorIds)
                    .values
                    .map { it.unitKey }
                    .distinct()
                    .sorted()
            PartgConsolidationStore.deleteCombination(sheetId = sheet.id, combinationId = id)
            PartgConsolidationStore.bumpRevision(sheet = sheet, actor = current.memberId, now = now)
            auditCombination(
                current = current,
                combinationId = id,
                action = AuditAction.VOID,
                snapshot =
                    PartgDonorCombinationAuditSnapshot(
                        year = year,
                        change = CHANGE_COMBINATION_DISSOLVED,
                        rowCount = donorIds.size,
                        unitKeys = unitKeys,
                        largeDonorIds = donorIds.map { it.toString() },
                    ),
                now = now,
            )
            result()
        }
    }

    private fun auditTransfer(
        current: CurrentMember,
        transferId: Uuid,
        action: AuditAction,
        snapshot: PartgIntraTransferAuditSnapshot,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.PARTG_INTRA_TRANSFER,
            entityId = transferId,
            action = action,
            after = Json.encodeToString(PartgIntraTransferAuditSnapshot.serializer(), snapshot),
            occurredAt = now,
        )
    }

    private fun auditCombination(
        current: CurrentMember,
        combinationId: Uuid,
        action: AuditAction,
        snapshot: PartgDonorCombinationAuditSnapshot,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.PARTG_DONOR_COMBINATION,
            entityId = combinationId,
            action = action,
            after = Json.encodeToString(PartgDonorCombinationAuditSnapshot.serializer(), snapshot),
            occurredAt = now,
        )
    }
}
