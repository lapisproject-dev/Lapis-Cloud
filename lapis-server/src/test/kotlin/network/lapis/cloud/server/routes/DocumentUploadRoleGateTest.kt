package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Welle "Treasurer Document Upload" regression: the real-world symptom (a TREASURER, e.g.
 * "Marc Levi Mousa" on the PdV instance, could no longer create folders or upload documents) was
 * a role-gate regression check on the HTTP upload route
 * (`POST /api/documents/{documentId}/versions`), not just the [network.lapis.cloud.server.rpc
 * .DocumentService] RPC layer -- see [DocumentRoutesGuestAccessTest] KDoc for why the two call
 * sites are tested separately even though they share [network.lapis.cloud.server.security
 * .canAccessDocumentAtLevel]. This test exercises the actual multipart upload route end to end
 * (not a direct-DB seed, unlike the download-side tests) because the role check under test sits
 * directly in [registerDocumentRoutes]'s route handler, before any file bytes are read.
 */
class DocumentUploadRoleGateTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdFolderIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdDocumentIds.isNotEmpty()) {
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList createdDocumentIds }
                    DocumentTable.deleteWhere { DocumentTable.id inList createdDocumentIds }
                }
                if (createdFolderIds.isNotEmpty()) {
                    DocumentFolderTable.deleteWhere { DocumentFolderTable.id inList createdFolderIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            status: MemberStatus,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Document-Upload-Role-Gate-Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createFolderAndDocument(
            creatorId: Uuid,
            accessLevel: DocumentAccessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
            isDeleted: Boolean = false,
        ): Uuid {
            val folderId = Uuid.random()
            val documentId = Uuid.random()
            transaction {
                DocumentFolderTable.insert {
                    it[id] = folderId
                    it[name] = "Upload-Role-Gate-Test-Ordner"
                    it[parentFolderId] = null
                }
                DocumentTable.insert {
                    it[id] = documentId
                    it[DocumentTable.folderId] = folderId
                    it[title] = "Upload-Role-Gate-Test-Dokument"
                    it[currentVersionId] = null
                    it[createdBy] = creatorId
                    it[createdAt] = LocalDateTime(2026, 9, 18, 9, 0)
                    it[DocumentTable.accessLevel] = accessLevel
                    it[DocumentTable.isDeleted] = isDeleted
                }
            }
            createdFolderIds += folderId
            createdDocumentIds += documentId
            return documentId
        }

        fun uploadBody(content: String) =
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        content.toByteArray(Charsets.UTF_8),
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"upload-role-gate-test.txt\"")
                        },
                    )
                },
            )

        /**
         * Review finding fix: previously ALL FOUR role assertions lived in one `test { ... }`
         * block, so a failure on the first assertion (e.g. BOARD) aborted the whole test and hid
         * whether the TREASURER assertion -- the actual regression this test exists to catch --
         * would have passed or failed. Split into one `test(...)` per role below via this shared
         * helper, so each role's outcome is reported independently. Also fixes the second finding
         * at this call site: `storageRoot` is now deleted in a `finally`, matching how
         * [afterSpec] already cleans up the member/folder/document rows created here -- previously
         * every test run leaked its uploaded bytes into the system temp directory.
         */
        suspend fun assertUploadRoleGate(
            role: AccountRole,
            expectedStatus: HttpStatusCode,
        ) {
            val storageRoot = Files.createTempDirectory("document-upload-role-gate-storage").toFile()
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) {
                            exception<UnauthenticatedException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                            }
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerDocumentRoutes(storageRoot) }
                    }

                    val creatorId =
                        createMember("upload-role-gate-${role.name.lowercase()}@example.org", role, MemberStatus.ACTIVE)
                    val docId = createFolderAndDocument(creatorId)
                    val response =
                        client.post("/api/documents/$docId/versions") {
                            header("X-Member-Id", creatorId.toString())
                            setBody(uploadBody("$role upload"))
                        }
                    response.status shouldBe expectedStatus

                    if (expectedStatus != HttpStatusCode.Created) {
                        // No version row/currentVersionId was written for a rejected attempt.
                        val docRow = transaction { DocumentTable.selectAll().where { DocumentTable.id eq docId }.single() }
                        docRow[DocumentTable.currentVersionId] shouldBe null
                    }
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        /**
         * Review finding fix (Runde 3, MAJOR): the upload route previously checked only
         * [ESCALATED_ROLES] role membership and never [DocumentAccessLevel] -- unlike
         * `deleteDocument`, which the Runde-2 fix already covers with an analogous test. This
         * helper seeds an `ADMIN_ONLY` document instead of relying on [createFolderAndDocument]'s
         * `PUBLIC_MEMBERS` default, so it actually exercises the access-level gate rather than
         * only the role gate.
         */
        suspend fun assertUploadAccessLevelGate(
            uploaderRole: AccountRole,
            expectedStatus: HttpStatusCode,
        ) {
            val storageRoot = Files.createTempDirectory("document-upload-access-level-gate-storage").toFile()
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) {
                            exception<UnauthenticatedException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                            }
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerDocumentRoutes(storageRoot) }
                    }

                    val ownerId =
                        createMember(
                            "upload-access-level-gate-owner-${uploaderRole.name.lowercase()}@example.org",
                            AccountRole.ADMIN,
                            MemberStatus.ACTIVE,
                        )
                    val docId = createFolderAndDocument(ownerId, accessLevel = DocumentAccessLevel.ADMIN_ONLY)
                    val uploaderId =
                        createMember(
                            "upload-access-level-gate-${uploaderRole.name.lowercase()}@example.org",
                            uploaderRole,
                            MemberStatus.ACTIVE,
                        )
                    val response =
                        client.post("/api/documents/$docId/versions") {
                            header("X-Member-Id", uploaderId.toString())
                            setBody(uploadBody("$uploaderRole upload against ADMIN_ONLY document"))
                        }
                    response.status shouldBe expectedStatus

                    if (expectedStatus != HttpStatusCode.Created) {
                        val docRow = transaction { DocumentTable.selectAll().where { DocumentTable.id eq docId }.single() }
                        docRow[DocumentTable.currentVersionId] shouldBe null
                    }
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        /**
         * Review finding fix (Runde 3, MINOR): the upload route checked `documentRow == null` but
         * never `documentRow[DocumentTable.isDeleted]`, unlike the download route and
         * `deleteDocument` -- so uploading a new version onto a soft-deleted document silently
         * succeeded and left a stray blob + version row behind a document the app otherwise treats
         * as gone. Uses [AccountRole.ADMIN] so a failure here can only be the missing
         * `isDeleted` check, not the role or access-level gate already covered above.
         */
        suspend fun assertUploadRejectedForSoftDeletedDocument() {
            val storageRoot = Files.createTempDirectory("document-upload-soft-deleted-storage").toFile()
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) {
                            exception<UnauthenticatedException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                            }
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerDocumentRoutes(storageRoot) }
                    }

                    val adminId =
                        createMember("upload-soft-deleted-admin@example.org", AccountRole.ADMIN, MemberStatus.ACTIVE)
                    val docId = createFolderAndDocument(adminId, isDeleted = true)
                    val response =
                        client.post("/api/documents/$docId/versions") {
                            header("X-Member-Id", adminId.toString())
                            setBody(uploadBody("upload against a soft-deleted document"))
                        }
                    response.status shouldBe HttpStatusCode.NotFound

                    val docRow = transaction { DocumentTable.selectAll().where { DocumentTable.id eq docId }.single() }
                    docRow[DocumentTable.currentVersionId] shouldBe null
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("upload route: BOARD can upload a document version (Welle Treasurer Document Upload)") {
            assertUploadRoleGate(AccountRole.BOARD, HttpStatusCode.Created)
        }

        test(
            "upload route: TREASURER can upload a document version -- the exact production gap this wave " +
                "fixed (Welle Treasurer Document Upload)",
        ) {
            assertUploadRoleGate(AccountRole.TREASURER, HttpStatusCode.Created)
        }

        test("upload route: ADMIN can upload a document version (Welle Treasurer Document Upload)") {
            assertUploadRoleGate(AccountRole.ADMIN, HttpStatusCode.Created)
        }

        test(
            "upload route: MEMBER is rejected with 403 -- ESCALATED_ROLES did not widen access beyond " +
                "BOARD/TREASURER/ADMIN (Welle Treasurer Document Upload)",
        ) {
            assertUploadRoleGate(AccountRole.MEMBER, HttpStatusCode.Forbidden)
        }

        test(
            "upload route: TREASURER is rejected with 403 against an ADMIN_ONLY document -- Runde-3-MAJOR " +
                "regression, canAccessDocumentAtLevel must gate the upload route like it already gates " +
                "deleteDocument (Welle Treasurer Document Upload)",
        ) {
            assertUploadAccessLevelGate(AccountRole.TREASURER, HttpStatusCode.Forbidden)
        }

        test(
            "upload route: BOARD is rejected with 403 against an ADMIN_ONLY document (Welle Treasurer Document Upload)",
        ) {
            assertUploadAccessLevelGate(AccountRole.BOARD, HttpStatusCode.Forbidden)
        }

        test(
            "upload route: ADMIN can upload a version onto an ADMIN_ONLY document (Welle Treasurer Document Upload)",
        ) {
            assertUploadAccessLevelGate(AccountRole.ADMIN, HttpStatusCode.Created)
        }

        test(
            "upload route: uploading a version onto a soft-deleted document is rejected with 404 -- " +
                "Runde-3-MINOR, matches the download route and deleteDocument's isDeleted check " +
                "(Welle Treasurer Document Upload)",
        ) {
            assertUploadRejectedForSoftDeletedDocument()
        }
    })
