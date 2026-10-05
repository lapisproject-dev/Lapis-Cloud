package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PrivilegedActionOverviewDto
import network.lapis.cloud.shared.domain.PrivilegedActionRequestDto
import network.lapis.cloud.shared.domain.PrivilegedPasswordResultDto

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the four-eyes lifecycle for the actions one ADMIN may take against ANOTHER ADMIN
 * only with the approval of a second one: a temporary password, taking the ADMIN role away, a login-blocking status.
 * The rules live in `network.lapis.cloud.server.security.PeerPolicy`; `docs/architecture/admin-peer-protection.adoc` has
 * the full matrix, the lifecycle diagram and the emergency path.
 *
 * Errors are typed ([PeerProtectionDeniedException], [PeerApprovalRequiredException], [NoSecondAdminException],
 * [PrivilegedActionStateException], [ForbiddenException], [NotFoundException], [BadRequestException], [ConflictException]);
 * Kilua RPC transmits only the subclass, never a message. Every request, approval and execution is audited without the
 * reason, a token or an address.
 */
@RpcService
interface IPrivilegedActionService {
    /**
     * Role: BOARD, TREASURER, ADMIN (UI-only read, the server re-decides on every call). The decisions of the peer-protection
     * matrix for [memberId] -- one per action -- so the UI can show the outcome and the REASON as text instead of hiding a
     * button. MEMBER callers get [ForbiddenException].
     */
    suspend fun getPeerActionDecisions(memberId: String): PeerActionDecisionsDto

    /**
     * Role: ADMIN. Asks for a temporary password for ANOTHER ADMIN. [reason] 10-500 characters. Needs outbound mail (the target
     * must be warned and gets an objection link). The target is told at once; after the approval a 24 hour objection period
     * runs before the REQUESTER can generate the password (it is generated and shown ONCE, never stored).
     */
    suspend fun requestTemporaryPassword(
        memberId: String,
        reason: String,
    ): PrivilegedActionRequestDto

    /** Role: ADMIN. Asks to take the ADMIN role away from ANOTHER ADMIN ([newRole] is not ADMIN). Effective on approval. */
    suspend fun requestDemotion(
        memberId: String,
        newRole: AccountRole,
        reason: String,
    ): PrivilegedActionRequestDto

    /**
     * Role: ADMIN. Asks to block the login of ANOTHER ADMIN ([newStatus] in the login-blocking set, not DECEASED). Effective on
     * approval.
     */
    suspend fun requestSuspension(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
    ): PrivilegedActionRequestDto

    /**
     * Role: ADMIN, an eligible approver (not the requester, not the target, not login-blocked, ADMIN for at least 7 days at the
     * time of the request). Approves; a demotion/suspension is executed in the same transaction, a temporary password becomes
     * waitable (generation possible after 24 hours).
     */
    suspend fun approve(requestId: String): PrivilegedActionRequestDto

    /** Role: ADMIN, an eligible approver. Rejects the request. The target may not reject: their instrument is the objection link. */
    suspend fun reject(requestId: String): PrivilegedActionRequestDto

    /** Role: the requester. Withdraws an open request. */
    suspend fun withdraw(requestId: String): PrivilegedActionRequestDto

    /**
     * Role: the requester, only between `notBefore` and `executeUntil` of an approved temporary-password request. Generates the
     * password server-side (a caller-chosen password is not possible) and returns it ONCE; sessions and reset tokens of the target
     * end, the target is told.
     */
    suspend fun executeTemporaryPassword(requestId: String): PrivilegedPasswordResultDto

    /** Role: ADMIN. The "Ausstehende Freigaben" card: what awaits the caller's approval, and what the caller requested. */
    suspend fun listPrivilegedActions(): PrivilegedActionOverviewDto
}
