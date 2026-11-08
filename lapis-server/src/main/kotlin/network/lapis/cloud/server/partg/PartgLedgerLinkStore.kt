package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgAccountMappingTable
import network.lapis.cloud.server.db.generated.PartgReportUnitModeTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- the ONLY reader and writer of `partg_account_mapping` and `partg_report_unit_mode` (pinned by `PartgLedgerLinkStoreTripwireTest`;
 * the one exception is `PartgReportPersonalData`, which exports who changed what). Every function runs inside the caller's transaction; the
 * callers serialise writers (the organisation settings row for the mapping, the chapter row or the settings row for a mode).
 */
internal object PartgLedgerLinkStore {
    /** One stored mapping: [positionCode] is `null` iff [excluded]. */
    data class MappingRow(
        val id: Uuid,
        val ledgerAccountId: Uuid,
        val positionCode: String?,
        val excluded: Boolean,
    )

    fun mappings(): Map<Uuid, MappingRow> =
        PartgAccountMappingTable.selectAll().associate {
            val accountId = it[PartgAccountMappingTable.ledgerAccountId]
            accountId to
                MappingRow(
                    id = it[PartgAccountMappingTable.id],
                    ledgerAccountId = accountId,
                    positionCode = it[PartgAccountMappingTable.positionCode],
                    excluded = it[PartgAccountMappingTable.excluded],
                )
        }

    /** Sets (inserts or replaces) the mapping of [accountId]. Returns the id of the row. */
    fun upsert(
        accountId: Uuid,
        positionCode: String?,
        excluded: Boolean,
        actor: Uuid,
        now: LocalDateTime,
    ): Uuid {
        val existing =
            PartgAccountMappingTable
                .selectAll()
                .where { PartgAccountMappingTable.ledgerAccountId eq accountId }
                .singleOrNull()
        if (existing != null) {
            PartgAccountMappingTable.update({ PartgAccountMappingTable.id eq existing[PartgAccountMappingTable.id] }) {
                it[PartgAccountMappingTable.positionCode] = positionCode
                it[PartgAccountMappingTable.excluded] = excluded
                it[updatedAt] = now
                it[updatedBy] = actor
            }
            return existing[PartgAccountMappingTable.id]
        }
        val id = Uuid.random()
        PartgAccountMappingTable.insert {
            it[PartgAccountMappingTable.id] = id
            it[ledgerAccountId] = accountId
            it[PartgAccountMappingTable.positionCode] = positionCode
            it[PartgAccountMappingTable.excluded] = excluded
            it[updatedAt] = now
            it[updatedBy] = actor
        }
        return id
    }

    fun delete(accountId: Uuid) {
        PartgAccountMappingTable.deleteWhere { ledgerAccountId eq accountId }
    }

    /** The mode of every unit that has a stored row for [year], keyed by unit key. A missing unit is mode C. */
    fun modes(year: Int): Map<String, PartgBookkeepingMode> =
        PartgReportUnitModeTable
            .selectAll()
            .where { PartgReportUnitModeTable.fiscalYear eq year }
            .associate { it[PartgReportUnitModeTable.unitKey] to it[PartgReportUnitModeTable.bookkeepingMode] }

    /** The mode of [unit] and [year]; C when nothing is stored (and always C for a subordinate summary). */
    fun modeOf(
        year: Int,
        unit: PartgUnit,
    ): PartgBookkeepingMode =
        PartgReportUnitModeTable
            .selectAll()
            .where { (PartgReportUnitModeTable.fiscalYear eq year) and (PartgReportUnitModeTable.unitKey eq unit.key()) }
            .singleOrNull()
            ?.get(PartgReportUnitModeTable.bookkeepingMode) ?: PartgBookkeepingMode.C

    /**
     * Sets the mode of a FEDERAL or STATE [unit]. The caller holds the lock of the chapter row (or of the settings row for the federal unit), so
     * the only parallel creator is excluded; the insert still runs under a savepoint and a unique violation falls back to an update.
     */
    fun setMode(
        year: Int,
        unit: PartgUnit,
        mode: PartgBookkeepingMode,
        actor: Uuid,
        now: LocalDateTime,
    ): Uuid {
        fun existingId(): Uuid? =
            PartgReportUnitModeTable
                .selectAll()
                .where { (PartgReportUnitModeTable.fiscalYear eq year) and (PartgReportUnitModeTable.unitKey eq unit.key()) }
                .singleOrNull()
                ?.get(PartgReportUnitModeTable.id)

        fun update(rowId: Uuid) {
            PartgReportUnitModeTable.update({ PartgReportUnitModeTable.id eq rowId }) {
                it[bookkeepingMode] = mode
                it[updatedAt] = now
                it[updatedBy] = actor
            }
        }
        existingId()?.let {
            update(it)
            return it
        }
        val newId = Uuid.random()
        try {
            withSavepoint(name = "partg_unit_mode_create") {
                PartgReportUnitModeTable.insert {
                    it[id] = newId
                    it[fiscalYear] = year
                    it[unitKey] = unit.key()
                    it[regionalChapterId] = unit.chapterId?.let(Uuid::parse)
                    it[bookkeepingMode] = mode
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            }
            return newId
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
            val winner = existingId() ?: throw e
            update(winner)
            return winner
        }
    }

    /** `true` iff a mode row (any year) of this chapter exists: the foreign key would block the delete anyway. */
    fun hasUnitModeForChapter(chapterId: Uuid): Boolean =
        PartgReportUnitModeTable
            .selectAll()
            .where { PartgReportUnitModeTable.regionalChapterId eq chapterId }
            .limit(1)
            .any()
}
