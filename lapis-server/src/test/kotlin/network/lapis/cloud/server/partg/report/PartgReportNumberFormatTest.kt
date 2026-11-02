package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgPercent

class PartgReportNumberFormatTest :
    FunSpec({
        test("euro: German grouping, two decimals, ASCII minus") {
            PartgReportNumberFormat.euro(0) shouldBe "0,00"
            PartgReportNumberFormat.euro(5) shouldBe "0,05"
            PartgReportNumberFormat.euro(-5) shouldBe "-0,05"
            PartgReportNumberFormat.euro(123_456_789) shouldBe "1.234.567,89"
            PartgReportNumberFormat.euro(-123_456) shouldBe "-1.234,56"
            PartgReportNumberFormat.euro(99_999_999_999_999L) shouldBe "999.999.999.999,99"
        }

        test("euro: the Long limits do not overflow") {
            PartgReportNumberFormat.euro(Long.MAX_VALUE) shouldBe "92.233.720.368.547.758,07"
            PartgReportNumberFormat.euro(Long.MIN_VALUE) shouldBe "-92.233.720.368.547.758,08"
        }

        test("percent: tenths with a comma, 'n. b.' when not computable, empty when there is none") {
            PartgReportNumberFormat.percent(PartgPercent.Tenths(123)) shouldBe "12,3"
            PartgReportNumberFormat.percent(PartgPercent.Tenths(-5)) shouldBe "-0,5"
            PartgReportNumberFormat.percent(PartgPercent.NotComputable) shouldBe "n. b."
            PartgReportNumberFormat.percent(null) shouldBe ""
        }

        test("cell: value, partial and not entered") {
            PartgReportNumberFormat.cell(PartgCell.Value(123_456)) shouldBe "1.234,56"
            PartgReportNumberFormat.cell(PartgCell.Partial(cents = 123_456, missing = listOf("E01"))) shouldBe "1.234,56 (unvollst.)"
            PartgReportNumberFormat.cell(PartgCell.NotEntered) shouldBe "n. e."
            PartgReportNumberFormat.cellShort(PartgCell.Partial(cents = 100, missing = listOf("E01"))) shouldBe "1,00*"
        }
    })
