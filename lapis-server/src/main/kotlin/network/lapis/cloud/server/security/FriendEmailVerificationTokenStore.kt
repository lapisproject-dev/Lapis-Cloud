package network.lapis.cloud.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
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
 * Server-side, DB-persisted, single-use email-verification-token store (V0.11.0 FRIEND self-
 * registration) -- a near-mechanical clone of [PasswordResetTokenStore], same class KDoc reasoning
 * applies here: hash-only persistence, single-use compare-and-swap consumption, opportunistic
 * purge. Deliberately a SEPARATE table/class from [PasswordResetTokenStore] (not a reused one with
 * a `purpose` discriminator column) -- a password-reset token and a first-contact email-
 * verification link are different bearer credentials with different TTLs and different threat
 * models (account takeover vs. "prove you control this mailbox").
 *
 * **[VERIFICATION_TTL] is 24 hours, not [PasswordResetTokenStore.RESET_TTL]'s 1 hour** -- this is a
 * first-contact link a brand-new registrant may not check immediately, not an account-takeover
 * credential where a short window matters more than convenience.
 *
 * **Only a hash of the token is ever stored** -- same discipline as [PasswordResetTokenStore],
 * enforced by [SessionTokens.hash]/[SessionTokens.newRawToken]. The raw, bearer-usable token
 * exists only transiently in memory and (briefly) in the outbound verification message -- see
 * [network.lapis.cloud.server.mail.FriendVerificationMailer] KDoc for the delivery story (V1.2.3:
 * real SMTP transport whenever configured, an honest disclosed non-delivery stub otherwise).
 */
object FriendEmailVerificationTokenStore {
    /** Documented tunable -- how long a freshly issued verification token stays valid. See class KDoc for why this is longer than [PasswordResetTokenStore.RESET_TTL]. */
    val VERIFICATION_TTL: Duration = 24.hours

    /** Probability [createToken] also runs [purgeExpired] -- see [PasswordResetTokenStore] KDoc "No scheduler exists". */
    private const val PURGE_PROBABILITY = 0.01

    /** Issues and persists a brand-new, single-use verification token for [memberId] -- always a fresh [SessionTokens.newRawToken], never a client-supplied value. */
    fun createToken(memberId: Uuid): String {
        val rawToken = SessionTokens.newRawToken()
        val now = nowLocalDateTime()
        val expiresAt = (now.toInstant(TimeZone.UTC) + VERIFICATION_TTL).toLocalDateTime(TimeZone.UTC)
        transaction {
            FriendEmailVerificationTokenTable.insert {
                it[id] = Uuid.random()
                it[FriendEmailVerificationTokenTable.memberId] = memberId
                it[tokenHash] = SessionTokens.hash(rawToken)
                it[createdAt] = now
                it[FriendEmailVerificationTokenTable.expiresAt] = expiresAt
                it[consumedAt] = null
            }
        }
        if (Random.nextDouble() < PURGE_PROBABILITY) {
            runCatching { purgeExpired() }
                .onFailure { logger.warn(it) { "Opportunistic expired-friend-email-verification-token purge failed (non-fatal)" } }
        }
        return rawToken
    }

    /**
     * Read-only lookup of the [Uuid] of the member [rawToken] authorizes email verification for, or
     * `null` if [rawToken] is unknown, expired, or already consumed -- see
     * [PasswordResetTokenStore.peekMemberId] KDoc for the identical TOCTOU/optimization reasoning.
     */
    fun peekMemberId(rawToken: String): Uuid? {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        return transaction {
            FriendEmailVerificationTokenTable
                .selectAll()
                .where {
                    (FriendEmailVerificationTokenTable.tokenHash eq tokenHash) and
                        FriendEmailVerificationTokenTable.consumedAt.isNull() and
                        (FriendEmailVerificationTokenTable.expiresAt greater now)
                }.singleOrNull()
                ?.get(FriendEmailVerificationTokenTable.memberId)
        }
    }

    /**
     * Atomically claims [rawToken] (single-use, row-locked compare-and-swap on
     * `consumed_at IS NULL AND expires_at > now`) and returns the [Uuid] of the member it verifies,
     * or `null` if [rawToken] is unknown, expired, or already consumed -- see
     * [PasswordResetTokenStore.consumeToken] KDoc "Single-use, atomically". Never throws for an
     * invalid token.
     */
    fun consumeToken(rawToken: String): Uuid? {
        val tokenHash = SessionTokens.hash(rawToken)
        val now = nowLocalDateTime()
        return transaction {
            val row =
                FriendEmailVerificationTokenTable
                    .selectAll()
                    .where {
                        (FriendEmailVerificationTokenTable.tokenHash eq tokenHash) and
                            FriendEmailVerificationTokenTable.consumedAt.isNull() and
                            (FriendEmailVerificationTokenTable.expiresAt greater now)
                    }.forUpdate()
                    .singleOrNull() ?: return@transaction null
            val updated =
                FriendEmailVerificationTokenTable.update({
                    (FriendEmailVerificationTokenTable.tokenHash eq tokenHash) and
                        FriendEmailVerificationTokenTable.consumedAt.isNull()
                }) {
                    it[consumedAt] = now
                }
            if (updated == 0) return@transaction null
            row[FriendEmailVerificationTokenTable.memberId]
        }
    }

    /**
     * Consumes every still-usable (unconsumed, unexpired) verification-token row issued for
     * [memberId] without resolving any of them to a member id -- used when an ADMIN/BOARD-driven
     * address correction ([network.lapis.cloud.server.rpc.MemberService.updateMemberCoreData])
     * changes [network.lapis.cloud.server.db.generated.MemberTable.email] out from under a token
     * that was minted for the OLD address: a still-open link must not be able to verify the NEW
     * address after the fact. Same "mark consumed" idiom [consumeToken] already uses, just without
     * a token to look up by. Safe to call even when no such token exists.
     */
    fun invalidateAllForMember(memberId: Uuid): Int {
        val now = nowLocalDateTime()
        return transaction {
            FriendEmailVerificationTokenTable.update({
                (FriendEmailVerificationTokenTable.memberId eq memberId) and
                    FriendEmailVerificationTokenTable.consumedAt.isNull()
            }) {
                it[consumedAt] = now
            }
        }
    }

    /** Hard-deletes every verification-token row whose [FriendEmailVerificationTokenTable.expiresAt] is already in the past. Returns the number of rows deleted. */
    fun purgeExpired(): Int {
        val now = nowLocalDateTime()
        return transaction {
            FriendEmailVerificationTokenTable.deleteWhere { FriendEmailVerificationTokenTable.expiresAt less now }
        }
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime(TimeZone.UTC)
}
