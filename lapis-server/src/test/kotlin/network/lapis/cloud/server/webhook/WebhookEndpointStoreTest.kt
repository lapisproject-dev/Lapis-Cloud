package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.SecureRandom
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

private fun randomKey(): ByteArray = ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes)

/** Exercises [WebhookEndpointStore] end to end against a real (H2) DB. */
class WebhookEndpointStoreTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        val secretBox = SecretBox(randomKey())

        test("create() mints a whsec_lapis_ secret and the row round-trips it through revealSecret()") {
            val apiKey = ApiKeyStore.issue(label = "Webhook Test Key A", createdByMemberId = ADMIN_ID)
            val (row, rawSecret) =
                WebhookEndpointStore.create(
                    apiKeyId = apiKey.id,
                    url = "https://example.com/hook",
                    createdByMemberId = ADMIN_ID,
                    secretBox = secretBox,
                )
            rawSecret.shouldNotBeNull()
            (rawSecret.startsWith("whsec_lapis_")) shouldBe true
            row.secretPrefix shouldBe rawSecret.take(16)
            row.revealSecret(secretBox) shouldBe rawSecret
            row.active shouldBe true
        }

        test("rotateSecret() mints a NEW secret that decrypts differently from the old one -- no grace window") {
            val apiKey = ApiKeyStore.issue(label = "Webhook Test Key B", createdByMemberId = ADMIN_ID)
            val (_, oldSecret) =
                WebhookEndpointStore.create(
                    apiKeyId = apiKey.id,
                    url = "https://example.com/hook",
                    createdByMemberId = ADMIN_ID,
                    secretBox = secretBox,
                )
            val (rotatedRow, newSecret) =
                requireNotNull(
                    WebhookEndpointStore.rotateSecret(apiKeyId = apiKey.id, updatedByMemberId = ADMIN_ID, secretBox = secretBox),
                )
            newSecret shouldNotBe oldSecret
            rotatedRow.revealSecret(secretBox) shouldBe newSecret
        }

        test("deactivate() is idempotent-signalling -- second call on an already-inactive endpoint returns null") {
            val apiKey = ApiKeyStore.issue(label = "Webhook Test Key C", createdByMemberId = ADMIN_ID)
            WebhookEndpointStore.create(
                apiKeyId = apiKey.id,
                url = "https://example.com/hook",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )
            val first =
                WebhookEndpointStore.deactivate(
                    apiKeyId = apiKey.id,
                    reason = WebhookDeactivationReason.MANUAL,
                    deactivatedByMemberId = ADMIN_ID,
                )
            first.shouldNotBeNull()
            first.active shouldBe false
            val second =
                WebhookEndpointStore.deactivate(
                    apiKeyId = apiKey.id,
                    reason = WebhookDeactivationReason.MANUAL,
                    deactivatedByMemberId = ADMIN_ID,
                )
            second.shouldBeNull()
        }

        test("reactivate() clears active/deactivatedAt/deactivationReason") {
            val apiKey = ApiKeyStore.issue(label = "Webhook Test Key D", createdByMemberId = ADMIN_ID)
            WebhookEndpointStore.create(
                apiKeyId = apiKey.id,
                url = "https://example.com/hook",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )
            WebhookEndpointStore.deactivate(
                apiKeyId = apiKey.id,
                reason = WebhookDeactivationReason.DELIVERY_FAILURES,
                deactivatedByMemberId = null,
            )
            val reactivated = requireNotNull(WebhookEndpointStore.reactivate(apiKeyId = apiKey.id, updatedByMemberId = ADMIN_ID))
            reactivated.active shouldBe true
            reactivated.deactivationReason.shouldBeNull()
        }

        test("migrateApiKeyId() repoints an existing endpoint's apiKeyId, secret unchanged") {
            val oldKey = ApiKeyStore.issue(label = "Webhook Test Key E (old)", createdByMemberId = ADMIN_ID)
            val newKey = ApiKeyStore.issue(label = "Webhook Test Key E (new)", createdByMemberId = ADMIN_ID)
            val (_, rawSecret) =
                WebhookEndpointStore.create(
                    apiKeyId = oldKey.id,
                    url = "https://example.com/hook",
                    createdByMemberId = ADMIN_ID,
                    secretBox = secretBox,
                )
            // migrateApiKeyId() is documented to run inside the CALLER's own transaction (see its
            // KDoc) -- called bare here in a test, it must be wrapped explicitly.
            org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                WebhookEndpointStore.migrateApiKeyId(oldApiKeyId = oldKey.id, newApiKeyId = newKey.id)
            }

            WebhookEndpointStore.getByApiKeyId(oldKey.id).shouldBeNull()
            val migrated = requireNotNull(WebhookEndpointStore.getByApiKeyId(newKey.id))
            migrated.apiKeyId shouldBe newKey.id
            migrated.revealSecret(secretBox) shouldBe rawSecret
        }

        test("remove() deletes the row entirely -- idempotent, false on a second call") {
            val apiKey = ApiKeyStore.issue(label = "Webhook Test Key F", createdByMemberId = ADMIN_ID)
            WebhookEndpointStore.create(
                apiKeyId = apiKey.id,
                url = "https://example.com/hook",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )
            WebhookEndpointStore.remove(apiKey.id) shouldBe true
            WebhookEndpointStore.getByApiKeyId(apiKey.id).shouldBeNull()
            WebhookEndpointStore.remove(apiKey.id) shouldBe false
        }

        test("listActive() only returns active endpoints") {
            val activeKey = ApiKeyStore.issue(label = "Webhook Test Key G (active)", createdByMemberId = ADMIN_ID)
            val inactiveKey = ApiKeyStore.issue(label = "Webhook Test Key G (inactive)", createdByMemberId = ADMIN_ID)
            WebhookEndpointStore.create(
                apiKeyId = activeKey.id,
                url = "https://example.com/a",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )
            WebhookEndpointStore.create(
                apiKeyId = inactiveKey.id,
                url = "https://example.com/b",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )
            WebhookEndpointStore.deactivate(
                apiKeyId = inactiveKey.id,
                reason = WebhookDeactivationReason.MANUAL,
                deactivatedByMemberId = ADMIN_ID,
            )

            val activeIds =
                org.jetbrains.exposed.v1.jdbc.transactions
                    .transaction { WebhookEndpointStore.listActive().map { it.apiKeyId } }
            (activeKey.id in activeIds) shouldBe true
            (inactiveKey.id in activeIds) shouldBe false
        }

        test(
            "F7 (Security-Audit-Fund, Runde 1, 2026-09-02): listActive() excludes an endpoint whose underlying " +
                "API key has EXPIRED, even though the endpoint row itself is still active=true (expiry has no " +
                "deactivation cascade the way revocation does)",
        ) {
            val expiredKey =
                ApiKeyStore.issue(
                    label = "Webhook Test Key H (expired)",
                    createdByMemberId = ADMIN_ID,
                    expiresAt = LocalDateTime(2020, 1, 1, 0, 0),
                )
            WebhookEndpointStore.create(
                apiKeyId = expiredKey.id,
                url = "https://example.com/expired",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )

            val activeIds = transaction { WebhookEndpointStore.listActive().map { it.apiKeyId } }
            (expiredKey.id in activeIds) shouldBe false

            // The endpoint row's own `active` flag is untouched by expiry -- unlike revocation,
            // nothing proactively cascades on a key merely passing its expiresAt. listActive()'s
            // own join filter is the ONLY thing excluding it from delivery.
            val endpoint = requireNotNull(transaction { WebhookEndpointStore.getByApiKeyId(expiredKey.id) })
            endpoint.active shouldBe true
        }

        test("listActive() still returns an endpoint whose API key has no expiry at all, or one that has not expired yet") {
            val noExpiryKey = ApiKeyStore.issue(label = "Webhook Test Key I (no expiry)", createdByMemberId = ADMIN_ID)
            val notYetExpiredKey =
                ApiKeyStore.issue(
                    label = "Webhook Test Key J (future expiry)",
                    createdByMemberId = ADMIN_ID,
                    expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                )
            WebhookEndpointStore.create(
                apiKeyId = noExpiryKey.id,
                url = "https://example.com/no-expiry",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )
            WebhookEndpointStore.create(
                apiKeyId = notYetExpiredKey.id,
                url = "https://example.com/future-expiry",
                createdByMemberId = ADMIN_ID,
                secretBox = secretBox,
            )

            val activeIds = transaction { WebhookEndpointStore.listActive().map { it.apiKeyId } }
            (noExpiryKey.id in activeIds) shouldBe true
            (notYetExpiredKey.id in activeIds) shouldBe true
        }
    })
