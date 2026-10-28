package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.239 -- the Protokoll draft operations with limits no test reaches by accident. */
internal fun SessionFixture.protocolOps() = MeetingProtocolDraftOperations(readLimiter = bigLimiter(), writeLimiter = bigLimiter())

/** Opens the Sitzung of the scene (as its lead) and returns the draft operations. */
internal suspend fun SessionScene.openedProtocol(): MeetingProtocolDraftOperations {
    ops.open(current = lead, roomId = roomId, meetingId = meetingId)
    return fx.protocolOps()
}

internal fun protocolDraftCount(meetingId: Uuid): Long =
    transaction { ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq meetingId }.count() }

internal fun protocolBlockCount(meetingId: Uuid): Long =
    transaction {
        val draft =
            ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq meetingId }.singleOrNull() ?: return@transaction 0L
        ProtocolDraftBlockTable.selectAll().where { ProtocolDraftBlockTable.draftId eq draft[ProtocolDraftTable.id] }.count()
    }

internal fun protocolTaskCount(meetingId: Uuid): Long =
    transaction {
        val draft =
            ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq meetingId }.singleOrNull() ?: return@transaction 0L
        ProtocolDraftTaskTable.selectAll().where { ProtocolDraftTaskTable.draftId eq draft[ProtocolDraftTable.id] }.count()
    }

internal fun lockProtocolDraft(meetingId: Uuid) {
    transaction {
        ProtocolDraftTable.update({ ProtocolDraftTable.meetingId eq meetingId }) { it[phase] = ProtocolDraftPhase.LOCKED }
    }
}

/** The number of MEETING_SESSION audit entries of this Sitzung. */
internal fun auditCount(meetingId: Uuid): Long =
    transaction {
        AuditLogEntryTable
            .selectAll()
            .where { (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_SESSION) and (AuditLogEntryTable.entityId eq meetingId) }
            .count()
    }
