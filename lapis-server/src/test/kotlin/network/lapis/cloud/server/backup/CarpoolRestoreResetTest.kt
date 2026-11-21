package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolPostingType
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
 * V1.9.319 (wave 7, F4) -- a REAL restore run switches the carpool exchange off and clears every consent, and ONLY a restore that finished does
 * so: an aborted one (non-empty target) leaves the target exactly as it was.
 */
class CarpoolRestoreResetTest :
    FunSpec({
        val at = LocalDateTime(2027, 1, 1, 10, 0)

        fun seed(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            adminId: Uuid,
            postingId: Uuid,
        ) = transaction(db) {
            MemberTable.insert {
                it[id] = adminId
                it[displayName] = "Carpool Reset Admin"
                it[email] = "carpool-reset-$adminId@example.org"
                it[status] = MemberStatus.ACTIVE
                it[joinedAt] = LocalDate(2027, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[id] = Uuid.random()
                it[memberId] = adminId
                it.legacyAccountRole(role = AccountRole.ADMIN)
            }
            CarpoolPostingTable.insert {
                it[id] = postingId
                it[authorMemberId] = adminId
                it[type] = CarpoolPostingType.OFFER
                it[fromPlace] = "Braunschweig"
                it[toPlace] = "Hannover"
                it[departureDate] = LocalDate(2027, 3, 1)
                it[departureTime] = null
                it[seatsOffered] = 2
                it[notes] = null
                it[createdAt] = at
                it[updatedAt] = at
            }
            CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            CarpoolFederationStore.setOptIn(postingId = postingId, value = true, now = at)
        }

        fun optIn(id: Uuid) =
            CarpoolPostingTable
                .selectAll()
                .where { CarpoolPostingTable.id eq id }
                .single()[CarpoolPostingTable.federationOptIn]

        test("a finished restore leaves the exchange off and the consent cleared; an aborted one (non-empty target) resets nothing") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-reset-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-reset-target-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-carpool-reset-source").toFile()
            val targetStorage = Files.createTempDirectory("backup-carpool-reset-target").toFile()
            val adminId = Uuid.random()
            val postingId = Uuid.random()
            seed(sourceDb, adminId, postingId)
            val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = File.createTempFile("carpool-reset-bundle-", ".zip")
            try {
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceStorage)
                        .streamExport(actor = actor, sink = out)
                }
                // the target already has data: the restore is refused and must not touch anything (here: the consent stays as it is)
                val otherPosting = Uuid.random()
                seed(targetDb, Uuid.random(), otherPosting)
                shouldThrow<NonEmptyTargetException> {
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorage)
                        .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                }
                transaction(targetDb) { CarpoolFederationStore.settings().enabled } shouldBe true
                transaction(targetDb) {
                    optIn(otherPosting)
                } shouldBe true

                // a finished restore into a fresh target: off and cleared
                val freshDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-reset-fresh-${Uuid.random()}")
                val freshStorage = Files.createTempDirectory("backup-carpool-reset-fresh").toFile()
                try {
                    OrganizationRestoreService(database = freshDb, documentStorageRoot = freshStorage)
                        .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                    transaction(freshDb) { CarpoolFederationStore.settings().enabled } shouldBe false
                    transaction(freshDb) {
                        optIn(postingId)
                    } shouldBe false
                } finally {
                    freshStorage.deleteRecursively()
                }
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                targetStorage.deleteRecursively()
            }
        }

        test("a restore that FAILS after carpool_posting was written still leaves the exchange off and the consent cleared") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-reset-fail-source-${Uuid.random()}")
            val freshDb = TestDatabaseFactory.freshMigratedH2Database("backup-carpool-reset-fail-target-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-carpool-reset-fail-source").toFile()
            val freshStorage = Files.createTempDirectory("backup-carpool-reset-fail-target").toFile()
            val adminId = Uuid.random()
            val postingId = Uuid.random()
            seed(sourceDb, adminId, postingId)
            val actor = CurrentMember(memberId = adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = File.createTempFile("carpool-reset-bundle-", ".zip")
            val tampered = File.createTempFile("carpool-reset-bundle-tampered-", ".zip")
            try {
                bundle.outputStream().use { out ->
                    OrganizationExportService(database = sourceDb, documentStorageRoot = sourceStorage)
                        .streamExport(actor = actor, sink = out)
                }
                val order =
                    transaction(freshDb) {
                        OrganizationSchemaCatalog.restoreOrder(OrganizationSchemaCatalog.exportableTables(this)).map { it.tableName }
                    }
                val lastTable = order.last()
                (order.indexOf("carpool_posting") < order.lastIndex) shouldBe true
                ZipFile(bundle).use { zip ->
                    ZipOutputStream(tampered.outputStream()).use { out ->
                        zip.entries().asSequence().forEach { entry ->
                            out.putNextEntry(ZipEntry(entry.name))
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            if (entry.name == MANIFEST_ENTRY_NAME) {
                                val manifest = Json.decodeFromString(BackupManifest.serializer(), bytes.toString(Charsets.UTF_8))
                                val broken =
                                    manifest.copy(
                                        tables =
                                            manifest.tables.map {
                                                if (it.tableName ==
                                                    lastTable
                                                ) {
                                                    it.copy(contentSha256 = "0".repeat(64))
                                                } else {
                                                    it
                                                }
                                            },
                                    )
                                out.write(Json.encodeToString(BackupManifest.serializer(), broken).toByteArray(Charsets.UTF_8))
                            } else {
                                out.write(bytes)
                            }
                            out.closeEntry()
                        }
                    }
                }
                shouldThrow<RestoreIncompleteException> {
                    OrganizationRestoreService(database = freshDb, documentStorageRoot = freshStorage)
                        .restore(actor = actor, bundleFile = tampered, allowNonEmptyTarget = false)
                }
                transaction(freshDb) { optIn(postingId) } shouldBe false
                transaction(freshDb) { CarpoolFederationStore.settings().enabled } shouldBe false
            } finally {
                bundle.delete()
                tampered.delete()
                sourceStorage.deleteRecursively()
                freshStorage.deleteRecursively()
            }
        }
    })
