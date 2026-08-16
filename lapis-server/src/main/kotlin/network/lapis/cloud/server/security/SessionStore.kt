package network.lapis.cloud.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
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
 * Server-side, DB-persisted, revocable session store (V0.7.1 Authentifizierung) — replaces the
 * `X-Member-Id` stand-in with real login sessions. See `22-session.kuml.kts` file header for the
 * full domain model and the "session vs. stateless JWT" rationale (auditability/revocability
 * first-class in this codebase, same reasoning as the V0.5.3 GoBD audit-log chain).
 *
 * **Only a hash of the token is ever stored** — [SessionTable.tokenHash] is
 * [SessionTokens.hash] of the raw token, computed fresh on every [createSession]/[resolve]/
 * [revoke] call. The raw, bearer-usable token exists only transiently in memory and in the
 * client's cookie/Authorization header — a leaked database (backup, replica, SQL-injection read)
 * can never be replayed as a live session.
 *
 * **No scheduler exists in this codebase** (see CLAUDE.md kUML-Repo-Konventionen) — [purgeExpired]
 * therefore piggybacks opportunistically on [createSession] (the hottest session-table write path)
 * with a low, fixed probability, rather than requiring a cron job. This bounds row growth without
 * adding an out-of-process dependency; it also means expired-but-unpurged rows may briefly linger,
 * which is harmless since [resolve] already excludes them by `expiresAt`.
 */
object SessionStore {
    /** Documented tunable — how long a freshly issued session stays valid without being used. */
    val SESSION_TTL: Duration = 8.hours

    /** Probability [createSession] also runs [purgeExpired] — see class KDoc "No scheduler exists". */
    private const val PURGE_PROBABILITY = 0.01

    data class IssuedSession(
        val rawToken: String,
        val expiresAt: LocalDateTime,
    )

    /** Issues and persists a brand-new session for [memberId] — always a fresh [SessionTokens.newRawToken] (see [SessionTokens] KDoc "session-fixation"), never a client-supplied value. */
    fun createSession(memberId: Uuid): IssuedSession {
        val rawToken = SessionTokens.newRawToken()
        val now = nowLocalDateTime()
        val expiresAt = (now.toInstant(TimeZone.UTC) + SESSION_TTL).toLocalDateTime(TimeZone.UTC)
        transaction {
            SessionTable.insert {
                it[id] = Uuid.random()
                it[tokenHash] = SessionTokens.hash(rawToken)
                it[SessionTable.memberId] = memberId
                it[createdAt] = now
                it[SessionTable.expiresAt] = expiresAt
                it[lastUsedAt] = null
                it[revokedAt] = null
            }
        }
        if (Random.nextDouble() < PURGE_PROBABILITY) {
            runCatching { purgeExpired() }
                .onFailure { logger.warn(it) { "Opportunistic expired-session purge failed (non-fatal)" } }
        }
        return IssuedSession(rawToken = rawToken, expiresAt = expiresAt)
    }

    /**
     * Resolves [rawToken] to the [CurrentMember] it authenticates, or `null` if the token is
     * unknown, revoked, or expired. On a hit, also "touches" [SessionTable.lastUsedAt] to `now` —
     * best-effort session-activity bookkeeping, not itself security-relevant.
     */
    fun resolve(rawToken: String): CurrentMember? {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        return transaction {
            val row =
                (SessionTable innerJoin MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where {
                        (SessionTable.tokenHash eq tokenHash) and
                            SessionTable.revokedAt.isNull() and
                            (SessionTable.expiresAt greater now)
                    }.singleOrNull() ?: return@transaction null

            SessionTable.update({ SessionTable.tokenHash eq tokenHash }) {
                it[lastUsedAt] = now
            }

            // V0.8.2 OIDC-Gastzugang-Federation / V0.11.0: MemberTable is already joined above (no
            // extra query) -- status is a free byproduct of that join, see CurrentMember KDoc.
            CurrentMember(
                memberId = row[SessionTable.memberId],
                role = row[AccountTable.role],
                status = row[MemberTable.status],
            )
        }
    }

    /** Revokes the session identified by [rawToken]. Idempotent: an unknown or already-revoked token is a silent no-op, never an error (logout must always succeed from the caller's point of view). */
    fun revoke(rawToken: String) {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        transaction {
            SessionTable.update({ (SessionTable.tokenHash eq tokenHash) and SessionTable.revokedAt.isNull() }) {
                it[revokedAt] = now
            }
        }
    }

    /**
     * Revokes every still-live session belonging to [memberId], except the one hashing to
     * [exceptRawToken] (if given) — used by [AuthService.changePassword][network.lapis.cloud.server.rpc.AuthService.changePassword]
     * so a password change kicks every OTHER device/session out while the caller's own current
     * session stays valid.
     */
    fun revokeAllForMember(
        memberId: Uuid,
        exceptRawToken: String? = null,
    ) {
        val now = nowLocalDateTime()
        val exceptHash = exceptRawToken?.let { SessionTokens.hash(it) }
        transaction {
            SessionTable.update({
                val base = (SessionTable.memberId eq memberId) and SessionTable.revokedAt.isNull()
                if (exceptHash != null) base and (SessionTable.tokenHash neq exceptHash) else base
            }) {
                it[revokedAt] = now
            }
        }
    }

    /** Hard-deletes every session row whose [SessionTable.expiresAt] is already in the past. Returns the number of rows deleted. Safe to call at any time — expired rows carry no live authorization. */
    fun purgeExpired(): Int {
        val now = nowLocalDateTime()
        return transaction {
            SessionTable.deleteWhere { SessionTable.expiresAt less now }
        }
    }

    /** The [SessionTable.expiresAt] of the still-live session hashing to [rawToken], or `null` if it is unknown/revoked/expired — used by [network.lapis.cloud.server.rpc.AuthService.getSessionInfo] to report an accurate expiry. */
    fun expiresAtOf(rawToken: String): LocalDateTime? {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        return transaction {
            SessionTable
                .selectAll()
                .where {
                    (SessionTable.tokenHash eq tokenHash) and
                        SessionTable.revokedAt.isNull() and
                        (SessionTable.expiresAt greater now)
                }.singleOrNull()
                ?.get(SessionTable.expiresAt)
        }
    }

    /** Best-effort placeholder expiry ("now + [SESSION_TTL]") for the rare case a caller was resolved via the test-only trusted-header fallback (see [AuthTestMode]), which has no real [SessionTable] row at all. */
    fun placeholderExpiry(): LocalDateTime {
        val now = nowLocalDateTime()
        return (now.toInstant(TimeZone.UTC) + SESSION_TTL).toLocalDateTime(TimeZone.UTC)
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)
}
