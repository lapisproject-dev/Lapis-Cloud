package network.lapis.cloud.server.webhook

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.WebhookDeliveryTable
import network.lapis.cloud.shared.domain.WebhookDeliveryStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.domain.WebhookFailureReason
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- persistence layer over [WebhookDeliveryTable]. Every
 * function here opens its OWN `transaction {}` EXCEPT [insert], which -- exactly the reverse
 * contract of [WebhookEndpointStore]/`network.lapis.cloud.server.security.ApiKeyStore` in the same
 * package (S1 in the plan's Stolperfallen list, called out explicitly because it is easy to get
 * backwards) -- MUST run inside the CALLER's already-open `transaction {}`: [insert] is the
 * outbox-pattern write, and it must commit atomically with the business fact that triggered it
 * (see [WebhookEventPublisher.publish] KDoc).
 */
internal object WebhookDeliveryQueue {
    data class DeliveryRow(
        val id: Uuid,
        val endpointId: Uuid,
        val eventId: Uuid,
        val eventType: WebhookEventType,
        val entityId: Uuid,
        val occurredAt: LocalDateTime,
        val payload: String,
        val status: WebhookDeliveryStatus,
        val attemptCount: Int,
        val nextAttemptAt: LocalDateTime?,
        val lastAttemptAt: LocalDateTime?,
        val lastHttpStatus: Int?,
        val lastError: String?,
        val createdAt: LocalDateTime,
        val deliveredAt: LocalDateTime?,
    )

    /**
     * Outbox insert -- see class KDoc. [eventId] is the receiver-visible idempotency key (the
     * `Lapis-Webhook-Id` header value AND the payload's own `"id"` field) -- it is a PARAMETER, not
     * generated here, because [WebhookEventPublisher.publish]/`WebhookService.sendWebhookTestEvent`
     * must embed the SAME id into [WebhookPayloads.build]'s output BEFORE this row is persisted
     * (the signed body and the row it lives in must agree on this id from the very first write).
     * `status` starts `PENDING` with `nextAttemptAt = now` (immediate first pickup) UNLESS
     * [initialStatus] overrides this (used by the synchronous test-event path -- see
     * `WebhookService.sendWebhookTestEvent`, S22 in the plan's Stolperfallen list). When
     * [initialStatus] is `DELIVERING`, [lastAttemptAt] is ALSO seeded to [now] (review fix) -- a
     * `null` [lastAttemptAt] on a `DELIVERING` row is invisible to [reapStaleClaims] (`less
     * staleCutoff` never matches SQL `NULL`), so a server restart between this insert and the
     * synchronous send's own `markTestDelivered`/`markTestFailed` call would otherwise strand the
     * row `DELIVERING` forever -- never reaped, never completed, and (being non-terminal)
     * permanently exempt from [deleteExpired] too, so it would keep shadowing every later delivery
     * as the endpoint's "most recent" one via [latestByEndpoint]'s `createdAt DESC` ordering.
     */
    fun insert(
        endpointId: Uuid,
        eventId: Uuid,
        eventType: WebhookEventType,
        entityId: Uuid,
        occurredAt: LocalDateTime,
        payload: String,
        now: LocalDateTime,
        initialStatus: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING,
    ): Uuid {
        val id = Uuid.random()
        WebhookDeliveryTable.insert {
            it[WebhookDeliveryTable.id] = id
            it[WebhookDeliveryTable.endpointId] = endpointId
            it[WebhookDeliveryTable.eventId] = eventId
            it[WebhookDeliveryTable.eventType] = eventType.name
            it[WebhookDeliveryTable.entityId] = entityId
            it[WebhookDeliveryTable.occurredAt] = occurredAt
            it[WebhookDeliveryTable.payload] = payload
            it[status] = initialStatus.name
            it[attemptCount] = if (initialStatus == WebhookDeliveryStatus.DELIVERING) 1 else 0
            it[nextAttemptAt] = if (initialStatus == WebhookDeliveryStatus.PENDING) now else null
            it[lastAttemptAt] = if (initialStatus == WebhookDeliveryStatus.DELIVERING) now else null
            it[lastHttpStatus] = null
            it[lastError] = null
            it[createdAt] = now
            it[deliveredAt] = null
        }
        return id
    }

