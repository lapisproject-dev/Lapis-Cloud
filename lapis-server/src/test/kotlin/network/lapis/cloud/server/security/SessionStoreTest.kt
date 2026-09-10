package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")
private val TREASURER_ID = Uuid.parse("00000000-0000-0000-0000-000000000003")

/**
 * Exercises [SessionStore] end to end against a real (H2) DB -- creation, resolution, revocation
 * (single + all-for-member), expiry, and the "only a hash is stored" property.
 */
class SessionStoreTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test("createSession() then resolve() round-trips to the correct member and role") {
            val issued = SessionStore.createSession(ADMIN_ID)
            val resolved = SessionStore.resolve(issued.rawToken)
            resolved.shouldNotBeNull()
            resolved.memberId shouldBe ADMIN_ID
            resolved.role shouldBe AccountRole.ADMIN
        }

        test("createSession() issues a fresh, distinct token every call, even for the same member") {
            val first = SessionStore.createSession(ADMIN_ID)
            val second = SessionStore.createSession(ADMIN_ID)
            first.rawToken shouldNotBe second.rawToken
        }

        test("only a hash of the raw token is ever persisted -- the raw token itself never appears in SessionTable") {
            val issued = SessionStore.createSession(ADMIN_ID)
            val storedHash =
                transaction {
                    SessionTable
                        .selectAll()
                        .where { SessionTable.tokenHash eq SessionTokens.hash(issued.rawToken) }
                        .single()[SessionTable.tokenHash]
                }
            storedHash shouldBe SessionTokens.hash(issued.rawToken)
            storedHash shouldNotBe issued.rawToken
        }

        test("resolve() of an unknown token returns null") {
            SessionStore.resolve("this-token-was-never-issued").shouldBeNull()
        }

        test("resolve() of a revoked token returns null") {
            val issued = SessionStore.createSession(ADMIN_ID)
            SessionStore.revoke(issued.rawToken)
            SessionStore.resolve(issued.rawToken).shouldBeNull()
        }

        test("revoke() is idempotent -- revoking an already-revoked or unknown token never throws") {
            val issued = SessionStore.createSession(ADMIN_ID)
            SessionStore.revoke(issued.rawToken)
            SessionStore.revoke(issued.rawToken)
            SessionStore.revoke("never-issued-either")
        }

        test("revokeAllForMember() revokes every live session for that member") {
            val first = SessionStore.createSession(BOARD_ID)
            val second = SessionStore.createSession(BOARD_ID)

            SessionStore.revokeAllForMember(memberId = BOARD_ID)

            SessionStore.resolve(first.rawToken).shouldBeNull()
            SessionStore.resolve(second.rawToken).shouldBeNull()
        }

        test("revokeAllForMember() with exceptRawToken keeps that one session alive, revokes every other") {
            val kept = SessionStore.createSession(BOARD_ID)
            val revoked = SessionStore.createSession(BOARD_ID)

            SessionStore.revokeAllForMember(memberId = BOARD_ID, exceptRawToken = kept.rawToken)

            SessionStore.resolve(kept.rawToken).shouldNotBeNull()
            SessionStore.resolve(revoked.rawToken).shouldBeNull()
        }

        test("revokeAllForMember() never touches a different member's session") {
            val adminSession = SessionStore.createSession(ADMIN_ID)
            val boardSession = SessionStore.createSession(BOARD_ID)

            SessionStore.revokeAllForMember(memberId = BOARD_ID)

            SessionStore.resolve(adminSession.rawToken).shouldNotBeNull()
            SessionStore.resolve(boardSession.rawToken).shouldBeNull()
        }

        test("expiresAtOf() reflects the real expiry for a live session, null for revoked/unknown") {
            val issued = SessionStore.createSession(ADMIN_ID)
            SessionStore.expiresAtOf(issued.rawToken) shouldBe issued.expiresAt

            SessionStore.revoke(issued.rawToken)
            SessionStore.expiresAtOf(issued.rawToken).shouldBeNull()
            SessionStore.expiresAtOf("never-issued").shouldBeNull()
        }

        test("purgeExpired() hard-deletes only rows whose expiresAt is already in the past") {
            val nowPlusHour = (Clock.System.now() + 1.hours).toLocalDateTime(TimeZone.UTC)
            val nowMinusHour = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            val liveRawToken = SessionTokens.newRawToken()
            val expiredRawToken = SessionTokens.newRawToken()

            transaction {
                SessionTable.insert {
                    it[id] = Uuid.random()
                    it[tokenHash] = SessionTokens.hash(liveRawToken)
                    it[memberId] = ADMIN_ID
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowPlusHour
                    it[lastUsedAt] = null
                    it[revokedAt] = null
                }
                SessionTable.insert {
                    it[id] = Uuid.random()
                    it[tokenHash] = SessionTokens.hash(expiredRawToken)
                    it[memberId] = ADMIN_ID
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowMinusHour
                    it[lastUsedAt] = null
                    it[revokedAt] = null
                }
            }

            SessionStore.purgeExpired()

            SessionStore.resolve(liveRawToken).shouldNotBeNull()
            transaction {
                SessionTable
                    .selectAll()
                    .where { SessionTable.tokenHash eq SessionTokens.hash(expiredRawToken) }
                    .toList()
            }.size shouldBe 0
        }

        test("placeholderExpiry() is roughly now + SESSION_TTL") {
            val placeholder = SessionStore.placeholderExpiry()
            val expectedFloor: LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            (placeholder >= expectedFloor) shouldBe true
        }

        // ── Welle V1.4.9 "Admin-Passwort-Reset" ──

        test("revokeAllForMember() returns the real number of sessions it actually revoked") {
            // Clean slate first -- robust against leftover sessions from other test files sharing
            // this DB; the count discarded here is irrelevant, only the delta below matters.
            SessionStore.revokeAllForMember(memberId = TREASURER_ID)
            SessionStore.createSession(TREASURER_ID)
            SessionStore.createSession(TREASURER_ID)

            SessionStore.revokeAllForMember(memberId = TREASURER_ID) shouldBe 2
            // Idempotent: a second call against the now-fully-revoked member revokes nothing further.
            SessionStore.revokeAllForMember(memberId = TREASURER_ID) shouldBe 0
        }

        test("revokeAllForMember() with exceptRawToken -- the returned count excludes the kept session") {
            SessionStore.revokeAllForMember(memberId = TREASURER_ID)
            val kept = SessionStore.createSession(TREASURER_ID)
            SessionStore.createSession(TREASURER_ID)
            SessionStore.createSession(TREASURER_ID)

            SessionStore.revokeAllForMember(memberId = TREASURER_ID, exceptRawToken = kept.rawToken) shouldBe 2
            SessionStore.resolve(kept.rawToken).shouldNotBeNull()
        }

        test("countActiveForMember() counts only live sessions -- not revoked, not expired") {
            SessionStore.revokeAllForMember(memberId = TREASURER_ID)
            SessionStore.countActiveForMember(TREASURER_ID) shouldBe 0

            val a = SessionStore.createSession(TREASURER_ID)
            SessionStore.createSession(TREASURER_ID)
            SessionStore.countActiveForMember(TREASURER_ID) shouldBe 2

            SessionStore.revoke(a.rawToken)
            SessionStore.countActiveForMember(TREASURER_ID) shouldBe 1

            // An EXPIRED-but-not-revoked row must not count either -- distinct from the plain
            // `revokedAt IS NULL` tally `MemberAdministrationTest.activeSessionCount` uses for its
            // own assertions.
            val expiredRawToken = SessionTokens.newRawToken()
            val nowMinusHour = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            transaction {
                SessionTable.insert {
                    it[id] = Uuid.random()
                    it[tokenHash] = SessionTokens.hash(expiredRawToken)
                    it[memberId] = TREASURER_ID
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowMinusHour
                    it[lastUsedAt] = null
                    it[revokedAt] = null
                }
            }
            SessionStore.countActiveForMember(TREASURER_ID) shouldBe 1
        }

        test(
            "revokeAllForMember() ignores already-expired-but-unpurged rows, matching countActiveForMember()'s " +
                "own definition of \"live\"",
        ) {
            // Review-round fix regression test: an expired-but-not-yet-purgeExpired()d row used to
            // be included in revokeAllForMember()'s raw update count (`revokedAt IS NULL` only)
            // while countActiveForMember() (`revokedAt IS NULL AND expiresAt > now`) already
            // excluded it -- the admin-password-reset dialog could show "no active session" in the
            // preflight and then "1 session(s) ended" in the very same receipt.
            SessionStore.revokeAllForMember(memberId = TREASURER_ID)
            val expiredRawToken = SessionTokens.newRawToken()
            val nowMinusHour = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            transaction {
                SessionTable.insert {
                    it[id] = Uuid.random()
                    it[tokenHash] = SessionTokens.hash(expiredRawToken)
                    it[memberId] = TREASURER_ID
                    it[createdAt] = nowMinusHour
                    it[expiresAt] = nowMinusHour
                    it[lastUsedAt] = null
                    it[revokedAt] = null
                }
            }

            SessionStore.countActiveForMember(TREASURER_ID) shouldBe 0
            SessionStore.revokeAllForMember(memberId = TREASURER_ID) shouldBe 0
        }
    })
