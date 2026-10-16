package network.lapis.cloud.server.encounter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.PulpitRecordingDocumentTable
import network.lapis.cloud.server.routes.archiveGeneratedBytes
import network.lapis.cloud.server.rpc.DocumentService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.uuid.Uuid

/**
 * V1.9.143 -- the HARD deletion of a pulpit recording: the blobs of every version, the version rows, the marker row and the document row are removed; only
 * BOARD/ADMIN may list or delete; the normal "delete document" of the Ablage hands a pulpit recording to the same function (every other document keeps
 * its soft delete); a storage key that points outside the store is never touched; the audit entry names no person but the actor.
 */
class PulpitRecordingDeleteTest :
    FunSpec({
        val fx = EncounterFixtures()
        val worlds = mutableListOf<PulpitWorld>()
        val ordinaryDocuments = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            worlds.forEach { it.close() }
            transaction {
                DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList ordinaryDocuments }
                DocumentTable.deleteWhere { DocumentTable.id inList ordinaryDocuments }
            }
            fx.cleanUp()
        }

        fun newWorld(): PulpitWorld =
            PulpitWorld(w = fx.tableWorld(tablesEnabled = false, profile = EncounterProfile.CHURCH_SERVICE)).also {
                worlds += it
                PulpitRecordingDocuments.useStorageRoot(it.documents)
            }

        /** Archives a stored recording of [p]'s space exactly as the clock does, and returns its document id and the blob. */
        fun store(p: PulpitWorld): Pair<Uuid, File> {
            val raw = p.root.resolve("raw-${Uuid.random()}.mp4").also { it.writeBytes(ByteArray(4096) { b -> b.toByte() }) }
            val id =
                PulpitRecordingDocuments.archive(
                    file = raw,
                    spaceId = p.w.space,
                    fileId = Uuid.random(),
                    recordedAt = DbClock.nowLocalDateTime(),
                    uploadedBy = p.w.steward,
                    assembly = false,
                    root = p.documents,
                )
            val key =
                transaction {
                    DocumentVersionTable
                        .selectAll()
                        .where {
                            DocumentVersionTable.documentId eq id
                        }.single()[DocumentVersionTable.storageKey]
                }
            return id to p.documents.resolve(key)
        }

        fun rowsOf(id: Uuid): Triple<Boolean, Boolean, Long> =
            transaction {
                Triple(
                    DocumentTable.selectAll().where { DocumentTable.id eq id }.any(),
                    PulpitRecordingDocumentTable.selectAll().where { PulpitRecordingDocumentTable.documentId eq id }.any(),
                    DocumentVersionTable.selectAll().where { DocumentVersionTable.documentId eq id }.count(),
                )
            }

        test("BOARD lists the recordings of a room (room, moment, size -- no person) and a MEMBER, a steward and the pulpit cannot") {
            val p = newWorld()
            val (id, _) = store(p)
            encounterApp {
                val list = p.asMember(client = client, member = p.w.board) { it.listPulpitRecordings(p.w.spaceId) }.getOrThrow()
                list.size shouldBe 1
                list.single().documentId shouldBe id.toString()
                list.single().spaceId shouldBe p.w.spaceId
                list.single().fileSizeBytes shouldBe 4096L
                p.asMember(client = client, member = p.w.board) { it.listPulpitRecordings(null) }.getOrThrow().any {
                    it.documentId ==
                        id.toString()
                } shouldBe
                    true
                listOf(p.w.a, p.w.steward, p.w.pulpit).forEach { who ->
                    p.asMember(client = client, member = who) { it.listPulpitRecordings(null) }.failure<ForbiddenException>()
                    p.asMember(client = client, member = who) { it.deletePulpitRecording(id.toString()) }.failure<ForbiddenException>()
                }
            }
        }

        test("the hard delete removes the blob, the versions, the marker and the document, and audits the deletion without a person") {
            val p = newWorld()
            val (id, blob) = store(p)
            blob.exists() shouldBe true
            rowsOf(id) shouldBe Triple(true, true, 1L)
            encounterApp {
                p.asMember(client = client, member = p.w.board) { it.deletePulpitRecording(id.toString()) }.getOrThrow() shouldBe true
                rowsOf(id) shouldBe Triple(false, false, 0L)
                blob.exists() shouldBe false
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.PULPIT_RECORDING) and
                                    (AuditLogEntryTable.entityId eq id)
                            }.single()
                    }
                entry[AuditLogEntryTable.afterSnapshot]!! shouldContain "\"event\":\"DELETED\""
                entry[AuditLogEntryTable.actorMemberId] shouldBe p.w.board
                p.asMember(client = client, member = p.w.board) { it.deletePulpitRecording(id.toString()) }.failure<NotFoundException>()
                p.asMember(client = client, member = p.w.board) { it.deletePulpitRecording("no-uuid") }.failure<NotFoundException>()
            }
        }

        test(
            "the ordinary 'delete document' of the Ablage removes a pulpit recording for real and still only marks every other document as deleted",
        ) {
            val p = newWorld()
            val (id, blob) = store(p)
            val admin = fx.createMember(role = AccountRole.ADMIN)
            val other =
                archiveGeneratedBytes(
                    storageRoot = p.documents,
                    folderName = "Sonstiges",
                    fileName = "x.pdf",
                    title = "Ein Dokument",
                    bytes = ByteArray(10),
                    mimeType = "application/pdf",
                    uploadedBy = p.w.board,
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                    changeNote = "test",
                ).also { ordinaryDocuments += it }
            encounterApp {
                client.runAs(member = admin) { call -> DocumentService(call).deleteDocument(id.toString()) }.getOrThrow()
                rowsOf(id) shouldBe Triple(false, false, 0L)
                blob.exists() shouldBe false
                client.runAs(member = admin) { call -> DocumentService(call).deleteDocument(other.toString()) }.getOrThrow()
                transaction { DocumentTable.selectAll().where { DocumentTable.id eq other }.single()[DocumentTable.isDeleted] } shouldBe
                    true
            }
        }

        test("a storage key that points outside the store is never touched, the rows are gone all the same") {
            val p = newWorld()
            val (id, blob) = store(p)
            val outside = p.root.resolve("outside.bin").also { it.writeBytes(ByteArray(3)) }
            transaction {
                DocumentVersionTable.insert {
                    it[DocumentVersionTable.id] = Uuid.random()
                    it[documentId] = id
                    it[versionNumber] = 2
                    it[fileName] = "evil.mp4"
                    it[mimeType] = "video/mp4"
                    it[fileSizeBytes] = 3
                    it[storageKey] = "../../outside.bin"
                    it[checksumSha256] = "0".repeat(64)
                    it[uploadedBy] = p.w.steward
                    it[uploadedAt] = DbClock.nowLocalDateTime()
                    it[changeNote] = "x"
                }
            }
            encounterApp {
                p.asMember(client = client, member = p.w.board) { it.deletePulpitRecording(id.toString()) }.getOrThrow()
                rowsOf(id) shouldBe Triple(false, false, 0L)
                blob.exists() shouldBe false
                outside.exists() shouldBe true
            }
        }

        test("nothing but a pulpit recording can be hard-deleted through this function") {
            val p = newWorld()
            val other =
                archiveGeneratedBytes(
                    storageRoot = p.documents,
                    folderName = "Sonstiges",
                    fileName = "y.pdf",
                    title = "Noch ein Dokument",
                    bytes = ByteArray(10),
                    mimeType = "application/pdf",
                    uploadedBy = p.w.board,
                    accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS,
                    changeNote = "test",
                ).also { ordinaryDocuments += it }
            encounterApp {
                p.asMember(client = client, member = p.w.board) { it.deletePulpitRecording(other.toString()) }.failure<NotFoundException>()
                transaction { DocumentTable.selectAll().where { DocumentTable.id eq other }.any() } shouldBe true
                transaction { PulpitRecordingDocuments.list(null) }.none { it.documentId == other.toString() } shouldBe true
            }
        }

        test("a pulpit recording and its folder cannot be loosened below BOARD_ONLY, not even by an admin") {
            val p = newWorld()
            val (id, _) = store(p)
            val admin = fx.createMember(role = AccountRole.ADMIN)
            val folderId = transaction { DocumentTable.selectAll().where { DocumentTable.id eq id }.single()[DocumentTable.folderId] }
            encounterApp {
                client
                    .runAs(member = admin) { call ->
                        DocumentService(
                            call,
                        ).setDocumentAccessLevel(documentId = id.toString(), accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS)
                    }.failure<ConflictException>()
                client
                    .runAs(member = admin) { call ->
                        DocumentService(
                            call,
                        ).setFolderAccessLevel(folderId = folderId.toString(), accessLevel = DocumentAccessLevel.PUBLIC_MEMBERS)
                    }.failure<ConflictException>()
                transaction { DocumentTable.selectAll().where { DocumentTable.id eq id }.single()[DocumentTable.accessLevel] } shouldBe
                    DocumentAccessLevel.BOARD_ONLY
            }
        }
    })
