package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ApiKeyTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")

/** Exercises [ApiKeyStore] end to end against a real (H2) DB -- issue/resolve/revoke/list, the "only a hash is stored" property, and the [ApiKeyStore.touchLastUsed] throttle. */
class ApiKeyStoreTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test("issue() mints a key with the lapis_ prefix, and resolve() round-trips it to a Valid principal") {
            val issued = ApiKeyStore.issue(label = "Test Key", createdByMemberId = ADMIN_ID)
            issued.rawKey.shouldStartWith()

            val resolution = ApiKeyStore.resolve(issued.rawKey)
            resolution.shouldBeInstanceOf<ApiKeyStore.Resolution.Valid>()
            (resolution as ApiKeyStore.Resolution.Valid).principal.apiKeyId shouldBe issued.id
            resolution.principal.label shouldBe "Test Key"
            resolution.principal.keyPrefix shouldBe issued.keyPrefix
        }

        test("issue() issues a fresh, distinct raw key every call, even for the same member") {
            val first = ApiKeyStore.issue(label = "A", createdByMemberId = ADMIN_ID)
            val second = ApiKeyStore.issue(label = "B", createdByMemberId = ADMIN_ID)
            first.rawKey shouldNotBe second.rawKey
        }

        test("only a hash of the raw key is ever persisted -- the raw key itself never appears in ApiKeyTable") {
            val issued = ApiKeyStore.issue(label = "Hash Test", createdByMemberId = ADMIN_ID)
            val storedHash =
                transaction {
                    ApiKeyTable.selectAll().where { ApiKeyTable.id eq issued.id }.single()[ApiKeyTable.tokenHash]
                }
            storedHash shouldBe SessionTokens.hash(issued.rawKey)
            storedHash shouldNotBe issued.rawKey
        }

        test("resolve() of an unknown raw key returns Unknown") {
            ApiKeyStore.resolve("lapis_this-key-was-never-issued").shouldBeInstanceOf<ApiKeyStore.Resolution.Unknown>()
        }

        test("resolve() of a revoked key returns Revoked with the correct keyPrefix") {
            val issued = ApiKeyStore.issue(label = "Revoke Test", createdByMemberId = ADMIN_ID)
            ApiKeyStore.revoke(id = issued.id, revokedByMemberId = ADMIN_ID)
            val resolution = ApiKeyStore.resolve(issued.rawKey)
            resolution.shouldBeInstanceOf<ApiKeyStore.Resolution.Revoked>()
            (resolution as ApiKeyStore.Resolution.Revoked).keyPrefix shouldBe issued.keyPrefix
        }

        test("resolve() of an expired key returns Expired with the correct keyPrefix") {
            val issued = ApiKeyStore.issue(label = "Expiry Test", createdByMemberId = ADMIN_ID)
            val past = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            transaction { ApiKeyTable.update({ ApiKeyTable.id eq issued.id }) { it[expiresAt] = past } }
            val resolution = ApiKeyStore.resolve(issued.rawKey)
            resolution.shouldBeInstanceOf<ApiKeyStore.Resolution.Expired>()
            (resolution as ApiKeyStore.Resolution.Expired).keyPrefix shouldBe issued.keyPrefix
        }

        test("revoke() is idempotent -- revoking an already-revoked or unknown id returns null, never throws") {
            val issued = ApiKeyStore.issue(label = "Idempotent", createdByMemberId = ADMIN_ID)
            ApiKeyStore.revoke(id = issued.id, revokedByMemberId = ADMIN_ID).shouldNotBeNull()
            ApiKeyStore.revoke(id = issued.id, revokedByMemberId = ADMIN_ID).shouldBeNull()
            ApiKeyStore.revoke(id = Uuid.random(), revokedByMemberId = ADMIN_ID).shouldBeNull()
        }

        test("list(includeRevoked = false) hides revoked keys, list(includeRevoked = true) shows them") {
            val live = ApiKeyStore.issue(label = "Live", createdByMemberId = BOARD_ID)
            val revoked = ApiKeyStore.issue(label = "Revoked", createdByMemberId = BOARD_ID)
            ApiKeyStore.revoke(id = revoked.id, revokedByMemberId = BOARD_ID)

            val activeOnly = ApiKeyStore.list(includeRevoked = false).map { it.id }
            activeOnly shouldContainId live.id
            activeOnly.shouldNotContainId(revoked.id)

            val all = ApiKeyStore.list(includeRevoked = true).map { it.id }
            all shouldContainId live.id
            all shouldContainId revoked.id
        }

        test("list() returns newest-first, per its documented contract -- IApiKeyService.listApiKeys KDoc") {
            val older = ApiKeyStore.issue(label = "Older", createdByMemberId = ADMIN_ID)
            val newer = ApiKeyStore.issue(label = "Newer", createdByMemberId = ADMIN_ID)
            // Back-date `older` well before `newer` so the assertion does not depend on the wall
            // clock's resolution between two issue() calls made microseconds apart in a test.
            val earlierTimestamp = (Clock.System.now() - 1.hours).toLocalDateTime(TimeZone.UTC)
            transaction { ApiKeyTable.update({ ApiKeyTable.id eq older.id }) { it[createdAt] = earlierTimestamp } }

            val ids = ApiKeyStore.list(includeRevoked = true).map { it.id }
            val newerIndex = ids.indexOf(newer.id)
            val olderIndex = ids.indexOf(older.id)
            (newerIndex >= 0 && olderIndex >= 0) shouldBe true
            (newerIndex < olderIndex) shouldBe true
        }

        test(
            "revoke() under two near-simultaneous calls for the SAME id: exactly one returns non-null " +
                "(the atomic conditional UPDATE closes the select-then-update TOCTOU race)",
        ) {
            val issued = ApiKeyStore.issue(label = "RaceTest", createdByMemberId = ADMIN_ID)

            val startLatch = CountDownLatch(2)
            val doneLatch = CountDownLatch(2)
            val results = java.util.Collections.synchronizedList(mutableListOf<ApiKeyStore.ApiKeyRow?>())
            val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

            fun revokeThread(revokedByMemberId: Uuid): Thread =
                Thread {
                    try {
                        startLatch.countDown()
                        startLatch.await(20, TimeUnit.SECONDS)
                        results += ApiKeyStore.revoke(id = issued.id, revokedByMemberId = revokedByMemberId)
                    } catch (t: Throwable) {
                        failures += t
                    } finally {
                        doneLatch.countDown()
                    }
                }

            val threadA = revokeThread(ADMIN_ID)
            val threadB = revokeThread(BOARD_ID)
            threadA.start()
            threadB.start()

            val completed = doneLatch.await(20, TimeUnit.SECONDS)
            check(completed) { "Concurrent revoke() calls did not complete within 20s" }
            if (failures.isNotEmpty()) throw failures.first()

            results.count { it != null } shouldBe 1
            results.count { it == null } shouldBe 1
        }

        test("touchLastUsed() writes lastUsedAt on the first call, and a second call within 5 minutes does not overwrite it again") {
            val issued = ApiKeyStore.issue(label = "Touch", createdByMemberId = ADMIN_ID)
            ApiKeyStore.getOrNull(issued.id)?.lastUsedAt.shouldBeNull()

            ApiKeyStore.touchLastUsed(issued.id)
            val firstTouch = ApiKeyStore.getOrNull(issued.id)?.lastUsedAt
            firstTouch.shouldNotBeNull()

            ApiKeyStore.touchLastUsed(issued.id)
            val secondTouch = ApiKeyStore.getOrNull(issued.id)?.lastUsedAt
            secondTouch shouldBe firstTouch
        }

        test("touchLastUsed() writes again once the previous value is older than the 5-minute throttle") {
            val issued = ApiKeyStore.issue(label = "Touch Stale", createdByMemberId = ADMIN_ID)
            val staleTimestamp = (Clock.System.now() - 10.hours).toLocalDateTime(TimeZone.UTC)
            transaction { ApiKeyTable.update({ ApiKeyTable.id eq issued.id }) { it[lastUsedAt] = staleTimestamp } }

            ApiKeyStore.touchLastUsed(issued.id)
            val touched = ApiKeyStore.getOrNull(issued.id)?.lastUsedAt
            touched.shouldNotBeNull()
            (touched!! > staleTimestamp) shouldBe true
        }
    })

private fun String.shouldStartWith() {
    (startsWith(ApiKeyStore.API_KEY_TOKEN_PREFIX)) shouldBe true
}

private infix fun List<Uuid>.shouldContainId(id: Uuid) {
    (id in this) shouldBe true
}

private fun List<Uuid>.shouldNotContainId(id: Uuid) {
    (id in this) shouldBe false
}
