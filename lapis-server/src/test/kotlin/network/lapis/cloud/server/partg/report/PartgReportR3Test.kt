package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.shared.domain.PartgCell
import network.lapis.cloud.shared.domain.PartgDeduction
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnit
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

private fun text(bytes: ByteArray): String =
    Loader.loadPDF(bytes).use { pdf -> PDFTextStripper().apply { sortByPosition = true }.getText(pdf) }

/** V1.9.269 (R3) -- the separate statements, the member count and the notes in the assembled document, the PDF and the XLSX. */
class PartgReportR3AssemblerTest :
    FunSpec({
        val doc = ReportFixtures.docR3()

        test("the whole-party deductions are partial sums with the missing deduction named, not a made-up total") {
            val whole = checkNotNull(doc.annex.wholeParty)
            whole.deductions.getValue(PartgDeduction.UNASSIGNABLE) shouldBe
                PartgCell.Partial(cents = 11_100L + 2_200L, missing = listOf("UNASSIGNABLE"))
            // OVER_3300 is null at the federal unit but 2,200 at the state unit: partial; the other three units have nothing entered
            whole.deductions.getValue(PartgDeduction.OVER_3300) shouldBe PartgCell.Partial(cents = 2_200L, missing = listOf("OVER_3300"))
            doc.annex.units
                .first()
                .deductions
                .getValue(PartgDeduction.OVER_3300) shouldBe PartgCell.NotEntered
            doc.annex.units
                .first()
                .deductions
                .getValue(PartgDeduction.CASH_OVER_1000) shouldBe PartgCell.Value(0L)
        }

        test("the whole-party member count is the federal value only, never a sum") {
            doc.annex.wholeParty?.memberCount shouldBe 1_234
            ReportFixtures.docR3(PartgReportScope.Single(PartgUnit.State(ReportFixtures.CHAPTER_A))).annex.wholeParty shouldBe null
        }

        test("donor rows are numbered per unit in the list order and carry the joined address") {
            doc.annex.donors.map { it.rowNumber } shouldContainExactly listOf(1, 2, 3, 4, 5, 6)
            doc.annex.donors
                .first()
                .addressLine shouldBe "Hauptstraße 1, 38100 Braunschweig"
            doc.annex.donors
                .last()
                .addressLine shouldBe ""
        }

        test("only the notes that are switched on are in the document; the holdings travel with them") {
            doc.notes.blocks.map { it.block.name } shouldContainExactly listOf("GENERAL_ACCOUNTING", "RELIEF_28", "HOLDINGS")
            doc.notes.holdings
                .single()
                .resultCents shouldBe -1_500_00L
        }

        test("without annex data the sections are empty, never a zero") {
            val plain = ReportFixtures.doc()
            plain.annex.donors shouldBe emptyList()
            plain.annex.units.all { page -> page.deductions.values.all { it == PartgCell.NotEntered } } shouldBe true
        }
    })

class PartgReportR3PdfTest :
    FunSpec({
        val doc = ReportFixtures.docR3()
        val bytes = PartgReportPdfRenderer().render(doc)
        val all = text(bytes)

        test("the three statements and the notes have their own headings") {
            all shouldContain "Gesonderter Ausweis A"
            all shouldContain "Gesonderter Ausweis B"
            all shouldContain "Mitgliederzahl zum 31.12."
            all shouldContain "Erläuterungen"
        }

        test("the donor list shows the rows, the amount and the legal-person marker; WinAnsi names survive, Cyrillic is replaced") {
            all shouldContain "Müller"
            all shouldContain "90.000,00"
            all shouldContain "juristisch"
            all shouldNotContain "Иван"
            all shouldContain "?????"
        }

        test("a note that is switched off does not appear; line breaks of a note stay separate lines") {
            all shouldNotContain "darf nicht erscheinen"
            all shouldContain "Erste Zeile"
            all shouldContain "Zweite Zeile"
            all shouldContain "Pressehaus GmbH"
            all shouldContain "-1.500,00"
        }

        test("an empty statement B says so instead of printing a zero or nothing") {
            val empty = text(PartgReportPdfRenderer().render(ReportFixtures.doc()))
            empty shouldContain "Keine Einträge erfasst"
            empty shouldContain "Erläuterungen"
            empty shouldContain "nicht erfasst"
        }

        test("a single-unit report has no whole-party member line") {
            val single = text(PartgReportPdfRenderer().render(ReportFixtures.docR3(PartgReportScope.Single(PartgUnit.Federal))))
            single shouldNotContain "Gesamtpartei"
            single shouldContain "nicht addiert"
        }

        test("the same document gives the same bytes") {
            PartgReportPdfRenderer().render(ReportFixtures.docR3()).contentEquals(bytes) shouldBe true
        }

        test("the page limit also bounds the new sections; 4,000-character notes are wrapped over pages, not cut") {
            io.kotest.assertions.throwables.shouldThrow<network.lapis.cloud.server.pdf.PdfLimitExceeded> {
                PartgReportPdfRenderer(maxPages = 2).render(ReportFixtures.docR3())
            }
            val longBody = (1..400).joinToString(" ") { "Wort$it" }
            val long =
                ReportFixtures.docR3().let { d ->
                    d.copy(
                        notes =
                            d.notes.copy(
                                blocks =
                                    d.notes.blocks.map { b ->
                                        if (b.block ==
                                            network.lapis.cloud.shared.domain.PartgNoteBlock.GENERAL_ACCOUNTING
                                        ) {
                                            b.copy(body = longBody)
                                        } else {
                                            b
                                        }
                                    },
                            ),
                    )
                }
            val rendered = text(PartgReportPdfRenderer().render(long))
            rendered shouldContain "Wort1 "
            rendered shouldContain "Wort400"
        }
    })

