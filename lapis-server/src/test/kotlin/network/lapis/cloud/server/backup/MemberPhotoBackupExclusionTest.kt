package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- `member_photo` is deliberately NOT part of the ADMIN-only
 * whole-organization backup (see `OrganizationSchemaCatalog.EXCLUDED_TABLES`): the bundle carries
 * neither the row (public token = bearer secret, consent state) nor any photo file, and a restore
 * into a fresh instance works and leaves the table empty.
 */
class MemberPhotoBackupExclusionTest :
    FunSpec({
        test(
            "a bundle exported from an instance with a member photo holds no member_photo data and no photo file; the restore works and member_photo stays empty",
        ) {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-photo-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-photo-target-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-photo-source-storage").toFile()
            val targetStorage = Files.createTempDirectory("backup-photo-target-storage").toFile()
            val adminId = Uuid.random()
            val photoFile = sourceStorage.resolve("member-photos/${Uuid.random()}.jpg")
            photoFile.parentFile.mkdirs()
            photoFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3))

            transaction(sourceDb) {
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Photo Backup Admin"
                    it[email] = "photo-backup-admin@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[role] = AccountRole.ADMIN
                }
                MemberPhotoTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it[storageKey] = photoFile.name
                    it[contentType] = "image/jpeg"
                    it[widthPx] = 800
                    it[heightPx] = 800
                    it[sizeBytes] = 6L
                    it[uploadedAt] = DbClock.nowLocalDateTime()
                    it[visibility] = MemberPhotoVisibility.PUBLIC
                    it[publicToken] = "SECRET-PUBLIC-TOKEN-SHOULD-NEVER-BE-IN-A-BACKUP"
                    it[consentGrantedAt] = DbClock.nowLocalDateTime()
                    it[consentTextVersion] = "member-photo-public-v1"
                }
            }

            val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = File.createTempFile("photo-backup-bundle-", ".zip")
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
                    names.none { it.contains("member_photo") } shouldBe true
                    names.none { it.contains("member-photos") } shouldBe true
                    // and the secret token is nowhere in any entry
                    names.forEach { name ->
                        val content = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                        String(content, Charsets.ISO_8859_1).contains("SECRET-PUBLIC-TOKEN-SHOULD-NEVER-BE-IN-A-BACKUP") shouldBe false
                    }
                }

                OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorage)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                transaction(targetDb) { MemberPhotoTable.selectAll().count() } shouldBe 0L
                transaction(targetDb) { MemberTable.selectAll().count() } shouldBe 1L
                targetStorage.resolve("member-photos").exists() shouldBe false
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                targetStorage.deleteRecursively()
            }
        }
    })
