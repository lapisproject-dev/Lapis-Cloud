package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.articles.ArticleStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ArticleTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ArticleStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation". Covers [PublicNavAvailabilityProvider]'s in-process
 * cache (T3a, no DB) and [loadPublicNavAvailability]'s two DB-backed filters (T3b/T4) -- see
 * `PublicNavAvailability.kt` class KDoc "MUSS identisch ... bleiben" for why these filters must never
 * drift from [ArticleStore.listPublishedNewestFirst]/`EventIcsFeed.loadUpcomingPublicPublished`.
 *
 * **Stolperfalle S3 (process-wide shared H2 DB)**: earlier specs in the same test run can leave
 * `PUBLISHED` articles or public, upcoming, `PUBLISHED` events behind. `beforeTest` NEUTRALIZES (never
 * deletes -- other specs' rows may carry FK-dependent children) every such row before each test in
 * THIS file, so the negative assertions below are deterministic. Kotest specs run sequentially by
 * default, so this is safe.
 */
class PublicNavAvailabilityTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdArticleIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                ArticleTable.update({ ArticleTable.status eq ArticleStatus.PUBLISHED }) { it[status] = ArticleStatus.DRAFT }
                EventTable.update({
                    (EventTable.visibility eq EventVisibility.PUBLIC) and (EventTable.status eq EventStatus.PUBLISHED)
                }) { it[status] = EventStatus.CANCELLED }
            }
        }

        afterSpec {
            transaction {
                if (createdArticleIds.isNotEmpty()) ArticleTable.deleteWhere { id inList createdArticleIds }
                if (createdEventIds.isNotEmpty()) EventTable.deleteWhere { id inList createdEventIds }
                if (createdMemberIds.isNotEmpty()) MemberTable.deleteWhere { id inList createdMemberIds }
            }
        }

        fun createMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "PublicNavAvailabilityTest Mitglied"
                    it[email] = "public-nav-availability-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun createArticle(status: ArticleStatus): Uuid {
            val author = createMember()
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ArticleTable.insert {
                    it[ArticleTable.id] = id
                    it[slug] = if (status == ArticleStatus.PUBLISHED) "public-nav-availability-test-$id" else null
                    it[title] = "PublicNavAvailabilityTest Artikel"
                    it[excerpt] = "Auszug"
                    it[body] = "Inhalt"
                    it[coverImageId] = null
                    it[authorId] = author
                    it[ArticleTable.status] = status
                    it[submittedAt] = now
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[rejectionReason] = null
                    it[publishedAt] = if (status == ArticleStatus.PUBLISHED) now else null
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            createdArticleIds += id
            return id
        }

        fun createEvent(
            startsAt: LocalDateTime,
            endsAt: LocalDateTime,
            status: EventStatus = EventStatus.PUBLISHED,
            visibility: EventVisibility = EventVisibility.PUBLIC,
        ): Uuid {
            val organizer = createMember()
            val id = Uuid.random()
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[slug] = "public-nav-availability-test-$id"
                    it[title] = "PublicNavAvailabilityTest Event"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[EventTable.startsAt] = startsAt
                    it[EventTable.endsAt] = endsAt
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[EventTable.visibility] = visibility
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id
        }

        val now = LocalDateTime(2030, 1, 1, 12, 0)

        // ── T3b: events ──────────────────────────────────────────────────────────
        test("T3b baseline: no published articles/events after neutralization -> both false") {
            loadPublicNavAvailability(wallNow = now) shouldBe PublicNavAvailability.NONE
        }

        test("T3b: PUBLIC + PUBLISHED + endsAt in the future -> events == true") {
            createEvent(startsAt = now.plusHours(-1), endsAt = now.plusHours(1))
            loadPublicNavAvailability(wallNow = now).events shouldBe true
        }

        test("T3b: an event exactly at the endsAt == now boundary is EXCLUDED (real '>' boundary)") {
            createEvent(startsAt = now.plusHours(-2), endsAt = now)
            loadPublicNavAvailability(wallNow = now).events shouldBe false
        }

        test("T3b: an already-ended event is excluded") {
            createEvent(startsAt = now.plusHours(-3), endsAt = now.plusHours(-1))
            loadPublicNavAvailability(wallNow = now).events shouldBe false
        }

        test("T3b: a DRAFT event is excluded") {
            createEvent(startsAt = now.plusHours(-1), endsAt = now.plusHours(1), status = EventStatus.DRAFT)
            loadPublicNavAvailability(wallNow = now).events shouldBe false
        }

        test("T3b: a CANCELLED event is excluded") {
            createEvent(startsAt = now.plusHours(-1), endsAt = now.plusHours(1), status = EventStatus.CANCELLED)
            loadPublicNavAvailability(wallNow = now).events shouldBe false
        }

        test("T3b: a MEMBERS_ONLY event is excluded, even if PUBLISHED and upcoming") {
            createEvent(startsAt = now.plusHours(-1), endsAt = now.plusHours(1), visibility = EventVisibility.MEMBERS_ONLY)
            loadPublicNavAvailability(wallNow = now).events shouldBe false
        }

        // ── T4: articles ─────────────────────────────────────────────────────────
        test("T4: a DRAFT article is excluded") {
            createArticle(status = ArticleStatus.DRAFT)
            loadPublicNavAvailability(wallNow = now).articles shouldBe false
        }

        test("T4: a SUBMITTED article is excluded") {
            createArticle(status = ArticleStatus.SUBMITTED)
            loadPublicNavAvailability(wallNow = now).articles shouldBe false
        }

        test("T4: a REJECTED article is excluded") {
            createArticle(status = ArticleStatus.REJECTED)
            loadPublicNavAvailability(wallNow = now).articles shouldBe false
        }

        test("T4: a PUBLISHED article -> articles == true") {
            createArticle(status = ArticleStatus.PUBLISHED)
            loadPublicNavAvailability(wallNow = now).articles shouldBe true
        }

        // ── T3c: consistency with the overview routes' own loaders ────────────────
        test("T3c: articles flag agrees with ArticleStore.listPublishedNewestFirst(1)") {
            createArticle(status = ArticleStatus.PUBLISHED)
            val nav = loadPublicNavAvailability(wallNow = now)
            transaction {
                nav.articles shouldBe ArticleStore.listPublishedNewestFirst(limit = 1).isNotEmpty()
            }
        }

        test("T3c: events flag agrees with EventIcsFeed.loadUpcomingPublicPublished(now, 1)") {
            createEvent(startsAt = now.plusHours(-1), endsAt = now.plusHours(1))
            val nav = loadPublicNavAvailability(wallNow = now)
            transaction {
                nav.events shouldBe EventIcsFeed.loadUpcomingPublicPublished(wallNow = now, limit = 1).isNotEmpty()
            }
        }

        // ── T3a: provider cache (no DB -- fake clock, counting loader) ────────────
        test("T3a: two calls within the TTL invoke the loader exactly once") {
            val callCount = AtomicInteger(0)
            var virtualNow = Instant.fromEpochSeconds(1_000)
            val provider =
                PublicNavAvailabilityProvider(
                    ttl = 30.seconds,
                    clock =
                        object : Clock {
                            override fun now(): Instant = virtualNow
                        },
                    loader = {
                        callCount.incrementAndGet()
                        PublicNavAvailability(articles = true, events = false)
                    },
                )
            provider.current()
            provider.current()
            callCount.get() shouldBe 1
        }

        test("T3a: after the TTL elapses, the loader is invoked again") {
            val callCount = AtomicInteger(0)
            var virtualNow = Instant.fromEpochSeconds(1_000)
            val provider =
                PublicNavAvailabilityProvider(
                    ttl = 30.seconds,
                    clock =
                        object : Clock {
                            override fun now(): Instant = virtualNow
                        },
                    loader = {
                        callCount.incrementAndGet()
                        PublicNavAvailability(articles = true, events = false)
                    },
                )
            provider.current()
            virtualNow = virtualNow + 31.seconds
            provider.current()
            callCount.get() shouldBe 2
        }

        test("T3a: a failed lookup falls back to the previous snapshot (or NONE) and is NOT cached -- the next call retries") {
            val callCount = AtomicInteger(0)
            val virtualNow = Instant.fromEpochSeconds(1_000)
            val provider =
                PublicNavAvailabilityProvider(
                    ttl = 30.seconds,
                    clock =
                        object : Clock {
                            override fun now(): Instant = virtualNow
                        },
                    loader = {
                        callCount.incrementAndGet()
                        error("simulated DB hiccup")
                    },
                )
            provider.current() shouldBe PublicNavAvailability.NONE
            provider.current() shouldBe PublicNavAvailability.NONE
            // Every call retried the loader -- a failure is never cached.
            callCount.get() shouldBe 2
        }
    })

/**
 * Test-local convenience -- deliberately NOT day-boundary-safe (every fixture in this file stays
 * within [-3, +1] hours of a fixed 12:00 [now], so no day rolls over).
 */
private fun LocalDateTime.plusHours(hours: Int): LocalDateTime = LocalDateTime(year, monthNumber, dayOfMonth, hour + hours, minute)
