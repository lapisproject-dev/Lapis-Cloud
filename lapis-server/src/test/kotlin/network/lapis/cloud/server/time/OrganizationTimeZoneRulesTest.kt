package network.lapis.cloud.server.time

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.rpc.BadRequestException

class OrganizationTimeZoneRulesTest :
    FunSpec({
        test("valid region ids and UTC are accepted") {
            listOf("Europe/Berlin", "UTC", "Asia/Tbilisi", "America/Argentina/Buenos_Aires", "Europe/Vienna").forEach {
                OrganizationTimeZoneRules.isValid(it) shouldBe true
                OrganizationTimeZoneRules.validateZoneId(it).id shouldBe it
            }
        }

        test("offsets, short ids, Etc/GMT, SystemV, path tricks, blanks and over-long ids are rejected") {
            listOf(
                "+02:00",
                "CET",
                "EST5EDT",
                "Etc/GMT+5",
                "Etc/UTC",
                "SystemV/AST4",
                "../etc/passwd",
                "Europe/../Berlin",
                "",
                "   ",
                "Europe/Berlin\n",
                " Europe/Berlin",
                "Europe/Berlin ",
                "Europe/" + "x".repeat(60),
                "Europe/Nowhere",
                "europe/berlin",
                "<script>alert(1)</script>",
            ).forEach { raw ->
                OrganizationTimeZoneRules.isValid(raw) shouldBe false
                shouldThrow<BadRequestException> { OrganizationTimeZoneRules.validateZoneId(raw) }.message shouldBe "Unbekannte Zeitzone"
            }
        }

        test("the id list offered to the client contains the common zones and none of the rejected families") {
            val ids = OrganizationTimeZoneRules.availableZoneIds()
            ids shouldContain "Europe/Berlin"
            ids shouldContain "UTC"
            ids shouldContain "Asia/Tbilisi"
            ids.filter { it.startsWith("Etc/") || it.startsWith("SystemV/") } shouldBe emptyList()
            ids shouldNotContain "CET"
            ids.all { OrganizationTimeZoneRules.isValid(it) } shouldBe true
            (ids.size in 300..700) shouldBe true
        }
    })
