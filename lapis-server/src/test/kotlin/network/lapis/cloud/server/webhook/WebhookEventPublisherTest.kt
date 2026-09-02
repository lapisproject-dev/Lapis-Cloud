package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.shared.domain.WebhookEventType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

private fun freshActiveEndpoint(secretBox: SecretBox): WebhookEndpointStore.EndpointRow {
    val apiKey = ApiKeyStore.issue(label = "Publisher Test Key ${Uuid.random()}", createdByMemberId = ADMIN_ID)
    val (row, _) =
        WebhookEndpointStore.create(
            apiKeyId = apiKey.id,
            url = "https://example.com/publisher-hook",
            createdByMemberId = ADMIN_ID,
            secretBox = secretBox,
        )
    return row
}

/**
 * Exercises [WebhookEventPublisher.publish] against a real (H2) DB.
 *
 * Review fix (race, PostgreSQL-specific, Welle V1.3.2 Runde 2) -- see [WebhookEventPublisher] class
 * KDoc "Review fix". The per-endpoint SAVEPOINT this fix introduced only changes observable
 * behavior on PostgreSQL (where an uncaught statement error aborts the whole transaction); this
 * repository has no PostgreSQL test infrastructure (see `DatabaseConfig` KDoc -- H2 is the test/
 * default target), so the true concurrent-delete race cannot be reproduced deterministically here.
 * What IS verified against H2:
 *  1. The fan-out itself still works correctly across MULTIPLE endpoints with the savepoint wrapping
 *     in place (the regression risk this change actually carries -- a bug in the savepoint
 *     bookkeeping could easily break every OTHER endpoint's delivery, not just a failing one).
 *  2. The exact savepoint/rollback/release primitive [WebhookEventPublisher.publish] uses -- applied
 *     directly to a deliberately doomed insert (a foreign-key violation, same failure class the race
 *     produces) -- correctly discards only that one insert and leaves the surrounding transaction
 *     free to commit further writes, including OTHER, real deliveries.
 */
class WebhookEventPublisherTest :
    FunSpec({
        val secretBox = SecretBox(randomKey())

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        beforeEach {
            WebhookEventPublisher.install(
                WebhookConfig(
                    enabled = true,
                    allowInsecureHttp = false,
                    pollIntervalSeconds = 10,
                    maxDeliveriesPerTick = 50,
                    maxConcurrentDeliveries = 4,
                    retentionDays = 30,
                    secretEncryptionKey = null,
                ),
            )
        }

        afterEach { WebhookEventPublisher.resetForTests() }

        test("publish() fans out one delivery row per active endpoint, each with its own eventId") {
            val e1 = freshActiveEndpoint(secretBox)
            val e2 = freshActiveEndpoint(secretBox)
            val entityId = Uuid.random()
            val occurredAt = DbClock.nowLocalDateTime(TimeZone.UTC)

            transaction {
                WebhookEventPublisher.publish(
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = entityId,
                    occurredAt = occurredAt,
                )
            }

            val allD1 = WebhookDeliveryQueue.listByEndpoint(endpointId = e1.id, limit = 100, offset = 0)
            val allD2 = WebhookDeliveryQueue.listByEndpoint(endpointId = e2.id, limit = 100, offset = 0)
            val d1 = allD1.filter { it.entityId == entityId }
            val d2 = allD2.filter { it.entityId == entityId }

            d1.size shouldBe 1
            d2.size shouldBe 1
            // Same underlying fact, but each receiver gets its OWN idempotency key -- class KDoc "Fan-out".
            (d1.single().eventId == d2.single().eventId) shouldBe false
        }

        test(
            "the savepoint primitive publish() relies on: a doomed insert (FK violation) rolls back to its " +
                "OWN savepoint without aborting the surrounding transaction or a later, unrelated, real write",
        ) {
            val real = freshActiveEndpoint(secretBox)
            val nonExistentEndpointId = Uuid.random()
            val now = WebhookDeliveryQueue.nowLocalDateTime()
            val entityId = Uuid.random()

            transaction {
                val connection = TransactionManager.current().connection
                val savepoint = connection.setSavepoint("test_doomed_insert")
                val threw =
                    try {
                        WebhookDeliveryQueue.insert(
                            endpointId = nonExistentEndpointId,
                            eventId = Uuid.random(),
                            eventType = WebhookEventType.RESOLUTION_ADOPTED,
                            entityId = entityId,
                            occurredAt = now,
                            payload = "{}",
                            now = now,
                        )
                        false
                    } catch (_: Throwable) {
                        connection.rollback(savepoint)
                        true
                    }
                threw shouldBe true

                // The surrounding transaction must still be usable -- a real insert right after the
                // rolled-back savepoint must succeed and, crucially, this whole `transaction {}` block
                // must be able to commit (Exposed only surfaces a poisoned/aborted transaction when it
                // tries to commit or run a further statement, so reaching the end of this block without
                // an exception IS the assertion here).
                WebhookDeliveryQueue.insert(
                    endpointId = real.id,
                    eventId = Uuid.random(),
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = entityId,
                    occurredAt = now,
                    payload = "{}",
                    now = now,
                )
            }

            val allDelivered = WebhookDeliveryQueue.listByEndpoint(endpointId = real.id, limit = 100, offset = 0)
            val delivered = allDelivered.filter { it.entityId == entityId }
            delivered.size shouldBe 1
        }

        test("disabled config -> publish() is a no-op, no delivery rows for any active endpoint") {
            WebhookEventPublisher.resetForTests()
            WebhookEventPublisher.install(
                WebhookConfig(
                    enabled = false,
                    allowInsecureHttp = false,
                    pollIntervalSeconds = 10,
                    maxDeliveriesPerTick = 50,
                    maxConcurrentDeliveries = 4,
                    retentionDays = 30,
                    secretEncryptionKey = null,
                ),
            )
            val endpoint = freshActiveEndpoint(secretBox)
            val entityId = Uuid.random()

            transaction {
                WebhookEventPublisher.publish(
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = entityId,
                    occurredAt = DbClock.nowLocalDateTime(TimeZone.UTC),
                )
            }

            val allRows = WebhookDeliveryQueue.listByEndpoint(endpointId = endpoint.id, limit = 100, offset = 0)
            allRows.filter { it.entityId == entityId } shouldBe emptyList()
        }

        test("never-installed publisher -> publish() is a no-op, not a NullPointerException") {
            WebhookEventPublisher.resetForTests()
            transaction {
                WebhookEventPublisher.publish(
                    eventType = WebhookEventType.RESOLUTION_ADOPTED,
                    entityId = Uuid.random(),
                    occurredAt = DbClock.nowLocalDateTime(TimeZone.UTC),
                )
            }
        }
    })
