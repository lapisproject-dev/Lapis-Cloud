// Welle V1.9.144 "Mitglieder-Finder" -- hand-edited, see 72-member-finder.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.memberfinder.MemberFinderRequestStatus
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object MemberFinderRequestTable : Table("member_finder_request") {
    public val id: Column<Uuid> = uuid("id")
    public val fromMemberId: Column<Uuid> = reference("from_member_id", MemberTable.id)
    public val toMemberId: Column<Uuid?> = optReference("to_member_id", MemberTable.id)
    public val purpose: Column<MemberFinderPurpose> = enumerationByName<MemberFinderPurpose>("purpose", 14)
    public val note: Column<String?> = varchar("note", 200).nullable()
    public val status: Column<MemberFinderRequestStatus> = enumerationByName<MemberFinderRequestStatus>("status", 9)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
    public val decidedAt: Column<LocalDateTime?> = datetime("decided_at").nullable()
    public val senderWithdrawnAt: Column<LocalDateTime?> = datetime("sender_withdrawn_at").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
