package network.lapis.cloud.server.auction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.320 (V166) -- the status CHECK of `auction` was an UNNAMED inline constraint (H2 name `CONSTRAINT_D88C`, PostgreSQL `auction_status_check`); V166
 * replaces it by named ones. This pins that the old one is gone on BOTH databases (a left-over would refuse AWAITING_PAYMENT), that a pre-V166 shape is
 * still valid, and that the new cross-column CHECKs refuse every mixed shape.
 */
abstract class AuctionStatusMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()
        val created = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach {
            transaction {
                AuctionBidTable.deleteWhere { AuctionBidTable.auctionId inList created }
                AuctionTable.deleteWhere { AuctionTable.id inList created }
            }
            created.clear()
        }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun insertAuction(
            seller: Uuid,
            currency: String,
            status: AuctionStatus,
            startingLtr: BigDecimal?,
            startingMinor: Long?,
            disclaimer: String? = if (currency == "LTR") null else "2026-11-23.v1",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                AuctionTable.insert {
                    it[AuctionTable.id] = id
                    it[title] = "Migration"
                    it[description] = "d"
                    it[AuctionTable.currency] = currency
                    it[startingBidLtr] = startingLtr
                    it[buyNowPriceLtr] = null
                    it[startingPriceMinor] = startingMinor
                    it[AuctionTable.status] = status
                    it[sellerMemberId] = seller
                    it[winnerMemberId] = null
                    it[finalPriceLtr] = null
                    it[listingFeeLtr] = BigDecimal("0.00")
                    it[sellerDisclaimerVersion] = disclaimer
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[AuctionTable.endsAt] = LocalDateTime(2027, 1, 1, 0, 0)
                    it[settledAt] = null
                }
            }
            created += id
            return id
        }

        test("no unnamed status CHECK is left on auction, the named one exists") {
            val names = mutableListOf<String>()
            transaction {
                exec(
                    "SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'auction' AND constraint_type = 'CHECK'",
                ) { rs -> while (rs.next()) names += rs.getString(1).lowercase() }
            }
            names.any { it == "chk_auction_status" } shouldBe true
            names.none { it == "constraint_d88c" || it == "auction_status_check" } shouldBe true
        }

        test("a pre-V166 row shape is still valid: an LTR auction without any Fiat column, currency defaulted to LTR by the database") {
            val seller = fx.member(label = "seller")
            val id = Uuid.random()
            created += id
            transaction {
                exec(
                    "INSERT INTO auction (id, title, description, starting_bid_ltr, status, seller_member_id, listing_fee_ltr, " +
                        "created_at, ends_at) VALUES ('$id', 'alt', 'd', 5.00, 'OPEN', '$seller', 0.01, TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2027-01-01 00:00:00')",
                )
            }
            transaction { AuctionTable.selectAll().where { AuctionTable.id eq id }.single()[AuctionTable.currency] } shouldBe "LTR"
        }

        test("the widened status column takes AWAITING_PAYMENT, PAID and COMPLETED for a EUR auction") {
            val seller = fx.member(label = "seller")
            val statuses = listOf(AuctionStatus.AWAITING_PAYMENT, AuctionStatus.PAID, AuctionStatus.COMPLETED, AuctionStatus.CLOSED_NO_SALE)
            statuses.forEach { status ->
                insertAuction(seller, "EUR", status, startingLtr = null, startingMinor = 1000)
            }
        }

        test(
            "every mixed shape is refused: a Fiat status on an LTR auction, SETTLED on a EUR one, both amount shapes, none, bad currency, zero minor",
        ) {
            val seller = fx.member(label = "seller")
            listOf(AuctionStatus.AWAITING_PAYMENT, AuctionStatus.PAID, AuctionStatus.COMPLETED).forEach { status ->
                shouldThrow<Exception> { insertAuction(seller, "LTR", status, BigDecimal("1.00"), null) }
            }
            shouldThrow<Exception> { insertAuction(seller, "EUR", AuctionStatus.SETTLED, null, 1000) }
            shouldThrow<Exception> { insertAuction(seller, "EUR", AuctionStatus.OPEN, BigDecimal("1.00"), 1000) }
            shouldThrow<Exception> { insertAuction(seller, "EUR", AuctionStatus.OPEN, null, null) }
            shouldThrow<Exception> { insertAuction(seller, "LTR", AuctionStatus.OPEN, null, null) }
            shouldThrow<Exception> { insertAuction(seller, "LTR", AuctionStatus.OPEN, BigDecimal("1.00"), 1000) }
            shouldThrow<Exception> { insertAuction(seller, "EUR", AuctionStatus.OPEN, null, 0) }
            shouldThrow<Exception> { insertAuction(seller, "eur", AuctionStatus.OPEN, null, 1000) }
            shouldThrow<Exception> { insertAuction(seller, "EURO", AuctionStatus.OPEN, null, 1000) }
            shouldThrow<Exception> { insertAuction(seller, "EUR", AuctionStatus.OPEN, null, 1000, disclaimer = null) }
        }

        test("a bid carries exactly one of the two amount columns") {
            val seller = fx.member(label = "seller")
            val bidder = fx.member(label = "bidder")
            val ltr = insertAuction(seller, "LTR", AuctionStatus.OPEN, BigDecimal("1.00"), null)
            val eur = insertAuction(seller, "EUR", AuctionStatus.OPEN, null, 1000)

            fun bid(
                auction: Uuid,
                ltrAmount: BigDecimal?,
                minor: Long?,
            ) = transaction {
                AuctionBidTable.insert {
                    it[id] = Uuid.random()
                    it[auctionId] = auction
                    it[bidderMemberId] = bidder
                    it[maxBidLtr] = ltrAmount
                    it[maxBidMinor] = minor
                    it[createdAt] = LocalDateTime(2026, 1, 2, 0, 0)
                }
            }
            bid(ltr, BigDecimal("2.00"), null)
            bid(eur, null, 2000)
            shouldThrow<Exception> { bid(ltr, BigDecimal("3.00"), 300) }
            shouldThrow<Exception> { bid(eur, null, null) }
            shouldThrow<Exception> { bid(eur, null, 0) }
        }
    })
