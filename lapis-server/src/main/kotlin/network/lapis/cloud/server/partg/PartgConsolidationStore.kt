package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgConsolidationSheetTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationRowTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationTable
import network.lapis.cloud.server.db.generated.PartgIntraTransferTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.290 (R5) -- the ONLY reader and writer of `partg_consolidation_sheet`, `partg_intra_transfer`, `partg_donor_combination` and
 * `partg_donor_combination_row` (pinned by `PartgConsolidationStoreTripwireTest`; the one exception is `PartgReportPersonalData`).
 *
 * Every function runs inside the caller's transaction. Lock order for writers, always: consolidation header row of the year, then (only the
 * combination writer) the annex headers of the units involved in `unit_key` order, audit last. The annex writer NEVER locks the consolidation
 * header; it only calls [detachDonor] under its own annex lock, so there is no lock cycle. Every write bumps the revision of the header in the
 * same transaction. The note of a pair is free text and may hold personal data: this object never logs and never puts a field into an exception.
 */
internal object PartgConsolidationStore {
    data class SheetRow(
        val id: Uuid,
        val year: Int,
        val revision: Long,
    )

    data class TransferRow(
        val id: Uuid,
        val kind: PartgTransferKind,
        val payerUnitKey: String,
        val payeeUnitKey: String,
        val payerAmountCents: Long?,
        val payeeAmountCents: Long?,
        val note: String?,
    )

    data class CombinationRow(
        val id: Uuid,
        val createdAt: LocalDateTime,
        val donorIds: List<Uuid>,
    )

    /** Everything of one year as read in a snapshot. */
    data class ConsolidationData(
        val revision: Long,
        /** Sorted: kind, payer unit key, payee unit key. */
        val transfers: List<TransferRow>,
        /** Sorted: created_at, id; the donor ids of a group are sorted by id. */
        val combinations: List<CombinationRow>,
    )

    private fun toSheetRow(row: ResultRow) =
        SheetRow(
            id = row[PartgConsolidationSheetTable.id],
            year = row[PartgConsolidationSheetTable.fiscalYear],
            revision = row[PartgConsolidationSheetTable.revision],
        )

    private fun toTransferRow(row: ResultRow) =
        TransferRow(
            id = row[PartgIntraTransferTable.id],
            kind = row[PartgIntraTransferTable.transferKind],
            payerUnitKey = row[PartgIntraTransferTable.payerUnitKey],
            payeeUnitKey = row[PartgIntraTransferTable.payeeUnitKey],
            payerAmountCents = row[PartgIntraTransferTable.payerAmountCents],
            payeeAmountCents = row[PartgIntraTransferTable.payeeAmountCents],
            note = row[PartgIntraTransferTable.note],
        )

    /** Kind, payer, payee: done in Kotlin so that H2 and PostgreSQL produce the same order. */
    fun sortTransfers(rows: List<TransferRow>): List<TransferRow> =
        rows.sortedWith(
            compareBy<TransferRow> {
                it.kind.ordinal
            }.thenBy { it.payerUnitKey }.thenBy { it.payeeUnitKey }.thenBy { it.id.toString() },
        )

    // ---------------------------------------------------------------- header

    fun findSheet(year: Int): SheetRow? =
        PartgConsolidationSheetTable
            .selectAll()
            .where { PartgConsolidationSheetTable.fiscalYear eq year }
            .singleOrNull()
            ?.let(::toSheetRow)

    /** Locks the header of [year] if it exists; `null` otherwise (nothing is created). */
    fun lockExistingSheet(year: Int): SheetRow? = lockSheet(year)

    private fun lockSheet(year: Int): SheetRow? =
        PartgConsolidationSheetTable
            .selectAll()
            .where { PartgConsolidationSheetTable.fiscalYear eq year }
            .forUpdate()
            .singleOrNull()
            ?.let(::toSheetRow)

