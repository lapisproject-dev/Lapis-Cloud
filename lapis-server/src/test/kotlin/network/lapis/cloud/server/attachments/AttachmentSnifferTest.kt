package network.lapis.cloud.server.attachments

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AttachmentRules
import java.io.File
import java.nio.file.Files
import network.lapis.cloud.server.attachments.AttachmentTestFiles as F

private fun verdictOf(
    bytes: ByteArray,
    extension: String,
): AttachmentVerdict {
    val file = Files.createTempFile("mail-sniff", ".bin").toFile()
    try {
        file.writeBytes(bytes)
        return AttachmentSniffer.classify(file = file, declaredExtension = extension)
    } finally {
        file.delete()
    }
}

private fun accepted(
    bytes: ByteArray,
    extension: String,
): AttachmentVerdict.Accepted = verdictOf(bytes = bytes, extension = extension) as AttachmentVerdict.Accepted

private fun relsOf(
    type: String,
    target: String,
    external: Boolean,
): ByteArray =
    (
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" " +
            "Target=\"$target\"" + (if (external) " TargetMode=\"External\"" else "") + "/></Relationships>"
    ).toByteArray()

private fun fixture(name: String): ByteArray {
    val stream =
        checkNotNull(AttachmentSnifferTest::class.java.getResourceAsStream("/mailing-attachments/$name")) { "missing $name" }
    return stream.use { it.readBytes() }
}

private const val OPC_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

