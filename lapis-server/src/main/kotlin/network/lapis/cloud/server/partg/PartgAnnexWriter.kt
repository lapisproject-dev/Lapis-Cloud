package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgAnnexAuditSnapshot
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
import network.lapis.cloud.shared.domain.PartgEnterpriseRules
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesAuditSnapshot
import network.lapis.cloud.shared.domain.PartgSponsorInputDto
import network.lapis.cloud.shared.domain.PartgStatementRules
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgYouthOrgInputDto
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.269 (R3) -- the ONE write path of the separate statements and the notes (`PartyReportService` calls nothing else).
 *
 * One transaction per write. Lock order: regional chapter row, then the header row (annex or notes sheet); the revision is compared under the
 * lock (a missing header has revision 0), the child rows are written, the revision is bumped, the audit entry is written LAST. [result] runs
 * inside the same transaction, so the caller returns exactly the state it wrote. Nothing is logged; no exception carries an input field.
 */
internal object PartgAnnexWriter {
    private const val CHANGE_FIGURES = "FIGURES"
    private const val CHANGE_ADDED = "DONOR_ADDED"
    private const val CHANGE_UPDATED = "DONOR_UPDATED"
    private const val CHANGE_DELETED = "DONOR_DELETED"
    private const val CHANGE_SPONSOR_ADDED = "SPONSOR_ADDED"
    private const val CHANGE_SPONSOR_UPDATED = "SPONSOR_UPDATED"
    private const val CHANGE_SPONSOR_DELETED = "SPONSOR_DELETED"
    private const val CHANGE_YOUTH_ADDED = "YOUTH_ORG_ADDED"
    private const val CHANGE_YOUTH_UPDATED = "YOUTH_ORG_UPDATED"
    private const val CHANGE_YOUTH_DELETED = "YOUTH_ORG_DELETED"
    private const val CHANGE_ENTERPRISE_ADDED = "ENTERPRISE_ADDED"
    private const val CHANGE_ENTERPRISE_UPDATED = "ENTERPRISE_UPDATED"
    private const val CHANGE_ENTERPRISE_REMOVED = "ENTERPRISE_REMOVED"
    private const val MEMBER_COUNT_FIELD = "MEMBER_COUNT"