    /**
     * Atomic conditional claim -- same idiom `network.lapis.cloud.server.security.ApiKeyStore
     * .revoke` KDoc documents at length: the `WHERE status = 'PENDING'` guard is baked into the
     * `UPDATE` itself so two near-simultaneous poller ticks (or two server instances -- see plan
     * O3) can never both claim the same row (`updatedRows == 1` for exactly one caller, `0` for
     * every other). Returns the row's state AFTER the claim, or `null` if it was already claimed
     * (or no longer `PENDING`) by the time this ran.
     */
    fun claimForDelivery(
        id: Uuid,
        now: LocalDateTime,
    ): DeliveryRow? =
        transaction {
            // Pre-read attemptCount rather than an in-place `Column + 1` SQL expression -- same
            // idiom DsgvoComplianceService/ConferenceStreamingService already establish
            // (`existing[Table.version] + 1`). Safe under the atomic conditional UPDATE below: no
            // OTHER successful claim can occur for this id while status stays PENDING, so this read
            // cannot go stale between here and the UPDATE.
            val currentAttempt =
                WebhookDeliveryTable
                    .select(WebhookDeliveryTable.attemptCount)
                    .where { WebhookDeliveryTable.id eq id }
                    .singleOrNull()
                    ?.get(WebhookDeliveryTable.attemptCount) ?: return@transaction null
            val updated =
                WebhookDeliveryTable.update({
                    (WebhookDeliveryTable.id eq id) and (WebhookDeliveryTable.status eq WebhookDeliveryStatus.PENDING.name)
                }) {
                    it[status] = WebhookDeliveryStatus.DELIVERING.name
                    it[attemptCount] = currentAttempt + 1
                    it[lastAttemptAt] = now
                    it[nextAttemptAt] = null
                }
            if (updated == 0) null else getById(id)
        }

    /** IDs of `PENDING` rows due for a (re-)attempt, oldest-due first -- see [WebhookDeliveryPoller] KDoc "Phase A". */
    fun dueForDelivery(
        now: LocalDateTime,
        limit: Int,
    ): List<Uuid> =
        transaction {
            WebhookDeliveryTable
                .select(WebhookDeliveryTable.id)
                .where {
                    (WebhookDeliveryTable.status eq WebhookDeliveryStatus.PENDING.name) and
                        (WebhookDeliveryTable.nextAttemptAt lessEq now)
                }.orderBy(WebhookDeliveryTable.nextAttemptAt, SortOrder.ASC)
                .limit(limit)
                .map { it[WebhookDeliveryTable.id] }
        }

    /**
     * Phase A0 -- reclaims any row stuck `DELIVERING` past [staleCutoff] (a crash mid-attempt, see
     * [WebhookDeliveryPoller] KDoc). Returns the TOTAL number of rows reclaimed, across both of the
     * two disjoint outcomes below (the caller only logs the sum, see `WebhookDeliveryPoller
     * .reapStaleClaims`'s own log line) -- ordinary rows and `WEBHOOK_TEST` rows are deliberately
     * NOT reclaimed the same way:
     *
     * - An ordinary row is reset back to `PENDING`, due immediately, so Phase A's own candidate scan
     *   can pick it back up in the SAME tick.
     * - A [WebhookEventType.WEBHOOK_TEST] row is instead force-completed TERMINALLY as `FAILED`
     *   (review fix, regression from the [insert] KDoc's own "review fix" that first made a
     *   `DELIVERING` test row visible to this reaper at all by seeding [DeliveryRow.lastAttemptAt]
     *   on it). `WebhookService.sendWebhookTestEvent`/D2/S22 promise a test is "synchronous, exactly
     *   one attempt ... NEVER enters the retry queue and NEVER deactivates the endpoint on failure".
     *   Resetting a stale test row to `PENDING` like any other row breaks that promise the moment a
     *   server restart/crash lands between the synchronous `insert(initialStatus = DELIVERING)` and
     *   its own `markTestDelivered`/`markTestFailed` call: the very next poller tick would pick the
     *   row up as an ordinary delivery, run it through the full 6-attempt retry/backoff ladder, and
     *   ultimately auto-deactivate the endpoint over a diagnostic test that was never meant to retry.
     */
    fun reapStaleClaims(
        staleCutoff: LocalDateTime,
        now: LocalDateTime,
    ): Int =
        transaction {
            val reapedTest =
                WebhookDeliveryTable.update({
                    (WebhookDeliveryTable.status eq WebhookDeliveryStatus.DELIVERING.name) and
                        (WebhookDeliveryTable.lastAttemptAt less staleCutoff) and
                        (WebhookDeliveryTable.eventType eq WebhookEventType.WEBHOOK_TEST.name)
                }) {
                    it[status] = WebhookDeliveryStatus.FAILED.name
                    it[lastError] = WebhookFailureReason.TIMEOUT.name
                    it[nextAttemptAt] = null
                }
            val reapedOrdinary =
                WebhookDeliveryTable.update({
                    (WebhookDeliveryTable.status eq WebhookDeliveryStatus.DELIVERING.name) and
                        (WebhookDeliveryTable.lastAttemptAt less staleCutoff) and
                        (WebhookDeliveryTable.eventType neq WebhookEventType.WEBHOOK_TEST.name)
                }) {
                    it[status] = WebhookDeliveryStatus.PENDING.name
                    it[nextAttemptAt] = now
                }
            reapedTest + reapedOrdinary
        }