/** V1.9.232 -- content classification of mailing attachments, one block per type. */
class AttachmentSnifferTest :
    FunSpec({
        context("happy path: every whitelisted extension with a minimal real file") {
            val samples: Map<String, ByteArray> =
                mapOf(
                    "pdf" to F.PDF,
                    "png" to F.PNG,
                    "jpg" to F.JPEG,
                    "jpeg" to F.JPEG,
                    "gif" to F.GIF,
                    "webp" to F.webp(),
                    "docx" to F.docx(),
                    "xlsx" to F.xlsx(),
                    "pptx" to F.pptx(),
                    "odt" to F.odf(mime = F.ODT),
                    "ods" to F.odf(mime = F.ODS),
                    "odp" to F.odf(mime = F.ODP),
                    "txt" to "Hallo Welt\n".toByteArray(),
                    "csv" to "a;b\n1;2\n".toByteArray(),
                )
            test("the sample set covers the whole whitelist") { samples.keys shouldBe AttachmentRules.ALLOWED.keys }
            samples.forEach { (extension, bytes) ->
                test(extension) {
                    accepted(bytes = bytes, extension = extension).mime shouldBe AttachmentRules.ALLOWED.getValue(extension)
                }
            }
        }

        context("extensions that are not on the whitelist") {
            listOf("exe", "zip", "html", "svg", "js", "", "docm", "xls", "doc", "bat").forEach { extension ->
                test("'$extension' is TypeNotAllowed") {
                    verdictOf(bytes = F.PDF, extension = extension) shouldBe
                        AttachmentVerdict.TypeNotAllowed
                }
            }
        }

        context("spoofing: the content must belong to the extension") {
            test(".pdf with PNG content") { verdictOf(bytes = F.PNG, extension = "pdf") shouldBe AttachmentVerdict.ContentMismatch }
            test(".png with an executable") {
                verdictOf(bytes = "MZ\u0090\u0000".toByteArray(Charsets.ISO_8859_1), extension = "png") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".jpg with a GIF") { verdictOf(bytes = F.GIF, extension = "jpg") shouldBe AttachmentVerdict.ContentMismatch }
            test(".docx with an xlsx package") {
                verdictOf(bytes = F.xlsx(), extension = "docx") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".xlsx with a docx package") {
                verdictOf(bytes = F.docx(), extension = "xlsx") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".odt with an ods package") {
                verdictOf(bytes = F.odf(mime = F.ODS), extension = "odt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".docx with plain text") {
                verdictOf(bytes = "hello".toByteArray(), extension = "docx") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("an empty file") { verdictOf(bytes = ByteArray(0), extension = "pdf") shouldBe AttachmentVerdict.ContentMismatch }
            test(".txt with an HTML page") {
                verdictOf(bytes = "<html><script>alert(1)</script></html>".toByteArray(), extension = "txt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".txt with an SVG") {
                verdictOf(bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(), extension = "txt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".csv with an XML document") {
                verdictOf(bytes = "<?xml version=\"1.0\"?><a/>".toByteArray(), extension = "csv") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test(".txt with a PDF inside") { verdictOf(bytes = F.PDF, extension = "txt") shouldBe AttachmentVerdict.ContentMismatch }
        }

        context("WebP needs a RIFF length that fits the file") {
            test("a RIFF length beyond the file is refused") {
                val bytes = F.webp().copyOf(20)
                verdictOf(bytes = bytes, extension = "webp") shouldBe AttachmentVerdict.ContentMismatch
            }
            test("RIFF without WEBP is refused") {
                val bytes = F.webp().also { it[8] = 'A'.code.toByte() }
                verdictOf(bytes = bytes, extension = "webp") shouldBe AttachmentVerdict.ContentMismatch
            }
        }

        context("an encrypted PDF is accepted (mailing a protected file is legitimate)") {
            test("/Encrypt in the trailer") {
                accepted(bytes = "%PDF-1.7\ntrailer\n<</Encrypt 5 0 R>>\n".toByteArray(), extension = "pdf").mime shouldBe "application/pdf"
            }
        }

        context("Office packages with macros or embedded objects") {
            test("docx with vbaProject.bin") {
                verdictOf(bytes = F.docx(listOf("word/vbaProject.bin" to byteArrayOf(1, 2))), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("docx with an embeddings/ entry") {
                verdictOf(bytes = F.docx(listOf("word/embeddings/oleObject1.bin" to byteArrayOf(1))), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("pptx with an activeX/ entry") {
                verdictOf(bytes = F.pptx(listOf("ppt/activeX/activeX1.xml" to byteArrayOf(1))), extension = "pptx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("xlsx with a macrosheets/ entry") {
                verdictOf(bytes = F.xlsx(listOf("xl/macrosheets/sheet1.xml" to byteArrayOf(1))), extension = "xlsx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("a macroEnabled content type (a renamed .docm)") {
                val docm =
                    F.ooxml(
                        mainPart = "word/document.xml",
                        contentTypesExtra =
                            "<Override PartName=\"/word/document.xml\" " +
                                "ContentType=\"application/vnd.ms-word.document.macroEnabled.main+xml\"/>",
                    )
                verdictOf(bytes = docm, extension = "docx") shouldBe AttachmentVerdict.MacroOrEmbedded
            }
            test("docx with an external attachedTemplate relationship (remote template injection)") {
                val rels = relsOf(type = "attachedTemplate", target = "https://evil.example/x.dotm", external = true)
                verdictOf(bytes = F.docx(listOf("word/_rels/settings.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("type attribute smuggled into the Target value (first 'type=' is not the Type attribute)") {
                val rels =
                    (
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<Relationship Id=\"rId1\" Target=\"https://evil.example/x.dotm?type='http://schemas.openxmlformats.org/" +
                            "officeDocument/2006/relationships/hyperlink'\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\" " +
                            "TargetMode=\"External\"/></Relationships>"
                    ).toByteArray()
                verdictOf(bytes = F.docx(listOf("word/_rels/settings.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("TargetMode written as a character reference is still External") {
                val rels =
                    (
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<Relationship Id=\"rId1\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\" " +
                            "Target=\"https://evil.example/x.dotm\" TargetMode=\"&#69;xternal\"/></Relationships>"
                    ).toByteArray()
                verdictOf(bytes = F.docx(listOf("word/_rels/settings.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("namespace-prefixed Relationship element is still checked") {
                val rels =
                    (
                        "<r:Relationships xmlns:r=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<r:Relationship Id=\"rId1\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate\" " +
                            "Target=\"https://evil.example/x.dotm\" TargetMode=\"External\"/></r:Relationships>"
                    ).toByteArray()
                verdictOf(bytes = F.docx(listOf("word/_rels/settings.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("a relationships part with a DOCTYPE is refused") {
                val rels = "<!DOCTYPE r [<!ENTITY x \"y\">]><Relationships xmlns=\"$OPC_NS\"/>".toByteArray()
                verdictOf(bytes = F.docx(listOf("word/_rels/document.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("a relationships part that is not well-formed is refused") {
                verdictOf(
                    bytes = F.docx(listOf("word/_rels/document.xml.rels" to "<Relationships".toByteArray())),
                    extension = "docx",
                ) shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("docx with an external oleObject relationship") {
                val rels = relsOf(type = "oleObject", target = "https://evil.example/x", external = true)
                verdictOf(bytes = F.docx(listOf("word/_rels/document.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("docx with an external frame relationship") {
                val rels = relsOf(type = "frame", target = "https://evil.example/f", external = true)
                verdictOf(bytes = F.docx(listOf("word/_rels/document.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("docx with an external hyperlink and an internal relationship stays accepted") {
                val rels =
                    (
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                            "<Relationship Id=\"r1\" " +
                            "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" " +
                            "Target=\"https://example.org\" TargetMode=\"External\"/>" +
                            "<Relationship Id=\"r2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" " +
                            "Target=\"styles.xml\"/></Relationships>"
                    ).toByteArray()
                verdictOf(bytes = F.docx(listOf("word/_rels/document.xml.rels" to rels)), extension = "docx") shouldBe
                    AttachmentVerdict.Accepted(mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            }
            test("a binary main part (document.bin)") {
                val bin = F.zip(entries = listOf("[Content_Types].xml" to "<Types/>".toByteArray(), "word/document.bin" to byteArrayOf(1)))
                verdictOf(bytes = bin, extension = "docx") shouldBe AttachmentVerdict.MacroOrEmbedded
            }
            test("a truncated zip") {
                verdictOf(bytes = F.docx().copyOf(40), extension = "docx") shouldBe AttachmentVerdict.ContentMismatch
            }
            test("an OOXML without [Content_Types].xml") {
                verdictOf(bytes = F.zip(entries = listOf("word/document.xml" to "<a/>".toByteArray())), extension = "docx") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
        }

        context("a ZIP64 marker is refused") {
            test("zip64 end-of-central-directory locator") {
                val base = F.docx()
                // Insert a ZIP64 locator signature (PK\u0006\u0007, 20 bytes) right before the EOCD record.
                val eocd = base.size - 22
                val locator =
                    ByteArray(20).also {
                        it[0] = 0x50
                        it[1] = 0x4B
                        it[2] = 0x06
                        it[3] = 0x07
                    }
                val patched = base.copyOfRange(0, eocd) + locator + base.copyOfRange(eocd, base.size)
                verdictOf(bytes = patched, extension = "docx") shouldBe AttachmentVerdict.ContentMismatch
            }
        }

        context("real LibreOffice-saved files (macro-free, with Configurations2/ directory entry) are accepted") {
            for (extension in listOf("odt", "ods", "docx")) {
                test("libreoffice.$extension") {
                    val bytes = fixture("libreoffice.$extension")
                    (verdictOf(bytes = bytes, extension = extension) is AttachmentVerdict.Accepted) shouldBe true
                }
            }
        }

        context("ODF") {
            test("Basic/ entries are macros") {
                verdictOf(
                    bytes = F.odf(mime = F.ODT, extraEntries = listOf("Basic/Standard/Module1.xml" to byteArrayOf(1))),
                    extension = "odt",
                ) shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("embedded Object N/ entries are refused") {
                verdictOf(
                    bytes = F.odf(mime = F.ODT, extraEntries = listOf("Object 1/content.xml" to byteArrayOf(1))),
                    extension = "odt",
                ) shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("ObjectReplacements/ entries are refused") {
                verdictOf(
                    bytes = F.odf(mime = F.ODT, extraEntries = listOf("ObjectReplacements/Object 1" to byteArrayOf(1))),
                    extension = "odt",
                ) shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("a manifest declaring an OLE object is refused") {
                val manifest =
                    F.manifest(
                        "<manifest:file-entry manifest:full-path=\"bin/ole1\" " +
                            "manifest:media-type=\"application/vnd.sun.star.oleobject\"/>",
                    )
                verdictOf(bytes = F.odf(mime = F.ODT, manifest = manifest), extension = "odt") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("an OLE media type hidden behind a character reference is still refused") {
                val manifest =
                    F.manifest(
                        "<manifest:file-entry manifest:full-path=\"bin/ole1\" " +
                            "manifest:media-type=\"application/vnd.sun.star.&#111;leobject\"/>",
                    )
                verdictOf(bytes = F.odf(mime = F.ODT, manifest = manifest), extension = "odt") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("a manifest with a DOCTYPE is refused") {
                val manifest = "<!DOCTYPE m [<!ENTITY x \"y\">]>" + F.manifest()
                verdictOf(bytes = F.odf(mime = F.ODT, manifest = manifest), extension = "odt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("a manifest that is not well-formed XML is refused") {
                verdictOf(bytes = F.odf(mime = F.ODT, manifest = "<manifest:manifest"), extension = "odt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("a sub-document directory entry in the manifest is refused") {
                val manifest =
                    F.manifest(
                        "<manifest:file-entry manifest:full-path=\"Sub/\" " +
                            "manifest:media-type=\"application/vnd.oasis.opendocument.spreadsheet\"/>",
                    )
                verdictOf(bytes = F.odf(mime = F.ODT, manifest = manifest), extension = "odt") shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("a LibreOffice-style manifest with Configurations2/ and Pictures/ directory entries stays accepted") {
                val manifest =
                    F.manifest(
                        "<manifest:file-entry manifest:full-path=\"Configurations2/\" " +
                            "manifest:media-type=\"application/vnd.sun.xml.ui.configuration\"/>" +
                            "<manifest:file-entry manifest:full-path=\"Pictures/\" manifest:media-type=\"\"/>" +
                            "<manifest:file-entry manifest:full-path=\"Thumbnails/thumbnail.png\" manifest:media-type=\"image/png\"/>" +
                            "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>",
                    )
                verdictOf(bytes = F.odf(mime = F.ODT, manifest = manifest), extension = "odt") shouldBe
                    AttachmentVerdict.Accepted(mime = F.ODT)
            }
            test("Scripts/ entries are macros") {
                verdictOf(
                    bytes = F.odf(mime = F.ODS, extraEntries = listOf("Scripts/python/a.py" to byteArrayOf(1))),
                    extension = "ods",
                ) shouldBe
                    AttachmentVerdict.MacroOrEmbedded
            }
            test("a deflated mimetype entry is refused") {
                verdictOf(bytes = F.odf(mime = F.ODT, mimetypeStored = false), extension = "odt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("an encrypted package (encryption-data in the manifest) is refused") {
                val manifest = F.manifest("<manifest:file-entry><manifest:encryption-data/></manifest:file-entry>")
                verdictOf(bytes = F.odf(mime = F.ODP, manifest = manifest), extension = "odp") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("mimetype is not the first entry") {
                val bytes =
                    F.zip(
                        entries =
                            listOf(
                                "content.xml" to "<a/>".toByteArray(),
                                "mimetype" to F.ODT.toByteArray(),
                                "META-INF/manifest.xml" to "<m/>".toByteArray(),
                            ),
                        stored = setOf("mimetype"),
                    )
                verdictOf(bytes = bytes, extension = "odt") shouldBe AttachmentVerdict.ContentMismatch
            }
        }

        context("text") {
            test("a NUL byte is refused") {
                verdictOf(bytes = "a\u0000b".toByteArray(), extension = "txt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("a C0 control character is refused") {
                verdictOf(bytes = "a\u0001b".toByteArray(), extension = "csv") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("TAB, LF, CR and FF are fine") {
                accepted(bytes = "a\tb\r\nc\u000Cd".toByteArray(), extension = "txt").charset shouldBe
                    "UTF-8"
            }
            test("UTF-8 with umlauts and a BOM is UTF-8") {
                accepted(
                    bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Grüße".toByteArray(Charsets.UTF_8),
                    extension = "txt",
                ).charset shouldBe
                    "UTF-8"
            }
            test("windows-1252 bytes fall back to windows-1252") {
                accepted(bytes = "Gr\u00FC\u00DFe;\u20AC".toByteArray(charset("windows-1252")), extension = "csv").charset shouldBe
                    "windows-1252"
            }
            test("an undefined windows-1252 byte (0x81) is refused") {
                verdictOf(bytes = byteArrayOf(0x41, 0x81.toByte(), 0x42), extension = "txt") shouldBe
                    AttachmentVerdict.ContentMismatch
            }
            test("a truncated UTF-8 sequence at the end is not UTF-8 (falls back, here windows-1252)") {
                accepted(bytes = byteArrayOf(0x41, 0xC3.toByte()), extension = "txt").charset shouldBe "windows-1252"
            }
            test("a UTF-8 file larger than the decode buffer is still UTF-8") {
                val big = ("äöü€ ".repeat(20_000)).toByteArray(Charsets.UTF_8)
                accepted(bytes = big, extension = "txt").charset shouldBe "UTF-8"
            }
        }

        test("a file that vanishes while it is classified is refused, never thrown") {
            val gone = File.createTempFile("mail-sniff-gone", ".bin")
            gone.delete()
            AttachmentSniffer.classify(file = gone, declaredExtension = "pdf") shouldBe AttachmentVerdict.ContentMismatch
        }
    })
