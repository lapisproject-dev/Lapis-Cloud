package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.232 -- structural guards of the mailing attachments, in the house style of `GalleryTripwireTest`: they read the production source and
 * fail the moment a rule that no behavioural test can see is broken.
 */
class MailingAttachmentStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun usersOf(token: String): Set<String> = files.filter { SourceScan.blank(it.readText()).contains(token) }.map { it.name }.toSet()

        test(
            "MailingMessageAttachmentTable is touched only by the store, the DSGVO contributor (coveredTables) and its own generated file",
        ) {
            usersOf("MailingMessageAttachmentTable") shouldBe
                setOf("MailingAttachmentStore.kt", "CommunicationPersonalData.kt", "MailingMessageAttachmentTable.kt")
        }

        test("the contributor does not write the table itself and only reads uploader rows") {
            val code = SourceScan.blank(files.single { it.name == "CommunicationPersonalData.kt" }.readText())
            listOf(".insert", ".update(", ".deleteWhere").forEach { code.contains("MailingMessageAttachmentTable$it") shouldBe false }
        }

        test("every code path that deletes a mailing message also calls the attachment hook in the same file") {
            val deleters =
                files.filter { f ->
                    Regex("""MailingMessageTable\.(deleteWhere|deleteAll|delete)\b""").containsMatchIn(SourceScan.blank(f.readText()))
                }
            // today the set is EMPTY (no path deletes a mailing message); it must stay empty or call the hook
            deleters.filterNot { SourceScan.blank(it.readText()).contains("deleteAllForMessageInTx") }.map { it.name }.shouldBeEmpty()
        }

        test("the attachment code writes no file name, storage key or path into a log line") {
            val sources =
                files.filter {
                    it.name.startsWith("MailingAttachment") ||
                        it.name.startsWith("Attachment") ||
                        it.name == "MailingDeliveryWorker.kt" ||
                        it.name == "JakartaMailTransport.kt"
                }
            (sources.size >= 6) shouldBe true
            val offenders =
                sources.flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> line.contains("logger.") }
                        .filter { (_, line) ->
                            listOf("fileName", "originalFilename", "storageKey", "stored.", "attachment.", ".path", "canonical").any {
                                line.contains(it)
                            }
                        }.map { (index, _) -> "${file.name}:${index + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test("IMailingService has no RPC method for attachments; the method order is the V1.9.231 one plus deleteMailingList (V1.9.234)") {
            val shared =
                listOf(
                    File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IMailingService.kt"),
                    File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IMailingService.kt"),
                ).first { it.exists() }
            val methods = Regex("""suspend fun (\w+)""").findAll(SourceScan.blank(shared.readText())).map { it.groupValues[1] }.toList()
            methods shouldBe
                listOf(
                    "listMailingLists",
                    "createMailingList",
                    "subscribe",
                    "unsubscribe",
                    "adminSubscribeMember",
                    "listSubscribers",
                    "createDraftMessage",
                    "listMailingMessages",
                    "createDraftMessageHtml",
                    "previewMailingMessage",
                    "previewMailingHtml",
                    "getMailingDeliveryMode",
                    "sendMailingMessage",
                    "setTrackingConsent",
                    "mailingMessageStats",
                    "mailingSendEstimate",
                    "mailingSendProgress",
                    "createDynamicMailingList",
                    "updateMailingListRule",
                    "previewDynamicRecipients",
                    "optOutOfDynamicList",
                    "revokeOptOut",
                    // V1.9.234: the only addition since V1.9.231 -- deleting a MANUAL list (no attachment RPC).
                    "deleteMailingList",
                )
        }

        test("the attachment routes are BOARD/ADMIN only, same-origin protected where they write, and never answer with free text") {
            val code = SourceScan.blank(files.single { it.name == "MailingAttachmentRoutes.kt" }.readText())
            Regex("""isSameOriginRequest""").findAll(code).count() shouldBe 2 // upload and delete
            Regex("""ATTACHMENT_BOARD_ROLES""").findAll(code).count() shouldBe 4 // declaration + the three routes
            code.contains("call.respond(HttpStatusCode.BadRequest, \"") shouldBe false
        }
    })
