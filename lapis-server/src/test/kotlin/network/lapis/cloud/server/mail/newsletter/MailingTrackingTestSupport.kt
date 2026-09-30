package network.lapis.cloud.server.mail.newsletter

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Fixed, valid (32 distinct-ish bytes) tracking key so tests can mint and verify tokens deterministically. */
internal val TEST_TRACKING_KEY: ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }

internal const val TEST_TRACKING_BASE_URL = "https://test.example"

internal fun testTrackingToken(): MailingTrackingToken = MailingTrackingToken(TEST_TRACKING_KEY)

// ── DB fixtures shared by the tracking tests ──────────────────────────────────────────────────

internal class TrackingFixture {
    val memberIds = mutableListOf<kotlin.uuid.Uuid>()
    val listIds = mutableListOf<kotlin.uuid.Uuid>()
    val messageIds = mutableListOf<kotlin.uuid.Uuid>()

    fun member(
        email: String = "trk-${kotlin.uuid.Uuid.random()}@example.org",
        anonymized: Boolean = false,
    ): kotlin.uuid.Uuid {
        val id = kotlin.uuid.Uuid.random()
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MemberTable.insert {
                it[this.id] = id
                it[displayName] = "Tracking-Testmitglied"
                it[this.email] = email
                it[status] = network.lapis.cloud.shared.domain.MemberStatus.ACTIVE
                it[joinedAt] = kotlinx.datetime.LocalDate(2020, 1, 1)
                it[anonymizedAt] =
                    if (anonymized) {
                        network.lapis.cloud.server.db.DbClock
                            .nowLocalDateTime()
                    } else {
                        null
                    }
            }
        }
        memberIds += id
        return id
    }

    fun list(createdBy: kotlin.uuid.Uuid = member()): kotlin.uuid.Uuid {
        val id = kotlin.uuid.Uuid.random()
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingListTable.insert {
                it[this.id] = id
                it[name] = "Tracking-Testliste"
                it[description] = null
                it[this.createdBy] = createdBy
            }
        }
        listIds += id
        return id
    }

    fun subscribe(
        listId: kotlin.uuid.Uuid,
        memberId: kotlin.uuid.Uuid,
        openConsent: Boolean = false,
        clickConsent: Boolean = false,
        unsubscribed: Boolean = false,
    ) {
        val now =
            network.lapis.cloud.server.db.DbClock
                .nowLocalDateTime()
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingListSubscriptionTable.insert {
                it[id] = kotlin.uuid.Uuid.random()
                it[mailingListId] = listId
                it[this.memberId] = memberId
                it[subscribedAt] = now
                it[unsubscribedAt] = if (unsubscribed) now else null
                it[openTrackingConsentedAt] = if (openConsent) now else null
                it[clickTrackingConsentedAt] = if (clickConsent) now else null
            }
        }
    }

    fun message(
        listId: kotlin.uuid.Uuid,
        sentBy: kotlin.uuid.Uuid,
        bodyHtml: String? = null,
        status: network.lapis.cloud.shared.domain.MailingMessageStatus = network.lapis.cloud.shared.domain.MailingMessageStatus.SENT,
        sentAt: kotlinx.datetime.LocalDateTime? =
            network.lapis.cloud.server.db.DbClock
                .nowLocalDateTime(),
    ): kotlin.uuid.Uuid {
        val id = kotlin.uuid.Uuid.random()
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingMessageTable.insert {
                it[this.id] = id
                it[mailingListId] = listId
                it[subject] = "Testbetreff"
                it[bodyText] = "Text"
                it[this.bodyHtml] = bodyHtml
                it[this.sentBy] = sentBy
                it[this.status] = status
                it[this.sentAt] = sentAt
            }
        }
        messageIds += id
        return id
    }

    fun link(
        messageId: kotlin.uuid.Uuid,
        index: Int,
        url: String,
    ) {
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingMessageLinkTable.insert {
                it[id] = kotlin.uuid.Uuid.random()
                it[linkIndex] = index
                it[targetUrl] = url
                it[mailingMessageId] = messageId
            }
        }
    }

    /** A delivery row with a tracking snapshot; returns (deliveryLogId, nonce). The nonce's hash is stored. */
    fun delivery(
        messageId: kotlin.uuid.Uuid,
        memberId: kotlin.uuid.Uuid,
        token: MailingTrackingToken = testTrackingToken(),
        openTracked: Boolean = false,
        clickTracked: Boolean = false,
        status: network.lapis.cloud.shared.domain.DeliveryStatus = network.lapis.cloud.shared.domain.DeliveryStatus.SENT,
    ): Pair<kotlin.uuid.Uuid, String> {
        val issued = token.issue()
        val id = kotlin.uuid.Uuid.random()
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingDeliveryLogTable.insert {
                it[this.id] = id
                it[mailingMessageId] = messageId
                it[this.memberId] = memberId
                it[deliveredAt] =
                    network.lapis.cloud.server.db.DbClock
                        .nowLocalDateTime()
                it[deliveryStatus] = status
                it[trackingTokenHash] = issued.hashHex
                it[this.openTracked] = openTracked
                it[this.clickTracked] = clickTracked
            }
        }
        return id to issued.nonce
    }

    fun cleanup() {
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            val deliveryIds =
                network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
                    .select(network.lapis.cloud.server.db.generated.MailingDeliveryLogTable.id)
                    .where {
                        network.lapis.cloud.server.db.generated.MailingDeliveryLogTable.mailingMessageId inList messageIds
                    }.map { it[network.lapis.cloud.server.db.generated.MailingDeliveryLogTable.id] }
            network.lapis.cloud.server.db.generated.MailingLinkClickTable
                .deleteWhere { mailingDeliveryLogId inList deliveryIds }
            network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
                .deleteWhere { mailingMessageId inList messageIds }
            network.lapis.cloud.server.db.generated.MailingMessageLinkTable
                .deleteWhere { mailingMessageId inList messageIds }
            network.lapis.cloud.server.db.generated.MailingMessageTable
                .deleteWhere { id inList messageIds }
            network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
                .deleteWhere { mailingListId inList listIds }
            network.lapis.cloud.server.db.generated.MailingListTable
                .deleteWhere { id inList listIds }
            network.lapis.cloud.server.db.generated.MemberTable
                .deleteWhere { id inList memberIds }
        }
    }

    fun clicksOf(deliveryLogId: kotlin.uuid.Uuid): Map<Int, Int> =
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingLinkClickTable
                .selectAll()
                .where { network.lapis.cloud.server.db.generated.MailingLinkClickTable.mailingDeliveryLogId eq deliveryLogId }
                .associate {
                    it[network.lapis.cloud.server.db.generated.MailingLinkClickTable.linkIndex] to
                        it[network.lapis.cloud.server.db.generated.MailingLinkClickTable.clickCount]
                }
        }

    fun openCountOf(deliveryLogId: kotlin.uuid.Uuid): Int =
        org.jetbrains.exposed.v1.jdbc.transactions.transaction {
            network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
                .selectAll()
                .where { network.lapis.cloud.server.db.generated.MailingDeliveryLogTable.id eq deliveryLogId }
                .single()[network.lapis.cloud.server.db.generated.MailingDeliveryLogTable.openCount]
        }
}
