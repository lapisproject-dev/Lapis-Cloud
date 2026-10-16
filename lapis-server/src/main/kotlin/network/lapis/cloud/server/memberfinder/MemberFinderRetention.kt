package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.MemberFinderRules
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * V1.9.144 -- the housekeeping of the finder: expiry, detaching, deletion, cleanup of members who lost eligibility, and the once-a-day band
 * evaluation. Called by [MemberFinderPoller] under the global finder lock, with an injected [now] so a test fixes the day. Must run inside a
 * `transaction {}`. **Counts are never logged by this object and never audited.**
 */
internal object MemberFinderRetention {
    /**
     * Order matters: (1) expire what ran out, (2) expire what lost a side's eligibility, (3) detach the counterpart link of closed requests after
     * the retention period, (4) delete, (5) end the consent of members that are no longer eligible, (6) evaluate the bands once per day.
     */
    fun run(now: LocalDateTime) {
        val closedCutoff = now.minusDays(MemberFinderRules.CLOSED_RETENTION_DAYS)
        val acceptedCutoff = now.minusDays(MemberFinderRules.ACCEPTED_RETENTION_DAYS)
        val today = OrganizationTimeZone.dateOf(now)
        val current = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)

        // 1. OPEN requests that ran out: decided_at = expires_at, so the time of the decision never differs from the time of the end.
        MemberFinderRequestTable
            .selectAll()
            .where {
                (MemberFinderRequestTable.status eq MemberFinderRequestStatus.OPEN) and (MemberFinderRequestTable.expiresAt lessEq now)
            }.map { it[MemberFinderRequestTable.id] to it[MemberFinderRequestTable.expiresAt] }
            .forEach { (id, expiresAt) ->
                MemberFinderRequestStore.setStatus(id = id, status = MemberFinderRequestStatus.EXPIRED, decidedAt = expiresAt)
            }

        // 2. OPEN requests whose sides are no longer both eligible in the same region. The sender's view stays PENDING until expires_at.
        val participants = MemberFinderEligibility.participants(today = today, current = current).associateBy { it.memberId }
        MemberFinderRequestTable
            .selectAll()
            .where {
                (MemberFinderRequestTable.status eq MemberFinderRequestStatus.OPEN) and MemberFinderRequestTable.toMemberId.isNotNull()
            }.map {
                Triple(
                    it[MemberFinderRequestTable.id],
                    it[MemberFinderRequestTable.fromMemberId],
                    it[MemberFinderRequestTable.toMemberId],
                )
            }.forEach { (id, from, to) ->
                val sender = participants[from]
                val addressee = to?.let { participants[it] }
                if (sender == null || addressee == null || sender.regionKey != addressee.regionKey) {
                    MemberFinderRequestStore.setStatus(id = id, status = MemberFinderRequestStatus.EXPIRED, decidedAt = now)
                }
            }

        // 3. Detach the counterpart link (and the note) of declined/expired requests once the 7-day pair block is over.
        MemberFinderRequestTable.update({
            (MemberFinderRequestTable.status inList listOf(MemberFinderRequestStatus.DECLINED, MemberFinderRequestStatus.EXPIRED)) and
                MemberFinderRequestTable.toMemberId.isNotNull() and
                (MemberFinderRequestTable.decidedAt lessEq closedCutoff)
        }) {
            it[toMemberId] = null
            it[note] = null
        }

        // 4. Delete. A request the sender withdrew goes 7 days after the withdrawal whatever its stored status (E6: every withdrawal vanishes at the
        // same age); a not withdrawn declined/expired request goes 7 days after max(decided_at, expires_at).
        MemberFinderRequestTable.deleteWhere {
            MemberFinderRequestTable.senderWithdrawnAt.isNotNull() and (MemberFinderRequestTable.senderWithdrawnAt lessEq closedCutoff)
        }
        MemberFinderRequestTable.deleteWhere {
            MemberFinderRequestTable.senderWithdrawnAt.isNull() and
                (MemberFinderRequestTable.status inList listOf(MemberFinderRequestStatus.DECLINED, MemberFinderRequestStatus.EXPIRED)) and
                (MemberFinderRequestTable.decidedAt lessEq closedCutoff) and
                (MemberFinderRequestTable.expiresAt lessEq closedCutoff)
        }
        MemberFinderRequestTable.deleteWhere {
            (MemberFinderRequestTable.status eq MemberFinderRequestStatus.ACCEPTED) and
                (MemberFinderRequestTable.decidedAt lessEq acceptedCutoff)
        }

        // 5. Members with an open consent who are no longer ACTIVE, were anonymized or turned out to be minors.
        MemberFinderEligibility.grantedButIneligible(today).forEach {
            MemberFinderLifecycle.onOptOut(memberId = it, reason = MemberFinderRevokeReason.STATUS_LOST, now = now)
        }

        // 6. The bands: once per organization day (or while there are none). A restart with an empty table triggers one extra evaluation -- accepted.
        val last = MemberFinderBands.computedAt()
        if (last == null || OrganizationTimeZone.dateOf(last) != today) {
            MemberFinderBands.recompute(now = now, today = today, current = current)
        }
    }
}
