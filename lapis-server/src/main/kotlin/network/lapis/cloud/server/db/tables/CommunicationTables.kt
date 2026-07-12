package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.DeliveryStatus
import network.lapis.cloud.shared.domain.MailingMessageStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

object MailingListTable : Table("mailing_list") {
    val id = uuid("id")
    val name = varchar("name", 200)
    val description = varchar("description", 1000).nullable()
    val createdBy = uuid("created_by").references(MemberTable.id)

    override val primaryKey = PrimaryKey(id)
}

object MailingListSubscriptionTable : Table("mailing_list_subscription") {
    val id = uuid("id")
    val mailingListId = uuid("mailing_list_id").references(MailingListTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val subscribedAt = datetime("subscribed_at")
    val unsubscribedAt = datetime("unsubscribed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object MailingMessageTable : Table("mailing_message") {
    val id = uuid("id")
    val mailingListId = uuid("mailing_list_id").references(MailingListTable.id)
    val subject = varchar("subject", 300)
    val bodyText = varchar("body_text", 20000)
    val sentBy = uuid("sent_by").references(MemberTable.id)
    val sentAt = datetime("sent_at").nullable()
    val status = enumerationByName<MailingMessageStatus>("status", 20)

    override val primaryKey = PrimaryKey(id)
}

object MailingDeliveryLogTable : Table("mailing_delivery_log") {
    val id = uuid("id")
    val mailingMessageId = uuid("mailing_message_id").references(MailingMessageTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val deliveredAt = datetime("delivered_at")
    val deliveryStatus = enumerationByName<DeliveryStatus>("delivery_status", 30)

    override val primaryKey = PrimaryKey(id)
}

object DirectMessageTable : Table("direct_message") {
    val id = uuid("id")
    val senderId = uuid("sender_id").references(MemberTable.id)
    val recipientId = uuid("recipient_id").references(MemberTable.id)
    val body = varchar("body", 10000)
    val sentAt = datetime("sent_at")
    val readAt = datetime("read_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
