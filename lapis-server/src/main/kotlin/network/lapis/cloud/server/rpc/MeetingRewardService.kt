package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.meetingreward.MAX_LIST_LIMIT
import network.lapis.cloud.server.meetingreward.MEETING_REWARD_POLICY_ROLES
import network.lapis.cloud.server.meetingreward.MEETING_REWARD_SETTLEMENT_ROLES
import network.lapis.cloud.server.meetingreward.MeetingRewardPayout
import network.lapis.cloud.server.meetingreward.MeetingRewardPolicyOperations
import network.lapis.cloud.server.meetingreward.MeetingRewardSettlementOperations
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MeetingRewardOwnLineDto
import network.lapis.cloud.shared.domain.MeetingRewardPolicyProposalInput
import network.lapis.cloud.shared.domain.MeetingRewardPolicyViewDto
import network.lapis.cloud.shared.domain.MeetingRewardResolutionOptionDto
import network.lapis.cloud.shared.domain.MeetingRewardSettlementAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingRewardSettlementViewDto
import network.lapis.cloud.shared.rpc.IMeetingRewardService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.316 -- the RPC shell of the LTR recognition for attendance at a Sitzung (see [IMeetingRewardService], `docs/features/meeting-attendance-recognition.adoc`,
 * `docs/architecture/meeting-attendance-recognition.adoc`). Everything except [listMyMeetingRewardLines] is administrator-only; each call is ONE
 * transaction without an external effect, the audit entry is the last write. The business rules live in `network.lapis.cloud.server.meetingreward`.
 * The tax and legal classification of the recognition has NOT been reviewed.
 */
