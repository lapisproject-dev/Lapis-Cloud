package network.lapis.cloud.server.webhook

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.security.ApiKeyStore
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
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
    })
