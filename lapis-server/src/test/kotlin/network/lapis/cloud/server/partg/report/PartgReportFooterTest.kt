package network.lapis.cloud.server.partg.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** V1.9.291 (R6) -- the footer text of a version, and how the XLSX writes it (an ampersand is a control character in an Excel footer). */
class PartgReportFooterTest :
    FunSpec({
        test("the footer of a kept version names number, day, the first 12 characters of the checksum and the status") {
            PartgReportTexts.versionFooter(versionNumber = 3, keptOn = "08.11.2026", hash12 = "0123456789ab", final = false) shouldBe
                "Version 3 · festgehalten 08.11.2026 · Prüfsumme 0123456789ab · Entwurf als Arbeitshilfe, nicht eingereicht"
            PartgReportTexts.versionFooter(versionNumber = 3, keptOn = "08.11.2026", hash12 = "0123456789ab", final = true) shouldBe
                "Version 3 · festgehalten 08.11.2026 · Prüfsumme 0123456789ab · Intern final, nicht eingereicht"
        }

        test("the working state has its own footer text") {
            PartgReportTexts.WORKING_STATE_FOOTER shouldBe "Arbeitsstand, keine festgehaltene Version"
        }

        test("the XLSX footer is in row 2 of every sheet and in the print footer; an ampersand is doubled and the XML is escaped") {
            val bytes = PartgReportXlsxWriter.write(document = ReportFixtures.doc(), footer = "A & B <x>")
            val contents = readXlsx(bytes)
            contents.sheets.values.forEach { it.getValue("A2").value shouldBe "A & B <x>" }
            contents.sheetNames.indices.forEach { i ->
                val xml = String(contents.parts.getValue("xl/worksheets/sheet${i + 1}.xml"), Charsets.UTF_8)
                xml shouldContain "<headerFooter><oddFooter>&amp;LA &amp;&amp; B &lt;x&gt;</oddFooter></headerFooter>"
            }
        }

        test("without a footer argument the working-state footer is used") {
            val contents = readXlsx(PartgReportXlsxWriter.write(document = ReportFixtures.doc()))
            contents.sheets.values.forEach { it.getValue("A2").value shouldBe PartgReportTexts.WORKING_STATE_FOOTER }
        }

        test("a different footer changes the PDF bytes, the same footer gives the same bytes") {
            val doc = ReportFixtures.doc()
            val a = PartgReportPdfRenderer().render(document = doc, footer = "Version 1")
            val b = PartgReportPdfRenderer().render(document = doc, footer = "Version 1")
            val c = PartgReportPdfRenderer().render(document = doc, footer = "Version 2")
            (a.contentEquals(b)) shouldBe true
            (a.contentEquals(c)) shouldBe false
            String(a, Charsets.ISO_8859_1) shouldNotContain "Version 2"
        }
    })
