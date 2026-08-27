package network.lapis.cloud.server.payment.sepa

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.SepaMandateTable
import network.lapis.cloud.server.rpc.resetGeneratedBatchesForUnusableMandate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.SepaMandateStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.2.12 -- the per-MEMBER rump of the former [SepaBatchPoller.runPhaseB] body, extracted
 * so BOTH the poller AND `network.lapis.cloud.server.rpc.MemberService.updateMemberStatus` call
 * the SAME code. There is deliberately no second implementation and no time window between an
 * ACTIVE->WITHDRAWN/DECEASED status change made through the admin RPC and the poller's own next
 * tick in which a pain.008-eligible mandate could be left inconsistent with the member's new
 * status.
 *
 * **Must be called from inside an already-open `transaction {}`** -- same contract
 * [AuditLogRecorder.record] itself documents; this function calls that recorder directly.
 *
 * [actorMemberId]/[actorRole]: `null`/`null` for the poller (system actor, same convention
 * [SepaBatchPoller] already uses for its own mandate-level audit entry), the real caller's
 * identity for the RPC path.
 *
 * Returns the number of mandates actually revoked (0 or 1 in practice -- a member has at most one
 * ACTIVE mandate at a time, but this does not assume that and revokes every ACTIVE mandate found),
 * for the caller's own logging/test assertions.
 */
internal fun revokeMandatesForEndedMembership(
    memberId: Uuid,
    actorMemberId: Uuid?,
    actorRole: AccountRole?,
    now: LocalDateTime,
): Int {
    val mandateIds =
        SepaMandateTable
            .selectAll()
            .where { (SepaMandateTable.memberId eq memberId) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE) }
            .map { it[SepaMandateTable.id] }
    var revokedCount = 0
    for (mandateId in mandateIds) {
        val updated =
            SepaMandateTable.update({
                (SepaMandateTable.id eq mandateId) and (SepaMandateTable.status eq SepaMandateStatus.ACTIVE)
            }) {
                it[status] = SepaMandateStatus.REVOKED
                it[revokedAt] = now
                it[revokedBy] = actorMemberId
                it[revocationReason] = "Mitgliedschaft beendet"
            }
        if (updated > 0) {
            revokedCount++
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.SEPA_MANDATE,
                entityId = mandateId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            resetGeneratedBatchesForUnusableMandate(mandateId = mandateId, actorMemberId = actorMemberId, actorRole = actorRole)
        }
    }
    return revokedCount
}
