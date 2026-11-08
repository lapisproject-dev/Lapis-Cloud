package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportHoldingTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PartgReportNoteTable
import network.lapis.cloud.server.db.generated.PartgReportNotesSheetTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.269 (R3) -- the ONLY reader and writer of `partg_report_annex`, `partg_report_large_donor`, `partg_report_notes_sheet`,
 * `partg_report_note` and `partg_report_holding` (pinned by `PartgAnnexStoreTripwireTest`; the one exception is `PartgReportPersonalData`).
 *
 * Every function runs inside the caller's transaction. Lock order for writers, always: regional chapter row (`PartgReportStore.lockChapter`),
 * then the annex (or notes) header row, audit last. Every write of a child row bumps the revision of its header in the same transaction.
 * Donor rows are PERSONAL DATA OF THIRD PARTIES: this object never logs and never puts a field into an exception.
 */
internal object PartgAnnexStore {
    data class AnnexRow(
        val id: Uuid,
        val year: Int,
        val unitKey: String,
        val revision: Long,
        val deductions: Map<PartgDeduction, Long?>,
        val memberCount: Int?,
    )

    data class DonorRow(
        val id: Uuid,
        val annexId: Uuid,
        val personKind: PartgPersonKind,
        val name: String,
        val street: String?,
        val postalCode: String?,
        val city: String?,
        val country: String,
        val contributionKind: PartgContributionKind,
        val amountCents: Long,
        /** V1.9.290 (R5): when the row was last changed (`null` for rows built by hand in tests); P21 compares it with the age of a combination. */
        val updatedAt: LocalDateTime? = null,
        /** V1.9.293 (R5b): the donor key `M:<uuid>` / `X:<uuid>` of the booking donor this row was taken from or linked to; `null` = by hand. */
        val sourceDonorKey: String? = null,
        /** V1.9.293 (R5b): when the row was last written FROM the bookings; a later [updatedAt] means "changed afterwards". */
        val sourceAppliedAt: LocalDateTime? = null,
    )

    data class NotesRow(
        val id: Uuid,
        val year: Int,
        val revision: Long,
    )

    /** Everything of one unit and year as read in a snapshot. */
    data class AnnexData(
        val revision: Long,
        val deductions: Map<PartgDeduction, Long?>,
        val memberCount: Int?,
        /** Sorted like [sortDonors]. */
        val donors: List<DonorRow>,
        /** V1.9.283: sorted like [PartgStatementStore.sortSponsors]. */
        val sponsors: List<PartgStatementStore.SponsorRow> = emptyList(),
        /** V1.9.283: sorted like [PartgStatementStore.sortYouthOrgs]. */
        val youthOrgs: List<PartgStatementStore.YouthOrgRow> = emptyList(),
        /** V1.9.287 (R3c): statement D, sorted like [PartgStatementStore.sortEnterprises]. */
        val enterprises: List<PartgStatementStore.EnterpriseRow> = emptyList(),
    )

    data class NotesData(
        val revision: Long,
        /** Always five, in the order of [PartgNoteBlock]. */
        val notes: List<PartgNoteDto>,
        val holdings: List<PartgHoldingDto>,
    )

    class AnnexSnapshot(
        val annex: Map<Int, Map<String, AnnexData>>,
        val notes: Map<Int, NotesData>,
    )

    /** Amount descending, name, id. Done in Kotlin (not in SQL) so that H2 and PostgreSQL produce the same order. */
    fun sortDonors(donors: List<DonorRow>): List<DonorRow> =
        donors.sortedWith(
            compareByDescending<DonorRow> { it.amountCents }
                .thenBy { it.name }
                .thenBy { it.id.toString() },
        )

    private fun toAnnexRow(row: ResultRow) =
        AnnexRow(
            id = row[PartgReportAnnexTable.id],
            year = row[PartgReportAnnexTable.fiscalYear],
            unitKey = row[PartgReportAnnexTable.unitKey],
            revision = row[PartgReportAnnexTable.revision],
            deductions = deductionsOf(row),
            memberCount = row[PartgReportAnnexTable.memberCount],
        )

