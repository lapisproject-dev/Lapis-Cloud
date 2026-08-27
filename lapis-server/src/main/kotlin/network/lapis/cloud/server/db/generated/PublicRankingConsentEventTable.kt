// Hand-written per ADR-0016 Option B (see 35-public-ranking-consent.kuml.kts file header) -- not
// codegen-emitted, mirrors the naming/style of every other file in this package.

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PublicRankingConsentEventType
import network.lapis.cloud.shared.domain.PublicRankingKind
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

public object PublicRankingConsentEventTable : Table("public_ranking_consent_event") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val rankingKind: Column<PublicRankingKind> = enumerationByName<PublicRankingKind>("ranking_kind", 12)
    public val eventType: Column<PublicRankingConsentEventType> = enumerationByName<PublicRankingConsentEventType>("event_type", 7)
    public val occurredAt: Column<LocalDateTime> = datetime("occurred_at")
    public val supersededAt: Column<LocalDateTime?> = datetime("superseded_at").nullable()
    public val consentVersion: Column<String> = varchar("consent_version", 50)
    public val consentSha256: Column<String> = varchar("consent_sha256", 64)

    override val primaryKey: PrimaryKey = PrimaryKey(id)

    // Note: 2 index(es) declared on this entity are not emitted --
    // Exposed's index {} DSL needs typed column references, not wired up in this wave.
}
