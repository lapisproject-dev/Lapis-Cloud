package network.lapis.cloud.server.security

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ApiKeyTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Server-side, DB-persisted, revocable API-key store (V1.3.1 "API-Fundament, lesend") -- the
 * SECOND Bearer credential namespace this codebase issues, alongside [SessionStore]/[SessionTable]
 * (see [ApiKeyAuth] KDoc "Four guarantees" for the full separation). Deliberately mirrors
 * [SessionStore]'s own shape: only a hash of the raw key is ever stored
 * ([ApiKeyTable.tokenHash] is [SessionTokens.hash] of the full raw key including the [API_KEY_TOKEN_PREFIX]
 * prefix), [resolve]/[touchLastUsed]/[issue]/[revoke]/[list] each open their OWN `transaction {}`
 * (same "runs before any caller-opened transaction" contract [SessionStore] documents) -- a REST
 * handler that also needs [network.lapis.cloud.server.rpc.GovernanceReads]/[network.lapis.cloud.server.rpc.MemberReads]
 * inside the SAME request must call this BEFORE opening its own `transaction { ... }` block, never
 * nest the two (see `PublicApiSupport.requirePublicApiPrincipal`'s own call-site ordering).
 *
 * Reuses [SessionTokens.newRawToken]/[SessionTokens.hash] verbatim -- same 256-bit,
 * cryptographically random, Base64URL-encoded secret material, just with a different, HARD-CODED
 * public prefix prepended before hashing (see [API_KEY_TOKEN_PREFIX] KDoc).
 */
object ApiKeyStore {
    /**
     * The literal prefix every raw API key starts with, e.g. `lapis_AbC123...`. Hard-coded (Design-
     * Team decision, see the V1.3.1 plan's §3.1/§14) -- distinguishes an API key from a session
     * token by CONTENT, not by a separate `Authorization` scheme word (both travel as `Bearer
     * <token>`, see [ApiKeyAuth]/[RequestContext.extractSessionToken] KDoc for the code-enforced
     * mutual exclusion this makes possible). Doubles as a GitHub-Secret-Scanning-friendly prefix
     * (regex `lapis_[A-Za-z0-9_-]{43}`, not itself submitted to GitHub as part of this wave).
     */
    const val API_KEY_TOKEN_PREFIX: String = "lapis_"

    /** How many characters of the raw key [ApiKeyRow.keyPrefix]/[IssuedApiKey.keyPrefix] retain for display -- always includes the full [API_KEY_TOKEN_PREFIX]. */
    private const val KEY_PREFIX_DISPLAY_LENGTH = 8

    /** [touchLastUsed] only writes when the previous value is missing or older than this -- see that function's own KDoc. */
    private val LAST_USED_THROTTLE = 5.minutes

    data class IssuedApiKey(
        val id: Uuid,
        val rawKey: String,
        val keyPrefix: String,
        val label: String,
        val createdAt: LocalDateTime,
        val createdByMemberId: Uuid,
        val expiresAt: LocalDateTime?,
    )

    data class ApiKeyRow(
        val id: Uuid,
        val label: String,
        val keyPrefix: String,
        val createdAt: LocalDateTime,
        val createdByMemberId: Uuid,
        val expiresAt: LocalDateTime?,
        val revokedAt: LocalDateTime?,
        val revokedByMemberId: Uuid?,
        val lastUsedAt: LocalDateTime?,
    )

    /**
     * Outcome of [resolve] -- a four-way discrimination so `PublicApiSupport.requirePublicApiPrincipal`
     * can report `key_revoked`/`key_expired` distinctly from a plain `unauthorized`, but ONLY once
     * the raw key's hash actually matched a row (Design-Team decision #4: no enumeration oracle for
     * an UNKNOWN key -- see [resolve] KDoc).
     */
    sealed interface Resolution {
        data class Valid(
            val principal: ApiKeyPrincipal,
        ) : Resolution

        data object Unknown : Resolution

        data class Revoked(
            val keyPrefix: String,
        ) : Resolution

        data class Expired(
            val keyPrefix: String,
        ) : Resolution
    }

    /** Mints and persists a brand-new API key for [createdByMemberId] -- always a fresh [SessionTokens.newRawToken], never client-supplied. */
    fun issue(
        label: String,
        createdByMemberId: Uuid,
        expiresAt: LocalDateTime? = null,
    ): IssuedApiKey {
        val rawKey = API_KEY_TOKEN_PREFIX + SessionTokens.newRawToken()
        val keyPrefix = rawKey.take(KEY_PREFIX_DISPLAY_LENGTH)
        val now = nowLocalDateTime()
        val id = Uuid.random()
        transaction {
            ApiKeyTable.insert {
                it[ApiKeyTable.id] = id
                it[ApiKeyTable.label] = label
                it[tokenHash] = SessionTokens.hash(rawKey)
                it[ApiKeyTable.keyPrefix] = keyPrefix
                it[createdAt] = now
                it[ApiKeyTable.createdByMemberId] = createdByMemberId
                it[ApiKeyTable.expiresAt] = expiresAt
                it[revokedAt] = null
                it[revokedByMemberId] = null
                it[lastUsedAt] = null
            }
        }
        return IssuedApiKey(
            id = id,
            rawKey = rawKey,
            keyPrefix = keyPrefix,
            label = label,
            createdAt = now,
            createdByMemberId = createdByMemberId,
            expiresAt = expiresAt,
        )
    }

    /**
     * Resolves [rawKey] to its [Resolution] -- looked up by hash. Distinguishes [Resolution.Revoked]/
     * [Resolution.Expired] from [Resolution.Unknown] ONLY after a hash match (Design-Team decision
     * #4): the caller already possesses the raw key at this point, so surfacing WHY a key that
     * genuinely exists no longer works carries no enumeration risk -- unlike distinguishing "wrong
     * key" from "right key, wrong state" for a caller who does NOT yet know a matching key exists.
     */
    fun resolve(rawKey: String): Resolution {
        val hash = SessionTokens.hash(rawKey)
        val now = nowLocalDateTime()
        return transaction {
            val row =
                ApiKeyTable
                    .selectAll()
                    .where { ApiKeyTable.tokenHash eq hash }
                    .singleOrNull()
                    ?: return@transaction Resolution.Unknown
            val prefix = row[ApiKeyTable.keyPrefix]
            when {
                row[ApiKeyTable.revokedAt] != null -> Resolution.Revoked(keyPrefix = prefix)
                row[ApiKeyTable.expiresAt]?.let { it <= now } == true -> Resolution.Expired(keyPrefix = prefix)
                else ->
                    Resolution.Valid(
                        principal =
                            ApiKeyPrincipal(
                                apiKeyId = row[ApiKeyTable.id],
                                label = row[ApiKeyTable.label],
                                keyPrefix = prefix,
                            ),
                    )
            }
        }
    }

    /**
     * Revokes the key identified by [id], on behalf of [revokedByMemberId]. Idempotent-signalling:
     * returns `null` for an unknown id OR a key already revoked (the caller -- [network.lapis.cloud.server.rpc.ApiKeyService.revokeApiKey] --
     * decides whether that is a 404 or a silent no-op; [ApiKeyStore] itself stays a thin persistence
     * layer). Returns the row's state AFTER the update on success.
     *
     * The `WHERE id = ... AND revoked_at IS NULL` guard is baked into the `UPDATE` itself (same
     * atomic-conditional-update idiom [SessionStore.revoke] already uses) rather than a separate
     * SELECT-then-UPDATE: under READ_COMMITTED (H2 and Postgres default), an `UPDATE` acquires its
     * row lock and re-checks the row's CURRENT (post-lock) state before applying, so two
     * near-simultaneous callers revoking the SAME id can never both see zero rows already revoked --
     * exactly one call's `update()` reports 1 affected row, the other reports 0 and returns `null`.
     * A plain SELECT first (checking `revokedAt == null` in Kotlin, THEN issuing the UPDATE) does NOT
     * have this guarantee: both transactions can read the pre-update row before either commits.
     */
    fun revoke(
        id: Uuid,
        revokedByMemberId: Uuid,
    ): ApiKeyRow? {
        val now = nowLocalDateTime()
        return transaction {
            val updatedRows =
                ApiKeyTable.update({ (ApiKeyTable.id eq id) and ApiKeyTable.revokedAt.isNull() }) {
                    it[revokedAt] = now
                    it[ApiKeyTable.revokedByMemberId] = revokedByMemberId
                }
            if (updatedRows == 0) return@transaction null
            ApiKeyTable
                .selectAll()
                .where { ApiKeyTable.id eq id }
                .single()
                .toApiKeyRow()
        }
    }

    /** Newest-first ([ApiKeyTable.createdAt] descending) -- see [network.lapis.cloud.shared.rpc.IApiKeyService.listApiKeys] KDoc for the documented ordering contract this fulfils. */
    fun list(includeRevoked: Boolean = false): List<ApiKeyRow> =
        transaction {
            val query = ApiKeyTable.selectAll()
            (if (includeRevoked) query else query.where { ApiKeyTable.revokedAt.isNull() })
                .orderBy(ApiKeyTable.createdAt, SortOrder.DESC)
                .map { it.toApiKeyRow() }
        }

    fun getOrNull(id: Uuid): ApiKeyRow? =
        transaction {
            ApiKeyTable
                .selectAll()
                .where { ApiKeyTable.id eq id }
                .singleOrNull()
                ?.toApiKeyRow()
        }

    /**
     * Best-effort "last used" bookkeeping -- writes [ApiKeyTable.lastUsedAt] ONLY when it is
     * currently `null` or older than [LAST_USED_THROTTLE] (Design-Team decision #11: a busy
     * integration hitting `/api/v1` many times per minute must not turn every single read into an
     * extra write; 5-minute resolution is plenty for an admin's "is this key still being used at
     * all" question). Never throws, never blocks the caller's response on failure -- callers
     * (`PublicApiSupport.requirePublicApiPrincipal`) are expected to fire-and-forget this the same
     * way [SessionStore.resolve]'s own touch is unconditional.
     */
    fun touchLastUsed(id: Uuid) {
        val now = nowLocalDateTime()
        transaction {
            val row = ApiKeyTable.selectAll().where { ApiKeyTable.id eq id }.singleOrNull() ?: return@transaction
            val last = row[ApiKeyTable.lastUsedAt]
            val staleEnough = last == null || (now.toInstant(TimeZone.UTC) - last.toInstant(TimeZone.UTC)) >= LAST_USED_THROTTLE
            if (staleEnough) {
                ApiKeyTable.update({ ApiKeyTable.id eq id }) { it[lastUsedAt] = now }
            }
        }
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toApiKeyRow(): ApiKeyStore.ApiKeyRow =
    ApiKeyStore.ApiKeyRow(
        id = this[ApiKeyTable.id],
        label = this[ApiKeyTable.label],
        keyPrefix = this[ApiKeyTable.keyPrefix],
        createdAt = this[ApiKeyTable.createdAt],
        createdByMemberId = this[ApiKeyTable.createdByMemberId],
        expiresAt = this[ApiKeyTable.expiresAt],
        revokedAt = this[ApiKeyTable.revokedAt],
        revokedByMemberId = this[ApiKeyTable.revokedByMemberId],
        lastUsedAt = this[ApiKeyTable.lastUsedAt],
    )
