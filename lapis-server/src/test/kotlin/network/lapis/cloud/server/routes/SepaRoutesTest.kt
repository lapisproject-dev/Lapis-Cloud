package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SepaDebitBatchTable
import network.lapis.cloud.server.payment.sepa.SepaConfig
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaSequenceType
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import kotlin.uuid.Uuid

/**
 * Security Round 1 (2026-08-20, MAJOR-1). Before this round, [registerSepaRoutes]' pain.008
 * download route had ZERO test coverage -- the ONLY untested HTTP surface in this wave (grep
 * confirmed). Covers: role gate (MEMBER/BOARD -> 403, TREASURER/ADMIN -> 200), malformed/unknown
 * batchId, a soft-deleted document (404, MAJOR-1's own `isDeleted` fix), a CANCELLED batch (409,
 * MINOR-1), and end-to-end SecretBox decryption (MAJOR-2) of a real sealed fixture file.
 */
class SepaRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdBatchIds = mutableListOf<Uuid>()
        val createdFolderIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()

        val testKeyBase64 =
            Base64.getEncoder().encodeToString(ByteArray(SecretBox.KEY_SIZE_BYTES).also(SecureRandom()::nextBytes))
        val testSepaConfig = SepaConfig.load { key -> if (key == "LAPIS_SECRET_ENCRYPTION_KEY") testKeyBase64 else null }
        val secretBox = SecretBox(requireNotNull(testSepaConfig.secretEncryptionKey))

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdBatchIds.isNotEmpty()) {
                    SepaDebitBatchTable.deleteWhere { SepaDebitBatchTable.id inList createdBatchIds }
                }
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
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Sepa-Route-Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
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

        /**
         * Seeds a GENERATED batch backed by a REAL SecretBox-sealed document/version/blob (same
         * wire format [SepaService.generateBatchFile] Phase 2 produces), returns the batch id.
         * [documentIsDeleted]/[batchStatus] let individual tests exercise MAJOR-1's isDeleted guard
         * and MINOR-1's CANCELLED guard without needing the real service to reach those states.
         */
        fun seedGeneratedBatch(
            storageRoot: java.io.File,
            createdBy: Uuid,
            plaintextXml: String = "<Document><!-- fixture pain.008 body --></Document>",
            documentIsDeleted: Boolean = false,
            batchStatus: SepaDebitBatchStatus = SepaDebitBatchStatus.GENERATED,
            withDocument: Boolean = true,
        ): Uuid {
            val batchId = Uuid.random()
            val folderId = Uuid.random()
            val documentId = Uuid.random()
            val versionId = Uuid.random()
            val now = LocalDateTime(2026, 8, 20, 9, 0)

            transaction {
                var generatedDocumentId: Uuid? = null
                if (withDocument) {
                    DocumentFolderTable.insert {
                        it[id] = folderId
                        it[name] = "SEPA-Lastschriften-Route-Test"
                        it[parentFolderId] = null
                    }
                    DocumentTable.insert {
                        it[id] = documentId
                        it[DocumentTable.folderId] = folderId
                        it[title] = "Route-Test SEPA-Datei"
                        it[currentVersionId] = null
                        it[DocumentTable.createdBy] = createdBy
                        it[createdAt] = now
                        it[accessLevel] = DocumentAccessLevel.ADMIN_ONLY
                        it[isDeleted] = documentIsDeleted
                    }
                    val sealed = secretBox.seal(plaintext = plaintextXml, aad = batchId.toString())
                    val storageKey = "$documentId/$versionId.bin"
                    val targetFile = storageRoot.resolve(storageKey)
                    targetFile.parentFile.mkdirs()
                    targetFile.writeText(sealed, Charsets.UTF_8)
                    DocumentVersionTable.insert {
                        it[id] = versionId
                        it[DocumentVersionTable.documentId] = documentId
                        it[versionNumber] = 1
                        it[fileName] = "sepa-lastschrift-route-test.xml.enc"
                        it[mimeType] = "application/octet-stream"
                        it[fileSizeBytes] = sealed.toByteArray(Charsets.UTF_8).size.toLong()
                        it[DocumentVersionTable.storageKey] = storageKey
                        it[checksumSha256] = "unused-in-this-test"
                        it[uploadedBy] = createdBy
                        it[uploadedAt] = now
                        it[changeNote] = null
                    }
                    DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }
                    generatedDocumentId = documentId
                    createdFolderIds += folderId
                    createdDocumentIds += documentId
                }

                SepaDebitBatchTable.insert {
                    it[id] = batchId
                    it[messageId] = "LC-DD-ROUTE-${batchId.toString().take(8)}"
                    it[paymentInfoId] = "LC-DD-ROUTE-${batchId.toString().take(8)}-P1"
                    it[requestedCollectionDate] = now.date
                    it[sequenceType] = SepaSequenceType.RCUR
                    it[status] = batchStatus
                    it[itemCount] = 1
                    it[totalAmount] = BigDecimal("50.00")
                    it[SepaDebitBatchTable.createdBy] = createdBy
                    it[createdAt] = now
                    it[notifiedAt] = now
                    it[requiredNoticeDays] = 14
                    it[generatedAt] = if (withDocument) now else null
                    it[SepaDebitBatchTable.generatedDocumentId] = generatedDocumentId
                    it[prenotificationDocumentId] = null
                    it[submittedAt] = null
                    it[submittedNote] = null
                    it[settledAt] = null
                    it[cancelledAt] = null
                    it[cancellationReason] = null
                    it[creditorId] = "DE98ZZZ09999999999"
                    it[creditorName] = "Sepa-Route-Test Verein"
                    it[creditorIban] = "DE89370400440532013000"
                    it[creditorBic] = "COBADEFFXXX"
                }
            }
            createdBatchIds += batchId
            return batchId
        }

        test("malformed batchId -> 400") {
            val storageRoot = Files.createTempDirectory("sepa-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerSepaRoutes(documentStorageRoot = storageRoot, sepaConfig = testSepaConfig) }
                    }
                    val admin = createMember("sepa-route-400-admin@example.org", AccountRole.ADMIN)
                    val response =
                        client.get("/api/sepa/batches/not-a-uuid/file.xml") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("unknown batchId -> 404") {
            val storageRoot = Files.createTempDirectory("sepa-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerSepaRoutes(documentStorageRoot = storageRoot, sepaConfig = testSepaConfig) }
                    }
                    val admin = createMember("sepa-route-404-admin@example.org", AccountRole.ADMIN)
                    val response =
                        client.get("/api/sepa/batches/${Uuid.random()}/file.xml") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test(
            "role gate (MAJOR-1): MEMBER and BOARD get 403; TREASURER and ADMIN get 200 with the " +
                "correctly decrypted plaintext, never the raw sealed bytes",
        ) {
            val storageRoot = Files.createTempDirectory("sepa-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerSepaRoutes(documentStorageRoot = storageRoot, sepaConfig = testSepaConfig) }
                    }
                    val admin = createMember("sepa-route-role-admin@example.org", AccountRole.ADMIN)
                    val treasurer = createMember("sepa-route-role-treasurer@example.org", AccountRole.TREASURER)
                    val board = createMember("sepa-route-role-board@example.org", AccountRole.BOARD)
                    val member = createMember("sepa-route-role-member@example.org", AccountRole.MEMBER)
                    val plaintext = "<Document>full plaintext IBAN would live here</Document>"
                    val batchId = seedGeneratedBatch(storageRoot = storageRoot, createdBy = admin, plaintextXml = plaintext)

                    val memberResponse = client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", member.toString()) }
                    memberResponse.status shouldBe HttpStatusCode.Forbidden

                    val boardResponse = client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", board.toString()) }
                    boardResponse.status shouldBe HttpStatusCode.Forbidden

                    val treasurerResponse =
                        client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", treasurer.toString()) }
                    treasurerResponse.status shouldBe HttpStatusCode.OK
                    treasurerResponse.bodyAsText() shouldBe plaintext

                    val adminResponse = client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", admin.toString()) }
                    adminResponse.status shouldBe HttpStatusCode.OK
                    adminResponse.bodyAsText() shouldBe plaintext
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("a soft-deleted document (MAJOR-1) -> 404, even for TREASURER") {
            val storageRoot = Files.createTempDirectory("sepa-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerSepaRoutes(documentStorageRoot = storageRoot, sepaConfig = testSepaConfig) }
                    }
                    val treasurer = createMember("sepa-route-deleted-treasurer@example.org", AccountRole.TREASURER)
                    val batchId = seedGeneratedBatch(storageRoot = storageRoot, createdBy = treasurer, documentIsDeleted = true)

                    val response = client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", treasurer.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("a CANCELLED batch (MINOR-1) -> 409, even though generatedDocumentId is still set") {
            val storageRoot = Files.createTempDirectory("sepa-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerSepaRoutes(documentStorageRoot = storageRoot, sepaConfig = testSepaConfig) }
                    }
                    val treasurer = createMember("sepa-route-cancelled-treasurer@example.org", AccountRole.TREASURER)
                    val batchId =
                        seedGeneratedBatch(
                            storageRoot = storageRoot,
                            createdBy = treasurer,
                            batchStatus = SepaDebitBatchStatus.CANCELLED,
                        )

                    val response = client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", treasurer.toString()) }
                    response.status shouldBe HttpStatusCode.Conflict
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("a batch with no generatedDocumentId at all -> 404") {
            val storageRoot = Files.createTempDirectory("sepa-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing { registerSepaRoutes(documentStorageRoot = storageRoot, sepaConfig = testSepaConfig) }
                    }
                    val treasurer = createMember("sepa-route-nodoc-treasurer@example.org", AccountRole.TREASURER)
                    val batchId =
                        seedGeneratedBatch(
                            storageRoot = storageRoot,
                            createdBy = treasurer,
                            batchStatus = SepaDebitBatchStatus.NOTIFIED,
                            withDocument = false,
                        )

                    val response = client.get("/api/sepa/batches/$batchId/file.xml") { header("X-Member-Id", treasurer.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }
    })
