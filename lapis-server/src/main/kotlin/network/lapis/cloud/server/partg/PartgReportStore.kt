package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.PartgOverviewRowDto
import network.lapis.cloud.shared.domain.PartgPositionDto
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- the ONLY reader and writer of `partg_report_position`, `partg_report_sheet` and `partg_report_value` (pinned by
 * `PartgReportStoreTripwireTest`; the one exception is `PartgReportPersonalData`, which exports who changed what).
 *
 * Every function runs inside the caller's transaction. Lock order for writers, always: regional chapter row, then the sheet of the OLDER year,
 * then the sheet of the younger year, audit last (`deleteChapter` locks the chapter row first as well).
 */
internal object PartgReportStore {
    /** The header of a sheet as read under the caller's lock. */
    data class SheetRow(
        val id: Uuid,
        val year: Int,
        val unitKey: String,
        val revision: Long,
        val updatedAt: LocalDateTime,
    )

    /** One effective change of a value; [oldCents] / [newCents] `null` mean "did not exist" / "deleted". */
    data class ValueDiff(
        val positionCode: String,
        val oldCents: Long?,
        val oldSource: PartgValueSource?,
        val newCents: Long?,
        val newSource: PartgValueSource?,
    )

    fun positions(): List<PartgPositionDto> =
        PartgReportPositionTable
            .selectAll()
            .orderBy(PartgReportPositionTable.sortOrder to SortOrder.ASC)
            .map {
                PartgPositionDto(
                    code = it[PartgReportPositionTable.code],
                    section = it[PartgReportPositionTable.section],
                    kind = it[PartgReportPositionTable.kind],
                    parentCode = it[PartgReportPositionTable.parentCode],
                    sortOrder = it[PartgReportPositionTable.sortOrder],
                    numberLabel = it[PartgReportPositionTable.numberLabel],
                    legalRef = it[PartgReportPositionTable.legalRef],
                    orgHint = it[PartgReportPositionTable.orgHint],
                )
            }

    /** The locked position row: its audit id and current hint. */
    data class PositionLock(
        val id: Uuid,
        val orgHint: String?,
    )

    /** Locks the position row; `null` for an unknown code. */
    fun lockPosition(code: String): PositionLock? =
        PartgReportPositionTable
            .selectAll()
            .where { PartgReportPositionTable.code eq code }
            .forUpdate()
            .singleOrNull()
            ?.let { PositionLock(id = it[PartgReportPositionTable.id], orgHint = it[PartgReportPositionTable.orgHint]) }

    fun setHint(
        code: String,
        hint: String?,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportPositionTable.update({ PartgReportPositionTable.code eq code }) {
            it[orgHint] = hint
            it[orgHintUpdatedAt] = now
            it[orgHintUpdatedBy] = actor
        }
    }

    /** Deterministic: the federal unit, then per chapter (by name key, then id) the state unit and the subordinate summary. */
    fun units(): List<PartgUnitDto> {
        val chapters =
            RegionalChapterTable
                .selectAll()
                .orderBy(RegionalChapterTable.nameKey to SortOrder.ASC, RegionalChapterTable.id to SortOrder.ASC)
                .map { it[RegionalChapterTable.id].toString() to it[RegionalChapterTable.name] }
        return buildList {
            add(PartgUnitDto(unitKey = PartgUnit.FEDERAL_KEY, level = PartgUnitLevel.FEDERAL))
            chapters.forEach { (id, name) ->
                add(PartgUnitDto(unitKey = PartgUnit.State(id).key(), level = PartgUnitLevel.STATE, chapterId = id, chapterName = name))
                add(
                    PartgUnitDto(
                        unitKey = PartgUnit.Subordinate(id).key(),
                        level = PartgUnitLevel.SUBORDINATE,
                        chapterId = id,
                        chapterName = name,
                    ),
                )
            }
        }
    }

    /** The unit as a DTO; throws [NotFoundException] for a chapter that does not exist (the chapter row is NOT locked). */
    fun unitDto(unit: PartgUnit): PartgUnitDto {
        val chapterId = unit.chapterId ?: return PartgUnitDto(unitKey = unit.key(), level = unit.level)
        val row =
            RegionalChapterTable
                .selectAll()
                .where { RegionalChapterTable.id eq Uuid.parse(chapterId) }
                .singleOrNull() ?: throw NotFoundException("Regional chapter not found")
        return PartgUnitDto(unitKey = unit.key(), level = unit.level, chapterId = chapterId, chapterName = row[RegionalChapterTable.name])
    }

