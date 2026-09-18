package network.lapis.cloud.server.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.events.QrCodeEncoder
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Pins the four properties of the card that a later change could break silently: the ID-1 page
 * geometry, the ONE-page rule, the four printed fields, and -- the finding this wave's own font
 * work exists for -- that a non-Latin member name survives as itself rather than as `?`.
 */
class MemberCardPdfGeneratorTest :
    FunSpec({
        fun card(
            displayName: String = "Erika Mustermann",
            memberNumber: String = "M-2026-00042",
            statusLabel: String = "Mitglied",
            tier: String? = "Vollmitgliedschaft",
        ) = MemberCardPdfGenerator.Card(
            brandTitle = "Lapis Cloud",
            displayName = displayName,
            memberNumber = memberNumber,
            joinedAt = LocalDate(2026, 2, 1),
            statusLabel = statusLabel,
            membershipTierName = tier,
            qr = QrCodeEncoder.encode("https://cloud.example.org/ausweis?code=0123456789ABCDEF"),
        )

        fun textOf(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

        test("card is exactly one page in ISO/IEC 7810 ID-1 landscape geometry") {
            val bytes = MemberCardPdfGenerator.generate(card())
            Loader.loadPDF(bytes).use { document ->
                document.numberOfPages shouldBe 1
                val box = document.getPage(0).mediaBox
                // Float literals from the specification, compared with a tolerance rather than for
                // equality -- PDFBox round-trips the media box through the PDF's own number syntax.
                (box.width - 242.65f).toDouble() shouldBeLessThan 0.01
                (box.height - 153.07f).toDouble() shouldBeLessThan 0.01
                // Landscape: the long edge is the horizontal one.
                (box.width > box.height) shouldBe true
            }
        }

        test("prints brand, name, joining date, status, tier and member number") {
            val text = textOf(MemberCardPdfGenerator.generate(card()))
            text shouldContain "Lapis Cloud"
            text shouldContain "Erika Mustermann"
            text shouldContain "Mitglied seit 01.02.2026"
            text shouldContain "Vollmitgliedschaft"
            text shouldContain "M-2026-00042"
        }

        test("a member without a membership tier still gets a complete card") {
            val text = textOf(MemberCardPdfGenerator.generate(card(tier = null)))
            text shouldContain "Erika Mustermann"
            text shouldContain "M-2026-00042"
        }

        // The wave's load-bearing correctness test: `LetterPdfBuilder`'s Standard-14 fonts would
        // render each of these names as a row of `?`. The embedded DejaVu subset must not.
        test("Georgian and Cyrillic member names are rendered, not replaced by question marks") {
            listOf("ირაკლი ბეჭვაია", "Ирина Ковалёва", "Jürgen Groß").forEach { name ->
                val text = textOf(MemberCardPdfGenerator.generate(card(displayName = name)))
                text shouldContain name
                text shouldNotContain "?"
            }
        }

        // Stage 1 of the fallback posture: a glyph the embedded font genuinely lacks (Georgian
        // MTAVRULI, U+1C90.., absent from DejaVu 2.37) must degrade that ONE code point and leave
        // generation -- and the rest of the card -- intact.
        test("an unknown glyph degrades to U+FFFD instead of failing generation") {
            val text = textOf(MemberCardPdfGenerator.generate(card(displayName = "Ⴀdam Test")))
            text shouldContain "dam Test"
            text shouldContain "M-2026-00042"
        }

        test("an overlong display name is truncated rather than overrunning the QR column") {
            val bytes = MemberCardPdfGenerator.generate(card(displayName = "Maximiliane".repeat(12)))
            val text = textOf(bytes)
            // Still a valid, complete card: the member number (the field a human reads aloud) and
            // the ellipsis marking the cut are both present.
            text shouldContain "M-2026-00042"
            text shouldContain "…"
        }

        test("joinedLine uses the codebase's German date format") {
            MemberCardPdfGenerator.joinedLine(LocalDate(2026, 12, 3)) shouldBe "Mitglied seit 03.12.2026"
        }
    })
