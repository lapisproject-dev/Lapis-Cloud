package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.DirectMessageAttachmentTable
import network.lapis.cloud.server.db.generated.DirectMessageBlockTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.messaging.DirectMessageAttachmentStore
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import kotlin.uuid.Uuid

/** Same root `Application.module` computes for `documentStorageRoot`; re-derived like in `TravelExpensePersonalData` (no storage root reaches a contributor). */
private val dmAttachmentStorageRoot: File
    get() = File(System.getenv("LAPIS_DOCUMENT_STORAGE_ROOT") ?: "build/document-storage")

/**
 * Owns mailing lists/subscriptions/messages/delivery log plus direct messages.
 *
 * **Export scoping for [DirectMessageTable]** (Art. 15 vs. third-party-data trade-off, see
 * `docs/architecture/dsgvo.adoc` "Export-Scoping"): messages where the subject is sender OR
 * recipient are included, each annotated with a `direction` field, but the counterparty's
 * *other* data is never expanded — a bulk export must not become a channel to harvest a third
 * party's personal data.
 *
 * **Welle V1.9.15**: [MailingLinkClickTable] (per-link click counts, FK to the delivery log) is
 * owned here and exported/erased with the deliveries. [MailingMessageLinkTable] holds only message
 * content (target URLs), no member FK, and is deliberately NOT a covered table.
 *
 * **V1.9.111**: [MailingListOptOutTable] (a member's opt-out of a dynamic list) is exported and hard-deleted with the member.
 *
 * **V1.9.232**: [MailingMessageAttachmentTable] (attachments of mailing messages) is exported per uploader (name, type, size, instant) and
 * RETAINED on erasure exactly like [MailingMessageTable] (`sent_by`): the attachment is part of the communication other members received
 * (the mail they hold contains the file); the file itself is deleted by the retention job 180 days after sending.
 *
 * **V1.9.233**: [DirectMessageAttachmentTable] (files attached to direct messages). Export (Art. 15): name, type, size, instant and the
 * direction (SENT/RECEIVED/PENDING) of every attachment the subject uploaded or received -- never the file and never the storage key,
 * consistent with the direct-message export that also contains received texts. Erasure: the subject's PENDING uploads are deleted in
 * every mode (no value for anybody else); under [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] ALL attachments the subject uploaded are
 * deleted too -- rows AND files, alongside the redaction of the sent texts -- while in the default mode the sent attachments stay with
 * the recipient for the same reason the texts do. Received attachments stay (they are the sender's data). The files are removed inside
 * the transaction (a contributor has no after-commit hook; a rollback leaves a row without file, which downloads as 404 and is cleaned
 * up by a repeated erasure).
 *
 * **Erasure**: [MailingListSubscriptionTable] and [MailingDeliveryLogTable] rows have no
 * retention duty and are hard-deleted regardless of [ErasureMode]. [MailingListTable] (creator)
 * and [MailingMessageTable] (sender) are retained — other members received/subscribed to them,
 * they are authored organizational communication, not purely the subject's own data.
 * [DirectMessageTable] bodies are retained by default (the recipient has their own interest in
 * the conversation); under [ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED] the subject's own
 * *sent* message bodies are additionally redacted — never the messages they *received*, since
 * redacting those would edit the counterparty's copy of their own words without the
 * counterparty's request.
 */
object CommunicationPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "communication"
    override val displayName = "Kommunikation"
    override val coveredTables =
        setOf(
            MailingListTable,
            MailingListSubscriptionTable,
            MailingListOptOutTable,
            MailingMessageTable,
            MailingMessageAttachmentTable,
            MailingDeliveryLogTable,
            MailingLinkClickTable,
            DirectMessageTable,
            DirectMessageAttachmentTable,
            DirectMessageBlockTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            // V1.9.237 -- the member's own setting for the recipient search of "Neue Nachricht".
            MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull()?.let {
                put("recipientSearchVisible", it[MemberTable.dmDiscoverable])
            }
            // V1.9.237 -- only the blocks the member SET. Blocks against the member are not exported (that would reveal who blocked them).
            putJsonArray("directMessageBlocksSet") {
                DirectMessageBlockTable
                    .selectAll()
                    .where { DirectMessageBlockTable.blockerMemberId eq memberId }
                    .orderBy(DirectMessageBlockTable.createdAt to SortOrder.ASC, DirectMessageBlockTable.id to SortOrder.ASC)
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("blockedMemberId", row[DirectMessageBlockTable.blockedMemberId].toString())
                                put("createdAt", row[DirectMessageBlockTable.createdAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("createdMailingLists") {
                MailingListTable
                    .selectAll()
                    // V1.9.234: also the lists this member deleted (archived rows keep deleted_by).
                    .where { (MailingListTable.createdBy eq memberId) or (MailingListTable.deletedBy eq memberId) }
                    .orderBy(MailingListTable.id to SortOrder.ASC)
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MailingListTable.id].toString())
                                put("name", row[MailingListTable.name])
                                row[MailingListTable.deletedAt]?.let { put("deletedAt", it.toString()) }
                                put("deletedByMe", row[MailingListTable.deletedBy] == memberId)
                            },
                        )
                    }
            }
            putJsonArray("subscriptions") {
                MailingListSubscriptionTable
                    .selectAll()
                    .where { MailingListSubscriptionTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("mailingListId", row[MailingListSubscriptionTable.mailingListId].toString())
                                put("subscribedAt", row[MailingListSubscriptionTable.subscribedAt].toString())
                                put("unsubscribedAt", row[MailingListSubscriptionTable.unsubscribedAt]?.toString())
                                put("openTrackingConsentedAt", row[MailingListSubscriptionTable.openTrackingConsentedAt]?.toString())
                                put("clickTrackingConsentedAt", row[MailingListSubscriptionTable.clickTrackingConsentedAt]?.toString())
                            },
                        )
                    }
            }
            // Welle V1.9.111 -- opt-outs of dynamic lists (the list id and the instant; the rule that includes the member is not personal data).
            putJsonArray("mailingListOptOuts") {
                MailingListOptOutTable
                    .selectAll()
                    .where { MailingListOptOutTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("mailingListId", row[MailingListOptOutTable.mailingListId].toString())
                                put("optedOutAt", row[MailingListOptOutTable.optedOutAt].toString())
                            },
                        )
                    }
            }
            // Welle V1.9.15 -- Art. 15: what was counted about this member's own deliveries (the
            // `mailing_delivery_log` rows were never exported before this wave). Clicks are listed
            // per link; the raw token hash is NOT exported (it is a credential surrogate, not data).
            putJsonArray("mailingDeliveries") {
                MailingDeliveryLogTable
                    .selectAll()
                    .where { MailingDeliveryLogTable.memberId eq memberId }
                    .forEach { row ->
                        val deliveryId = row[MailingDeliveryLogTable.id]
                        val messageId = row[MailingDeliveryLogTable.mailingMessageId]
                        val linkUrls =
                            MailingMessageLinkTable
                                .selectAll()
                                .where { MailingMessageLinkTable.mailingMessageId eq messageId }
                                .associate { it[MailingMessageLinkTable.linkIndex] to it[MailingMessageLinkTable.targetUrl] }
                        add(
                            buildJsonObject {
                                put("messageId", messageId.toString())
                                put("deliveryStatus", row[MailingDeliveryLogTable.deliveryStatus].name)
                                put("deliveredAt", row[MailingDeliveryLogTable.deliveredAt].toString())
                                put("openTracked", row[MailingDeliveryLogTable.openTracked])
                                put("clickTracked", row[MailingDeliveryLogTable.clickTracked])
                                put("firstOpenedAt", row[MailingDeliveryLogTable.firstOpenedAt]?.toString())
                                put("openCount", row[MailingDeliveryLogTable.openCount])
                                putJsonArray("clicks") {
                                    MailingLinkClickTable
                                        .selectAll()
                                        .where { MailingLinkClickTable.mailingDeliveryLogId eq deliveryId }
                                        .forEach { click ->
                                            add(
                                                buildJsonObject {
                                                    put("linkIndex", click[MailingLinkClickTable.linkIndex])
                                                    put("targetUrl", linkUrls[click[MailingLinkClickTable.linkIndex]])
                                                    put("clickCount", click[MailingLinkClickTable.clickCount])
                                                    put("firstClickedAt", click[MailingLinkClickTable.firstClickedAt].toString())
                                                },
                                            )
                                        }
                                }
                            },
                        )
                    }
            }
            putJsonArray("sentMailingMessages") {
                MailingMessageTable
                    .selectAll()
                    .where { MailingMessageTable.sentBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MailingMessageTable.id].toString())
                                put("subject", row[MailingMessageTable.subject])
                                put("sentAt", row[MailingMessageTable.sentAt]?.toString())
                                put("status", row[MailingMessageTable.status].name)
                            },
                        )
                    }
            }
            // V1.9.232 -- attachments this member uploaded to mailing messages: name, type, size and instant (never the file itself).
            putJsonArray("uploadedMailingAttachments") {
                MailingMessageAttachmentTable
                    .selectAll()
                    .where { MailingMessageAttachmentTable.uploadedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("messageId", row[MailingMessageAttachmentTable.mailingMessageId].toString())
                                put("fileName", row[MailingMessageAttachmentTable.originalFilename])
                                put("mimeType", row[MailingMessageAttachmentTable.mimeType])
                                put("sizeBytes", row[MailingMessageAttachmentTable.sizeBytes])
                                put("uploadedAt", row[MailingMessageAttachmentTable.uploadedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("directMessages") {
                DirectMessageTable
                    .selectAll()
                    .where { (DirectMessageTable.senderId eq memberId) or (DirectMessageTable.recipientId eq memberId) }
                    .forEach { row ->
                        val direction = if (row[DirectMessageTable.senderId] == memberId) "SENT" else "RECEIVED"
                        add(
                            buildJsonObject {
                                put("id", row[DirectMessageTable.id].toString())
                                put("direction", direction)
                                put("body", row[DirectMessageTable.body])
                                put("sentAt", row[DirectMessageTable.sentAt].toString())
                                put("readAt", row[DirectMessageTable.readAt]?.toString())
                            },
                        )
                    }
            }
            // V1.9.233 -- attachments the subject uploaded (SENT / PENDING) or received (RECEIVED): metadata only, never the file or its key.
            putJsonArray("directMessageAttachments") {
                DirectMessageAttachmentTable
                    .selectAll()
                    .where {
                        (DirectMessageAttachmentTable.uploaderId eq memberId) or (DirectMessageAttachmentTable.recipientId eq memberId)
                    }.forEach { row ->
                        val sentByMe = row[DirectMessageAttachmentTable.uploaderId] == memberId
                        val pending = row[DirectMessageAttachmentTable.messageId] == null
                        // A pending file addressed to the subject is not visible to it yet (and not its data): skipped.
                        if (pending && !sentByMe) return@forEach
                        add(
                            buildJsonObject {
                                put(
                                    "direction",
                                    if (pending) {
                                        "PENDING"
                                    } else if (sentByMe) {
                                        "SENT"
                                    } else {
                                        "RECEIVED"
                                    },
                                )
                                put("messageId", row[DirectMessageAttachmentTable.messageId]?.toString())
                                put("fileName", row[DirectMessageAttachmentTable.originalFilename])
                                put("mimeType", row[DirectMessageAttachmentTable.mimeType])
                                put("sizeBytes", row[DirectMessageAttachmentTable.sizeBytes])
                                put("createdAt", row[DirectMessageAttachmentTable.createdAt].toString())
                            },
                        )
                    }
            }
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        // V1.9.234: archived (deleted) lists are retained like live ones -- they are the evidence for the messages already sent;
        // deleted_by is not anonymized separately because the member row itself is anonymized, exactly as for created_by.
        val listsRetained = MailingListTable.selectAll().where { MailingListTable.createdBy eq memberId }.count()
        val subscriptionsDeleted = MailingListSubscriptionTable.deleteWhere { MailingListSubscriptionTable.memberId eq memberId }
        val optOutsDeleted = MailingListOptOutTable.deleteWhere { MailingListOptOutTable.memberId eq memberId }
        // Welle V1.9.15 -- click rows reference the delivery log by FK (no CASCADE by repo
        // convention), so they MUST go first or the delivery-log delete below aborts on the FK.
        val ownDeliveryIds =
            MailingDeliveryLogTable
                .select(
                    MailingDeliveryLogTable.id,
                ).where { MailingDeliveryLogTable.memberId eq memberId }
        val clicksDeleted = MailingLinkClickTable.deleteWhere { mailingDeliveryLogId inSubQuery ownDeliveryIds }
        val deliveryLogDeleted = MailingDeliveryLogTable.deleteWhere { MailingDeliveryLogTable.memberId eq memberId }
        val messagesRetained = MailingMessageTable.selectAll().where { MailingMessageTable.sentBy eq memberId }.count()
        val attachmentsRetained =
            MailingMessageAttachmentTable
                .selectAll()
                .where { MailingMessageAttachmentTable.uploadedBy eq memberId }
                .count()

        // V1.9.237 -- blocks on BOTH sides go with the member (a block is about the person, nobody else has an interest in it).
        val blocksDeleted =
            DirectMessageBlockTable.deleteWhere {
                (DirectMessageBlockTable.blockerMemberId eq memberId) or (DirectMessageBlockTable.blockedMemberId eq memberId)
            }
        val sentCount = DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq memberId }.count()
        val receivedCount = DirectMessageTable.selectAll().where { DirectMessageTable.recipientId eq memberId }.count()
        var sentRedacted = 0
        // V1.9.233 -- attachments: pending uploads always; every upload of the subject under HARD_DELETE. Rows first (FK), then files.
        val uploadedBefore = DirectMessageAttachmentTable.selectAll().where { DirectMessageAttachmentTable.uploaderId eq memberId }.count()
        val pendingKeys = DirectMessageAttachmentStore.deletePendingForUploaderInTx(memberId)
        val sentKeys =
            if (mode ==
                ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED
            ) {
                DirectMessageAttachmentStore.deleteAllUploadedByInTx(memberId)
            } else {
                emptyList()
            }
        val attachmentKeys = pendingKeys + sentKeys
        if (attachmentKeys.isNotEmpty()) {
            DirectMessageAttachmentStore.deleteFiles(root = dmAttachmentStorageRoot, keys = attachmentKeys)
        }
        val dmAttachmentsDeleted = attachmentKeys.size
        val dmAttachmentsRetained = (uploadedBefore - dmAttachmentsDeleted).toInt()
        if (mode == ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED) {
            sentRedacted =
                DirectMessageTable.update({ DirectMessageTable.senderId eq memberId }) {
                    it[body] = "[Nachricht vom Absender geloescht]"
                }
        }

        return listOf(
            TableErasureOutcome(
                table = "mailing_list",
                rowsRetained = listsRetained.toInt(),
                retentionReason = "Organisationsobjekt, andere Mitglieder haben abonniert",
            ),
            TableErasureOutcome(table = "mailing_list_subscription", rowsDeleted = subscriptionsDeleted),
            TableErasureOutcome(table = "mailing_list_opt_out", rowsDeleted = optOutsDeleted),
            TableErasureOutcome(
                table = "mailing_message",
                rowsRetained = messagesRetained.toInt(),
                retentionReason = "Von anderen Mitgliedern empfangene Organisationskommunikation",
            ),
            TableErasureOutcome(
                table = "mailing_message_attachment",
                rowsRetained = attachmentsRetained.toInt(),
                retentionReason =
                    "Teil der von anderen Mitgliedern empfangenen Organisationskommunikation (wie mailing_message.sent_by); " +
                        "die Dateien werden ohnehin nach der Aufbewahrungsfrist des Versands gelöscht",
            ),
            TableErasureOutcome(table = "mailing_link_click", rowsDeleted = clicksDeleted),
            TableErasureOutcome(table = "mailing_delivery_log", rowsDeleted = deliveryLogDeleted),
            TableErasureOutcome(table = "direct_message_block", rowsDeleted = blocksDeleted),
            TableErasureOutcome(
                table = "direct_message_attachment",
                rowsDeleted = dmAttachmentsDeleted,
                rowsRetained = dmAttachmentsRetained,
                retentionReason =
                    "Gesendete Anhaenge bleiben bei der Gegenpartei (wie die Textkoerper); nur unter " +
                        "HARD_DELETE_WHERE_UNCONSTRAINED werden die selbst hochgeladenen Dateien samt Zeilen geloescht",
            ),
            TableErasureOutcome(
                table = "direct_message",
                rowsAnonymized = sentRedacted,
                rowsRetained = (sentCount + receivedCount).toInt() - sentRedacted,
                retentionReason =
                    "Gegenpartei hat eigenes Interesse an ihrer Kopie der Konversation; nur unter " +
                        "HARD_DELETE_WHERE_UNCONSTRAINED werden die selbst gesendeten Textkoerper redigiert",
            ),
        )
    }
}