    private fun deductionsOf(row: ResultRow): Map<PartgDeduction, Long?> =
        linkedMapOf(
            PartgDeduction.UNASSIGNABLE to row[PartgReportAnnexTable.deductionUnassignableCents],
            PartgDeduction.CASH_OVER_1000 to row[PartgReportAnnexTable.deductionCashOver1000Cents],
            PartgDeduction.OVER_3300 to row[PartgReportAnnexTable.deductionOver3300Cents],
            PartgDeduction.WRONGLY_REPORTED to row[PartgReportAnnexTable.deductionWronglyReportedCents],
        )

    private fun toDonorRow(row: ResultRow) =
        DonorRow(
            id = row[PartgReportLargeDonorTable.id],
            annexId = row[PartgReportLargeDonorTable.annexId],
            personKind = row[PartgReportLargeDonorTable.personKind],
            name = row[PartgReportLargeDonorTable.name],
            street = row[PartgReportLargeDonorTable.street],
            postalCode = row[PartgReportLargeDonorTable.postalCode],
            city = row[PartgReportLargeDonorTable.city],
            country = row[PartgReportLargeDonorTable.country],
            contributionKind = row[PartgReportLargeDonorTable.contributionKind],
            amountCents = row[PartgReportLargeDonorTable.amountCents],
            updatedAt = row[PartgReportLargeDonorTable.updatedAt],
            sourceDonorKey = row[PartgReportLargeDonorTable.sourceDonorKey],
            sourceAppliedAt = row[PartgReportLargeDonorTable.sourceAppliedAt],
        )

    // ---------------------------------------------------------------- annex

    /** Reads without a lock. */
    fun findAnnex(
        year: Int,
        unit: PartgUnit,
    ): AnnexRow? =
        PartgReportAnnexTable
            .selectAll()
            .where { (PartgReportAnnexTable.fiscalYear eq year) and (PartgReportAnnexTable.unitKey eq unit.key()) }
            .singleOrNull()
            ?.let(::toAnnexRow)

    /** `SELECT ... FOR UPDATE` on an existing annex; `null` when there is none yet. */
    fun lockAnnex(
        year: Int,
        unit: PartgUnit,
    ): AnnexRow? =
        PartgReportAnnexTable
            .selectAll()
            .where { (PartgReportAnnexTable.fiscalYear eq year) and (PartgReportAnnexTable.unitKey eq unit.key()) }
            .forUpdate()
            .singleOrNull()
            ?.let(::toAnnexRow)

