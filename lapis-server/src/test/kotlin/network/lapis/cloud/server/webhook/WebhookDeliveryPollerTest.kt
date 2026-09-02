package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.WebhookDeliveryTable
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.server.webhook.WebhookDeliveryQueue.nowLocalDateTime
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import network.lapis.cloud.shared.domain.WebhookDeliveryStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

private fun LocalDateTime.plusDuration(duration: kotlin.time.Duration): LocalDateTime =
    toInstant(TimeZone.UTC).plus(duration).toLocalDateTime(TimeZone.UTC)

/**
 * A [MailTransport] that completes [sent] on every call and counts invocations in [count] --
 * mirrors [network.lapis.cloud.server.mail.MailDispatcherTest]'s own `CompletableDeferred`-handshake
 * idiom (never `Thread.sleep`) so [WebhookDeactivationNotifier]'s async, fire-and-forget
 * [MailDispatcher.enqueue] fan-out can be awaited deterministically from a test.
 */
private class RecordingMailTransport : MailTransport {
    val count = AtomicInteger(0)
    var sent = CompletableDeferred<Unit>()

    override suspend fun send(
        to: String,
        subject: String,
        plainTextBody: String,
        htmlBody: String,
    ): MailSendOutcome {
        count.incrementAndGet()
        sent.complete(Unit)
        return MailSendOutcome.Sent
    }
}

/**
 * Exercises [WebhookDeliveryPoller]'s retry/backoff/abandon state machine end to end against a real
 * (H2) DB -- the gap flagged by review: before this file, none of [WebhookDeliveryPoller]'s logic
 * (backoff indexing, the `MAX_ATTEMPTS` boundary, `abandonAndDeactivate`'s
 * [WebhookEndpointDeactivation]/[WebhookDeactivationNotifier] fan-out) had a single test anywhere in
 * this codebase.
 *
 * **Deliberately drives every attempt through [WebhookUrlRejectionReason.NOT_PUBLICLY_ROUTABLE]**,
 * exactly [WebhookServiceTest]'s own documented reason for skipping `sendWebhookTestEvent`'s HTTP
 * outcome: there is no publicly-routable HTTPS endpoint available to this test suite, and
 * [OutboundUrlGuard]'s SSRF guard correctly, deterministically refuses a loopback URL regardless.
 * That refusal is [WebhookSendOutcome.Rejected] in [WebhookDeliverySender.sendOnce] -- the SAME
 * `WebhookFailureReason.URL_REJECTED` "Fehlversuch, not an immediate abandon" path a transient
 * DNS/timeout/HTTP-error failure would take (see [WebhookDeliveryPoller] KDoc "Klassifikation"), so
 * it drives the entire [WebhookDeliveryPoller.handleFailure]/[WebhookDeliveryPoller.handleResponse]
 * machinery (short of the unreachable 2xx/`410 Gone` branches, which genuinely need a real HTTP
 * response and stay untested here, same as the poller's own class KDoc documents for
 * [WebhookServiceTest]) without any network peer at all. The endpoint is created directly via
 * [WebhookEndpointStore.create] (bypassing `WebhookService.setWebhookUrl`'s own URL validation,
 * which would otherwise reject a loopback URL before it could ever be saved) and the delivery row is
 * inserted directly via [WebhookDeliveryQueue.insert] (bypassing the RPC layer entirely) -- the same
 * two-bypass idiom [WebhookServiceTest]'s own FK-violation regression test uses.
 */
class WebhookDeliveryPollerTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        val secretBox = SecretBox(randomKey())

        fun freshRejectedEndpoint(): WebhookEndpointStore.EndpointRow {
            val apiKey = ApiKeyStore.issue(label = "Poller Test Key ${Uuid.random()}", createdByMemberId = ADMIN_ID)
            val (row, _) =
                WebhookEndpointStore.create(
                    apiKeyId = apiKey.id,
                    // Loopback -- OutboundUrlGuard.checkWebhookUrl always rejects this as
                    // NOT_PUBLICLY_ROUTABLE, regardless of allowInsecureHttp -- see class KDoc.
                    url = "https://127.0.0.1/reject-me",
                    createdByMemberId = ADMIN_ID,
                    secretBox = secretBox,
                )
            return row
        }

        fun insertPending(
            endpointId: Uuid,
            now: LocalDateTime,
        ): Uuid =
            transaction {
                WebhookDeliveryQueue.insert(
                    endpointId = endpointId,
                    eventId = Uuid.random(),
                    eventType = WebhookEventType.MEMBER_CREATED,
                    entityId = Uuid.random(),
                    occurredAt = now,
                    payload = "{}",
                    now = now,
                )
            }

        fun freshPoller(
            transport: RecordingMailTransport,
            clockRef: () -> LocalDateTime,
        ): WebhookDeliveryPoller {
            val dispatcher = MailDispatcher(transport = transport, scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
            val notifier = WebhookDeactivationNotifier(mailDispatcher = dispatcher, branding = MailBranding.notConfigured())
            return WebhookDeliveryPoller(
                config =
                    WebhookConfig(
                        enabled = true,
                        allowInsecureHttp = false,
                        pollIntervalSeconds = 10,
                        maxDeliveriesPerTick = 50,
                        maxConcurrentDeliveries = 4,
                        retentionDays = 30,
                        secretEncryptionKey = null,
                    ),
                secretBox = secretBox,
                deactivationNotifier = notifier,
                clock = clockRef,
            )
        }

        test(
            "a URL_REJECTED delivery retries with the documented 30s/2min/10min/45min/3h backoff, then " +
                "abandons + deactivates the endpoint + notifies BOARD/ADMIN on attempt 6 (MAX_ATTEMPTS)",
        ) {
            val endpoint = freshRejectedEndpoint()
            var now = nowLocalDateTime()
            val deliveryId = insertPending(endpointId = endpoint.id, now = now)
            val transport = RecordingMailTransport()
            val poller = freshPoller(transport = transport, clockRef = { now })

            // Attempts 1..5 -- each a Fehlversuch (URL_REJECTED), scheduled to retry with the
            // matching RETRY_BACKOFF[attempt - 1] entry, endpoint and delivery both still alive.
            val expectedBackoffs = listOf(30.seconds, 2.minutes, 10.minutes, 45.minutes, 3.hours)
            for ((index, backoff) in expectedBackoffs.withIndex()) {
                val attemptNumber = index + 1
                runBlocking { poller.tick() }
                val row = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
                row.status shouldBe WebhookDeliveryStatus.PENDING
                row.attemptCount shouldBe attemptNumber
                row.lastError shouldBe "URL_REJECTED"
                val expectedNextAttemptAt = now.plusDuration(backoff)
                row.nextAttemptAt shouldBe expectedNextAttemptAt
                transaction { WebhookEndpointStore.getById(endpoint.id) }?.active shouldBe true

                now = expectedNextAttemptAt
            }

            // Attempt 6 == MAX_ATTEMPTS -- ABANDONED, endpoint deactivated (DELIVERY_FAILURES), and
            // WebhookDeactivationNotifier's mail fan-out fires (at least the seeded ADMIN account).
            runBlocking { poller.tick() }
            runBlocking { withTimeout(5.seconds) { transport.sent.await() } }

            val finalDelivery = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
            finalDelivery.status shouldBe WebhookDeliveryStatus.ABANDONED
            finalDelivery.lastError shouldBe "RETRIES_EXHAUSTED"
            finalDelivery.attemptCount shouldBe 6

            val finalEndpoint = requireNotNull(transaction { WebhookEndpointStore.getById(endpoint.id) })
            finalEndpoint.active shouldBe false
            finalEndpoint.deactivationReason shouldBe WebhookDeactivationReason.DELIVERY_FAILURES
            (transport.count.get() >= 1) shouldBe true
        }

        test("reapStaleClaims resets a poller-crashed DELIVERING row back to PENDING, due immediately, in the SAME tick") {
            val endpoint = freshRejectedEndpoint()
            val insertedAt = nowLocalDateTime()
            val deliveryId = insertPending(endpointId = endpoint.id, now = insertedAt)
            // Simulate a crash mid-attempt: claim it directly (bypassing the poller), leaving it
            // DELIVERING with a lastAttemptAt well past the poller's 5-minute stale-claim cutoff.
            transaction { WebhookDeliveryQueue.claimForDelivery(id = deliveryId, now = insertedAt) }
            requireNotNull(WebhookDeliveryQueue.getById(deliveryId)).status shouldBe WebhookDeliveryStatus.DELIVERING

            val tenMinutesLater = insertedAt.plusDuration(10.minutes)
            val transport = RecordingMailTransport()
            val poller = freshPoller(transport = transport) { tenMinutesLater }
            runBlocking { poller.tick() }

            // Reaped back to PENDING by Phase A0, THEN picked up again by Phase A in the very same
            // tick -- attemptCount is now 2 (the reap's re-queue, claimed a second time), status is
            // PENDING again (the Fehlversuch from THIS tick's own delivery attempt), never DELIVERING.
            val row = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
            row.status shouldBe WebhookDeliveryStatus.PENDING
            row.attemptCount shouldBe 2
            row.lastError shouldBe "URL_REJECTED"
        }

        test(
            "reapStaleClaims force-completes a stale DELIVERING WEBHOOK_TEST row as FAILED, never routes it " +
                "through the ordinary retry/deactivation machinery (D2/S22 regression guard)",
        ) {
            val endpoint = freshRejectedEndpoint()
            val insertedAt = nowLocalDateTime()
            // Mirror WebhookService.sendWebhookTestEvent's own insert -- initialStatus = DELIVERING,
            // never PENDING (see WebhookDeliveryQueue.insert KDoc "initialStatus").
            val deliveryId =
                transaction {
                    WebhookDeliveryQueue.insert(
                        endpointId = endpoint.id,
                        eventId = Uuid.random(),
                        eventType = WebhookEventType.WEBHOOK_TEST,
                        entityId = endpoint.id,
                        occurredAt = insertedAt,
                        payload = "{}",
                        now = insertedAt,
                        initialStatus = WebhookDeliveryStatus.DELIVERING,
                    )
                }
            requireNotNull(WebhookDeliveryQueue.getById(deliveryId)).status shouldBe WebhookDeliveryStatus.DELIVERING

            // Simulate the server crashing before markTestDelivered/markTestFailed ever ran -- ten
            // minutes later (well past the 5-minute stale-claim cutoff), run a real poller tick.
            val tenMinutesLater = insertedAt.plusDuration(10.minutes)
            val transport = RecordingMailTransport()
            val poller = freshPoller(transport = transport) { tenMinutesLater }
            runBlocking { poller.tick() }

            // Terminal FAILED, attemptCount untouched (never claimed a second time -- it never
            // became PENDING, so Phase A's due-for-delivery scan could never pick it up), endpoint
            // stays active, and no deactivation mail was sent -- the full opposite of what the
            // MEMBER_CREATED row above goes through in the very same tick shape.
            val row = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
            row.status shouldBe WebhookDeliveryStatus.FAILED
            row.attemptCount shouldBe 1
            row.nextAttemptAt.shouldBeNull()

            val finalEndpoint = requireNotNull(transaction { WebhookEndpointStore.getById(endpoint.id) })
            finalEndpoint.active shouldBe true
            transport.count.get() shouldBe 0
        }

        test(
            "F3 (Security-Audit-Fund, Runde 1, 2026-09-02): a delivery claimed while its endpoint is ALREADY " +
                "inactive is abandoned immediately as ENDPOINT_DEACTIVATED, never sent, never entered into the " +
                "retry/backoff ladder",
        ) {
            val endpoint = freshRejectedEndpoint()
            val insertedAt = nowLocalDateTime()
            val deliveryId = insertPending(endpointId = endpoint.id, now = insertedAt)

            // Simulate the race F3 describes: the endpoint is deactivated through a path that does
            // NOT also abandon this already-PENDING row. WebhookEndpointStore.deactivate (the
            // low-level flag flip) does exactly that -- unlike WebhookEndpointDeactivation.deactivate
            // (the higher-level cascade used everywhere else in this suite), it never touches
            // webhook_delivery at all, reproducing a row that survives deactivation still PENDING.
            transaction {
                WebhookEndpointStore.deactivate(
                    apiKeyId = endpoint.apiKeyId,
                    reason = WebhookDeactivationReason.MANUAL,
                    deactivatedByMemberId = ADMIN_ID,
                )
            }
            requireNotNull(transaction { WebhookEndpointStore.getById(endpoint.id) }).active shouldBe false
            requireNotNull(WebhookDeliveryQueue.getById(deliveryId)).status shouldBe WebhookDeliveryStatus.PENDING

            val transport = RecordingMailTransport()
            val poller = freshPoller(transport = transport) { insertedAt }
            runBlocking { poller.tick() }

            // Before the fix, this row would instead go through checkWebhookUrl's SSRF rejection
            // (URL_REJECTED) like every OTHER test in this suite, land back in PENDING with a 30s
            // backoff, and retry for up to ~4h against an endpoint everyone already believes is off.
            val row = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
            row.status shouldBe WebhookDeliveryStatus.ABANDONED
            row.lastError shouldBe "ENDPOINT_DEACTIVATED"
            row.attemptCount shouldBe 1 // claimed exactly once, never actually sent
            // No re-deactivation, no new mail -- the endpoint was already inactive before this tick.
            transport.count.get() shouldBe 0
        }

        test(
            "F2 part 3 (Security-Audit-Fund, Runde 1, 2026-09-02) defense in depth: a delivery claimed with " +
                "attemptCount already beyond MAX_ATTEMPTS is abandoned without another send attempt",
        ) {
            val endpoint = freshRejectedEndpoint()
            val insertedAt = nowLocalDateTime()
            val deliveryId = insertPending(endpointId = endpoint.id, now = insertedAt)
            // Force attemptCount past MAX_ATTEMPTS (6) directly at the DB layer -- reproduces
            // whatever pathological state this defense-in-depth guard is meant to catch without
            // needing to actually run the row through 6 real attempts first.
            transaction {
                WebhookDeliveryTable.update({ WebhookDeliveryTable.id eq deliveryId }) {
                    it[attemptCount] = 7
                }
            }

            val transport = RecordingMailTransport()
            val poller = freshPoller(transport = transport) { insertedAt }
            runBlocking { poller.tick() }
            runBlocking { withTimeout(5.seconds) { transport.sent.await() } }

            // claimForDelivery increments once more on the claim (7 -> 8) before this guard runs --
            // the row is abandoned right there, sender.sendOnce is never called a 7th time.
            val row = requireNotNull(WebhookDeliveryQueue.getById(deliveryId))
            row.status shouldBe WebhookDeliveryStatus.ABANDONED
            row.lastError shouldBe "RETRIES_EXHAUSTED"
            row.attemptCount shouldBe 8

            val finalEndpoint = requireNotNull(transaction { WebhookEndpointStore.getById(endpoint.id) })
            finalEndpoint.active shouldBe false
            finalEndpoint.deactivationReason shouldBe WebhookDeactivationReason.DELIVERY_FAILURES
        }

        test("runRetentionPhase deletes a terminal delivery row older than retentionDays, never touches a PENDING one") {
            val endpoint = freshRejectedEndpoint()
            val old = nowLocalDateTime().plusDuration(-(40 * 24).hours)
            val terminalId = insertPending(endpointId = endpoint.id, now = old)
            transaction { WebhookDeliveryQueue.markDelivered(id = terminalId, httpStatus = 200, now = old) }
            val pendingId = insertPending(endpointId = endpoint.id, now = old)

            val transport = RecordingMailTransport()
            val poller = freshPoller(transport = transport) { nowLocalDateTime() }
            runBlocking { poller.tick() }

            WebhookDeliveryQueue.getById(terminalId).shouldBeNull()
            // The PENDING row survives retention -- it gets claimed/attempted instead (URL_REJECTED,
            // still PENDING with a fresh backoff), never deleted, exactly [WebhookDeliveryPoller]'s
            // own "PENDING/DELIVERING are NEVER touched [by retention]" contract.
            requireNotNull(WebhookDeliveryQueue.getById(pendingId)).shouldNotBeNull()
        }
    })
