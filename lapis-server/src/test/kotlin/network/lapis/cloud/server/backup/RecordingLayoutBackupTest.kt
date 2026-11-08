package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceBroadcastView
import network.lapis.cloud.shared.domain.ConferenceRecordingLayout
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * V1.9.294 -- the two new columns of `conference_recording` (`layout`, `single_participant_identity`) travel in the whole-organization backup. A
 * bundle written before them (their columns missing from the manifest) is rejected as a whole: it is never restored with a silent GRID default.
 */
class RecordingLayoutBackupTest :
    FunSpec({
        val adminId = Uuid.random()
        val roomId = Uuid.random()
        val grid = Uuid.random()
        val speaker = Uuid.random()
        val single = Uuid.random()
        val chosen = Uuid.random().toString()
        val at = LocalDateTime(2027, 1, 2, 9, 0)

        fun seed(db: org.jetbrains.exposed.v1.jdbc.Database) =
            transaction(db) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Layout Backup"
                    it[email] = "layout-backup@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[passwordHash] = "a-fake-bcrypt-hash-for-backup-verification-only"
                    it[role] = AccountRole.ADMIN
                }
                ConferenceRoomTable.insert {
                    it[id] = roomId
                    it[title] = "Layout Backup Room"
                    it[description] = ""
                    it[livekitRoomName] = "lc-layout-$roomId"
                    it[createdByMemberId] = adminId
                    it[createdAt] = at
                    it[endedAt] = at
                    it[maxParticipants] = 25
                }
                listOf(
                    Triple(grid, ConferenceRecordingLayout.GRID, null),
                    Triple(speaker, ConferenceRecordingLayout.SPEAKER, null),
                    Triple(single, ConferenceRecordingLayout.SINGLE_PARTICIPANT, chosen),
                ).forEach { (recording, recordingLayout, identity) ->
                    ConferenceRecordingTable.insert {
                        it[id] = recording
                        it[ConferenceRecordingTable.roomId] = roomId
                        it[startedByMemberId] = adminId
                        it[startedAt] = at
                        it[status] = ConferenceRecordingStatus.RECORDING
                        it[accessLevel] = DocumentAccessLevel.BOARD_ONLY
                        it[rawDir] = recording.toString()
                        it[broadcastView] = ConferenceBroadcastView.RASTER
                        it[layout] = recordingLayout
                        it[singleParticipantIdentity] = identity
                    }
                }
            }

        fun export(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            root: File,
            bundle: File,
        ) {
            val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            bundle.outputStream().use { out ->
                OrganizationExportService(database = db, documentStorageRoot = root).streamExport(actor = actor, sink = out)
            }
        }

        test("GRID, SPEAKER and SINGLE_PARTICIPANT (with its identity) round-trip through export and restore") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("layout-backup-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("layout-backup-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("layout-source").toFile()
            val targetRoot = Files.createTempDirectory("layout-target").toFile()
            val bundle = File.createTempFile("layout-bundle-", ".zip")
            try {
                seed(sourceDb)
                export(sourceDb, sourceRoot, bundle)
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                transaction(targetDb) {
                    fun r(id: Uuid) = ConferenceRecordingTable.selectAll().where { ConferenceRecordingTable.id eq id }.single()
                    r(grid)[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.GRID
                    r(grid)[ConferenceRecordingTable.singleParticipantIdentity] shouldBe null
                    r(speaker)[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.SPEAKER
                    r(single)[ConferenceRecordingTable.layout] shouldBe ConferenceRecordingLayout.SINGLE_PARTICIPANT
                    r(single)[ConferenceRecordingTable.singleParticipantIdentity] shouldBe chosen
                }
            } finally {
                bundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }

        test("a bundle from before the two columns is rejected as a whole and writes nothing (never a silent GRID default)") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("layout-old-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("layout-old-target-${Uuid.random()}")
            val sourceRoot = Files.createTempDirectory("layout-old-source").toFile()
            val targetRoot = Files.createTempDirectory("layout-old-target").toFile()
            val bundle = File.createTempFile("layout-old-bundle-", ".zip")
            val oldBundle = File.createTempFile("layout-old-rewritten-", ".zip")
            try {
                seed(sourceDb)
                export(sourceDb, sourceRoot, bundle)
                // rewrite the manifest as an older release would have written it: no `layout`, no `single_participant_identity`
                ZipFile(bundle).use { zip ->
                    ZipOutputStream(oldBundle.outputStream()).use { out ->
                        zip.entries().asSequence().forEach { entry ->
                            out.putNextEntry(ZipEntry(entry.name))
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (entry.name == MANIFEST_ENTRY_NAME) {
                                val manifest = Json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
                                val old =
                                    manifest.copy(
                                        tables =
                                            manifest.tables.map {
                                                if (it.tableName ==
                                                    "conference_recording"
                                                ) {
                                                    it.copy(columns = it.columns - "layout" - "single_participant_identity")
                                                } else {
                                                    it
                                                }
                                            },
                                    )
                                out.write(Json.encodeToString(BackupManifest.serializer(), old).toByteArray(Charsets.UTF_8))
                            } else {
                                out.write(bytes)
                            }
                            out.closeEntry()
                        }
                    }
                }
                val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                shouldThrow<IncompatibleBundleException> {
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetRoot)
                        .restore(actor = actor, bundleFile = oldBundle, allowNonEmptyTarget = false)
                }
                transaction(targetDb) { ConferenceRecordingTable.selectAll().count() shouldBe 0L }
            } finally {
                bundle.delete()
                oldBundle.delete()
                sourceRoot.deleteRecursively()
                targetRoot.deleteRecursively()
            }
        }
    })
