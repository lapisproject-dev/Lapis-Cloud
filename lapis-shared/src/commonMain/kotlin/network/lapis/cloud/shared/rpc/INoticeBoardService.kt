package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.domain.NoticeInput
import network.lapis.cloud.shared.domain.NoticeListQuery
import network.lapis.cloud.shared.domain.NoticePageDto
import network.lapis.cloud.shared.domain.NoticeRemovalInput
import network.lapis.cloud.shared.domain.NoticeUpdateInput

/**
 * V1.9.297 "Schwarzes Brett" -- the members' bulletin board. EVERY method is [network.lapis.cloud.shared.domain.MemberStatusSets.NOTICE_BOARD]
 * only (ACTIVE; no guest and no friend, not even for reading). The order of the board depends on the LTR members spend on a notice, with the same
 * 10 % decay per day as the social network; pinned notices stay on top. An unknown, invalid, expired, withdrawn or removed id is ONE answer
 * (`NotFoundException`), also for a notice whose author is no longer a member.
 *
 * Kilua RPC numbers its routes by declaration order: new methods go to the END, never in between.
 */
@RpcService
interface INoticeBoardService {
    /** Pinned notices plus one page of the rest, sorted by LTR weight (newest first on a tie). */
    suspend fun listNotices(query: NoticeListQuery = NoticeListQuery()): NoticePageDto

    /** The caller's own notices in every state until they are deleted for good, newest first, with the removal reason. */
    suspend fun listMyNotices(): List<NoticeDto>

    suspend fun getNotice(id: String): NoticeDto

    /** At most [network.lapis.cloud.shared.domain.NoticeRules.MAX_ACTIVE_PER_MEMBER] active notices per member. An optional initial LTR stake is booked atomically. */
    suspend fun createNotice(input: NoticeInput): NoticeDto

    /** Author only, while the notice is active. */
    suspend fun updateNotice(
        id: String,
        input: NoticeUpdateInput,
    ): NoticeDto

    /** Author only. Already spent LTR is NOT refunded. */
    suspend fun withdrawNotice(id: String): NoticeDto

    /** Spend free LTR on a notice (own notice allowed). 0.01 minimum, two decimals, never refunded. */
    suspend fun boostNotice(
        id: String,
        amountLtr: Decimal,
    ): NoticeDto

    /** Sends a direct message to the author; the server prepends `Schwarzes Brett: <title>` as the first line. */
    suspend fun replyToNotice(
        id: String,
        message: String,
    ): DirectMessageDto

    /** BOARD/ADMIN. At most [network.lapis.cloud.shared.domain.NoticeRules.MAX_PINNED] pinned at once. */
    suspend fun pinNotice(id: String): NoticeDto

    /** BOARD/ADMIN. */
    suspend fun unpinNotice(id: String): NoticeDto

    /** BOARD/ADMIN removes somebody else's notice with a mandatory reason. */
    suspend fun removeNotice(
        id: String,
        input: NoticeRemovalInput,
    ): NoticeDto
}
