package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.member.PrivilegedActionService
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PrivilegedActionOverviewDto
import network.lapis.cloud.shared.domain.PrivilegedActionRequestDto
import network.lapis.cloud.shared.domain.PrivilegedPasswordResultDto
import network.lapis.cloud.shared.rpc.IPrivilegedActionService

/**
 * Welle V1.9.57 -- thin RPC facade over [PrivilegedActionService] (which holds all logic, so the unauthenticated objection
 * route and the poller share it). Rebuilt per call like every RPC service; the singleton [domain] instance carries the rate
 * limiters. Every method resolves the caller exactly once.
 */
class PrivilegedActionRpcService internal constructor(
    private val call: ApplicationCall,
    private val domain: PrivilegedActionService,
) : IPrivilegedActionService {
    override suspend fun getPeerActionDecisions(memberId: String): PeerActionDecisionsDto =
        domain.decisions(actor = resolveCurrentMember(call), targetIdRaw = memberId)

    override suspend fun requestTemporaryPassword(
        memberId: String,
        reason: String,
    ): PrivilegedActionRequestDto =
        domain.requestTemporaryPassword(actor = resolveCurrentMember(call), targetIdRaw = memberId, reasonRaw = reason)

    override suspend fun requestDemotion(
        memberId: String,
        newRole: AccountRole,
        reason: String,
    ): PrivilegedActionRequestDto =
        domain.requestDemotion(actor = resolveCurrentMember(call), targetIdRaw = memberId, newRole = newRole, reasonRaw = reason)

    override suspend fun requestSuspension(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
    ): PrivilegedActionRequestDto =
        domain.requestSuspension(actor = resolveCurrentMember(call), targetIdRaw = memberId, newStatus = newStatus, reasonRaw = reason)

    override suspend fun approve(requestId: String): PrivilegedActionRequestDto =
        domain.approve(actor = resolveCurrentMember(call), requestIdRaw = requestId)

    override suspend fun reject(requestId: String): PrivilegedActionRequestDto =
        domain.reject(actor = resolveCurrentMember(call), requestIdRaw = requestId)

    override suspend fun withdraw(requestId: String): PrivilegedActionRequestDto =
        domain.withdraw(actor = resolveCurrentMember(call), requestIdRaw = requestId)

    override suspend fun executeTemporaryPassword(requestId: String): PrivilegedPasswordResultDto =
        domain.executeTemporaryPassword(actor = resolveCurrentMember(call), requestIdRaw = requestId)

    override suspend fun listPrivilegedActions(): PrivilegedActionOverviewDto = domain.overview(actor = resolveCurrentMember(call))
}