    fun <T> saveFigures(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        deductions: Map<PartgDeduction, Long?>,
        memberCount: Int?,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val existing = PartgAnnexStore.lockAnnex(year = year, unit = unit)
            if (existing != null && existing.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val newDeductions = PartgDeduction.entries.associateWith { deductions[it] }
            if (existing == null && expectedRevision != 0L) throw PartyReportRevisionConflictException()
            val changed =
                PartgDeduction.entries.filter { (existing?.deductions?.get(it)) != newDeductions[it] }.map { it.name } +
                    listOfNotNull(MEMBER_COUNT_FIELD.takeIf { existing?.memberCount != memberCount })
            if (changed.isNotEmpty()) {
                val annex = existing ?: PartgAnnexStore.lockOrCreateAnnex(year = year, unit = unit, actor = current.memberId, now = now)
                // no header yet: a concurrent first writer may have created it between the read and now, so compare again under the lock
                if (existing == null && annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
                PartgAnnexStore.setFigures(annexId = annex.id, deductions = newDeductions, memberCount = memberCount)
                PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
                val donorCount = PartgAnnexStore.donorCount(annex.id)
                audit(
                    current = current,
                    annexId = annex.id,
                    action = if (existing == null) AuditAction.CREATE else AuditAction.UPDATE,
                    snapshot =
                        PartgAnnexAuditSnapshot(
                            year = year,
                            unitKey = unit.key(),
                            change = CHANGE_FIGURES,
                            changedFields = changed,
                            donorCount = donorCount,
                            memberCountEntered = memberCount != null,
                            deductionsEntered = newDeductions.values.count { it != null },
                        ),
                    now = now,
                )
            }
            result()
        }

    fun <T> saveDonor(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        donor: PartgLargeDonorInputDto,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockOrCreateAnnex(year = year, unit = unit, actor = current.memberId, now = now)
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val row =
                PartgAnnexStore.DonorRow(
                    id = donor.id?.let { parseId(it) } ?: Uuid.random(),
                    annexId = annex.id,
                    personKind = donor.personKind,
                    name = PartgAnnexRules.normalize(donor.name).orEmpty(),
                    street = PartgAnnexRules.normalize(donor.street),
                    postalCode = PartgAnnexRules.normalize(donor.postalCode),
                    city = PartgAnnexRules.normalize(donor.city),
                    country = donor.country,
                    contributionKind = donor.contributionKind,
                    amountCents = donor.amountCents,
                )
            val adding = donor.id == null
            if (adding) {
                if (PartgAnnexStore.donorCount(annex.id) >= PartgAnnexRules.MAX_DONORS_PER_UNIT) {
                    throw PartyReportInvalidInputException("TOO_MANY")
                }
                PartgAnnexStore.insertDonor(annexId = annex.id, donor = row, actor = current.memberId, now = now)
            } else {
                PartgAnnexStore.findDonor(annexId = annex.id, donorId = row.id) ?: throw NotFoundException("Row not found")
                PartgAnnexStore.updateDonor(annexId = annex.id, donor = row, actor = current.memberId, now = now)
            }
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = if (adding) AuditAction.CREATE else AuditAction.UPDATE,
                snapshot =
                    donorSnapshot(
                        annex = annex,
                        year = year,
                        unit = unit,
                        change = if (adding) CHANGE_ADDED else CHANGE_UPDATED,
                        donorId = row.id,
                    ),
                now = now,
            )
            result()
        }

    fun <T> deleteDonor(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        donorId: String,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockAnnex(year = year, unit = unit) ?: throw NotFoundException("Row not found")
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val id = parseId(donorId)
            PartgAnnexStore.findDonor(annexId = annex.id, donorId = id) ?: throw NotFoundException("Row not found")
            // V1.9.290 (R5): there is no cascade, so the row leaves its donor combination first (under the annex lock held here)
            PartgConsolidationStore.detachDonor(id)
            PartgAnnexStore.deleteDonor(annexId = annex.id, donorId = id)
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = AuditAction.VOID,
                snapshot = donorSnapshot(annex = annex, year = year, unit = unit, change = CHANGE_DELETED, donorId = id),
                now = now,
            )
            result()
        }

