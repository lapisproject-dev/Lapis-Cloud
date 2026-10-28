package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.pdf.formatGermanDate
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.ProtocolApprovalDto
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.240 -- "Genehmigung des Protokolls der Sitzung vom ..." : the agenda item with which a committee approves the Protokoll of its previous
 * Sitzung. It is put on the agenda of the NEXT Sitzung of the same committee exactly once per source Protokoll, in either order of events:
 *
 * - [onMeetingCreated]: a Sitzung is created and the committee already has a confirmed Protokoll that was not offered for approval yet;
 * - [onConfirmed]: a Protokoll becomes CONFIRMED and a later, not yet opened Sitzung of the committee exists.
 *
 * "Exactly once" is the tombstone `meeting.protocol_approval_source_meeting_id` (UNIQUE, set together with the item in one savepoint): a second
 * Protokoll version, a removed item or a racing second Sitzung can never put the same source on an agenda again. All functions run inside the
 * caller's transaction and RETURN the audit snapshots instead of recording them -- the audit entry takes the chain lock and must be the caller's
 * LAST lock-taking step.
 */
internal object ProtocolApprovalItems {
    fun onMeetingCreated(newMeetingId: Uuid): List<MeetingSessionAuditSnapshot> {
        val target = MeetingTable.selectAll().where { MeetingTable.id eq newMeetingId }.singleOrNull() ?: return emptyList()
        val candidates =
            MeetingTable
                .selectAll()
                .where {
                    (MeetingTable.committeeId eq target[MeetingTable.committeeId]) and
                        (MeetingTable.scheduledAt less target[MeetingTable.scheduledAt]) and
                        (MeetingTable.id neq newMeetingId)
                }.orderBy(MeetingTable.scheduledAt to SortOrder.DESC, MeetingTable.id to SortOrder.DESC)
                .limit(CANDIDATE_CAP)
                .map { it[MeetingTable.id] }
        if (candidates.isEmpty()) return emptyList()
        val confirmed =
            ProtocolVersionTable
                .select(ProtocolVersionTable.meetingId)
                .where {
                    (ProtocolVersionTable.meetingId inList candidates) and
                        (ProtocolVersionTable.status eq ProtocolVersionStatus.CONFIRMED)
                }.map { it[ProtocolVersionTable.meetingId] }
                .toSet()
        val offered =
            MeetingTable
                .select(MeetingTable.protocolApprovalSourceMeetingId)
                .where { MeetingTable.protocolApprovalSourceMeetingId inList candidates }
                .mapNotNull { it[MeetingTable.protocolApprovalSourceMeetingId] }
                .toSet()
        val source = candidates.firstOrNull { it in confirmed && it !in offered } ?: return emptyList()
        return listOfNotNull(claim(targetMeetingId = newMeetingId, sourceMeetingId = source))
    }

    fun onConfirmed(sourceMeetingId: Uuid): List<MeetingSessionAuditSnapshot> {
        val source = MeetingTable.selectAll().where { MeetingTable.id eq sourceMeetingId }.singleOrNull() ?: return emptyList()
        val alreadyOffered =
            MeetingTable
                .selectAll()
                .where { MeetingTable.protocolApprovalSourceMeetingId eq sourceMeetingId }
                .limit(1)
                .count() > 0
        if (alreadyOffered) return emptyList()
        val target =
            MeetingTable
                .selectAll()
                .where {
                    (MeetingTable.committeeId eq source[MeetingTable.committeeId]) and
                        (MeetingTable.scheduledAt greater source[MeetingTable.scheduledAt]) and
                        MeetingTable.openedAt.isNull() and
                        (MeetingTable.status neq MeetingStatus.CANCELLED) and
                        MeetingTable.protocolApprovalSourceMeetingId.isNull()
                }.orderBy(MeetingTable.scheduledAt to SortOrder.ASC, MeetingTable.id to SortOrder.ASC)
                .limit(1)
                .forUpdate()
                .singleOrNull() ?: return emptyList()
        return listOfNotNull(claim(targetMeetingId = target[MeetingTable.id], sourceMeetingId = sourceMeetingId))
    }

