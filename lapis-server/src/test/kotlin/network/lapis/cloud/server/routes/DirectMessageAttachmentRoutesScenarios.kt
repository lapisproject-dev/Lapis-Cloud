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
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DirectMessageAttachmentTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.messaging.DirectMessageAttachmentPendingPoller
import network.lapis.cloud.server.messaging.DirectMessageAttachmentStore
import network.lapis.cloud.server.rpc.DirectMessageService
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachmentRules
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.DirectMessageAttachmentsUnavailableException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
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

private const val DM_CANONICAL_ORIGIN = "https://cloud.example.org"

/** A file that the sniffer accepts as a PDF and that is exactly [size] bytes long. */
private fun dmPdfOfSize(size: Int): ByteArray = ByteArray(size).also { F.PDF.copyInto(it) }

/**
 * V1.9.233 -- security and behaviour coverage of [registerDirectMessageAttachmentRoutes] and of
 * `DirectMessageService.sendDirectMessageWithAttachments` on H2 and on a real PostgreSQL (the row-lock races are the point of the
 * Postgres lane). Members have real sessions (`Authorization: Bearer`), the spec depends on no seed data and never calls `module()`.
 * Two helper routes (`/test/send`, `/test/page`) call the RPC service with the same session. The storage root is a fresh temp directory.
 */
abstract class DirectMessageAttachmentRoutesScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val storageRoot: File = Files.createTempDirectory("dm-attachments").toFile()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            try {
                transaction {
                    DirectMessageAttachmentTable.deleteWhere {
                        (uploaderId inList createdMemberIds) or (recipientId inList createdMemberIds)
                    }
                    DirectMessageTable.deleteWhere { (senderId inList createdMemberIds) or (recipientId inList createdMemberIds) }
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

        fun newActor(
            role: AccountRole = AccountRole.MEMBER,
            status: MemberStatus = MemberStatus.ACTIVE,
            anonymized: Boolean = false,
        ): Actor {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DM Attachment Test"
                    it[email] = "dm-attachment-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                    if (anonymized) it[anonymizedAt] = DbClock.nowLocalDateTime()
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

        fun ApplicationTestBuilder.mount(
            uploadLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
            downloadLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
        ) {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    registerDirectMessageAttachmentRoutes(
                        storageRoot = storageRoot,
                        uploadLimiter = uploadLimiter,
                        downloadLimiter = downloadLimiter,
                        canonicalOrigin = DM_CANONICAL_ORIGIN,
                    )
                    // Helper routes: the real RPC service under the same bearer session.
                    post("/test/send") {
                        val to = call.request.queryParameters["to"]!!
                        val ids =
                            call.request.queryParameters
                                .getAll("id")
                                .orEmpty()
                        try {
                            call.respond(
                                DirectMessageService(
                                    call,
                                ).sendDirectMessageWithAttachments(recipientId = to, body = "Hallo", attachmentIds = ids),
                            )
                        } catch (e: DirectMessageAttachmentsUnavailableException) {
                            call.respondText("attachments", status = HttpStatusCode.Conflict)
                        } catch (e: ConflictException) {
                            call.respondText("conflict", status = HttpStatusCode.Conflict)
                        }
                    }
                    get("/test/page/{other}") {
                        call.respond(
                            DirectMessageService(call).listConversationPage(
                                otherMemberId = call.parameters["other"]!!,
                                beforeSentAt = null,
                                beforeId = null,
                                limit = 50,
                            ),
                        )
                    }
                }
            }
        }

