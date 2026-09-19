package network.lapis.cloud.server.ai.kb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.io.RandomAccessFile

/** Builds a PDF with one page per entry; `null` yields an empty (image-only stand-in) page. */
internal fun pdfBytes(pages: List<String?>): ByteArray {
    PDDocument().use { doc ->
        pages.forEach { text ->
            val page = PDPage()
            doc.addPage(page)
            if (text != null) {
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(text)
                    stream.endText()
                }
            }
        }
        val out = java.io.ByteArrayOutputStream()
        doc.save(out)
        return out.toByteArray()
    }
}

class DocumentTextExtractorTest :
    FunSpec({
        val dir =
            kotlin.io.path
                .createTempDirectory("extractor-test")
                .toFile()

        afterSpec { dir.deleteRecursively() }

        fun file(
            name: String,
            bytes: ByteArray,
        ): File = File(dir, name).also { it.writeBytes(bytes) }

        test("a multi-page PDF yields one entry per page with correct page numbers") {
            val f = file("a.pdf", pdfBytes(listOf("Seite eins Beitrag", "Seite zwei Vorstand", "Seite drei Wahl")))
            val result = DocumentTextExtractor.extract(file = f, mimeType = "application/pdf", fileName = "a.pdf")
            val pages = result.shouldBeInstanceOf<ExtractionResult.Extracted>().pages
            pages.map { it.pageNumber } shouldBe listOf(1, 2, 3)
            pages[1].text shouldContain "Vorstand"
        }

        test("blank pages are skipped but keep the numbering of the others") {
            val f = file("b.pdf", pdfBytes(listOf(null, "Nur Seite zwei", null)))
            val pages =
                DocumentTextExtractor
                    .extract(
                        file = f,
                        mimeType = "application/pdf",
                        fileName = "b.pdf",
                    ).shouldBeInstanceOf<ExtractionResult.Extracted>()
                    .pages
            pages.map { it.pageNumber } shouldBe listOf(2)
        }

        test("an image-only PDF without text is NO_EXTRACTABLE_TEXT") {
            val f = file("c.pdf", pdfBytes(listOf(null, null)))
            DocumentTextExtractor.extract(file = f, mimeType = "application/pdf", fileName = "c.pdf") shouldBe
                ExtractionResult.Unsupported(UnsupportedReason.NO_EXTRACTABLE_TEXT)
        }

        test("plain text and Markdown are extracted as a single page-less page") {
            val txt = file("d.txt", "§ 1 Name\nDer Verein heißt Beispielverein.".toByteArray())
            val md = file("e.md", "# Titel\nInhalt".toByteArray())
            DocumentTextExtractor
                .extract(
                    file = txt,
                    mimeType = "text/plain; charset=utf-8",
                    fileName = "d.txt",
                ).shouldBeInstanceOf<ExtractionResult.Extracted>()
                .pages
                .single()
                .pageNumber shouldBe
                null
            DocumentTextExtractor
                .extract(
                    file = md,
                    mimeType = "text/markdown",
                    fileName = "e.md",
                ).shouldBeInstanceOf<ExtractionResult.Extracted>()
            DocumentTextExtractor
                .extract(
                    file = md,
                    mimeType = "application/octet-stream",
                    fileName = "e.md",
                ).shouldBeInstanceOf<ExtractionResult.Extracted>()
        }

        test("an unsupported MIME type is MIME_TYPE_NOT_SUPPORTED") {
            val f = file("f.docx", byteArrayOf(1, 2, 3))
            DocumentTextExtractor.extract(
                file = f,
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                fileName = "f.docx",
            ) shouldBe ExtractionResult.Unsupported(UnsupportedReason.MIME_TYPE_NOT_SUPPORTED)
        }

        test("corrupt PDF bytes are a PARSE_ERROR, never an exception") {
            val f = file("g.pdf", "das ist kein pdf".toByteArray())
            DocumentTextExtractor.extract(file = f, mimeType = "application/pdf", fileName = "g.pdf") shouldBe
                ExtractionResult.Failed(ExtractionFailure.PARSE_ERROR)
        }

        test("a single page exceeding the extracted-text budget is FILE_TOO_LARGE, aborted while parsing") {
            val bytes =
                PDDocument().use { doc ->
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 1f)
                        stream.newLineAtOffset(0f, 700f)
                        // ~3M glyphs on ONE page (> MAX_EXTRACTED_CHARS), tiny compressed size.
                        val chunk = "a".repeat(1_000)
                        repeat(3_000) { stream.showText(chunk) }
                        stream.endText()
                    }
                    val out = java.io.ByteArrayOutputStream()
                    doc.save(out)
                    out.toByteArray()
                }
            val f = file("bomb.pdf", bytes)
            DocumentTextExtractor.extract(file = f, mimeType = "application/pdf", fileName = "bomb.pdf") shouldBe
                ExtractionResult.Unsupported(UnsupportedReason.FILE_TOO_LARGE)
        }

        test("a missing file is an IO_ERROR") {
            DocumentTextExtractor.extract(file = File(dir, "missing.pdf"), mimeType = "application/pdf", fileName = "missing.pdf") shouldBe
                ExtractionResult.Failed(ExtractionFailure.IO_ERROR)
        }

        test("a file above the size cap is FILE_TOO_LARGE without being read") {
            val big = File(dir, "big.txt")
            RandomAccessFile(big, "rw").use { it.setLength(DocumentTextExtractor.MAX_SOURCE_BYTES + 1L) }
            DocumentTextExtractor.extract(file = big, mimeType = "text/plain", fileName = "big.txt") shouldBe
                ExtractionResult.Unsupported(UnsupportedReason.FILE_TOO_LARGE)
        }

        test("an empty text file is NO_EXTRACTABLE_TEXT") {
            DocumentTextExtractor.extract(file = file("h.txt", "  \n ".toByteArray()), mimeType = "text/plain", fileName = "h.txt") shouldBe
                ExtractionResult.Unsupported(UnsupportedReason.NO_EXTRACTABLE_TEXT)
        }
    })