class PartgReportR3XlsxTest :
    FunSpec({
        val xlsx = readXlsx(PartgReportXlsxWriter.write(ReportFixtures.docR3()))

        test("the new sheets come before the hints sheet in the documented order") {
            xlsx.sheetNames.takeLast(7) shouldContainExactly
                listOf("Ausweis A", "Ausweis B", "Ausweis C", "Mitglieder", "Ausweis F", "Erläuterungen", "Hinweise")
        }

        test("no cell is a formula, whatever the donor name looks like") {
            xlsx.parts
                .filterKeys { it.startsWith("xl/worksheets/") }
                .values
                .forEach { String(it, Charsets.UTF_8) shouldNotContain "<f>" }
        }

        test("a name that starts with a formula character is an inline string with the quote prefix") {
            val b = xlsx.sheets.getValue("Ausweis B")
            val cells =
                b.values.filter {
                    it.value.startsWith("=HYPERLINK") ||
                        it.value.startsWith("+1") ||
                        it.value.startsWith("-x") ||
                        it.value.startsWith("@x")
                }
            cells.size shouldBe 4
            cells.forEach {
                it.type shouldBe "inlineStr"
                it.style shouldBe PartgReportXlsxWriter.S_QUOTE
            }
        }

        test("Cyrillic and Umlauts stay as typed; amounts are numbers with the euro format") {
            val b = xlsx.sheets.getValue("Ausweis B")
            b.values.any { it.value == "Иван Петров" } shouldBe true
            b.values.any { it.value.startsWith("Müller & Söhne <GmbH>") } shouldBe true
            val amount = b.values.first { it.style == PartgReportXlsxWriter.S_EURO }
            amount.type shouldBe "n"
            amount.value shouldBe "90000.00"
        }

        test("a missing deduction is the text 'nicht erfasst', zero is the number 0.00") {
            val a = xlsx.sheets.getValue("Ausweis A")
            a.values.any { it.value == "nicht erfasst" } shouldBe true
            a.values.any { it.type == "n" && it.value == "0.00" } shouldBe true
        }

        test("the member count is a number; a missing one is text; the hints sheet lists codes and rows but no name") {
            val m = xlsx.sheets.getValue("Mitglieder")
            m.values.any { it.type == "n" && it.value == "1234" } shouldBe true
            val hints =
                xlsx.sheets
                    .getValue("Hinweise")
                    .values
                    .joinToString("\n") { it.value }
            hints shouldContain "Plausibilitätshinweise"
            hints shouldContain "P04"
            hints shouldContain "P01"
            listOf("Müller", "Иван", "HYPERLINK", "Braunschweig").forEach { hints shouldNotContain it }
        }

        test("the same document gives the same bytes") {
            PartgReportXlsxWriter.write(ReportFixtures.docR3()).contentEquals(PartgReportXlsxWriter.write(ReportFixtures.docR3())) shouldBe
                true
        }
    })
