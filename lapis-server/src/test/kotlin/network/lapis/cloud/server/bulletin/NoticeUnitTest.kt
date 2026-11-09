package network.lapis.cloud.server.bulletin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.economy.WeightDecayClock
import network.lapis.cloud.server.rpc.SocialPostWeight
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun at(
    base: LocalDateTime,
    days: Int = 0,
    hours: Int = 0,
): LocalDateTime = (base.toInstant(TimeZone.UTC) + days.days + hours.hours).toLocalDateTime(TimeZone.UTC)

private val T0 = LocalDateTime(2026, 11, 1, 10, 0, 0)

private fun boost(
    amount: String,
    boostedAt: LocalDateTime,
) = SocialPostWeight.BoostContribution(amountLtr = BigDecimal(amount), boostedAt = boostedAt)

/** V1.9.297 -- the pure parts of the bulletin board: weight, order, validation, the rate limiter. No service, no HTTP. */
class NoticeUnitTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        context("weight") {
            test("a notice without a boost weighs 0.00") {
                NoticeWeight.weightOf(boosts = emptyList(), createdAt = T0, now = at(base = T0, days = 3)) shouldBe BigDecimal("0.00")
            }

            test("one boost of 10.00 decays by 10 percent per day: 10.00, 9.00, 8.10") {
                val boosts = listOf(boost(amount = "10.00", boostedAt = T0))
                NoticeWeight.weightOf(boosts = boosts, createdAt = T0, now = T0) shouldBe BigDecimal("10.00")
                NoticeWeight.weightOf(boosts = boosts, createdAt = T0, now = at(base = T0, days = 1)) shouldBe BigDecimal("9.00")
                NoticeWeight.weightOf(boosts = boosts, createdAt = T0, now = at(base = T0, days = 2)) shouldBe BigDecimal("8.10")
            }

            test("every boost decays from its OWN time, not from the creation of the notice") {
                val boosts = listOf(boost(amount = "10.00", boostedAt = T0), boost(amount = "10.00", boostedAt = at(base = T0, days = 1)))
                // after two days: 10 * 0.81 + 10 * 0.9 = 17.10
                NoticeWeight.weightOf(boosts = boosts, createdAt = T0, now = at(base = T0, days = 2)) shouldBe BigDecimal("17.10")
            }

            test("rounding happens once, at the end, and equals the social network's own computation") {
                val boosts =
                    listOf(
                        boost(amount = "0.01", boostedAt = T0),
                        boost(amount = "0.01", boostedAt = T0),
                        boost(amount = "0.01", boostedAt = T0),
                    )
                val now = at(base = T0, days = 7)
                val expected =
                    WeightDecayClock.round2(
                        SocialPostWeight.ownWeightUnrounded(
                            initialWeightLtr = BigDecimal.ZERO,
                            publishedAt = T0,
                            boosts = boosts,
                            now = now,
                        ),
                    )
                NoticeWeight.weightOf(boosts = boosts, createdAt = T0, now = now) shouldBe expected
                // three cents at 0.9^7 = 0.478...: 0.0143 -> 0.01, while rounding each summand first would give 0.00
                expected shouldBe BigDecimal("0.01")
            }