class MeetingRewardService(
    private val call: ApplicationCall,
) : IMeetingRewardService {
    private fun policyActor(): CurrentMember =
        resolveCurrentMember(call).also { it.requireRole(*MEETING_REWARD_POLICY_ROLES.toTypedArray()) }

    private fun settlementActor(): CurrentMember =
        resolveCurrentMember(call).also { it.requireRole(*MEETING_REWARD_SETTLEMENT_ROLES.toTypedArray()) }

    override suspend fun getMeetingRewardPolicy(): MeetingRewardPolicyViewDto {
        val current = policyActor()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardPolicyOperations.view(current)
        }
    }

    override suspend fun listMeetingRewardResolutionOptions(): List<MeetingRewardResolutionOptionDto> {
        val current = policyActor()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardPolicyOperations.resolutionOptions()
        }
    }

    override suspend fun proposeMeetingRewardPolicyChange(input: MeetingRewardPolicyProposalInput): MeetingRewardPolicyViewDto {
        val current = policyActor()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardPolicyOperations.propose(current = current, input = input)
            MeetingRewardPolicyOperations.view(current)
        }
    }

    override suspend fun confirmMeetingRewardPolicyChange(changeId: String): MeetingRewardPolicyViewDto {
        val current = policyActor()
        val id = changeId.toRewardUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardPolicyOperations.confirm(current = current, changeId = id)
            MeetingRewardPolicyOperations.view(current)
        }
    }

    override suspend fun withdrawMeetingRewardPolicyChange(changeId: String): MeetingRewardPolicyViewDto {
        val current = policyActor()
        val id = changeId.toRewardUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardPolicyOperations.withdraw(current = current, changeId = id)
            MeetingRewardPolicyOperations.view(current)
        }
    }

    override suspend fun disableMeetingReward(): MeetingRewardPolicyViewDto {
        val current = policyActor()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardPolicyOperations.disable(current)
            MeetingRewardPolicyOperations.view(current)
        }
    }

    override suspend fun previewMeetingRewardSettlement(meetingId: String): MeetingRewardSettlementViewDto {
        val current = settlementActor()
        val id = meetingId.toRewardUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            MeetingRewardSettlementOperations.view(current = current, meetingId = id)
        }
    }

    override suspend fun prepareMeetingRewardSettlement(
        meetingId: String,
        previewHash: String,
    ): MeetingRewardSettlementViewDto {
        val current = settlementActor()
        val id = meetingId.toRewardUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val prepared = MeetingRewardSettlementOperations.prepare(current = current, meetingId = id, expectedHash = previewHash)
            audit(
                current = current,
                entityId = prepared.settlementId,
                action = AuditAction.CREATE,
                snapshot =
                    MeetingRewardSettlementAuditSnapshot(
                        event = "PREPARED",
                        meetingId = id.toString(),
                        settlementId = prepared.settlementId.toString(),
                        policyChangeId = prepared.policyChangeId.toString(),
                        protocolVersionId = prepared.protocolVersionId.toString(),
                        previewHash = prepared.previewHash,
                        lineCount = prepared.lineCount,
                        paidCount = prepared.paidCount,
                        totalAmount = prepared.total.toPlainString(),
                        preparerIsBeneficiary = prepared.preparerIsBeneficiary,
                    ),
            )
            MeetingRewardSettlementOperations.view(current = current, meetingId = id)
        }
    }

    override suspend fun releaseMeetingRewardSettlement(
        meetingId: String,
        previewHash: String,
    ): MeetingRewardSettlementViewDto {
        val current = settlementActor()
        val id = meetingId.toRewardUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            when (val outcome = MeetingRewardPayout.release(current = current, meetingId = id, expectedHash = previewHash)) {
                is MeetingRewardPayout.Outcome.AlreadyReleased -> Unit
                is MeetingRewardPayout.Outcome.Released ->
                    audit(
                        current = current,
                        entityId = outcome.settlementId,
                        action = AuditAction.UPDATE,
                        snapshot =
                            MeetingRewardSettlementAuditSnapshot(
                                event = "RELEASED",
                                meetingId = id.toString(),
                                settlementId = outcome.settlementId.toString(),
                                policyChangeId = outcome.policyChangeId.toString(),
                                protocolVersionId = outcome.protocolVersionId.toString(),
                                previewHash = outcome.previewHash,
                                lineCount = outcome.lineCount,
                                paidCount = outcome.paidCount,
                                totalAmount = outcome.total.toPlainString(),
                                releaserIsBeneficiary = outcome.releaserIsBeneficiary,
                            ),
                    )
            }
            MeetingRewardSettlementOperations.view(current = current, meetingId = id)
        }
    }

    override suspend fun discardMeetingRewardSettlement(meetingId: String): MeetingRewardSettlementViewDto {
        val current = settlementActor()
        val id = meetingId.toRewardUuid()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val discarded = MeetingRewardSettlementOperations.discard(id)
            audit(
                current = current,
                entityId = discarded.settlementId,
                action = AuditAction.UPDATE,
                snapshot =
                    MeetingRewardSettlementAuditSnapshot(
                        event = "DISCARDED",
                        meetingId = id.toString(),
                        settlementId = discarded.settlementId.toString(),
                        policyChangeId = discarded.policyChangeId.toString(),
                        protocolVersionId = discarded.protocolVersionId.toString(),
                        previewHash = discarded.previewHash,
                        lineCount = discarded.lineCount,
                    ),
            )
            MeetingRewardSettlementOperations.view(current = current, meetingId = id)
        }
    }

    /** The caller's OWN recognitions. No role gate (every member sees what they received); there is no member parameter. */
    override suspend fun listMyMeetingRewardLines(limit: Int): List<MeetingRewardOwnLineDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            MeetingRewardSettlementOperations.ownLines(
                memberId = current.memberId,
                limit = limit.coerceIn(1, MAX_LIST_LIMIT),
            )
        }
    }

    private fun audit(
        current: CurrentMember,
        entityId: Uuid,
        action: AuditAction,
        snapshot: MeetingRewardSettlementAuditSnapshot,
    ) {
        AuditLogRecorder.record(
            actorMemberId = current.memberId,
            actorRole = current.role,
            entityType = AuditEntityType.MEETING_REWARD_SETTLEMENT,
            entityId = entityId,
            action = action,
            before = null,
            after = Json.encodeToString(MeetingRewardSettlementAuditSnapshot.serializer(), snapshot),
        )
    }

    private fun String.toRewardUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Not found") }
}
