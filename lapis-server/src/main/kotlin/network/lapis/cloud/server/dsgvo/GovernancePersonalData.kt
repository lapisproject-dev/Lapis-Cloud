package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns [CommitteeMembershipTable]/[MeetingTable]/[AgendaItemTable]/[AttendanceTable]/
 * [ResolutionTable] — the five member-FK-bearing tables of the committee/meeting management wave
 * (V0.2.1) — plus [MotionTable] (motion management, V0.2.2) and [VoteTable]/
 * [VoteBallotTable] (meritocratic voting, V0.2.3), the same domain area.
 * [CommitteeTable] itself has no member FK and is instead listed in
 * [PersonalDataRegistry.noPersonalDataAllowlist]; so does `vote_option` (no member FK at
 * all -- only the ballots staked into an option carry personal data, not the basket itself).
 * [MotionTable.targetCommitteeId] references `committee`, not `member`, so only
 * `submitter_member_id`/`reviewed_by` are subject to `PersonalDataCoverageTest`'s
 * `information_schema` FK walk — both covered simply by adding [MotionTable] here. Likewise
 * [VoteTable.motionId]/`.meetingId`/`.resolutionId` reference `motion`/`meeting`/`resolution`,
 * not `member` -- only `opened_by` is a member FK.
 *
 * Retain-with-reason across the board, consistent with [ContributionPersonalData]/
 * [DocumentPersonalData] precedent — governance records (who chaired a meeting, attendance
 * history behind a quorum determination, the resolution text itself, a Motion's motion text and
 * review rationale, a Vickrey ballot's staked/settled LTR amounts) are
 * organizational/legal-defensibility records (and, for ballots, also the member's property
 * record), not purely personal data, and all FK pointers resolve to the now-anonymized
 * [network.lapis.cloud.server.db.generated.MemberTable] row post-erasure (see
 * [FoundationPersonalData]).
 */
object GovernancePersonalData : PersonalDataContributor {
    override val sectionKey = "governance"
    override val displayName = "Committees and Meetings"
    override val coveredTables =
        setOf(
            CommitteeMembershipTable,
            MeetingTable,
            AgendaItemTable,
            AttendanceTable,
            ResolutionTable,
            MotionTable,
            VoteTable,
            VoteBallotTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("committeeMemberships") {
                (CommitteeMembershipTable innerJoin CommitteeTable)
                    .selectAll()
                    .where { CommitteeMembershipTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[CommitteeMembershipTable.id].toString())
                                put("committeeName", row[CommitteeTable.name])
                                put("role", row[CommitteeMembershipTable.role].name)
                                put("since", row[CommitteeMembershipTable.since].toString())
                                put("until", row[CommitteeMembershipTable.until]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("meetingsCalled") {
                MeetingTable
                    .selectAll()
                    .where { MeetingTable.calledBy eq memberId }
                    .forEach { row -> add(meetingSummaryJson(row)) }
            }
            putJsonArray("meetingsChaired") {
                MeetingTable
                    .selectAll()
                    .where { MeetingTable.chairMemberId eq memberId }
                    .forEach { row -> add(meetingSummaryJson(row)) }
            }
            putJsonArray("meetingsAsMinuteTaker") {
                MeetingTable
                    .selectAll()
                    .where { MeetingTable.minuteTakerMemberId eq memberId }
                    .forEach { row -> add(meetingSummaryJson(row)) }
            }
            putJsonArray("agendaItemsPresented") {
                AgendaItemTable
                    .selectAll()
                    .where { AgendaItemTable.presenterMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AgendaItemTable.id].toString())
                                put("meetingId", row[AgendaItemTable.meetingId].toString())
                                put("title", row[AgendaItemTable.title])
                            },
                        )
                    }
            }
            putJsonArray("attendances") {
                AttendanceTable
                    .selectAll()
                    .where { AttendanceTable.memberId eq memberId }
                    .forEach { row -> add(attendanceJson(row)) }
            }
            putJsonArray("attendedAsProxyFor") {
                AttendanceTable
                    .selectAll()
                    .where { AttendanceTable.representedByMemberId eq memberId }
                    .forEach { row -> add(attendanceJson(row)) }
            }
            putJsonArray("resolutionsRecorded") {
                ResolutionTable
                    .selectAll()
                    .where { ResolutionTable.recordedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[ResolutionTable.id].toString())
                                put("meetingId", row[ResolutionTable.meetingId].toString())
                                put("number", row[ResolutionTable.number])
                                put("title", row[ResolutionTable.title])
                                put("status", row[ResolutionTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("motionsSubmitted") {
                MotionTable
                    .selectAll()
                    .where { MotionTable.submitterMemberId eq memberId }
                    .forEach { row -> add(motionSummaryJson(row)) }
            }
            putJsonArray("motionsReviewed") {
                MotionTable
                    .selectAll()
                    .where { MotionTable.reviewedBy eq memberId }
                    .forEach { row -> add(motionSummaryJson(row)) }
            }
            putJsonArray("votesOpened") {
                VoteTable
                    .selectAll()
                    .where { VoteTable.openedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[VoteTable.id].toString())
                                put("motionId", row[VoteTable.motionId].toString())
                                put("title", row[VoteTable.title])
                                put("status", row[VoteTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("ballotsCast") {
                VoteBallotTable
                    .selectAll()
                    .where { VoteBallotTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[VoteBallotTable.id].toString())
                                put("voteId", row[VoteBallotTable.voteId].toString())
                                put("optionId", row[VoteBallotTable.optionId].toString())
                                put("stakeLtr", row[VoteBallotTable.stakeLtr].toPlainString())
                                put("settledLtr", row[VoteBallotTable.settledLtr]?.toPlainString())
                                put("castAt", row[VoteBallotTable.castAt].toString())
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val membershipCount =
            CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.memberId eq memberId }.count()

        val meetingCount =
            MeetingTable
                .selectAll()
                .where {
                    (MeetingTable.calledBy eq memberId) or
                        (MeetingTable.chairMemberId eq memberId) or
                        (MeetingTable.minuteTakerMemberId eq memberId)
                }.count()

        val agendaItemCount =
            AgendaItemTable.selectAll().where { AgendaItemTable.presenterMemberId eq memberId }.count()

        val attendanceCondition =
            (AttendanceTable.memberId eq memberId) or (AttendanceTable.representedByMemberId eq memberId)
        val attendanceCount = AttendanceTable.selectAll().where { attendanceCondition }.count()
        AttendanceTable.update({ attendanceCondition }) {
            it[note] = null
        }

        val resolutionCount = ResolutionTable.selectAll().where { ResolutionTable.recordedBy eq memberId }.count()

        val motionCondition = (MotionTable.submitterMemberId eq memberId) or (MotionTable.reviewedBy eq memberId)
        val motionCount = MotionTable.selectAll().where { motionCondition }.count()

        val voteCount = VoteTable.selectAll().where { VoteTable.openedBy eq memberId }.count()

        val ballotCount = VoteBallotTable.selectAll().where { VoteBallotTable.memberId eq memberId }.count()

        return listOf(
            TableErasureOutcome(
                table = "committee_membership",
                rowsRetained = membershipCount.toInt(),
                retentionReason = "Accountability record of who held which office on which Committee, and when.",
            ),
            TableErasureOutcome(
                table = "meeting",
                rowsRetained = meetingCount.toInt(),
                retentionReason = "Meeting metadata is an organizational record, not purely personal data.",
            ),
            TableErasureOutcome(
                table = "agenda_item",
                rowsRetained = agendaItemCount.toInt(),
                retentionReason = "Agenda content is an organizational record, not purely personal data.",
            ),
            TableErasureOutcome(
                table = "attendance",
                rowsRetained = attendanceCount.toInt(),
                retentionReason =
                    "Needed as evidence of historical quorum; only the free-text note was " +
                        "erased.",
            ),
            TableErasureOutcome(
                table = "resolution",
                rowsRetained = resolutionCount.toInt(),
                retentionReason =
                    "The resolution text itself is the material legal record (an association " +
                        "must be able to prove what was resolved) -- unlike ContributionTable's " +
                        "incidental note, erasing this would itself be a compliance problem.",
            ),
            TableErasureOutcome(
                table = "motion",
                rowsRetained = motionCount.toInt(),
                retentionReason =
                    "The motion text and review rationale are accountability-relevant " +
                        "administrative records (who moved what, who reviewed it and how) -- " +
                        "same precedent as resolution, so review_note is also kept in full, no " +
                        "field is erased.",
            ),
            TableErasureOutcome(
                table = "vote",
                rowsRetained = voteCount.toInt(),
                retentionReason =
                    "Who opened a Vote is part of the accountable resolution process -- same " +
                        "precedent as resolution.",
            ),
            TableErasureOutcome(
                table = "vote_ballot",
                rowsRetained = ballotCount.toInt(),
                retentionReason =
                    "The staked and settled LTR amount is both the member's own property record " +
                        "and part of other members' Vickrey settlement -- erasing it would damage " +
                        "both. No field is erased.",
            ),
        )
    }
}

private fun meetingSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[MeetingTable.id].toString())
        put("title", row[MeetingTable.title])
        put("scheduledAt", row[MeetingTable.scheduledAt].toString())
        put("status", row[MeetingTable.status].name)
    }

private fun attendanceJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[AttendanceTable.id].toString())
        put("meetingId", row[AttendanceTable.meetingId].toString())
        put("status", row[AttendanceTable.status].name)
        put("recordedAt", row[AttendanceTable.recordedAt].toString())
    }

private fun motionSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[MotionTable.id].toString())
        put("targetCommitteeId", row[MotionTable.targetCommitteeId].toString())
        put("title", row[MotionTable.title])
        put("status", row[MotionTable.status].name)
        put("submittedAt", row[MotionTable.submittedAt].toString())
    }
