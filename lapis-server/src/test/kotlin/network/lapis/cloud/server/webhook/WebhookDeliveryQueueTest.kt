package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.shared.domain.WebhookDeliveryStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import java.security.SecureRandom
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

private fun freshEndpointId(secretBox: SecretBox): Uuid {
    val apiKey = ApiKeyStore.issue(label = "Delivery Queue Test Key ${Uuid.random()}", createdByMemberId = ADMIN_ID)
    val (row, _) =
        WebhookEndpointStore.create(
            apiKeyId = apiKey.id,
            url = "https://example.com/hook",
            createdByMemberId = ADMIN_ID,
            secretBox = secretBox,
        )
    return row.id
}

// DbClock.nowLocalDateTime, NOT a bare Clock.System.now().toLocalDateTime(...) -- see DbClock's own
// KDoc: an untruncated nanosecond-precision LocalDateTime written to a TIMESTAMP column can round-
// trip as a DIFFERENT (rounded) value, which broke this test's own due-by-"now" comparisons before
// this fix (a value stored as "now" could come back a few nanoseconds LATER than the in-memory
// "now" still held by the test, failing a `nextAttemptAt <= now` check that production code never
// hits this way -- WebhookDeliveryPoller always re-reads a FRESH DbClock "now" on its next tick,
// strictly after any previously-stored value).
private fun now() = DbClock.nowLocalDateTime(TimeZone.UTC)

/** [WebhookDeliveryQueue.insert] is a transactional-outbox write -- it must run inside the CALLER's own `transaction {}` (see that function's own KDoc), so every test call site here goes through this wrapper rather than calling it bare. */
private fun insertDelivery(
    endpointId: Uuid,
    eventType: WebhookEventType,
    entityId: Uuid,
    occurredAt: kotlinx.datetime.LocalDateTime,
    now: kotlinx.datetime.LocalDateTime,
    initialStatus: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING,
): Uuid =
    org.jetbrains.exposed.v1.jdbc.transactions.transaction {
        WebhookDeliveryQueue.insert(
            endpointId = endpointId,
            eventId = Uuid.random(),
            eventType = eventType,
            entityId = entityId,
            occurredAt = occurredAt,
            payload = "{}",
            now = now,
            initialStatus = initialStatus,
        )
    }

