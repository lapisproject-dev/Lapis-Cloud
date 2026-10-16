package network.lapis.cloud.server.memberfinder

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.shared.domain.MEMBER_FINDER_MIN_REGION_SIZE
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderRules
import java.io.File

/**
 * V1.9.144 tripwire (b) -- the visibility threshold of five is FIXED: a `const val` with the literal 5 that no variable, environment variable,
 * configuration or database column can set. A group size below five is a re-identification risk, and a configurable threshold would be turned down
 * by the first operator who finds it "too strict".
 */
class MemberFinderThresholdPinTest :
    FunSpec({
        val shared = File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/domain/MemberFinder.kt")
        val serverMain = SourceScan.mainRoot()

        test("the threshold is the literal 5 and declared as a const val") {
            MEMBER_FINDER_MIN_REGION_SIZE shouldBe 5
            SourceScan.blank(shared.readText()).contains("const val MEMBER_FINDER_MIN_REGION_SIZE: Int = 5") shouldBe true
        }

        test("no var, no environment variable, no configuration and no column can set a threshold") {
            val offenders =
                (serverMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() + shared)
                    .filter { file ->
                        val code = SourceScan.blank(file.readText())
                        Regex("""\bvar\s+\w*(MinRegion|MIN_REGION|minRegionSize|RegionThreshold)\w*""").containsMatchIn(code) ||
                            Regex("""getenv\([^)]*(FINDER|REGION)""", RegexOption.IGNORE_CASE).containsMatchIn(code)
                    }.map { it.name }
            offenders.shouldBeEmpty()
            val migration = File(SourceScan.mainRoot().parentFile, "resources/db/migration/V107__member_finder.sql").readText().lowercase()
            (migration.contains("min_region") || migration.contains("threshold")) shouldBe false
        }

        test("bandOf pins the band edges") {
            MemberFinderRules.bandOf(4) shouldBe null
            MemberFinderRules.bandOf(5) shouldBe MemberFinderBand.B5_9
            MemberFinderRules.bandOf(9) shouldBe MemberFinderBand.B5_9
            MemberFinderRules.bandOf(10) shouldBe MemberFinderBand.B10_19
            MemberFinderRules.bandOf(19) shouldBe MemberFinderBand.B10_19
            MemberFinderRules.bandOf(20) shouldBe MemberFinderBand.B20_PLUS
        }
    })
