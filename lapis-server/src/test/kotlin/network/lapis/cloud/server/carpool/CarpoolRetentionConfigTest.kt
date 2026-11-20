package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** V1.9.318 (wave 6): the interval of the carpool retention poller is a whole number of hours from 1 to 24; everything else is the default of 24. */
class CarpoolRetentionConfigTest :
    FunSpec({
        test("nothing, blank, text, zero, negative, too large and decimal values fall back to 24") {
            listOf(null, "", "   ", "abc", "0", "25", "-1", "1.5", "12h", "99999999999999999999").forEach {
                CarpoolRetentionConfig.parse(it) shouldBe 24L
            }
        }

        test("1 to 24 are taken as they are, surrounding blanks are ignored") {
            CarpoolRetentionConfig.parse("1") shouldBe 1L
            CarpoolRetentionConfig.parse("12") shouldBe 12L
            CarpoolRetentionConfig.parse(" 6 ") shouldBe 6L
            CarpoolRetentionConfig.parse("24") shouldBe 24L
        }

        test("load reads exactly one variable") {
            CarpoolRetentionConfig.load { name -> if (name == "LAPIS_CARPOOL_RETENTION_INTERVAL_HOURS") "8" else null } shouldBe 8L
            CarpoolRetentionConfig.load { null } shouldBe 24L
            CarpoolRetentionConfig.ENV shouldBe "LAPIS_CARPOOL_RETENTION_INTERVAL_HOURS"
        }
    })
