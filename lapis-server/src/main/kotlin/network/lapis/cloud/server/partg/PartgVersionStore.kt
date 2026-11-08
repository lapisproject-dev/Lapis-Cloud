package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgReportDeadlineTable
import network.lapis.cloud.server.db.generated.PartgReportDeclarationTable
import network.lapis.cloud.server.db.generated.PartgReportVersionCounterTable
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.PartgVersionStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- the ONLY reader and writer of `partg_report_version_counter`, `partg_report_version`, `partg_report_declaration` and
 * `partg_report_deadline` (pinned by `PartgVersionStoreTripwireTest`; the one exception is the data subject contributor `PartgReportPersonalData`).
 *
 * Every function runs inside the caller's transaction. IMMUTABILITY BY CONSTRUCTION: there is no function that updates the content, the checksum,
 * the number, the year, the scope or the keeping stamp of a version. The only UPDATE on a version is [markFinal], which sets the finalisation pair
 * once (`WHERE finalized_at IS NULL`). A version is removed only by [deleteVersions] (the ADMIN routine); its counter row is never removed, so a
 * number is never given twice. Lock order for writers, always: counter rows (sorted by year, scope), then the version row.
 * Nothing here logs; the comment is free text and is never put into an exception.
 */
internal object PartgVersionStore {
    data class CounterRow(
        val id: Uuid,
        val year: Int,
        val scopeKey: String,
        val lastNumber: Int,
    )

    /** One version WITHOUT its content (the JSON is up to 4 MiB and is read by [content] only where it is needed). */
    data class VersionMeta(
        val id: Uuid,
        val counterId: Uuid,
        val year: Int,
        val scopeKey: String,
        val versionNumber: Int,
        val formatVersion: Int,
        val contentHash: String,
        val byteSize: Int,
        val hasThirdPartyData: Boolean,
        val comment: String?,
        val frozenAt: LocalDateTime,
        val frozenBy: Uuid,
        val finalizedAt: LocalDateTime?,
        val finalizedBy: Uuid?,
    )

    data class DeclarationRow(
        val id: Uuid,
        val versionId: Uuid,
        val templateCode: String,
        val contentHash: String,
        val declaredAt: LocalDateTime,
        val declaredBy: Uuid,
    )

    data class DeadlineRow(
        val id: Uuid,
        val year: Int,
        val deadline: LocalDate,
        val confirmedAt: LocalDateTime,
        val confirmedBy: Uuid,
    )

    private val metaColumns =
        listOf(
            PartgReportVersionTable.id,
            PartgReportVersionTable.counterId,
            PartgReportVersionTable.fiscalYear,
            PartgReportVersionTable.scopeKey,
            PartgReportVersionTable.versionNumber,
            PartgReportVersionTable.formatVersion,
            PartgReportVersionTable.contentHash,
            PartgReportVersionTable.byteSize,
            PartgReportVersionTable.hasThirdPartyData,
            PartgReportVersionTable.comment,
            PartgReportVersionTable.frozenAt,
            PartgReportVersionTable.frozenBy,
            PartgReportVersionTable.finalizedAt,
            PartgReportVersionTable.finalizedBy,
        )

    private fun toCounter(row: ResultRow) =
        CounterRow(
            id = row[PartgReportVersionCounterTable.id],
            year = row[PartgReportVersionCounterTable.fiscalYear],
            scopeKey = row[PartgReportVersionCounterTable.scopeKey],
            lastNumber = row[PartgReportVersionCounterTable.lastNumber],
        )

    private fun toMeta(row: ResultRow) =
        VersionMeta(
            id = row[PartgReportVersionTable.id],
            counterId = row[PartgReportVersionTable.counterId],
            year = row[PartgReportVersionTable.fiscalYear],
            scopeKey = row[PartgReportVersionTable.scopeKey],
            versionNumber = row[PartgReportVersionTable.versionNumber],
            formatVersion = row[PartgReportVersionTable.formatVersion],
            contentHash = row[PartgReportVersionTable.contentHash],
            byteSize = row[PartgReportVersionTable.byteSize],
            hasThirdPartyData = row[PartgReportVersionTable.hasThirdPartyData],
            comment = row[PartgReportVersionTable.comment],
            frozenAt = row[PartgReportVersionTable.frozenAt],
            frozenBy = row[PartgReportVersionTable.frozenBy],
            finalizedAt = row[PartgReportVersionTable.finalizedAt],
            finalizedBy = row[PartgReportVersionTable.finalizedBy],
        )

