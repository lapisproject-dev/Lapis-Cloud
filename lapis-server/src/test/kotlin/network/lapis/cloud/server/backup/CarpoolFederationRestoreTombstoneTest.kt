package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolTeaserTombstonePolicy
import network.lapis.cloud.server.carpool.CarpoolWithdrawalKey
import network.lapis.cloud.server.carpool.CarpoolWithdrawalTarget
import network.lapis.cloud.server.carpool.feed.TEST_IDS
import network.lapis.cloud.server.carpool.withWithdrawalKey
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
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
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A restore tells the partners: one global carpool tombstone per open, shared OFFER of the backup (the consents themselves come back cleared).
 * Real exports and real restores on H2; the teaser key is set (and reset) by the test.
 */
class CarpoolFederationRestoreTombstoneTest :
    FunSpec({
        val at = LocalDateTime(2027, 1, 1, 10, 0)
        val today =
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.UTC)
                .date

        class Seeded(
            val admin: Uuid,
            val open: Uuid,
            val notShared: Uuid,
            val expired: Uuid,
            val request: Uuid,
        )

        fun seed(db: org.jetbrains.exposed.v1.jdbc.Database): Seeded =
            transaction(db) {
                val adminId = Uuid.random()
                MemberTable.insert {
                    it[id] = adminId
                    it[displayName] = "Carpool Tombstone Admin"
                    it[email] = "carpool-tombstone-$adminId@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = adminId
                    it.legacyAccountRole(role = AccountRole.ADMIN)
                }

                fun posting(
                    kind: CarpoolPostingType,
                    departure: LocalDate,
                    shared: Boolean,
                ): Uuid {
                    val pid = Uuid.random()
                    CarpoolPostingTable.insert {
                        it[id] = pid
                        it[authorMemberId] = adminId
                        it[type] = kind
                        it[fromPlace] = "Braunschweig"
                        it[toPlace] = "Hannover"
                        it[departureDate] = departure
                        it[departureTime] = null
                        it[seatsOffered] = if (kind == CarpoolPostingType.OFFER) 2 else null
                        it[notes] = null
                        it[createdAt] = at
                        it[updatedAt] = at
                    }
                    if (shared) {
                        CarpoolPostingTable.update({ CarpoolPostingTable.id eq pid }) {
                            it[federationOptIn] = true
                            it[federationOptInAt] = at
                        }
                    }
                    return pid
                }
                val seeded =
                    Seeded(
                        admin = adminId,
                        open = posting(CarpoolPostingType.OFFER, today.plus(30, DateTimeUnit.DAY), shared = true),
                        notShared = posting(CarpoolPostingType.OFFER, today.plus(30, DateTimeUnit.DAY), shared = false),
                        expired = posting(CarpoolPostingType.OFFER, today.minus(10, DateTimeUnit.DAY), shared = true),
                        request = posting(CarpoolPostingType.REQUEST, today.plus(30, DateTimeUnit.DAY), shared = true),
                    )
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
                seeded
            }

        fun export(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            storage: File,
            actor: CurrentMember,
        ): File {
            val bundle = File.createTempFile("carpool-tombstone-bundle-", ".zip")
            bundle.outputStream().use { out ->
                OrganizationExportService(database = db, documentStorageRoot = storage).streamExport(actor = actor, sink = out)
            }
            return bundle
        }

        fun tombstones(db: org.jetbrains.exposed.v1.jdbc.Database) =
            transaction(db) {
                CarpoolTeaserTombstoneTable.selectAll().toList()
            }

        fun optIn(
            db: org.jetbrains.exposed.v1.jdbc.Database,
            id: Uuid,
        ) = transaction(db) {
            CarpoolPostingTable
                .selectAll()
                .where { CarpoolPostingTable.id eq id }
                .single()[CarpoolPostingTable.federationOptIn]
        }

        fun fresh(name: String) = TestDatabaseFactory.freshMigratedH2Database("$name-${Uuid.random()}")

        fun withBundle(block: (Seeded, CurrentMember, File, File) -> Unit) {
            val sourceDb = fresh("carpool-restore-tomb-source")
            val sourceStorage = Files.createTempDirectory("carpool-restore-tomb-source").toFile()
            val seeded = seed(sourceDb)
            val actor = CurrentMember(memberId = seeded.admin, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = export(sourceDb, sourceStorage, actor)
            try {
                block(seeded, actor, bundle, sourceStorage)
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
            }
        }

        test("a restore writes ONE global tombstone per open shared offer and none for unshared, expired and request rows") {
            withBundle { s, actor, bundle, _ ->
                val targetDb = fresh("carpool-restore-tomb-target")
                val storage = Files.createTempDirectory("carpool-restore-tomb-target").toFile()
                try {
                    withWithdrawalKey {
                        OrganizationRestoreService(database = targetDb, documentStorageRoot = storage)
                            .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                    }
                    val rows = tombstones(targetDb)
                    rows shouldHaveSize 1
                    val row = rows.single()
                    row[CarpoolTeaserTombstoneTable.teaserId] shouldBe TEST_IDS.of(s.open)
                    row[CarpoolTeaserTombstoneTable.partnerId] shouldBe null
                    row[CarpoolTeaserTombstoneTable.relayOriginHost] shouldBe null
                    val departure = today.plus(30, DateTimeUnit.DAY)
                    row[CarpoolTeaserTombstoneTable.expiresAt] shouldBe
                        CarpoolTeaserTombstonePolicy.expiresAt(
                            createdAt = row[CarpoolTeaserTombstoneTable.createdAt],
                            departure = departure,
                        )
                    // the consents themselves stay cleared and the exchange switched off
                    listOf(s.open, s.notShared, s.expired, s.request).forEach { optIn(targetDb, it) shouldBe false }
                    transaction(targetDb) { CarpoolFederationStore.settings().enabled } shouldBe false
                } finally {
                    storage.deleteRecursively()
                }
            }
        }

        test("without the teaser key the restore still succeeds, writes nothing and counts the miss") {
            withBundle { _, actor, bundle, _ ->
                val targetDb = fresh("carpool-restore-tomb-nokey")
                val storage = Files.createTempDirectory("carpool-restore-tomb-nokey").toFile()
                try {
                    CarpoolWithdrawalKey.resetForTest()
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = storage)
                        .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                    tombstones(targetDb) shouldHaveSize 0
                    CarpoolWithdrawalKey.skippedNoKey.get() shouldBeGreaterThan 0L
                } finally {
                    CarpoolWithdrawalKey.resetForTest()
                    storage.deleteRecursively()
                }
            }
        }

        test("an aborted restore (non-empty target) writes no tombstone") {
            withBundle { _, actor, bundle, _ ->
                val targetDb = fresh("carpool-restore-tomb-nonempty")
                val storage = Files.createTempDirectory("carpool-restore-tomb-nonempty").toFile()
                try {
                    seed(targetDb)
                    withWithdrawalKey {
                        shouldThrow<NonEmptyTargetException> {
                            OrganizationRestoreService(database = targetDb, documentStorageRoot = storage)
                                .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                        }
                    }
                    tombstones(targetDb) shouldHaveSize 0
                } finally {
                    storage.deleteRecursively()
                }
            }
        }

        test("a restore that FAILS after carpool_posting was written still writes the tombstones") {
            withBundle { s, actor, bundle, _ ->
                val targetDb = fresh("carpool-restore-tomb-fail")
                val storage = Files.createTempDirectory("carpool-restore-tomb-fail").toFile()
                val tampered = File.createTempFile("carpool-restore-tomb-tampered-", ".zip")
                try {
                    val order =
                        transaction(targetDb) {
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
                                                    if (it.tableName == lastTable) it.copy(contentSha256 = "0".repeat(64)) else it
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
                    withWithdrawalKey {
                        shouldThrow<RestoreIncompleteException> {
                            OrganizationRestoreService(database = targetDb, documentStorageRoot = storage)
                                .restore(actor = actor, bundleFile = tampered, allowNonEmptyTarget = false)
                        }
                    }
                    tombstones(targetDb).map { it[CarpoolTeaserTombstoneTable.teaserId] } shouldBe listOf(TEST_IDS.of(s.open))
                    optIn(targetDb, s.open) shouldBe false
                } finally {
                    tampered.delete()
                    storage.deleteRecursively()
                }
            }
        }

        test("a withdrawal already in the backup is not revived by the restore") {
            val sourceDb = fresh("carpool-restore-tomb-revive-source")
            val sourceStorage = Files.createTempDirectory("carpool-restore-tomb-revive-source").toFile()
            val targetDb = fresh("carpool-restore-tomb-revive-target")
            val storage = Files.createTempDirectory("carpool-restore-tomb-revive-target").toFile()
            val s = seed(sourceDb)
            val actor = CurrentMember(memberId = s.admin, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            var bundle: File? = null
            try {
                // the open trip was withdrawn before the backup: consent cleared, tombstone written
                withWithdrawalKey {
                    transaction(sourceDb) {
                        CarpoolPostingTable.update({ CarpoolPostingTable.id eq s.open }) {
                            it[federationOptIn] = false
                            it[federationOptInAt] = null
                        }
                        CarpoolFederationStore.withdraw(
                            targets = listOf(CarpoolWithdrawalTarget(postingId = s.open, departureDate = today.plus(30, DateTimeUnit.DAY))),
                            partnerId = null,
                            ids = TEST_IDS,
                            now = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                            clearOptIn = true,
                            requireServed = false,
                        )
                    }
                    bundle = export(sourceDb, sourceStorage, actor)
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = storage)
                        .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                }
                optIn(targetDb, s.open) shouldBe false
                transaction(targetDb) {
                    CarpoolPostingTable
                        .selectAll()
                        .where { CarpoolPostingTable.id eq s.open }
                        .single()[CarpoolPostingTable.federationOptInAt]
                } shouldBe null
                transaction(targetDb) { CarpoolFederationStore.settings().enabled } shouldBe false
                tombstones(targetDb) shouldHaveSize 1
            } finally {
                bundle?.delete()
                sourceStorage.deleteRecursively()
                storage.deleteRecursively()
            }
        }

        test("withdrawAfterRestore is idempotent, and empty or key-less calls write nothing") {
            val db = fresh("carpool-restore-tomb-idem")
            val s = seed(db)
            val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
            val targets = listOf(CarpoolWithdrawalTarget(postingId = s.open, departureDate = today.plus(30, DateTimeUnit.DAY)))
            withWithdrawalKey {
                transaction(db) { CarpoolFederationStore.withdrawAfterRestore(targets = targets, now = now) } shouldBe 1
                transaction(db) { CarpoolFederationStore.withdrawAfterRestore(targets = targets, now = now) } shouldBe 0
                transaction(db) { CarpoolFederationStore.withdrawAfterRestore(targets = emptyList(), now = now) } shouldBe 0
            }
            tombstones(db) shouldHaveSize 1
            CarpoolWithdrawalKey.resetForTest()
            transaction(db) { CarpoolFederationStore.withdrawAfterRestore(targets = targets, now = now) } shouldBe 0
        }

        test("the collector keeps only the backup's own open offers and is capped") {
            val now = LocalDateTime(2027, 6, 1, 12, 0)

            fun row(
                id: String,
                type: String = "OFFER",
                departure: String = "2027-07-01",
            ) = JsonObject(
                mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type), "departure_date" to JsonPrimitive(departure)),
            )
            RestoredCarpoolConsents(now).apply {
                note(row(Uuid.random().toString(), type = "REQUEST"))
                note(row("not-a-uuid"))
                note(row(Uuid.random().toString(), departure = "garbage"))
                note(row(Uuid.random().toString(), departure = "2027-05-30")) // grace period (+2 days) over
                note(JsonObject(emptyMap()))
                targets() shouldHaveSize 0
                note(row(Uuid.random().toString(), departure = "2027-06-01")) // still inside the grace period
                targets() shouldHaveSize 1
            }
            val capped = RestoredCarpoolConsents(now)
            repeat(RestoredCarpoolConsents.CAP + 1) { capped.note(row(Uuid.random().toString())) }
            capped.truncated shouldBe true
            capped.targets() shouldHaveSize RestoredCarpoolConsents.CAP
        }
    })
