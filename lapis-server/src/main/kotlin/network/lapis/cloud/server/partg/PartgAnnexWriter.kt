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
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesAuditSnapshot
import network.lapis.cloud.shared.domain.PartgUnit
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
