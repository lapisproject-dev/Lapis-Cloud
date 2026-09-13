package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowanceVerdict
import network.lapis.cloud.shared.rpc.BadRequestException
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- pure logic, NO DB. Covers
 * [VolunteerAllowanceCalculator.check] and [requireSelfDeclarationShape].
 */
class VolunteerAllowanceCalculatorTest :
    FunSpec({
        test("capFor returns the correct cap per category") {
            VolunteerAllowanceCalculator.capFor(VolunteerAllowanceCategory.INSTRUCTOR) shouldBe BigDecimal("3300.00")
            VolunteerAllowanceCalculator.capFor(VolunteerAllowanceCategory.HONORARY) shouldBe BigDecimal("960.00")
        }

        test("a payment well under the cap is WITHIN_CAP, fully free, no exceeding amount") {
            val result =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.HONORARY,
                    amount = BigDecimal("100.00"),
                    priorPostedTotalThisYear = BigDecimal("200.00"),
                )
            result.verdict shouldBe VolunteerAllowanceVerdict.WITHIN_CAP
            result.freeAmount shouldBe BigDecimal("100.00")
            result.exceedingAmount shouldBe BigDecimal("0.00")
            result.newTotal shouldBe BigDecimal("300.00")
        }

        test("the cap exactly exhausted is CAP_EXHAUSTED even when equals() would say the scales differ") {
            // BigDecimal("960") vs BigDecimal("960.00") differ under equals() (different scale) but
            // must compare equal via compareTo -- this is exactly the trap the calculator's KDoc warns about.
            val result =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.HONORARY,
                    amount = BigDecimal("60"),
                    priorPostedTotalThisYear = BigDecimal("900"),
                )
            result.verdict shouldBe VolunteerAllowanceVerdict.CAP_EXHAUSTED
            result.exceedingAmount shouldBe BigDecimal("0.00")
            (result.newTotal.compareTo(BigDecimal("960")) == 0) shouldBe true
            (result.newTotal == BigDecimal("960")) shouldBe false
        }

        test("a payment that overshoots the cap splits exactly into free and exceeding amounts") {
            val result =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.HONORARY,
                    amount = BigDecimal("500.00"),
                    priorPostedTotalThisYear = BigDecimal("900.00"),
                )
            result.verdict shouldBe VolunteerAllowanceVerdict.EXCEEDS_CAP
            result.freeAmount shouldBe BigDecimal("60.00")
            result.exceedingAmount shouldBe BigDecimal("440.00")
            (result.freeAmount + result.exceedingAmount).compareTo(BigDecimal("500.00")) shouldBe 0
        }

        test("priorTotal already over the cap: freeAmount is zero, exceedingAmount is the full amount") {
            val result =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.INSTRUCTOR,
                    amount = BigDecimal("100.00"),
                    priorPostedTotalThisYear = BigDecimal("3300.00"),
                )
            result.verdict shouldBe VolunteerAllowanceVerdict.EXCEEDS_CAP
            result.freeAmount shouldBe BigDecimal("0.00")
            result.exceedingAmount shouldBe BigDecimal("100.00")
        }

        test("both categories have independent caps -- one does not influence the other") {
            val instructor =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.INSTRUCTOR,
                    amount = BigDecimal("3300.00"),
                    priorPostedTotalThisYear = BigDecimal.ZERO,
                )
            val honorary =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.HONORARY,
                    amount = BigDecimal("960.00"),
                    priorPostedTotalThisYear = BigDecimal.ZERO,
                )
            instructor.verdict shouldBe VolunteerAllowanceVerdict.CAP_EXHAUSTED
            honorary.verdict shouldBe VolunteerAllowanceVerdict.CAP_EXHAUSTED
            instructor.cap shouldBe BigDecimal("3300.00")
            honorary.cap shouldBe BigDecimal("960.00")
        }

        test("scale is always 2 regardless of input scale") {
            val result =
                VolunteerAllowanceCalculator.check(
                    category = VolunteerAllowanceCategory.HONORARY,
                    amount = BigDecimal("10"),
                    priorPostedTotalThisYear = BigDecimal("5.5"),
                )
            result.priorTotal.scale() shouldBe 2
            result.newTotal.scale() shouldBe 2
            result.freeAmount.scale() shouldBe 2
            result.exceedingAmount.scale() shouldBe 2
            result.cap.scale() shouldBe 2
        }

        test("check never throws, even for pathological inputs") {
            VolunteerAllowanceCalculator.check(
                category = VolunteerAllowanceCategory.HONORARY,
                amount = BigDecimal("0.01"),
                priorPostedTotalThisYear = BigDecimal("999999.99"),
            )
            VolunteerAllowanceCalculator.check(
                category = VolunteerAllowanceCategory.INSTRUCTOR,
                amount = BigDecimal.ZERO,
                priorPostedTotalThisYear = BigDecimal.ZERO,
            )
        }

        // ── requireSelfDeclarationShape matrix ─────────────────────────────────────────────

        val member = Uuid.random()
        val other = Uuid.random()
        val today = LocalDate(2026, 9, 12)

        test("IN_APP with recordedBy == memberId and signedOn == null is valid") {
            requireSelfDeclarationShape(
                source = VolunteerAllowanceDeclarationSource.IN_APP,
                memberId = member,
                recordedBy = member,
                signedOn = null,
                today = today,
            )
        }

        test("IN_APP with recordedBy != memberId is rejected") {
            shouldThrow<BadRequestException> {
                requireSelfDeclarationShape(
                    source = VolunteerAllowanceDeclarationSource.IN_APP,
                    memberId = member,
                    recordedBy = other,
                    signedOn = null,
                    today = today,
                )
            }
        }

        test("IN_APP with a signedOn set is rejected") {
            shouldThrow<BadRequestException> {
                requireSelfDeclarationShape(
                    source = VolunteerAllowanceDeclarationSource.IN_APP,
                    memberId = member,
                    recordedBy = member,
                    signedOn = today,
                    today = today,
                )
            }
        }

        test("ON_PAPER with recordedBy != memberId and a past signedOn is valid") {
            requireSelfDeclarationShape(
                source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                memberId = member,
                recordedBy = other,
                signedOn = LocalDate(2026, 1, 1),
                today = today,
            )
        }

        test("ON_PAPER with recordedBy == memberId is rejected") {
            shouldThrow<BadRequestException> {
                requireSelfDeclarationShape(
                    source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                    memberId = member,
                    recordedBy = member,
                    signedOn = today,
                    today = today,
                )
            }
        }

        test("ON_PAPER with signedOn == null is rejected") {
            shouldThrow<BadRequestException> {
                requireSelfDeclarationShape(
                    source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                    memberId = member,
                    recordedBy = other,
                    signedOn = null,
                    today = today,
                )
            }
        }

        test("ON_PAPER with a future signedOn is rejected") {
            shouldThrow<BadRequestException> {
                requireSelfDeclarationShape(
                    source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                    memberId = member,
                    recordedBy = other,
                    signedOn = LocalDate(2026, 9, 13),
                    today = today,
                )
            }
        }
    })
