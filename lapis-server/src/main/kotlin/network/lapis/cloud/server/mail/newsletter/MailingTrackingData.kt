package network.lapis.cloud.server.mail.newsletter

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingLinkClickTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingMessageLinkTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.15 -- every database access of the tracking feature, kept in one place so the routes,
 * the worker, the consent RPC, the retention poller and the DSGVO contributor cannot drift.
 *
 * **Transaction contract**: the read helpers and [captureLinks]/[eraseForSubscription] run inside
 * the CALLER's transaction. [recordClick]/[recordOpen] open their OWN transactions on purpose: a
 * unique-index race on the first click of a (delivery, link) pair aborts a PostgreSQL transaction,
 * so the retry has to be a fresh one -- same "catch OUTSIDE the transaction" discipline
 * `PriceOracleSnapshotStore.recordIfAbsent` documents.
 *
 * **Counter ceilings** live in the `WHERE` clause (`< MAX_EVENT_COUNT`), never as a bare increment:
 * the `chk_mailing_delivery_log_open_count`/`click_count` CHECKs would otherwise make the 1001st
 * event throw instead of saturate.
 */
internal object MailingTrackingData {
    data class DeliveryRef(
        val deliveryLogId: Uuid,
        val messageId: Uuid,
        val memberId: Uuid,
        val listId: Uuid,
        val openTracked: Boolean,
        val clickTracked: Boolean,
    )

    fun findDeliveryByHash(hashHex: String): DeliveryRef? =
        (MailingDeliveryLogTable innerJoin MailingMessageTable)
            .selectAll()
            .where { MailingDeliveryLogTable.trackingTokenHash eq hashHex }
            .singleOrNull()
            ?.let { row ->
                DeliveryRef(
                    deliveryLogId = row[MailingDeliveryLogTable.id],
                    messageId = row[MailingDeliveryLogTable.mailingMessageId],
                    memberId = row[MailingDeliveryLogTable.memberId],
                    listId = row[MailingMessageTable.mailingListId],
                    openTracked = row[MailingDeliveryLogTable.openTracked],
                    clickTracked = row[MailingDeliveryLogTable.clickTracked],
                )
            }

    /** The redirect target, ONLY ever read from the message's own captured link table. */
    fun resolveTarget(
        messageId: Uuid,
        linkIndex: Int,
    ): String? =
        MailingMessageLinkTable
            .selectAll()
            .where { (MailingMessageLinkTable.mailingMessageId eq messageId) and (MailingMessageLinkTable.linkIndex eq linkIndex) }
            .singleOrNull()
            ?.get(MailingMessageLinkTable.targetUrl)

    /** Snapshot AND current consent AND active subscription AND not anonymized -- see the route KDoc. */
    fun mayCountClick(ref: DeliveryRef): Boolean = ref.clickTracked && currentConsent(ref = ref, click = true)

    fun mayCountOpen(ref: DeliveryRef): Boolean = ref.openTracked && currentConsent(ref = ref, click = false)

    private fun currentConsent(
        ref: DeliveryRef,
        click: Boolean,
    ): Boolean {
        val consentColumn =
            if (click) MailingListSubscriptionTable.clickTrackingConsentedAt else MailingListSubscriptionTable.openTrackingConsentedAt
        return (MailingListSubscriptionTable innerJoin MemberTable)
            .selectAll()
            .where {
                (MailingListSubscriptionTable.mailingListId eq ref.listId) and
                    (MailingListSubscriptionTable.memberId eq ref.memberId) and
                    (MailingListSubscriptionTable.unsubscribedAt.isNull()) and
                    consentColumn.isNotNull() and
                    (MemberTable.anonymizedAt.isNull())
            }.limit(1)
            .any()
    }

