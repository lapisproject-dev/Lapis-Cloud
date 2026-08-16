package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
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
 * V1.0 Wave 2 "Aufzeichnung" fix regression: [registerDocumentRoutes]'s download route used to
 * `respondBytes(Files.readAllBytes(...))`, which cannot serve HTTP Range requests at all (the whole
 * body is always sent, `206`/`Content-Range` never produced) -- fine for the small documents it was
 * originally written for, a real gap once a conference recording (hundreds of MB, playable inline
 * via `<video controls>`) reaches this same route as a `document`. This test proves the fix: a
 * `Range:` request now gets a real `206 Partial Content` response with exactly the requested byte
 * range, not a silently-ignored `200` with the full body.
 */
class DocumentRoutesRangeTest :
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

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Document-Route-Range-Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        test("Range request on the download route returns 206 Partial Content with exactly the requested bytes") {
            val storageRoot = Files.createTempDirectory("document-routes-range-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent) // production installs this too -- see Application.module.
                        routing { registerDocumentRoutes(storageRoot) }
                    }

                    val memberId = createMember("doc-route-range-member@example.org")
                    val folderId = Uuid.random()
                    val documentId = Uuid.random()
                    val versionId = Uuid.random()
                    // Large enough that a real Range request meaningfully sub-selects the body.
                    val blobBytes = ByteArray(10_000) { (it % 256).toByte() }
                    val storageKey = "$documentId/$versionId.bin"

                    transaction {
                        DocumentFolderTable.insert {
                            it[id] = folderId
                            it[name] = "Range-Test-Ordner"
                            it[parentFolderId] = null
                        }
                        DocumentTable.insert {
                            it[id] = documentId
                            it[DocumentTable.folderId] = folderId
                            it[title] = "Range-Test-Dokument"
                            it[currentVersionId] = null
                            it[createdBy] = memberId
                            it[createdAt] = LocalDateTime(2026, 8, 9, 9, 0)
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
                            it[fileName] = "range-test.bin"
                            it[mimeType] = "application/octet-stream"
                            it[fileSizeBytes] = blobBytes.size.toLong()
                            it[DocumentVersionTable.storageKey] = storageKey
                            it[checksumSha256] =
                                MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(blobBytes)
                                    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
                            it[uploadedBy] = memberId
                            it[uploadedAt] = LocalDateTime(2026, 8, 9, 9, 5)
                            it[changeNote] = null
                        }
                        DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }
                    }
                    createdFolderIds += folderId
                    createdDocumentIds += documentId

                    // Baseline: a full, unranged download is unchanged by the fix.
                    val fullResponse =
                        client.get("/api/documents/$documentId/download") { header("X-Member-Id", memberId.toString()) }
                    fullResponse.status shouldBe HttpStatusCode.OK
                    fullResponse.bodyAsBytes() shouldBe blobBytes

                    // The fix: a Range request now gets a real 206 with exactly the requested slice.
                    val rangeResponse =
                        client.get("/api/documents/$documentId/download") {
                            header("X-Member-Id", memberId.toString())
                            header(HttpHeaders.Range, "bytes=100-199")
                        }
                    rangeResponse.status shouldBe HttpStatusCode.PartialContent
                    rangeResponse.headers[HttpHeaders.ContentRange] shouldBe "bytes 100-199/10000"
                    rangeResponse.bodyAsBytes() shouldBe blobBytes.copyOfRange(100, 200)
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }
    })
