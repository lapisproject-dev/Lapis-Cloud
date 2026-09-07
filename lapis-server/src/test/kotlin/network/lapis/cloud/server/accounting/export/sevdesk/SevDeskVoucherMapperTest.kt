package network.lapis.cloud.server.accounting.export.sevdesk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.shared.domain.AccountingExportDirection
import java.math.BigDecimal
import java.util.Locale

private fun testVoucher(
    direction: AccountingExportDirection = AccountingExportDirection.INCOME,
    externalCategoryId: String = "12:99",
    remark: String = "Lapis Cloud Export LAPIS-20260105-1a2b3c4d",
) = OutboundVoucher(
    voucherDate = LocalDate(2026, 1, 5),
    voucherNumber = "LAPIS-20260105-1a2b3c4d",
    direction = direction,
    grossAmount = BigDecimal("119.00"),
    externalCategoryId = externalCategoryId,
    remark = remark,
)

/** Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- pins [SevDeskVoucherMapper]'s field-by-field mapping. */
class SevDeskVoucherMapperTest :
    FunSpec({
        test("toRequest: INCOME maps to creditDebit D") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(direction = AccountingExportDirection.INCOME))
            (result is SevDeskVoucherMapper.MappingResult.Ok) shouldBe true
            (result as SevDeskVoucherMapper.MappingResult.Ok).request.voucher.creditDebit shouldBe "D"
        }

        test("toRequest: EXPENSE maps to creditDebit C") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(direction = AccountingExportDirection.EXPENSE))
            (result as SevDeskVoucherMapper.MappingResult.Ok).request.voucher.creditDebit shouldBe "C"
        }

        test("toRequest: voucherDate is dd.MM.yyyy WITH leading zeros for single-digit day/month") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucher.voucherDate shouldBe "05.01.2026"
        }

        test("toRequest: voucherDate always uses ASCII digits (Locale.ROOT), regardless of the JVM default locale") {
            // Regression guard for a MAJOR finding: without an explicit Locale.ROOT, a JVM default
            // locale with non-ASCII decimal digits (e.g. Arabic) makes toSevDeskDateString() emit
            // non-ASCII digits. sevDesk's saveVoucher then rejects the date with HTTP 422, which
            // this codebase maps to VoucherPushOutcome.Rejected -> markFailed WITHOUT retry -- a
            // silent, permanent export failure that a plain "clean check" run under the JVM's own
            // (typically ASCII-digit) default locale would never surface.
            val previousDefault = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("ar-EG")) // Eastern Arabic-Indic digits by default
                val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
                val voucherDate = result.request.voucher.voucherDate

                voucherDate shouldBe "05.01.2026"
                voucherDate.all { it.code < 128 } shouldBe true
            } finally {
                Locale.setDefault(previousDefault)
            }
        }

        test("toRequest: sumGross and sumNet are identical, net=false, taxRate=0") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
            val pos = result.request.voucherPosSave.single()
            pos.net shouldBe false
            pos.sumGross.toString() shouldBe pos.sumNet.toString()
            pos.taxRate.toString() shouldBe "0"
        }

        test("toRequest: supplier is always null, supplierName is the constant collective name") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucher.supplier shouldBe null
            result.request.voucher.supplierName shouldBe "Sammelbuchung Lapis Cloud"
        }

        test("toRequest: comment is the voucher's own remark, NEVER a journal-entry description substitute") {
            val result =
                SevDeskVoucherMapper.toRequest(testVoucher(remark = "Lapis Cloud Export LAPIS-20260105-1a2b3c4d")) as
                    SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucherPosSave
                .single()
                .comment shouldBe "Lapis Cloud Export LAPIS-20260105-1a2b3c4d"
        }

        test("toRequest: externalCategoryId splits accountDatevId:taxRuleId correctly") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(externalCategoryId = "12:99")) as SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucherPosSave
                .single()
                .accountDatev.id shouldBe "12"
            result.request.voucher.taxRule.id shouldBe "99"
        }

        test("toRequest: status is always 50 (draft), voucherType is always VOU") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucher.status shouldBe 50
            result.request.voucher.voucherType shouldBe "VOU"
        }

        test("toRequest: description is the voucherNumber") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucher.description shouldBe "LAPIS-20260105-1a2b3c4d"
        }

        test("toRequest: exactly one voucherPosSave entry") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher()) as SevDeskVoucherMapper.MappingResult.Ok
            result.request.voucherPosSave.size shouldBe 1
        }

        test("toRequest: externalCategoryId without a colon is Unmappable, never throws") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(externalCategoryId = "abc"))
            (result is SevDeskVoucherMapper.MappingResult.Unmappable) shouldBe true
            (result as SevDeskVoucherMapper.MappingResult.Unmappable).errorCode shouldBe "INVALID_CATEGORY_MAPPING"
        }

        test("toRequest: externalCategoryId with non-numeric parts is Unmappable, never throws") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(externalCategoryId = "abc:def"))
            (result is SevDeskVoucherMapper.MappingResult.Unmappable) shouldBe true
        }

        test("toRequest: externalCategoryId with more than one colon is Unmappable, never throws") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(externalCategoryId = "12:99:1"))
            (result is SevDeskVoucherMapper.MappingResult.Unmappable) shouldBe true
        }

        test("toRequest: blank externalCategoryId is Unmappable, never throws") {
            val result = SevDeskVoucherMapper.toRequest(testVoucher(externalCategoryId = ""))
            (result is SevDeskVoucherMapper.MappingResult.Unmappable) shouldBe true
        }
    })
