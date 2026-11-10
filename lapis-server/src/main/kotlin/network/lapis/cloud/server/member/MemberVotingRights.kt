package network.lapis.cloud.server.member

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VotingRightAuditEvent
import network.lapis.cloud.shared.domain.VotingRightSource
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.298 -- the **only** writer of `member.voting_right`, `member.voting_right_source` and `member.voting_right_changed_at`
 * (`MemberVotingRightWriterTripwireTest`). Exceptions, by design: the DSGVO erasure (`FoundationPersonalData`, resets to the defaults in the
 * same `update` that clears the personal columns) and the column defaults of every insert; a new member created by the LupaBB sync as
 * "Neumitglied" is inserted through [insertValues].
 *
 * Voting right and membership are independent: membership is the `status`, the voting right is this attribute. The electorate of every
 * decision is membership AND voting right (`electorateMemberIds`).
 *
 * The `*Locked` functions require that the caller holds the member row lock (`forMemberUpdate()`) in THIS transaction on the row it passes in.
 * They write the three columns only (never status, role or anything else) and return the audit event to record, or `null` if nothing changed
 * (no write, no audit). Audit entries carry the event key only -- never the value as free text, never a name.
 */
internal object MemberVotingRights {
    /** An administrator sets the value by hand. The source becomes MANUAL. No-op if it is already MANUAL with the same value. */
    fun setManualLocked(
        actor: CurrentMember,
        targetRow: ResultRow,
        value: Boolean,
        now: LocalDateTime,
    ): VotingRightAuditEvent? {
        require(actor.memberId != targetRow[MemberTable.id]) { "an administrator never changes their own voting right" }
        if (targetRow[MemberTable.votingRightSource] == VotingRightSource.MANUAL && targetRow[MemberTable.votingRight] == value) return null
        write(memberId = targetRow[MemberTable.id], value = value, source = VotingRightSource.MANUAL, now = now)
        return if (value) VotingRightAuditEvent.GRANTED else VotingRightAuditEvent.REVOKED
    }

    /** The administrator hands a MANUAL value back to the LupaBB sync: the value stays, the source becomes LUPABB. `null` if it was not MANUAL. */
    fun releaseToSyncLocked(
        actor: CurrentMember,
        targetRow: ResultRow,
        now: LocalDateTime,
    ): VotingRightAuditEvent? {
        require(actor.memberId != targetRow[MemberTable.id]) { "an administrator never changes their own voting right" }
        if (targetRow[MemberTable.votingRightSource] != VotingRightSource.MANUAL) return null
        write(
            memberId = targetRow[MemberTable.id],
            value = targetRow[MemberTable.votingRight],
            source = VotingRightSource.LUPABB,
            now = now,
        )
        return VotingRightAuditEvent.RELEASED_TO_SYNC
    }

    /**
     * The sync's write. Only when the source is DEFAULT or LUPABB (a MANUAL value is never overwritten), never on an anonymised member and
     * never on a DECEASED one; the source becomes LUPABB. `null` if nothing was written.
     */
    fun applyFromSyncLocked(
        targetRow: ResultRow,
        value: Boolean,
        now: LocalDateTime,
    ): VotingRightAuditEvent? {
        if (targetRow[MemberTable.votingRightSource] == VotingRightSource.MANUAL) return null
        if (targetRow[MemberTable.anonymizedAt] != null) return null
        if (targetRow[MemberTable.status] == MemberStatus.DECEASED) return null
        if (targetRow[MemberTable.votingRight] == value) return null
        write(memberId = targetRow[MemberTable.id], value = value, source = VotingRightSource.LUPABB, now = now)
        return if (value) VotingRightAuditEvent.GRANTED_BY_SYNC else VotingRightAuditEvent.REVOKED_BY_SYNC
    }

    /** The column values for a member row the sync inserts: `(votingRight, source, changedAt)`; the defaults when the record says nothing. */
    fun insertValues(
        votingRight: Boolean?,
        now: LocalDateTime,
    ): Triple<Boolean, VotingRightSource, LocalDateTime?> =
        if (votingRight == null || votingRight) {
            Triple(true, VotingRightSource.DEFAULT, null)
        } else {
            Triple(false, VotingRightSource.LUPABB, now)
        }

    /** Whether [memberId] holds the voting right right now (live read). */
    fun isVoterNow(memberId: Uuid): Boolean =
        MemberTable
            .selectAll()
            .where { (MemberTable.id eq memberId) and (MemberTable.votingRight eq true) }
            .limit(1)
            .any()

    private fun write(
        memberId: Uuid,
        value: Boolean,
        source: VotingRightSource,
        now: LocalDateTime,
    ) {
        MemberTable.update({ MemberTable.id eq memberId }) {
            it[votingRight] = value
            it[votingRightSource] = source
            it[votingRightChangedAt] = now
        }
    }
}
