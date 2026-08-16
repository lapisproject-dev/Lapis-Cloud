package network.lapis.cloud.server.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * Mirrors `network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest`'s own end-to-end coverage
 * proof: runs a real [OrganizationExportService.streamExport] against the shared H2 test database
 * and asserts the resulting ZIP's `data/<table>.jsonl` entries and `manifest.json` cover every
 * table an independently-issued `information_schema` query reports -- not just the tables this
 * test's author happened to remember to check.
 */
class OrganizationBackupCoverageTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        test("streamExport's ZIP entries and manifest cover every real table in the live schema") {
            val database = DatabaseConfig.connect()
            val expectedTables =
                transaction {
                    OrganizationSchemaCatalog.exportableTables(this).map { it.tableName }.toSet()
                }

            val storageRoot = Files.createTempDirectory("coverage-storage").toFile()
            val bytes =
                ByteArrayOutputStream()
                    .also { out ->
                        OrganizationExportService(
                            database = database,
                            documentStorageRoot = storageRoot,
                        ).streamExport(
                            actor = CurrentMember(memberId = ADMIN_ID, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                            sink = out,
                        )
                    }.toByteArray()

            val dataEntryTables = mutableSetOf<String>()
            var manifestJson: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name.startsWith(DATA_ENTRY_PREFIX) && entry.name.endsWith(".jsonl") -> {
                            dataEntryTables += entry.name.removePrefix(DATA_ENTRY_PREFIX).removeSuffix(".jsonl")
                        }
                        entry.name == MANIFEST_ENTRY_NAME -> {
                            manifestJson = zip.readBytes().toString(Charsets.UTF_8)
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            dataEntryTables shouldContainExactlyInAnyOrder expectedTables

            val manifest = Json.decodeFromString(BackupManifest.serializer(), manifestJson!!)
            manifest.tables.map { it.tableName }.toSet() shouldContainExactlyInAnyOrder expectedTables
            manifest.formatVersion shouldBe OrganizationExportService.FORMAT_VERSION
        }
    })
