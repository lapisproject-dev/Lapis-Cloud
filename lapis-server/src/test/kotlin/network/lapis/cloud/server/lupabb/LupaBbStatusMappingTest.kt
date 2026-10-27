package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import java.io.File

/** V1.9.226 -- all twelve labels of the status table, plus an unknown one; the table in the feature doc is pinned against the code. */
class LupaBbStatusMappingTest :
    FunSpec({
        val expected =
            mapOf(
                "Mitglied" to MemberStatus.ACTIVE,
                "Neumitglied" to MemberStatus.ACTIVE,
                "in Aufnahme" to MemberStatus.APPLICATION,
                "Förderer" to MemberStatus.FRIEND,
                "Spender" to MemberStatus.DONOR,
                "Nur Zahler" to MemberStatus.DONOR,
                "verstorben" to MemberStatus.DECEASED,
                "Widerrufen" to MemberStatus.WITHDRAWN,
                "Gekündigt" to MemberStatus.WITHDRAWN,
                "Ausgeschlossen" to MemberStatus.WITHDRAWN,
                "Ablehnung" to MemberStatus.REJECTED,
            )

        for ((label, status) in expected) {
            test("'$label' maps to $status") {
                LupaBbStatusMapping.map(label) shouldBe LupaBbStatusMapping.Mapped(status)
            }
        }

        test("'Storniert' is skipped as cancelled") {
            LupaBbStatusMapping.map("Storniert") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.CANCELLED_STATUS)
        }

        test("an unknown or empty label is skipped as unknown") {
            LupaBbStatusMapping.map("Ehrenmitglied") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
            LupaBbStatusMapping.map("") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
        }

        test("surrounding blanks are ignored, the spelling is not (case counts)") {
            LupaBbStatusMapping.map("  Mitglied ") shouldBe LupaBbStatusMapping.Mapped(MemberStatus.ACTIVE)
            LupaBbStatusMapping.map("mitglied") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
        }

        test("the status table of docs/features/lupabb-sync.adoc names every one of the twelve labels and their targets") {
            val doc = File("../docs/features/lupabb-sync.adoc").takeIf { it.exists() } ?: File("docs/features/lupabb-sync.adoc")
            val text = doc.readText()
            for ((label, status) in expected) text.contains("| $label | ${status.name}") shouldBe true
            text.contains("| Storniert | skipped") shouldBe true
        }
    })