        fun multipart(
            recipient: Uuid?,
            bytes: ByteArray,
            fileName: String,
            declaredType: String,
            recipientFirst: Boolean,
            encodedName: Boolean = false,
        ) = MultiPartFormDataContent(
            formData {
                fun file() =
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(
                                HttpHeaders.ContentDisposition,
                                if (encodedName) {
                                    "filename*=UTF-8''" +
                                        java.net.URLEncoder
                                            .encode(fileName, "UTF-8")
                                            .replace("+", "%20")
                                } else {
                                    "filename=\"$fileName\""
                                },
                            )
                            append(HttpHeaders.ContentType, declaredType)
                        },
                    )
                if (recipient != null && recipientFirst) append("recipientId", recipient.toString())
                file()
                if (recipient != null && !recipientFirst) append("recipientId", recipient.toString())
            },
        )

        suspend fun HttpClient.upload(
            actor: Actor?,
            recipient: Uuid?,
            bytes: ByteArray = F.PDF,
            fileName: String = "anhang.pdf",
            declaredType: String = "application/pdf",
            origin: String? = null,
            recipientFirst: Boolean = true,
            encodedName: Boolean = false,
        ): HttpResponse =
            post("/api/direct-messages/attachments") {
                if (actor != null) header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                if (origin != null) header(HttpHeaders.Origin, origin)
                setBody(multipart(recipient, bytes, fileName, declaredType, recipientFirst, encodedName))
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

        suspend fun HttpClient.download(
            actor: Actor?,
            id: Any,
        ): HttpResponse =
            get("/api/direct-messages/attachments/$id/download") {
                if (actor !=
                    null
                ) {
                    header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                }
            }

        suspend fun HttpClient.remove(
            actor: Actor,
            id: Any,
            origin: String? = null,
        ): HttpResponse =
            delete("/api/direct-messages/attachments/$id") {
                header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                if (origin != null) header(HttpHeaders.Origin, origin)
            }

        suspend fun HttpClient.send(
            actor: Actor,
            to: Uuid,
            ids: List<Uuid>,
        ): HttpResponse =
            post("/test/send") {
                header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                url {
                    parameters.append("to", to.toString())
                    ids.forEach { parameters.append("id", it.toString()) }
                }
            }

        fun rowOf(id: Uuid) =
            transaction { DirectMessageAttachmentTable.selectAll().where { DirectMessageAttachmentTable.id eq id }.single() }

        fun pendingCount(uploader: Actor): Long =
            transaction {
                DirectMessageAttachmentTable.selectAll().where { DirectMessageAttachmentTable.uploaderId eq uploader.id }.count()
            }

        fun messageCount(sender: Actor): Long =
            transaction { DirectMessageTable.selectAll().where { DirectMessageTable.senderId eq sender.id }.count() }

        fun filesOf(uploader: Actor): Int = storageRoot.resolve("dm/${uploader.id}").listFiles()?.size ?: 0

        test("happy path: upload pending, send with the id, both sides download the same bytes with hardened headers, the page lists it") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                // The client claims text/html; the stored and served type is the server's.
                val created = client.upload(alice, bob.id, fileName = "Einladung Käfer.pdf", declaredType = "text/html")
                created.status shouldBe HttpStatusCode.Created
                val id = created.attachmentId()
                val row = rowOf(id)
                row[DirectMessageAttachmentTable.mimeType] shouldBe "application/pdf"
                row[DirectMessageAttachmentTable.messageId] shouldBe null
                row[DirectMessageAttachmentTable.storageKey] shouldBe "dm/${alice.id}/$id.bin"
                storageRoot.resolve(row[DirectMessageAttachmentTable.storageKey]).exists() shouldBe true

                // pending: only the uploader may download it
                client.download(alice, id).status shouldBe HttpStatusCode.OK
                client.download(bob, id).status shouldBe HttpStatusCode.NotFound

                val sent = client.send(alice, bob.id, listOf(id))
                sent.status shouldBe HttpStatusCode.OK
                sent.bodyAsText() shouldContain "Einladung Käfer.pdf"
                rowOf(id)[DirectMessageAttachmentTable.messageId] shouldBe
                    Uuid.parse(
                        Json
                            .parseToJsonElement(sent.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content,
                    )

                for (viewer in listOf(alice, bob)) {
                    val download = client.download(viewer, id)
                    download.status shouldBe HttpStatusCode.OK
                    download.bodyAsBytes().toList() shouldBe F.PDF.toList()
                    download.headers[HttpHeaders.ContentType] shouldBe "application/pdf"
                    download.headers[HttpHeaders.ContentDisposition]!! shouldContain "attachment"
                    download.headers[HttpHeaders.ContentDisposition]!! shouldContain "filename*=UTF-8''Einladung%20K%C3%A4fer.pdf"
                    download.headers["X-Content-Type-Options"] shouldBe "nosniff"
                    download.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                    download.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                    download.headers["Content-Security-Policy"] shouldBe "default-src 'none'; sandbox"
                }
                val page = client.get("/test/page/${bob.id}") { header(HttpHeaders.Authorization, "Bearer ${alice.token}") }.bodyAsText()
                page shouldContain "\"fileName\":\"Einladung Käfer.pdf\""
                // sent: no longer deletable
                client.remove(alice, id).status shouldBe HttpStatusCode.NotFound
                storageRoot.resolve("dm/${alice.id}/$id.bin").exists() shouldBe true
            }
        }

        test("a send without ids behaves exactly like a plain send, and a pending file can be deleted (row and file)") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                client.send(alice, bob.id, emptyList()).status shouldBe HttpStatusCode.OK
                val id = client.upload(alice, bob.id).attachmentId()
                filesOf(alice) shouldBe 1
                client.remove(alice, id).status shouldBe HttpStatusCode.NoContent
                pendingCount(alice) shouldBe 0L
                filesOf(alice) shouldBe 0
            }
        }

        test("limits: the 6th pending file for one pair is TOO_MANY, 10 MiB + 1 is FILE_TOO_LARGE and leaves no file") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                repeat(AttachmentRules.MAX_FILES) { index ->
                    client.upload(alice, bob.id, fileName = "a$index.pdf").status shouldBe
                        HttpStatusCode.Created
                }
                val sixth = client.upload(alice, bob.id, fileName = "sechs.pdf")
                sixth.status shouldBe HttpStatusCode.Conflict
                sixth.code() shouldBe "TOO_MANY"
                filesOf(alice) shouldBe AttachmentRules.MAX_FILES

                val carol = newActor()
                val tooLarge = client.upload(alice, carol.id, dmPdfOfSize((AttachmentRules.MAX_FILE_BYTES + 1).toInt()))
                tooLarge.status shouldBe HttpStatusCode.PayloadTooLarge
                tooLarge.code() shouldBe "FILE_TOO_LARGE"
                filesOf(alice) shouldBe AttachmentRules.MAX_FILES
                client.upload(alice, carol.id, dmPdfOfSize(AttachmentRules.MAX_FILE_BYTES.toInt())).status shouldBe HttpStatusCode.Created
            }
        }

        test("limits: more than 20 MiB for one pair is TOTAL_TOO_LARGE") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val ten = dmPdfOfSize(AttachmentRules.MAX_FILE_BYTES.toInt())
                client.upload(alice, bob.id, ten, "eins.pdf").status shouldBe HttpStatusCode.Created
                client.upload(alice, bob.id, ten, "zwei.pdf").status shouldBe HttpStatusCode.Created
                val third = client.upload(alice, bob.id, F.PDF, "drei.pdf")
                third.status shouldBe HttpStatusCode.Conflict
                third.code() shouldBe "TOTAL_TOO_LARGE"
            }
        }

        test("limits: more than 20 pending files across recipients is PENDING_LIMIT") {
            testApplication {
                mount()
                val alice = newActor()
                val recipients = (1..5).map { newActor() }
                recipients.take(4).forEachIndexed { r, bob ->
                    repeat(AttachmentRules.MAX_FILES) { index ->
                        client.upload(alice, bob.id, fileName = "r$r-$index.pdf").status shouldBe HttpStatusCode.Created
                    }
                }
                val over = client.upload(alice, recipients[4].id, fileName = "ueber.pdf")
                over.status shouldBe HttpStatusCode.Conflict
                over.code() shouldBe "PENDING_LIMIT"
                pendingCount(alice) shouldBe 20L
            }
        }

        test("limits: SENT attachments count against a rolling 24 h budget per uploader, the upload-send loop is capped") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val ten = dmPdfOfSize(AttachmentRules.MAX_FILE_BYTES.toInt())
                repeat(10) { round ->
                    val a = client.upload(alice, bob.id, ten, "a$round.pdf")
                    a.status shouldBe HttpStatusCode.Created
                    val b = client.upload(alice, bob.id, ten, "b$round.pdf")
                    b.status shouldBe HttpStatusCode.Created
                    client.send(alice, bob.id, listOf(a.attachmentId(), b.attachmentId())).status shouldBe HttpStatusCode.OK
                }
                val filesBefore = filesOf(alice)
                val over = client.upload(alice, bob.id, ten, "ueber.pdf")
                over.status shouldBe HttpStatusCode.Conflict
                over.code() shouldBe "PENDING_LIMIT"
                filesOf(alice) shouldBe filesBefore
                pendingCount(alice) shouldBe 20L
            }
        }

        test("a send with more than 5 ids, a duplicated id or a malformed id fails uniformly and creates no message") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val id = client.upload(alice, bob.id).attachmentId()
                client.send(alice, bob.id, List(6) { Uuid.random() }).status shouldBe HttpStatusCode.Conflict
                client.send(alice, bob.id, listOf(id, id)).status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/send") {
                        header(HttpHeaders.Authorization, "Bearer ${alice.token}")
                        url {
                            parameters.append("to", bob.id.toString())
                            parameters.append("id", "no-uuid")
                        }
                    }.status shouldBe HttpStatusCode.Conflict
                messageCount(alice) shouldBe 0L
                rowOf(id)[DirectMessageAttachmentTable.messageId] shouldBe null
            }
        }

        test(
            "TYPE refusals: exe, html, svg, zip and no extension are 415 TYPE_NOT_ALLOWED; pdf with PNG bytes is CONTENT_MISMATCH; no file stays",
        ) {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                listOf("virus.exe", "seite.html", "bild.svg", "archiv.zip", "ohne-endung").forEach { name ->
                    val response = client.upload(alice, bob.id, F.PDF, name)
                    response.status shouldBe HttpStatusCode.UnsupportedMediaType
                    response.code() shouldBe "TYPE_NOT_ALLOWED"
                }
                val mismatch = client.upload(alice, bob.id, F.PNG, "falsch.pdf")
                mismatch.status shouldBe HttpStatusCode.UnsupportedMediaType
                mismatch.code() shouldBe "CONTENT_MISMATCH"
                val macro = client.upload(alice, bob.id, F.docx(listOf("word/vbaProject.bin" to byteArrayOf(1))), "makro.docx")
                macro.status shouldBe HttpStatusCode.UnsupportedMediaType
                macro.code() shouldBe "MACRO_OR_EMBEDDED"
                pendingCount(alice) shouldBe 0L
                filesOf(alice) shouldBe 0
            }
        }

        test("names: traversal is neutralised, a unicode duplicate is DUPLICATE_NAME, reserved names get a prefix") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val traversal = client.upload(alice, bob.id, fileName = "../../../etc/passwd.pdf")
                traversal.status shouldBe HttpStatusCode.Created
                val row = rowOf(traversal.attachmentId())
                row[DirectMessageAttachmentTable.originalFilename] shouldBe "passwd.pdf"
                row[DirectMessageAttachmentTable.storageKey].contains("..") shouldBe false

                val first = client.upload(alice, bob.id, fileName = "Café.pdf")
                first.status shouldBe HttpStatusCode.Created
                // NFD spelling and different case of the same name
                val duplicate = client.upload(alice, bob.id, fileName = "CAFÉ.PDF")
                duplicate.status shouldBe HttpStatusCode.Conflict
                duplicate.code() shouldBe "DUPLICATE_NAME"

                val reserved = client.upload(alice, bob.id, fileName = "CON.pdf")
                reserved.status shouldBe HttpStatusCode.Created
                rowOf(reserved.attachmentId())[DirectMessageAttachmentTable.originalFilename] shouldBe "_CON.pdf"
                client.upload(alice, bob.id, fileName = ".pdf").code() shouldBe "INVALID_NAME"
            }
        }

        test("error bodies carry only the fixed code, never the file name") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val body = client.upload(alice, bob.id, F.PDF, "geheim-<script>.exe").bodyAsText()
                body shouldBe "{\"code\":\"TYPE_NOT_ALLOWED\"}"
                body shouldNotContain "geheim"
            }
        }

        test("authz: a third member, BOARD and ADMIN get the SAME 404 as for a nonexistent id; no role bypass") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val id = client.upload(alice, bob.id).attachmentId()
                client.send(alice, bob.id, listOf(id)).status shouldBe HttpStatusCode.OK
                val unknown = client.download(bob, Uuid.random())
                unknown.status shouldBe HttpStatusCode.NotFound
                val unknownBody = unknown.bodyAsText()
                for (role in listOf(AccountRole.MEMBER, AccountRole.BOARD, AccountRole.ADMIN, AccountRole.TREASURER)) {
                    val outsider = newActor(role)
                    val response = client.download(outsider, id)
                    response.status shouldBe HttpStatusCode.NotFound
                    response.bodyAsText() shouldBe unknownBody
                    response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                    client.remove(outsider, id).status shouldBe HttpStatusCode.NotFound
                }
                client.download(bob, "not-a-uuid").bodyAsText() shouldBe unknownBody
            }
        }

        test(
            "authz: a foreign pending upload is 404 on download and delete (and stays); FRIEND and APPLICATION cannot upload or download",
        ) {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val mallory = newActor()
                val id = client.upload(alice, bob.id).attachmentId()
                client.download(mallory, id).status shouldBe HttpStatusCode.NotFound
                client.remove(mallory, id).status shouldBe HttpStatusCode.NotFound
                client.download(bob, id).status shouldBe HttpStatusCode.NotFound
                pendingCount(alice) shouldBe 1L

                listOf(MemberStatus.FRIEND, MemberStatus.APPLICATION).forEach { status ->
                    val limited = newActor(status = status)
                    val upload = client.upload(limited, bob.id)
                    upload.status shouldBe HttpStatusCode.Forbidden
                    upload.code() shouldBe "FORBIDDEN"
                    client.download(limited, id).status shouldBe HttpStatusCode.NotFound
                }
                // an inactive PARTICIPANT of a sent message loses access too
                client.send(alice, bob.id, listOf(id)).status shouldBe HttpStatusCode.OK
                transaction { MemberTable.update({ MemberTable.id eq bob.id }) { it[status] = MemberStatus.WITHDRAWN } }
                client.download(bob, id).status shouldBe HttpStatusCode.NotFound
                transaction { MemberTable.update({ MemberTable.id eq bob.id }) { it[status] = MemberStatus.ACTIVE } }
            }
        }

        test("send: a foreign, unknown, other-recipient or already-attached id fails the whole send; no message, the id stays pending") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val carol = newActor()
                val mallory = newActor()
                val mine = client.upload(alice, bob.id, fileName = "mine.pdf").attachmentId()
                val foreign = client.upload(mallory, bob.id, fileName = "foreign.pdf").attachmentId()
                val forCarol = client.upload(alice, carol.id, fileName = "carol.pdf").attachmentId()

                client.send(alice, bob.id, listOf(mine, foreign)).status shouldBe HttpStatusCode.Conflict
                // A file problem is its OWN exception type; a send to oneself stays a plain conflict (the client tells them apart).
                client.send(alice, bob.id, listOf(mine, foreign)).bodyAsText() shouldBe "attachments"
                client.send(alice, alice.id, listOf(mine)).bodyAsText() shouldBe "conflict"
                client.send(alice, bob.id, listOf(mine, Uuid.random())).status shouldBe HttpStatusCode.Conflict
                client.send(alice, bob.id, listOf(mine, forCarol)).status shouldBe HttpStatusCode.Conflict
                messageCount(alice) shouldBe 0L
                rowOf(mine)[DirectMessageAttachmentTable.messageId] shouldBe null
                rowOf(foreign)[DirectMessageAttachmentTable.messageId] shouldBe null

                client.send(alice, bob.id, listOf(mine)).status shouldBe HttpStatusCode.OK
                // the same id a second time
                client.send(alice, bob.id, listOf(mine)).status shouldBe HttpStatusCode.Conflict
                messageCount(alice) shouldBe 1L
            }
        }

        suspend fun HttpClient.pending(
            actor: Actor?,
            recipient: Any,
        ): HttpResponse =
            get("/api/direct-messages/attachments/pending") {
                if (actor != null) header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                url { parameters.append("recipientId", recipient.toString()) }
            }

        suspend fun HttpResponse.pendingFileNames(): List<String> =
            Json
                .parseToJsonElement(bodyAsText())
                .jsonArray
                .map { it.jsonObject["fileName"]!!.jsonPrimitive.content }

        test("pending list: a rebuilt composer finds the caller's own unsent uploads for ONE partner, in upload order, and nobody else's") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val carol = newActor()
                val first = client.upload(alice, bob.id, fileName = "Vertrag.pdf").attachmentId()
                client.upload(alice, bob.id, fileName = "Anlage.pdf").status shouldBe HttpStatusCode.Created
                client.upload(alice, carol.id, fileName = "ForCarol.pdf").status shouldBe HttpStatusCode.Created
                client.upload(bob, alice.id, fileName = "FromBob.pdf").status shouldBe HttpStatusCode.Created

                val listed = client.pending(alice, bob.id)
                listed.status shouldBe HttpStatusCode.OK
                listed.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                listed.pendingFileNames() shouldBe listOf("Vertrag.pdf", "Anlage.pdf")
                client.pending(bob, alice.id).pendingFileNames() shouldBe listOf("FromBob.pdf")
                client.pending(carol, bob.id).pendingFileNames() shouldBe emptyList()

                // The scenario of the report: the composer is gone, the same name is refused -- but the file can be listed and deleted.
                client.upload(alice, bob.id, fileName = "Vertrag.pdf").code() shouldBe "DUPLICATE_NAME"
                client.remove(alice, first).status shouldBe HttpStatusCode.NoContent
                client.pending(alice, bob.id).pendingFileNames() shouldBe listOf("Anlage.pdf")

                // a SENT file is no longer pending
                val sent = client.upload(alice, bob.id, fileName = "Gesendet.pdf").attachmentId()
                client.send(alice, bob.id, listOf(sent)).status shouldBe HttpStatusCode.OK
                client.pending(alice, bob.id).pendingFileNames() shouldBe listOf("Anlage.pdf")
            }
        }

        test("pending list: unauthenticated is 401, an inactive caller 403 and a missing or malformed partner 400 NO_FILE") {
            testApplication {
                mount()
                val alice = newActor()
                val withdrawn = newActor(status = MemberStatus.WITHDRAWN)
                client.pending(null, alice.id).status shouldBe HttpStatusCode.Unauthorized
                client.pending(withdrawn, alice.id).status shouldBe HttpStatusCode.Forbidden
                val malformed = client.pending(alice, "not-a-uuid")
                malformed.status shouldBe HttpStatusCode.BadRequest
                malformed.code() shouldBe "NO_FILE"
                val missing =
                    client.get("/api/direct-messages/attachments/pending") { header(HttpHeaders.Authorization, "Bearer ${alice.token}") }
                missing.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("listForMessages is chunked: far more ids than one IN list holds still resolve, and only the sent file is found") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val id = client.upload(alice, bob.id, fileName = "Eins.pdf").attachmentId()
                val sentResponse = client.send(alice, bob.id, listOf(id))
                val messageId =
                    Uuid.parse(
                        Json
                            .parseToJsonElement(sentResponse.bodyAsText())
                            .jsonObject["id"]!!
                            .jsonPrimitive.content,
                    )
                val ids = List(5_000) { Uuid.random() } + messageId
                val found = transaction { DirectMessageAttachmentStore.listForMessages(ids) }
                found.keys shouldBe setOf(messageId)
                found.getValue(messageId).map { it.fileName } shouldBe listOf("Eins.pdf")
            }
        }

        test("recipient: unknown, own, inactive and anonymized targets are RECIPIENT_UNAVAILABLE and no file is streamed to disk") {
            testApplication {
                mount()
                val alice = newActor()
                val withdrawn = newActor(status = MemberStatus.WITHDRAWN)
                val friend = newActor(status = MemberStatus.FRIEND)
                val anonymized = newActor(anonymized = true)
                listOf(Uuid.random(), alice.id, withdrawn.id, friend.id, anonymized.id).forEach { target ->
                    val response = client.upload(alice, target)
                    response.status shouldBe HttpStatusCode.Conflict
                    response.code() shouldBe "RECIPIENT_UNAVAILABLE"
                }
                pendingCount(alice) shouldBe 0L
                filesOf(alice) shouldBe 0
            }
        }

        test("a missing recipient field is 400 NO_FILE and nothing is stored") {
            testApplication {
                mount()
                val alice = newActor()
                val without = client.upload(alice, recipient = null)
                without.status shouldBe HttpStatusCode.BadRequest
                without.code() shouldBe "NO_FILE"
                pendingCount(alice) shouldBe 0L
                filesOf(alice) shouldBe 0
            }
        }

        test("the recipient field AFTER the file is refused (400 NO_FILE): the gate needs it first") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val after = client.upload(alice, bob.id, recipientFirst = false)
                after.status shouldBe HttpStatusCode.BadRequest
                after.code() shouldBe "NO_FILE"
                pendingCount(alice) shouldBe 0L
                filesOf(alice) shouldBe 0
            }
        }

        test("a malformed recipient field without any file is 400 NO_FILE") {
            testApplication {
                mount()
                val alice = newActor()
                val malformed =
                    client.post("/api/direct-messages/attachments") {
                        header(HttpHeaders.Authorization, "Bearer ${alice.token}")
                        setBody(MultiPartFormDataContent(formData { append("recipientId", "kein-uuid") }))
                    }
                malformed.status shouldBe HttpStatusCode.BadRequest
                malformed.code() shouldBe "NO_FILE"
                pendingCount(alice) shouldBe 0L
            }
        }

        test("CSRF: a foreign Origin is 403 INVALID_ORIGIN on upload and delete; the canonical origin passes") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val foreign = client.upload(alice, bob.id, origin = "https://evil.example")
                foreign.status shouldBe HttpStatusCode.Forbidden
                foreign.code() shouldBe "INVALID_ORIGIN"
                val ok = client.upload(alice, bob.id, origin = DM_CANONICAL_ORIGIN)
                ok.status shouldBe HttpStatusCode.Created
                val deleteForeign = client.remove(alice, ok.attachmentId(), origin = "https://evil.example")
                deleteForeign.status shouldBe HttpStatusCode.Forbidden
                deleteForeign.code() shouldBe "INVALID_ORIGIN"
                pendingCount(alice) shouldBe 1L
            }
        }

        test("anonymous download is 401 whatever the id; the download limiter answers 429 whatever the id") {
            testApplication {
                mount(downloadLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes))
                val alice = newActor()
                val bob = newActor()
                val id = client.upload(alice, bob.id).attachmentId()
                client.download(null, id).status shouldBe HttpStatusCode.Unauthorized
                client.download(null, Uuid.random()).status shouldBe HttpStatusCode.Unauthorized
                client.download(alice, id).status shouldBe HttpStatusCode.OK
                client.download(alice, Uuid.random()).status shouldBe HttpStatusCode.NotFound
                client.download(alice, id).status shouldBe HttpStatusCode.TooManyRequests
                client.download(alice, Uuid.random()).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("the upload limiter answers 429 RATE_LIMITED and stores nothing more") {
            testApplication {
                mount(uploadLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
                val alice = newActor()
                val bob = newActor()
                client.upload(alice, bob.id, fileName = "a.pdf").status shouldBe HttpStatusCode.Created
                val limited = client.upload(alice, bob.id, fileName = "b.pdf")
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.code() shouldBe "RATE_LIMITED"
                pendingCount(alice) shouldBe 1L
            }
        }

        test("parallel uploads for one pair never exceed 5 rows and leave no orphan files") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val responses =
                    coroutineScope { (1..12).map { index -> async { client.upload(alice, bob.id, fileName = "p$index.pdf") } }.awaitAll() }
                responses.count { it.status == HttpStatusCode.Created } shouldBe AttachmentRules.MAX_FILES
                responses.filter { it.status != HttpStatusCode.Created }.map { it.code() }.toSet() shouldContainExactly setOf("TOO_MANY")
                pendingCount(alice) shouldBe AttachmentRules.MAX_FILES.toLong()
                filesOf(alice) shouldBe AttachmentRules.MAX_FILES
            }
        }

        test("parallel sends with the same ids create exactly one message") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val ids = (1..3).map { client.upload(alice, bob.id, fileName = "s$it.pdf").attachmentId() }
                val responses = coroutineScope { (1..6).map { async { client.send(alice, bob.id, ids) } }.awaitAll() }
                responses.count { it.status == HttpStatusCode.OK } shouldBe 1
                responses.count { it.status == HttpStatusCode.Conflict } shouldBe 5
                messageCount(alice) shouldBe 1L
            }
        }

        test("the pending poller deletes pending uploads older than 24 h (row and file) and never a sent one or a young one") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val old = client.upload(alice, bob.id, fileName = "alt.pdf").attachmentId()
                val young = client.upload(alice, bob.id, fileName = "jung.pdf").attachmentId()
                val sent = client.upload(alice, bob.id, fileName = "gesendet.pdf").attachmentId()
                client.send(alice, bob.id, listOf(sent)).status shouldBe HttpStatusCode.OK
                transaction {
                    DirectMessageAttachmentTable.update({ DirectMessageAttachmentTable.id inList listOf(old, sent) }) {
                        it[createdAt] = kotlinx.datetime.LocalDateTime(2020, 1, 1, 0, 0, 0)
                    }
                }
                DirectMessageAttachmentPendingPoller(storageRoot = storageRoot).tick() shouldBe 1
                transaction { DirectMessageAttachmentTable.selectAll().where { DirectMessageAttachmentTable.id eq old }.count() } shouldBe
                    0L
                storageRoot.resolve("dm/${alice.id}/$old.bin").exists() shouldBe false
                rowOf(young)[DirectMessageAttachmentTable.id] shouldBe young
                storageRoot.resolve("dm/${alice.id}/$young.bin").exists() shouldBe true
                rowOf(sent)[DirectMessageAttachmentTable.id] shouldBe sent
                storageRoot.resolve("dm/${alice.id}/$sent.bin").exists() shouldBe true
            }
        }

        test("a missing file behind a row answers the same 404 (no file-system detail)") {
            testApplication {
                mount()
                val alice = newActor()
                val bob = newActor()
                val id = client.upload(alice, bob.id).attachmentId()
                val unknown = client.download(alice, Uuid.random()).bodyAsText()
                storageRoot.resolve("dm/${alice.id}/$id.bin").delete()
                val response = client.download(alice, id)
                response.status shouldBe HttpStatusCode.NotFound
                response.bodyAsText() shouldBe unknown
            }
        }
    })
