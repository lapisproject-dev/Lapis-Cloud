package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.BackupOperationLogTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.MemberStatus
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
 * Proves [OrganizationRestoreService.restore] fails loudly and without partial application (except
 * for the one documented, tested exception -- see the last test in this class) on every kind of
 * corrupted/incompatible bundle, rather than silently producing an incomplete restore.
 */
class OrganizationBackupCorruptBundleTest :
    FunSpec({
        fun freshDb(label: String) = TestDatabaseFactory.freshMigratedH2Database("backup-corrupt-$label-${Uuid.random()}")

        // backup_operation_log is deliberately excluded here -- every restore attempt, including a
        // rejected/failed one, legitimately writes exactly one FAILED row there by design (see
        // OrganizationRestoreService KDoc "Exactly one BackupOperationLogTable row ... regardless of
        // outcome"). "Untouched" below always means every OTHER table.
        fun rowCountsOf(db: org.jetbrains.exposed.v1.jdbc.Database): Map<String, Long> =
            transaction(db) {
                OrganizationSchemaCatalog.exportableTables(this).filter { it.tableName != "backup_operation_log" }.associate { table ->
                    table.tableName to
                        rawConnection().prepareStatement("SELECT COUNT(*) FROM \"${table.tableName}\"").use { ps ->
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                }
            }

        fun backupOperationLogRows(db: org.jetbrains.exposed.v1.jdbc.Database) =
            transaction(db) { BackupOperationLogTable.selectAll().toList() }

        test(
            "a non-ZIP file is rejected as an incompatible bundle, target's organizational data left completely untouched (only a FAILED log row is added)",
        ) {
            val targetDb = freshDb("nonzip")
            val storageRoot = Files.createTempDirectory("corrupt-nonzip-storage").toFile()
            val bogus = File.createTempFile("bogus-", ".zip")
            bogus.writeBytes("this is not a zip file at all".toByteArray(Charsets.UTF_8))
            val actor = adminOf(targetDb)
            val before = rowCountsOf(targetDb)
            val logRowsBefore = backupOperationLogRows(targetDb).size

            try {
                OrganizationRestoreService(
                    database = targetDb,
                    documentStorageRoot = storageRoot,
                ).restore(actor = actor, bundleFile = bogus)
                throw AssertionError("Expected an exception for a non-ZIP bundle")
            } catch (e: Exception) {
                (e is IncompatibleBundleException || e is RestoreIncompleteException) shouldBe true
            } finally {
                bogus.delete()
            }
            rowCountsOf(targetDb) shouldBe before
            val logRowsAfter = backupOperationLogRows(targetDb)
            logRowsAfter.size shouldBe logRowsBefore + 1
            logRowsAfter.last()[BackupOperationLogTable.status] shouldBe BackupOperationStatus.FAILED
        }

        test("a ZIP missing manifest.json is rejected with a clear error") {
            val targetDb = freshDb("nomanifest")
            val storageRoot = Files.createTempDirectory("corrupt-nomanifest-storage").toFile()
            val zipFile = File.createTempFile("no-manifest-", ".zip")
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("data/member.jsonl"))
                zip.closeEntry()
            }

            try {
                OrganizationRestoreService(
                    database = targetDb,
                    documentStorageRoot = storageRoot,
                ).restore(actor = adminOf(targetDb), bundleFile = zipFile)
                throw AssertionError("Expected IncompatibleBundleException")
            } catch (e: IncompatibleBundleException) {
                (e.message?.contains("manifest.json") == true) shouldBe true
            } finally {
                zipFile.delete()
            }
        }

        test("a manifest with an incompatible formatVersion is rejected, naming both versions") {
            val targetDb = freshDb("badversion")
            val storageRoot = Files.createTempDirectory("corrupt-badversion-storage").toFile()
            val zipFile = File.createTempFile("bad-version-", ".zip")
            val manifest =
                BackupManifest(
                    formatVersion = 999,
                    generatedAt = "2027-01-01T00:00:00",
                    generatedByMemberId = Uuid.random().toString(),
                    schemaChecksum = "irrelevant",
                    tables = emptyList(),
                    blobCount = 0,
                    blobBytesTotal = 0,
                )
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                zip.write(Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            try {
                OrganizationRestoreService(
                    database = targetDb,
                    documentStorageRoot = storageRoot,
                ).restore(actor = adminOf(targetDb), bundleFile = zipFile)
                throw AssertionError("Expected IncompatibleBundleException")
            } catch (e: IncompatibleBundleException) {
                (e.message?.contains("999") == true) shouldBe true
                (e.message?.contains(OrganizationExportService.FORMAT_VERSION.toString()) == true) shouldBe true
            } finally {
                zipFile.delete()
            }
        }

        test("a manifest with a tampered schemaChecksum is rejected, zero rows written") {
            val targetDb = freshDb("badchecksum")
            val storageRoot = Files.createTempDirectory("corrupt-badchecksum-storage").toFile()
            val zipFile = File.createTempFile("bad-checksum-", ".zip")
            val manifest =
                BackupManifest(
                    formatVersion = OrganizationExportService.FORMAT_VERSION,
                    generatedAt = "2027-01-01T00:00:00",
                    generatedByMemberId = Uuid.random().toString(),
                    schemaChecksum = "0".repeat(64),
                    tables = emptyList(),
                    blobCount = 0,
                    blobBytesTotal = 0,
                )
            ZipOutputStream(zipFile.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                zip.write(Json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val actor = adminOf(targetDb)
            val before = rowCountsOf(targetDb)

            try {
                OrganizationRestoreService(
                    database = targetDb,
                    documentStorageRoot = storageRoot,
                ).restore(actor = actor, bundleFile = zipFile)
                throw AssertionError("Expected IncompatibleBundleException")
            } catch (e: IncompatibleBundleException) {
                (e.message?.contains("schemaChecksum") == true) shouldBe true
            } finally {
                zipFile.delete()
            }
            rowCountsOf(targetDb) shouldBe before
        }

        test(
            "a table's JSONL content truncated mid-file is detected via the content checksum mismatch, restore reports failure not silent partial success",
        ) {
            val sourceDb = freshDb("truncsource")
            val targetDb = freshDb("trunctarget")
            val sourceStorageRoot = Files.createTempDirectory("corrupt-trunc-source-storage").toFile()
            val targetStorageRoot = Files.createTempDirectory("corrupt-trunc-target-storage").toFile()
            val adminId = seedAdmin(database = sourceDb, displayName = "Truncation-Test Admin")

            val originalBundle = File.createTempFile("trunc-original-", ".zip")
            originalBundle.outputStream().use { out ->
                OrganizationExportService(
                    database = sourceDb,
                    documentStorageRoot = sourceStorageRoot,
                ).streamExport(
                    actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                    sink = out,
                )
            }

            // Rewrite the bundle with the "member" table's data/<table>.jsonl entry's content
            // mutated -- a same-length character substitution inside the displayName string value,
            // so the JSON stays syntactically valid (this exercises the dedicated `contentSha256`
            // mismatch detection path specifically, not just a generic JSON-parse-error path) -- but
            // the manifest's declared contentSha256 is left untouched, simulating transport
            // corruption the ZIP container itself doesn't catch.
            val tamperedBundle = File.createTempFile("trunc-tampered-", ".zip")
            ZipFile(originalBundle).use { source ->
                ZipOutputStream(tamperedBundle.outputStream()).use { out ->
                    val entries = source.entries().asSequence().toList()
                    entries.forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == DATA_ENTRY_PREFIX + "member.jsonl") {
                            val text = source.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                            val tampered = text.replace("Truncation-Test Admin", "truncation-test Admin")
                            (tampered != text) shouldBe true
                            out.write(tampered.toByteArray(Charsets.UTF_8))
                        } else {
                            source.getInputStream(entry).copyTo(out)
                        }
                        out.closeEntry()
                    }
                }
            }

            try {
                OrganizationRestoreService(
                    database = targetDb,
                    documentStorageRoot = targetStorageRoot,
                ).restore(
                    actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                    bundleFile = tamperedBundle,
                )
                throw AssertionError("Expected RestoreIncompleteException")
            } catch (e: RestoreIncompleteException) {
                (e.message?.contains("checksum") == true) shouldBe true
                (e.message?.contains("member") == true) shouldBe true
            } finally {
                originalBundle.delete()
                tamperedBundle.delete()
            }
        }

        test("a blob entry with a Zip-Slip path is rejected before any write outside documentStorageRoot") {
            // Built on top of a genuine, valid export (real member row for the FK the restore's own
            // log-write needs, real matching checksums) with one extra, malicious ZIP entry injected
            // afterward -- isolates exactly the Zip-Slip guard, nothing else.
            val sourceDb = freshDb("zipslipsource")
            val targetDb = freshDb("zipsliptarget")
            val sourceStorageRoot = Files.createTempDirectory("corrupt-zipslip-source-storage").toFile()
            val targetStorageRoot = Files.createTempDirectory("corrupt-zipslip-target-storage").toFile()
            val adminId = seedAdmin(database = sourceDb)

            val validBundle = File.createTempFile("zipslip-valid-", ".zip")
            validBundle.outputStream().use { out ->
                OrganizationExportService(
                    database = sourceDb,
                    documentStorageRoot = sourceStorageRoot,
                ).streamExport(
                    actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                    sink = out,
                )
            }

            val outsideCanary = File(targetStorageRoot.parentFile, "zipslip-canary-${Uuid.random()}.txt")
            outsideCanary.writeText("untouched")

            val tamperedBundle = File.createTempFile("zipslip-tampered-", ".zip")
            ZipFile(validBundle).use { source ->
                ZipOutputStream(tamperedBundle.outputStream()).use { out ->
                    source.entries().asSequence().forEach { entry ->
                        out.putNextEntry(ZipEntry(entry.name))
                        source.getInputStream(entry).copyTo(out)
                        out.closeEntry()
                    }
                    out.putNextEntry(ZipEntry(BLOB_ENTRY_PREFIX + "../../${outsideCanary.name}"))
                    out.write("evil".toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
            }

            try {
                val result =
                    OrganizationRestoreService(
                        database = targetDb,
                        documentStorageRoot = targetStorageRoot,
                    ).restore(
                        actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                        bundleFile = tamperedBundle,
                    )
                (result.warnings.any { it.contains("unsafe path") }) shouldBe true
            } finally {
                validBundle.delete()
                tamperedBundle.delete()
            }
            outsideCanary.readText() shouldBe "untouched"
            outsideCanary.delete()
        }
    })

private fun seedAdmin(
    database: org.jetbrains.exposed.v1.jdbc.Database,
    displayName: String = "Corrupt-Test Admin",
): Uuid {
    val adminId = Uuid.random()
    transaction(database) {
        MemberTable.insert {
            it[id] = adminId
            it[MemberTable.displayName] = displayName
            it[email] = "corrupt-test-admin-${Uuid.random()}@example.org"
            it[status] = MemberStatus.ACTIVE
            it[joinedAt] = LocalDate(2027, 1, 1)
            it[membershipTierId] = null
        }
        AccountTable.insert {
            it[id] = Uuid.random()
            it[memberId] = adminId
            it[role] = AccountRole.ADMIN
        }
    }
    return adminId
}

private fun adminOf(database: org.jetbrains.exposed.v1.jdbc.Database): CurrentMember {
    val existing =
        transaction(database) {
            MemberTable
                .selectAll()
                .limit(1)
                .map { it[MemberTable.id] }
                .singleOrNull()
        }
    val memberId = existing ?: seedAdmin(database = database)
    return CurrentMember(memberId = memberId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
}
