package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
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
 * [registerConferenceRecordingRoutes]' media route -- see that function's own KDoc for the security
 * checklist this exercises end to end (never mocked at the [ConferenceRecordingAccess] layer, the
 * real predicate runs against real DB rows, same discipline [DocumentRoutesGuestAccessTest]
 * establishes for the analogous document-download route).
 */
class ConferenceRecordingRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdRecordingIds = mutableListOf<Uuid>()
        val createdFolderIds = mutableListOf<Uuid>()
        val createdDocumentIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdRecordingIds.isNotEmpty()) {
                    ConferenceRecordingTable.deleteWhere { ConferenceRecordingTable.id inList createdRecordingIds }
                }
                if (createdRoomIds.isNotEmpty()) {
                    ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList createdRoomIds }
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
                    it[displayName] = "Recording-Route-Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
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

        fun createRoom(creatorId: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "Route-Test-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-route-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[endedAt] = null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        /** Seeds a READY recording backed by a real document/version/blob, returns (recordingId, documentId). */
        fun createReadyRecording(
            storageRoot: java.io.File,
            roomId: Uuid,
            startedByMemberId: Uuid,
            accessLevel: DocumentAccessLevel,
            documentIsDeleted: Boolean = false,
        ): Pair<Uuid, Uuid> {
            val recordingId = Uuid.random()
            val folderId = Uuid.random()
            val documentId = Uuid.random()
            val versionId = Uuid.random()
            val blobBytes = "fake mp4 bytes for the route test".toByteArray(Charsets.UTF_8)
            val storageKey = "$documentId/$versionId.bin"

            transaction {
                DocumentFolderTable.insert {
                    it[id] = folderId
                    it[name] = "Aufzeichnungen-Route-Test"
                    it[parentFolderId] = null
                }
                DocumentTable.insert {
                    it[id] = documentId
                    it[DocumentTable.folderId] = folderId
                    it[title] = "Route-Test-Aufzeichnung"
                    it[currentVersionId] = null
                    it[createdBy] = startedByMemberId
                    it[createdAt] = LocalDateTime(2026, 8, 9, 9, 30)
                    it[DocumentTable.accessLevel] = accessLevel
                    it[isDeleted] = documentIsDeleted
                }
                val targetFile = storageRoot.resolve(storageKey)
                targetFile.parentFile.mkdirs()
                targetFile.writeBytes(blobBytes)
                DocumentVersionTable.insert {
                    it[id] = versionId
                    it[DocumentVersionTable.documentId] = documentId
                    it[versionNumber] = 1
                    it[fileName] = "route-test.mp4"
                    it[mimeType] = "video/mp4"
                    it[fileSizeBytes] = blobBytes.size.toLong()
                    it[DocumentVersionTable.storageKey] = storageKey
                    it[checksumSha256] =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(blobBytes)
                            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
                    it[uploadedBy] = startedByMemberId
                    it[uploadedAt] = LocalDateTime(2026, 8, 9, 9, 31)
                    it[changeNote] = null
                }
                DocumentTable.update({ DocumentTable.id eq documentId }) { it[currentVersionId] = versionId }

                ConferenceRecordingTable.insert {
                    it[id] = recordingId
                    it[ConferenceRecordingTable.roomId] = roomId
                    it[ConferenceRecordingTable.startedByMemberId] = startedByMemberId
                    it[startedAt] = LocalDateTime(2026, 8, 9, 9, 0)
                    it[stoppedAt] = LocalDateTime(2026, 8, 9, 9, 20)
                    it[readyAt] = LocalDateTime(2026, 8, 9, 9, 25)
                    it[status] = ConferenceRecordingStatus.READY
                    it[ConferenceRecordingTable.accessLevel] = accessLevel
                    it[ConferenceRecordingTable.documentId] = documentId
                    it[rawDir] = recordingId.toString()
                    it[durationSeconds] = 1200
                    it[fileSizeBytes] = blobBytes.size.toLong()
                    it[failureReason] = null
                    it[composeAttempts] = 1
                }
            }
            createdFolderIds += folderId
            createdDocumentIds += documentId
            createdRecordingIds += recordingId
            return recordingId to documentId
        }

        test("unknown recordingId -> 404") {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val member = createMember("crr-404-member@example.org", AccountRole.MEMBER)
                    val response =
                        client.get("/api/conference/recordings/${Uuid.random()}/media") { header("X-Member-Id", member.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("recording exists but not READY -> 404, even for a privileged caller") {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val admin = createMember("crr-notready-admin@example.org", AccountRole.ADMIN)
                    val roomId = createRoom(admin)
                    val recordingId = Uuid.random()
                    transaction {
                        ConferenceRecordingTable.insert {
                            it[id] = recordingId
                            it[ConferenceRecordingTable.roomId] = roomId
                            it[startedByMemberId] = admin
                            it[startedAt] = LocalDateTime(2026, 8, 9, 9, 0)
                            it[stoppedAt] = null
                            it[readyAt] = null
                            it[status] = ConferenceRecordingStatus.RECORDING
                            it[ConferenceRecordingTable.accessLevel] = DocumentAccessLevel.BOARD_ONLY
                            it[ConferenceRecordingTable.documentId] = null
                            it[rawDir] = recordingId.toString()
                            it[durationSeconds] = null
                            it[fileSizeBytes] = null
                            it[failureReason] = null
                            it[composeAttempts] = 0
                        }
                    }
                    createdRecordingIds += recordingId

                    val response =
                        client.get("/api/conference/recordings/$recordingId/media") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("no session at all -> 401 Unauthorized") {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val response = client.get("/api/conference/recordings/${Uuid.random()}/media")
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test(
            "BOARD_ONLY recording: an ordinary member who did not start it gets 403 Forbidden via ConferenceRecordingAccess.mayAccess",
        ) {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> { call, cause ->
                                call.respond(HttpStatusCode.Forbidden, cause.message)
                            }
                        }
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val board = createMember("crr-board-only-board@example.org", AccountRole.BOARD)
                    val outsider = createMember("crr-board-only-outsider@example.org", AccountRole.MEMBER)
                    val roomId = createRoom(board)
                    val (recordingId, _) = createReadyRecording(storageRoot, roomId, board, DocumentAccessLevel.BOARD_ONLY)

                    val response =
                        client.get("/api/conference/recordings/$recordingId/media") { header("X-Member-Id", outsider.toString()) }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("BOARD_ONLY recording: a BOARD member downloads it successfully with the correct bytes and video/mp4 content type") {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val board = createMember("crr-board-download-board@example.org", AccountRole.BOARD)
                    val roomId = createRoom(board)
                    val (recordingId, _) = createReadyRecording(storageRoot, roomId, board, DocumentAccessLevel.BOARD_ONLY)

                    val response =
                        client.get("/api/conference/recordings/$recordingId/media") { header("X-Member-Id", board.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentType] shouldBe ContentType.Video.MP4.toString()
                    response.bodyAsBytes() shouldBe "fake mp4 bytes for the route test".toByteArray(Charsets.UTF_8)
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test(
            "BOARD_ONLY recording: an ordinary-MEMBER moderator who STARTED it can still access their own recording (the starter carve-out)",
        ) {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val starter = createMember("crr-starter-carveout@example.org", AccountRole.MEMBER)
                    val roomId = createRoom(starter)
                    val (recordingId, _) = createReadyRecording(storageRoot, roomId, starter, DocumentAccessLevel.BOARD_ONLY)

                    val response =
                        client.get("/api/conference/recordings/$recordingId/media") { header("X-Member-Id", starter.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("a READY recording whose underlying document was soft-deleted -> 404, even for the starter") {
            val storageRoot = Files.createTempDirectory("conf-rec-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(PartialContent)
                        routing { registerConferenceRecordingRoutes(storageRoot) }
                    }
                    val starter = createMember("crr-soft-deleted@example.org", AccountRole.MEMBER)
                    val roomId = createRoom(starter)
                    val (recordingId, _) =
                        createReadyRecording(
                            storageRoot,
                            roomId,
                            starter,
                            DocumentAccessLevel.BOARD_ONLY,
                            documentIsDeleted = true,
                        )

                    val response =
                        client.get("/api/conference/recordings/$recordingId/media") { header("X-Member-Id", starter.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }
    })
