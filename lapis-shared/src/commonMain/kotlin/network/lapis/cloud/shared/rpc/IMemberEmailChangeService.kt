package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangePendingDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OwnEmailChangeResultDto
import network.lapis.cloud.shared.domain.OwnPendingEmailChangeStateDto
import network.lapis.cloud.shared.domain.PendingEmailChangeLookupDto

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the ONLY way to change the login address of an existing member.
 * `member.email` is the login and password-reset identity; a pending change redirects neither. A third party can never
 * change it immediately: it takes effect either through the OWNER's password, or -- where no password exists --
 * through proof of ownership of the new address plus a 72 hour warning period (the old address is warned and can
 * reject). See docs/architecture/member-email-change.adoc.
 *
 * Errors are typed ([EmailChangeMailUnavailableException], [EmailChangeRepeatMismatchException],
 * [EmailChangeNotAllowedException], [EmailChangeAlreadyCurrentException], [EmailChangePendingNotFoundException],
 * [EmailChangeRateLimitedException], [MemberEmailInUseException], [MemberEmailTooLongException], [InvalidPasswordException],
 * [ForbiddenException]); Kilua RPC transmits only the subclass, never a message.
 */
@RpcService
interface IMemberEmailChangeService {
    /** Role: any signed-in member. Read only. */
    suspend fun getEmailChangeCapability(): EmailChangeCapabilityDto

    /**
     * Role: any signed-in member with a password account, on the caller's OWN address. Takes effect immediately; the
     * other sessions end, the old address is informed (when mail is configured).
     */
    suspend fun changeOwnEmail(
        currentPassword: String,
        newEmail: String,
        newEmailRepeat: String,
    ): OwnEmailChangeResultDto

    /** Role: any signed-in member. The open change addressed to the caller (`pending == null` when there is none). */
    suspend fun getOwnPendingEmailChange(): OwnPendingEmailChangeStateDto

    /** Role: the owner. Accepts a [network.lapis.cloud.shared.domain.EmailChangeKind.PROPOSAL] with the current password. */
    suspend fun acceptOwnPendingEmailChange(
        changeId: String,
        currentPassword: String,
    ): MemberDto

    /** Role: the owner. Rejects (REVOKED) the open change. */
    suspend fun declineOwnPendingEmailChange(changeId: String)

    /**
     * Role: BOARD against targets without BOARD/TREASURER/ADMIN role; ADMIN against everyone but themselves. Needs mail.
     * Supersedes an earlier open proposal for the same target.
     */
    suspend fun proposeEmailChange(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
    ): EmailChangePendingDto

    /** Role: ADMIN only, never against oneself. Emergency path: [reason] 10-500 characters. Needs mail. */
    suspend fun requestEmailChangeOverride(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
        reason: String,
    ): EmailChangePendingDto

    /** Role: BOARD/ADMIN (same reach as the proposal). The open change of [memberId], address masked (`pending == null` when there is none). */
    suspend fun getPendingEmailChangeForMember(memberId: String): PendingEmailChangeLookupDto

    /** Role: the initiator, or an ADMIN. Withdraws (WITHDRAWN) the open change. */
    suspend fun withdrawEmailChange(changeId: String)
}
