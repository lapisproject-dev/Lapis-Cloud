package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminPasswordAction
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.PeerActionAuditFacts
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- THE write of a temporary password, extracted verbatim from
 * `MemberService.setTemporaryPasswordForMember` so the direct call (against a non-ADMIN target) and the execution of an
 * APPROVED request (against an ADMIN target, `PrivilegedActionService.executeTemporaryPassword`) run the same code. Runs
 * INSIDE the caller's transaction with the target's member row and account row locked; the audit write is the last
 * lock-taking call. Session revocation, reset-token invalidation and the notice are the caller's AFTER-commit job.
 *
 * The password never reaches the audit entry: it carries only the [AdminPasswordAction] marker, the peer facts and (for
 * the direct path) the reason. The caller decides whether the peer decision allows this at all.
 */
internal object TemporaryPasswordMutation {
    fun applyLocked(
        actor: CurrentMember,
        targetId: Uuid,
        memberRow: ResultRow,
        accountRole: AccountRole,
        effectivePassword: String,
        auditReason: String?,
        now: LocalDateTime,
        peerFacts: PeerActionAuditFacts?,
    ) {
        // Against the address AS STORED, never a client-supplied one -- this call does not accept an e-mail parameter at all.
        PasswordPolicy.validate(newPassword = effectivePassword, email = memberRow[MemberTable.email])

        AccountTable.update({ AccountTable.memberId eq targetId }) {
            it[passwordHash] = PasswordHasher.hash(effectivePassword)
        }

        val beforeSnapshot =
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = memberRow[MemberTable.status],
                role = accountRole,
            )
        val afterSnapshot =
            beforeSnapshot.copy(
                reason = auditReason,
                adminPasswordAction = AdminPasswordAction.TEMPORARY_PASSWORD_SET,
                peerAction = peerFacts,
            )
        // LAST sperrende Operation dieser Transaktion (Deadlock-Vertrag, AuditLogRecorder KDoc).
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.MEMBER,
            entityId = targetId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), beforeSnapshot),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), afterSnapshot),
            occurredAt = now,
        )
    }
}
