package network.lapis.cloud.server.meetingreward

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MeetingRewardBlockedReason
import network.lapis.cloud.shared.domain.MeetingRewardLineReason
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.meetingRewardBonusRoles
import network.lapis.cloud.shared.domain.rank
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlin.uuid.Uuid

/*
 * V1.9.316 -- the pure rules of the LTR recognition for attendance at a Sitzung. No database access, no ledger access: the booking itself lives in
 * `MeetingRewardPayout` (the ONE file that may touch the ledger, `MeetingRewardLedgerTripwireTest` pins this), so every decision here can be tested
 * exhaustively. Recognition is an ISSUANCE of LTR, never a payment, never EUR. The tax and legal classification has NOT been reviewed.
 */

/** Who may propose, confirm, withdraw and switch off the rules (design: administrators only, deliberately its own constant). */
internal val MEETING_REWARD_POLICY_ROLES: Set<AccountRole> = setOf(AccountRole.ADMIN)

/** Who may prepare, release and discard a settlement (design: administrators only, deliberately its own constant). */
internal val MEETING_REWARD_SETTLEMENT_ROLES: Set<AccountRole> = setOf(AccountRole.ADMIN)

// Fixed texts. NEVER a name, an amount or a text of a person in them.
internal const val FEATURE_DISABLED = "The recognition for meetings is not switched on"
internal const val COMMITTEE_NOT_ENABLED = "The recognition is not switched on for this committee"
internal const val MEETING_NOT_HELD = "The meeting was not held"
internal const val PROTOCOL_NOT_CONFIRMED = "The protocol of the meeting is not confirmed"
internal const val ATTENDANCE_CHANGED_AFTER_PROTOCOL = "The attendance differs from the confirmed protocol"
internal const val PREVIEW_STALE = "The settlement changed, please prepare it again"
internal const val FOUR_EYES_REQUIRED = "A second administrator is required"
internal const val APPROVER_NOT_TENURED = "The confirming administrator has not held the role long enough"
internal const val RESOLUTION_INVALID = "The resolution is not an adopted resolution of the general assembly"
internal const val POLICY_PROPOSAL_OPEN = "A proposal is already open"
internal const val POLICY_PROPOSAL_STALE = "The rules changed since the proposal was made"
internal const val ENABLE_NEEDS_RESOLUTION = "Switching on needs a resolution of the general assembly"
internal const val MEETING_CAP_EXCEEDED = "The total of the settlement exceeds the limit per meeting"
internal const val ALREADY_RELEASED = "The settlement was already released"
internal const val AMOUNT_SCALE_INVALID = "An amount has more than two decimal places"
internal const val AMOUNT_OUT_OF_RANGE = "An amount is out of range"
internal const val COMMITTEE_INVALID = "A committee is unknown"
internal const val REFERENCE_TOO_LONG = "The reference is too long"
internal const val NOT_AVAILABLE = "This action is not available in the current state"
internal const val CONCURRENT_CHANGE = "The data was changed concurrently, please reload"

internal val MAX_BONUS_OR_BASE: BigDecimal = BigDecimal("10000.00")
internal val MAX_CAP: BigDecimal = BigDecimal("1000000.00")
internal const val MAX_REFERENCE_LENGTH = 200
internal const val MAX_COMMITTEES = 200
internal const val MAX_LIST_LIMIT = 500

/** The rules as the server works with them (amounts always at scale 2). */
internal data class MeetingRewardPolicyValues(
    val enabled: Boolean,
    val attendanceAmount: BigDecimal,
    val bonusChair: BigDecimal,
    val bonusDeputyChair: BigDecimal,
    val bonusSecretary: BigDecimal,
    val bonusManagingDirector: BigDecimal,
    val capPerPersonPerYear: BigDecimal,
    val capPerMeeting: BigDecimal,
    val committeeIds: Set<Uuid>,
) {
    /** Equal in every figure; scale differences do not matter, the order of the committees does not either. */
    fun sameAs(other: MeetingRewardPolicyValues): Boolean =
        enabled == other.enabled &&
            attendanceAmount.compareTo(other.attendanceAmount) == 0 &&
            bonusChair.compareTo(other.bonusChair) == 0 &&
            bonusDeputyChair.compareTo(other.bonusDeputyChair) == 0 &&
            bonusSecretary.compareTo(other.bonusSecretary) == 0 &&
            bonusManagingDirector.compareTo(other.bonusManagingDirector) == 0 &&
            capPerPersonPerYear.compareTo(other.capPerPersonPerYear) == 0 &&
            capPerMeeting.compareTo(other.capPerMeeting) == 0 &&
            committeeIds == other.committeeIds
}

/** One row of the attendance table, reduced to what the rules need. */
internal data class AttendanceFact(
    val memberId: Uuid,
    val status: AttendanceStatus,
)