    fun markDelivered(
        id: Uuid,
        httpStatus: Int,
        now: LocalDateTime,
    ) {
        transaction {
            WebhookDeliveryTable.update({ WebhookDeliveryTable.id eq id }) {
                it[status] = WebhookDeliveryStatus.DELIVERED.name
                it[lastHttpStatus] = httpStatus
                it[lastError] = null
                it[deliveredAt] = now
                it[nextAttemptAt] = null
            }
        }
    }

    /** Retry scheduled -- `PENDING` again with a backed-off `nextAttemptAt`. */
    fun markRetryScheduled(
        id: Uuid,
        nextAttemptAt: LocalDateTime,
        httpStatus: Int?,
        errorCode: String,
    ) {
        transaction {
            WebhookDeliveryTable.update({ WebhookDeliveryTable.id eq id }) {
                it[status] = WebhookDeliveryStatus.PENDING.name
                it[WebhookDeliveryTable.nextAttemptAt] = nextAttemptAt
                it[lastHttpStatus] = httpStatus
                it[lastError] = errorCode
            }
        }
    }

    /** Terminal -- retries exhausted, `410 Gone`, or `URL_REJECTED` after re-validation. */
    fun markAbandoned(
        id: Uuid,
        httpStatus: Int?,
        errorCode: String,
    ) {
        transaction {
            WebhookDeliveryTable.update({ WebhookDeliveryTable.id eq id }) {
                it[status] = WebhookDeliveryStatus.ABANDONED.name
                it[lastHttpStatus] = httpStatus
                it[lastError] = errorCode
                it[nextAttemptAt] = null
            }
        }
    }

    /** Terminal, no retry, used ONLY by the synchronous test-event path (D2) -- see [insert] KDoc "initialStatus". */
    fun markTestFailed(
        id: Uuid,
        httpStatus: Int?,
        errorCode: String,
    ) {
        transaction {
            WebhookDeliveryTable.update({ WebhookDeliveryTable.id eq id }) {
                it[status] = WebhookDeliveryStatus.FAILED.name
                it[lastHttpStatus] = httpStatus
                it[lastError] = errorCode
                it[nextAttemptAt] = null
            }
        }
    }

    fun markTestDelivered(
        id: Uuid,
        httpStatus: Int,
        now: LocalDateTime,
    ) = markDelivered(id = id, httpStatus = httpStatus, now = now)

    /** All remaining `PENDING` rows of [endpointId] -> `ABANDONED`/`ENDPOINT_DEACTIVATED` -- called from the SAME transaction as [WebhookEndpointStore.deactivate] (see that function's own KDoc / plan §5.6). Must run inside the caller's already-open `transaction {}`. */
    fun abandonAllPendingForEndpoint(endpointId: Uuid) {
        WebhookDeliveryTable.update({
            (WebhookDeliveryTable.endpointId eq endpointId) and (WebhookDeliveryTable.status eq WebhookDeliveryStatus.PENDING.name)
        }) {
            it[status] = WebhookDeliveryStatus.ABANDONED.name
            it[lastError] = "ENDPOINT_DEACTIVATED"
            it[nextAttemptAt] = null
        }
    }

    /**
     * ALL delivery rows of [endpointId], regardless of status -- unlike [deleteExpired] (which only
     * ever touches terminal rows within the retention window), this is the unconditional cleanup an
     * endpoint REMOVAL needs. Must run inside the CALLER's already-open `transaction {}`, and MUST
     * run BEFORE the caller's own `WebhookEndpointStore.remove` in that same transaction -- see
     * `WebhookService.removeWebhookUrl` call site. Without this, `webhook_delivery.endpoint_id`
     * (`V15__webhooks.sql`, a plain `REFERENCES webhook_endpoint(id)` with NO `ON DELETE CASCADE`)
     * makes the endpoint's row un-deletable via a raw FK violation the instant it has EVER produced
     * a single delivery -- including its own `webhook.test` row from "Test-Event senden" (review
     * finding, empirically reproduced against H2). Returns how many rows were deleted.
     */
    fun deleteByEndpoint(endpointId: Uuid): Int = WebhookDeliveryTable.deleteWhere { WebhookDeliveryTable.endpointId eq endpointId }