    /** Locks the header of [year], creating it (revision 0) when it does not exist; the insert runs under a savepoint, only a unique violation is swallowed. */
    fun lockOrCreateSheet(
        year: Int,
        actor: Uuid,
        now: LocalDateTime,
    ): SheetRow {
        lockSheet(year)?.let { return it }
        try {
            withSavepoint(name = "partg_consolidation_create") {
                PartgConsolidationSheetTable.insert {
                    it[id] = Uuid.random()
                    it[fiscalYear] = year
                    it[revision] = 0L
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
        }
        return lockSheet(year) ?: error("partg consolidation sheet vanished after create")
    }

    fun bumpRevision(
        sheet: SheetRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgConsolidationSheetTable.update({ PartgConsolidationSheetTable.id eq sheet.id }) {
            it[revision] = sheet.revision + 1
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    // ---------------------------------------------------------------- transfers

    fun countTransfers(sheetId: Uuid): Int =
        PartgIntraTransferTable
            .selectAll()
            .where { PartgIntraTransferTable.consolidationId eq sheetId }
            .count()
            .toInt()

    /** The pair of this header only; a pair of another year is invisible. */
    fun findTransfer(
        sheetId: Uuid,
        transferId: Uuid,
    ): TransferRow? =
        PartgIntraTransferTable
            .selectAll()
            .where { (PartgIntraTransferTable.id eq transferId) and (PartgIntraTransferTable.consolidationId eq sheetId) }
            .singleOrNull()
            ?.let(::toTransferRow)

    /** The pair with this key under this header, if any (used to turn a unique violation into a business error without a failed statement). */
    fun findTransferByPair(
        sheetId: Uuid,
        kind: PartgTransferKind,
        payerUnitKey: String,
        payeeUnitKey: String,
    ): TransferRow? =
        PartgIntraTransferTable
            .selectAll()
            .where {
                (PartgIntraTransferTable.consolidationId eq sheetId) and
                    (PartgIntraTransferTable.transferKind eq kind) and
                    (PartgIntraTransferTable.payerUnitKey eq payerUnitKey) and
                    (PartgIntraTransferTable.payeeUnitKey eq payeeUnitKey)
            }.singleOrNull()
            ?.let(::toTransferRow)

    fun insertTransfer(
        sheetId: Uuid,
        transfer: TransferRow,
        payer: PartgUnit,
        payee: PartgUnit,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgIntraTransferTable.insert {
            it[id] = transfer.id
            it[consolidationId] = sheetId
            it[transferKind] = transfer.kind
            it[payerLevel] = payer.level
            it[payerChapterId] = payer.chapterId?.let(Uuid::parse)
            it[payerUnitKey] = payer.key()
            it[payeeLevel] = payee.level
            it[payeeChapterId] = payee.chapterId?.let(Uuid::parse)
            it[payeeUnitKey] = payee.key()
            it[payerAmountCents] = transfer.payerAmountCents
            it[payeeAmountCents] = transfer.payeeAmountCents
            it[note] = transfer.note
            it[createdAt] = now
            it[createdBy] = actor
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun updateTransfer(
        sheetId: Uuid,
        transfer: TransferRow,
        payer: PartgUnit,
        payee: PartgUnit,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgIntraTransferTable.update(
            { (PartgIntraTransferTable.id eq transfer.id) and (PartgIntraTransferTable.consolidationId eq sheetId) },
        ) {
            it[transferKind] = transfer.kind
            it[payerLevel] = payer.level
            it[payerChapterId] = payer.chapterId?.let(Uuid::parse)
            it[payerUnitKey] = payer.key()
            it[payeeLevel] = payee.level
            it[payeeChapterId] = payee.chapterId?.let(Uuid::parse)
            it[payeeUnitKey] = payee.key()
            it[payerAmountCents] = transfer.payerAmountCents
            it[payeeAmountCents] = transfer.payeeAmountCents
            it[note] = transfer.note
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun deleteTransfer(
        sheetId: Uuid,
        transferId: Uuid,
    ) {
        PartgIntraTransferTable.deleteWhere {
            (PartgIntraTransferTable.id eq transferId) and (PartgIntraTransferTable.consolidationId eq sheetId)
        }
    }

    /** `true` iff any pair names this chapter as payer or payee: the foreign key would block the delete of the chapter anyway. */
    fun hasTransferForChapter(chapterId: Uuid): Boolean =
        PartgIntraTransferTable
            .selectAll()
            .where { (PartgIntraTransferTable.payerChapterId eq chapterId) or (PartgIntraTransferTable.payeeChapterId eq chapterId) }
            .limit(1)
            .any()

    // ---------------------------------------------------------------- combinations

    fun countCombinations(sheetId: Uuid): Int =
        PartgDonorCombinationTable
            .selectAll()
            .where { PartgDonorCombinationTable.consolidationId eq sheetId }
            .count()
            .toInt()

    /** The donor ids of a group of this header only (sorted); `null` when the group does not belong to the header. */
    fun combinationDonorIds(
        sheetId: Uuid,
        combinationId: Uuid,
    ): List<Uuid>? {
        PartgDonorCombinationTable
            .selectAll()
            .where { (PartgDonorCombinationTable.id eq combinationId) and (PartgDonorCombinationTable.consolidationId eq sheetId) }
            .singleOrNull() ?: return null
        return PartgDonorCombinationRowTable
            .selectAll()
            .where { PartgDonorCombinationRowTable.combinationId eq combinationId }
            .map { it[PartgDonorCombinationRowTable.largeDonorId] }
            .sortedBy { it.toString() }
    }

    /** The subset of [donorIds] that already belongs to a group. */
    fun alreadyCombined(donorIds: Collection<Uuid>): Set<Uuid> =
        if (donorIds.isEmpty()) {
            emptySet()
        } else {
            PartgDonorCombinationRowTable
                .selectAll()
                .where { PartgDonorCombinationRowTable.largeDonorId inList donorIds.toList() }
                .map { it[PartgDonorCombinationRowTable.largeDonorId] }
                .toSet()
        }

    fun insertCombination(
        sheetId: Uuid,
        donorIds: List<Uuid>,
        actor: Uuid,
        now: LocalDateTime,
    ): Uuid {
        val combinationId = Uuid.random()
        PartgDonorCombinationTable.insert {
            it[id] = combinationId
            it[consolidationId] = sheetId
            it[createdAt] = now
            it[createdBy] = actor
        }
        donorIds.forEach { donorId ->
            PartgDonorCombinationRowTable.insert {
                it[id] = Uuid.random()
                it[PartgDonorCombinationRowTable.combinationId] = combinationId
                it[largeDonorId] = donorId
            }
        }
        return combinationId
    }

    /** Rows first, then the head (no cascade). */
    fun deleteCombination(
        sheetId: Uuid,
        combinationId: Uuid,
    ) {
        PartgDonorCombinationRowTable.deleteWhere { PartgDonorCombinationRowTable.combinationId eq combinationId }
        PartgDonorCombinationTable.deleteWhere {
            (PartgDonorCombinationTable.id eq combinationId) and (PartgDonorCombinationTable.consolidationId eq sheetId)
        }
    }

    /**
     * Called by the annex writer under ITS lock, before a large-donor row is deleted (there is no cascade): the row leaves its group. A group
     * that drops below two rows stays, is ignored by the computation and raises P21.
     */
    fun detachDonor(largeDonorId: Uuid) {
        PartgDonorCombinationRowTable.deleteWhere { PartgDonorCombinationRowTable.largeDonorId eq largeDonorId }
    }

    // ---------------------------------------------------------------- snapshot

    /** The revisions of the consolidation headers of [years], keyed `CONS:<year>`; one statement (part of the snapshot fence). */
    fun revisions(years: Collection<Int>): Map<String, Long> {
        if (years.isEmpty()) return emptyMap()
        return PartgConsolidationSheetTable
            .selectAll()
            .where { PartgConsolidationSheetTable.fiscalYear inList years.toList() }
            .associate { "CONS:${it[PartgConsolidationSheetTable.fiscalYear]}" to it[PartgConsolidationSheetTable.revision] }
    }

    /** All pairs and groups of [years]; one statement per table. The caller brackets it with [revisions] reads. */
    fun snapshot(years: Collection<Int>): Map<Int, ConsolidationData> {
        if (years.isEmpty()) return emptyMap()
        val sheets =
            PartgConsolidationSheetTable
                .selectAll()
                .where { PartgConsolidationSheetTable.fiscalYear inList years.toList() }
                .map(::toSheetRow)
        if (sheets.isEmpty()) return emptyMap()
        val sheetIds = sheets.map { it.id }
        val transfers =
            PartgIntraTransferTable
                .selectAll()
                .where { PartgIntraTransferTable.consolidationId inList sheetIds }
                .groupBy({ it[PartgIntraTransferTable.consolidationId] }, ::toTransferRow)
        val heads =
            PartgDonorCombinationTable
                .selectAll()
                .where { PartgDonorCombinationTable.consolidationId inList sheetIds }
                .toList()
        val rows =
            if (heads.isEmpty()) {
                emptyMap()
            } else {
                PartgDonorCombinationRowTable
                    .selectAll()
                    .where { PartgDonorCombinationRowTable.combinationId inList heads.map { it[PartgDonorCombinationTable.id] } }
                    .groupBy({ it[PartgDonorCombinationRowTable.combinationId] }, { it[PartgDonorCombinationRowTable.largeDonorId] })
            }
        val combinations =
            heads
                .groupBy { it[PartgDonorCombinationTable.consolidationId] }
                .mapValues { (_, list) ->
                    list
                        .map {
                            CombinationRow(
                                id = it[PartgDonorCombinationTable.id],
                                createdAt = it[PartgDonorCombinationTable.createdAt],
                                donorIds = rows[it[PartgDonorCombinationTable.id]].orEmpty().sortedBy { donor -> donor.toString() },
                            )
                        }.sortedWith(compareBy({ it.createdAt }, { it.id.toString() }))
                }
        return sheets.associate { sheet ->
            sheet.year to
                ConsolidationData(
                    revision = sheet.revision,
                    transfers = sortTransfers(transfers[sheet.id].orEmpty()),
                    combinations = combinations[sheet.id].orEmpty(),
                )
        }
    }
}