/** One computed line. [reason] is `null` for a plainly paid line. */
internal data class ComputedLine(
    val memberId: Uuid,
    val attendanceStatus: AttendanceStatus,
    val functionRole: CommitteeRole?,
    val baseAmount: BigDecimal,
    val bonusAmount: BigDecimal,
    val amount: BigDecimal,
    val reduced: Boolean,
    val reason: MeetingRewardLineReason?,
    val periodYear: Int,
)

private val ZERO: BigDecimal = BigDecimal("0.00")

/**
 * Validates one amount: at most two decimal places (nothing is ever rounded), at least 0 and at most [max]. Returns the amount at scale 2.
 * [AMOUNT_SCALE_INVALID] / [AMOUNT_OUT_OF_RANGE] are thrown as [ConflictException].
 */
internal fun validateAmount(
    value: BigDecimal,
    max: BigDecimal,
): BigDecimal {
    if (value.stripTrailingZeros().scale() > 2) throw ConflictException(AMOUNT_SCALE_INVALID)
    val normalized = value.setScale(2, RoundingMode.UNNECESSARY)
    if (normalized.signum() < 0 || normalized > max) throw ConflictException(AMOUNT_OUT_OF_RANGE)
    return normalized
}

/**
 * The function bonus: the MAXIMUM of the configured amounts over the bonus roles the person held (never the sum). Only CHAIR, DEPUTY_CHAIR, SECRETARY
 * and MANAGING_DIRECTOR count; GENERAL_SECRETARY and TREASURER get no bonus. The returned role is the one that earned the maximum (the lowest rank on a
 * tie); without a bonus it is the person's highest role by rank, for display only (or `null` without any role).
 */
internal fun bonusFor(
    roles: Set<CommitteeRole>,
    policy: MeetingRewardPolicyValues,
): Pair<CommitteeRole?, BigDecimal> {
    fun amountOf(role: CommitteeRole): BigDecimal =
        when (role) {
            CommitteeRole.CHAIR -> policy.bonusChair
            CommitteeRole.DEPUTY_CHAIR -> policy.bonusDeputyChair
            CommitteeRole.SECRETARY -> policy.bonusSecretary
            CommitteeRole.MANAGING_DIRECTOR -> policy.bonusManagingDirector
            else -> ZERO
        }
    val bonusRoles = roles.filter { it in meetingRewardBonusRoles }
    val best = bonusRoles.maxOfOrNull { amountOf(it) } ?: ZERO
    if (best.signum() > 0) {
        val role = bonusRoles.filter { amountOf(it).compareTo(best) == 0 }.minBy { it.rank }
        return role to best.setScale(2, RoundingMode.UNNECESSARY)
    }
    return roles.minByOrNull { it.rank } to ZERO
}

private fun unpaid(
    fact: AttendanceFact,
    reason: MeetingRewardLineReason,
    year: Int,
) = ComputedLine(
    memberId = fact.memberId,
    attendanceStatus = fact.status,
    functionRole = null,
    baseAmount = ZERO,
    bonusAmount = ZERO,
    amount = ZERO,
    reduced = false,
    reason = reason,
    periodYear = year,
)

/**
 * The lines of a settlement, one per attendance row, sorted by member id. Order of the checks per person:
 *  1. not PRESENT: nothing, with the reason of the status (a represented person gets nothing; a representative is paid only for their OWN PRESENT row,
 *     never for representing);
 *  2. [alreadySettled]: nothing (ALREADY_SETTLED);
 *  3. the member is a guest or outside the LTR-eligible organisation members: nothing (MEMBER_NOT_ELIGIBLE);
 *  4. total = base + function bonus, then the yearly cap: no room left = CAP_REACHED (0.00), less room than the total = the rest (CAP_REDUCED).
 * [alreadyPaidThisYear] is what the person got earlier in [year] (released lines of OTHER meetings).
 */
internal fun computeLines(
    attendance: List<AttendanceFact>,
    year: Int,
    rolesByMember: Map<Uuid, Set<CommitteeRole>>,
    statusByMember: Map<Uuid, MemberStatus>,
    alreadyPaidThisYear: Map<Uuid, BigDecimal>,
    alreadySettled: Set<Uuid>,
    policy: MeetingRewardPolicyValues,
): List<ComputedLine> =
    attendance
        .sortedBy { it.memberId.toString() }
        .map { fact ->
            when {
                fact.status == AttendanceStatus.EXCUSED -> unpaid(fact = fact, reason = MeetingRewardLineReason.EXCUSED, year = year)
                fact.status == AttendanceStatus.UNEXCUSED -> unpaid(fact = fact, reason = MeetingRewardLineReason.NOT_PRESENT, year = year)
                fact.status == AttendanceStatus.REPRESENTED ->
                    unpaid(
                        fact = fact,
                        reason = MeetingRewardLineReason.REPRESENTED,
                        year = year,
                    )
                fact.status == AttendanceStatus.GUEST -> unpaid(fact = fact, reason = MeetingRewardLineReason.GUEST, year = year)
                fact.memberId in alreadySettled -> unpaid(fact = fact, reason = MeetingRewardLineReason.ALREADY_SETTLED, year = year)
                !isRecognisable(
                    statusByMember[fact.memberId],
                ) -> unpaid(fact = fact, reason = MeetingRewardLineReason.MEMBER_NOT_ELIGIBLE, year = year)
                else ->
                    paidLine(
                        fact = fact,
                        year = year,
                        roles = rolesByMember[fact.memberId].orEmpty(),
                        paidBefore =
                            alreadyPaidThisYear[fact.memberId] ?: ZERO,
                        policy = policy,
                    )
            }
        }

