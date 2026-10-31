// Welle V1.9.246 "Aktionen A1" -- hand-edited, see 80-activism.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ActivismKind
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object ActivismNoticeTextTable : Table("activism_notice_text") {
    public val id: Column<Uuid> = uuid("id")
    public val countryCode: Column<String> = varchar("country_code", 2)
    public val kind: Column<ActivismKind> = enumerationByName<ActivismKind>("kind", 20)
    public val body: Column<String> = varchar("body", 2000)
    public val updatedAt: Column<LocalDateTime> = datetime("updated_at")
    public val updatedBy: Column<Uuid?> = uuid("updated_by").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
