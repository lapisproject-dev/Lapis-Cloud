package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * V1.9.316 -- LTR recognition for attendance at a Sitzung ("Anerkennung fuer Sitzungsteilnahme"). See 83-meeting-reward.kuml.kts,
 * docs/features/meeting-attendance-recognition.adoc and docs/architecture/meeting-attendance-recognition.adoc.
 *
 * It is an ISSUANCE of LTR (one credit row [LtrLedgerEntryType.MEETING_REWARD] per recognised person), never a payment, never EUR. The tax and
 * legal classification has NOT been reviewed.
 */

/** A change of the rules is either a full proposal ([CHANGE], needs a resolution and a second administrator) or the switch-off ([DISABLE]). */
@Serializable
enum class MeetingRewardPolicyChangeKind { CHANGE, DISABLE }

/** [PROPOSED] waits for a second administrator, [CONFIRMED] is in force (or was, until replaced), [WITHDRAWN] was taken back. */
@Serializable
enum class MeetingRewardPolicyChangeStatus { PROPOSED, CONFIRMED, WITHDRAWN }

/** [PREPARED] is a frozen preview waiting for a second administrator, [RELEASED] has been booked to the ledger. */
@Serializable
enum class MeetingRewardSettlementStatus { PREPARED, RELEASED }

/**
 * Why a person gets no (or a reduced) amount. [NOT_PRESENT] stands for UNEXCUSED. A paid line has no reason, except [CAP_REDUCED]. Persisted by name
 * (`meeting_reward_line.reason`, CHECK in V164).
 */
@Serializable
enum class MeetingRewardLineReason {
    EXCUSED,
    REPRESENTED,
    NOT_PRESENT,
    GUEST,
    CAP_REACHED,
    CAP_REDUCED,
    ALREADY_SETTLED,
    MEMBER_NOT_ELIGIBLE,
}

/** Why the settlement of a Sitzung is not available at all. */
@Serializable
enum class MeetingRewardUnavailableReason {
    FEATURE_DISABLED,
    COMMITTEE_NOT_ENABLED,
    MEETING_NOT_HELD,
    PROTOCOL_NOT_CONFIRMED,
    ATTENDANCE_CHANGED_AFTER_PROTOCOL,
}

/** Why the caller cannot do the next step of a proposal or settlement (`null` = may). */
@Serializable
enum class MeetingRewardBlockedReason {
    NOT_ADMIN,
    FOUR_EYES,
    NOT_RELEASABLE,
    APPROVER_NOT_TENURED,
}

/** The committee roles that earn a function bonus. GENERAL_SECRETARY and TREASURER never do. */
val meetingRewardBonusRoles: Set<CommitteeRole> =
    setOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY, CommitteeRole.MANAGING_DIRECTOR)

/** The complete set of rules that is in force or proposed. Amounts are LTR with two decimal places. */
@Serializable
data class MeetingRewardPolicyValuesDto(
    val enabled: Boolean,
    val attendanceAmount: Decimal,
    val bonusChair: Decimal,
    val bonusDeputyChair: Decimal,
    val bonusSecretary: Decimal,
    val bonusManagingDirector: Decimal,
    val capPerPersonPerYear: Decimal,
    val capPerMeeting: Decimal,
    val committeeIds: List<String>,
)

/** An ADOPTED resolution of the general assembly that can be cited as the legal basis of a change. */
@Serializable
data class MeetingRewardResolutionOptionDto(
    val resolutionId: String,
    val number: String,
    val title: String,
    val decidedAt: LocalDateTime,
)

/** One change of the rules, from the proposal to its decision. Names are for administrators only (the whole view is administrator-only). */
@Serializable
data class MeetingRewardPolicyChangeDto(
    val id: String,
    val kind: MeetingRewardPolicyChangeKind,
    val status: MeetingRewardPolicyChangeStatus,
    val proposedByName: String,
    val proposedAt: LocalDateTime,
    val decidedByName: String?,
    val decidedAt: LocalDateTime?,
    val resolution: MeetingRewardResolutionOptionDto?,
    val resolutionReference: String?,
    val old: MeetingRewardPolicyValuesDto,
    val new: MeetingRewardPolicyValuesDto,
)