/** Only an organisation member that can hold LTR is recognised (a guest keeps the account on the home server; a friend is not identity-verified). */
internal fun isRecognisable(status: MemberStatus?): Boolean =
    status != null &&
        status != MemberStatus.GUEST &&
        status in MemberStatusSets.LTR_ELIGIBLE &&
        status in MemberStatusSets.ORGANIZATION_MEMBER

private fun paidLine(
    fact: AttendanceFact,
    year: Int,
    roles: Set<CommitteeRole>,
    paidBefore: BigDecimal,
    policy: MeetingRewardPolicyValues,
): ComputedLine {
    val (role, bonus) = bonusFor(roles = roles, policy = policy)
    val base = policy.attendanceAmount.setScale(2, RoundingMode.UNNECESSARY)
    val total = base + bonus
    val line =
        ComputedLine(
            memberId = fact.memberId,
            attendanceStatus = fact.status,
            functionRole = role,
            baseAmount = base,
            bonusAmount = bonus,
            amount = total,
            reduced = false,
            reason = null,
            periodYear = year,
        )
    if (total.signum() == 0) return line
    val remaining = policy.capPerPersonPerYear - paidBefore
    return when {
        remaining.signum() <= 0 -> line.copy(amount = ZERO, reason = MeetingRewardLineReason.CAP_REACHED)
        total > remaining ->
            line.copy(
                amount = remaining.setScale(2, RoundingMode.UNNECESSARY),
                reduced = true,
                reason = MeetingRewardLineReason.CAP_REDUCED,
            )
        else -> line
    }
}

/** The total of the lines, at scale 2. */
internal fun settlementTotal(lines: List<ComputedLine>): BigDecimal = lines.fold(ZERO) { acc, line -> acc + line.amount }

/** The limit per meeting: a total above it is REFUSED (never reduced proportionally). */
internal fun requireMeetingCap(
    total: BigDecimal,
    cap: BigDecimal,
) {
    if (total > cap) throw ConflictException(MEETING_CAP_EXCEEDED)
}

/**
 * SHA-256 over a canonical text (no names; amounts always `toPlainString` at scale 2; lines sorted by member id; fields joined with `|`, lines with
 * `\n`). Binds the meeting, the rules in force (the confirmed change) and the confirmed protocol version to every figure. A new [MessageDigest] per call.
 */
internal fun previewHash(
    meetingId: Uuid,
    policyChangeId: Uuid,
    protocolVersionId: Uuid,
    lines: List<ComputedLine>,
): String {
    val text =
        buildString {
            append("meeting-reward-v1|")
                .append(meetingId)
                .append('|')
                .append(policyChangeId)
                .append('|')
                .append(protocolVersionId)
            lines.sortedBy { it.memberId.toString() }.forEach { l ->
                append('\n')
                append(l.memberId)
                    .append('|')
                    .append(l.attendanceStatus.name)
                    .append('|')
                    .append(l.reason?.name.orEmpty())
                    .append('|')
                append(l.functionRole?.name.orEmpty()).append('|')
                append(l.baseAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()).append('|')
                append(l.bonusAmount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()).append('|')
                append(l.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()).append('|')
                append(l.reduced).append('|').append(l.periodYear)
            }
        }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Why the caller cannot do the NEXT step of the settlement (`null` = may): NOT_ADMIN first, then NOT_RELEASABLE (nothing prepared to release),
 * then FOUR_EYES (the caller prepared it themselves).
 */
internal fun settlementBlockedReason(
    isAdmin: Boolean,
    status: MeetingRewardSettlementStatus?,
    callerIsPreparer: Boolean,
): MeetingRewardBlockedReason? =
    when {
        !isAdmin -> MeetingRewardBlockedReason.NOT_ADMIN
        status == MeetingRewardSettlementStatus.PREPARED && callerIsPreparer -> MeetingRewardBlockedReason.FOUR_EYES
        else -> null
    }

/** Why the caller cannot RELEASE (`null` = may): NOT_ADMIN, NOT_RELEASABLE (not PREPARED), FOUR_EYES. */
internal fun releaseBlockedReason(
    isAdmin: Boolean,
    status: MeetingRewardSettlementStatus?,
    callerIsPreparer: Boolean,
): MeetingRewardBlockedReason? =
    when {
        !isAdmin -> MeetingRewardBlockedReason.NOT_ADMIN
        status != MeetingRewardSettlementStatus.PREPARED -> MeetingRewardBlockedReason.NOT_RELEASABLE
        callerIsPreparer -> MeetingRewardBlockedReason.FOUR_EYES
        else -> null
    }
