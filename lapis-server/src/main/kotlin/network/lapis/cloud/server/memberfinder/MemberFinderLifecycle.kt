package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberFinderBlockTable
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.144 -- what ends when a consent ends. **Plain database code, no external effect**: every function runs inside the caller's `transaction {}`
 * (the hooks sit in the transactions of the status writes, `MemberPublicBioStore.revokePublicationOnStatusLoss` pattern) and none of them takes the
 * global finder lock -- they only ever delete or detach, so a request a concurrent sender inserts a moment later is caught by the live filters and
 * the poller.
 *
 * **Detaching instead of deleting the addressee's rows.** When the ADDRESSEE of a request leaves, the row is not deleted but loses its counterpart and
 * its note and stays as a phantom (decision E6): the sender must not see a pending request vanish -- that would answer "the other side left" at once.
 * Only an ACCEPTED request is deleted (the connection is gone, and the sender saw it).
 *
 * Neither the audit log nor a log line mentions a member or a count: a line "member X left the finder" would be an erasure-proof trace.
 */
internal object MemberFinderLifecycle {
    /** The member's own opt-out ([reason] MEMBER) or the end of a consent for another reason: revoke, then remove what hangs on it. */
    fun onOptOut(
        memberId: Uuid,
        reason: MemberFinderRevokeReason,
        now: LocalDateTime,
    ) {
        MemberFinderConsentStore.revoke(memberId = memberId, reason = reason, now = now)
        detachRelations(memberId)
    }

    /** A status change away from ACTIVE ends the consent (a later reactivation does NOT bring it back). No-op while the member stays ACTIVE. */
    fun onStatusChange(
        memberId: Uuid,
        newStatus: MemberStatus,
        now: LocalDateTime,
    ) {
        if (newStatus in MemberStatusSets.ORGANIZATION_MEMBER) return
        onOptOut(memberId = memberId, reason = MemberFinderRevokeReason.STATUS_LOST, now = now)
    }

    /**
     * Requests of [memberId] as the sender are deleted; as the addressee an ACCEPTED one is deleted and every other one is detached (phantom). The
     * member's OWN blocks are deleted; blocks other members set against the member stay (they protect the blocker).
     */
    fun detachRelations(memberId: Uuid) {
        MemberFinderRequestTable.deleteWhere { MemberFinderRequestTable.fromMemberId eq memberId }
        MemberFinderRequestTable.deleteWhere {
            (MemberFinderRequestTable.toMemberId eq memberId) and (MemberFinderRequestTable.status eq MemberFinderRequestStatus.ACCEPTED)
        }
        MemberFinderRequestTable.update({ MemberFinderRequestTable.toMemberId eq memberId }) {
            it[toMemberId] = null
            it[note] = null
        }
        MemberFinderBlockTable.deleteWhere { MemberFinderBlockTable.blockerMemberId eq memberId }
    }

    /**
     * The instance switch went OFF (decision E8): every request, every block and every band goes; every effective consent gets a SYSTEM revoke
     * (`INSTANCE_OFF`). The consent events stay as proof, but a later switch-on does NOT revive them -- a member who consents again does so
     * knowingly, and a blocked sender can never be drawn again by accident. Writes no count anywhere.
     */
    fun onInstanceDisabled(now: LocalDateTime) {
        MemberFinderRequestTable.deleteAll()
        MemberFinderBlockTable.deleteAll()
        MemberFinderBands.deleteAll()
        MemberFinderConsentStore.membersWithOpenGrant().forEach {
            MemberFinderConsentStore.revoke(memberId = it, reason = MemberFinderRevokeReason.INSTANCE_OFF, now = now)
        }
    }

    /**
     * GDPR erasure: everything that names the member. Requests where the member is the addressee are detached (see class KDoc), every other
     * reference -- consent events, own requests, blocks of BOTH sides -- is deleted.
     */
    fun purgeMember(memberId: Uuid) {
        MemberFinderRequestTable.deleteWhere { MemberFinderRequestTable.fromMemberId eq memberId }
        MemberFinderRequestTable.deleteWhere {
            (MemberFinderRequestTable.toMemberId eq memberId) and (MemberFinderRequestTable.status eq MemberFinderRequestStatus.ACCEPTED)
        }
        MemberFinderRequestTable.update({ MemberFinderRequestTable.toMemberId eq memberId }) {
            it[toMemberId] = null
            it[note] = null
        }
        MemberFinderBlockTable.deleteWhere {
            (MemberFinderBlockTable.blockerMemberId eq memberId) or (MemberFinderBlockTable.blockedMemberId eq memberId)
        }
        MemberFinderConsentEventTable.deleteWhere { MemberFinderConsentEventTable.memberId eq memberId }
    }
}
