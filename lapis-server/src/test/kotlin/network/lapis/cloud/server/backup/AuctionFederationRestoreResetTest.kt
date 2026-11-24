package network.lapis.cloud.server.backup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.auction.federation.AuctionFederationKey
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionTeaserIds
import network.lapis.cloud.server.carpool.feed.TEST_BOX
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.uuid.Uuid

private val IDS = AuctionTeaserIds(TEST_BOX)

/**
 * V1.9.322 (c4) -- a REAL restore run: the exchange is off and every consent cleared (also a stale consent moment), and the partners are told with
 * ONE global tombstone per auction that was shared in the backup and is still open (not for an expired one, not for an LTR row of a manipulated
 * backup). Without the teaser key nothing is written. An aborted restore (non-empty target) changes nothing.
 */
class AuctionFederationRestoreResetTest :
    FunSpec({
        val farFuture = LocalDateTime(2099, 1, 1, 10, 0)
        val past = LocalDateTime(2020, 1, 1, 10, 0)
        val stamp = LocalDateTime(2027, 1, 1, 10, 0)

        class Seed(
            val adminId: Uuid,
            val open: Uuid,
            val expired: Uuid,
            val ltr: Uuid,
            val stale: Uuid,
        )

        fun seed(db: Database): Seed {
            val s = Seed(Uuid.random(), Uuid.random(), Uuid.random(), Uuid.random(), Uuid.random())
            transaction(db) {
                MemberTable.insert {
                    it[id] = s.adminId
                    it[displayName] = "Auction Reset Admin"
                    it[email] = "auction-reset-${s.adminId}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = s.adminId
                    it.legacyAccountRole(role = AccountRole.ADMIN)
                }

                fun row(
                    auctionId: Uuid,
                    end: LocalDateTime,
                    cur: String,
                ) = AuctionTable.insert {
                    it[AuctionTable.id] = auctionId
                    it[title] = "Reset Auction"
                    it[description] = "desc"
                    it[status] = AuctionStatus.OPEN
                    it[sellerMemberId] = s.adminId
                    it[listingFeeLtr] = BigDecimal("0.00")
                    it[createdAt] = stamp
                    it[endsAt] = end
                    it[currency] = cur
                    if (cur == "LTR") {
                        it[startingBidLtr] = BigDecimal("1.00")
                    } else {
                        it[startingPriceMinor] = 1000L
                        it[sellerDisclaimerVersion] = "test"
                    }
                }
                row(s.open, farFuture, "EUR")
                row(s.expired, past, "EUR")
                row(s.ltr, farFuture, "LTR")
                row(s.stale, farFuture, "EUR")
                AuctionFederationStore.setSettings(enabled = true, hopLimit = 2)
                listOf(s.open, s.expired, s.ltr).forEach { AuctionFederationStore.setOptIn(auctionId = it, value = true, now = stamp) }
                // a stale moment without the consent (an older or manipulated backup)
                AuctionTable.update({ AuctionTable.id eq s.stale }) { it[federationOptInAt] = stamp }
            }
            return s
        }

        fun globalTombstones(db: Database): List<String> =
            transaction(db) {
                AuctionTeaserTombstoneTable
                    .selectAll()
                    .where { AuctionTeaserTombstoneTable.partnerId.isNull() }
                    .map { it[AuctionTeaserTombstoneTable.teaserId] }
            }

        fun export(
            sourceDb: Database,
            actor: CurrentMember,
            storage: File,
        ): File {
            val bundle = File.createTempFile("auction-reset-bundle-", ".zip")
            bundle.outputStream().use { out ->
                OrganizationExportService(database = sourceDb, documentStorageRoot = storage).streamExport(actor = actor, sink = out)
            }
            return bundle
        }

        beforeTest {
            AuctionFederationKey.resetForTest()
            AuctionFederationKey.configure(IDS)
        }
        afterSpec { AuctionFederationKey.resetForTest() }

        test("a finished restore clears switch, consents and stale moment and writes ONE tombstone for the open shared auction only") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-auction-reset-source-${Uuid.random()}")
            val freshDb = TestDatabaseFactory.freshMigratedH2Database("backup-auction-reset-fresh-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-auction-reset-source").toFile()
            val freshStorage = Files.createTempDirectory("backup-auction-reset-fresh").toFile()
            val s = seed(sourceDb)
            val actor = CurrentMember(memberId = s.adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = export(sourceDb, actor, sourceStorage)
            try {
                OrganizationRestoreService(database = freshDb, documentStorageRoot = freshStorage)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                transaction(freshDb) { AuctionFederationStore.settings().enabled } shouldBe false
                transaction(freshDb) { AuctionFederationStore.countOptIns() } shouldBe 0L
                transaction(freshDb) { AuctionFederationStore.optInAt(s.stale) } shouldBe null
                transaction(freshDb) { AuctionFederationStore.optInAt(s.open) } shouldBe null
                globalTombstones(freshDb) shouldBe listOf(IDS.of(s.open))
                // one audit entry with the number of consents (3) and no amount
                val audit =
                    transaction(freshDb) {
                        AuditLogEntryTable.selectAll().map { it[AuditLogEntryTable.afterSnapshot] ?: "" }.filter {
                            "AUCTION_RESET_BY_RESTORE" in
                                it
                        }
                    }
                audit.size shouldBe 1
                ("\"subject\":\"3\"" in audit.single()) shouldBe true
                (audit.single().contains("Minor") || audit.single().contains("price")) shouldBe false
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                freshStorage.deleteRecursively()
            }
        }

        test("without the teaser key the restore still resets, writes no tombstone and does not fail") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-auction-reset-nokey-source-${Uuid.random()}")
            val freshDb = TestDatabaseFactory.freshMigratedH2Database("backup-auction-reset-nokey-fresh-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-auction-reset-nokey-source").toFile()
            val freshStorage = Files.createTempDirectory("backup-auction-reset-nokey-fresh").toFile()
            val s = seed(sourceDb)
            val actor = CurrentMember(memberId = s.adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = export(sourceDb, actor, sourceStorage)
            try {
                AuctionFederationKey.configure(null)
                OrganizationRestoreService(database = freshDb, documentStorageRoot = freshStorage)
                    .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                transaction(freshDb) { AuctionFederationStore.countOptIns() } shouldBe 0L
                globalTombstones(freshDb) shouldBe emptyList()
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                freshStorage.deleteRecursively()
            }
        }

        test("an aborted restore (non-empty target) changes nothing: no tombstone, the consent stays") {
            val sourceDb = TestDatabaseFactory.freshMigratedH2Database("backup-auction-reset-abort-source-${Uuid.random()}")
            val targetDb = TestDatabaseFactory.freshMigratedH2Database("backup-auction-reset-abort-target-${Uuid.random()}")
            val sourceStorage = Files.createTempDirectory("backup-auction-reset-abort-source").toFile()
            val targetStorage = Files.createTempDirectory("backup-auction-reset-abort-target").toFile()
            val s = seed(sourceDb)
            val t = seed(targetDb)
            val actor = CurrentMember(memberId = s.adminId, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
            val bundle = export(sourceDb, actor, sourceStorage)
            try {
                shouldThrow<NonEmptyTargetException> {
                    OrganizationRestoreService(database = targetDb, documentStorageRoot = targetStorage)
                        .restore(actor = actor, bundleFile = bundle, allowNonEmptyTarget = false)
                }
                transaction(targetDb) { AuctionFederationStore.settings().enabled } shouldBe true
                transaction(targetDb) { AuctionFederationStore.optIn(t.open) } shouldBe true
                globalTombstones(targetDb) shouldBe emptyList()
            } finally {
                bundle.delete()
                sourceStorage.deleteRecursively()
                targetStorage.deleteRecursively()
            }
        }
    })
