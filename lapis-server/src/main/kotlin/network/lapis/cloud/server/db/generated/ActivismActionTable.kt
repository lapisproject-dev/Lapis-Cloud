// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ActivismActionTable : Table("activism_action") {
    public val id: Column<Uuid> = uuid("id")
    public val title: Column<String> = varchar("title", 120)
    public val description: Column<String?> = varchar("description", 4000).nullable()
    public val goalText: Column<String?> = varchar("goal_text", 500).nullable()
    public val location: Column<String?> = varchar("location", 200).nullable()
    public val startsAt: Column<LocalDateTime?> = datetime("starts_at").nullable()
    public val endsAt: Column<LocalDateTime?> = datetime("ends_at").nullable()
    public val kind: Column<ActivismKind> = enumerationByName<ActivismKind>("kind", 20)
    public val formAppointment: Column<Boolean> = bool("form_appointment").default(false)
    public val formProgress: Column<Boolean> = bool("form_progress").default(false)
    public val formSignal: Column<Boolean> = bool("form_signal").default(false)
    public val goalQuantity: Column<Int?> = integer("goal_quantity").nullable()
    public val unit: Column<String?> = varchar("unit", 40).nullable()
    public val confirmContributions: Column<Boolean> = bool("confirm_contributions").default(false)
    public val ownNotice: Column<String?> = varchar("own_notice", 2000).nullable()
    public val costCenterId: Column<Uuid?> = uuid("cost_center_id").nullable()
    public val eventId: Column<Uuid?> = uuid("event_id").nullable()
    public val status: Column<ActivismStatus> = enumerationByName<ActivismStatus>("status", 20).default(ActivismStatus.IDEA)
    public val withdrawnReason: Column<String?> = varchar("withdrawn_reason", 500).nullable()
    public val reviewRejectReason: Column<String?> = varchar("review_reject_reason", 200).nullable()
    public val resultParticipants: Column<Int?> = integer("result_participants").nullable()
    public val resultQuantity: Column<Int?> = integer("result_quantity").nullable()
    public val resultText: Column<String?> = varchar("result_text", 4000).nullable()
    public val resultRecordedAt: Column<LocalDateTime?> = datetime("result_recorded_at").nullable()
    public val createdBy: Column<Uuid?> = uuid("created_by").nullable()
    public val approvedBy: Column<Uuid?> = uuid("approved_by").nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val revision: Column<Int> = integer("revision").default(0)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
