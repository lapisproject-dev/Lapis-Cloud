package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberFinderBlockDto
import network.lapis.cloud.shared.domain.MemberFinderBoardOverviewDto
import network.lapis.cloud.shared.domain.MemberFinderConnectionDto
import network.lapis.cloud.shared.domain.MemberFinderConsentTextDto
import network.lapis.cloud.shared.domain.MemberFinderIncomingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderResponseDto
import network.lapis.cloud.shared.domain.MemberFinderStateDto

/**
 * V1.9.144 -- the optional member finder (see `docs/features/member-finder.adoc`). Every member method requires an ACTIVE, non-anonymized
 * member and an enabled instance switch ([MemberFinderUnavailableException] otherwise); [getState] and [optOut] are the two exceptions
 * (see their KDoc). No method ever returns an identity before both sides agreed, and [sendRequest] answers every case with the same shape.
 * **Registered LAST** in `Application.kt` (Kilua numbers routes by declaration order); new methods go to the END of this interface.
 */
@RpcService
interface IMemberFinderService {
    /** Never throws because the switch is off: it then answers `offered = false`. */
    suspend fun getState(): MemberFinderStateDto

    suspend fun getConsentText(): MemberFinderConsentTextDto

    /** The version label and hash are those of [getConsentText]; a stale pair throws [MemberFinderConsentOutdatedException]. */
    suspend fun optIn(
        consentVersion: String,
        consentSha256: String,
        interest: MemberFinderInterest,
        adultConfirmed: Boolean,
    ): MemberFinderStateDto

    /** ALWAYS possible for an ACTIVE member, even while the switch is off (Art. 7(3) GDPR); never subject to the 24-hour rule. */
    suspend fun optOut(): MemberFinderStateDto

    /** Always answers [network.lapis.cloud.shared.domain.MemberFinderOutgoingState.PENDING] with the same shape, whether or not anybody was found. */
    suspend fun sendRequest(
        purpose: MemberFinderPurpose,
        note: String?,
    ): MemberFinderOutgoingRequestDto

    suspend fun listOutgoingRequests(): List<MemberFinderOutgoingRequestDto>

    suspend fun withdrawRequest(requestId: String)

    suspend fun listIncomingRequests(): List<MemberFinderIncomingRequestDto>

    /** The answer carries the connection when [accept] is true, else none. With [block] on a refusal the (unnamed) sender is blocked. */
    suspend fun respondToRequest(
        requestId: String,
        accept: Boolean,
        block: Boolean,
    ): MemberFinderResponseDto

    suspend fun listConnections(): List<MemberFinderConnectionDto>

    /** Only for a connection both sides agreed to. */
    suspend fun contactConnection(
        connectionId: String,
        message: String,
    ): DirectMessageDto

    suspend fun blockConnection(connectionId: String)

    suspend fun listBlocks(): List<MemberFinderBlockDto>

    suspend fun unblock(blockId: String)

    /** BOARD/ADMIN only; bands only. */
    suspend fun getBoardOverview(): MemberFinderBoardOverviewDto
}
