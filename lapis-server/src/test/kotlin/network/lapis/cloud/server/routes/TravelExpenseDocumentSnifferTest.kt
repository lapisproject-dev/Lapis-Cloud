package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.routes.TravelExpenseDocumentFixtures.OleEntry
import network.lapis.cloud.server.routes.TravelExpenseDocumentFixtures.Part
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import java.io.File
import java.nio.file.Files

/** V1.9.199 -- verdict table of [TravelExpenseDocumentSniffer] on documents built in the test. */
class TravelExpenseDocumentSnifferTest :
    FunSpec({
        val dir: File = Files.createTempDirectory("te-sniffer").toFile()
        afterSpec { dir.deleteRecursively() }

        fun verdict(bytes: ByteArray): FileClassification {
            val file = File.createTempFile("doc", ".bin", dir)
            file.writeBytes(bytes)
            val head = ByteArray(8)
            val filled = minOf(8, bytes.size)
            System.arraycopy(bytes, 0, head, 0, filled)
            return TravelExpenseDocumentSniffer.classify(file = file, head = head, filled = filled)
        }

        val type = FileClassification.Rejected(FileRejection.TYPE)
        val macro = FileClassification.Rejected(FileRejection.MACRO)
        val encrypted = FileClassification.Rejected(FileRejection.ENCRYPTED)
        val pdfOk = FileClassification.Accepted(TravelExpenseDocumentRules.MIME_PDF)
        val xlsxOk = FileClassification.Accepted(TravelExpenseDocumentRules.MIME_XLSX)
        val xlsOk = FileClassification.Accepted(TravelExpenseDocumentRules.MIME_XLS)

        context("PDF") {
            test("a plain PDF is accepted") { verdict(TravelExpenseDocumentFixtures.pdf()) shouldBe pdfOk }
            test("a PDF with an /Encrypt entry is ENCRYPTED") {
                verdict(TravelExpenseDocumentFixtures.pdf(extra = "<< /Encrypt 5 0 R >>")) shouldBe encrypted
            }
            test("the /Encrypt token is found across the read-buffer boundary (every offset around it)") {
                // the scan reads 8199 bytes first and carries 7 over: place the 8-byte token so that it straddles that border
                for (offset in 8190..8200) {
                    val header = "%PDF-1.4\n".toByteArray()
                    val bytes =
                        header + ByteArray(offset - header.size) { ' '.code.toByte() } + "/Encrypt".toByteArray() +
                            ByteArray(40) { ' '.code.toByte() }
                    verdict(bytes) shouldBe encrypted
                }
            }
            test("a file that only mentions encrypt in lower case stays accepted") {
                verdict(TravelExpenseDocumentFixtures.pdf(extra = "/encrypted-by-nobody")) shouldBe pdfOk
            }
        }

        context("XLSX") {
            test("a plain workbook is accepted") { verdict(TravelExpenseDocumentFixtures.xlsx()) shouldBe xlsxOk }
            test("a workbook with stored (uncompressed) content types is accepted") {
                val bytes =
                    TravelExpenseDocumentFixtures.zip(
                        listOf(
                            Part(
                                name = TravelExpenseDocumentFixtures.CONTENT_TYPES,
                                bytes = TravelExpenseDocumentFixtures.PLAIN_CONTENT_TYPES.toByteArray(),
                                stored = true,
                            ),
                            Part(name = TravelExpenseDocumentFixtures.WORKBOOK, bytes = "<workbook/>".toByteArray()),
                        ),
                    )
                verdict(bytes) shouldBe xlsxOk
            }
            test("xlsm: vbaProject.bin is MACRO") {
                verdict(TravelExpenseDocumentFixtures.xlsx(extra = listOf(Part(name = "xl/vbaProject.bin", bytes = ByteArray(8))))) shouldBe
                    macro
            }
            test("macro-enabled content type alone is MACRO") {
                verdict(TravelExpenseDocumentFixtures.xlsx(contentTypes = TravelExpenseDocumentFixtures.MACRO_CONTENT_TYPES)) shouldBe macro
            }
            test("xlsb, macrosheets, activeX and embeddings are MACRO (case-insensitive)") {
                val names =
                    listOf("xl/workbook.bin", "xl/macrosheets/sheet1.xml", "XL/ActiveX/activeX1.xml", "xl/embeddings/oleObject1.bin")
                names.forEach { name ->
                    verdict(TravelExpenseDocumentFixtures.xlsx(extra = listOf(Part(name = name, bytes = ByteArray(4))))) shouldBe macro
                }
            }
            test("docx and ods are TYPE") {
                verdict(
                    TravelExpenseDocumentFixtures.zip(
                        listOf(
                            Part(name = "[Content_Types].xml", bytes = "<Types/>".toByteArray()),
                            Part(name = "word/document.xml", bytes = "<w/>".toByteArray()),
                        ),
                    ),
                ) shouldBe
                    type
                verdict(
                    TravelExpenseDocumentFixtures.zip(
                        listOf(
                            Part(name = "mimetype", bytes = "application/vnd.oasis.opendocument.spreadsheet".toByteArray()),
                            Part(name = "content.xml", bytes = "<c/>".toByteArray()),
                        ),
                    ),
                ) shouldBe
                    type
            }
            test("a duplicated [Content_Types].xml is TYPE (parser differential)") {
                val forged =
                    TravelExpenseDocumentFixtures.xlsx(
                        extra =
                            listOf(
                                Part(name = "[Content_Types].yml", bytes = TravelExpenseDocumentFixtures.MACRO_CONTENT_TYPES.toByteArray()),
                            ),
                    )
                verdict(
                    TravelExpenseDocumentFixtures.replaceBytes(zip = forged, from = "[Content_Types].yml", to = "[Content_Types].xml"),
                ) shouldBe
                    type
            }
            test("2001 entries are refused, 2000 are fine") {
                fun many(count: Int) =
                    TravelExpenseDocumentFixtures.xlsx(
                        extra = (1..count - 2).map { Part(name = "xl/worksheets/s$it.xml", bytes = ByteArray(1)) },
                    )
                verdict(many(2000)) shouldBe xlsxOk
                verdict(many(2001)) shouldBe type
            }
            test("an entry name longer than 512 bytes is refused") {
                verdict(
                    TravelExpenseDocumentFixtures.xlsx(extra = listOf(Part(name = "xl/" + "a".repeat(520), bytes = ByteArray(1)))),
                ) shouldBe
                    type
            }
            test("the encryption bit makes it ENCRYPTED") {
                verdict(TravelExpenseDocumentFixtures.withEncryptionBit(TravelExpenseDocumentFixtures.xlsx())) shouldBe encrypted
            }
            test("a ZIP64 marker is refused") {
                verdict(TravelExpenseDocumentFixtures.withZip64Marker(TravelExpenseDocumentFixtures.xlsx())) shouldBe type
            }
            test("a deflate bomb in [Content_Types].xml is TYPE (declared size over the cap)") {
                val bomb = ByteArray(1024 * 1024) { ' '.code.toByte() }
                verdict(TravelExpenseDocumentFixtures.xlsx(contentTypes = String(bomb))) shouldBe type
            }
            test("a header that lies about the size does not help: the real inflated ratio is checked") {
                val big = ByteArray(200 * 1024) { ' '.code.toByte() }
                val honest = TravelExpenseDocumentFixtures.xlsx(contentTypes = String(big))
                verdict(TravelExpenseDocumentFixtures.withDeclaredUncompressedSize(zip = honest, size = 100)) shouldBe type
            }
            test("a truncated archive (EOCD missing) is TYPE") {
                val full = TravelExpenseDocumentFixtures.xlsx()
                verdict(full.copyOf(full.size - 30)) shouldBe type
                verdict(full.copyOf(10)) shouldBe type
            }
        }

        context("XLS (OLE2)") {
            test("a Workbook stream is accepted, so is the old Book name") {
                verdict(TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "Workbook")))) shouldBe xlsOk
                verdict(TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "Book")))) shouldBe xlsOk
            }
            test("EncryptionInfo / EncryptedPackage is ENCRYPTED") {
                verdict(
                    TravelExpenseDocumentFixtures.ole2(
                        entries = listOf(OleEntry(name = "EncryptionInfo"), OleEntry(name = "EncryptedPackage")),
                    ),
                ) shouldBe
                    encrypted
            }
            test("a VBA storage is MACRO") {
                verdict(
                    TravelExpenseDocumentFixtures.ole2(
                        entries = listOf(OleEntry(name = "Workbook"), OleEntry(name = "_VBA_PROJECT_CUR", type = 1)),
                    ),
                ) shouldBe
                    macro
                verdict(
                    TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "Workbook"), OleEntry(name = "VBA", type = 1))),
                ) shouldBe
                    macro
            }
            test("a document without a Workbook stream (a .doc) is TYPE") {
                verdict(
                    TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "WordDocument"), OleEntry(name = "1Table"))),
                ) shouldBe
                    type
            }
            test("a cycle in the FAT is TYPE, no endless loop") {
                verdict(TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "Workbook")), fatNextOfDirectory = 1L)) shouldBe
                    type
            }
            test("a sector size outside 512/4096 is TYPE") {
                verdict(TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "Workbook")), sectorShift = 10)) shouldBe type
            }
            test("a truncated compound file is TYPE") {
                verdict(TravelExpenseDocumentFixtures.ole2(entries = listOf(OleEntry(name = "Workbook"))).copyOf(700)) shouldBe type
            }
        }

        context("everything else") {
            test("an empty file is TYPE") { verdict(ByteArray(0)) shouldBe type }
            test("an executable is TYPE") {
                verdict(byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00)) shouldBe
                    type
            }
            test("a PNG and a text file are TYPE") {
                verdict(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)) shouldBe type
                verdict("hello world".toByteArray()) shouldBe type
            }
        }
    })