    /** Locks the regional chapter row of a chapter unit (same lock as `deleteChapter`). */
    fun lockChapter(unit: PartgUnit) {
        val chapterId = unit.chapterId ?: return
        RegionalChapterTable
            .selectAll()
            .where { RegionalChapterTable.id eq Uuid.parse(chapterId) }
            .forUpdate()
            .singleOrNull() ?: throw NotFoundException("Regional chapter not found")
    }

    private fun toSheetRow(row: org.jetbrains.exposed.v1.core.ResultRow) =
        SheetRow(
            id = row[PartgReportSheetTable.id],
            year = row[PartgReportSheetTable.fiscalYear],
            unitKey = row[PartgReportSheetTable.unitKey],
            revision = row[PartgReportSheetTable.revision],
            updatedAt = row[PartgReportSheetTable.updatedAt],
        )

    /** Reads without a lock (for reading screens). */
    fun findSheet(
        year: Int,
        unit: PartgUnit,
    ): SheetRow? =
        PartgReportSheetTable
            .selectAll()
            .where { (PartgReportSheetTable.fiscalYear eq year) and (PartgReportSheetTable.unitKey eq unit.key()) }
            .singleOrNull()
            ?.let(::toSheetRow)

    /** `SELECT ... FOR UPDATE` on an existing sheet; `null` when there is none yet. */
    fun lockSheet(
        year: Int,
        unit: PartgUnit,
    ): SheetRow? =
        PartgReportSheetTable
            .selectAll()
            .where { (PartgReportSheetTable.fiscalYear eq year) and (PartgReportSheetTable.unitKey eq unit.key()) }
            .forUpdate()
            .singleOrNull()
            ?.let(::toSheetRow)