    /** Locks the annex, creating it (revision 0) when it does not exist; the insert runs under a savepoint and only a unique violation is swallowed. */
    fun lockOrCreateAnnex(
        year: Int,
        unit: PartgUnit,
        actor: Uuid,
        now: LocalDateTime,
    ): AnnexRow {
        lockAnnex(year = year, unit = unit)?.let { return it }
        try {
            withSavepoint(name = "partg_annex_create") {
                PartgReportAnnexTable.insert {
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
        return lockAnnex(year = year, unit = unit) ?: error("partg annex vanished after create")
    }

    fun bumpAnnexRevision(
        annex: AnnexRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportAnnexTable.update({ PartgReportAnnexTable.id eq annex.id }) {
            it[revision] = annex.revision + 1
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun revisionOfAnnex(annexId: Uuid): Long =
        PartgReportAnnexTable.selectAll().where { PartgReportAnnexTable.id eq annexId }.single()[PartgReportAnnexTable.revision]

    /** Writes the four deductions and the member count (the revision is bumped by the caller). */
    fun setFigures(
        annexId: Uuid,
        deductions: Map<PartgDeduction, Long?>,
        memberCount: Int?,
    ) {
        PartgReportAnnexTable.update({ PartgReportAnnexTable.id eq annexId }) {
            it[deductionUnassignableCents] = deductions[PartgDeduction.UNASSIGNABLE]
            it[deductionCashOver1000Cents] = deductions[PartgDeduction.CASH_OVER_1000]
            it[deductionOver3300Cents] = deductions[PartgDeduction.OVER_3300]
            it[deductionWronglyReportedCents] = deductions[PartgDeduction.WRONGLY_REPORTED]
            it[PartgReportAnnexTable.memberCount] = memberCount
        }
    }

    /** `true` iff any annex (even an empty header) of this chapter exists: the foreign key would block the delete anyway. */
    fun hasAnnexForChapter(chapterId: Uuid): Boolean =
        PartgReportAnnexTable
            .selectAll()
            .where { PartgReportAnnexTable.regionalChapterId eq chapterId }
            .limit(1)
            .any()

    // ---------------------------------------------------------------- donors

    fun donors(annexId: Uuid): List<DonorRow> =
        sortDonors(
            PartgReportLargeDonorTable
                .selectAll()
                .where { PartgReportLargeDonorTable.annexId eq annexId }
                .map(::toDonorRow),
        )

    fun donorCount(annexId: Uuid): Int =
        PartgReportLargeDonorTable
            .selectAll()
            .where { PartgReportLargeDonorTable.annexId eq annexId }
            .count()
            .toInt()

    /** The donor row of this annex only; a row of another annex (year or unit) is invisible. */
    fun findDonor(
        annexId: Uuid,
        donorId: Uuid,
    ): DonorRow? =
        PartgReportLargeDonorTable
            .selectAll()
            .where { (PartgReportLargeDonorTable.id eq donorId) and (PartgReportLargeDonorTable.annexId eq annexId) }
            .singleOrNull()
            ?.let(::toDonorRow)

    fun insertDonor(
        annexId: Uuid,
        donor: DonorRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportLargeDonorTable.insert {
            it[id] = donor.id
            it[PartgReportLargeDonorTable.annexId] = annexId
            it[personKind] = donor.personKind
            it[name] = donor.name
            it[street] = donor.street
            it[postalCode] = donor.postalCode
            it[city] = donor.city
            it[country] = donor.country
            it[contributionKind] = donor.contributionKind
            it[amountCents] = donor.amountCents
            it[createdAt] = now
            it[createdBy] = actor
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    /**
     * V1.9.293 (R5b): a new row taken from the bookings. `updated_at` and `source_applied_at` get the SAME [now], so the row reads "from the
     * bookings" until somebody changes it by hand.
     */
    fun insertDonorFromLedger(
        annexId: Uuid,
        donor: DonorRow,
        key: String,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportLargeDonorTable.insert {
            it[id] = donor.id
            it[PartgReportLargeDonorTable.annexId] = annexId
            it[personKind] = donor.personKind
            it[name] = donor.name
            it[street] = donor.street
            it[postalCode] = donor.postalCode
            it[city] = donor.city
            it[country] = donor.country
            it[contributionKind] = donor.contributionKind
            it[amountCents] = donor.amountCents
            it[createdAt] = now
            it[createdBy] = actor
            it[updatedAt] = now
            it[updatedBy] = actor
            it[sourceDonorKey] = key
            it[sourceAppliedAt] = now
        }
    }

    /** V1.9.293 (R5b): replaces the amount of the row that carries [key]; returns the number of rows changed (0 = the row is gone or has another key). */
    fun replaceDonorAmountFromLedger(
        annexId: Uuid,
        donorId: Uuid,
        key: String,
        amountCents: Long,
        actor: Uuid,
        now: LocalDateTime,
    ): Int =
        PartgReportLargeDonorTable.update(
            {
                (PartgReportLargeDonorTable.id eq donorId) and (PartgReportLargeDonorTable.annexId eq annexId) and
                    (PartgReportLargeDonorTable.sourceDonorKey eq key)
            },
        ) {
            it[PartgReportLargeDonorTable.amountCents] = amountCents
            it[updatedAt] = now
            it[updatedBy] = actor
            it[sourceAppliedAt] = now
        }

    /**
     * V1.9.293 (R5b): links an entered row to a booking donor -- sets ONLY the key. `updated_at`, `updated_by` and the amount stay as they are
     * (a link is not a change of the row; touching `updated_at` would make the donor combination look changed, finding P21). Returns the number of
     * rows changed (0 = the row is gone or already carries a key).
     */
    fun linkDonor(
        annexId: Uuid,
        donorId: Uuid,
        key: String,
    ): Int =
        PartgReportLargeDonorTable.update(
            {
                (PartgReportLargeDonorTable.id eq donorId) and (PartgReportLargeDonorTable.annexId eq annexId) and
                    PartgReportLargeDonorTable.sourceDonorKey.isNull()
            },
        ) {
            it[sourceDonorKey] = key
        }

    fun updateDonor(
        annexId: Uuid,
        donor: DonorRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportLargeDonorTable.update(
            { (PartgReportLargeDonorTable.id eq donor.id) and (PartgReportLargeDonorTable.annexId eq annexId) },
        ) {
            it[personKind] = donor.personKind
            it[name] = donor.name
            it[street] = donor.street
            it[postalCode] = donor.postalCode
            it[city] = donor.city
            it[country] = donor.country
            it[contributionKind] = donor.contributionKind
            it[amountCents] = donor.amountCents
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun deleteDonor(
        annexId: Uuid,
        donorId: Uuid,
    ) {
        PartgReportLargeDonorTable.deleteWhere {
            (PartgReportLargeDonorTable.id eq donorId) and (PartgReportLargeDonorTable.annexId eq annexId)
        }
    }

    // ---------------------------------------------------------------- notes

    private fun toNotesRow(row: ResultRow) =
        NotesRow(
            id = row[PartgReportNotesSheetTable.id],
            year = row[PartgReportNotesSheetTable.fiscalYear],
            revision = row[PartgReportNotesSheetTable.revision],
        )

    fun findNotes(year: Int): NotesRow? =
        PartgReportNotesSheetTable
            .selectAll()
            .where { PartgReportNotesSheetTable.fiscalYear eq year }
            .singleOrNull()
            ?.let(::toNotesRow)

    private fun lockNotes(year: Int): NotesRow? =
        PartgReportNotesSheetTable
            .selectAll()
            .where { PartgReportNotesSheetTable.fiscalYear eq year }
            .forUpdate()
            .singleOrNull()
            ?.let(::toNotesRow)

    fun lockOrCreateNotes(
        year: Int,
        actor: Uuid,
        now: LocalDateTime,
    ): NotesRow {
        lockNotes(year)?.let { return it }
        try {
            withSavepoint(name = "partg_notes_create") {
                PartgReportNotesSheetTable.insert {
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
        return lockNotes(year) ?: error("partg notes sheet vanished after create")
    }

    fun bumpNotesRevision(
        notes: NotesRow,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportNotesSheetTable.update({ PartgReportNotesSheetTable.id eq notes.id }) {
            it[revision] = notes.revision + 1
            it[updatedAt] = now
            it[updatedBy] = actor
        }
    }

    fun revisionOfNotes(notesId: Uuid): Long =
        PartgReportNotesSheetTable
            .selectAll()
            .where { PartgReportNotesSheetTable.id eq notesId }
            .single()[PartgReportNotesSheetTable.revision]

    /** The five blocks in the order of [PartgNoteBlock]; a block that was never saved is "not included, empty". */
    fun notes(notesId: Uuid?): List<PartgNoteDto> {
        val stored =
            if (notesId == null) {
                emptyMap()
            } else {
                PartgReportNoteTable
                    .selectAll()
                    .where { PartgReportNoteTable.notesSheetId eq notesId }
                    .associateBy { it[PartgReportNoteTable.blockCode] }
            }
        return noteDtos(stored)
    }

    private fun noteDtos(stored: Map<PartgNoteBlock, ResultRow>): List<PartgNoteDto> =
        PartgNoteBlock.entries.map { block ->
            val row = stored[block]
            PartgNoteDto(
                block = block,
                included = row?.get(PartgReportNoteTable.included) ?: false,
                body = row?.get(PartgReportNoteTable.body),
                reliefAbs1 = row?.get(PartgReportNoteTable.reliefAbs1),
                reliefAbs3 = row?.get(PartgReportNoteTable.reliefAbs3),
            )
        }

    fun holdings(notesId: Uuid?): List<PartgHoldingDto> =
        if (notesId == null) {
            emptyList()
        } else {
            PartgReportHoldingTable
                .selectAll()
                .where { PartgReportHoldingTable.notesSheetId eq notesId }
                .orderBy(PartgReportHoldingTable.sortOrder to SortOrder.ASC)
                .map(::toHoldingDto)
        }

    private fun toHoldingDto(row: ResultRow) =
        PartgHoldingDto(
            name = row[PartgReportHoldingTable.name],
            seat = row[PartgReportHoldingTable.seat],
            shareBasisPoints = row[PartgReportHoldingTable.shareBasisPoints],
            capitalCents = row[PartgReportHoldingTable.capitalCents],
            resultCents = row[PartgReportHoldingTable.resultCents],
        )

    /** Upserts the five blocks (the revision is bumped by the caller). */
    fun replaceNotes(
        notesId: Uuid,
        notes: List<PartgNoteDto>,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        val existing =
            PartgReportNoteTable
                .selectAll()
                .where { PartgReportNoteTable.notesSheetId eq notesId }
                .associate { it[PartgReportNoteTable.blockCode] to it[PartgReportNoteTable.id] }
        notes.forEach { note ->
            val existingId = existing[note.block]
            if (existingId == null) {
                PartgReportNoteTable.insert {
                    it[id] = Uuid.random()
                    it[notesSheetId] = notesId
                    it[blockCode] = note.block
                    it[included] = note.included
                    it[body] = note.body
                    it[reliefAbs1] = note.reliefAbs1
                    it[reliefAbs3] = note.reliefAbs3
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            } else {
                PartgReportNoteTable.update({ PartgReportNoteTable.id eq existingId }) {
                    it[included] = note.included
                    it[body] = note.body
                    it[reliefAbs1] = note.reliefAbs1
                    it[reliefAbs3] = note.reliefAbs3
                    it[updatedAt] = now
                    it[updatedBy] = actor
                }
            }
        }
    }

    /** Deletes and re-inserts the holdings; `sort_order` is the list index. */
    fun replaceHoldings(
        notesId: Uuid,
        holdings: List<PartgHoldingDto>,
        actor: Uuid,
        now: LocalDateTime,
    ) {
        PartgReportHoldingTable.deleteWhere { PartgReportHoldingTable.notesSheetId eq notesId }
        holdings.forEachIndexed { index, holding ->
            PartgReportHoldingTable.insert {
                it[id] = Uuid.random()
                it[notesSheetId] = notesId
                it[sortOrder] = index
                it[name] = holding.name
                it[seat] = holding.seat
                it[shareBasisPoints] = holding.shareBasisPoints
                it[capitalCents] = holding.capitalCents
                it[resultCents] = holding.resultCents
                it[updatedAt] = now
                it[updatedBy] = actor
            }
        }
    }

    // ---------------------------------------------------------------- snapshot

    /** The revisions of all annex headers of [years], keyed `A:<year>:<unitKey>`, and of the notes headers, keyed `N:<year>`. One statement each. */
    fun headerRevisions(years: Collection<Int>): Map<String, Long> {
        if (years.isEmpty()) return emptyMap()
        val result = HashMap<String, Long>()
        PartgReportAnnexTable
            .selectAll()
            .where { PartgReportAnnexTable.fiscalYear inList years.toList() }
            .forEach {
                result["A:${it[PartgReportAnnexTable.fiscalYear]}:${it[PartgReportAnnexTable.unitKey]}"] =
                    it[PartgReportAnnexTable.revision]
            }
        PartgReportNotesSheetTable
            .selectAll()
            .where { PartgReportNotesSheetTable.fiscalYear inList years.toList() }
            .forEach { result["N:${it[PartgReportNotesSheetTable.fiscalYear]}"] = it[PartgReportNotesSheetTable.revision] }
        return result
    }

    /**
     * The annex and notes data of [years]. Not one statement over five tables (child lists do not fit); the caller (`PartgReportSnapshotReader`)
     * brackets this call with [headerRevisions] reads to prove that nothing changed in between.
     */
    fun annexSnapshot(years: Collection<Int>): AnnexSnapshot {
        if (years.isEmpty()) return AnnexSnapshot(annex = emptyMap(), notes = emptyMap())
        val yearList = years.toList()
        val headers =
            PartgReportAnnexTable
                .selectAll()
                .where { PartgReportAnnexTable.fiscalYear inList yearList }
                .map(::toAnnexRow)
        val donorsByAnnex =
            if (headers.isEmpty()) {
                emptyMap()
            } else {
                PartgReportLargeDonorTable
                    .selectAll()
                    .where { PartgReportLargeDonorTable.annexId inList headers.map { it.id } }
                    .map(::toDonorRow)
                    .groupBy { it.annexId }
            }
        val sponsorsByAnnex = PartgStatementStore.sponsorsByAnnex(headers.map { it.id })
        val youthOrgsByAnnex = PartgStatementStore.youthOrgsByAnnex(headers.map { it.id })
        val enterprisesByAnnex = PartgStatementStore.enterprisesByAnnex(headers.map { it.id })
        val annex = HashMap<Int, HashMap<String, AnnexData>>()
        headers.forEach { header ->
            annex.getOrPut(header.year) { HashMap() }[header.unitKey] =
                AnnexData(
                    revision = header.revision,
                    deductions = header.deductions,
                    memberCount = header.memberCount,
                    donors = sortDonors(donorsByAnnex[header.id].orEmpty()),
                    sponsors = sponsorsByAnnex[header.id].orEmpty(),
                    youthOrgs = youthOrgsByAnnex[header.id].orEmpty(),
                    enterprises = enterprisesByAnnex[header.id].orEmpty(),
                )
        }
        val notes = HashMap<Int, NotesData>()
        PartgReportNotesSheetTable
            .selectAll()
            .where { PartgReportNotesSheetTable.fiscalYear inList yearList }
            .map(::toNotesRow)
            .forEach { sheet ->
                notes[sheet.year] = NotesData(revision = sheet.revision, notes = notes(sheet.id), holdings = holdings(sheet.id))
            }
        return AnnexSnapshot(annex = annex, notes = notes)
    }

    // ---------------------------------------------------------------- V1.9.290 (R5): lookups for the consolidation

    /** Where a donor row lives: its annex (year, unit) and the row itself. */
    data class DonorLocation(
        val annexId: Uuid,
        val year: Int,
        val unitKey: String,
        val donor: DonorRow,
    )

    /** The donor rows [ids] with year and unit of their annex; unknown ids are simply missing in the result. Reads without a lock. */
    fun donorLocations(ids: Collection<Uuid>): Map<Uuid, DonorLocation> {
        if (ids.isEmpty()) return emptyMap()
        val donors =
            PartgReportLargeDonorTable
                .selectAll()
                .where { PartgReportLargeDonorTable.id inList ids.toList() }
                .map(::toDonorRow)
        if (donors.isEmpty()) return emptyMap()
        val headers =
            PartgReportAnnexTable
                .selectAll()
                .where { PartgReportAnnexTable.id inList donors.map { it.annexId }.distinct() }
                .associate { it[PartgReportAnnexTable.id] to (it[PartgReportAnnexTable.fiscalYear] to it[PartgReportAnnexTable.unitKey]) }
        return donors.associate { donor ->
            val (year, unitKey) = headers.getValue(donor.annexId)
            donor.id to DonorLocation(annexId = donor.annexId, year = year, unitKey = unitKey, donor = donor)
        }
    }

    /**
     * Locks the existing annex headers of [year] for [unitKeys] (FOR UPDATE, always in `unit_key` order, so two writers cannot deadlock) and
     * returns them keyed by unit key. A unit without a header is missing in the result.
     */
    fun lockAnnexHeaders(
        year: Int,
        unitKeys: Collection<String>,
    ): Map<String, AnnexRow> {
        if (unitKeys.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, AnnexRow>()
        PartgReportAnnexTable
            .selectAll()
            .where { (PartgReportAnnexTable.fiscalYear eq year) and (PartgReportAnnexTable.unitKey inList unitKeys.toList()) }
            .orderBy(PartgReportAnnexTable.unitKey to SortOrder.ASC)
            .forUpdate()
            .forEach { result[it[PartgReportAnnexTable.unitKey]] = toAnnexRow(it) }
        return result
    }
}