    private fun toDeclaration(row: ResultRow) =
        DeclarationRow(
            id = row[PartgReportDeclarationTable.id],
            versionId = row[PartgReportDeclarationTable.versionId],
            templateCode = row[PartgReportDeclarationTable.templateCode],
            contentHash = row[PartgReportDeclarationTable.contentHash],
            declaredAt = row[PartgReportDeclarationTable.declaredAt],
            declaredBy = row[PartgReportDeclarationTable.declaredBy],
        )

    private fun toDeadline(row: ResultRow) =
        DeadlineRow(
            id = row[PartgReportDeadlineTable.id],
            year = row[PartgReportDeadlineTable.fiscalYear],
            deadline = row[PartgReportDeadlineTable.deadline],
            confirmedAt = row[PartgReportDeadlineTable.confirmedAt],
            confirmedBy = row[PartgReportDeadlineTable.confirmedBy],
        )

    // ---------------------------------------------------------------- counter (lock row + number source)

    /** Locks the counter of (year, scope); `null` if there is none (nothing is created). */
    fun lockExistingCounter(
        year: Int,
        scopeKey: String,
    ): CounterRow? =
        PartgReportVersionCounterTable
            .selectAll()
            .where { (PartgReportVersionCounterTable.fiscalYear eq year) and (PartgReportVersionCounterTable.scopeKey eq scopeKey) }
            .forUpdate()
            .singleOrNull()
            ?.let(::toCounter)

