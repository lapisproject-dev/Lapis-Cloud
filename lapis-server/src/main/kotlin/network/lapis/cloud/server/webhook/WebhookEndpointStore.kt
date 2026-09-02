package network.lapis.cloud.server.webhook

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ApiKeyTable
import network.lapis.cloud.server.db.generated.WebhookEndpointTable
import network.lapis.cloud.server.security.SessionTokens
import network.lapis.cloud.shared.domain.WebhookDeactivationReason
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** `"whsec_lapis_" + SessionTokens.newRawToken()` -- see [WebhookEndpointStore.create]/[WebhookEndpointStore.rotateSecret] KDoc. */
private const val WEBHOOK_SECRET_PREFIX = "whsec_lapis_"

/** How many characters of the raw secret [WebhookEndpointStore.EndpointRow.secretPrefix] retains for display -- includes [WEBHOOK_SECRET_PREFIX]. */
private const val SECRET_PREFIX_DISPLAY_LENGTH = 16

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- server-side, DB-persisted store for [WebhookEndpointTable],
 * mirroring `network.lapis.cloud.server.security.ApiKeyStore`'s own shape ("resolve/mutate, each
 * function opens its OWN `transaction {}`"). **Every raw signature secret is encrypted at rest**
 * via [SecretBox] (AAD-bound to the endpoint's own id, same "ciphertext cannot be moved to a
 * different row" discipline `ConferenceStreamingService`'s own `SecretBox` usage establishes) --
 * unlike [network.lapis.cloud.server.security.ApiKeyStore]'s API-key hash, the signature secret
 * must be recoverable in PLAINTEXT so [WebhookSigner.sign] can use it on every delivery attempt, so
 * hashing (irreversible) is not an option here -- encryption is.
 */
internal object WebhookEndpointStore {
    data class EndpointRow(
        val id: Uuid,
        val apiKeyId: Uuid,
        val apiKeyLabel: String,
        val url: String,
        val secretSealed: String,
        val secretPrefix: String,
        val active: Boolean,
        val createdAt: LocalDateTime,
        val createdByMemberId: Uuid,
        val updatedAt: LocalDateTime?,
        val updatedByMemberId: Uuid?,
        val deactivatedAt: LocalDateTime?,
        val deactivatedByMemberId: Uuid?,
        val deactivationReason: WebhookDeactivationReason?,
    ) {
        /** Decrypts [secretSealed] using [secretBox] -- AAD bound to [id], see class KDoc. */
        fun revealSecret(secretBox: SecretBox): String = secretBox.open(sealed = secretSealed, aad = id.toString())
    }

    /**
     * Creates a brand-new endpoint for [apiKeyId] -- caller (`WebhookService.setWebhookUrl`) must
     * have already verified none exists yet (`uq_webhook_endpoint_api_key` backstops this at the DB
     * layer regardless). Returns the raw secret ONCE, alongside the persisted row.
     */
    fun create(
        apiKeyId: Uuid,
        url: String,
        createdByMemberId: Uuid,
        secretBox: SecretBox,
    ): Pair<EndpointRow, String> {
        val id = Uuid.random()
        val rawSecret = WEBHOOK_SECRET_PREFIX + SessionTokens.newRawToken()
        val secretPrefix = rawSecret.take(SECRET_PREFIX_DISPLAY_LENGTH)
        val secretSealed = secretBox.seal(plaintext = rawSecret, aad = id.toString())
        val now = nowLocalDateTime()
        transaction {
            WebhookEndpointTable.insert {
                it[WebhookEndpointTable.id] = id
                it[WebhookEndpointTable.apiKeyId] = apiKeyId
                it[WebhookEndpointTable.url] = url
                it[WebhookEndpointTable.secretSealed] = secretSealed
                it[WebhookEndpointTable.secretPrefix] = secretPrefix
                it[active] = true
                it[createdAt] = now
                it[WebhookEndpointTable.createdByMemberId] = createdByMemberId
                it[updatedAt] = null
                it[updatedByMemberId] = null
                it[deactivatedAt] = null
                it[deactivatedByMemberId] = null
                it[deactivationReason] = null
            }
        }
        return requireNotNull(getByApiKeyId(apiKeyId)) to rawSecret
    }

    /** Updates an EXISTING endpoint's [url] only -- secret unchanged. `null` if no endpoint exists for [apiKeyId]. */
    fun updateUrl(
        apiKeyId: Uuid,
        url: String,
        updatedByMemberId: Uuid,
    ): EndpointRow? {
        val now = nowLocalDateTime()
        return transaction {
            val updated =
                WebhookEndpointTable.update({ WebhookEndpointTable.apiKeyId eq apiKeyId }) {
                    it[WebhookEndpointTable.url] = url
                    it[updatedAt] = now
                    it[WebhookEndpointTable.updatedByMemberId] = updatedByMemberId
                }
            if (updated == 0) null else getByApiKeyId(apiKeyId)
        }
    }

    /** Mints and persists a fresh secret for [apiKeyId]'s endpoint -- old secret is immediately unusable (no dual-secret grace window, see [WebhookSigner] KDoc "Secret"). `null` if no endpoint exists. */
    fun rotateSecret(
        apiKeyId: Uuid,
        updatedByMemberId: Uuid,
        secretBox: SecretBox,
    ): Pair<EndpointRow, String>? {
        val existing = getByApiKeyId(apiKeyId) ?: return null
        val rawSecret = WEBHOOK_SECRET_PREFIX + SessionTokens.newRawToken()
        val secretPrefix = rawSecret.take(SECRET_PREFIX_DISPLAY_LENGTH)
        val secretSealed = secretBox.seal(plaintext = rawSecret, aad = existing.id.toString())
        val now = nowLocalDateTime()
        transaction {
            WebhookEndpointTable.update({ WebhookEndpointTable.apiKeyId eq apiKeyId }) {
                it[WebhookEndpointTable.secretSealed] = secretSealed
                it[WebhookEndpointTable.secretPrefix] = secretPrefix
                it[updatedAt] = now
                it[WebhookEndpointTable.updatedByMemberId] = updatedByMemberId
            }
        }
        return requireNotNull(getByApiKeyId(apiKeyId)) to rawSecret
    }

    /** Idempotent -- `false` if no endpoint existed for [apiKeyId]. */
    fun remove(apiKeyId: Uuid): Boolean =
        transaction {
            WebhookEndpointTable.deleteWhere { WebhookEndpointTable.apiKeyId eq apiKeyId } > 0
        }

    /** `true` iff the row existed and was ACTIVE before this call (idempotent-signalling, same idiom as `ApiKeyStore.revoke`). */
    fun deactivate(
        apiKeyId: Uuid,
        reason: WebhookDeactivationReason,
        deactivatedByMemberId: Uuid?,
    ): EndpointRow? {
        val now = nowLocalDateTime()
        return transaction {
            val updated =
                WebhookEndpointTable.update({
                    (WebhookEndpointTable.apiKeyId eq apiKeyId) and (WebhookEndpointTable.active eq true)
                }) {
                    it[active] = false
                    it[deactivatedAt] = now
                    it[WebhookEndpointTable.deactivatedByMemberId] = deactivatedByMemberId
                    it[deactivationReason] = reason.name
                }
            if (updated == 0) null else getByApiKeyId(apiKeyId)
        }
    }

    /** `null` if no endpoint exists, or one exists but is already active (nothing to reactivate). */
    fun reactivate(
        apiKeyId: Uuid,
        updatedByMemberId: Uuid,
    ): EndpointRow? {
        val now = nowLocalDateTime()
        return transaction {
            val updated =
                WebhookEndpointTable.update({
                    (WebhookEndpointTable.apiKeyId eq apiKeyId) and (WebhookEndpointTable.active eq false)
                }) {
                    it[active] = true
                    it[deactivatedAt] = null
                    it[deactivatedByMemberId] = null
                    it[deactivationReason] = null
                    it[updatedAt] = now
                    it[WebhookEndpointTable.updatedByMemberId] = updatedByMemberId
                }
            if (updated == 0) null else getByApiKeyId(apiKeyId)
        }
    }

    /**
     * S10 in the plan's Stolperfallen list -- `ApiKeyService.reissueApiKey` mints a NEW key id;
     * without this, an endpoint's `api_key_id` FK would keep pointing at the revoked old key
     * (`uq_webhook_endpoint_api_key` still "occupied" by the dead id) and the endpoint would
     * silently orphan -- invisible in the UI, never polled. Runs inside the CALLER's own
     * transaction (unlike every other function here, which opens its own) -- called from
     * `ApiKeyService.reissueApiKey`'s existing `transaction { ... }` block, same "outbox must
     * commit atomically with the triggering fact" reasoning as [WebhookEventPublisher.publish].
     * The signature secret is deliberately left UNCHANGED -- secret and key are independent
     * credentials (see `IWebhookService.setWebhookUrl` KDoc).
     */
    fun migrateApiKeyId(
        oldApiKeyId: Uuid,
        newApiKeyId: Uuid,
    ) {
        WebhookEndpointTable.update({ WebhookEndpointTable.apiKeyId eq oldApiKeyId }) {
            it[apiKeyId] = newApiKeyId
        }
    }

    fun getByApiKeyId(apiKeyId: Uuid): EndpointRow? =
        transaction {
            joinedQuery()
                .where { WebhookEndpointTable.apiKeyId eq apiKeyId }
                .singleOrNull()
                ?.toEndpointRow()
        }

    fun getById(id: Uuid): EndpointRow? =
        transaction {
            joinedQuery()
                .where { WebhookEndpointTable.id eq id }
                .singleOrNull()
                ?.toEndpointRow()
        }

    fun list(): List<EndpointRow> =
        transaction {
            joinedQuery().map { it.toEndpointRow() }
        }

    /**
     * Every currently-ACTIVE endpoint -- used by [WebhookEventPublisher.publish] to fan out one
     * delivery row per subscriber. Must run inside the caller's already-open `transaction {}`.
     *
     * **Security-Audit-Fund F7 (Runde 1, 2026-09-02, MINOR)**: filters on the joined
     * [ApiKeyTable] row too, not only [WebhookEndpointTable.active] -- a REVOKED key already
     * cascades through [network.lapis.cloud.server.rpc.ApiKeyService.revokeApiKey]'s `KEY_REVOKED`
     * deactivation (`active` becomes `false`, so `revokedAt.isNull()` here is normally redundant
     * with that cascade, kept as defense in depth against that cascade ever being missed/raced), but
     * an EXPIRED key has no such cascade at all -- `expiresAt` passing is a pure clock event nothing
     * proactively reacts to. Without the `expiresAt` half of this filter, an endpoint tied to an
     * already-expired API key kept receiving every new event indefinitely (including
     * `contribution.paid`/`donation.received` Fat-event payloads carrying `amount`/`currency`) even
     * though the same key can no longer authenticate a single `/api/v1` read.
     */
    fun listActive(): List<EndpointRow> {
        val now = nowLocalDateTime()
        return joinedQuery()
            .where {
                (WebhookEndpointTable.active eq true) and
                    ApiKeyTable.revokedAt.isNull() and
                    (ApiKeyTable.expiresAt.isNull() or (ApiKeyTable.expiresAt greater now))
            }.map { it.toEndpointRow() }
    }

    private fun joinedQuery() = (WebhookEndpointTable innerJoin ApiKeyTable).selectAll()

    private fun ResultRow.toEndpointRow(): EndpointRow =
        EndpointRow(
            id = this[WebhookEndpointTable.id],
            apiKeyId = this[WebhookEndpointTable.apiKeyId],
            apiKeyLabel = this[ApiKeyTable.label],
            url = this[WebhookEndpointTable.url],
            secretSealed = this[WebhookEndpointTable.secretSealed],
            secretPrefix = this[WebhookEndpointTable.secretPrefix],
            active = this[WebhookEndpointTable.active],
            createdAt = this[WebhookEndpointTable.createdAt],
            createdByMemberId = this[WebhookEndpointTable.createdByMemberId],
            updatedAt = this[WebhookEndpointTable.updatedAt],
            updatedByMemberId = this[WebhookEndpointTable.updatedByMemberId],
            deactivatedAt = this[WebhookEndpointTable.deactivatedAt],
            deactivatedByMemberId = this[WebhookEndpointTable.deactivatedByMemberId],
            deactivationReason = this[WebhookEndpointTable.deactivationReason]?.let { WebhookDeactivationReason.valueOf(it) },
        )

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)
}