            test("the amount rule is the social network's: two decimals at most, at least 0.01") {
                NoticeWeight.normalizeAmount(amount = BigDecimal("1.5"), field = "amountLtr") shouldBe BigDecimal("1.50")
                shouldThrow<ConflictException> { NoticeWeight.normalizeAmount(amount = BigDecimal("1.001"), field = "amountLtr") }
                shouldThrow<ConflictException> { NoticeWeight.normalizeAmount(amount = BigDecimal("0.00"), field = "amountLtr") }
                shouldThrow<ConflictException> { NoticeWeight.normalizeAmount(amount = BigDecimal("-3.00"), field = "amountLtr") }
            }
        }

        context("ranking") {
            val a = Uuid.parse("00000000-0000-0000-0000-00000000000a")
            val b = Uuid.parse("00000000-0000-0000-0000-00000000000b")
            val c = Uuid.parse("00000000-0000-0000-0000-00000000000c")
            val d = Uuid.parse("00000000-0000-0000-0000-00000000000d")
            val now = at(base = T0, days = 1)

            fun row(
                id: Uuid,
                createdDaysAfter: Int,
                pinnedAt: LocalDateTime? = null,
            ) = NoticeRanking.RankRow(id = id, createdAt = at(base = T0, days = createdDaysAfter), pinnedAt = pinnedAt)

            test("pinned first, then weight descending, newest first on a tie, then the id") {
                val rows = listOf(row(a, 0), row(b, 0), row(c, 0), row(d, 0, pinnedAt = T0))
                val boosts = mapOf(b to listOf(boost(amount = "5.00", boostedAt = T0)), c to listOf(boost(amount = "1.00", boostedAt = T0)))
                NoticeRanking.rank(rows = rows, boosts = boosts, now = now).map { it.id } shouldContainExactly listOf(d, b, c, a)
            }

            test("with an equal weight the newest notice comes first") {
                val rows = listOf(row(a, createdDaysAfter = 0), row(b, createdDaysAfter = 1))
                NoticeRanking.rank(rows = rows, boosts = emptyMap(), now = now).map { it.id } shouldContainExactly listOf(b, a)
            }

            test("with an equal weight and an equal time the id decides, so the order is total") {
                val rows = listOf(row(b, 0), row(a, 0))
                NoticeRanking.rank(rows = rows, boosts = emptyMap(), now = now).map { it.id } shouldContainExactly listOf(a, b)
            }

            test("several pinned notices: the most recently pinned on top") {
                val rows = listOf(row(a, 0, pinnedAt = T0), row(b, 0, pinnedAt = at(base = T0, hours = 5)))
                NoticeRanking.rank(rows = rows, boosts = emptyMap(), now = now).map { it.id } shouldContainExactly listOf(b, a)
            }

            test("a pinned notice stays above a heavier unpinned one") {
                val rows = listOf(row(a, 0, pinnedAt = T0), row(b, 0))
                val boosts = mapOf(b to listOf(boost(amount = "100.00", boostedAt = T0)))
                NoticeRanking.rank(rows = rows, boosts = boosts, now = now).map { it.id } shouldContainExactly listOf(a, b)
            }
        }

        context("validation") {
            test("title: 3 to 120 characters after trimming, no control characters") {
                shouldThrow<ConflictException> { NoticeValidation.title("ab") }
                NoticeValidation.title("  abc  ") shouldBe "abc"
                NoticeValidation.title("x".repeat(120)).length shouldBe 120
                shouldThrow<ConflictException> { NoticeValidation.title("x".repeat(121)) }
                shouldThrow<ConflictException> { NoticeValidation.title("a\nb c") }
            }

            test("text: 1 to 2000 characters, a line break is allowed, other control characters are not") {
                shouldThrow<ConflictException> { NoticeValidation.body("   ") }
                NoticeValidation.body("a\r\nb") shouldBe "a\nb"
                NoticeValidation.body("x".repeat(2000)).length shouldBe 2000
                shouldThrow<ConflictException> { NoticeValidation.body("x".repeat(2001)) }
                shouldThrow<ConflictException> { NoticeValidation.body("a\u0007b") }
            }

            test("removal note: blank is null, 500 characters pass, 501 are refused") {
                NoticeValidation.removalNote("  ") shouldBe null
                NoticeValidation.removalNote("x".repeat(500))?.length shouldBe 500
                shouldThrow<ConflictException> { NoticeValidation.removalNote("x".repeat(501)) }
            }

            val zone = TimeZone.of("Europe/Berlin")

            test("the stored instant is the start of the NEXT organization day, and the inverse gives the last visible day back") {
                val day = LocalDate(2026, 12, 24)
                val stored = NoticeValidation.expiresAtFor(expiresOn = day, orgZone = zone)
                stored shouldBe LocalDateTime(2026, 12, 24, 23, 0, 0) // 25 Dec 00:00 Berlin = 24 Dec 23:00 UTC
                NoticeValidation.expiresOnOf(stored).let { it shouldBe day }
            }

            test("a daylight-saving change does not move the end of the day: 26 Oct 2026 has 25 hours") {
                val stored = NoticeValidation.expiresAtFor(expiresOn = LocalDate(2026, 10, 25), orgZone = zone)
                // 26 Oct 00:00 Berlin (CET, UTC+1) = 25 Oct 23:00 UTC
                stored shouldBe LocalDateTime(2026, 10, 25, 23, 0, 0)
                val summer = NoticeValidation.expiresAtFor(expiresOn = LocalDate(2026, 10, 24), orgZone = zone)
                // 25 Oct 00:00 Berlin (CEST, UTC+2) = 24 Oct 22:00 UTC
                summer shouldBe LocalDateTime(2026, 10, 24, 22, 0, 0)
            }

            test(
                "the window is [tomorrow, day of (created + 90 days) - 1]; the day itself, the day before the window and 91 days are refused",
            ) {
                val now = LocalDateTime(2026, 11, 1, 10, 0, 0)
                val today = OrganizationTimeZone.dateOf(now)
                val min = NoticeValidation.minExpiresOn(now)
                min shouldBe today.plus(1, DateTimeUnit.DAY)
                val max = NoticeValidation.maxExpiresOn(now)
                // the stored instant of the latest day never lies after created + 90 days
                val latest = NoticeValidation.expiresAtFor(expiresOn = max)
                (latest.toInstant(TimeZone.UTC) <= now.toInstant(TimeZone.UTC) + 90.days) shouldBe true
                NoticeValidation.checkedExpiresAt(expiresOn = min, createdAt = now, now = now)
                NoticeValidation.checkedExpiresAt(expiresOn = max, createdAt = now, now = now)
                shouldThrow<ConflictException> { NoticeValidation.checkedExpiresAt(expiresOn = today, createdAt = now, now = now) }
                shouldThrow<ConflictException> {
                    NoticeValidation.checkedExpiresAt(expiresOn = max.plus(1, DateTimeUnit.DAY), createdAt = now, now = now)
                }
                shouldThrow<ConflictException> {
                    NoticeValidation.checkedExpiresAt(expiresOn = today.plus(91, DateTimeUnit.DAY), createdAt = now, now = now)
                }
            }

            test("an edit may keep the stored day even when it lies outside the window of today; the cap counts from the creation") {
                val created = LocalDateTime(2026, 8, 1, 10, 0, 0)
                val now = LocalDateTime(2026, 10, 25, 10, 0, 0)
                val storedDay = NoticeValidation.maxExpiresOn(created)
                val stored = NoticeValidation.expiresAtFor(expiresOn = storedDay)
                NoticeValidation.checkedExpiresAt(expiresOn = storedDay, createdAt = created, now = now, currentExpiresAt = stored)
                // an extension beyond created + 90 days is refused, however far the window of today reaches
                shouldThrow<ConflictException> {
                    NoticeValidation.checkedExpiresAt(
                        expiresOn = storedDay.plus(5, DateTimeUnit.DAY),
                        createdAt = created,
                        now = now,
                        currentExpiresAt = stored,
                    )
                }
            }

            test("the default is 30 days ahead") {
                val now = LocalDateTime(2026, 11, 1, 10, 0, 0)
                NoticeValidation.defaultExpiresOn(now) shouldBe OrganizationTimeZone.dateOf(now).plus(30, DateTimeUnit.DAY)
            }
        }

        context("rate limiter") {
            test("the limit of an action is reached after its budget; other actions and other members are unaffected") {
                var clock = Instant.fromEpochSeconds(1_800_000_000)
                val limiter = NoticeBoardRateLimiter(now = { clock })
                val me = Uuid.random()
                val other = Uuid.random()
                repeat(NoticeBoardAction.WRITE.limit) { limiter.tryAcquire(memberId = me, action = NoticeBoardAction.WRITE) shouldBe true }
                limiter.tryAcquire(memberId = me, action = NoticeBoardAction.WRITE) shouldBe false
                limiter.tryAcquire(memberId = me, action = NoticeBoardAction.BOOST) shouldBe true
                limiter.tryAcquire(memberId = other, action = NoticeBoardAction.WRITE) shouldBe true
                shouldThrow<ConflictException> { limiter.checkAndRecord(memberId = me, action = NoticeBoardAction.WRITE) }
                clock += 61.minutes
                limiter.tryAcquire(memberId = me, action = NoticeBoardAction.WRITE) shouldBe true
            }

            test("the budgets are the documented ones") {
                NoticeBoardAction.WRITE.limit shouldBe 10
                NoticeBoardAction.BOOST.limit shouldBe 20
                NoticeBoardAction.REPLY.limit shouldBe 20
                NoticeBoardAction.READ.limit shouldBe 120
                NoticeBoardAction.MODERATE.limit shouldBe 30
            }
        }
    })
