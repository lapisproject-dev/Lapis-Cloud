package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageAttachmentTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import network.lapis.cloud.server.attachments.AttachmentTestFiles as F

private const val CANONICAL_ORIGIN = "https://cloud.example.org"

/** A file that the sniffer accepts as a PDF and that is exactly [size] bytes long. */
private fun pdfOfSize(size: Int): ByteArray = ByteArray(size).also { F.PDF.copyInto(it) }

/**
 * V1.9.232 -- security and behaviour coverage of [registerMailingAttachmentRoutes] on H2 and on a real PostgreSQL (the row-lock races are
 * the point of the Postgres lane). Members, lists and messages are created directly with real sessions (`Authorization: Bearer`), so the
 * spec depends on no seed data and never calls `module()`. The storage root is a fresh temp directory per spec.
 */
abstract class MailingAttachmentRoutesScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdListIds = mutableListOf<Uuid>()
        val createdMessageIds = mutableListOf<Uuid>()
        val storageRoot: File = Files.createTempDirectory("mailing-attachments").toFile()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            try {
                transaction {
                    MailingMessageAttachmentTable.deleteWhere { MailingMessageAttachmentTable.mailingMessageId inList createdMessageIds }
                    MailingMessageTable.deleteWhere { MailingMessageTable.id inList createdMessageIds }
                    MailingListTable.deleteWhere { MailingListTable.id inList createdListIds }
                    SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            } finally {
                storageRoot.deleteRecursively()
                db.deactivate()
            }
        }

        class Actor(
            val id: Uuid,
            val token: String,
        )

        fun newActor(role: AccountRole = AccountRole.BOARD): Actor {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Mailing Attachment Test"
                    it[email] = "mailing-attachment-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return Actor(id, SessionStore.createSession(id).rawToken)
        }

        fun newMessage(
            author: Actor,
            status: MailingMessageStatus = MailingMessageStatus.DRAFT,
        ): Uuid {
            val listId = Uuid.random()
            val messageId = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[MailingListTable.id] = listId
                    it[name] = "Anhang-Testliste"
                    it[description] = null
                    it[createdBy] = author.id
                }
                MailingMessageTable.insert {
                    it[MailingMessageTable.id] = messageId
                    it[mailingListId] = listId
                    it[subject] = "Betreff"
                    it[bodyText] = "Text"
                    it[bodyHtml] = null
                    it[sentBy] = author.id
                    it[MailingMessageTable.status] = status
                }
            }
            createdListIds += listId
            createdMessageIds += messageId
            return messageId
        }

        fun setStatus(
            messageId: Uuid,
            status: MailingMessageStatus,
        ) {
            transaction { MailingMessageTable.update({ MailingMessageTable.id eq messageId }) { it[MailingMessageTable.status] = status } }
        }

        fun ApplicationTestBuilder.mount(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
        ) {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    registerMailingAttachmentRoutes(
                        storageRoot = storageRoot,
                        rateLimiter = limiter,
                        canonicalOrigin = CANONICAL_ORIGIN,
                    )
                }
            }
        }

        fun multipart(
            bytes: ByteArray,
            fileName: String,
            declaredType: String,
        ) = MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, declaredType)
                    },
                )
            },
        )

        suspend fun HttpClient.upload(
            actor: Actor?,
            messageId: Uuid,
            bytes: ByteArray = F.PDF,
            fileName: String = "anhang.pdf",
            declaredType: String = "application/pdf",
            origin: String? = null,
        ): HttpResponse =
            post("/api/mailing/messages/$messageId/attachments") {
                if (actor != null) header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody(multipart(bytes, fileName, declaredType))
            }

        suspend fun HttpResponse.attachmentId(): Uuid =
            Uuid.parse(
                Json
                    .parseToJsonElement(bodyAsText())
                    .jsonObject["attachmentId"]!!
                    .jsonPrimitive.content,
            )

        suspend fun HttpResponse.code(): String =
            Json
                .parseToJsonElement(bodyAsText())
                .jsonObject["code"]!!
                .jsonPrimitive.content

        fun rowOf(id: Uuid) =
            transaction { MailingMessageAttachmentTable.selectAll().where { MailingMessageAttachmentTable.id eq id }.single() }

        fun attachmentCount(messageId: Uuid): Long =
            transaction {
                MailingMessageAttachmentTable.selectAll().where { MailingMessageAttachmentTable.mailingMessageId eq messageId }.count()
            }

        fun filesUnder(messageId: Uuid): Int = storageRoot.resolve("mailing/$messageId").listFiles()?.size ?: 0

        test("happy path: upload, list, download with the server MIME type and hardened headers, delete") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                // The client claims text/html; the stored and served type is the server's.
                val created = client.upload(board, messageId, fileName = "Einladung Käfer.pdf", declaredType = "text/html")
                created.status shouldBe HttpStatusCode.Created
                val id = created.attachmentId()
                val row = rowOf(id)
                row[MailingMessageAttachmentTable.mimeType] shouldBe "application/pdf"
                row[MailingMessageAttachmentTable.originalFilename] shouldBe "Einladung Käfer.pdf"
                row[MailingMessageAttachmentTable.sizeBytes] shouldBe F.PDF.size.toLong()
                row[MailingMessageAttachmentTable.storageKey] shouldBe "mailing/$messageId/$id.bin"
                storageRoot.resolve(row[MailingMessageAttachmentTable.storageKey]).exists() shouldBe true

                val download =
                    client.get(
                        "/api/mailing/attachments/$id/download",
                    ) { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                download.status shouldBe HttpStatusCode.OK
                download.headers[HttpHeaders.ContentType] shouldBe "application/pdf"
                download.headers[HttpHeaders.ContentDisposition]!! shouldContain "attachment"
                download.headers[HttpHeaders.ContentDisposition]!! shouldContain "filename*=UTF-8''Einladung%20K%C3%A4fer.pdf"
                download.headers["X-Content-Type-Options"] shouldBe "nosniff"
                download.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                download.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"

                client
                    .delete("/api/mailing/attachments/$id") { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                    .status shouldBe HttpStatusCode.NoContent
                attachmentCount(messageId) shouldBe 0L
                filesUnder(messageId) shouldBe 0
            }
        }

        test("a text attachment is served with its charset") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val id =
                    client
                        .upload(
                            board,
                            messageId,
                            "Grüße".toByteArray(Charsets.UTF_8),
                            "notiz.txt",
                            "application/octet-stream",
                        ).attachmentId()
                rowOf(id)[MailingMessageAttachmentTable.textCharset] shouldBe "UTF-8"
                val download =
                    client.get(
                        "/api/mailing/attachments/$id/download",
                    ) { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                download.headers[HttpHeaders.ContentType]!!.lowercase() shouldBe "text/plain; charset=utf-8"
            }
        }

        test("a TYPE refusal: exe, html and svg names are 415 TYPE_NOT_ALLOWED, no row, no file") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                listOf("virus.exe", "seite.html", "bild.svg", "ohne-endung", "archiv.zip").forEach { name ->
                    val response = client.upload(board, messageId, F.PDF, name)
                    response.status shouldBe HttpStatusCode.UnsupportedMediaType
                    response.code() shouldBe "TYPE_NOT_ALLOWED"
                }
                attachmentCount(messageId) shouldBe 0L
                filesUnder(messageId) shouldBe 0
            }
        }

        test("a CONTENT refusal: .pdf with PNG bytes is 415 CONTENT_MISMATCH") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val response = client.upload(board, messageId, F.PNG, "falsch.pdf")
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
                response.code() shouldBe "CONTENT_MISMATCH"
                filesUnder(messageId) shouldBe 0
            }
        }

        test("a docx with macros or embedded objects is 415 MACRO_OR_EMBEDDED") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val response = client.upload(board, messageId, F.docx(listOf("word/vbaProject.bin" to byteArrayOf(1))), "makro.docx")
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
                response.code() shouldBe "MACRO_OR_EMBEDDED"
                filesUnder(messageId) shouldBe 0
            }
        }

        test("an invalid name is 400 INVALID_NAME") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val response = client.upload(board, messageId, F.PDF, ".pdf")
                response.status shouldBe HttpStatusCode.BadRequest
                response.code() shouldBe "INVALID_NAME"
                filesUnder(messageId) shouldBe 0
            }
        }

        test("path traversal in the name is neutralised: server-generated storage key") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val response = client.upload(board, messageId, fileName = "../../../etc/passwd.pdf")
                response.status shouldBe HttpStatusCode.Created
                val row = rowOf(response.attachmentId())
                row[MailingMessageAttachmentTable.originalFilename] shouldBe "passwd.pdf"
                row[MailingMessageAttachmentTable.storageKey].contains("..") shouldBe false
                storageRoot
                    .resolve(
                        row[MailingMessageAttachmentTable.storageKey],
                    ).canonicalPath
                    .startsWith(storageRoot.canonicalPath) shouldBe
                    true
            }
        }

        test("the 6th file is 409 TOO_MANY") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                repeat(AttachmentRules.MAX_FILES) { index ->
                    client.upload(board, messageId, fileName = "datei$index.pdf").status shouldBe HttpStatusCode.Created
                }
                val sixth = client.upload(board, messageId, fileName = "sechs.pdf")
                sixth.status shouldBe HttpStatusCode.Conflict
                sixth.code() shouldBe "TOO_MANY"
                attachmentCount(messageId) shouldBe 5L
                filesUnder(messageId) shouldBe 5
            }
        }

        test("10 MiB + 1 byte is 413 FILE_TOO_LARGE, exactly 10 MiB is accepted") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val tooLarge = client.upload(board, messageId, pdfOfSize((AttachmentRules.MAX_FILE_BYTES + 1).toInt()))
                tooLarge.status shouldBe HttpStatusCode.PayloadTooLarge
                tooLarge.code() shouldBe "FILE_TOO_LARGE"
                filesUnder(messageId) shouldBe 0
                client.upload(board, messageId, pdfOfSize(AttachmentRules.MAX_FILE_BYTES.toInt())).status shouldBe
                    HttpStatusCode.Created
            }
        }

        test("the total is capped at 20 MiB: two 10 MiB files fit exactly, a third byte-sized file does not") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val ten = pdfOfSize(AttachmentRules.MAX_FILE_BYTES.toInt())
                client.upload(board, messageId, ten, "a.pdf").status shouldBe HttpStatusCode.Created
                client.upload(board, messageId, ten, "b.pdf").status shouldBe HttpStatusCode.Created
                val third = client.upload(board, messageId, F.PDF, "c.pdf")
                third.status shouldBe HttpStatusCode.Conflict
                third.code() shouldBe "TOTAL_TOO_LARGE"
                attachmentCount(messageId) shouldBe 2L
                filesUnder(messageId) shouldBe 2
            }
        }

        test("three files of 8 MiB: the third is TOTAL_TOO_LARGE") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val eight = pdfOfSize(8 * 1024 * 1024)
                client.upload(board, messageId, eight, "a.pdf").status shouldBe HttpStatusCode.Created
                client.upload(board, messageId, eight, "b.pdf").status shouldBe HttpStatusCode.Created
                client.upload(board, messageId, eight, "c.pdf").code() shouldBe "TOTAL_TOO_LARGE"
            }
        }

        test("a duplicate name that differs only in case is 409 DUPLICATE_NAME") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                client.upload(board, messageId, fileName = "Plan.PDF").status shouldBe HttpStatusCode.Created
                val duplicate = client.upload(board, messageId, fileName = "plan.pdf")
                duplicate.status shouldBe HttpStatusCode.Conflict
                duplicate.code() shouldBe "DUPLICATE_NAME"
                filesUnder(messageId) shouldBe 1
            }
        }

        test("two file parts and no file part are both 400") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val two =
                    MultiPartFormDataContent(
                        formData {
                            append("a", F.PDF, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"") })
                            append("b", F.PDF, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"b.pdf\"") })
                        },
                    )
                val multiple =
                    client.post("/api/mailing/messages/$messageId/attachments") {
                        header(HttpHeaders.Authorization, "Bearer ${board.token}")
                        setBody(two)
                    }
                multiple.status shouldBe HttpStatusCode.BadRequest
                multiple.code() shouldBe "MULTIPLE_FILES"
                val none =
                    client.post("/api/mailing/messages/$messageId/attachments") {
                        header(HttpHeaders.Authorization, "Bearer ${board.token}")
                        setBody(MultiPartFormDataContent(formData { append("note", "no file") }))
                    }
                none.status shouldBe HttpStatusCode.BadRequest
                none.code() shouldBe "NO_FILE"
                attachmentCount(messageId) shouldBe 0L
                filesUnder(messageId) shouldBe 0
            }
        }

        test("authz: MEMBER and TREASURER get 403 on upload, delete and download; anonymous download is 401") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val attachmentId = client.upload(board, messageId).attachmentId()
                listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEach { role ->
                    val outsider = newActor(role)
                    val upload = client.upload(outsider, messageId)
                    upload.status shouldBe HttpStatusCode.Forbidden
                    upload.code() shouldBe "FORBIDDEN"
                    client
                        .delete("/api/mailing/attachments/$attachmentId") { header(HttpHeaders.Authorization, "Bearer ${outsider.token}") }
                        .status shouldBe HttpStatusCode.Forbidden
                    client
                        .get(
                            "/api/mailing/attachments/$attachmentId/download",
                        ) { header(HttpHeaders.Authorization, "Bearer ${outsider.token}") }
                        .status shouldBe HttpStatusCode.Forbidden
                }
                client.get("/api/mailing/attachments/$attachmentId/download").status shouldBe HttpStatusCode.Unauthorized
                attachmentCount(messageId) shouldBe 1L
            }
        }

        test("authz is checked before existence and status: an outsider gets 403 for a sent AND for an unknown message") {
            testApplication {
                mount()
                val board = newActor()
                val outsider = newActor(AccountRole.MEMBER)
                val sent = newMessage(board, MailingMessageStatus.SENT)
                client.upload(outsider, sent).status shouldBe HttpStatusCode.Forbidden
                client.upload(outsider, Uuid.random()).status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("CSRF: a foreign Origin is 403 INVALID_ORIGIN on upload and delete; the canonical origin passes") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val foreign = client.upload(board, messageId, origin = "https://evil.example")
                foreign.status shouldBe HttpStatusCode.Forbidden
                foreign.code() shouldBe "INVALID_ORIGIN"
                client.upload(board, messageId, origin = CANONICAL_ORIGIN).status shouldBe HttpStatusCode.Created
                val id =
                    transaction {
                        MailingMessageAttachmentTable.selectAll().single {
                            it[MailingMessageAttachmentTable.mailingMessageId] ==
                                messageId
                        }
                    }
                val attachmentId = id[MailingMessageAttachmentTable.id]
                val deleteForeign =
                    client.delete("/api/mailing/attachments/$attachmentId") {
                        header(HttpHeaders.Authorization, "Bearer ${board.token}")
                        header(HttpHeaders.Origin, "https://evil.example")
                    }
                deleteForeign.status shouldBe HttpStatusCode.Forbidden
                attachmentCount(messageId) shouldBe 1L
            }
        }

        test("upload and delete on QUEUED, SENT and FAILED are 409 NOT_DRAFT; an unknown message is 404") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val attachmentId = client.upload(board, messageId).attachmentId()
                listOf(MailingMessageStatus.QUEUED, MailingMessageStatus.SENT, MailingMessageStatus.FAILED).forEach { status ->
                    setStatus(messageId, status)
                    val upload = client.upload(board, messageId, fileName = "neu-$status.pdf")
                    upload.status shouldBe HttpStatusCode.Conflict
                    upload.code() shouldBe "NOT_DRAFT"
                    val delete =
                        client.delete(
                            "/api/mailing/attachments/$attachmentId",
                        ) { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                    delete.status shouldBe HttpStatusCode.Conflict
                    delete.code() shouldBe "NOT_DRAFT"
                }
                attachmentCount(messageId) shouldBe 1L
                // download of a sent message's attachment stays possible for the board
                client
                    .get("/api/mailing/attachments/$attachmentId/download") { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                    .status shouldBe HttpStatusCode.OK
                client.upload(board, Uuid.random()).status shouldBe HttpStatusCode.NotFound
                client
                    .delete("/api/mailing/attachments/${Uuid.random()}") { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("a file removed by retention (file_deleted_at) or missing on disk is 404 on download") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val id = client.upload(board, messageId).attachmentId()
                storageRoot.resolve(rowOf(id)[MailingMessageAttachmentTable.storageKey]).delete()
                client
                    .get("/api/mailing/attachments/$id/download") { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("the rate limiter answers 429 RATE_LIMITED and stores nothing") {
            testApplication {
                mount(limiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
                val board = newActor()
                val messageId = newMessage(board)
                client.upload(board, messageId, fileName = "a.pdf").status shouldBe HttpStatusCode.Created
                val limited = client.upload(board, messageId, fileName = "b.pdf")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.code() shouldBe "RATE_LIMITED"
                attachmentCount(messageId) shouldBe 1L
            }
        }

        test("error bodies carry only the fixed code, never the file name") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val body = client.upload(board, messageId, F.PDF, "geheim-<script>.exe").bodyAsText()
                body shouldBe "{\"code\":\"TYPE_NOT_ALLOWED\"}"
                body shouldNotContain "geheim"
            }
        }

        test("parallel uploads never exceed 5 files (row lock on the message) and leave no orphan files") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val statuses =
                    coroutineScope {
                        (1..12)
                            .map { index ->
                                async { client.upload(board, messageId, fileName = "p$index.pdf") }
                            }.awaitAll()
                    }
                statuses.count { it.status == HttpStatusCode.Created } shouldBe AttachmentRules.MAX_FILES
                statuses.filter { it.status != HttpStatusCode.Created }.map { it.code() }.toSet() shouldContainExactly setOf("TOO_MANY")
                attachmentCount(messageId) shouldBe AttachmentRules.MAX_FILES.toLong()
                filesUnder(messageId) shouldBe AttachmentRules.MAX_FILES
            }
        }

        test("parallel uploads never exceed 20 MiB in total") {
            testApplication {
                mount()
                val board = newActor()
                val messageId = newMessage(board)
                val eight = pdfOfSize(8 * 1024 * 1024)
                val responses =
                    coroutineScope {
                        (1..4)
                            .map { index ->
                                async { client.upload(board, messageId, eight, "g$index.pdf") }
                            }.awaitAll()
                    }
                responses.count { it.status == HttpStatusCode.Created } shouldBe 2
                attachmentCount(messageId) shouldBe 2L
                filesUnder(messageId) shouldBe 2
            }
        }

        test("an upload racing the DRAFT -> QUEUED transition is either part of what the send sees or refused with 409, never lost") {
            testApplication {
                mount()
                val board = newActor()
                repeat(3) {
                    val messageId = newMessage(board)
                    val queuedSnapshot =
                        java.util.concurrent.atomic
                            .AtomicReference<Set<Uuid>>()
                    val responses =
                        coroutineScope {
                            val uploads = (1..5).map { index -> async { client.upload(board, messageId, fileName = "r$index.pdf") } }
                            val send =
                                async {
                                    // exactly what sendMailingMessage does: the conditional update locks the message row, then it reads the attachments.
                                    transaction {
                                        val updated =
                                            MailingMessageTable.update(
                                                {
                                                    (MailingMessageTable.id eq messageId) and
                                                        (MailingMessageTable.status eq MailingMessageStatus.DRAFT)
                                                },
                                            ) { it[MailingMessageTable.status] = MailingMessageStatus.QUEUED }
                                        updated shouldBe 1
                                        queuedSnapshot.set(
                                            MailingMessageAttachmentTable
                                                .selectAll()
                                                .where { MailingMessageAttachmentTable.mailingMessageId eq messageId }
                                                .map { it[MailingMessageAttachmentTable.id] }
                                                .toSet(),
                                        )
                                    }
                                }
                            send.await()
                            uploads.awaitAll()
                        }
                    val accepted = responses.filter { it.status == HttpStatusCode.Created }.map { it.attachmentId() }.toSet()
                    val refused = responses.filter { it.status != HttpStatusCode.Created }
                    refused.forEach { it.status shouldBe HttpStatusCode.Conflict }
                    // Every accepted upload was committed before the send looked, and nothing was added afterwards.
                    accepted shouldBe queuedSnapshot.get()
                    val finalRows =
                        transaction {
                            MailingMessageAttachmentTable
                                .selectAll()
                                .where { MailingMessageAttachmentTable.mailingMessageId eq messageId }
                                .map { it[MailingMessageAttachmentTable.id] }
                                .toSet()
                        }
                    finalRows shouldBe accepted
                    filesUnder(messageId) shouldBe accepted.size
                }
            }
        }
    })
