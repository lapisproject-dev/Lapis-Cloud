// Welle V1.9.237 "Neue Nachricht" -- hand-edited, see 78-direct-message-block.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object DirectMessageBlockTable : Table("direct_message_block") {
    public val id: Column<Uuid> = uuid("id")
    public val blockerMemberId: Column<Uuid> = reference("blocker_member_id", MemberTable.id)
    public val blockedMemberId: Column<Uuid> = reference("blocked_member_id", MemberTable.id)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
