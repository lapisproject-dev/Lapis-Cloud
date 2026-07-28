package network.lapis.cloud.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.PasswordResetTokenTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Server-side, DB-persisted, single-use password-reset-token store (V0.7.2 Beitritts-/
 * Registrierungs-Workflow) -- the "forgot password" mechanism [network.lapis.cloud.shared.rpc.IAuthService]
 * (V0.7.1) explicitly deferred to this wave. Mirrors [SessionStore]'s own shape closely (same
 * hash-only-persisted discipline, reusing [SessionTokens] unchanged for both raw-token generation
 * and hashing) -- deliberately NOT the same table, though: a session's liveness/purpose and a
 * reset token's liveness/purpose are different enough (multi-use-until-revoked vs. exactly-once)
 * that folding them into one table/column set would blur two distinct security properties.
 *
 * **Only a hash of the token is ever stored** -- [PasswordResetTokenTable.tokenHash] is
 * [SessionTokens.hash] of the raw token, computed fresh on every [createToken]/[consumeToken]
 * call. The raw, bearer-usable token exists only transiently in memory and (briefly) in the
 * outbound "reset your password" message -- see [network.lapis.cloud.server.mail.PasswordResetMailer]
 * KDoc for why NO real email transport delivers it anywhere in this codebase today.
 *
 * **Single-use, atomically**: [consumeToken] claims a token via a `SELECT ... FOR UPDATE` row
 * lock followed by a compare-and-swap `UPDATE ... WHERE consumed_at IS NULL` -- so two concurrent
 * `consumeToken` calls for the SAME raw token can never both succeed (the second always observes
 * `updated == 0` and returns `null`), the same row-lock + compare-and-swap discipline
 * [network.lapis.cloud.server.rpc.CrowdfundingService.approveProject]/`rejectProject` already
 * establish for a different table.
 *
 * **No scheduler exists in this codebase** (see CLAUDE.md kUML-Repo-Konventionen) --
 * [purgeExpired] therefore piggybacks opportunistically on [createToken] with a low, fixed
 * probability, same idiom [SessionStore.createSession]/[SessionStore.purgeExpired] already
 * establish.
 */
object PasswordResetTokenStore {
    /** Documented tunable -- how long a freshly issued reset token stays valid. Deliberately much shorter than [SessionStore.SESSION_TTL]: a reset token is a bearer credential to TAKE OVER an account, not merely to use one. */
    val RESET_TTL: Duration = 1.hours

    /** Probability [createToken] also runs [purgeExpired] -- see class KDoc "No scheduler exists". */
    private const val PURGE_PROBABILITY = 0.01

    /** Issues and persists a brand-new, single-use reset token for [memberId] -- always a fresh [SessionTokens.newRawToken] (see [SessionTokens] KDoc), never a client-supplied value. */
    fun createToken(memberId: Uuid): String {
        val rawToken = SessionTokens.newRawToken()
        val now = nowLocalDateTime()
        val expiresAt = (now.toInstant(TimeZone.UTC) + RESET_TTL).toLocalDateTime(TimeZone.UTC)
        transaction {
            PasswordResetTokenTable.insert {
                it[id] = Uuid.random()
                it[PasswordResetTokenTable.memberId] = memberId
                it[tokenHash] = SessionTokens.hash(rawToken)
                it[createdAt] = now
                it[PasswordResetTokenTable.expiresAt] = expiresAt
                it[consumedAt] = null
            }
        }
        if (Random.nextDouble() < PURGE_PROBABILITY) {
            runCatching { purgeExpired() }
                .onFailure { logger.warn(it) { "Opportunistic expired-password-reset-token purge failed (non-fatal)" } }
        }
        return rawToken
    }

    /**
     * Read-only lookup of the [Uuid] of the member [rawToken] authorizes a password reset for, or
     * `null` if [rawToken] is unknown, expired, or already consumed -- identical liveness check to
     * [consumeToken] but WITHOUT claiming/consuming the token. Callers that need to validate
     * something ELSE (e.g. [network.lapis.cloud.server.security.PasswordPolicy] against the
     * member's email) before actually spending a valid, single-use token MUST use this first and
     * only call [consumeToken] once that validation has passed -- otherwise a token gets burned on
     * a rejected `newPassword` and the caller has to request an entirely new reset email for what
     * was otherwise a perfectly valid, unexpired token. A benign TOCTOU race remains possible (the
     * token could be consumed by a concurrent request between this call and [consumeToken]) --
     * [consumeToken] itself is still the sole atomic authority and returns `null` in that case, so
     * this method is a pure optimization, never a security boundary.
     */
    fun peekMemberId(rawToken: String): Uuid? {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        return transaction {
            PasswordResetTokenTable
                .selectAll()
                .where {
                    (PasswordResetTokenTable.tokenHash eq tokenHash) and
                        PasswordResetTokenTable.consumedAt.isNull() and
                        (PasswordResetTokenTable.expiresAt greater now)
                }.singleOrNull()
                ?.get(PasswordResetTokenTable.memberId)
        }
    }

    /**
     * Atomically claims [rawToken] (single-use, row-locked compare-and-swap on
     * `consumed_at IS NULL AND expires_at > now`) and returns the [Uuid] of the member it
     * authorizes a password reset for, or `null` if [rawToken] is unknown, expired, or already
     * consumed -- see class KDoc "Single-use, atomically". Never throws for an invalid token.
     */
    fun consumeToken(rawToken: String): Uuid? {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        return transaction {
            val row =
                PasswordResetTokenTable
                    .selectAll()
                    .where {
                        (PasswordResetTokenTable.tokenHash eq tokenHash) and
                            PasswordResetTokenTable.consumedAt.isNull() and
                            (PasswordResetTokenTable.expiresAt greater now)
                    }.forUpdate()
                    .singleOrNull() ?: return@transaction null
            val updated =
                PasswordResetTokenTable.update({
                    (PasswordResetTokenTable.tokenHash eq tokenHash) and PasswordResetTokenTable.consumedAt.isNull()
                }) {
                    it[consumedAt] = now
                }
            if (updated == 0) return@transaction null
            row[PasswordResetTokenTable.memberId]
        }
    }

    /** Hard-deletes every reset-token row whose [PasswordResetTokenTable.expiresAt] is already in the past. Returns the number of rows deleted. */
    fun purgeExpired(): Int {
        val now = nowLocalDateTime()
        return transaction {
            PasswordResetTokenTable.deleteWhere { PasswordResetTokenTable.expiresAt less now }
        }
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)
}
