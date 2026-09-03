package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
