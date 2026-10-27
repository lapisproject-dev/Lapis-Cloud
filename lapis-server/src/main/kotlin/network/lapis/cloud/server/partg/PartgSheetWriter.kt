package network.lapis.cloud.server.partg

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgSaveResultDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueAuditSnapshot
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * V1.9.225 -- the ONE write path of report values, shared by `PartyReportService.saveSheet` and the Excel import apply route.
 *
 * One transaction. Lock order: regional chapter row, sheet of the older year, sheet of the younger year; revisions are compared under the
 * lock (a missing sheet has revision 0); the changes are applied; the audit entries are written LAST (one per effective value, then the
 * optional [collectiveAudit] entry). No external effect inside the transaction.
 */
internal object PartgSheetWriter {
    fun write(
        current: CurrentMember,
        year: Int,
        unit: PartgUnit,
        expectedRevision: Long,
        expectedPriorRevision: Long,
        currentChanges: Map<String, Long?>,
        priorChanges: Map<String, Long?>,
        source: PartgValueSource,
        now: LocalDateTime,
        collectiveAudit: ((sheetId: kotlin.uuid.Uuid, now: LocalDateTime, changeCount: Int) -> Unit)? = null,
    ): PartgSaveResultDto =
        transaction {
            PartgReportStore.lockChapter(unit)
            val priorYear = year - 1
            val prior = openSheet(year = priorYear, unit = unit, hasChanges = priorChanges.isNotEmpty(), current = current, now = now)
            val currentSheet = openSheet(year = year, unit = unit, hasChanges = currentChanges.isNotEmpty(), current = current, now = now)
            if ((prior?.revision ?: 0L) != expectedPriorRevision || (currentSheet?.revision ?: 0L) != expectedRevision) {
                throw PartyReportRevisionConflictException()
            }
            val priorDiffs =
                prior
                    ?.let {
                        PartgReportStore.applyChanges(
                            sheet = it,
                            changes = priorChanges,
                            source = source,
                            actor = current.memberId,
                            now = now,
                        )
                    }.orEmpty()
            val currentDiffs =
                currentSheet
                    ?.let {
                        PartgReportStore.applyChanges(
                            sheet = it,
                            changes = currentChanges,
                            source = source,
                            actor = current.memberId,
                            now = now,
                        )
                    }.orEmpty()
            // audit LAST
            prior?.let { audit(current = current, sheet = it, unit = unit, year = priorYear, diffs = priorDiffs, now = now) }
            currentSheet?.let { audit(current = current, sheet = it, unit = unit, year = year, diffs = currentDiffs, now = now) }
            val total = priorDiffs.size + currentDiffs.size
            if (collectiveAudit != null && total > 0) collectiveAudit(currentSheet?.id ?: prior!!.id, now, total)
            PartgSaveResultDto(
                revision = currentSheet?.let { PartgReportStore.revisionOf(it.id) } ?: 0L,
                priorRevision = prior?.let { PartgReportStore.revisionOf(it.id) } ?: 0L,
                changed = total,
            )
        }

    /** Locks or creates (when there are changes) the sheet of [year]; `null` = no sheet and nothing to write. */
    private fun openSheet(
        year: Int,
        unit: PartgUnit,
        hasChanges: Boolean,
        current: CurrentMember,
        now: LocalDateTime,
    ): PartgReportStore.SheetRow? =
        if (hasChanges) {
            PartgReportStore.lockOrCreateSheet(year = year, unit = unit, actor = current.memberId, now = now)
        } else {
            PartgReportStore.lockSheet(year = year, unit = unit)
        }

    private fun audit(
        current: CurrentMember,
        sheet: PartgReportStore.SheetRow,
        unit: PartgUnit,
        year: Int,
        diffs: List<PartgReportStore.ValueDiff>,
        now: LocalDateTime,
    ) {
        diffs.forEach { diff ->
            fun snapshot(
                cents: Long?,
                source: PartgValueSource?,
            ) = Json.encodeToString(
                PartgValueAuditSnapshot.serializer(),
                PartgValueAuditSnapshot(
                    year = year,
                    unitKey = unit.key(),
                    positionCode = diff.positionCode,
                    cents = cents,
                    source = source,
                ),
            )
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.PARTG_REPORT_SHEET,
                entityId = sheet.id,
                action =
                    when {
                        diff.newCents == null -> AuditAction.VOID
                        diff.oldCents == null -> AuditAction.CREATE
                        else -> AuditAction.UPDATE
                    },
                before = if (diff.oldCents == null) null else snapshot(diff.oldCents, diff.oldSource),
                after = if (diff.newCents == null) null else snapshot(diff.newCents, diff.newSource),
                occurredAt = now,
            )
        }
    }
}
