package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberPublicBioTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- `member_public_bio` is deliberately NOT part of the ADMIN-only
 * whole-organization backup (a PRIVATE self-description must not become readable through it), while
 * `regional_chapter` stays in (its description and the `crest_*` columns travel) but the crest FILES
 * do not: after a restore the row points at a crest that has to be uploaded again.
 */
class MemberPublicBioBackupExclusionTest :
    FunSpec({
        test(
            "the bundle holds no member_public_bio data; the chapter row and its description travel, the crest file does not; the restore works",
        ) {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-bio-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-bio-target-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-bio-source-storage").toFile()
            val targetStorage = Files.createTempDirectory("backup-bio-target-storage").toFile()
            val adminId = Uuid.random()
            val crestFileId = Uuid.random()
            val crestFile = sourceStorage.resolve("chapter-crests/$crestFileId.png")
            crestFile.parentFile.mkdirs()
            crestFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3))

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Bio Backup Admin"
                    it[email] = "bio-backup-admin@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[role] = AccountRole.ADMIN
                }
                MemberPublicBioTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[bioText] = "GEHEIMER-BIO-TEXT-DARF-NICHT-IM-BACKUP-STEHEN"
                    it[updatedAt] = DbClock.nowLocalDateTime()
                    it[consentGrantedAt] = null
                    it[consentTextVersion] = null
                }
                RegionalChapterTable.insert {
                    it[id] = Uuid.random()
                    it[name] = "Landesverband Backup"
                    it[nameKey] = "landesverband backup"
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[description] = "Beschreibung reist mit"
                    it[crestImageId] = crestFileId
                    it[crestPublicToken] = "C".repeat(43)
                    it[crestContentType] = "image/png"
                }
            }

            val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = File.createTempFile("bio-backup-bundle-", ".zip")
            try {
                bundle.outputStream().use { out ->
                    OrganizationExportService(
                        database = sourceDb,
                        documentStorageRoot = sourceStorage,
                    ).streamExport(actor = actor, sink = out)
                }
                ZipFile(bundle).use { zip ->
                    val names =
                        zip
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .toList()
                    names.none { it.contains("member_public_bio") } shouldBe true
                    names.none { it.contains("chapter-crests") } shouldBe true
                    val all =
                        names.joinToString("\n") { name ->
                            String(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }, Charsets.ISO_8859_1)
                        }
                    all.contains("GEHEIMER-BIO-TEXT-DARF-NICHT-IM-BACKUP-STEHEN") shouldBe false
                    // The chapter itself travels, including its description.
                    all.contains("Beschreibung reist mit") shouldBe true
                }

                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorage)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                transaction(targetDb) { MemberPublicBioTable.selectAll().count() } shouldBe 0L
                val restored = transaction(targetDb) { RegionalChapterTable.selectAll().single() }
                restored[RegionalChapterTable.description] shouldBe "Beschreibung reist mit"
                // The crest file is NOT restored -- the row points at a file that has to be uploaded again (documented).
                targetStorage.resolve("chapter-crests").exists() shouldBe false
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                targetStorage.deleteRecursively()
            }
        }
    })