    /**
     * V1.9.283 (R3b): adds or replaces ONE sponsor with the full list of its entries. The caps are checked under the lock of the annex row, so
     * there is no gap between the check and the write.
     */
    fun <T> saveSponsor(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        sponsor: PartgSponsorInputDto,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockOrCreateAnnex(year = year, unit = unit, actor = current.memberId, now = now)
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val adding = sponsor.id == null
            val sponsorId = sponsor.id?.let { parseId(it) } ?: Uuid.random()
            val old =
                if (adding) {
                    null
                } else {
                    PartgStatementStore.findSponsor(annexId = annex.id, sponsorId = sponsorId)
                        ?: throw NotFoundException("Row not found")
                }
            if (adding && PartgStatementStore.sponsorCount(annex.id) >= PartgStatementRules.MAX_SPONSORS_PER_UNIT) {
                throw PartyReportInvalidInputException("TOO_MANY")
            }
            val oldEntryIds =
                old
                    ?.entries
                    ?.map { it.id }
                    ?.toSet()
                    .orEmpty()
            val entries =
                sponsor.entries.map { e ->
                    val entryId = e.id?.let { parseId(it) }
                    // an entry id that is not an entry of THIS sponsor is not reachable (also not by a sponsor of another unit)
                    if (entryId != null && entryId !in oldEntryIds) throw NotFoundException("Row not found")
                    PartgStatementStore.EntryRow(
                        id = entryId ?: Uuid.random(),
                        sponsorId = sponsorId,
                        date = e.date,
                        amountCents = e.amountCents,
                        purpose = PartgAnnexRules.normalize(e.purpose),
                    )
                }
            // the rules compare the raw strings, but Uuid.parse also accepts other spellings of the same id (case, with or without dashes)
            if (entries.map { it.id }.toSet().size != entries.size) throw PartyReportInvalidInputException("DUPLICATE_ID")
            val total = PartgStatementStore.entryCountOfAnnex(annex.id) - oldEntryIds.size + entries.size
            if (total > PartgStatementRules.MAX_ENTRIES_PER_UNIT) throw PartyReportInvalidInputException("TOO_MANY")
            val row =
                PartgStatementStore.SponsorRow(
                    id = sponsorId,
                    annexId = annex.id,
                    personKind = sponsor.personKind,
                    name = PartgAnnexRules.normalize(sponsor.name).orEmpty(),
                    street = PartgAnnexRules.normalize(sponsor.street),
                    postalCode = PartgAnnexRules.normalize(sponsor.postalCode),
                    city = PartgAnnexRules.normalize(sponsor.city),
                    country = sponsor.country,
                    entries = emptyList(),
                )
            if (adding) {
                PartgStatementStore.insertSponsor(sponsor = row, actor = current.memberId, now = now)
            } else {
                PartgStatementStore.updateSponsorHead(sponsor = row, actor = current.memberId, now = now)
            }
            PartgStatementStore.replaceEntries(sponsorId = sponsorId, entries = entries, actor = current.memberId, now = now)
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = if (adding) AuditAction.CREATE else AuditAction.UPDATE,
                snapshot =
                    statementSnapshot(
                        annex = annex,
                        year = year,
                        unit = unit,
                        change = if (adding) CHANGE_SPONSOR_ADDED else CHANGE_SPONSOR_UPDATED,
                        sponsorId = sponsorId,
                    ),
                now = now,
            )
            result()
        }

    fun <T> deleteSponsor(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        sponsorId: String,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockAnnex(year = year, unit = unit) ?: throw NotFoundException("Row not found")
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val id = parseId(sponsorId)
            PartgStatementStore.findSponsor(annexId = annex.id, sponsorId = id) ?: throw NotFoundException("Row not found")
            PartgStatementStore.deleteSponsor(annexId = annex.id, sponsorId = id)
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = AuditAction.VOID,
                snapshot = statementSnapshot(annex = annex, year = year, unit = unit, change = CHANGE_SPONSOR_DELETED, sponsorId = id),
                now = now,
            )
            result()
        }

    fun <T> saveYouthOrg(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        youthOrg: PartgYouthOrgInputDto,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockOrCreateAnnex(year = year, unit = unit, actor = current.memberId, now = now)
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val adding = youthOrg.id == null
            val row =
                PartgStatementStore.YouthOrgRow(
                    id = youthOrg.id?.let { parseId(it) } ?: Uuid.random(),
                    annexId = annex.id,
                    name = PartgAnnexRules.normalize(youthOrg.name).orEmpty(),
                    relationship = PartgAnnexRules.normalize(youthOrg.relationship),
                    incomeCents = youthOrg.incomeCents,
                    expensesCents = youthOrg.expensesCents,
                )
            if (adding) {
                if (PartgStatementStore.youthOrgCount(annex.id) >= PartgStatementRules.MAX_YOUTH_ORGS_PER_UNIT) {
                    throw PartyReportInvalidInputException("TOO_MANY")
                }
                PartgStatementStore.insertYouthOrg(youthOrg = row, actor = current.memberId, now = now)
            } else {
                PartgStatementStore.findYouthOrg(annexId = annex.id, youthOrgId = row.id) ?: throw NotFoundException("Row not found")
                PartgStatementStore.updateYouthOrg(youthOrg = row, actor = current.memberId, now = now)
            }
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = if (adding) AuditAction.CREATE else AuditAction.UPDATE,
                snapshot =
                    statementSnapshot(
                        annex = annex,
                        year = year,
                        unit = unit,
                        change = if (adding) CHANGE_YOUTH_ADDED else CHANGE_YOUTH_UPDATED,
                        youthOrgId = row.id,
                    ),
                now = now,
            )
            result()
        }

    fun <T> deleteYouthOrg(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        youthOrgId: String,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockAnnex(year = year, unit = unit) ?: throw NotFoundException("Row not found")
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val id = parseId(youthOrgId)
            PartgStatementStore.findYouthOrg(annexId = annex.id, youthOrgId = id) ?: throw NotFoundException("Row not found")
            PartgStatementStore.deleteYouthOrg(annexId = annex.id, youthOrgId = id)
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = AuditAction.VOID,
                snapshot = statementSnapshot(annex = annex, year = year, unit = unit, change = CHANGE_YOUTH_DELETED, youthOrgId = id),
                now = now,
            )
            result()
        }

    /** V1.9.287 (R3c): adds or changes an enterprise of statement D. The cap of 50 is checked under the lock of the annex. */
    fun <T> saveEnterprise(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        enterprise: PartgEnterpriseInputDto,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockOrCreateAnnex(year = year, unit = unit, actor = current.memberId, now = now)
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val adding = enterprise.id == null
            val row =
                PartgStatementStore.EnterpriseRow(
                    id = enterprise.id?.let { parseId(it) } ?: Uuid.random(),
                    annexId = annex.id,
                    name = PartgAnnexRules.normalize(enterprise.name).orEmpty(),
                    legalForm = PartgAnnexRules.normalize(enterprise.legalForm),
                    seat = PartgAnnexRules.normalize(enterprise.seat),
                    shareBasisPoints = enterprise.shareBasisPoints,
                    incomeCents = enterprise.incomeCents,
                    expensesCents = enterprise.expensesCents,
                    createdAt = now,
                )
            if (adding) {
                if (PartgStatementStore.enterpriseCount(annex.id) >= PartgEnterpriseRules.MAX_ENTERPRISES_PER_UNIT) {
                    throw PartyReportInvalidInputException("TOO_MANY")
                }
                PartgStatementStore.insertEnterprise(enterprise = row, actor = current.memberId, now = now)
            } else {
                PartgStatementStore.findEnterprise(annexId = annex.id, enterpriseId = row.id) ?: throw NotFoundException("Row not found")
                PartgStatementStore.updateEnterprise(enterprise = row, actor = current.memberId, now = now)
            }
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = if (adding) AuditAction.CREATE else AuditAction.UPDATE,
                snapshot =
                    statementSnapshot(
                        annex = annex,
                        year = year,
                        unit = unit,
                        change = if (adding) CHANGE_ENTERPRISE_ADDED else CHANGE_ENTERPRISE_UPDATED,
                        enterpriseId = row.id,
                    ),
                now = now,
            )
            result()
        }

    fun <T> deleteEnterprise(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        enterpriseId: String,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            PartgReportStore.lockChapter(unit)
            val annex = PartgAnnexStore.lockAnnex(year = year, unit = unit) ?: throw NotFoundException("Row not found")
            if (annex.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val id = parseId(enterpriseId)
            PartgStatementStore.findEnterprise(annexId = annex.id, enterpriseId = id) ?: throw NotFoundException("Row not found")
            PartgStatementStore.deleteEnterprise(annexId = annex.id, enterpriseId = id)
            PartgAnnexStore.bumpAnnexRevision(annex = annex, actor = current.memberId, now = now)
            audit(
                current = current,
                annexId = annex.id,
                action = AuditAction.VOID,
                snapshot =
                    statementSnapshot(
                        annex = annex,
                        year = year,
                        unit = unit,
                        change = CHANGE_ENTERPRISE_REMOVED,
                        enterpriseId = id,
                    ),
                now = now,
            )
            result()
        }

    fun <T> saveNotes(
        current: CurrentMember,
        year: Int,
        expectedRevision: Long,
        notes: List<PartgNoteDto>,
        holdings: List<PartgHoldingDto>,
        now: LocalDateTime,
        result: () -> T,
    ): T =
        transaction {
            val sheet = PartgAnnexStore.lockOrCreateNotes(year = year, actor = current.memberId, now = now)
            if (sheet.revision != expectedRevision) throw PartyReportRevisionConflictException()
            val before = PartgAnnexStore.notes(sheet.id)
            val beforeHoldings = PartgAnnexStore.holdings(sheet.id)
            // all five blocks are always written, a block the caller left out is stored "not included, empty"
            val byBlock = notes.associateBy { it.block }
            val full =
                PartgNoteBlock.entries.map {
                    byBlock[it]
                        ?: PartgNoteDto(block = it, included = false, body = null, reliefAbs1 = null, reliefAbs3 = null)
                }
            val normalised =
                full.map {
                    it.copy(
                        body = PartgAnnexRules.normalize(it.body),
                        reliefAbs1 = if (it.block == PartgNoteBlock.RELIEF_28) it.reliefAbs1 else null,
                        reliefAbs3 = if (it.block == PartgNoteBlock.RELIEF_28) it.reliefAbs3 else null,
                    )
                }
            val cleanHoldings =
                holdings.map { it.copy(name = PartgAnnexRules.normalize(it.name).orEmpty(), seat = PartgAnnexRules.normalize(it.seat)) }
            val changedBlocks = normalised.filter { n -> before.single { it.block == n.block } != n }.map { it.block }
            val holdingsChanged = beforeHoldings != cleanHoldings
            if (changedBlocks.isNotEmpty() || holdingsChanged) {
                PartgAnnexStore.replaceNotes(notesId = sheet.id, notes = normalised, actor = current.memberId, now = now)
                if (holdingsChanged) {
                    PartgAnnexStore.replaceHoldings(notesId = sheet.id, holdings = cleanHoldings, actor = current.memberId, now = now)
                }
                PartgAnnexStore.bumpNotesRevision(notes = sheet, actor = current.memberId, now = now)
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.PARTG_REPORT_NOTES,
                    entityId = sheet.id,
                    action = if (sheet.revision == 0L) AuditAction.CREATE else AuditAction.UPDATE,
                    after =
                        Json.encodeToString(
                            PartgNotesAuditSnapshot.serializer(),
                            PartgNotesAuditSnapshot(
                                year = year,
                                includedBlocks = normalised.filter { it.included }.map { it.block },
                                changedBlocks = changedBlocks,
                                holdingCount = cleanHoldings.size,
                            ),
                        ),
                    occurredAt = now,
                )
            }
            result()
        }

    private fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrNull() ?: throw NotFoundException("Row not found")

    private fun donorSnapshot(
        annex: PartgAnnexStore.AnnexRow,
        year: Int,
        unit: PartgUnit,
        change: String,
        donorId: Uuid,
    ) = PartgAnnexAuditSnapshot(
        year = year,
        unitKey = unit.key(),
        change = change,
        donorId = donorId.toString(),
        donorCount = PartgAnnexStore.donorCount(annex.id),
        memberCountEntered = annex.memberCount != null,
        deductionsEntered = annex.deductions.values.count { it != null },
    )

    private fun statementSnapshot(
        annex: PartgAnnexStore.AnnexRow,
        year: Int,
        unit: PartgUnit,
        change: String,
        sponsorId: Uuid? = null,
        youthOrgId: Uuid? = null,
        enterpriseId: Uuid? = null,
    ) = PartgAnnexAuditSnapshot(
        year = year,
        unitKey = unit.key(),
        change = change,
        donorCount = PartgAnnexStore.donorCount(annex.id),
        memberCountEntered = annex.memberCount != null,
        deductionsEntered = annex.deductions.values.count { it != null },
        sponsorId = sponsorId?.toString(),
        sponsorCount = PartgStatementStore.sponsorCount(annex.id),
        sponsoringEntryCount = PartgStatementStore.entryCountOfAnnex(annex.id),
        youthOrgId = youthOrgId?.toString(),
        youthOrgCount = PartgStatementStore.youthOrgCount(annex.id),
        enterpriseId = enterpriseId?.toString(),
        enterpriseCount = PartgStatementStore.enterpriseCount(annex.id),
    )

    private fun audit(
        current: CurrentMember,
        annexId: Uuid,
        action: AuditAction,
        snapshot: PartgAnnexAuditSnapshot,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.PARTG_REPORT_ANNEX,
            entityId = annexId,
            action = action,
            after = Json.encodeToString(PartgAnnexAuditSnapshot.serializer(), snapshot),
            occurredAt = now,
        )
    }
}