    /** Locks the counter, creating it (number 0) when it does not exist; the insert runs under a savepoint, only a unique violation is swallowed. */
    fun lockCounter(
        year: Int,
        scopeKey: String,
        actor: Uuid,
        now: LocalDateTime,
    ): CounterRow {
        lockExistingCounter(year = year, scopeKey = scopeKey)?.let { return it }
        try {
            withSavepoint(name = "partg_version_counter_create") {
                PartgReportVersionCounterTable.insert {
                    it[id] = Uuid.random()
                    it[fiscalYear] = year
                    it[PartgReportVersionCounterTable.scopeKey] = scopeKey
                    it[lastNumber] = 0
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
        }
        return lockExistingCounter(year = year, scopeKey = scopeKey) ?: error("partg version counter vanished after create")
    }

    /** Locks EVERY counter row in the fixed order (year, scope): the deletion routine's first step. */
    fun lockAllCountersSorted(): List<CounterRow> =
        PartgReportVersionCounterTable
            .selectAll()
            .orderBy(PartgReportVersionCounterTable.fiscalYear to SortOrder.ASC, PartgReportVersionCounterTable.scopeKey to SortOrder.ASC)
            .forUpdate()
            .map(::toCounter)

    fun bumpCounter(
        counter: CounterRow,
        number: Int,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportVersionCounterTable.update({ PartgReportVersionCounterTable.id eq counter.id }) {
            it[lastNumber] = number
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    // ---------------------------------------------------------------- versions

    fun countVersions(counterId: Uuid): Int =
        PartgReportVersionTable
            .selectAll()
            .where { PartgReportVersionTable.counterId eq counterId }
            .count()
            .toInt()

    /** The newest version (highest number) of (year, scope), without content. */
    fun latest(
        year: Int,
        scopeKey: String,
    ): VersionMeta? =
        PartgReportVersionTable
            .select(metaColumns)
            .where { (PartgReportVersionTable.fiscalYear eq year) and (PartgReportVersionTable.scopeKey eq scopeKey) }
            .orderBy(PartgReportVersionTable.versionNumber to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.let(::toMeta)

    @Suppress("LongParameterList")
    fun insertVersion(
        counter: CounterRow,
        number: Int,
        formatVersion: Int,
        contentJson: String,
        contentHash: String,
        byteSize: Int,
        hasThirdPartyData: Boolean,
        comment: String?,
        actor: Uuid,
        now: LocalDateTime,
    ): Uuid {
        val newId = Uuid.random()
        PartgReportVersionTable.insert {
            it[id] = newId
            it[counterId] = counter.id
            it[fiscalYear] = counter.year
            it[scopeKey] = counter.scopeKey
            it[versionNumber] = number
            it[PartgReportVersionTable.formatVersion] = formatVersion
            it[PartgReportVersionTable.contentJson] = contentJson
            it[PartgReportVersionTable.contentHash] = contentHash
            it[PartgReportVersionTable.byteSize] = byteSize
            it[PartgReportVersionTable.hasThirdPartyData] = hasThirdPartyData
            it[PartgReportVersionTable.comment] = comment
            it[frozenAt] = now
            it[frozenBy] = actor
            it[finalizedAt] = null
            it[finalizedBy] = null
        }
        return newId
    }

    /** All versions of [year] without content, newest first inside each scope; the caller sorts for display. */
    fun versions(year: Int): List<VersionMeta> =
        PartgReportVersionTable
            .select(metaColumns)
            .where { PartgReportVersionTable.fiscalYear eq year }
            .map(::toMeta)

    fun versionMeta(id: Uuid): VersionMeta? =
        PartgReportVersionTable
            .select(metaColumns)
            .where { PartgReportVersionTable.id eq id }
            .singleOrNull()
            ?.let(::toMeta)

    fun lockVersion(id: Uuid): VersionMeta? =
        PartgReportVersionTable
            .select(metaColumns)
            .where { PartgReportVersionTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.let(::toMeta)

    /** The stored text and the stored checksum (in this order) of one version; `null` for an unknown id. */
    fun content(id: Uuid): Pair<String, String>? =
        PartgReportVersionTable
            .select(PartgReportVersionTable.contentJson, PartgReportVersionTable.contentHash)
            .where { PartgReportVersionTable.id eq id }
            .singleOrNull()
            ?.let { it[PartgReportVersionTable.contentJson] to it[PartgReportVersionTable.contentHash] }

    /** The one and only update of a version: sets the finalisation pair once. `false` = it was already final (or gone). */
    fun markFinal(
        id: Uuid,
        actor: Uuid,
        now: LocalDateTime,
    ): Boolean =
        PartgReportVersionTable.update(
            { (PartgReportVersionTable.id eq id) and PartgReportVersionTable.finalizedAt.isNull() },
        ) {
            it[finalizedAt] = now
            it[finalizedBy] = actor
        } == 1

    // ---------------------------------------------------------------- declarations

    fun insertDeclaration(
        versionId: Uuid,
        templateCode: String,
        contentHash: String,
        actor: Uuid,
        now: LocalDateTime,
    ): Uuid {
        val newId = Uuid.random()
        PartgReportDeclarationTable.insert {
            it[id] = newId
            it[PartgReportDeclarationTable.versionId] = versionId
            it[PartgReportDeclarationTable.templateCode] = templateCode
            it[PartgReportDeclarationTable.contentHash] = contentHash
            it[declaredAt] = now
            it[declaredBy] = actor
        }
        return newId
    }

    fun declaration(versionId: Uuid): DeclarationRow? =
        PartgReportDeclarationTable
            .selectAll()
            .where { PartgReportDeclarationTable.versionId eq versionId }
            .singleOrNull()
            ?.let(::toDeclaration)

    fun declarations(versionIds: Collection<Uuid>): Map<Uuid, DeclarationRow> =
        if (versionIds.isEmpty()) {
            emptyMap()
        } else {
            PartgReportDeclarationTable
                .selectAll()
                .where { PartgReportDeclarationTable.versionId inList versionIds.toList() }
                .map(::toDeclaration)
                .associateBy { it.versionId }
        }

    // ---------------------------------------------------------------- deadline

    fun deadline(year: Int): DeadlineRow? =
        PartgReportDeadlineTable
            .selectAll()
            .where { PartgReportDeadlineTable.fiscalYear eq year }
            .singleOrNull()
            ?.let(::toDeadline)

    fun deadlines(years: Collection<Int>): Map<Int, DeadlineRow> =
        if (years.isEmpty()) {
            emptyMap()
        } else {
            PartgReportDeadlineTable
                .selectAll()
                .where { PartgReportDeadlineTable.fiscalYear inList years.toList() }
                .map(::toDeadline)
                .associateBy { it.year }
        }

    /** Sets the confirmed deadline of [year] (insert under a savepoint; a concurrent insert falls back to the update). Returns the old date, if any. */
    fun upsertDeadline(
        year: Int,
        deadline: LocalDate,
        actor: Uuid,
        now: LocalDateTime,
    ): LocalDate? {
        val existing = deadline(year)
        if (existing == null) {
            try {
                withSavepoint(name = "partg_deadline_create") {
                    PartgReportDeadlineTable.insert {
                        it[id] = Uuid.random()
                        it[fiscalYear] = year
                        it[PartgReportDeadlineTable.deadline] = deadline
                        it[confirmedAt] = now
                        it[confirmedBy] = actor
                    }
                }
                return null
            } catch (e: Exception) {
                if (!e.isUniqueViolation()) throw e
            }
        }
        val old = deadline(year)?.deadline
        PartgReportDeadlineTable.update({ PartgReportDeadlineTable.fiscalYear eq year }) {
            it[PartgReportDeadlineTable.deadline] = deadline
            it[confirmedAt] = now
            it[confirmedBy] = actor
        }
        return old
    }

    // ---------------------------------------------------------------- status / dashboard

    /** The status of every kept version of [years], whole-party versions only, as (year, status). */
    fun wholePartyStatuses(years: Collection<Int>): Map<Int, List<PartgVersionStatus>> {
        if (years.isEmpty()) return emptyMap()
        val metas =
            PartgReportVersionTable
                .select(metaColumns)
                .where {
                    (PartgReportVersionTable.fiscalYear inList years.toList()) and
                        (PartgReportVersionTable.scopeKey eq WHOLE_PARTY_KEY)
                }.map(::toMeta)
        val declared = declarations(metas.map { it.id }).keys
        return metas.groupBy({ it.year }) { statusOf(meta = it, declared = it.id in declared) }
    }

    /** Years from [minYear] up with at least one version or a deadline row. */
    fun yearsWithActivity(minYear: Int): Set<Int> =
        PartgReportVersionCounterTable
            .select(PartgReportVersionCounterTable.fiscalYear)
            .where { PartgReportVersionCounterTable.fiscalYear greaterEq minYear }
            .map { it[PartgReportVersionCounterTable.fiscalYear] }
            .toSet() +
            PartgReportDeadlineTable
                .select(PartgReportDeadlineTable.fiscalYear)
                .where { PartgReportDeadlineTable.fiscalYear greaterEq minYear }
                .map { it[PartgReportDeadlineTable.fiscalYear] }
                .toSet()

    fun statusOf(
        meta: VersionMeta,
        declared: Boolean,
    ): PartgVersionStatus =
        when {
            meta.finalizedAt != null -> PartgVersionStatus.FINAL
            declared -> PartgVersionStatus.DECLARED
            else -> PartgVersionStatus.FROZEN
        }

    // ---------------------------------------------------------------- deletion routine

    /** Every version with a report year BEFORE [beforeYear], without content, in a fixed order (year, scope, number). */
    fun versionsBefore(beforeYear: Int): List<VersionMeta> =
        PartgReportVersionTable
            .select(metaColumns)
            .where { PartgReportVersionTable.fiscalYear less beforeYear }
            .map(::toMeta)
            .sortedWith(compareBy<VersionMeta> { it.year }.thenBy { it.scopeKey }.thenBy { it.versionNumber })

    /** Removes the declarations first (there is no cascade), then the versions. The counter rows stay. */
    fun deleteVersions(ids: Collection<Uuid>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        PartgReportDeclarationTable.deleteWhere { PartgReportDeclarationTable.versionId inList list }
        PartgReportVersionTable.deleteWhere { PartgReportVersionTable.id inList list }
    }

    const val WHOLE_PARTY_KEY = "ALL"
}
