package network.lapis.cloud.server.messaging

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V1.9.233 -- structural guards of the direct-message attachments (house style of `MailingAttachmentStoreTripwireTest`): they read the
 * production source and fail the moment a rule that no behavioural test can see is broken.
 */
class DirectMessageAttachmentStoreTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()

        fun usersOf(token: String): Set<String> = files.filter { SourceScan.blank(it.readText()).contains(token) }.map { it.name }.toSet()

        fun code(name: String): String = SourceScan.blank(files.single { it.name == name }.readText())

        test(
            "DirectMessageAttachmentTable is touched only by the store, the DSGVO contributor (coveredTables) and its own generated file",
        ) {
            usersOf("DirectMessageAttachmentTable") shouldBe
                setOf("DirectMessageAttachmentStore.kt", "CommunicationPersonalData.kt", "DirectMessageAttachmentTable.kt")
        }

        test("the contributor does not write the table itself (it goes through the store) and only reads it for the export") {
            val source = code("CommunicationPersonalData.kt")
            listOf(".insert", ".update(", ".deleteWhere").forEach { source.contains("DirectMessageAttachmentTable$it") shouldBe false }
        }

        test("the direct-message attachment routes never read a role: there is NO BOARD/ADMIN bypass") {
            val routes = code("DirectMessageAttachmentRoutes.kt")
            listOf("AccountRole", ".role", "ATTACHMENT_BOARD_ROLES", "isPrivileged").forEach { token ->
                withClue("DirectMessageAttachmentRoutes.kt must not contain $token") { routes.contains(token) shouldBe false }
            }
            val store = code("DirectMessageAttachmentStore.kt")
            listOf("AccountRole", "AccountTable", "role").forEach { token ->
                withClue("DirectMessageAttachmentStore.kt must not contain $token") {
                    Regex("""\b$token\b""").containsMatchIn(store) shouldBe
                        false
                }
            }
        }

        test("the attachment code writes no file name, storage key or path into a log line") {
            val sources = files.filter { it.name.startsWith("DirectMessageAttachment") }
            (sources.size >= 4) shouldBe true
            val offenders =
                sources.flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> line.contains("logger.") }
                        .filter { (_, line) ->
                            listOf(
                                "fileName",
                                "originalFilename",
                                "storageKey",
                                "stored.",
                                "attachment.",
                                ".path",
                                "canonical",
                                "key",
                            ).any {
                                line.contains(it)
                            }
                        }.map { (index, _) -> "${file.name}:${index + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test(
            "IDirectMessageService only GREW at the end: the V1.9.36 order is unchanged and the new method is last (Kilua numbers by order)",
        ) {
            val shared =
                listOf(
                    File("lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IDirectMessageService.kt"),
                    File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IDirectMessageService.kt"),
                ).first { it.exists() }
            val methods = Regex("""suspend fun (\w+)""").findAll(SourceScan.blank(shared.readText())).map { it.groupValues[1] }.toList()
            methods shouldBe
                listOf(
                    "sendDirectMessage",
                    "listInbox",
                    "listConversation",
                    "markRead",
                    "unreadCount",
                    "listConversationPartners",
                    "listConversationPage",
                    "markConversationRead",
                    "sendDirectMessageWithAttachments",
                )
        }

        test("the send with attachments takes the member-row lock first and the message insert and the assignment share one transaction") {
            val service = code("DirectMessageService.kt")
            val start = service.indexOf("override suspend fun sendDirectMessageWithAttachments")
            val body = service.substring(start, service.indexOf("override suspend fun listInbox"))
            val lock = body.indexOf("requireActiveMembership(memberId = current.memberId, forUpdate = true)")
            val insert = body.indexOf("insertDirectMessage(")
            val attach = body.indexOf("attachPendingInTx(")
            (lock in 0 until insert) shouldBe true
            (insert in 0 until attach) shouldBe true
            Regex("""\btransaction\s*\{""").findAll(body).count() shouldBe 1
        }

        test("the upload route takes the same member-row lock before it checks the limits and inserts") {
            val routes = code("DirectMessageAttachmentRoutes.kt")
            val lock = routes.indexOf("requireActiveMembership(memberId = current.memberId, forUpdate = true)")
            val insert = routes.indexOf("insertPendingInTx(")
            (lock in 0 until insert) shouldBe true
        }
    })
