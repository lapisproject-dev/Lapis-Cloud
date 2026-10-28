// Welle V1.9.240 "Protokoll fertigstellen" -- hand-edited, see 05-governance.kuml.kts and V130__meeting_protocol_versions.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ProtocolVersionConfirmationTable : Table("protocol_version_confirmation") {
    public val id: Column<Uuid> = uuid("id")
    public val versionId: Column<Uuid> = reference("version_id", ProtocolVersionTable.id)
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val role: Column<ProtocolConfirmationRole> = enumerationByName<ProtocolConfirmationRole>("role", 7)
    public val confirmedAt: Column<LocalDateTime> = datetime("confirmed_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
