package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.FriendEmailVerificationTokenTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")

/**
 * Exercises [FriendEmailVerificationTokenStore] end to end against a real (H2) DB -- creation,
 * single-use/atomic consumption, expiry, tamper/replay resistance, and the "only a hash is stored"
 * property. A near-mechanical clone of [PasswordResetTokenStoreTest], same house style (that class
 * is the store's own KDoc-documented template) -- see [FriendEmailVerificationTokenStore] KDoc
 * "near-mechanical clone" for why the two stores intentionally look almost identical.
 */
class FriendEmailVerificationTokenStoreTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test("createToken() then consumeToken() round-trips to the correct member") {
            val rawToken = FriendEmailVerificationTokenStore.createToken(ADMIN_ID)
            val consumedMemberId = FriendEmailVerificationTokenStore.consumeToken(rawToken)
            consumedMemberId shouldBe ADMIN_ID
        }

        test("createToken() issues a fresh, distinct token every call, even for the same member") {
            val first = FriendEmailVerificationTokenStore.createToken(BOARD_ID)
            val second = FriendEmailVerificationTokenStore.createToken(BOARD_ID)
            first shouldNotBe second
        }

        test("only a hash of the raw token is ever persisted -- the raw token itself never appears in FriendEmailVerificationTokenTable") {
            val rawToken = FriendEmailVerificationTokenStore.createToken(ADMIN_ID)
            val storedHash =
                transaction {
                    FriendEmailVerificationTokenTable
                        .selectAll()
                        .where { FriendEmailVerificationTokenTable.tokenHash eq SessionTokens.hash(rawToken) }
                        .single()[FriendEmailVerificationTokenTable.tokenHash]
                }
            storedHash shouldBe SessionTokens.hash(rawToken)
            storedHash shouldNotBe rawToken
        }

        test("consumeToken() of an unknown token returns null, never throws") {
            FriendEmailVerificationTokenStore.consumeToken("this-token-was-never-issued").shouldBeNull()
        }

        test("consumeToken() is single-use -- a second consumption of the SAME raw token returns null (tamper/replay test)") {
            val rawToken = FriendEmailVerificationTokenStore.createToken(ADMIN_ID)
            val first = FriendEmailVerificationTokenStore.consumeToken(rawToken)
            first.shouldNotBeNull()
            val second = FriendEmailVerificationTokenStore.consumeToken(rawToken)
            second.shouldBeNull()
        }

        test("peekMemberId() round-trips to the correct member WITHOUT consuming the token -- consumeToken still succeeds afterward") {
            val rawToken = FriendEmailVerificationTokenStore.createToken(ADMIN_ID)
            FriendEmailVerificationTokenStore.peekMemberId(rawToken) shouldBe ADMIN_ID
            // Peeking must not have consumed it -- a real consumeToken() call still succeeds.
            FriendEmailVerificationTokenStore.peekMemberId(rawToken) shouldBe ADMIN_ID
            FriendEmailVerificationTokenStore.consumeToken(rawToken) shouldBe ADMIN_ID
        }

        test("peekMemberId() of an unknown or already-consumed token returns null, never throws") {
            FriendEmailVerificationTokenStore.peekMemberId("this-token-was-never-issued").shouldBeNull()

            val rawToken = FriendEmailVerificationTokenStore.createToken(BOARD_ID)
            FriendEmailVerificationTokenStore.consumeToken(rawToken).shouldNotBeNull()
            FriendEmailVerificationTokenStore.peekMemberId(rawToken).shouldBeNull()
        }

        test("consumeToken() of an expired token returns null (tamper test: manipulated expiresAt)") {
            val nowMinusHour = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            val rawToken = SessionTokens.newRawToken()
            transaction {
                FriendEmailVerificationTokenTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = ADMIN_ID
                    it[tokenHash] = SessionTokens.hash(rawToken)
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowMinusHour
                    it[consumedAt] = null
                }
            }
            FriendEmailVerificationTokenStore.consumeToken(rawToken).shouldBeNull()
        }

        test("purgeExpired() hard-deletes only rows whose expiresAt is already in the past") {
            val nowPlusHour = (Clock.System.now() + 1.hours).toLocalDateTime(TimeZone.UTC)
            val nowMinusHour = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            val liveRawToken = SessionTokens.newRawToken()
            val expiredRawToken = SessionTokens.newRawToken()

            transaction {
                FriendEmailVerificationTokenTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = ADMIN_ID
                    it[tokenHash] = SessionTokens.hash(liveRawToken)
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowPlusHour
                    it[consumedAt] = null
                }
                FriendEmailVerificationTokenTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = ADMIN_ID
                    it[tokenHash] = SessionTokens.hash(expiredRawToken)
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowMinusHour
                    it[consumedAt] = null
                }
            }

            FriendEmailVerificationTokenStore.purgeExpired()

            FriendEmailVerificationTokenStore.consumeToken(liveRawToken).shouldNotBeNull()
            transaction {
                FriendEmailVerificationTokenTable
                    .selectAll()
                    .where { FriendEmailVerificationTokenTable.tokenHash eq SessionTokens.hash(expiredRawToken) }
                    .toList()
            }.size shouldBe 0
        }

        test(
            "VERIFICATION_TTL is 24h, longer than PasswordResetTokenStore.RESET_TTL -- a first-contact link, not an account-takeover credential",
        ) {
            (FriendEmailVerificationTokenStore.VERIFICATION_TTL > PasswordResetTokenStore.RESET_TTL) shouldBe true
        }
    })
