package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
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
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Route-level regression for `document_version.download_count` (Wave "Download-Zaehler +
 * Ordner-Dokumentanzahl", 2026-09-16) -- proves the increment happens exactly once per real full
 * download, strictly after the access-control gate, and never on a Range request. Mirrors
 * [DocumentRoutesGuestAccessTest]'s idiom (direct DB seed for the version row + blob, real HTTP
 * calls against the actual route wiring) rather than exercising [DocumentService] directly, since
 * the counter is incremented in [registerDocumentRoutes] itself, not in the RPC service layer.
 */
class DocumentDownloadCountTest :
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
                    it[displayName] = "Download-Count-Testmitglied"
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

        /** Seeds one folder/document/version triple and its on-disk blob, returns (documentId, versionId). */
        fun seedDocument(
            storageRoot: java.io.File,
            creatorId: Uuid,
            accessLevel: DocumentAccessLevel,
            blobBytes: ByteArray,
        ): Pair<Uuid, Uuid> {
            val folderId = Uuid.random()
            val documentId = Uuid.random()
            val versionId = Uuid.random()
            val storageKey = "$documentId/$versionId.bin"
            transaction {
                DocumentFolderTable.insert {
                    it[id] = folderId
                    it[name] = "Download-Count-Test-Ordner"
                    it[parentFolderId] = null
                }
                DocumentTable.insert {
                    it[id] = documentId
                    it[DocumentTable.folderId] = folderId
                    it[title] = "Download-Count-Test-Dokument"
                    it[currentVersionId] = null
                    it[createdBy] = creatorId
                    it[createdAt] = LocalDateTime(2026, 9, 16, 9, 0)
                    it[DocumentTable.accessLevel] = accessLevel
                    it[isDeleted] = false
                }
                val targetFile = storageRoot.resolve(storageKey)
                targetFile.parentFile.mkdirs()
                targetFile.writeBytes(blobBytes)
                DocumentVersionTable.insert {
                    it[id] = versionId
                    it[DocumentVersionTable.documentId] = documentId
                    it[versionNumber] = 1
                    it[fileName] = "download-count-test.txt"
                    it[mimeType] = "text/plain"
                    it[fileSizeBytes] = blobBytes.size.toLong()
                    it[DocumentVersionTable.storageKey] = storageKey
                    it[checksumSha256] =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(blobBytes)
                            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
                    it[uploadedBy] = creatorId
                    it[uploadedAt] = LocalDateTime(2026, 9, 16, 9, 5)
                    it[changeNote] = null
                    // downloadCount deliberately NOT set -- exercising the real DB default (0).
                }
                DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }
            }
            createdFolderIds += folderId
            createdDocumentIds += documentId
            return documentId to versionId
        }

        fun downloadCountOf(versionId: Uuid): Long =
            transaction {
                DocumentVersionTable
                    .selectAll()
                    .where { DocumentVersionTable.id eq versionId }
                    .single()[DocumentVersionTable.downloadCount]
            }

        fun withApp(block: suspend io.ktor.client.HttpClient.(storageRoot: java.io.File) -> Unit) {
            val storageRoot = Files.createTempDirectory("document-download-count-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<UnauthenticatedException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                            }
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        install(PartialContent) // production installs this too -- see Application.module.
                        routing { registerDocumentRoutes(storageRoot) }
                    }
                    block(client, storageRoot)
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("download with authorization increments download_count by 1") {
            withApp { storageRoot ->
                val creatorId = createMember("dl-count-1-creator@example.org", AccountRole.BOARD, MemberStatus.ACTIVE)
                val memberId = createMember("dl-count-1-member@example.org", AccountRole.MEMBER, MemberStatus.ACTIVE)
                val (documentId, versionId) =
                    seedDocument(storageRoot, creatorId, DocumentAccessLevel.PUBLIC_MEMBERS, "content-1".toByteArray())

                downloadCountOf(versionId) shouldBe 0L
                val response = get("/api/documents/$documentId/download") { header("X-Member-Id", memberId.toString()) }
                response.status shouldBe HttpStatusCode.OK
                downloadCountOf(versionId) shouldBe 1L
            }
        }

        test("download without authorization (403) leaves download_count unchanged") {
            withApp { storageRoot ->
                val creatorId = createMember("dl-count-2-creator@example.org", AccountRole.BOARD, MemberStatus.ACTIVE)
                val guestId = createMember("dl-count-2-guest@example.org", AccountRole.MEMBER, MemberStatus.GUEST)
                val (documentId, versionId) =
                    seedDocument(storageRoot, creatorId, DocumentAccessLevel.PUBLIC_MEMBERS, "content-2".toByteArray())

                val response = get("/api/documents/$documentId/download") { header("X-Member-Id", guestId.toString()) }
                response.status shouldBe HttpStatusCode.Forbidden
                downloadCountOf(versionId) shouldBe 0L
            }
        }

        test("download with a Range header (partial content) leaves download_count unchanged") {
            withApp { storageRoot ->
                val creatorId = createMember("dl-count-3-creator@example.org", AccountRole.BOARD, MemberStatus.ACTIVE)
                val memberId = createMember("dl-count-3-member@example.org", AccountRole.MEMBER, MemberStatus.ACTIVE)
                val (documentId, versionId) =
                    seedDocument(storageRoot, creatorId, DocumentAccessLevel.PUBLIC_MEMBERS, "0123456789content-3".toByteArray())

                val response =
                    get("/api/documents/$documentId/download") {
                        header("X-Member-Id", memberId.toString())
                        header(HttpHeaders.Range, "bytes=0-4")
                    }
                response.status shouldBe HttpStatusCode.PartialContent
                downloadCountOf(versionId) shouldBe 0L
            }
        }

        test("two sequential full downloads increment download_count by exactly 2") {
            withApp { storageRoot ->
                val creatorId = createMember("dl-count-4-creator@example.org", AccountRole.BOARD, MemberStatus.ACTIVE)
                val memberId = createMember("dl-count-4-member@example.org", AccountRole.MEMBER, MemberStatus.ACTIVE)
                val (documentId, versionId) =
                    seedDocument(storageRoot, creatorId, DocumentAccessLevel.PUBLIC_MEMBERS, "content-4".toByteArray())

                get("/api/documents/$documentId/download") { header("X-Member-Id", memberId.toString()) }
                get("/api/documents/$documentId/download") { header("X-Member-Id", memberId.toString()) }
                downloadCountOf(versionId) shouldBe 2L
            }
        }

        test("download of a non-existent version (404) leaves download_count unchanged") {
            withApp { storageRoot ->
                val creatorId = createMember("dl-count-5-creator@example.org", AccountRole.BOARD, MemberStatus.ACTIVE)
                val memberId = createMember("dl-count-5-member@example.org", AccountRole.MEMBER, MemberStatus.ACTIVE)
                val (documentId, versionId) =
                    seedDocument(storageRoot, creatorId, DocumentAccessLevel.PUBLIC_MEMBERS, "content-5".toByteArray())

                val bogusVersionId = Uuid.random()
                val response =
                    get("/api/documents/$documentId/download?version=$bogusVersionId") {
                        header("X-Member-Id", memberId.toString())
                    }
                response.status shouldBe HttpStatusCode.NotFound
                downloadCountOf(versionId) shouldBe 0L
            }
        }
    })