/** The administrator's view of the rules: what is in force, the open proposal and the history. */
@Serializable
data class MeetingRewardPolicyViewDto(
    val current: MeetingRewardPolicyValuesDto,
    val resolution: MeetingRewardResolutionOptionDto?,
    val resolutionReference: String?,
    val openProposal: MeetingRewardPolicyChangeDto?,
    val canConfirmOpen: Boolean,
    val confirmBlockedReason: MeetingRewardBlockedReason?,
    val history: List<MeetingRewardPolicyChangeDto>,
)

/** A proposal: the complete new rules, the cited resolution of the general assembly and an optional note such as "TOP 7". */
@Serializable
data class MeetingRewardPolicyProposalInput(
    val values: MeetingRewardPolicyValuesDto,
    val resolutionId: String,
    val resolutionReference: String? = null,
)

/** One person's line of a settlement. [functionRole] is the role that earned the bonus (or the highest role, display only, without a bonus). */
@Serializable
data class MeetingRewardLineDto(
    val memberId: String,
    val displayName: String,
    val attendanceStatus: AttendanceStatus,
    val functionRole: CommitteeRole?,
    val baseAmount: Decimal,
    val bonusAmount: Decimal,
    val amount: Decimal,
    val reduced: Boolean,
    val reason: MeetingRewardLineReason?,
)

/** The settlement of one Sitzung: a live preview, a prepared one or a released one. [unavailable] explains why there is none. */
@Serializable
data class MeetingRewardSettlementViewDto(
    val meetingId: String,
    val unavailable: MeetingRewardUnavailableReason?,
    val status: MeetingRewardSettlementStatus?,
    val preparedByName: String?,
    val preparedAt: LocalDateTime?,
    val releasedByName: String?,
    val releasedAt: LocalDateTime?,
    val previewHash: String?,
    val lines: List<MeetingRewardLineDto>,
    val total: Decimal,
    val callerIsBeneficiary: Boolean,
    val canPrepare: Boolean,
    val canRelease: Boolean,
    val canDiscard: Boolean,
    val blockedReason: MeetingRewardBlockedReason?,
)

/** A recognition the caller RECEIVED (own lines only). [ledgerEntryId] links the ledger row to this explanation. */
@Serializable
data class MeetingRewardOwnLineDto(
    val meetingId: String,
    val committeeName: String,
    val meetingDate: LocalDate,
    val functionRole: CommitteeRole?,
    val amount: Decimal,
    val bonusAmount: Decimal,
    val ledgerEntryId: String,
)

/** The values of the rules inside an audit entry: plain strings, so the append-only snapshot never depends on a number format. */
@Serializable
data class MeetingRewardPolicyAuditValues(
    val enabled: Boolean,
    val attendanceAmount: String,
    val bonusChair: String,
    val bonusDeputyChair: String,
    val bonusSecretary: String,
    val bonusManagingDirector: String,
    val capPerPersonPerYear: String,
    val capPerMeeting: String,
)

/**
 * After-snapshot of an [AuditEntityType.MEETING_REWARD_POLICY] entry. [event] is PROPOSED, CONFIRMED, WITHDRAWN or DISABLED. Ids, enums and amounts
 * only -- never a name.
 */
@Serializable
data class MeetingRewardPolicyAuditSnapshot(
    val event: String,
    val changeId: String,
    val kind: MeetingRewardPolicyChangeKind,
    val resolutionId: String? = null,
    val resolutionReference: String? = null,
    val old: MeetingRewardPolicyAuditValues,
    val new: MeetingRewardPolicyAuditValues,
    val oldCommitteeIds: List<String>,
    val newCommitteeIds: List<String>,
)

/** After-snapshot of an [AuditEntityType.MEETING_REWARD_SETTLEMENT] entry. [event] is PREPARED, RELEASED or DISCARDED. Ids, enums and counts only. */
@Serializable
data class MeetingRewardSettlementAuditSnapshot(
    val event: String,
    val meetingId: String,
    val settlementId: String? = null,
    val policyChangeId: String? = null,
    val protocolVersionId: String? = null,
    val previewHash: String? = null,
    val lineCount: Int = 0,
    val paidCount: Int = 0,
    val totalAmount: String? = null,
    val releaserIsBeneficiary: Boolean = false,
    val preparerIsBeneficiary: Boolean = false,
)
