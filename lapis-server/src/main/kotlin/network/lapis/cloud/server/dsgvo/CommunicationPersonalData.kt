package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

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
            MailingMessageTable,
            MailingDeliveryLogTable,
            MailingLinkClickTable,
            DirectMessageTable,
        )

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("createdMailingLists") {
                MailingListTable
                    .selectAll()
                    .where { MailingListTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[MailingListTable.id].toString())
                                put("name", row[MailingListTable.name])
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
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val listsRetained = MailingListTable.selectAll().where { MailingListTable.createdBy eq memberId }.count()
        val subscriptionsDeleted = MailingListSubscriptionTable.deleteWhere { MailingListSubscriptionTable.memberId eq memberId }
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

        val sentCount = DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq memberId }.count()
        val receivedCount = DirectMessageTable.selectAll().where { DirectMessageTable.recipientId eq memberId }.count()
        var sentRedacted = 0
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
            TableErasureOutcome(
                table = "mailing_message",
                rowsRetained = messagesRetained.toInt(),
                retentionReason = "Von anderen Mitgliedern empfangene Organisationskommunikation",
            ),
            TableErasureOutcome(table = "mailing_link_click", rowsDeleted = clicksDeleted),
            TableErasureOutcome(table = "mailing_delivery_log", rowsDeleted = deliveryLogDeleted),
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
