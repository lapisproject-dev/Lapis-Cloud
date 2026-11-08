// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.291 (R6).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PartgReportDeclarationTable : Table("partg_report_declaration") {
    public val id: Column<Uuid> = uuid("id")
    public val versionId: Column<Uuid> = reference("version_id", PartgReportVersionTable.id)
    public val templateCode: Column<String> = varchar("template_code", 32)
    public val contentHash: Column<String> = varchar("content_hash", 64)
    public val declaredAt: Column<LocalDateTime> = datetime("declared_at")
    public val declaredBy: Column<Uuid> = reference("declared_by", MemberTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: the CHECK and the unique index (version_id) exist in SQL only.
}
