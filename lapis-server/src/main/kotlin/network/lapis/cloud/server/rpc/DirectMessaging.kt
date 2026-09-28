package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

internal const val DIRECT_MESSAGE_MAX_BODY_LENGTH = 10_000

/**
 * Gemeinsamer Einfügepfad für [DirectMessageService.sendDirectMessage] UND
 * [CarpoolService.contactAuthor] -- aus `DirectMessageService.sendDirectMessage` extrahiert
 * (Welle V1.9.12 "Mitfahrerzentrale"). Muss innerhalb der offenen `transaction {}` des Aufrufers
 * laufen, wie jeder andere Query-Helfer in diesem Package.
 *
 * Prüft (Security-Befund -- dieselbe Lücken-Klasse wie [DirectMessageService]s eigener "V0.11.0
 * security fix" KDoc, jetzt für Empfänger/Inhalt statt nur Absender geschlossen):
 * - Empfänger existiert, ist ACTIVE und nicht anonymisiert (sonst [ConflictException] -- Empfänger
 *   unpassend, gleiche Wahl wie [requireLtrEligibleRecipient]s eigenes Muster)
 * - Text nicht leer, höchstens [DIRECT_MESSAGE_MAX_BODY_LENGTH] Zeichen
 * - keine Nachricht an sich selbst ([ConflictException], gleiche Wahl wie
 *   `PeerTransferService.transferLtr`s "Cannot transfer LTR to yourself")
 *
 * **Verhaltensänderung für [DirectMessageService.sendDirectMessage]** (Welle V1.9.12): vor diesem
 * Refactor akzeptierte `sendDirectMessage` leere Nachrichten und Selbstnachrichten anstandslos.
 * Das ist beabsichtigt (Design-Team-Review) -- kein Client-Aufrufer existierte zuvor, siehe
 * `CarpoolServiceTest`s eigener Regressionsfall.
 */
internal fun insertDirectMessage(
    senderId: Uuid,
    recipientId: Uuid,
    body: String,
): DirectMessageDto {
    val trimmed = body.trim()
    if (trimmed.isEmpty() || trimmed.length > DIRECT_MESSAGE_MAX_BODY_LENGTH) {
        throw ConflictException("Message body must be 1-$DIRECT_MESSAGE_MAX_BODY_LENGTH characters")
    }
    if (senderId == recipientId) throw ConflictException("Cannot send a direct message to yourself")
    val recipientRow =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq recipientId }
            .singleOrNull()
            ?: throw NotFoundException("Member $recipientId not found")
    if (recipientRow[MemberTable.anonymizedAt] != null ||
        recipientRow[MemberTable.status] !in MemberStatusSets.ORGANIZATION_MEMBER
    ) {
        throw ConflictException("Recipient cannot receive direct messages")
    }
    val id = Uuid.random()
    val now = DbClock.nowLocalDateTime()
    DirectMessageTable.insert {
        it[DirectMessageTable.id] = id
        it[DirectMessageTable.senderId] = senderId
        it[DirectMessageTable.recipientId] = recipientId
        it[DirectMessageTable.body] = trimmed
        it[sentAt] = now
    }
    val senderMember = MemberTable.alias("sender_member_dm")
    val recipientMember = MemberTable.alias("recipient_member_dm")
    val row =
        DirectMessageTable
            .join(senderMember, JoinType.INNER, DirectMessageTable.senderId, senderMember[MemberTable.id])
            .join(recipientMember, JoinType.INNER, DirectMessageTable.recipientId, recipientMember[MemberTable.id])
            .selectAll()
            .where { DirectMessageTable.id eq id }
            .single()
    return DirectMessageDto(
        id = row[DirectMessageTable.id].toString(),
        senderId = row[DirectMessageTable.senderId].toString(),
        senderDisplayName = row[senderMember[MemberTable.displayName]],
        recipientId = row[DirectMessageTable.recipientId].toString(),
        recipientDisplayName = row[recipientMember[MemberTable.displayName]],
        body = row[DirectMessageTable.body],
        sentAt = row[DirectMessageTable.sentAt],
        readAt = row[DirectMessageTable.readAt],
    )
}