    /** Opens its own transaction(s) -- see the object KDoc. */
    fun recordClick(
        deliveryLogId: Uuid,
        linkIndex: Int,
        now: LocalDateTime,
    ) {
        repeat(2) { attempt ->
            try {
                transaction {
                    // Same transaction as the write: a consent withdrawal (eraseForSubscription) that
                    // committed after the route's mayCountClick check must not be followed by a new row.
                    val stillTracked =
                        MailingDeliveryLogTable
                            .selectAll()
                            .where { (MailingDeliveryLogTable.id eq deliveryLogId) and (MailingDeliveryLogTable.clickTracked eq true) }
                            .any()
                    if (!stillTracked) return@transaction
                    val updated =
                        MailingLinkClickTable.update(
                            {
                                (MailingLinkClickTable.mailingDeliveryLogId eq deliveryLogId) and
                                    (MailingLinkClickTable.linkIndex eq linkIndex) and
                                    (MailingLinkClickTable.clickCount less MailingHtmlPolicy.MAX_EVENT_COUNT)
                            },
                        ) {
                            it[clickCount] = clickCount + 1
                        }
                    if (updated == 0) {
                        val exists =
                            MailingLinkClickTable
                                .selectAll()
                                .where {
                                    (MailingLinkClickTable.mailingDeliveryLogId eq deliveryLogId) and
                                        (MailingLinkClickTable.linkIndex eq linkIndex)
                                }.any()
                        if (!exists) {
                            MailingLinkClickTable.insert {
                                it[id] = Uuid.random()
                                it[MailingLinkClickTable.linkIndex] = linkIndex
                                it[firstClickedAt] = now
                                it[clickCount] = 1
                                it[mailingDeliveryLogId] = deliveryLogId
                            }
                        }
                    }
                }
                return
            } catch (e: ExposedSQLException) {
                // First-click race (unique index on delivery+link): retry once as a plain UPDATE.
                if (attempt == 1) throw e
            }
        }
    }

    /** Opens its own transaction -- see the object KDoc. */
    fun recordOpen(
        deliveryLogId: Uuid,
        now: LocalDateTime,
    ) {
        transaction {
            MailingDeliveryLogTable.update(
                {
                    (MailingDeliveryLogTable.id eq deliveryLogId) and
                        (MailingDeliveryLogTable.openCount less MailingHtmlPolicy.MAX_EVENT_COUNT)
                },
            ) {
                it[openCount] = openCount + 1
            }
            MailingDeliveryLogTable.update(
                { (MailingDeliveryLogTable.id eq deliveryLogId) and MailingDeliveryLogTable.firstOpenedAt.isNull() },
            ) {
                it[firstOpenedAt] = now
            }
        }
    }

    /**
     * Captures every distinct trackable link of [sanitized] in first-occurrence order as the
     * message's `mailing_message_link` rows (delete + insert, so a repeat call is idempotent).
     * The index IS the click-token index and the renderer's [linkIndexByUrl] key.
     */
    fun captureLinks(
        messageId: Uuid,
        sanitized: SanitizedMailingHtml,
    ) {
        MailingMessageLinkTable.deleteWhere { mailingMessageId eq messageId }
        sanitized.trackableLinks
            .distinct()
            .filter { MailingTrackingTarget.safeRedirectTarget(it) != null }
            .forEachIndexed { index, url ->
                MailingMessageLinkTable.insert {
                    it[id] = Uuid.random()
                    it[linkIndex] = index
                    it[targetUrl] = url
                    it[mailingMessageId] = messageId
                }
            }
    }

    fun linkIndexByUrl(messageId: Uuid): Map<String, Int> =
        MailingMessageLinkTable
            .selectAll()
            .where { MailingMessageLinkTable.mailingMessageId eq messageId }
            .associate { it[MailingMessageLinkTable.targetUrl] to it[MailingMessageLinkTable.linkIndex] }

    /**
     * Withdrawal / unsubscribe / Art. 17(1)(b): erases the member's already-collected counting data
     * of the withdrawn kind for every delivery of this list. The token hash stays, so links in
     * already-sent mails keep redirecting (they just stop counting). Returns the number of
     * deliveries touched.
     */
    fun eraseForSubscription(
        listId: Uuid,
        memberId: Uuid,
        eraseOpen: Boolean,
        eraseClick: Boolean,
    ): Int {
        if (!eraseOpen && !eraseClick) return 0
        val listMessages = MailingMessageTable.select(MailingMessageTable.id).where { MailingMessageTable.mailingListId eq listId }
        val scope =
            (MailingDeliveryLogTable.memberId eq memberId) and
                (MailingDeliveryLogTable.mailingMessageId inSubQuery listMessages)
        if (eraseClick) {
            val deliveryIds = MailingDeliveryLogTable.select(MailingDeliveryLogTable.id).where { scope }
            MailingLinkClickTable.deleteWhere { mailingDeliveryLogId inSubQuery deliveryIds }
        }
        return MailingDeliveryLogTable.update({ scope }) {
            if (eraseClick) it[clickTracked] = false
            if (eraseOpen) {
                it[openTracked] = false
                it[openCount] = 0
                it[firstOpenedAt] = null
            }
        }
    }
}