    /** Retention sweep -- deletes terminal rows ([WebhookDeliveryStatus.DELIVERED]/[WebhookDeliveryStatus.ABANDONED]/[WebhookDeliveryStatus.FAILED]) older than [olderThan], capped at [limit]. `PENDING`/`DELIVERING` are NEVER touched. Returns how many were deleted. */
    fun deleteExpired(
        olderThan: LocalDateTime,
        limit: Int,
    ): Int =
        transaction {
            val candidateIds =
                WebhookDeliveryTable
                    .select(WebhookDeliveryTable.id)
                    .where {
                        (
                            (WebhookDeliveryTable.status eq WebhookDeliveryStatus.DELIVERED.name) or
                                (WebhookDeliveryTable.status eq WebhookDeliveryStatus.ABANDONED.name) or
                                (WebhookDeliveryTable.status eq WebhookDeliveryStatus.FAILED.name)
                        ) and (WebhookDeliveryTable.createdAt less olderThan)
                    }.limit(limit)
                    .map { it[WebhookDeliveryTable.id] }
            if (candidateIds.isEmpty()) return@transaction 0
            WebhookDeliveryTable.deleteWhere { WebhookDeliveryTable.id inList candidateIds }
        }

    fun getById(id: Uuid): DeliveryRow? =
        transaction {
            WebhookDeliveryTable
                .selectAll()
                .where { WebhookDeliveryTable.id eq id }
                .singleOrNull()
                ?.toDeliveryRow()
        }

    fun listByEndpoint(
        endpointId: Uuid,
        limit: Int,
        offset: Int,
    ): List<DeliveryRow> =
        transaction {
            WebhookDeliveryTable
                .selectAll()
                .where { WebhookDeliveryTable.endpointId eq endpointId }
                .orderBy(WebhookDeliveryTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toDeliveryRow() }
        }

    fun countByEndpoint(endpointId: Uuid): Long =
        transaction {
            WebhookDeliveryTable.selectAll().where { WebhookDeliveryTable.endpointId eq endpointId }.count()
        }

    /**
     * Most recently CREATED delivery row for [endpointId], or `null` if none exists yet -- feeds
     * `WebhookEndpointDto.lastHttpStatus`/`.lastAttemptAt` so the endpoint card itself (not just a
     * fresh test-event result) can show "letzter HTTP-Status" per plan Abschnitt 9 / Design-Team
     * decision D1. Ordered by `createdAt`, not `lastAttemptAt` -- a row that has not been attempted
     * yet (`lastAttemptAt IS NULL`, freshly `PENDING`) must still outrank an older, already-terminal
     * row as "the latest delivery", otherwise the card would show a stale status while a newer
     * attempt is in flight.
     */
    fun latestByEndpoint(endpointId: Uuid): DeliveryRow? =
        transaction {
            WebhookDeliveryTable
                .selectAll()
                .where { WebhookDeliveryTable.endpointId eq endpointId }
                .orderBy(WebhookDeliveryTable.createdAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toDeliveryRow()
        }

    fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)

    private fun ResultRow.toDeliveryRow(): DeliveryRow =
        DeliveryRow(
            id = this[WebhookDeliveryTable.id],
            endpointId = this[WebhookDeliveryTable.endpointId],
            eventId = this[WebhookDeliveryTable.eventId],
            eventType = WebhookEventType.valueOf(this[WebhookDeliveryTable.eventType]),
            entityId = this[WebhookDeliveryTable.entityId],
            occurredAt = this[WebhookDeliveryTable.occurredAt],
            payload = this[WebhookDeliveryTable.payload],
            status = WebhookDeliveryStatus.valueOf(this[WebhookDeliveryTable.status]),
            attemptCount = this[WebhookDeliveryTable.attemptCount],
            nextAttemptAt = this[WebhookDeliveryTable.nextAttemptAt],
            lastAttemptAt = this[WebhookDeliveryTable.lastAttemptAt],
            lastHttpStatus = this[WebhookDeliveryTable.lastHttpStatus],
            lastError = this[WebhookDeliveryTable.lastError],
            createdAt = this[WebhookDeliveryTable.createdAt],
            deliveredAt = this[WebhookDeliveryTable.deliveredAt],
        )
}
