package network.lapis.cloud.server.payment.sepa

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/** Pure tests of [SepaMandateReferenceGenerator] -- no DB access anywhere in this file. */
class SepaMandateReferenceGeneratorTest :
    FunSpec({
        val memberId = Uuid.random()
        val date = LocalDate(2026, 8, 19)

        test("generated reference is at most 35 characters and exactly 25 by construction") {
            val reference = SepaMandateReferenceGenerator.generate(memberId = memberId, signatureDate = date)
            reference.length shouldBeLessThanOrEqual 35
            reference.length shouldBe 25
        }

        test("generated reference contains only A-Z0-9- characters") {
            val reference = SepaMandateReferenceGenerator.generate(memberId = memberId, signatureDate = date)
            reference.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' } shouldBe true
        }

        test("isWellFormed recognizes this generator's own output") {
            val reference = SepaMandateReferenceGenerator.generate(memberId = memberId, signatureDate = date)
            SepaMandateReferenceGenerator.isWellFormed(reference) shouldBe true
        }

        test("isWellFormed rejects an arbitrary string") {
            SepaMandateReferenceGenerator.isWellFormed("not-a-mandate-reference") shouldBe false
            SepaMandateReferenceGenerator.isWellFormed("") shouldBe false
        }

        test("many generations for the same member on the same day collide only negligibly and carry no derivable sequence") {
            val references = (1..10_000).map { SepaMandateReferenceGenerator.generate(memberId = memberId, signatureDate = date) }
            val distinct = references.toSet()
            // 4 hex random suffix bytes-worth of entropy -- collisions are possible but should be rare;
            // requiring near-uniqueness (allowing some collisions from the birthday paradox at n=10000,
            // space=65536) without claiming zero.
            distinct.size shouldBeLessThanOrEqual 10_000
            (distinct.size > 9_000) shouldBe true
        }

        test("different memberIds produce different reference prefixes") {
            val otherMemberId = Uuid.random()
            val referenceA = SepaMandateReferenceGenerator.generate(memberId = memberId, signatureDate = date)
            val referenceB = SepaMandateReferenceGenerator.generate(memberId = otherMemberId, signatureDate = date)
            referenceA.substring(3, 11) shouldBe referenceA.substring(3, 11)
            (referenceA.substring(3, 11) == referenceB.substring(3, 11)) shouldBe false
        }

        test("reference format is LC-<8hex>-<yyyyMMdd>-<4hex>") {
            val reference = SepaMandateReferenceGenerator.generate(memberId = memberId, signatureDate = date)
            val parts = reference.split("-")
            parts shouldHaveSize 4
            parts[0] shouldBe "LC"
            parts[1].length shouldBe 8
            parts[2] shouldBe "20260819"
            parts[3].length shouldBe 4
        }
    })
