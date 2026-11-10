package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import java.io.File

/** V1.9.226 -- all twelve labels of the status table, plus an unknown one; the table in the feature doc is pinned against the code. */
class LupaBbStatusMappingTest :
    FunSpec({
        // V1.9.298: the voting right a label implies (`null` = the sync does not touch it); only Mitglied (yes) and Neumitglied (no) say something
        val votingRightOf = mapOf("Mitglied" to true, "Neumitglied" to false)
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
                LupaBbStatusMapping.map(label) shouldBe LupaBbStatusMapping.Mapped(status = status, votingRight = votingRightOf[label])
            }
        }

        test("Foerderer stays FRIEND and Spender stays DONOR (open point D of V1.9.298: not changed)") {
            LupaBbStatusMapping.map("Förderer") shouldBe LupaBbStatusMapping.Mapped(status = MemberStatus.FRIEND)
            LupaBbStatusMapping.map("Spender") shouldBe LupaBbStatusMapping.Mapped(status = MemberStatus.DONOR)
        }

        test("'Storniert' is skipped as cancelled") {
            LupaBbStatusMapping.map("Storniert") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.CANCELLED_STATUS)
        }

        test("an unknown or empty label is skipped as unknown") {
            LupaBbStatusMapping.map("Ehrenmitglied") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
            LupaBbStatusMapping.map("") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
        }

        test("surrounding blanks are ignored, the spelling is not (case counts)") {
            LupaBbStatusMapping.map("  Mitglied ") shouldBe LupaBbStatusMapping.Mapped(status = MemberStatus.ACTIVE, votingRight = true)
            LupaBbStatusMapping.map("mitglied") shouldBe LupaBbStatusMapping.Skip(LupaBbRowReasonCode.UNKNOWN_STATUS)
        }

        test("the status table of docs/features/lupabb-sync.adoc names every one of the twelve labels and their targets") {
            val doc = File("../docs/features/lupabb-sync.adoc").takeIf { it.exists() } ?: File("docs/features/lupabb-sync.adoc")
            val text = doc.readText()
            for ((label, status) in expected) text.contains("| $label | ${status.name}") shouldBe true
            text.contains("| Storniert | skipped") shouldBe true
        }
    })
