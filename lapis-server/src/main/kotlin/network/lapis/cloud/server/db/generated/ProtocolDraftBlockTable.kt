// Welle V1.9.239 "Protokollentwurf in der Sitzung" -- hand-edited, see 05-governance.kuml.kts and V129__meeting_protocol_draft.sql.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ProtocolDraftBlockTable : Table("protocol_draft_block") {
    public val id: Column<Uuid> = uuid("id")
    public val draftId: Column<Uuid> = reference("draft_id", ProtocolDraftTable.id)
    public val agendaItemId: Column<Uuid> = reference("agenda_item_id", AgendaItemTable.id)
    public val body: Column<String> = varchar("body", 8000)
    public val version: Column<Long> = long("version")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
