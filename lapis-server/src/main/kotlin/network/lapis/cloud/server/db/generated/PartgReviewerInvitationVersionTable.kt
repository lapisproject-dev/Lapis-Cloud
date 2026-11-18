// Hand-written, mirroring the generated-file convention every other db/generated table in this package establishes -- kept in sync with
// 76-partg-report.kuml.kts by PartgReportSchemaDriftTest. V1.9.310 (R6b).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

public object PartgReviewerInvitationVersionTable : Table("partg_reviewer_invitation_version") {
    public val invitationId: Column<Uuid> = reference("invitation_id", PartgReviewerInvitationTable.id)
    public val versionId: Column<Uuid> = reference("version_id", PartgReportVersionTable.id)

    override val primaryKey: PrimaryKey = PrimaryKey(invitationId, versionId)
}
