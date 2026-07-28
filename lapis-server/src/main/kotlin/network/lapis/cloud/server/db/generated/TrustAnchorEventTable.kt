// Hand-written per ADR-0016 Option B (see 26-trust-anchor.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TrustAnchorEventType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object TrustAnchorEventTable : Table("trust_anchor_event") {
    public val id: Column<Uuid> = uuid("id")
    public val occurredAt: Column<LocalDateTime> = datetime("occurred_at")
    public val eventType: Column<TrustAnchorEventType> = enumerationByName<TrustAnchorEventType>("event_type", 22)
    public val subject: Column<String> = varchar("subject", 2048)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 1 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
