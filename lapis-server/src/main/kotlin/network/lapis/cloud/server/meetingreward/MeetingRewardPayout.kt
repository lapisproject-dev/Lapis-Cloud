package network.lapis.cloud.server.meetingreward

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.rpc.lockedMemberStatuses
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MeetingRewardSettlementStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.jdbc.insert
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V1.9.316 -- THE ONLY file that may write the LTR ledger for [LtrLedgerEntryType.MEETING_REWARD] (`MeetingRewardLedgerTripwireTest` pins this).
 *
 * ## What a release is
 *
 * An ISSUANCE by the organisation, like a MINT and a tender payout: ONE credit row [LtrLedgerEntryType.MEETING_REWARD] per recognised person with an
 * amount above zero, reference [LtrLedgerReferenceType.MEETING] = the meeting id, `note` = `null`. No debit row, no organisation account, so
 * "insufficient balance" cannot happen. The amounts are the lines of the PREPARED settlement, recomputed here from the live rows and compared with the
 * stored preview hash -- a figure is never taken from client input. LTR only, never EUR.
 *
 * ## Idempotency in four layers (no partial index, so it holds on H2 and PostgreSQL)
 *
 *  a. the settlement row is locked `FOR UPDATE` and moves PREPARED -> RELEASED by a conditional `UPDATE ... WHERE status = 'PREPARED'`;
 *  b. `meeting_reward_settlement.meeting_id` is UNIQUE (one settlement per meeting);
 *  c. UNIQUE (`meeting_id`, `member_id`) and UNIQUE `idempotency_key` on the lines;
 *  d. UNIQUE `meeting_reward_line.ledger_entry_id`.
 *
 * ## Lock order: policy row -> settlement row -> member rows (ascending id, `forMemberUpdate`) -> ledger -> audit (the caller, last).
 * The policy row is locked FIRST by every path (a deliberate refinement of "settlement first"): a concurrent confirmation of a rule change and a
 * release serialise against each other, and so do two releases of different meetings. The yearly cap per person is read only AFTER the member rows
 * are locked: on READ COMMITTED every statement sees a fresh snapshot, so two releases that touch the same person cannot both pass the cap.
 * Must run inside an open `transaction {}`; opens none. No log line carries an amount or a name -- only ids and enum names.
 */
internal object MeetingRewardPayout {
    sealed interface Outcome {
        /** The settlement was already RELEASED: nothing was written. */
        data class AlreadyReleased(
            val settlementId: Uuid,
        ) : Outcome

        data class Released(
            val settlementId: Uuid,
            val policyChangeId: Uuid,
            val protocolVersionId: Uuid,
            val previewHash: String,
            val lineCount: Int,
            val paidCount: Int,
            val total: BigDecimal,
            val releaserIsBeneficiary: Boolean,
        ) : Outcome
    }

    fun release(
        current: CurrentMember,
        meetingId: Uuid,
        expectedHash: String,
    ): Outcome {
        val policyRow = MeetingRewardStore.lockPolicy()
        val settlement = MeetingRewardStore.lockSettlement(meetingId) ?: throw ConflictException(NOT_AVAILABLE)
        val settlementId = settlement[MeetingRewardSettlementTable.id]
        if (settlement[MeetingRewardSettlementTable.status] ==
            MeetingRewardSettlementStatus.RELEASED
        ) {
            return Outcome.AlreadyReleased(settlementId)
        }
        if (settlement[MeetingRewardSettlementTable.preparedBy] == current.memberId) throw ConflictException(FOUR_EYES_REQUIRED)

        val policy = MeetingRewardStore.valuesOf(policyRow)
        val storedChange = settlement[MeetingRewardSettlementTable.policyChangeId]
        val storedProtocol = settlement[MeetingRewardSettlementTable.protocolVersionId]
        val facts = MeetingRewardFactsReader.load(meetingId = meetingId, policy = policy)
        facts.unavailable?.let { throw ConflictException(unavailableText(it)) }
        // The rules or the confirmed Protokoll moved on since the settlement was prepared: it has to be prepared again.
        if (policyRow[MeetingRewardPolicyTable.lastChangeId] != storedChange || facts.protocolVersionId != storedProtocol) {
            throw ConflictException(PREVIEW_STALE)
        }

        // The member rows are locked (ascending id) BEFORE the yearly sums are read.
        val statuses = lockedMemberStatuses(facts.attendance.map { it.memberId })
        val paidBefore =
            MeetingRewardStore.paidSumByMemberForYear(
                memberIds =
                    facts.attendance.map {
                        it.memberId
                    },
                year = facts.year,
                excludingMeetingId = meetingId,
            )
        val lines =
            computeLines(
                attendance = facts.attendance,
                year = facts.year,
                rolesByMember = facts.rolesByMember,
                statusByMember = statuses,
                alreadyPaidThisYear = paidBefore,
                alreadySettled = MeetingRewardStore.releasedMemberIds(meetingId),
                policy = policy,
            )
        val hash = previewHash(meetingId = meetingId, policyChangeId = storedChange, protocolVersionId = storedProtocol, lines = lines)
        if (hash != settlement[MeetingRewardSettlementTable.previewHash] || hash != expectedHash) throw ConflictException(PREVIEW_STALE)
        val total = settlementTotal(lines)
        requireMeetingCap(total = total, cap = policy.capPerMeeting)

        val now = DbClock.nowLocalDateTime()
        if (MeetingRewardStore.markReleased(settlementId = settlementId, by = current.memberId, at = now) !=
            1
        ) {
            throw ConflictException(CONCURRENT_CHANGE)
        }

        val storedLines = MeetingRewardStore.lines(settlementId).associateBy { it[MeetingRewardLineTable.memberId] }
        var paid = 0
        for (line in lines) {
            if (line.amount.signum() <= 0) continue
            val entryId = Uuid.random()
            LtrLedgerEntryTable.insert {
                it[LtrLedgerEntryTable.id] = entryId
                it[memberId] = line.memberId
                it[entryType] = LtrLedgerEntryType.MEETING_REWARD
                it[amountLtr] = line.amount
                it[referenceType] = LtrLedgerReferenceType.MEETING
                it[referenceId] = meetingId
                it[note] = null
                it[createdBy] = current.memberId
                it[createdAt] = now
            }
            val lineId =
                storedLines[line.memberId]?.get(MeetingRewardLineTable.id)
                    ?: error("settlement $settlementId lost the line of a recognised member")
            // Layer (c)/(d): a unique violation can only mean that somebody else booked first. ONLY that is translated; every other failure
            // propagates and rolls the whole transaction back (the savepoint keeps PostgreSQL's transaction alive for the translation).
            try {
                withSavepoint(
                    name = "meeting_reward_line_entry",
                ) { MeetingRewardStore.setLineLedgerEntry(lineId = lineId, ledgerEntryId = entryId) }
            } catch (e: Exception) {
                if (e.isUniqueViolation()) throw ConflictException(CONCURRENT_CHANGE)
                throw e
            }
            paid++
        }
        logger.info { "meeting reward settlement $settlementId released" }
        return Outcome.Released(
            settlementId = settlementId,
            policyChangeId = storedChange,
            protocolVersionId = storedProtocol,
            previewHash = hash,
            lineCount = lines.size,
            paidCount = paid,
            total = total,
            releaserIsBeneficiary = lines.any { it.memberId == current.memberId && it.amount.signum() > 0 },
        )
    }
}