    /**
     * Locks the sheet, creating it (revision 0) when it does not exist. The insert runs under a savepoint: on a unique violation (a parallel
     * creator won) only the insert is rolled back, then the winner's row is locked and read. Any other SQL error is rethrown.
     */
    fun lockOrCreateSheet(
        year: Int,
        unit: PartgUnit,
        actor: Uuid,
        now: LocalDateTime,
    ): SheetRow {
        lockSheet(year = year, unit = unit)?.let { return it }
        try {
            withSavepoint(name = "partg_sheet_create") {
                PartgReportSheetTable.insert {
                    it[id] = Uuid.random()
                    it[fiscalYear] = year
                    it[unitLevel] = unit.level
                    it[regionalChapterId] = unit.chapterId?.let(Uuid::parse)
                    it[unitKey] = unit.key()
                    it[revision] = 0L
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
        }
        return lockSheet(year = year, unit = unit) ?: error("partg sheet vanished after create")
    }

    /** The typed-in cents per leaf code. */
    fun values(sheetId: Uuid): Map<String, Long> =
        PartgReportValueTable
            .selectAll()
            .where { PartgReportValueTable.sheetId eq sheetId }
            .orderBy(PartgReportValueTable.positionCode to SortOrder.ASC)
            .associate { it[PartgReportValueTable.positionCode] to it[PartgReportValueTable.amountCents] }

    /**
     * Applies [changes] (code -> cents, `null` deletes) to the locked [sheet] and bumps its revision once, only if something changed.
     * Returns the effective differences (empty = nothing changed, the revision stays).
     */
    fun applyChanges(
        sheet: SheetRow,
        changes: Map<String, Long?>,
        source: PartgValueSource,
        actor: Uuid,
        now: LocalDateTime,
    ): List<ValueDiff> {
        if (changes.isEmpty()) return emptyList()
        val existing =
            PartgReportValueTable
                .selectAll()
                .where { (PartgReportValueTable.sheetId eq sheet.id) and (PartgReportValueTable.positionCode inList changes.keys) }
                .associateBy { it[PartgReportValueTable.positionCode] }
        val diffs = mutableListOf<ValueDiff>()
        changes.entries.sortedBy { it.key }.forEach { (code, cents) ->
            val row = existing[code]
            val oldCents = row?.get(PartgReportValueTable.amountCents)
            val oldSource = row?.get(PartgReportValueTable.valueSource)
            when {
                cents == null && row != null -> {
                    PartgReportValueTable.deleteWhere { PartgReportValueTable.id eq row[PartgReportValueTable.id] }
                    diffs += ValueDiff(positionCode = code, oldCents = oldCents, oldSource = oldSource, newCents = null, newSource = null)
                }
                cents == null -> Unit
                row == null -> {
                    PartgReportValueTable.insert {
                        it[id] = Uuid.random()
                        it[sheetId] = sheet.id
                        it[positionCode] = code
                        it[amountCents] = cents
                        it[valueSource] = source
                        it[updatedAt] = now
                        it[updatedBy] = actor
                    }
                    diffs += ValueDiff(positionCode = code, oldCents = null, oldSource = null, newCents = cents, newSource = source)
                }
                oldCents != cents -> {
                    PartgReportValueTable.update({ PartgReportValueTable.id eq row[PartgReportValueTable.id] }) {
                        it[amountCents] = cents
                        it[valueSource] = source
                        it[updatedAt] = now
                        it[updatedBy] = actor
                    }
                    diffs +=
                        ValueDiff(positionCode = code, oldCents = oldCents, oldSource = oldSource, newCents = cents, newSource = source)
                }
            }
        }
        if (diffs.isNotEmpty()) {
            PartgReportSheetTable.update({ PartgReportSheetTable.id eq sheet.id }) {
                it[revision] = sheet.revision + 1
                it[updatedAt] = now
                it[updatedBy] = actor
            }
        }
        return diffs
    }

    /** Revision of a sheet after [applyChanges] (re-read, so the caller does not guess). */
    fun revisionOf(sheetId: Uuid): Long =
        PartgReportSheetTable
            .selectAll()
            .where { PartgReportSheetTable.id eq sheetId }
            .single()[PartgReportSheetTable.revision]

    /** One entry per unit with the filled count and the three totals; `null` totals mean nothing was entered. */
    fun overview(year: Int): List<PartgOverviewRowDto> {
        val units = units()
        val sheets =
            PartgReportSheetTable
                .selectAll()
                .where { PartgReportSheetTable.fiscalYear eq year }
                .map(::toSheetRow)
                .associateBy { it.unitKey }
        val valuesBySheet: Map<Uuid, Map<String, Long>> =
            if (sheets.isEmpty()) {
                emptyMap()
            } else {
                PartgReportValueTable
                    .selectAll()
                    .where { PartgReportValueTable.sheetId inList sheets.values.map { it.id } }
                    .orderBy(PartgReportValueTable.sheetId to SortOrder.ASC, PartgReportValueTable.positionCode to SortOrder.ASC)
                    .groupBy({ it[PartgReportValueTable.sheetId] }, {
                        it[PartgReportValueTable.positionCode] to
                            it[PartgReportValueTable.amountCents]
                    })
                    .mapValues { it.value.toMap() }
            }
        return units.map { unit ->
            val sheet = sheets[unit.unitKey]
            val values = sheet?.let { valuesBySheet[it.id] }.orEmpty()
            if (values.isEmpty()) {
                PartgOverviewRowDto(unit = unit, filledCount = 0, leafCount = PartgPositions.LEAF_COUNT, updatedAt = sheet?.updatedAt)
            } else {
                val sums = PartgPositions.sums(values)
                PartgOverviewRowDto(
                    unit = unit,
                    filledCount = values.size,
                    leafCount = PartgPositions.LEAF_COUNT,
                    incomeTotalCents = sums.getValue(PartgPositions.INCOME_TOTAL),
                    expenseTotalCents = sums.getValue(PartgPositions.EXPENSE_TOTAL),
                    netAssetsCents = sums.getValue(PartgPositions.NET),
                    updatedAt = sheet?.updatedAt,
                )
            }
        }
    }

    /** `true` iff any sheet (even an empty header) of this chapter exists: the foreign key would block the delete anyway. */
    fun hasValuesForChapter(chapterId: Uuid): Boolean =
        PartgReportSheetTable
            .selectAll()
            .where { PartgReportSheetTable.regionalChapterId eq chapterId }
            .limit(1)
            .any()
}
