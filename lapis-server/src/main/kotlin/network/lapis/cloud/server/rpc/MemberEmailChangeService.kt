package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.member.EmailChangeService
import network.lapis.cloud.server.security.extractSessionToken
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangePendingDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OwnEmailChangeResultDto
import network.lapis.cloud.shared.domain.OwnPendingEmailChangeStateDto
import network.lapis.cloud.shared.domain.PendingEmailChangeLookupDto
import network.lapis.cloud.shared.rpc.IMemberEmailChangeService

/**
 * Welle V1.9.56 -- thin RPC facade over [EmailChangeService] (which holds all logic, so the unauthenticated link routes
 * and the poller share it). Rebuilt per call like every RPC service; the singleton [domain] instance carries the rate
 * limiters. Every method resolves the caller exactly once.
 */
class MemberEmailChangeService internal constructor(
    private val call: ApplicationCall,
    private val domain: EmailChangeService,
) : IMemberEmailChangeService {
    override suspend fun getEmailChangeCapability(): EmailChangeCapabilityDto = domain.capability(resolveCurrentMember(call))

    override suspend fun changeOwnEmail(
        currentPassword: String,
        newEmail: String,
        newEmailRepeat: String,
    ): OwnEmailChangeResultDto =
        domain.changeOwn(
            actor = resolveCurrentMember(call),
            currentPassword = currentPassword,
            newEmail = newEmail,
            newEmailRepeat = newEmailRepeat,
            ownRawSessionToken = extractSessionToken(call),
        )

    override suspend fun getOwnPendingEmailChange(): OwnPendingEmailChangeStateDto =
        OwnPendingEmailChangeStateDto(pending = domain.ownPending(resolveCurrentMember(call)))

    override suspend fun acceptOwnPendingEmailChange(
        changeId: String,
        currentPassword: String,
    ): MemberDto =
        domain.acceptOwn(
            actor = resolveCurrentMember(call),
            changeIdRaw = changeId,
            currentPassword = currentPassword,
            ownRawSessionToken = extractSessionToken(call),
        )

    override suspend fun declineOwnPendingEmailChange(changeId: String) {
        domain.declineOwn(actor = resolveCurrentMember(call), changeIdRaw = changeId)
    }

    override suspend fun proposeEmailChange(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
    ): EmailChangePendingDto =
        domain.propose(actor = resolveCurrentMember(call), targetIdRaw = memberId, newEmail = newEmail, newEmailRepeat = newEmailRepeat)

    override suspend fun requestEmailChangeOverride(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
        reason: String,
    ): EmailChangePendingDto =
        domain.requestOverride(
            actor = resolveCurrentMember(call),
            targetIdRaw = memberId,
            newEmail = newEmail,
            newEmailRepeat = newEmailRepeat,
            reason = reason,
        )

    override suspend fun getPendingEmailChangeForMember(memberId: String): PendingEmailChangeLookupDto =
        PendingEmailChangeLookupDto(
            pending = domain.pendingForAdministration(actor = resolveCurrentMember(call), targetIdRaw = memberId),
        )

    override suspend fun withdrawEmailChange(changeId: String) {
        domain.withdraw(actor = resolveCurrentMember(call), changeIdRaw = changeId)
    }
}