    /**
     * A Sitzung was cancelled: when it carried an approval item that holds no record yet (not opened, no result, no Protokoll text or task), the
     * item and the tombstone are released and the Protokoll is offered again to the next Sitzung that is not cancelled -- otherwise a cancelled
     * target would swallow the approval for good. An item with a record stays where it is.
     */
    fun onMeetingCancelled(meetingId: Uuid): List<MeetingSessionAuditSnapshot> {
        val meeting =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq meetingId }
                .forUpdate()
                .singleOrNull() ?: return emptyList()
        val source = meeting[MeetingTable.protocolApprovalSourceMeetingId] ?: return emptyList()
        if (meeting[MeetingTable.openedAt] != null) return emptyList()
        val item =
            AgendaItemTable
                .selectAll()
                .where { (AgendaItemTable.meetingId eq meetingId) and (AgendaItemTable.protocolApprovalSourceMeetingId eq source) }
                .singleOrNull()
        if (item != null) {
            if (item[AgendaItemTable.protocolApprovalResult] != null) return emptyList()
            val itemId = item[AgendaItemTable.id]
            // A resolution or motion hangs on the item by foreign key: it keeps the item (and a deleteWhere would fail the whole status change).
            val hasContent =
                ResolutionTable.selectAll().where { ResolutionTable.agendaItemId eq itemId }.count() > 0 ||
                    MotionTable.selectAll().where { MotionTable.agendaItemId eq itemId }.count() > 0 ||
                    ProtocolDraftTaskTable.selectAll().where { ProtocolDraftTaskTable.agendaItemId eq itemId }.count() > 0 ||
                    ProtocolDraftBlockTable
                        .selectAll()
                        .where { (ProtocolDraftBlockTable.agendaItemId eq itemId) and (ProtocolDraftBlockTable.body neq "") }
                        .count() > 0
            if (hasContent) return emptyList()
            MeetingTable.update({ MeetingTable.currentAgendaItemId eq itemId }) { it[currentAgendaItemId] = null }
            ProtocolDraftBlockTable.deleteWhere { ProtocolDraftBlockTable.agendaItemId eq itemId }
            AgendaItemTable.deleteWhere { AgendaItemTable.id eq itemId }
        }
        MeetingTable.update({ MeetingTable.id eq meetingId }) { it[protocolApprovalSourceMeetingId] = null }
        return onConfirmed(source)
    }

    /** Sets the tombstone and inserts the item in ONE savepoint; any unique violation (tombstone or position) means "somebody else was first". */
    private fun claim(
        targetMeetingId: Uuid,
        sourceMeetingId: Uuid,
    ): MeetingSessionAuditSnapshot? {
        val sourceDate =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq sourceMeetingId }
                .singleOrNull()
                ?.get(MeetingTable.scheduledAt)
                ?.date
                ?: return null
        val itemId = Uuid.random()
        return try {
            withSavepoint(name = "protocol_approval_claim") {
                val claimed =
                    MeetingTable.update(
                        { (MeetingTable.id eq targetMeetingId) and MeetingTable.protocolApprovalSourceMeetingId.isNull() },
                    ) {
                        it[protocolApprovalSourceMeetingId] = sourceMeetingId
                    }
                if (claimed == 0) return@withSavepoint null
                val positions =
                    AgendaItemTable
                        .select(AgendaItemTable.position)
                        .where { AgendaItemTable.meetingId eq targetMeetingId }
                        .map { it[AgendaItemTable.position] }
                // Position 1 when free (the approval opens a Sitzung); otherwise behind the existing items -- there is no re-ordering here.
                val position = if (1 !in positions) 1 else positions.max() + 1
                AgendaItemTable.insert {
                    it[AgendaItemTable.id] = itemId
                    it[AgendaItemTable.meetingId] = targetMeetingId
                    it[AgendaItemTable.position] = position
                    it[AgendaItemTable.title] = approvalTitle(sourceDate)
                    it[AgendaItemTable.description] = null
                    it[AgendaItemTable.presenterMemberId] = null
                    it[AgendaItemTable.protocolApprovalSourceMeetingId] = sourceMeetingId
                }
                MeetingSessionAuditSnapshot(
                    kind = MeetingSessionAuditKind.PROTOCOL_APPROVAL_ITEM_INSERTED,
                    meetingId = targetMeetingId.toString(),
                    agendaItemId = itemId.toString(),
                    sourceMeetingId = sourceMeetingId.toString(),
                )
            }
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) null else throw e
        }
    }

    /** The title is a server-side German text like the PDFs; the client shows approval items through [ProtocolApprovalDto] with its own text. */
    fun approvalTitle(sourceDate: LocalDate): String = "Genehmigung des Protokolls der Sitzung vom ${formatGermanDate(sourceDate)}"

    fun isApprovalItem(item: ResultRow): Boolean = item[AgendaItemTable.protocolApprovalSourceMeetingId] != null

    /** The read model of an approval item, or `null` for any other item. [editable] is decided by the caller (role, phase). */
    fun dto(
        item: ResultRow,
        editable: Boolean,
    ): ProtocolApprovalDto? {
        val sourceId = item[AgendaItemTable.protocolApprovalSourceMeetingId] ?: return null
        val sourceDate =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq sourceId }
                .singleOrNull()
                ?.get(MeetingTable.scheduledAt)
                ?.date
                ?: return null
        val result = item[AgendaItemTable.protocolApprovalResult]
        val newest = ProtocolVersionStore.maxVersionNo(sourceId)
        return ProtocolApprovalDto(
            sourceMeetingId = sourceId.toString(),
            sourceMeetingDate = sourceDate,
            versionNo = item[AgendaItemTable.protocolApprovalVersionNo],
            result = result,
            recordedAt = item[AgendaItemTable.approvalRecordedAt],
            editable = editable,
            correctionPending =
                result == ProtocolApprovalResult.APPROVED_WITH_CORRECTIONS &&
                    item[AgendaItemTable.protocolApprovalVersionNo] == newest,
        )
    }

    /** `true` when removing [item] would lose a recorded decision: a result exists or the Sitzung was opened. */
    fun isProtected(
        item: ResultRow,
        meeting: ResultRow,
    ): Boolean =
        isApprovalItem(item) &&
            (item[AgendaItemTable.protocolApprovalResult] != null || meeting[MeetingTable.openedAt] != null)

    private const val CANDIDATE_CAP = 50
}
