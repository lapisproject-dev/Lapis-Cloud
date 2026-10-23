package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** V1.9.198 -- the shared upload/download helpers extracted into `TravelExpenseFileSupport.kt`. */
class TravelExpenseFileSupportTest :
    FunSpec({
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31)

        test("streamSingleTravelExpenseFile: every failure outcome leaves no file behind, success keeps it") {
            val dir = Files.createTempDirectory("te-support").toFile()
            try {
                var last: StreamedTravelExpenseFile? = null
                lateinit var target: File
                testApplication {
                    application {
                        routing {
                            post("/up") {
                                target = dir.resolve("nested/${Uuid.random()}.bin")
                                val outcome =
                                    call.streamSingleTravelExpenseFile(
                                        targetFile = target,
                                        maxBytes = 100,
                                        fallbackName = "anlage",
                                    )
                                last = outcome
                                if (outcome is StreamedTravelExpenseFile.Stored) {
                                    call.respond(HttpStatusCode.Created)
                                } else {
                                    call.respondStreamFailure(outcome = outcome)
                                }
                            }
                        }
                    }

                    fun body(
                        bytes: ByteArray,
                        name: String? = "x.pdf",
                    ) = MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        if (name !=
                                            null
                                        ) {
                                            "filename=\"$name\""
                                        } else {
                                            "form-data"
                                        },
                                    )
                                },
                            )
                        },
                    )

                    client.post("/up") { setBody(body(ByteArray(101) { pdf.getOrElse(it) { 0 } })) }.status shouldBe
                        HttpStatusCode.PayloadTooLarge
                    last shouldBe StreamedTravelExpenseFile.TooLarge
                    target.exists() shouldBe false

                    client.post("/up") { setBody(body("hello".toByteArray())) }.status shouldBe HttpStatusCode.UnsupportedMediaType
                    last shouldBe StreamedTravelExpenseFile.UnsupportedType
                    target.exists() shouldBe false

                    client.post("/up") { setBody(body(ByteArray(0))) }.status shouldBe HttpStatusCode.UnsupportedMediaType
                    target.exists() shouldBe false

                    client.post("/up") { setBody(body(pdf, name = "../../a/c.pdf")) }.status shouldBe HttpStatusCode.Created
                    val stored = last.shouldBeInstanceOf<StreamedTravelExpenseFile.Stored>()
                    stored.sanitizedFileName shouldBe "c.pdf"
                    stored.mimeType shouldBe "application/pdf"
                    stored.sizeBytes shouldBe pdf.size.toLong()
                    stored.sha256.length shouldBe 64
                    target.exists() shouldBe true
                }
            } finally {
                dir.deleteRecursively()
            }
        }

        test("contentDispositionHeader is header-injection safe") {
            val header = contentDispositionHeader("a\"b\\c\r\nX-Evil: 1.pdf")
            header shouldNotContain "\r"
            header shouldNotContain "\n"
            header shouldContain "filename*=UTF-8''"
            header.substringAfter("filename=\"").substringBefore("\";") shouldNotContain "\""
        }

        test("sanitizeReceiptFilename strips separators and control characters, falls back for blanks") {
            sanitizeReceiptFilename("../../etc/passwd") shouldBe "passwd"
            sanitizeReceiptFilename("C:\\x\\y.pdf") shouldBe "y.pdf"
            sanitizeReceiptFilename("a\u0000b\u007f.pdf") shouldBe "ab.pdf"
            sanitizeReceiptFilename("   ") shouldBe "beleg"
            sanitizeReceiptFilename("x".repeat(400)).length shouldBe 255
        }

        test("sanitizeUploadFilename takes a caller-specific fallback (documents use \"unterlage\"); the receipt wrapper keeps \"beleg\"") {
            sanitizeUploadFilename(raw = "  ", fallback = "unterlage") shouldBe "unterlage"
            sanitizeUploadFilename(raw = "../x.pdf", fallback = "unterlage") shouldBe "x.pdf"
            sanitizeReceiptFilename("  ") shouldBe "beleg"
        }

        test(
            "filenameWithExtension sets the extension of the sniffed type: replaces a wrong one, appends a missing one, keeps a right one",
        ) {
            val pdf = "application/pdf"
            val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            val xls = "application/vnd.ms-excel"
            filenameWithExtension(name = "ordnung.pdf", mime = pdf) shouldBe "ordnung.pdf"
            filenameWithExtension(name = "ORDNUNG.PDF", mime = pdf) shouldBe "ORDNUNG.PDF"
            filenameWithExtension(name = "tabelle.pdf", mime = xlsx) shouldBe "tabelle.xlsx"
            filenameWithExtension(name = "tabelle", mime = xls) shouldBe "tabelle.xls"
            filenameWithExtension(name = ".hidden", mime = pdf) shouldBe ".hidden.pdf"
            filenameWithExtension(name = "a.b.c", mime = xlsx) shouldBe "a.b.xlsx"
            filenameWithExtension(name = "x".repeat(255) + ".pdf", mime = xlsx).length shouldBe 255
            filenameWithExtension(name = "datei.png", mime = "image/png") shouldBe "datei.png"
        }

        test("the default classifier is the magic-byte allowlist of receipts and attachments (regression)") {
            val classify = DEFAULT_TRAVEL_EXPENSE_CLASSIFIER
            val file = java.io.File("unused")
            classify(file, byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D), 5) shouldBe FileClassification.Accepted("application/pdf")
            classify(file, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 3) shouldBe FileClassification.Accepted("image/jpeg")
            classify(file, byteArrayOf(0x50, 0x4B, 0x03, 0x04), 4) shouldBe FileClassification.Rejected(FileRejection.TYPE)
            classify(file, ByteArray(0), 0) shouldBe FileClassification.Rejected(FileRejection.TYPE)
        }

        test("canDownloadTravelExpenseFile: subject/requester always, BOARD/ADMIN only after DRAFT, never TREASURER or strangers") {
            val subject = Uuid.random()
            val requester = Uuid.random()

            fun member(
                id: Uuid,
                role: AccountRole,
            ) = CurrentMember(memberId = id, role = role, status = MemberStatus.ACTIVE)

            fun allowed(
                who: CurrentMember,
                status: TravelExpenseReportStatus,
            ) = canDownloadTravelExpenseFile(current = who, subjectId = subject, requestedById = requester, status = status)
            for (status in TravelExpenseReportStatus.entries) {
                allowed(who = member(subject, AccountRole.MEMBER), status = status) shouldBe true
                allowed(who = member(requester, AccountRole.MEMBER), status = status) shouldBe true
                allowed(who = member(Uuid.random(), AccountRole.MEMBER), status = status) shouldBe false
                allowed(who = member(Uuid.random(), AccountRole.TREASURER), status = status) shouldBe false
                val privilegedAllowed = status != TravelExpenseReportStatus.DRAFT
                allowed(who = member(Uuid.random(), AccountRole.BOARD), status = status) shouldBe privilegedAllowed
                allowed(who = member(Uuid.random(), AccountRole.ADMIN), status = status) shouldBe privilegedAllowed
            }
        }
    })
