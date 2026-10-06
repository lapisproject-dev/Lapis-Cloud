package network.lapis.cloud.server.member

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.shared.domain.MemberCountGranularity
import network.lapis.cloud.shared.domain.MemberStatisticsRules
import network.lapis.cloud.shared.domain.MemberStatus

/** Welle V1.9.59 -- the pure arithmetic behind the member-count history: matrices computed by hand, zone and calendar edges. */
class MemberCountAggregationTest :
    FunSpec({
        val berlin = TimeZone.of("Europe/Berlin")
        val newYork = TimeZone.of("America/New_York")

        fun at(iso: String): LocalDateTime = LocalDateTime.parse(iso.removeSuffix("Z"))

        /** One member's chain of (instant, status) rows -> deltas, grouped like the database groups them. */
        fun deltasOf(vararg members: List<Pair<String, MemberStatus>>): List<StatusDelta> =
            members
                .flatMap { chain ->
                    chain.mapIndexed { i, (iso, status) -> Triple(at(iso), status, chain.getOrNull(i - 1)?.second) }
                }.groupBy { Triple(it.first, it.second, it.third) }
                .map { (key, rows) ->
                    StatusDelta(status = key.second, previous = key.third, effectiveFrom = key.first, count = rows.size.toLong())
                }

        fun counts(vararg pairs: Pair<MemberStatus, Int>): Map<MemberStatus, Int> = MemberStatus.entries.associateWith { 0 } + pairs.toMap()

        fun monthly(
            from: String,
            to: String,
            zone: TimeZone,
            now: String,
        ) = MemberCountAggregation.periods(
            from = LocalDate.parse(from),
            to = LocalDate.parse(to),
            granularity = MemberCountGranularity.MONTH,
            orgZone = zone,
            now = at(now),
        )

        test("a hand-computed matrix over five months, organization zone Europe/Berlin, the current month counted up to now") {
            val deltas =
                deltasOf(
                    listOf(
                        "2025-11-10T10:00:00Z" to MemberStatus.APPLICATION,
                        "2025-12-05T09:00:00Z" to MemberStatus.ACTIVE,
                        "2026-02-20T12:00:00Z" to MemberStatus.WITHDRAWN,
                    ),
                    listOf("2025-12-20T10:00:00Z" to MemberStatus.ACTIVE, "2026-03-15T08:00:00Z" to MemberStatus.DONOR),
                    listOf("2026-01-10T10:00:00Z" to MemberStatus.FRIEND),
                )
            val periods = monthly("2025-11-01", "2026-03-20", berlin, "2026-03-20T12:00:00Z")
            periods.map { it.start.toString() } shouldBe listOf("2025-11-01", "2025-12-01", "2026-01-01", "2026-02-01", "2026-03-01")
            periods.map { it.current } shouldBe listOf(false, false, false, false, true)
            val result = MemberCountAggregation.countsAt(deltas = deltas, boundaries = periods.map { it.boundary })
            result shouldBe
                listOf(
                    counts(MemberStatus.APPLICATION to 1),
                    counts(MemberStatus.ACTIVE to 2),
                    counts(MemberStatus.ACTIVE to 2, MemberStatus.FRIEND to 1),
                    counts(MemberStatus.ACTIVE to 1, MemberStatus.WITHDRAWN to 1, MemberStatus.FRIEND to 1),
                    counts(MemberStatus.DONOR to 1, MemberStatus.WITHDRAWN to 1, MemberStatus.FRIEND to 1),
                )
        }

        test("Europe/Berlin: a change at 23:30 UTC on the last day of February belongs to March, not February") {
            val deltas = deltasOf(listOf("2026-02-28T23:30:00Z" to MemberStatus.ACTIVE))
            val periods = monthly("2026-02-01", "2026-03-01", berlin, "2026-03-10T10:00:00Z")
            val result = MemberCountAggregation.countsAt(deltas = deltas, boundaries = periods.map { it.boundary })
            result.map { it.getValue(MemberStatus.ACTIVE) } shouldBe listOf(0, 1)
        }

        test("America/New_York: a change at 03:00 UTC on 1 March is still February in the organization zone") {
            val deltas = deltasOf(listOf("2026-03-01T03:00:00Z" to MemberStatus.ACTIVE))
            val periods = monthly("2026-02-01", "2026-03-01", newYork, "2026-03-10T10:00:00Z")
            val result = MemberCountAggregation.countsAt(deltas = deltas, boundaries = periods.map { it.boundary })
            result.map { it.getValue(MemberStatus.ACTIVE) } shouldBe listOf(1, 1)
        }

        test("leap year: 29 February 2028 belongs to February, the quarter boundary follows the DST offset") {
            val feb =
                deltasOf(
                    listOf("2028-02-29T22:30:00Z" to MemberStatus.ACTIVE),
                    listOf("2028-02-29T23:30:00Z" to MemberStatus.FRIEND),
                )
            val febPeriods = monthly("2028-02-01", "2028-03-01", berlin, "2028-06-01T10:00:00Z")
            val febResult = MemberCountAggregation.countsAt(deltas = feb, boundaries = febPeriods.map { it.boundary })
            febResult.map { it.getValue(MemberStatus.ACTIVE) to it.getValue(MemberStatus.FRIEND) } shouldBe listOf(1 to 0, 1 to 1)

            val quarter =
                deltasOf(
                    listOf("2028-03-31T21:59:00Z" to MemberStatus.ACTIVE),
                    listOf("2028-03-31T22:01:00Z" to MemberStatus.FRIEND),
                )
            val quarters =
                MemberCountAggregation.periods(
                    from = LocalDate.parse("2028-01-01"),
                    to = LocalDate.parse("2028-04-15"),
                    granularity = MemberCountGranularity.QUARTER,
                    orgZone = berlin,
                    now = at("2028-06-01T10:00:00Z"),
                )
            quarters.map { it.start.toString() } shouldBe listOf("2028-01-01", "2028-04-01")
            val q = MemberCountAggregation.countsAt(deltas = quarter, boundaries = quarters.map { it.boundary })
            q.map { it.getValue(MemberStatus.ACTIVE) to it.getValue(MemberStatus.FRIEND) } shouldBe listOf(1 to 0, 1 to 1)
        }

        test("year boundary: 31 December 23:30 UTC is already the next year in Berlin") {
            val deltas = deltasOf(listOf("2026-12-31T23:30:00Z" to MemberStatus.ACTIVE))
            val periods =
                MemberCountAggregation.periods(
                    from = LocalDate.parse("2026-01-01"),
                    to = LocalDate.parse("2027-06-01"),
                    granularity = MemberCountGranularity.YEAR,
                    orgZone = berlin,
                    now = at("2027-06-01T10:00:00Z"),
                )
            periods.map { it.start.toString() } shouldBe listOf("2026-01-01", "2027-01-01")
            MemberCountAggregation
                .countsAt(deltas = deltas, boundaries = periods.map { it.boundary })
                .map { it.getValue(MemberStatus.ACTIVE) } shouldBe listOf(0, 1)
        }

        test("no history at all and a range before the first entry give zeros; a range that contains the first entry splits correctly") {
            val periods = monthly("2020-01-01", "2020-03-01", berlin, "2026-03-10T10:00:00Z")
            MemberCountAggregation.countsAt(deltas = emptyList(), boundaries = periods.map { it.boundary }) shouldBe List(3) { counts() }
            val deltas = deltasOf(listOf("2020-02-15T10:00:00Z" to MemberStatus.ACTIVE))
            MemberCountAggregation
                .countsAt(deltas = deltas, boundaries = periods.map { it.boundary })
                .map { it.getValue(MemberStatus.ACTIVE) } shouldBe listOf(0, 1, 1)
            val before = monthly("2019-01-01", "2019-12-01", berlin, "2026-03-10T10:00:00Z")
            MemberCountAggregation.countsAt(deltas = deltas, boundaries = before.map { it.boundary }).all { m ->
                m.values.all { it == 0 }
            } shouldBe
                true
        }

        test("the boundary of the current period is now: a change stamped exactly now is counted, one a microsecond later is not") {
            val now = "2026-03-20T12:00:00Z"
            val periods = monthly("2026-03-01", "2026-03-20", berlin, now)
            periods.single().current shouldBe true
            val exactlyNow = deltasOf(listOf(now to MemberStatus.ACTIVE))
            MemberCountAggregation
                .countsAt(
                    deltas = exactlyNow,
                    boundaries =
                        periods.map {
                            it.boundary
                        },
                ).single()
                .getValue(MemberStatus.ACTIVE) shouldBe
                1
            val later = deltasOf(listOf("2026-03-20T12:00:00.000001Z" to MemberStatus.ACTIVE))
            MemberCountAggregation
                .countsAt(
                    deltas = later,
                    boundaries = periods.map { it.boundary },
                ).single()
                .getValue(MemberStatus.ACTIVE) shouldBe
                0
            val muchLater = deltasOf(listOf("2026-03-20T12:00:01Z" to MemberStatus.ACTIVE))
            MemberCountAggregation
                .countsAt(
                    deltas = muchLater,
                    boundaries =
                        periods.map {
                            it.boundary
                        },
                ).single()
                .getValue(MemberStatus.ACTIVE) shouldBe
                0
        }

        test("a daylight-saving month: March 2026 in Berlin ends at 22:00 UTC, the month before at 23:00 UTC") {
            val periods = monthly("2026-02-01", "2026-03-01", berlin, "2026-06-01T10:00:00Z")
            periods.map { it.boundary.toString() } shouldBe listOf("2026-02-28T23:00", "2026-03-31T22:00")
            // UTC check for the same boundaries: Berlin midnight converted to UTC
            periods.map { LocalDateTime.parse(it.boundary.toString()).toInstant(TimeZone.UTC).toString() } shouldBe
                listOf("2026-02-28T23:00:00Z", "2026-03-31T22:00:00Z")
        }

        test("an inconsistent chain (a row that leaves a status nobody holds) fails loudly instead of showing a negative number") {
            val broken =
                listOf(
                    StatusDelta(
                        status = MemberStatus.WITHDRAWN,
                        previous = MemberStatus.ACTIVE,
                        effectiveFrom = at("2026-01-10T10:00:00Z"),
                        count = 1,
                    ),
                )
            val periods = monthly("2026-01-01", "2026-02-01", berlin, "2026-03-10T10:00:00Z")
            shouldThrow<IllegalStateException> {
                MemberCountAggregation.countsAt(
                    deltas = broken,
                    boundaries = periods.map { it.boundary },
                )
            }
        }

        test("bulk rows with one instant collapse into one group: the count is the group size") {
            val deltas =
                listOf(StatusDelta(status = MemberStatus.ACTIVE, previous = null, effectiveFrom = at("2026-01-10T10:00:00Z"), count = 250))
            val periods = monthly("2026-01-01", "2026-01-31", berlin, "2026-03-10T10:00:00Z")
            MemberCountAggregation
                .countsAt(
                    deltas = deltas,
                    boundaries = periods.map { it.boundary },
                ).single()
                .getValue(MemberStatus.ACTIVE) shouldBe
                250
        }

        test("pointCount at the limit: 240 is allowed, 241 is not, for every granularity") {
            val from = LocalDate.parse("2000-01-01")

            fun lastDayOfPeriods(
                n: Int,
                g: MemberCountGranularity,
            ): LocalDate {
                var start = MemberStatisticsRules.periodStart(date = from, granularity = g)
                repeat(n) { start = MemberStatisticsRules.nextPeriodStart(periodStart = start, granularity = g) }
                return start.minusDay()
            }
            MemberCountGranularity.entries.forEach { g ->
                MemberStatisticsRules.pointCount(
                    from = from,
                    to = lastDayOfPeriods(MemberStatisticsRules.MAX_POINTS, g),
                    granularity = g,
                ) shouldBe
                    240
                MemberStatisticsRules.pointCount(
                    from = from,
                    to = lastDayOfPeriods(MemberStatisticsRules.MAX_POINTS + 1, g),
                    granularity = g,
                ) shouldBe
                    241
            }
            MemberStatisticsRules.pointCount(
                from = LocalDate.parse("2026-03-31"),
                to = LocalDate.parse("2026-03-01"),
                granularity = MemberCountGranularity.MONTH,
            ) shouldBe
                0
            MemberStatisticsRules.pointCount(
                from = LocalDate.parse("2026-01-15"),
                to = LocalDate.parse("2026-12-31"),
                granularity = MemberCountGranularity.QUARTER,
            ) shouldBe
                4
            MemberStatisticsRules.pointCount(
                from = LocalDate.parse("2025-12-31"),
                to = LocalDate.parse("2026-01-01"),
                granularity = MemberCountGranularity.YEAR,
            ) shouldBe
                2
        }
    })

private fun LocalDate.minusDay(): LocalDate = LocalDate.fromEpochDays(toEpochDays() - 1)
