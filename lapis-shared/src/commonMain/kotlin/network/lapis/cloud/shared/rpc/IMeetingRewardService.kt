package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MeetingRewardOwnLineDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyProposalInput
import network.lapis.cloud.shared.domain.MeetingRewardPolicyViewDto
import network.lapis.cloud.shared.domain.MeetingRewardResolutionOptionDto
import network.lapis.cloud.shared.domain.MeetingRewardSettlementViewDto

/**
 * V1.9.316 -- LTR recognition for attendance at a Sitzung (see `docs/features/meeting-attendance-recognition.adoc`). An ISSUANCE of LTR, never a
 * payment, never EUR; the tax and legal classification has NOT been reviewed. **Registered LAST** in `Application.kt` (Kilua numbers routes by
 * declaration order); new methods go to the END of this interface.
 *
 * Every method except [listMyMeetingRewardLines] is administrator-only. The rules change only through a resolution of the general assembly plus a
 * second, tenured administrator (four eyes); a settlement is prepared by one and released by another administrator. The caller of
 * [listMyMeetingRewardLines] sees their own lines only (there is no member parameter).
 */
@RpcService
interface IMeetingRewardService {
    suspend fun getMeetingRewardPolicy(): MeetingRewardPolicyViewDto

    suspend fun listMeetingRewardResolutionOptions(): List<MeetingRewardResolutionOptionDto>

    suspend fun proposeMeetingRewardPolicyChange(input: MeetingRewardPolicyProposalInput): MeetingRewardPolicyViewDto

    suspend fun confirmMeetingRewardPolicyChange(changeId: String): MeetingRewardPolicyViewDto

    suspend fun withdrawMeetingRewardPolicyChange(changeId: String): MeetingRewardPolicyViewDto

    suspend fun disableMeetingReward(): MeetingRewardPolicyViewDto

    suspend fun previewMeetingRewardSettlement(meetingId: String): MeetingRewardSettlementViewDto

    suspend fun prepareMeetingRewardSettlement(
        meetingId: String,
        previewHash: String,
    ): MeetingRewardSettlementViewDto

    suspend fun releaseMeetingRewardSettlement(
        meetingId: String,
        previewHash: String,
    ): MeetingRewardSettlementViewDto

    suspend fun discardMeetingRewardSettlement(meetingId: String): MeetingRewardSettlementViewDto

    suspend fun listMyMeetingRewardLines(limit: Int = 200): List<MeetingRewardOwnLineDto>
}
