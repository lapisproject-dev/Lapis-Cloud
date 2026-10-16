package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.shared.domain.MemberFinderInterest
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** The open (not yet superseded) GRANT of a member. */
internal data class OpenGrant(
    val version: String,
    val sha256: String,
    val interest: MemberFinderInterest,
    val occurredAt: LocalDateTime,
)

/**
 * V1.9.144 -- the append-only consent log (`member_finder_consent_event`), `PublicRankingConsentStore` pattern: a new event supersedes the open one
 * and is inserted as the new open one. **Every caller holds the global finder lock** (`MemberFinderSettingsStore.lockAndLoad`), which serializes
 * writers -- so two overlapping grants can never leave two open rows. Must run inside a `transaction {}`.
 */
internal object MemberFinderConsentStore {
    fun openGrant(memberId: Uuid): OpenGrant? =
        MemberFinderConsentEventTable
            .selectAll()
            .where {
                (MemberFinderConsentEventTable.memberId eq memberId) and
                    MemberFinderConsentEventTable.supersededAt.isNull() and
                    (MemberFinderConsentEventTable.eventType eq MemberFinderConsentEventType.GRANT)
            }.orderBy(MemberFinderConsentEventTable.occurredAt, SortOrder.DESC)
            .firstOrNull()
            ?.let {
                OpenGrant(
                    version = it[MemberFinderConsentEventTable.consentVersion].orEmpty(),
                    sha256 = it[MemberFinderConsentEventTable.consentSha256].orEmpty(),
                    interest = checkNotNull(it[MemberFinderConsentEventTable.interest]),
                    occurredAt = it[MemberFinderConsentEventTable.occurredAt],
                )
            }

    /** The moment of the latest event of any kind, `null` if the member never consented. */
    fun lastEventAt(memberId: Uuid): LocalDateTime? =
        MemberFinderConsentEventTable
            .selectAll()
            .where { MemberFinderConsentEventTable.memberId eq memberId }
            .orderBy(MemberFinderConsentEventTable.occurredAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(MemberFinderConsentEventTable.occurredAt)

    fun grant(
        memberId: Uuid,
        version: String,
        sha256: String,
        interest: MemberFinderInterest,
        now: LocalDateTime,
    ) {
        supersedeOpenRows(memberId = memberId, now = now)
        MemberFinderConsentEventTable.insert {
            it[id] = Uuid.random()
            it[MemberFinderConsentEventTable.memberId] = memberId
            it[eventType] = MemberFinderConsentEventType.GRANT
            it[occurredAt] = now
            it[supersededAt] = null
            it[consentVersion] = version
            it[consentSha256] = sha256
            it[MemberFinderConsentEventTable.interest] = interest
            it[adultConfirmed] = true
            it[revokeReason] = null
        }
    }

    /** `true` iff a GRANT was open and is now revoked; a silent no-op otherwise (a repeated revoke writes nothing). */
    fun revoke(
        memberId: Uuid,
        reason: MemberFinderRevokeReason,
        now: LocalDateTime,
    ): Boolean {
        if (openGrant(memberId) == null) return false
        supersedeOpenRows(memberId = memberId, now = now)
        MemberFinderConsentEventTable.insert {
            it[id] = Uuid.random()
            it[MemberFinderConsentEventTable.memberId] = memberId
            it[eventType] = MemberFinderConsentEventType.REVOKE
            it[occurredAt] = now
            it[supersededAt] = null
            it[consentVersion] = null
            it[consentSha256] = null
            it[interest] = null
            it[adultConfirmed] = false
            it[revokeReason] = reason
        }
        return true
    }

    /** Every member with an open GRANT (any consent version). */
    fun membersWithOpenGrant(): List<Uuid> =
        MemberFinderConsentEventTable
            .selectAll()
            .where {
                MemberFinderConsentEventTable.supersededAt.isNull() and
                    (MemberFinderConsentEventTable.eventType eq MemberFinderConsentEventType.GRANT)
            }.map { it[MemberFinderConsentEventTable.memberId] }
            .distinct()

    private fun supersedeOpenRows(
        memberId: Uuid,
        now: LocalDateTime,
    ) {
        MemberFinderConsentEventTable.update({
            (MemberFinderConsentEventTable.memberId eq memberId) and MemberFinderConsentEventTable.supersededAt.isNull()
        }) {
            it[supersededAt] = now
        }
    }
}
