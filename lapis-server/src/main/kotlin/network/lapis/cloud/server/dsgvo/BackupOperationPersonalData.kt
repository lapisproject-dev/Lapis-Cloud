package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.BackupOperationLogTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [BackupOperationLogTable] -- the only member-FK-bearing table the V0.5.4 Backup-/Restore-/
 * Datenexport-Garantie domain adds (`actor_member_id`). See
 * `network.lapis.cloud.server.backup.OrganizationExportService`/`OrganizationRestoreService` KDoc
 * for the write path.
 *
 * Same "retained unconditionally, regardless of [ErasureMode]" treatment as
 * [AuditLogPersonalData] -- a full-organization export/restore is the single most privileged
 * operation ADMIN can perform, and knowing WHO triggered it, permanently, is itself an
 * accountability record (analogous rationale to GoBD Nachvollziehbarkeit, even though this table
 * is not part of the GoBD hash chain itself -- see `15-backup-export.kuml.kts` file header for why
 * it is deliberately a separate, simpler, non-chained log).
 *
 * [export] returns only metadata (id/operationType/status/timestamps/counts) for rows where
 * [memberId] was the actor -- never bundle content, which never lived in this table anyway (see
 * `BackupOperationLogDto` KDoc's own "counts only, never payload" note).
 */
object BackupOperationPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "backupOperations"
    override val displayName = "Backup-/Restore-Vorgaenge"
    override val coveredTables = setOf(BackupOperationLogTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonArray {
            BackupOperationLogTable
                .selectAll()
                .where { BackupOperationLogTable.actorMemberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[BackupOperationLogTable.id].toString())
                            put("operationType", row[BackupOperationLogTable.operationType].name)
                            put("status", row[BackupOperationLogTable.status].name)
                            put("startedAt", row[BackupOperationLogTable.startedAt].toString())
                            put("finishedAt", row[BackupOperationLogTable.finishedAt].toString())
                            put("tableCount", row[BackupOperationLogTable.tableCount])
                            put("totalRowCount", row[BackupOperationLogTable.totalRowCount])
                        },
                    )
                }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total = BackupOperationLogTable.selectAll().where { BackupOperationLogTable.actorMemberId eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "backup_operation_log",
                rowsRetained = total.toInt(),
                retentionReason =
                    "Operational accountability record of a highly privileged (ADMIN-only) full-" +
                        "organization export/restore -- who triggered it and when is never cleared or " +
                        "anonymized, mirroring AuditLogPersonalData's own GoBD-adjacent retention " +
                        "rationale.",
            ),
        )
    }
}
