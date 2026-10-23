package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
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
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.db.generated.TravelExpenseDocumentTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseDocumentRules
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.199 -- security and behaviour coverage of [registerTravelExpenseDocumentRoutes] on H2 and on a real PostgreSQL (the quota lock is the
 * point of the Postgres lane). Members are created directly with real sessions (`Authorization: Bearer`).
 */
abstract class TravelExpenseDocumentRoutesScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val storageRoot: File = Files.createTempDirectory("te-documents").toFile()
        val documentsDir = storageRoot.resolve("travel-expenses/documents")

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach {
            transaction { TravelExpenseDocumentTable.deleteAll() }
            documentsDir.deleteRecursively()
        }
        afterSpec {
            try {
                transaction {
                    TravelExpenseDocumentTable.deleteAll()
                    if (createdMemberIds.isNotEmpty()) {
                        SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                        AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    }
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
            role: AccountRole = AccountRole.BOARD,
            memberStatus: MemberStatus = MemberStatus.ACTIVE,
        ): Actor {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Dokument Testmitglied"
                    it[email] = "te-document-$id@example.org"
                    it[status] = memberStatus
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

        fun ApplicationTestBuilder.mount(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
        ) {
            application {
                install(ContentNegotiation) { json() }
                install(StatusPages) {
                    exception<UnauthenticatedException> {
                        call,
                        cause,
                        ->
                        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                    }
                }
                routing { registerTravelExpenseDocumentRoutes(storageRoot = storageRoot, rateLimiter = limiter) }
            }
        }

        val xlsx = TravelExpenseDocumentFixtures.xlsx()
        val xls = TravelExpenseDocumentFixtures.ole2(entries = listOf(TravelExpenseDocumentFixtures.OleEntry(name = "Workbook")))

        fun multipart(
            bytes: ByteArray,
            fileName: String,
            declaredType: String,
            title: String?,
            titleFirst: Boolean,
        ) = MultiPartFormDataContent(
            formData {
                fun fileEntry() =
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            append(HttpHeaders.ContentType, declaredType)
                        },
                    )
                if (title != null && titleFirst) append("title", title)
                fileEntry()
                if (title != null && !titleFirst) append("title", title)
            },
        )

        suspend fun HttpClient.upload(
            actor: Actor?,
            bytes: ByteArray = TravelExpenseDocumentFixtures.pdf(),
            fileName: String = "ordnung.pdf",
            declaredType: String = "application/pdf",
            title: String? = null,
            titleFirst: Boolean = true,
        ): HttpResponse =
            post("/api/travel-expenses/documents") {
                if (actor != null) header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                setBody(multipart(bytes, fileName, declaredType, title, titleFirst))
            }

        suspend fun HttpResponse.documentId(): Uuid =
            Uuid.parse(
                Json
                    .parseToJsonElement(bodyAsText())
                    .jsonObject["documentId"]!!
                    .jsonPrimitive.content,
            )

        fun rowOf(id: Uuid) = transaction { TravelExpenseDocumentTable.selectAll().where { TravelExpenseDocumentTable.id eq id }.single() }

        fun documentCount(): Long = transaction { TravelExpenseDocumentTable.selectAll().count() }

        fun filesOnDisk(): Int = documentsDir.listFiles()?.size ?: 0

        fun auditOf(id: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.TRAVEL_EXPENSE_DOCUMENT) and
                            (AuditLogEntryTable.entityId eq id)
                    }.orderBy(AuditLogEntryTable.sequenceNumber)
                    .map {
                        Triple(
                            it[AuditLogEntryTable.action],
                            it[AuditLogEntryTable.beforeSnapshot],
                            it[AuditLogEntryTable.afterSnapshot],
                        )
                    }
            }

        test("PDF, XLSX and XLS are accepted (201) and stored with the sniffed type, whatever the client declares") {
            testApplication {
                mount()
                val board = newActor()
                listOf(
                    Triple(TravelExpenseDocumentFixtures.pdf(), "a.pdf", TravelExpenseDocumentRules.MIME_PDF),
                    Triple(xlsx, "b.xlsx", TravelExpenseDocumentRules.MIME_XLSX),
                    Triple(xls, "c.xls", TravelExpenseDocumentRules.MIME_XLS),
                ).forEach { (bytes, name, mime) ->
                    val response = client.upload(board, bytes, fileName = name, declaredType = "application/octet-stream")
                    response.status shouldBe HttpStatusCode.Created
                    val row = rowOf(response.documentId())
                    row[TravelExpenseDocumentTable.mimeType] shouldBe mime
                    row[TravelExpenseDocumentTable.sizeBytes] shouldBe bytes.size.toLong()
                    row[TravelExpenseDocumentTable.uploadedBy] shouldBe board.id
                    storageRoot.resolve(row[TravelExpenseDocumentTable.storageKey]).exists() shouldBe true
                }
                documentCount() shouldBe 3L
            }
        }

        test("a text file renamed to .pdf is refused (415 unsupported_type), an EXE declared as PDF too; no row, no file") {
            testApplication {
                mount()
                val board = newActor()
                val fake = client.upload(board, "just text".toByteArray(), fileName = "x.pdf")
                fake.status shouldBe HttpStatusCode.UnsupportedMediaType
                fake.bodyAsText() shouldBe "unsupported_type"
                client.upload(board, byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0, 3, 0, 0, 0), fileName = "x.pdf").status shouldBe
                    HttpStatusCode.UnsupportedMediaType
                documentCount() shouldBe 0L
                filesOnDisk() shouldBe 0
            }
        }

        test("macro-enabled, encrypted and password-protected files give their own fixed token") {
            testApplication {
                mount()
                val board = newActor()
                val macro =
                    client.upload(
                        board,
                        TravelExpenseDocumentFixtures.xlsx(
                            extra = listOf(TravelExpenseDocumentFixtures.Part(name = "xl/vbaProject.bin", bytes = ByteArray(4))),
                        ),
                        fileName = "m.xlsx",
                    )
                macro.status shouldBe HttpStatusCode.UnsupportedMediaType
                macro.bodyAsText() shouldBe "macro_content"
                val pdf = client.upload(board, TravelExpenseDocumentFixtures.pdf(extra = "/Encrypt 4 0 R"), fileName = "e.pdf")
                pdf.status shouldBe HttpStatusCode.UnsupportedMediaType
                pdf.bodyAsText() shouldBe "encrypted"
                val ole =
                    client.upload(
                        board,
                        TravelExpenseDocumentFixtures.ole2(
                            entries = listOf(TravelExpenseDocumentFixtures.OleEntry(name = "EncryptionInfo")),
                        ),
                        fileName = "e.xlsx",
                    )
                ole.bodyAsText() shouldBe "encrypted"
                documentCount() shouldBe 0L
                filesOnDisk() shouldBe 0
            }
        }

        test("a file over 10 MiB is refused with 413 and leaves nothing behind") {
            testApplication {
                mount()
                val board = newActor()
                val big =
                    TravelExpenseDocumentFixtures.pdf() + ByteArray(TravelExpenseDocumentRules.MAX_BYTES.toInt()) { 'x'.code.toByte() }
                client.upload(board, big).status shouldBe HttpStatusCode.PayloadTooLarge
                documentCount() shouldBe 0L
                filesOnDisk() shouldBe 0
            }
        }

        test("the 11th document is refused with 409, nothing is stored for it") {
            testApplication {
                mount()
                val board = newActor()
                repeat(TravelExpenseDocumentRules.MAX_DOCUMENTS) { client.upload(board).status shouldBe HttpStatusCode.Created }
                client.upload(board).status shouldBe HttpStatusCode.Conflict
                documentCount() shouldBe TravelExpenseDocumentRules.MAX_DOCUMENTS.toLong()
                filesOnDisk() shouldBe TravelExpenseDocumentRules.MAX_DOCUMENTS
            }
        }

        test("parallel uploads never exceed the quota (lock on the settings row) and leave no orphan files") {
            testApplication {
                mount()
                val board = newActor()
                val statuses = coroutineScope { (1..13).map { async { client.upload(board).status } }.awaitAll() }
                statuses.count { it == HttpStatusCode.Created } shouldBe TravelExpenseDocumentRules.MAX_DOCUMENTS
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 3
                documentCount() shouldBe TravelExpenseDocumentRules.MAX_DOCUMENTS.toLong()
                filesOnDisk() shouldBe TravelExpenseDocumentRules.MAX_DOCUMENTS
            }
        }

        test("a MEMBER gets 403 on POST, PATCH and DELETE; anonymous gets 401 on POST and GET") {
            testApplication {
                mount()
                val board = newActor()
                val member = newActor(AccountRole.MEMBER)
                val id = client.upload(board).documentId()
                client.upload(member).status shouldBe HttpStatusCode.Forbidden
                client
                    .patch("/api/travel-expenses/documents/$id") {
                        header(HttpHeaders.Authorization, "Bearer ${member.token}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"title":"x"}""")
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .delete(
                        "/api/travel-expenses/documents/$id",
                    ) { header(HttpHeaders.Authorization, "Bearer ${member.token}") }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                client.upload(null).status shouldBe HttpStatusCode.Unauthorized
                client.get("/api/travel-expenses/documents/$id/download").status shouldBe HttpStatusCode.Unauthorized
                documentCount() shouldBe 1L
            }
        }

        test("a TREASURER cannot manage documents either (BOARD and ADMIN only)") {
            testApplication {
                mount()
                client.upload(newActor(AccountRole.TREASURER)).status shouldBe HttpStatusCode.Forbidden
                client.upload(newActor(AccountRole.ADMIN)).status shouldBe HttpStatusCode.Created
            }
        }

        test("any signed-in member downloads; the headers force an attachment and forbid sniffing") {
            testApplication {
                mount()
                val board = newActor()
                val member = newActor(AccountRole.MEMBER)
                val bytes = TravelExpenseDocumentFixtures.pdf()
                val id = client.upload(board, bytes, fileName = "Reisekostenordnung 2026.pdf").documentId()
                val response =
                    client.get(
                        "/api/travel-expenses/documents/$id/download",
                    ) { header(HttpHeaders.Authorization, "Bearer ${member.token}") }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsBytes().toList() shouldBe bytes.toList()
                response.headers[HttpHeaders.ContentDisposition]!!.shouldContain("attachment")
                response.headers[HttpHeaders.ContentDisposition]!!.shouldContain("filename*=UTF-8''Reisekostenordnung%202026.pdf")
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                response.headers[HttpHeaders.ContentType]!!.shouldContain("application/pdf")
            }
        }

        test("download is members only: GUEST, FRIEND, APPLICATION and WITHDRAWN get 403") {
            testApplication {
                mount()
                val id = client.upload(newActor()).documentId()
                for (s in listOf(MemberStatus.GUEST, MemberStatus.FRIEND, MemberStatus.APPLICATION, MemberStatus.WITHDRAWN)) {
                    val outsider = newActor(AccountRole.MEMBER, s)
                    client
                        .get(
                            "/api/travel-expenses/documents/$id/download",
                        ) { header(HttpHeaders.Authorization, "Bearer ${outsider.token}") }
                        .status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("unknown id: 404 on download, PATCH and DELETE; a malformed id is 400; a missing file is 404") {
            testApplication {
                mount()
                val board = newActor()
                val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                    header(
                        HttpHeaders.Authorization,
                        "Bearer ${board.token}",
                    )
                }
                client.get("/api/travel-expenses/documents/${Uuid.random()}/download", auth).status shouldBe HttpStatusCode.NotFound
                client.delete("/api/travel-expenses/documents/${Uuid.random()}", auth).status shouldBe HttpStatusCode.NotFound
                client
                    .patch("/api/travel-expenses/documents/${Uuid.random()}") {
                        auth()
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"title":"x"}""")
                    }.status shouldBe HttpStatusCode.NotFound
                client.get("/api/travel-expenses/documents/not-a-uuid/download", auth).status shouldBe HttpStatusCode.BadRequest
                val id = client.upload(board).documentId()
                storageRoot.resolve(rowOf(id)[TravelExpenseDocumentTable.storageKey]).delete()
                client.get("/api/travel-expenses/documents/$id/download", auth).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("hostile file names are neutralised: traversal, backslash, wrong extension; the storage path stays under the root") {
            testApplication {
                mount()
                val board = newActor()
                val traversal = client.upload(board, fileName = "../../etc/passwd.pdf").documentId()
                rowOf(traversal)[TravelExpenseDocumentTable.originalFilename] shouldBe "passwd.pdf"
                val backslash = client.upload(board, fileName = "x\\..\\y.pdf").documentId()
                rowOf(backslash)[TravelExpenseDocumentTable.originalFilename].shouldNotContain("\\")
                val wrongExtension = client.upload(board, xlsx, fileName = "tabelle.pdf").documentId()
                rowOf(wrongExtension)[TravelExpenseDocumentTable.originalFilename] shouldBe "tabelle.xlsx"
                val noExtension = client.upload(board, xls, fileName = "tabelle").documentId()
                rowOf(noExtension)[TravelExpenseDocumentTable.originalFilename] shouldBe "tabelle.xls"
                val unicode = client.upload(board, fileName = "Übersicht €.pdf").documentId()
                rowOf(unicode)[TravelExpenseDocumentTable.originalFilename] shouldBe "Übersicht €.pdf"
                listOf(traversal, backslash, wrongExtension, noExtension, unicode).forEach { id ->
                    val key = rowOf(id)[TravelExpenseDocumentTable.storageKey]
                    key shouldBe "travel-expenses/documents/$id.bin"
                    storageRoot.resolve(key).canonicalPath.startsWith(storageRoot.canonicalPath) shouldBe true
                }
            }
        }

        test("the title: given before or after the file part, else derived from the file name; control characters are dropped") {
            testApplication {
                mount()
                val board = newActor()
                val before = client.upload(board, title = "Reisekostenordnung", titleFirst = true).documentId()
                rowOf(before)[TravelExpenseDocumentTable.title] shouldBe "Reisekostenordnung"
                val after = client.upload(board, title = "Beschluss 12/2026", titleFirst = false).documentId()
                rowOf(after)[TravelExpenseDocumentTable.title] shouldBe "Beschluss 12/2026"
                val derived = client.upload(board, fileName = "Reisekosten_Ordnung_2026.pdf").documentId()
                rowOf(derived)[TravelExpenseDocumentTable.title] shouldBe "Reisekosten Ordnung 2026"
                val blank = client.upload(board, fileName = "x.pdf", title = "   ").documentId()
                rowOf(blank)[TravelExpenseDocumentTable.title] shouldBe "x"
                val control = client.upload(board, title = "Ord\u0007nung").documentId()
                rowOf(control)[TravelExpenseDocumentTable.title] shouldBe "Ordnung"
            }
        }

        test("an unknown form field is 400 and stores nothing") {
            testApplication {
                mount()
                val board = newActor()
                val response =
                    client.post("/api/travel-expenses/documents") {
                        header(HttpHeaders.Authorization, "Bearer ${board.token}")
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append("evil", "x")
                                    append(
                                        "file",
                                        TravelExpenseDocumentFixtures.pdf(),
                                        Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"") },
                                    )
                                },
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                documentCount() shouldBe 0L
                filesOnDisk() shouldBe 0
            }
        }

        test("rename (PATCH) changes the title, rejects an empty one and a non-JSON body") {
            testApplication {
                mount()
                val board = newActor()
                val id = client.upload(board, title = "Alt").documentId()

                fun patchBody(
                    body: String,
                    type: ContentType = ContentType.Application.Json,
                ) = suspend {
                    client.patch("/api/travel-expenses/documents/$id") {
                        header(HttpHeaders.Authorization, "Bearer ${board.token}")
                        header(HttpHeaders.ContentType, type.toString())
                        setBody(body)
                    }
                }
                patchBody("""{"title":"  Neu  "}""")().status shouldBe HttpStatusCode.NoContent
                rowOf(id)[TravelExpenseDocumentTable.title] shouldBe "Neu"
                patchBody("""{"title":"   "}""")().status shouldBe HttpStatusCode.BadRequest
                patchBody("not json")().status shouldBe HttpStatusCode.BadRequest
                patchBody("""{"title":"x"}""", ContentType.Text.Plain)().status shouldBe HttpStatusCode.UnsupportedMediaType
                rowOf(id)[TravelExpenseDocumentTable.title] shouldBe "Neu"
            }
        }

        test("delete removes the row and the file after the commit") {
            testApplication {
                mount()
                val board = newActor()
                val id = client.upload(board).documentId()
                val key = rowOf(id)[TravelExpenseDocumentTable.storageKey]
                client
                    .delete(
                        "/api/travel-expenses/documents/$id",
                    ) { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                    .status shouldBe
                    HttpStatusCode.NoContent
                documentCount() shouldBe 0L
                storageRoot.resolve(key).exists() shouldBe false
            }
        }

        test("upload, rename and delete each write one audit entry (CREATE, UPDATE, VOID) without any content or file name") {
            testApplication {
                mount()
                val board = newActor()
                val id = client.upload(board, fileName = "geheim-dateiname.pdf", title = "Ordnung").documentId()
                client
                    .patch("/api/travel-expenses/documents/$id") {
                        header(HttpHeaders.Authorization, "Bearer ${board.token}")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"title":"Ordnung neu"}""")
                    }.status shouldBe HttpStatusCode.NoContent
                client.delete("/api/travel-expenses/documents/$id") { header(HttpHeaders.Authorization, "Bearer ${board.token}") }
                val audit = auditOf(id)
                audit.map { it.first } shouldBe listOf(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.VOID)
                audit[0].third!!.shouldContain("Ordnung")
                audit[1].second!!.shouldContain("\"Ordnung\"")
                audit[1].third!!.shouldContain("Ordnung neu")
                audit[2].third shouldBe null
                audit.forEach { (_, before, after) ->
                    listOfNotNull(before, after).forEach {
                        it.shouldNotContain("geheim-dateiname")
                        it.shouldNotContain("%PDF")
                    }
                }
            }
        }

        test("the shared upload budget answers 429 once the limit is used up") {
            testApplication {
                mount(FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes))
                val board = newActor()
                repeat(3) { client.upload(board).status shouldBe HttpStatusCode.Created }
                client.upload(board).status shouldBe HttpStatusCode.TooManyRequests
                documentCount() shouldBe 3L
            }
        }
    })
