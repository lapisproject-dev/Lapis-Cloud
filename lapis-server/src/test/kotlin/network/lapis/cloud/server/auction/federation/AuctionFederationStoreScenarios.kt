package network.lapis.cloud.server.auction.federation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 12, 1, 10, 0, 0)

/**
 * V1.9.322 (step c1) -- the foundation of the auction hint exchange on H2 AND PostgreSQL with the same assertions: settings (default off, hop limit
 * 1, 3 rejected), the consent flag with its moment, and the restore reset (exchange off, every consent cleared, one audit entry without an actor).
 */
abstract class AuctionFederationStoreScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)

        val created = mutableListOf<Uuid>()

        // The Postgres lane clones a Flyway-only template without seed members, so the scenario owns its seller.
        val ownSeller = Uuid.random()
        var sellerInserted = false

        fun sellerId(): Uuid {
            if (!sellerInserted) {
                transaction {
                    MemberTable.insert {
                        it[id] = ownSeller
                        it[displayName] = "Auction Federation Seller"
                        it[email] = "auction-federation-seller@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                    }
                }
                sellerInserted = true
            }
            return ownSeller
        }

        fun insertAuction(): Uuid {
            val id = Uuid.random()
            val seller = sellerId()
            transaction {
                AuctionTable.insert {
                    it[AuctionTable.id] = id
                    it[title] = "Federation Test Auction"
                    it[description] = "desc"
                    it[status] = AuctionStatus.OPEN
                    it[sellerMemberId] = seller
                    it[listingFeeLtr] = BigDecimal("0.00")
                    it[createdAt] = NOW
                    it[endsAt] = LocalDateTime(2026, 12, 3, 10, 0, 0)
                    it[currency] = "EUR"
                    it[startingPriceMinor] = 1000L
                    it[sellerDisclaimerVersion] = "test"
                }
            }
            created += id
            return id
        }

        fun reset() =
            transaction {
                created.forEach { id -> AuctionTable.deleteWhere { AuctionTable.id eq id } }
                created.clear()
                AuctionFederationStore.setSettings(enabled = false, hopLimit = 1)
            }
        beforeTest { reset() }
        afterSpec {
            try {
                reset()
                if (sellerInserted) transaction { MemberTable.deleteWhere { MemberTable.id eq ownSeller } }
            } finally {
                db.deactivate()
            }
        }

        test("a fresh instance has the exchange off and the hop limit at one") {
            transaction { AuctionFederationStore.settings() } shouldBe AuctionFederationSettings(enabled = false, hopLimit = 1)
        }

        test("setSettings persists, returns the previous state and rejects a hop limit outside 1..2") {
            val before = transaction { AuctionFederationStore.setSettings(enabled = true, hopLimit = 2) }
            before shouldBe AuctionFederationSettings(enabled = false, hopLimit = 1)
            transaction { AuctionFederationStore.settings() } shouldBe AuctionFederationSettings(enabled = true, hopLimit = 2)
            shouldThrow<IllegalArgumentException> { transaction { AuctionFederationStore.setSettings(enabled = true, hopLimit = 3) } }
            shouldThrow<IllegalArgumentException> { transaction { AuctionFederationStore.setSettings(enabled = true, hopLimit = 0) } }
            transaction { AuctionFederationStore.settings() } shouldBe AuctionFederationSettings(enabled = true, hopLimit = 2)
        }

        test("the consent flag and its moment are written together and cleared together") {
            val a = insertAuction()
            transaction { AuctionFederationStore.optIn(a) } shouldBe false
            transaction { AuctionFederationStore.optInAt(a) } shouldBe null
            transaction { AuctionFederationStore.setOptIn(auctionId = a, value = true, now = NOW) }
            transaction { AuctionFederationStore.optIn(a) } shouldBe true
            transaction { AuctionFederationStore.optInAt(a) } shouldBe NOW
            transaction { AuctionFederationStore.setOptIn(auctionId = a, value = false, now = NOW) }
            transaction { AuctionFederationStore.optIn(a) } shouldBe false
            transaction { AuctionFederationStore.optInAt(a) } shouldBe null
        }

        test("a restore switches the exchange off, clears every consent and writes one audit entry without an actor") {
            val a = insertAuction()
            val b = insertAuction()
            transaction {
                AuctionFederationStore.setSettings(enabled = true, hopLimit = 1)
                AuctionFederationStore.setOptIn(auctionId = a, value = true, now = NOW)
                AuctionFederationStore.setOptIn(auctionId = b, value = true, now = NOW)
            }
            val auditsBefore = transaction { AuditLogEntryTable.selectAll().count() }
            transaction { AuctionFederationStore.resetAfterRestore(NOW) } shouldBe true
            transaction { AuctionFederationStore.settings().enabled } shouldBe false
            transaction { AuctionFederationStore.countOptIns() } shouldBe 0L
            transaction { AuctionFederationStore.optInAt(a) } shouldBe null
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditsBefore + 1
            val last =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.DESC)
                        .first()
                }
            last[AuditLogEntryTable.entityType] shouldBe AuditEntityType.PARTNER_SERVER
            last[AuditLogEntryTable.actorMemberId] shouldBe null
            (last[AuditLogEntryTable.afterSnapshot] ?: "").contains("AUCTION_RESET_BY_RESTORE") shouldBe true
            (last[AuditLogEntryTable.afterSnapshot] ?: "").contains("\"subject\":\"2\"") shouldBe true
            // a second reset has nothing to do and writes nothing
            transaction { AuctionFederationStore.resetAfterRestore(NOW) } shouldBe false
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditsBefore + 1
        }
    })
