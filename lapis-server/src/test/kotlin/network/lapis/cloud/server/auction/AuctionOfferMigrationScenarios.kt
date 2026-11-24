package network.lapis.cloud.server.auction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AuctionOfferBuyerRestrictionTable
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.321 (V167) -- the price-offer columns and table on BOTH databases: existing rows keep `offers_enabled = FALSE`, the CHECK of `auction` refuses
 * offers on an LTR auction or without a Sofortkauf price, and the CHECKs/unique index of `auction_offer` refuse every malformed row (a counter-offer
 * without parent, a proposal with a parent, a second counter-offer on one proposal).
 */
abstract class AuctionOfferMigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()
        val auctions = mutableListOf<Uuid>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterEach {
            transaction {
                AuctionOfferBuyerRestrictionTable.deleteWhere { AuctionOfferBuyerRestrictionTable.auctionId inList auctions }
                AuctionOfferTable.deleteWhere { (AuctionOfferTable.auctionId inList auctions) and (AuctionOfferTable.fromSeller eq true) }
                AuctionOfferTable.deleteWhere { AuctionOfferTable.auctionId inList auctions }
                AuctionTable.deleteWhere { AuctionTable.id inList auctions }
            }
            auctions.clear()
        }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val ts = "TIMESTAMP '2026-01-01 00:00:00'"
        val later = "TIMESTAMP '2027-01-01 00:00:00'"

        fun sql(statement: String) = transaction { exec(statement) }

        fun eurAuction(
            seller: Uuid,
            offers: String,
            buyNow: String,
        ): Uuid {
            val id = Uuid.random()
            auctions += id
            sql(
                "INSERT INTO auction (id, title, description, status, seller_member_id, listing_fee_ltr, created_at, ends_at, currency, " +
                    "starting_price_minor, buy_now_price_minor, seller_disclaimer_version, offers_enabled) " +
                    "VALUES ('$id', 't', 'd', 'OPEN', '$seller', 0.00, " +
                    "$ts, $later, 'EUR', 1000, $buyNow, 'v1', $offers)",
            )
            return id
        }

        fun offerRow(
            auction: Uuid,
            buyer: Uuid,
            fromSeller: String = "FALSE",
            parent: String = "NULL",
            amount: Long = 2000,
            status: String = "OFFERED",
            expires: String = later,
        ): Uuid {
            val id = Uuid.random()
            sql(
                "INSERT INTO auction_offer (id, auction_id, buyer_member_id, from_seller, parent_offer_id, amount_minor, status, " +
                    "created_at, expires_at) " +
                    "VALUES ('$id', '$auction', '$buyer', $fromSeller, $parent, $amount, '$status', $ts, $expires)",
            )
            return id
        }

        test("an auction row of the V166 shape gets offers_enabled = FALSE from the database") {
            val seller = fx.member(label = "seller")
            val id = Uuid.random()
            auctions += id
            sql(
                "INSERT INTO auction (id, title, description, starting_bid_ltr, status, seller_member_id, listing_fee_ltr, " +
                    "created_at, ends_at) " +
                    "VALUES ('$id', 'alt', 'd', 5.00, 'OPEN', '$seller', 0.01, $ts, $later)",
            )
            transaction { AuctionTable.selectAll().where { AuctionTable.id eq id }.single()[AuctionTable.offersEnabled] } shouldBe false
        }

        test("offers_enabled needs a Fiat auction with a Sofortkauf price: LTR and a missing Sofortkauf price are refused") {
            val seller = fx.member(label = "seller")
            eurAuction(seller, offers = "TRUE", buyNow = "9000")
            eurAuction(seller, offers = "FALSE", buyNow = "NULL")
            shouldThrow<Exception> { eurAuction(seller, offers = "TRUE", buyNow = "NULL") }
            shouldThrow<Exception> {
                sql(
                    "INSERT INTO auction (id, title, description, starting_bid_ltr, status, seller_member_id, listing_fee_ltr, " +
                        "created_at, ends_at, " +
                        "offers_enabled) VALUES ('${Uuid.random()}', 'ltr', 'd', 5.00, 'OPEN', '$seller', 0.01, $ts, $later, TRUE)",
                )
            }
        }

        test("auction_offer refuses every malformed row; several NULL parents are fine, a second counter-offer on one proposal is not") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val a = eurAuction(seller, offers = "TRUE", buyNow = "9000")
            val p1 = offerRow(a, buyer)
            val p2 = offerRow(a, buyer, status = "DECLINED")
            offerRow(a, buyer, fromSeller = "TRUE", parent = "'$p1'")
            // a second counter-offer on the same proposal: the unique index on parent_offer_id
            shouldThrow<Exception> { offerRow(a, buyer, fromSeller = "TRUE", parent = "'$p1'", amount = 3000) }
            // counter-offer without parent, proposal with parent
            shouldThrow<Exception> { offerRow(a, buyer, fromSeller = "TRUE") }
            shouldThrow<Exception> { offerRow(a, buyer, fromSeller = "FALSE", parent = "'$p2'") }
            // status, amount, expiry
            shouldThrow<Exception> { offerRow(a, buyer, status = "BOGUS") }
            shouldThrow<Exception> { offerRow(a, buyer, amount = 0) }
            shouldThrow<Exception> { offerRow(a, buyer, expires = "TIMESTAMP '2025-01-01 00:00:00'") }
            listOf("ACCEPTED", "DECLINED", "COUNTERED", "EXPIRED", "WITHDRAWN").forEach { offerRow(a, buyer, status = it) }
        }

        // ── V170 (V1.9.323): the notice columns and the decline counters ───────────────────────────────────────────────

        val sha = "a".repeat(64)

        fun noticeRow(
            auction: Uuid,
            buyer: Uuid,
            version: String,
            lang: String,
            hash: String,
        ) = sql(
            "INSERT INTO auction_offer (id, auction_id, buyer_member_id, from_seller, parent_offer_id, amount_minor, status, created_at, " +
                "expires_at, notice_version, notice_lang, notice_sha256) " +
                "VALUES ('${Uuid.random()}', '$auction', '$buyer', FALSE, NULL, 2000, " +
                "'OFFERED', $ts, $later, $version, $lang, $hash)",
        )

        test(
            "V170: rows without a notice stay valid (all three NULL); a notice needs all three parts, a known language and a 64-character hash",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val a = eurAuction(seller, offers = "TRUE", buyNow = "9000")
            // the V167 shape: no notice columns at all
            offerRow(a, buyer)
            noticeRow(a, buyer, "'2026-11-24.v1'", "'de'", "'$sha'")
            noticeRow(a, buyer, "'2026-11-24.v1'", "'en'", "'$sha'")
            // mixed states and bad values are refused
            shouldThrow<Exception> { noticeRow(a, buyer, "'2026-11-24.v1'", "NULL", "NULL") }
            shouldThrow<Exception> { noticeRow(a, buyer, "NULL", "'de'", "'$sha'") }
            shouldThrow<Exception> { noticeRow(a, buyer, "'2026-11-24.v1'", "'fr'", "'$sha'") }
            shouldThrow<Exception> { noticeRow(a, buyer, "'2026-11-24.v1'", "'de'", "'${sha.dropLast(1)}'") }
            shouldThrow<Exception> { noticeRow(a, buyer, "'2026-11-24.v1'", "'de'", "'${sha}0'") }
        }

        test("V170: the decline counter row is unique per auction and buyer, the counter stays below three, both foreign keys hold") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val a = eurAuction(seller, offers = "TRUE", buyNow = "9000")

            fun restriction(
                auction: Any,
                member: Any,
                declines: Int,
            ) = sql(
                "INSERT INTO auction_offer_buyer_restriction " +
                    "(id, auction_id, buyer_member_id, declines_in_cycle, blocked_until, updated_at) " +
                    "VALUES ('${Uuid.random()}', '$auction', '$member', $declines, NULL, $ts)",
            )
            restriction(a, buyer, 2)
            shouldThrow<Exception> { restriction(a, buyer, 1) }
            val other = fx.member(label = "other")
            restriction(a, other, 0)
            shouldThrow<Exception> { restriction(a, fx.member(label = "third"), 3) }
            shouldThrow<Exception> { restriction(a, fx.member(label = "fourth"), -1) }
            shouldThrow<Exception> { restriction(Uuid.random(), fx.member(label = "fifth"), 0) }
            shouldThrow<Exception> { restriction(a, Uuid.random(), 0) }
        }
    })
