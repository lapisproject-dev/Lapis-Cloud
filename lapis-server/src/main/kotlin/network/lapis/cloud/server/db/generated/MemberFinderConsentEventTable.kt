// Welle V1.9.144 "Mitglieder-Finder" -- hand-edited, see 72-member-finder.kuml.kts file header (this repo's `designModelStrategy option B`,
// see docs/architecture/domain-model.adoc).

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.memberfinder.MemberFinderConsentEventType
import network.lapis.cloud.server.memberfinder.MemberFinderRevokeReason
import network.lapis.cloud.shared.domain.MemberFinderInterest
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object MemberFinderConsentEventTable : Table("member_finder_consent_event") {
    public val id: Column<Uuid> = uuid("id")
    public val memberId: Column<Uuid> = reference("member_id", MemberTable.id)
    public val eventType: Column<MemberFinderConsentEventType> = enumerationByName<MemberFinderConsentEventType>("event_type", 6)
    public val occurredAt: Column<LocalDateTime> = datetime("occurred_at")
    public val supersededAt: Column<LocalDateTime?> = datetime("superseded_at").nullable()
    public val consentVersion: Column<String?> = varchar("consent_version", 50).nullable()
    public val consentSha256: Column<String?> = varchar("consent_sha256", 64).nullable()
    public val interest: Column<MemberFinderInterest?> = enumerationByName<MemberFinderInterest>("interest", 14).nullable()
    public val adultConfirmed: Column<Boolean> = bool("adult_confirmed").default(false)
    public val revokeReason: Column<MemberFinderRevokeReason?> = enumerationByName<MemberFinderRevokeReason>("revoke_reason", 12).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
