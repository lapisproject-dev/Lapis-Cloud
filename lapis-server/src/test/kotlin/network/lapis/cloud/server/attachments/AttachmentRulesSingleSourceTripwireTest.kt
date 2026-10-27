package network.lapis.cloud.server.attachments

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan

/**
 * V1.9.233 -- the attachment rules exist ONCE. Mailing messages and direct messages share the sniffer, the name cleaning, the type
 * whitelist and the upload classification; a second copy would drift (the exact failure this wave's extraction prevents). Structural
 * guards in the house style of `MailingAttachmentStoreTripwireTest`.
 */
class AttachmentRulesSingleSourceTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun code(name: String): String = SourceScan.blank(files.single { it.name == name }.readText())

        test("there is exactly one sniffer, one zip inspector and one name cleaning") {
            // (TravelExpenseDocumentSniffer is the receipts domain's own, older component and not part of the message-attachment rules.)
            val sniffers =
                files.filter {
                    it.name.contains("Attachment") &&
                        Regex("""\bobject \w*Sniffer\b""").containsMatchIn(SourceScan.blank(it.readText()))
                }
            sniffers.map { it.name } shouldBe listOf("AttachmentSniffer.kt")
            val zipInspectors =
                files.filter {
                    it.name.contains("Attachment") &&
                        Regex("""\bobject \w*Zip\b""").containsMatchIn(SourceScan.blank(it.readText()))
                }
            zipInspectors.map { it.name } shouldBe listOf("AttachmentZip.kt")
            val nameCleaners =
                files.filter {
                    Regex(
                        """\bfun \w*[sS]anitize\w*AttachmentName\w*\(""",
                    ).containsMatchIn(SourceScan.blank(it.readText()))
                }
            nameCleaners.map { it.name } shouldBe listOf("AttachmentNames.kt")
        }

        test("only the shared support file opens a ZIP/OOXML/ODF in the routes: the routes classify through classifyAttachmentUpload") {
            listOf("MailingAttachmentRoutes.kt", "DirectMessageAttachmentRoutes.kt").forEach { name ->
                withClue("$name must call the shared classification") { code(name).contains("classifyAttachmentUpload(") shouldBe true }
                withClue("$name must not talk to the sniffer or ZIP code itself") {
                    listOf("AttachmentSniffer", "AttachmentZip", "ZipFile", "ZipInputStream").none { code(name).contains(it) } shouldBe true
                }
            }
        }

        test("the direct-message routes never send NOT_DRAFT (a mailing-only code); the mailing routes never send the DM-only codes") {
            code("DirectMessageAttachmentRoutes.kt").contains("NOT_DRAFT") shouldBe false
            val mailing = code("MailingAttachmentRoutes.kt")
            listOf("RECIPIENT_UNAVAILABLE", "PENDING_LIMIT").forEach { mailing.contains(it) shouldBe false }
        }
    })
