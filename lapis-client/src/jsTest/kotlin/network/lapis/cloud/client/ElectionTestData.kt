package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CandidacyDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionBoardMemberDto
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOptionDto
import network.lapis.cloud.shared.domain.ElectionParticipationDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionStatus

// V1.9.22 -- shared fixtures of the elections tests.

internal val ELECTION_AT = LocalDateTime(2026, 5, 1, 12, 0)

internal fun option(
    id: String,
    label: String,
    position: Int,
    candidacyId: String? = null,
) = ElectionOptionDto(id = id, electionId = "e1", label = label, position = position, candidacyId = candidacyId, voteCount = 0)

internal fun yesNoOptions() = listOf(option("o-yes", "YES", 0), option("o-no", "NO", 1), option("o-abstain", "ABSTAIN", 2))

internal fun election(
    type: ElectionType = ElectionType.YES_NO,
    status: ElectionStatus = ElectionStatus.PREPARATION,
    secret: Boolean = true,
    seatCount: Int = 1,
    tallyThreshold: Int = 2,
    options: List<ElectionOptionDto> = if (type == ElectionType.YES_NO) yesNoOptions() else emptyList(),
    targetCommitteeId: String? = null,
    title: String = "Vorstandswahl 2026",
    resolutionId: String? = null,
) = ElectionDto(
    id = "e1",
    motionId = "m1",
    meetingId = "s1",
    title = title,
    electionType = type,
    secret = secret,
    seatCount = seatCount,
    targetCommitteeId = targetCommitteeId,
    targetCommitteeName = targetCommitteeId?.let { "Vorstand" },
    targetRole = null,
    requiredMajorityPercent = 50,
    status = status,
    openedById = "chair-1",
    openedByDisplayName = "Clara Chair",
    openedAt = ELECTION_AT,
    candidateListApprovedAt = null,
    votingOpenedAt = if (status == ElectionStatus.PREPARATION || status == ElectionStatus.CANDIDATE_LIST_RELEASED) null else ELECTION_AT,
    votingClosedAt = if (status == ElectionStatus.CLOSED || status == ElectionStatus.TALLIED) ELECTION_AT else null,
    tallyThreshold = tallyThreshold,
    tallyRunAt = null,
    resolutionId = resolutionId,
    options = options,
)

internal fun participation(
    eligible: Boolean? = true,
    hasVoted: Boolean = false,
    isBoard: Boolean = false,
    hasApproved: Boolean = false,
    approvals: Int = 0,
    threshold: Int = 2,
    boardSize: Int = 3,
    eligibleCount: Int? = 4,
    ballotCount: Int = 0,
) = ElectionParticipationDto(
    electionId = "e1",
    eligible = eligible,
    hasVoted = hasVoted,
    isElectionBoardMember = isBoard,
    hasApprovedTally = hasApproved,
    tallyApprovalCount = approvals,
    tallyThreshold = threshold,
    electionBoardSize = boardSize,
    eligibleCount = eligibleCount,
    ballotCount = ballotCount,
)

internal fun motionDto(committeeId: String = "c1") =
    MotionDto(
        id = "m1",
        targetCommitteeId = committeeId,
        targetCommitteeName = "Mitgliederversammlung",
        targetCommitteeType = CommitteeType.GENERAL_ASSEMBLY,
        title = "Wahl des Vorstands",
        rationale = "",
        text = "Text",
        submitterMemberId = "chair-1",
        submitterDisplayName = "Clara Chair",
        status = MotionStatus.SCHEDULED,
        submittedAt = ELECTION_AT,
        reviewedById = null,
        reviewedByDisplayName = null,
        reviewedAt = null,
        reviewNote = null,
        meetingId = "s1",
        agendaItemId = null,
        resolutionId = null,
    )

internal fun rosterEntry(
    memberId: String,
    role: CommitteeRole,
    committeeId: String = "c1",
) = CommitteeMembershipDto(
    id = "cm-$memberId",
    committeeId = committeeId,
    memberId = memberId,
    memberDisplayName = "Mitglied $memberId",
    role = role,
    since = LocalDate(2020, 1, 1),
    until = null,
)

internal fun candidacy(
    id: String,
    memberId: String,
    name: String,
    withdrawn: Boolean = false,
    motivation: String? = null,
) = CandidacyDto(
    id = id,
    electionId = "e1",
    memberId = memberId,
    memberDisplayName = name,
    motivationText = motivation,
    submittedAt = ELECTION_AT,
    withdrawnAt = if (withdrawn) ELECTION_AT else null,
)

internal fun boardMember(
    memberId: String,
    name: String,
) = ElectionBoardMemberDto(id = "b-$memberId", electionId = "e1", memberId = memberId, memberDisplayName = name, appointedAt = ELECTION_AT)