/** Exercises [WebhookDeliveryQueue] end to end against a real (H2) DB -- claim atomicity, backoff bookkeeping, the stale-claim reaper, and retention. */
class WebhookDeliveryQueueTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        val secretBox = SecretBox(randomKey())

        test("insert() with the PENDING default is immediately due, attemptCount starts at 0") {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )
            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.status shouldBe WebhookDeliveryStatus.PENDING
            row.attemptCount shouldBe 0
            (WebhookDeliveryQueue.dueForDelivery(now = n, limit = 100).contains(id)) shouldBe true
        }

        test("insert() with initialStatus = DELIVERING (test-event path) is never due, attemptCount starts at 1") {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.WEBHOOK_TEST,
                    entityId = endpointId,
                    occurredAt = n,
                    now = n,
                    initialStatus = WebhookDeliveryStatus.DELIVERING,
                )
            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.attemptCount shouldBe 1
            row.nextAttemptAt.shouldBeNull()
            (WebhookDeliveryQueue.dueForDelivery(now = n, limit = 100).contains(id)) shouldBe false
        }

        test(
            "insert() with initialStatus = DELIVERING seeds lastAttemptAt = now (review fix) -- a row stranded " +
                "DELIVERING by a crash mid-test-event is reapable, not stuck forever, and is force-completed " +
                "TERMINALLY (FAILED) rather than re-entering the retry queue (D2/S22 -- a test never retries)",
        ) {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.WEBHOOK_TEST,
                    entityId = endpointId,
                    occurredAt = n,
                    now = n,
                    initialStatus = WebhookDeliveryStatus.DELIVERING,
                )
            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.lastAttemptAt shouldBe n

            // Simulate the server crashing before markTestDelivered/markTestFailed ever ran -- 10
            // minutes later, the stale-claim reaper must be able to find this row and terminate it,
            // WITHOUT handing it back to the ordinary delivery/retry path (see the
            // "reapStaleClaims() resets a DELIVERING row" test below for the non-test counterpart).
            val staleCutoff = n.toInstantUtc().plus(4.minutes).toLocalDateTimeUtc()
            val laterNow = n.toInstantUtc().plus(10.minutes).toLocalDateTimeUtc()
            val reaped = WebhookDeliveryQueue.reapStaleClaims(staleCutoff = staleCutoff, now = laterNow)
            (reaped >= 1) shouldBe true
            val reapedRow = requireNotNull(WebhookDeliveryQueue.getById(id))
            reapedRow.status shouldBe WebhookDeliveryStatus.FAILED
            reapedRow.nextAttemptAt.shouldBeNull()
            // Never falls back into the poller's ordinary due-for-delivery scan -- confirms this is a
            // genuine terminal state, not a disguised retry.
            (WebhookDeliveryQueue.dueForDelivery(now = laterNow, limit = 100).contains(id)) shouldBe false
        }

        test("claimForDelivery() is atomic -- a second claim of an already-claimed row returns null") {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )

            val first = WebhookDeliveryQueue.claimForDelivery(id = id, now = n)
            first.shouldNotBeNull()
            first.status shouldBe WebhookDeliveryStatus.DELIVERING
            first.attemptCount shouldBe 1

            val second = WebhookDeliveryQueue.claimForDelivery(id = id, now = n)
            second.shouldBeNull()
        }

        test("markRetryScheduled() returns the row to PENDING with the given nextAttemptAt and error code") {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )
            WebhookDeliveryQueue.claimForDelivery(id = id, now = n)
            val retryAt = n.toInstantUtc().plus(30.minutes).toLocalDateTimeUtc()
            WebhookDeliveryQueue.markRetryScheduled(id = id, nextAttemptAt = retryAt, httpStatus = 503, errorCode = "HTTP_ERROR")
            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.status shouldBe WebhookDeliveryStatus.PENDING
            row.nextAttemptAt shouldBe retryAt
            row.lastError shouldBe "HTTP_ERROR"
            row.lastHttpStatus shouldBe 503
        }

        test("markAbandoned() is terminal -- ABANDONED rows are never returned by dueForDelivery") {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )
            WebhookDeliveryQueue.claimForDelivery(id = id, now = n)
            WebhookDeliveryQueue.markAbandoned(id = id, httpStatus = null, errorCode = "RETRIES_EXHAUSTED")
            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.status shouldBe WebhookDeliveryStatus.ABANDONED
            (
                WebhookDeliveryQueue
                    .dueForDelivery(
                        now = n.toInstantUtc().plus(4.hours).toLocalDateTimeUtc(),
                        limit = 100,
                    ).contains(id)
            ) shouldBe
                false
        }

        test("reapStaleClaims() resets a DELIVERING row whose lastAttemptAt predates the cutoff back to PENDING, due immediately") {
            val endpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )
            WebhookDeliveryQueue.claimForDelivery(id = id, now = n)

            // Simulate a crash 10 minutes ago -- well past the 5-minute stale cutoff.
            val staleCutoff = n.toInstantUtc().plus(4.minutes).toLocalDateTimeUtc()
            val laterNow = n.toInstantUtc().plus(10.minutes).toLocalDateTimeUtc()
            val reaped = WebhookDeliveryQueue.reapStaleClaims(staleCutoff = staleCutoff, now = laterNow)
            (reaped >= 1) shouldBe true
            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.status shouldBe WebhookDeliveryStatus.PENDING
            (WebhookDeliveryQueue.dueForDelivery(now = laterNow, limit = 100).contains(id)) shouldBe true
        }

        test(
            "abandonAllPendingForEndpoint() abandons every PENDING row of that endpoint with ENDPOINT_DEACTIVATED, leaves other endpoints' rows untouched",
        ) {
            val endpointId = freshEndpointId(secretBox)
            val otherEndpointId = freshEndpointId(secretBox)
            val n = now()
            val id =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )
            val otherId =
                insertDelivery(
                    endpointId = otherEndpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = n,
                    now = n,
                )

            org.jetbrains.exposed.v1.jdbc.transactions
                .transaction { WebhookDeliveryQueue.abandonAllPendingForEndpoint(endpointId) }

            val row = requireNotNull(WebhookDeliveryQueue.getById(id))
            row.status shouldBe WebhookDeliveryStatus.ABANDONED
            row.lastError shouldBe "ENDPOINT_DEACTIVATED"
            val otherRow = requireNotNull(WebhookDeliveryQueue.getById(otherId))
            otherRow.status shouldBe WebhookDeliveryStatus.PENDING
        }

        test("deleteExpired() only deletes terminal rows older than the cutoff, never PENDING") {
            val endpointId = freshEndpointId(secretBox)
            val old = n().toInstantUtc().minus(40.hours).toLocalDateTimeUtc()
            val terminalId =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = old,
                    now = old,
                )
            WebhookDeliveryQueue.markDelivered(id = terminalId, httpStatus = 200, now = old)
            val pendingId =
                insertDelivery(
                    endpointId = endpointId,
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = old,
                    now = old,
                )

            val cutoff = n().toInstantUtc().minus(30.hours).toLocalDateTimeUtc()
            val deleted = WebhookDeliveryQueue.deleteExpired(olderThan = cutoff, limit = 500)
            (deleted >= 1) shouldBe true
            WebhookDeliveryQueue.getById(terminalId).shouldBeNull()
            WebhookDeliveryQueue.getById(pendingId).shouldNotBeNull()
        }
    })

private fun n() = now()

private fun kotlinx.datetime.LocalDateTime.toInstantUtc() = this.toInstant(TimeZone.UTC)

private fun kotlin.time.Instant.toLocalDateTimeUtc() = this.toLocalDateTime(TimeZone.UTC)
