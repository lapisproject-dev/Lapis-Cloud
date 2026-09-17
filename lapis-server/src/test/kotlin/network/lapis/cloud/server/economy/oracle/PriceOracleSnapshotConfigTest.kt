package network.lapis.cloud.server.economy.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

/**
 * Exercises [PriceOracleSnapshotConfig.load] purely through its injected `env` function -- never
 * `System.getenv`. Covers the two gaps flagged in review (both previously untested):
 * `LAPIS_ORACLE_SNAPSHOT_ENABLED`'s opt-in default, and the `MIN_INTERVAL_SECONDS` floor actually
 * being exercised BELOW the floor, not merely AT it (the existing
 * [PriceOracleSnapshotPollerTest] only ever passes exactly `"300"`, which never exercises
 * `.coerceAtLeast`).
 */
class PriceOracleSnapshotConfigTest :
    FunSpec({
        test("everything unset -> disabled by default (opt-in, same posture as SepaConfig.pollerEnabled)") {
            val config = PriceOracleSnapshotConfig.load(envOf())
            config.enabled.shouldBeFalse()
            config.intervalSeconds shouldBe 3600L
        }

        test("LAPIS_ORACLE_SNAPSHOT_ENABLED=true -> enabled") {
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "true")).enabled.shouldBeTrue()
        }

        test("LAPIS_ORACLE_SNAPSHOT_ENABLED is case-insensitive") {
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "TRUE")).enabled.shouldBeTrue()
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "True")).enabled.shouldBeTrue()
        }

        test("any value other than 'true' -> disabled, no failure") {
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "false")).enabled.shouldBeFalse()
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "yes")).enabled.shouldBeFalse()
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "1")).enabled.shouldBeFalse()
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_ENABLED" to "")).enabled.shouldBeFalse()
        }

        test("interval below the 300s floor is clamped UP to the floor, not passed through") {
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS" to "1")).intervalSeconds shouldBe 300L
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS" to "0")).intervalSeconds shouldBe 300L
        }

        test("interval above the floor is passed through unchanged") {
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS" to "7200")).intervalSeconds shouldBe 7200L
        }

        test("unparseable interval falls back to the 3600s default, not a crash") {
            PriceOracleSnapshotConfig.load(envOf("LAPIS_ORACLE_SNAPSHOT_INTERVAL_SECONDS" to "not-a-number")).intervalSeconds shouldBe
                3600L
        }
    })
