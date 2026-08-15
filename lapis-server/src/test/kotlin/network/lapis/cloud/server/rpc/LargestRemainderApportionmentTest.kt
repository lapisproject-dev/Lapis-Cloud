package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

private data class ApportionmentScenario(
    val weights: Map<Uuid, BigDecimal>,
    val pool: BigDecimal,
)

/** Random 0..8 non-negative weights and a random pool (0..10 000.00 EUR/LTR, always scale 2). */
private fun scenarioArb(): Arb<ApportionmentScenario> =
    arbitrary { rs ->
        val random = rs.random
        val count = random.nextInt(0, 9)
        val weights =
            (0 until count).associate {
                Uuid.random() to BigDecimal(random.nextInt(0, 100_000)).movePointLeft(2).setScale(2)
            }
        val pool = BigDecimal(random.nextInt(0, 1_000_000)).movePointLeft(2).setScale(2)
        ApportionmentScenario(weights = weights, pool = pool)
    }

/**
 * Pure property tests for [LargestRemainderApportionment] -- the extraction target of
 * [VoteSettlement]'s own `allocateProportional`. Mirrors `VoteVickreyTest`'s property-test style;
 * a regression guard that `VoteVickreyTest` itself stays green after this extraction lives in that
 * file, not here (behavioral equivalence, not a new property).
 */
class LargestRemainderApportionmentTest :
    FunSpec({
        test("Σ result.values == pool exactly, over random scenarios with at least one positive weight") {
            checkAll(300, scenarioArb()) { scenario ->
                if (scenario.weights.values.any { it.signum() > 0 } && scenario.pool.signum() > 0) {
                    val result = LargestRemainderApportionment.apportion(weights = scenario.weights, pool = scenario.pool)
                    val sum = result.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                    sum.setScale(2) shouldBe scenario.pool.setScale(2)
                }
            }
        }

        test("no share is ever negative") {
            checkAll(300, scenarioArb()) { scenario ->
                val result = LargestRemainderApportionment.apportion(weights = scenario.weights, pool = scenario.pool)
                result.values.all { it.signum() >= 0 } shouldBe true
            }
        }

        test("a single weight receives the entire pool") {
            val key = Uuid.random()
            val pool = BigDecimal("123.45")
            val result = LargestRemainderApportionment.apportion(weights = mapOf(key to BigDecimal("7.00")), pool = pool)
            result[key]?.compareTo(pool) shouldBe 0
        }

        test("empty weights map returns an empty result") {
            LargestRemainderApportionment.apportion(weights = emptyMap(), pool = BigDecimal("100.00")) shouldBe emptyMap()
        }

        test("pool zero returns every key mapped to zero, not an empty map") {
            val a = Uuid.random()
            val b = Uuid.random()
            val result =
                LargestRemainderApportionment.apportion(
                    weights = mapOf(a to BigDecimal("10.00"), b to BigDecimal("20.00")),
                    pool = ZERO_2DP,
                )
            result[a]?.compareTo(ZERO_2DP) shouldBe 0
            result[b]?.compareTo(ZERO_2DP) shouldBe 0
        }

        test("every weight zero returns every key mapped to zero even with a non-zero pool") {
            val a = Uuid.random()
            val b = Uuid.random()
            val result =
                LargestRemainderApportionment.apportion(weights = mapOf(a to ZERO_2DP, b to ZERO_2DP), pool = BigDecimal("50.00"))
            result[a]?.compareTo(ZERO_2DP) shouldBe 0
            result[b]?.compareTo(ZERO_2DP) shouldBe 0
        }

        test("two equal weights split an odd pool with the extra cent deterministically, and repeated calls agree") {
            val a = Uuid.random()
            val b = Uuid.random()
            val weights = mapOf(a to BigDecimal("50.00"), b to BigDecimal("50.00"))
            val pool = BigDecimal("0.01")
            val first = LargestRemainderApportionment.apportion(weights = weights, pool = pool)
            val second = LargestRemainderApportionment.apportion(weights = weights, pool = pool)
            first shouldBe second
            val sum = first.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
            sum.setScale(2) shouldBe pool
        }

        test("determinism: identical input produces identical output") {
            checkAll(200, scenarioArb()) { scenario ->
                val first = LargestRemainderApportionment.apportion(weights = scenario.weights, pool = scenario.pool)
                val second = LargestRemainderApportionment.apportion(weights = scenario.weights, pool = scenario.pool)
                first shouldBe second
            }
        }

        test("proportionality: doubling every weight (keeping the pool fixed) leaves the apportionment unchanged") {
            val a = Uuid.random()
            val b = Uuid.random()
            val c = Uuid.random()
            val weights = mapOf(a to BigDecimal("10.00"), b to BigDecimal("20.00"), c to BigDecimal("30.00"))
            val doubled = weights.mapValues { (_, w) -> w * BigDecimal(2) }
            val pool = BigDecimal("60.00")
            LargestRemainderApportionment.apportion(weights = weights, pool = pool) shouldBe
                LargestRemainderApportionment.apportion(weights = doubled, pool = pool)
        }
    })
