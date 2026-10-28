package network.lapis.cloud.server.governance.session

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.ProtocolFinaliseResultDto
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** V1.9.240 -- the finalising operations with limits no test reaches by accident and a throw-away storage root for the PDFs. */
internal fun SessionFixture.finalOps(storage: File): MeetingProtocolOperations =
    MeetingProtocolOperations(
        storageRoot = storage,
        readLimiter = bigLimiter(),
        writeLimiter = bigLimiter(),
        draftOps = protocolOps(),
    )

internal fun newStorageRoot(): File = Files.createTempDirectory("lapis-protocol-test").toFile()

/** Opens the Sitzung, writes a text into the first agenda item (as lead) and returns the hash the lead sees in the preview. */
internal fun SessionScene.openAndWrite(
    final: MeetingProtocolOperations,
    text: String = "MSX Verlauf des ersten Punkts",
): String {
    runBlocking { ops.open(current = lead, roomId = roomId, meetingId = meetingId) }
    fx.protocolOps().saveBlock(
        current = lead,
        meetingId = meetingId.toString(),
        agendaItemId = agenda[0].toString(),
        expectedVersion = 0,
        body = text,
    )
    return final.preview(current = lead, meetingId = meetingId.toString()).contentSha256
}

internal fun SessionScene.finaliseAs(
    final: MeetingProtocolOperations,
    who: CurrentMember,
): ProtocolFinaliseResultDto {
    val hash = final.preview(current = who, meetingId = meetingId.toString()).contentSha256
    return final.finalise(current = who, meetingId = meetingId.toString(), expectedContentSha256 = hash)
}

internal fun versionRows(meetingId: Uuid) =
    transaction { ProtocolVersionTable.selectAll().where { ProtocolVersionTable.meetingId eq meetingId }.toList() }

internal fun confirmationCount(versionId: Uuid): Long =
    transaction { ProtocolVersionConfirmationTable.selectAll().where { ProtocolVersionConfirmationTable.versionId eq versionId }.count() }

internal fun reschedule(
    meetingId: Uuid,
    at: LocalDateTime,
) {
    transaction {
        network.lapis.cloud.server.db.generated.MeetingTable
            .update({ network.lapis.cloud.server.db.generated.MeetingTable.id eq meetingId }) {
                it[scheduledAt] = at
            }
    }
}

internal fun approvalItemsOf(meetingId: Uuid): List<Pair<Uuid, Int>> =
    transaction {
        AgendaItemTable
            .selectAll()
            .where { AgendaItemTable.meetingId eq meetingId }
            .filter { it[AgendaItemTable.protocolApprovalSourceMeetingId] != null }
            .map { it[AgendaItemTable.id] to it[AgendaItemTable.position] }
    }
