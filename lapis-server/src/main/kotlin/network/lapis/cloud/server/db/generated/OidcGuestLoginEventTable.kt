// Hand-written per ADR-0016 Option B (see 25-oidc-guest-federation.kuml.kts file header).

package network.lapis.cloud.server.db.generated

import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.OidcLoginEventType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Forensic, non-hash-chained login/logout audit trail (V0.8.2) -- NOT `audit_log_entry` (see
 * 25-oidc-guest-federation.kuml.kts file header). [memberId] is DELIBERATELY a plain `Column<Uuid?>`
 * with NO `.references(...)` -- the one place this wave intentionally does not create a DB-level
 * FK to `member`, pinned by `OidcGuestFederationSchemaDriftTest`.
 */
public object OidcGuestLoginEventTable : Table("oidc_guest_login_event") {
    public val id: Column<Uuid> = uuid("id")
    public val occurredAt: Column<LocalDateTime> = datetime("occurred_at")
    public val eventType: Column<OidcLoginEventType> = enumerationByName<OidcLoginEventType>("event_type", 27)
    public val memberId: Column<Uuid?> = uuid("member_id").nullable()
    public val remoteParty: Column<String?> = varchar("remote_party", 2048).nullable()
    public val reason: Column<String?> = varchar("reason", 255).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
