package network.lapis.cloud.server.ai.kb

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.io.Writer

/** Text of one page; [pageNumber] is `null` for formats without pages (plain text/Markdown). */
internal data class PageText(
    val pageNumber: Int?,
    val text: String,
)

internal enum class UnsupportedReason { MIME_TYPE_NOT_SUPPORTED, NO_EXTRACTABLE_TEXT, FILE_TOO_LARGE }

internal enum class ExtractionFailure { PARSE_ERROR, IO_ERROR }

internal sealed interface ExtractionResult {
    data class Extracted(
        val pages: List<PageText>,
    ) : ExtractionResult

    data class Unsupported(
        val code: UnsupportedReason,
    ) : ExtractionResult

    data class Failed(
        val code: ExtractionFailure,
    ) : ExtractionResult
}

/**
 * Extracts plain text from a stored document version: PDF (per page, so the page number survives as
 * a citation locator), `text/plain` and Markdown. Everything else is [ExtractionResult.Unsupported].
 *
 * **Never throws.** A scanned (image-only) PDF yields no text and is reported as
 * [UnsupportedReason.NO_EXTRACTABLE_TEXT] -- exactly the case the member-facing "searched
 * documents" block must make visible instead of silently pretending the document was searched.
 * Hard caps ([MAX_SOURCE_BYTES], [MAX_PAGES], [MAX_EXTRACTED_CHARS]) bound the cost, because
 * indexing runs synchronously inside an RPC call (this repo deliberately has no scheduler).
 */
internal object DocumentTextExtractor {
    const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    const val MAX_EXTRACTED_CHARS = 2_000_000
    const val MAX_PAGES = 2_000

    /**
     * Glyph budget of ONE page. PDFBox keeps a heavyweight `TextPosition` (~1 KB) per glyph until the
     * page is written, so the per-page bound -- not the document-wide [MAX_EXTRACTED_CHARS] -- is what
     * bounds heap. A realistic dense page holds a few thousand characters.
     */
    const val MAX_CHARS_PER_PAGE = 50_000

    private val PDF_MIME = setOf("application/pdf")
    private val TEXT_MIME = setOf("text/plain", "text/markdown", "text/x-markdown")
    private const val OCTET_STREAM = "application/octet-stream"

    fun extract(
        file: File,
        mimeType: String,
        fileName: String,
    ): ExtractionResult {
        val mime = mimeType.substringBefore(';').trim().lowercase()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val isPdf = mime in PDF_MIME || (mime == OCTET_STREAM && extension == "pdf")
        val isText = mime in TEXT_MIME || (mime == OCTET_STREAM && extension in setOf("txt", "md"))
        if (!isPdf && !isText) return ExtractionResult.Unsupported(code = UnsupportedReason.MIME_TYPE_NOT_SUPPORTED)
        if (!file.isFile) return ExtractionResult.Failed(code = ExtractionFailure.IO_ERROR)
        if (file.length() > MAX_SOURCE_BYTES) return ExtractionResult.Unsupported(code = UnsupportedReason.FILE_TOO_LARGE)
        return try {
            if (isPdf) extractPdf(file) else extractText(file)
        } catch (_: IOException) {
            ExtractionResult.Failed(code = ExtractionFailure.PARSE_ERROR)
        } catch (_: RuntimeException) {
            ExtractionResult.Failed(code = ExtractionFailure.PARSE_ERROR)
        }
    }

    private fun extractPdf(file: File): ExtractionResult =
        Loader.loadPDF(file).use { document ->
            val pageCount = minOf(document.numberOfPages, MAX_PAGES)
            val stripper = BudgetedTextStripper(MAX_CHARS_PER_PAGE)
            val pages = mutableListOf<PageText>()
            var total = 0
            try {
                for (page in 1..pageCount) {
                    stripper.startPage = page
                    stripper.endPage = page
                    stripper.resetPageBudget()
                    // Bounded on BOTH sides: glyphs are counted while the page is still being parsed
                    // (processTextPosition) and the output writer refuses to grow past the budget, so a
                    // single Flate-bomb page can never materialise an unbounded String/glyph list.
                    val budget = minOf(MAX_CHARS_PER_PAGE, MAX_EXTRACTED_CHARS - total)
                    val text = BudgetedWriter(budget).also { stripper.writeText(document, it) }.toString().trim()
                    if (text.isEmpty()) continue
                    total += text.length
                    if (total > MAX_EXTRACTED_CHARS) return@use ExtractionResult.Unsupported(code = UnsupportedReason.FILE_TOO_LARGE)
                    pages += PageText(pageNumber = page, text = text)
                }
            } catch (_: TextBudgetExceededException) {
                return@use ExtractionResult.Unsupported(code = UnsupportedReason.FILE_TOO_LARGE)
            }
            if (document.numberOfPages > MAX_PAGES) {
                ExtractionResult.Unsupported(code = UnsupportedReason.FILE_TOO_LARGE)
            } else if (pages.isEmpty()) {
                ExtractionResult.Unsupported(code = UnsupportedReason.NO_EXTRACTABLE_TEXT)
            } else {
                ExtractionResult.Extracted(pages = pages)
            }
        }

    private fun extractText(file: File): ExtractionResult {
        // Malformed byte sequences are replaced, never thrown on (String(bytes, UTF_8) semantics).
        val text = String(file.readBytes(), Charsets.UTF_8).trim()
        return when {
            text.isEmpty() -> ExtractionResult.Unsupported(code = UnsupportedReason.NO_EXTRACTABLE_TEXT)
            text.length > MAX_EXTRACTED_CHARS -> ExtractionResult.Unsupported(code = UnsupportedReason.FILE_TOO_LARGE)
            else -> ExtractionResult.Extracted(pages = listOf(PageText(pageNumber = null, text = text)))
        }
    }

    /** Not an [IOException]: PDFBox's content-stream engine swallows/rewraps some IOExceptions per operator. */
    private class TextBudgetExceededException : RuntimeException("Extracted text exceeds the budget", null, false, false)

    /** Counts glyphs of the current page while parsing, aborting once the per-page budget is blown. */
    private class BudgetedTextStripper(
        private val maxChars: Int,
    ) : PDFTextStripper() {
        private var glyphChars = 0L

        fun resetPageBudget() {
            glyphChars = 0
        }

        override fun processTextPosition(text: TextPosition) {
            glyphChars += text.unicode?.length ?: 0
            if (glyphChars > maxChars) throw TextBudgetExceededException()
            super.processTextPosition(text)
        }
    }

    /** A [StringWriter] that throws once more than [budget] characters were written. */
    private class BudgetedWriter(
        private val budget: Int,
    ) : Writer() {
        private val delegate = StringWriter()

        override fun write(
            cbuf: CharArray,
            off: Int,
            len: Int,
        ) {
            if (delegate.buffer.length.toLong() + len > budget) throw TextBudgetExceededException()
            delegate.write(cbuf, off, len)
        }

        override fun flush() = Unit

        override fun close() = Unit

        override fun toString(): String = delegate.toString()
    }
}
