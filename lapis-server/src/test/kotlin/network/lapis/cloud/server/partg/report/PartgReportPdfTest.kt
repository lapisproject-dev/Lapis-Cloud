package network.lapis.cloud.server.partg.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgReportLimits
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

private fun pages(bytes: ByteArray): List<String> =
    Loader.loadPDF(bytes).use { pdf ->
        (1..pdf.numberOfPages).map { n ->
            PDFTextStripper()
                .apply {
                    startPage = n
                    endPage = n
                    sortByPosition = true
                }.getText(pdf)
        }
    }

class PartgReportPdfGoldenTest :
    FunSpec({
        val doc = ReportFixtures.doc()
        val bytes = PartgReportPdfRenderer().render(doc)
        val texts = pages(bytes)
        val all = texts.joinToString("\n")

        test("page 1 starts with the draft title and the completeness block") {
            texts.first() shouldContain "Vollständigkeit"
            texts.first() shouldContain "Rechenschaftsbericht 2025 – ENTWURF"
            texts.first() shouldContain "Positionen nicht erfasst"
        }

        test("every page has the header line, 'Seite x von y' and the legend") {
            texts.forEachIndexed { i, t ->
                t shouldContain "Test Partei e.V. · Rechenschaftsbericht 2025 · ENTWURF – nicht amtlich"
                t shouldContain "Seite ${i + 1} von ${texts.size}"
                t shouldContain "n. e. = nicht erfasst"
            }
        }

        test("every page is landscape A4") {
            Loader.loadPDF(bytes).use { pdf ->
                (0 until pdf.numberOfPages).forEach { pdf.getPage(it).mediaBox.let { box -> (box.width > box.height) shouldBe true } }
            }
        }

        test("not entered is 'n. e.' and never an amount of 0,00") {
            all shouldContain "n. e."
            Regex("""(?<![\d.,])0,00(?![\d])""").containsMatchIn(all) shouldBe false
        }

        test("incomplete sums are marked, the footnote names the missing positions, unreliable shares are 'n. b.'") {
            all shouldContain "(unvollst.)"
            all shouldContain "fehlend"
            all shouldContain "Nr. 5a"
            all shouldContain "n. b."
        }

        test("the summary carries the section title, the subsidy note and the level table") {
            all shouldContain "Zusammenfassung (§ 24 Abs. 9 PartG)"
            all shouldContain "innerparteilicher Zuschüsse"
            all shouldContain "Ergebnis je Ebene"
            all shouldContain "Einnahmenrechnung (§ 24 Abs. 4 PartG)"
            all shouldContain "Ausgabenrechnung (§ 24 Abs. 5 PartG)"
            all shouldContain "Vermögensbilanz (§ 24 Abs. 6 PartG)"
        }

        test("a negative result is printed with the ASCII minus, never a question mark") {
            all shouldContain "-"
            Regex("""-\d{1,3}(\.\d{3})*,\d{2}""").containsMatchIn(all) shouldBe true
            all shouldNotContain "?"
        }

        test("the watermark lives behind the content as a translucent graphics state") {
            Loader.loadPDF(bytes).use { pdf ->
                val resources = pdf.getPage(0).resources
                val alphas = resources.extGStateNames.map { resources.getExtGState(it).nonStrokingAlphaConstant }
                alphas.shouldNotBeEmpty()
                alphas.any { it == 0.12f } shouldBe true
            }
        }

        test("the same document gives the same bytes") {
            PartgReportPdfRenderer().render(ReportFixtures.doc()).contentEquals(bytes) shouldBe true
        }

        test("a single unit report has the unit page and no whole party") {
            val single = PartgReportPdfRenderer().render(ReportFixtures.doc(scope = PartgReportScope.Single(PartgUnit.Federal)))
            val text = pages(single).joinToString("\n")
            text shouldContain "Umfang: Bundesverband"
            text shouldNotContain "Gesamtpartei"
        }
    })

class PartgReportPdfLimitsTest :
    FunSpec({
        fun manyUnits(count: Int) =
            List(count) { i ->
                ReportFixtures.unitDto(
                    level = PartgUnitLevel.STATE,
                    chapter = "00000000-0000-4000-8000-%012d".format(i),
                    name = "Verband $i",
                )
            }

        fun fullEntered(
            units: List<network.lapis.cloud.shared.domain.PartgUnitDto>,
            value: Long,
        ) = mapOf(
            ReportFixtures.YEAR to
                units.associate { it.unitKey to PartgPositions.LEAF_CODES.associateWith { _ -> value } },
        )

        test("the maximum of 201 units renders and stays far below the page limit") {
            val units = manyUnits(PartgReportComputationLimit.MAX)
            val doc = ReportFixtures.doc(units = units, entered = fullEntered(units, 12_345_67L))
            val bytes = PartgReportPdfRenderer().render(doc)
            Loader.loadPDF(bytes).use { it.numberOfPages shouldBeLessThan PartgReportLimits.MAX_PDF_PAGES }
        }

        test("more pages than allowed throw PdfLimitExceeded") {
            shouldThrow<PdfLimitExceeded> { PartgReportPdfRenderer(maxPages = 3).render(ReportFixtures.doc()) }
        }

        test("a byte limit that is too small throws PdfLimitExceeded") {
            shouldThrow<PdfLimitExceeded> { PartgReportPdfRenderer(maxBytes = 100).render(ReportFixtures.doc()) }
        }

        test("hostile organisation names render without an exception and without markup leaking") {
            val name = "ტესტი 😀 (Partei) \\ \n zeile\t) Tj BT ${"x".repeat(500)}"
            val bytes = PartgReportPdfRenderer().render(ReportFixtures.doc(org = name))
            val text = pages(bytes).first()
            text shouldContain "?"
            text shouldNotContain "x".repeat(100)
        }

        test("the maximum amounts are never cut off and never overlap their neighbour") {
            val units = manyUnits(6)
            val doc = ReportFixtures.doc(units = units, entered = fullEntered(units, PartgPositionsMax.CENTS))
            val bytes = PartgReportPdfRenderer().render(doc)
            val overlaps = mutableListOf<String>()
            Loader.loadPDF(bytes).use { pdf ->
                object : PDFTextStripper() {
                    override fun writeString(
                        text: String,
                        textPositions: MutableList<TextPosition>,
                    ) {
                        val byLine = textPositions.filter { it.fontSizeInPt < 30f }.groupBy { Math.round(it.yDirAdj) }
                        byLine.values.forEach { line ->
                            val sorted = line.sortedBy { it.xDirAdj }
                            sorted.zipWithNext().forEach { (a, b) ->
                                if (a.xDirAdj + a.widthDirAdj > b.xDirAdj + 1.0f && a.unicode.isNotBlank() && b.unicode.isNotBlank()) {
                                    overlaps += "${a.unicode}${b.unicode}@${a.xDirAdj} in [$text]"
                                }
                            }
                        }
                    }
                }.apply { sortByPosition = true }.getText(pdf)
            }
            overlaps shouldBe emptyList()
            val all = pages(bytes).joinToString("\n")
            all shouldContain "999.999.999.999,99"
        }
    })

private object PartgReportComputationLimit {
    const val MAX = network.lapis.cloud.shared.domain.PartgReportComputation.MAX_UNITS
}

private object PartgPositionsMax {
    const val CENTS = network.lapis.cloud.shared.domain.PARTG_MAX_AMOUNT_CENTS
}

@Suppress("unused")
private val keep = listOf(PartgKind.LEAF)
