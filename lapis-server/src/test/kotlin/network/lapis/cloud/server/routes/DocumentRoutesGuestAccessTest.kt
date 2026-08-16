package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
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
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * HTTP-route-level regression for the guest/`PUBLIC_MEMBERS` document-access fix -- proves the
 * same gate closure holds on [registerDocumentRoutes]'s `/api/documents/{id}/download` route, not
 * only at the [network.lapis.cloud.server.rpc.DocumentService] layer (see
 * `ServiceIntegrationTest`'s "documents: a guest session is excluded from PUBLIC_MEMBERS..." for
 * that layer's coverage). Both call sites share the exact same
 * [network.lapis.cloud.server.security.canAccessDocumentAtLevel] function, but this test exercises
 * the real route wiring end to end. The version row + blob are seeded directly via DB insert +
 * file write (mirroring `OrganizationBackupRoundTripTest`'s idiom) rather than a real multipart
 * upload -- upload-path correctness is out of scope here, only the download-side access gate is.
 */
class DocumentRoutesGuestAccessTest :
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
                    it[displayName] = "Document-Route-Guest-Access-Testmitglied"
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

        test(
            "download route: a guest session gets 403 Forbidden on a PUBLIC_MEMBERS document; a real local member downloads it successfully, unchanged",
        ) {
            val storageRoot = Files.createTempDirectory("document-routes-guest-access-storage").toFile()
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
                        routing { registerDocumentRoutes(storageRoot) }
                    }

                    val creatorId =
                        createMember("doc-route-guest-access-creator@example.org", AccountRole.BOARD, MemberStatus.ACTIVE)
                    val memberId =
                        createMember("doc-route-guest-access-member@example.org", AccountRole.MEMBER, MemberStatus.ACTIVE)
                    val guestId =
                        createMember("doc-route-guest-access-guest@example.org", AccountRole.MEMBER, MemberStatus.GUEST)

                    val folderId = Uuid.random()
                    val documentId = Uuid.random()
                    val versionId = Uuid.random()
                    val blobBytes = "PUBLIC_MEMBERS document content for the guest-access regression test".toByteArray(Charsets.UTF_8)
                    val storageKey = "$documentId/$versionId.bin"

                    transaction {
                        DocumentFolderTable.insert {
                            it[id] = folderId
                            it[name] = "Guest-Access-Test-Ordner"
                            it[parentFolderId] = null
                        }
                        DocumentTable.insert {
                            it[id] = documentId
                            it[DocumentTable.folderId] = folderId
                            it[title] = "Guest-Access-Test-Dokument"
                            it[currentVersionId] = null
                            it[createdBy] = creatorId
                            it[createdAt] = LocalDateTime(2026, 7, 27, 9, 0)
                            it[accessLevel] = DocumentAccessLevel.PUBLIC_MEMBERS
                            it[isDeleted] = false
                        }
                        val targetFile = storageRoot.resolve(storageKey)
                        targetFile.parentFile.mkdirs()
                        targetFile.writeBytes(blobBytes)
                        DocumentVersionTable.insert {
                            it[id] = versionId
                            it[DocumentVersionTable.documentId] = documentId
                            it[versionNumber] = 1
                            it[fileName] = "guest-access-test.txt"
                            it[mimeType] = "text/plain"
                            it[fileSizeBytes] = blobBytes.size.toLong()
                            it[DocumentVersionTable.storageKey] = storageKey
                            it[checksumSha256] =
                                MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(blobBytes)
                                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
                            it[uploadedBy] = creatorId
                            it[uploadedAt] = LocalDateTime(2026, 7, 27, 9, 5)
                            it[changeNote] = null
                        }
                        DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }
                    }
                    createdFolderIds += folderId
                    createdDocumentIds += documentId

                    // Regression: a real local member's download of a PUBLIC_MEMBERS document is
                    // unchanged by this fix.
                    val memberResponse =
                        client.get("/api/documents/$documentId/download") { header("X-Member-Id", memberId.toString()) }
                    memberResponse.status shouldBe HttpStatusCode.OK
                    memberResponse.bodyAsBytes() shouldBe blobBytes

                    // The fix: a guest session (role = MEMBER, status = GAST) is now rejected on the
                    // download route exactly like the listDocuments/listVersions path.
                    val guestResponse =
                        client.get("/api/documents/$documentId/download") { header("X-Member-Id", guestId.toString()) }
                    guestResponse.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }
    })
