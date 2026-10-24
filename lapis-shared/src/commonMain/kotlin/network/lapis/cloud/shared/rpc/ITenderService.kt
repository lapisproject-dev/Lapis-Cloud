package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderAwardMotionOptionDto
import network.lapis.cloud.shared.domain.TenderBidDto
import network.lapis.cloud.shared.domain.TenderBidInput
import network.lapis.cloud.shared.domain.TenderCreateInput
import network.lapis.cloud.shared.domain.TenderDefaultsDto
import network.lapis.cloud.shared.domain.TenderDetailDto
import network.lapis.cloud.shared.domain.TenderDraftUpdateInput
import network.lapis.cloud.shared.domain.TenderMotionOptionDto
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TenderSummaryDto

/**
 * V1.9.201 -- tenders, wave 1 (see `docs/features/tenders.adoc`). A tender implements an ADOPTED resolution: the board publishes a task with a
 * deadline, participants (members, friends and guests) bid in LTR, the board awards and finally accepts the result. Nothing here moves LTR
 * (the payout is wave 3). **Registered LAST** in `Application.kt` (Kilua numbers routes by declaration order); new methods go to the END of
 * this interface.
 *
 * Roles: BOARD and ADMIN manage ([createTender], [updateTenderDraft], [publishTender], [cancelTender], [registerAwardMotion], [awardTender],
 * [recordAcceptance]); TREASURER only has the board's read view. Whoever has ANY bid row on a tender (even a withdrawn one) may neither award nor
 * accept nor register an award motion on it, whatever their role ([ForbiddenException]).
 */
@RpcService
interface ITenderService {
    suspend fun createTender(input: TenderCreateInput): TenderDetailDto

    suspend fun updateTenderDraft(
        tenderId: String,
        input: TenderDraftUpdateInput,
    ): TenderDetailDto

    suspend fun publishTender(tenderId: String): TenderDetailDto

    suspend fun cancelTender(
        tenderId: String,
        reason: String,
    ): TenderDetailDto

    suspend fun getTender(tenderId: String): TenderDetailDto

    suspend fun listTenders(statusFilter: TenderStatus? = null): List<TenderSummaryDto>

    /** Creates the caller's bid or replaces it. Only before the deadline, strictly. */
    suspend fun submitBid(
        tenderId: String,
        input: TenderBidInput,
    ): TenderBidDto

    suspend fun withdrawBid(tenderId: String): TenderBidDto

    suspend fun listMyBids(): List<TenderBidDto>

    /** BEST_OFFER only: binds the committee resolution that decides the award. Only once the tender is CLOSED. */
    suspend fun registerAwardMotion(
        tenderId: String,
        motionId: String,
    ): TenderDetailDto

    suspend fun awardTender(input: TenderAwardInput): TenderDetailDto

    suspend fun recordAcceptance(input: TenderAcceptanceInput): TenderDetailDto

    /** BOARD, ADMIN and TREASURER. */
    suspend fun getTenderDefaults(): TenderDefaultsDto

    /** V1.9.202, BOARD/ADMIN: resolved motions (and the not yet resolved ones, with the reason) that can be bound to a new tender. */
    suspend fun listTenderMotionOptions(): List<TenderMotionOptionDto>

    /** V1.9.202, BOARD/ADMIN who is no bidder: candidates for the award motion of a BEST_OFFER tender, each with the reason if unusable. */
    suspend fun listAwardMotionOptions(tenderId: String): List<TenderAwardMotionOptionDto>

    /** V1.9.202, BOARD/ADMIN: the server-rendered, sanitized HTML of a Markdown description (rate-limited). */
    suspend fun previewTenderDescription(markdown: String): String
}
