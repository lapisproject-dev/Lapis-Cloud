package network.lapis.cloud.server.attachments

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import network.lapis.cloud.shared.domain.AttachmentRules

/** V1.9.232 -- the sanitizer of mailing-attachment display names. */
class AttachmentNamesTest :
    FunSpec({
        test("a harmless name is kept unchanged") {
            sanitizeAttachmentName("Einladung 2026.pdf") shouldBe "Einladung 2026.pdf"
        }

        test("path parts are cut at the last slash or backslash") {
            sanitizeAttachmentName("../../etc/passwd.pdf") shouldBe "passwd.pdf"
            sanitizeAttachmentName("C:\\x\\a.pdf") shouldBe "a.pdf"
        }

        test("CR, LF, NUL and other control characters are removed") {
            sanitizeAttachmentName("a\r\nb\u0000c\u0007.pdf") shouldBe "abc.pdf"
        }

        test("bidi overrides, ZWSP and ZWJ are removed, NBSP becomes a plain space") {
            sanitizeAttachmentName("rechnung\u202Efdp.exe") shouldBe "rechnungfdp.exe"
            sanitizeAttachmentName("a\u200Bb\u200Dc.pdf") shouldBe "abc.pdf"
            sanitizeAttachmentName("Plan\u00A0Mai.pdf") shouldBe "Plan Mai.pdf"
        }

        test("characters that are forbidden in Windows names are removed") {
            sanitizeAttachmentName("a\"b<c>d:e*f?g|h.pdf") shouldBe "abcdefgh.pdf"
        }

        test("a lone surrogate is removed") {
            sanitizeAttachmentName("a\uD800b.pdf") shouldBe "ab.pdf"
        }

        test("reserved Windows device names get a prefix") {
            sanitizeAttachmentName("CON.pdf") shouldBe "_CON.pdf"
            sanitizeAttachmentName("com1.txt") shouldBe "_com1.txt"
            sanitizeAttachmentName("NUL") shouldBe "_NUL"
            sanitizeAttachmentName("CONTRACT.pdf") shouldBe "CONTRACT.pdf"
        }

        test("a 300 character name is shortened to 120 and keeps its extension") {
            val name = sanitizeAttachmentName("a".repeat(300) + ".pdf")!!
            name.length shouldBe AttachmentRules.MAX_NAME_CHARS
            name shouldEndWith ".pdf"
        }

        test("a name that is only an extension, or empty after cleaning, is invalid") {
            sanitizeAttachmentName(".pdf") shouldBe null
            sanitizeAttachmentName("   ") shouldBe null
            sanitizeAttachmentName("\u0000\u0001") shouldBe null
            sanitizeAttachmentName("...") shouldBe null
            sanitizeAttachmentName("") shouldBe null
        }

        test("spaces and dots at both ends are trimmed") {
            sanitizeAttachmentName("  name.pdf. ") shouldBe "name.pdf"
        }

        test("a decomposed umlaut is NFC-normalised, so the duplicate key of both spellings is equal") {
            val decomposed = sanitizeAttachmentName("Ba\u0308r.pdf")!!
            decomposed shouldBe "B\u00E4r.pdf"
            attachmentNameKey(decomposed) shouldBe attachmentNameKey("B\u00E4R.PDF")
        }

        test("the duplicate key is case-insensitive") {
            attachmentNameKey("Plan.PDF") shouldBe attachmentNameKey("plan.pdf")
        }
    })
