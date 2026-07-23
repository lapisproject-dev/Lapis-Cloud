package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PeerTransferTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [AuctionService] end to end, mirroring [PeerTransferServiceTest]/[PoliticianServiceTest]'s
 * house style (throwaway routes calling the service class directly, no wire format to reverse-
 * engineer). DevSeedData's ADMIN account is used only as the actor enabling the feature; every
 * seller/bidder is a fresh test member. [afterTest] resets `auctionEnabled`/`auctionMaxValueLtr`
 * and wipes every auction row; [afterSpec] hard-deletes every member row this file created.
 */
class AuctionServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                AuctionBidTable.deleteWhere { AuctionBidTable.id eq AuctionBidTable.id }
                AuctionTable.deleteWhere { AuctionTable.id eq AuctionTable.id }
                AuctionComplianceAcknowledgmentTable.deleteWhere {
                    AuctionComplianceAcknowledgmentTable.id eq
                        AuctionComplianceAcknowledgmentTable.id
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionEnabled] = false
                    it[auctionMaxValueLtr] = null
                }
            }
        }

        afterSpec {
            transaction {
                // The cross-path reservation-safety test drives PeerTransferService.transferLtr,
                // which writes its own peer_transfer row (sender/recipient FK -> member) -- must be
                // cleaned up before MemberTable, same as LtrLedgerEntryTable/AccountTable below.
                PeerTransferTable.deleteWhere {
                    (PeerTransferTable.senderMemberId inList createdMemberIds) or
                        (PeerTransferTable.recipientMemberId inList createdMemberIds) or
                        (PeerTransferTable.initiatedBy inList createdMemberIds)
                }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Auktion Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun mintLtr(
            memberId: Uuid,
            amount: BigDecimal,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = amount
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        fun freeBalanceOf(memberId: Uuid): BigDecimal =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where { LtrLedgerEntryTable.memberId eq memberId }
                    .fold(BigDecimal.ZERO.setScale(2)) { acc, row -> acc + row[LtrLedgerEntryTable.amountLtr] }
            }

        fun enableAuctionDirectly() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionEnabled] = true
                }
            }
        }

        /** Inserts an auction row directly, bypassing createListing -- used to construct an already-overdue auction for lazy-close tests. */
        fun insertAuctionDirectly(
            sellerId: Uuid,
            startingBidLtr: BigDecimal,
            buyNowPriceLtr: BigDecimal?,
            endsAt: LocalDateTime,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                AuctionTable.insert {
                    it[AuctionTable.id] = id
                    it[title] = "Direct Test Auction"
                    it[description] = "desc"
                    it[AuctionTable.startingBidLtr] = startingBidLtr
                    it[AuctionTable.buyNowPriceLtr] = buyNowPriceLtr
                    it[status] = AuctionStatus.OPEN
                    it[sellerMemberId] = sellerId
                    it[winnerMemberId] = null
                    it[finalPriceLtr] = null
                    it[listingFeeLtr] = BigDecimal("0.01")
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[AuctionTable.endsAt] = endsAt
                    it[settledAt] = null
                }
            }
            return id
        }

        // ── The auctionEnabled gate ──────────────────────────────────────────

        test("every participant endpoint is rejected with Conflict while auctionEnabled=false, zero side effects") {
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("gate-seller@example.org")
                mintLtr(seller, BigDecimal("10.00"))

                val createResp =
                    client.post("/test/create-listing?title=X&description=Y&startingBid=1.00&durationHours=24") {
                        header("X-Member-Id", seller.toString())
                    }
                createResp.status shouldBe HttpStatusCode.Conflict
                transaction { AuctionTable.selectAll().count() } shouldBe 0L
                freeBalanceOf(seller).compareTo(BigDecimal("10.00")) shouldBe 0
            }
        }

        // ── Disclaimer-acknowledgment mechanism ──────────────────────────────

        test(
            "enableAuction: MEMBER forbidden, wrong version/hash rejected with zero side effect, correct hash enables + writes one auditable ack row",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val disclaimer = client.get("/test/get-disclaimer") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                val version = disclaimer[0]
                val sha256 = disclaimer[1]

                val forbidden = client.post("/test/enable-auction?version=$version&sha256=$sha256") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val wrongHash = client.post("/test/enable-auction?version=$version&sha256=deadbeef") { header("X-Member-Id", ADMIN_ID) }
                wrongHash.status shouldBe HttpStatusCode.Conflict
                transaction { AuctionComplianceAcknowledgmentTable.selectAll().count() } shouldBe 0L
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .single()[OrganizationSettingsTable.auctionEnabled]
                } shouldBe false

                val correct = client.post("/test/enable-auction?version=$version&sha256=$sha256") { header("X-Member-Id", ADMIN_ID) }
                correct.status shouldBe HttpStatusCode.OK
                transaction { AuctionComplianceAcknowledgmentTable.selectAll().count() } shouldBe 1L
                val ackRow = transaction { AuctionComplianceAcknowledgmentTable.selectAll().single() }
                ackRow[AuctionComplianceAcknowledgmentTable.acknowledgedByMemberId] shouldBe Uuid.parse(ADMIN_ID)
                ackRow[AuctionComplianceAcknowledgmentTable.disclaimerVersion] shouldBe version

                val settingsAfter = client.get("/test/get-settings") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                settingsAfter[0] shouldBe "true"
            }
        }

        test("disableAuction resets the gate but keeps the acknowledgment history; re-enable writes a second ack row") {
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val disclaimer = client.get("/test/get-disclaimer") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                client.post("/test/enable-auction?version=${disclaimer[0]}&sha256=${disclaimer[1]}") { header("X-Member-Id", ADMIN_ID) }

                val disabled = client.post("/test/disable-auction") { header("X-Member-Id", ADMIN_ID) }
                disabled.status shouldBe HttpStatusCode.OK
                transaction { AuctionComplianceAcknowledgmentTable.selectAll().count() } shouldBe 1L

                client.post("/test/enable-auction?version=${disclaimer[0]}&sha256=${disclaimer[1]}") { header("X-Member-Id", ADMIN_ID) }
                transaction { AuctionComplianceAcknowledgmentTable.selectAll().count() } shouldBe 2L
            }
        }

        // ── Happy path ────────────────────────────────────────────────────────

        test("createListing debits exactly the listing fee and writes exactly one AUCTION_LISTING_FEE entry") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("happy-seller@example.org")
                mintLtr(seller, BigDecimal("10.00"))

                val resp =
                    client.post("/test/create-listing?title=Chair&description=A%20nice%20chair&startingBid=5.00&durationHours=24") {
                        header("X-Member-Id", seller.toString())
                    }
                resp.status shouldBe HttpStatusCode.OK
                freeBalanceOf(seller).compareTo(BigDecimal("9.99")) shouldBe 0
                val feeEntries =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.memberId eq seller) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.AUCTION_LISTING_FEE)
                            }.count()
                    }
                feeEntries shouldBe 1L
            }
        }

        test("two bidders: leader pays second price, outbidding releases the former leader's hold and books the new one") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("two-bidder-seller@example.org")
                val bidderA = createTestMember("two-bidder-a@example.org")
                val bidderB = createTestMember("two-bidder-b@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(bidderA, BigDecimal("100.00"))
                mintLtr(bidderB, BigDecimal("100.00"))

                val created =
                    client
                        .post("/test/create-listing?title=Table&description=D&startingBid=1.00&durationHours=24") {
                            header("X-Member-Id", seller.toString())
                        }.bodyAsText()
                        .split("|")
                val auctionId = created[0]

                val bidA =
                    client
                        .post("/test/place-bid?auctionId=$auctionId&maxBid=50.00") { header("X-Member-Id", bidderA.toString()) }
                        .bodyAsText()
                        .split("|")
                bidA[1] shouldBe "true" // youAreLeader
                bidA[2] shouldBe "1.00" // currentPrice == startingBid, uncontested so far
                freeBalanceOf(bidderA).compareTo(BigDecimal("50.00")) shouldBe 0

                val bidB =
                    client
                        .post("/test/place-bid?auctionId=$auctionId&maxBid=80.00") { header("X-Member-Id", bidderB.toString()) }
                        .bodyAsText()
                        .split("|")
                bidB[1] shouldBe "true"
                bidB[2] shouldBe "50.01"

                // A was outbid -- their hold must be fully released.
                freeBalanceOf(bidderA).compareTo(BigDecimal("100.00")) shouldBe 0
                // B now holds their own 80.00 maximum.
                freeBalanceOf(bidderB).compareTo(BigDecimal("20.00")) shouldBe 0
            }
        }

        test("own-raise nets correctly: releasing the old hold and booking the new one leaves exactly the new amount reserved") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("raise-seller@example.org")
                val bidder = createTestMember("raise-bidder@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(bidder, BigDecimal("100.00"))

                val auctionId =
                    client
                        .post("/test/create-listing?title=Lamp&description=D&startingBid=1.00&durationHours=24") {
                            header("X-Member-Id", seller.toString())
                        }.bodyAsText()
                        .split("|")[0]

                client.post("/test/place-bid?auctionId=$auctionId&maxBid=30.00") { header("X-Member-Id", bidder.toString()) }
                freeBalanceOf(bidder).compareTo(BigDecimal("70.00")) shouldBe 0

                val raised =
                    client.post("/test/place-bid?auctionId=$auctionId&maxBid=45.00") { header("X-Member-Id", bidder.toString()) }
                raised.status shouldBe HttpStatusCode.OK
                freeBalanceOf(bidder).compareTo(BigDecimal("55.00")) shouldBe 0

                val lower = client.post("/test/place-bid?auctionId=$auctionId&maxBid=44.00") { header("X-Member-Id", bidder.toString()) }
                lower.status shouldBe HttpStatusCode.Conflict
                freeBalanceOf(bidder).compareTo(BigDecimal("55.00")) shouldBe 0
            }
        }

        test("a non-leading bid holds nothing but legitimately sets the second price") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("nonleading-seller@example.org")
                val bidderA = createTestMember("nonleading-a@example.org")
                val bidderB = createTestMember("nonleading-b@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(bidderA, BigDecimal("100.00"))
                mintLtr(bidderB, BigDecimal("40.00"))

                val auctionId =
                    client
                        .post("/test/create-listing?title=Desk&description=D&startingBid=1.00&durationHours=24") {
                            header("X-Member-Id", seller.toString())
                        }.bodyAsText()
                        .split("|")[0]

                client.post("/test/place-bid?auctionId=$auctionId&maxBid=90.00") { header("X-Member-Id", bidderA.toString()) }
                freeBalanceOf(bidderA).compareTo(BigDecimal("10.00")) shouldBe 0

                val bidB =
                    client
                        .post("/test/place-bid?auctionId=$auctionId&maxBid=30.00") { header("X-Member-Id", bidderB.toString()) }
                        .bodyAsText()
                        .split("|")
                bidB[1] shouldBe "false"
                // B holds nothing -- their full balance remains free.
                freeBalanceOf(bidderB).compareTo(BigDecimal("40.00")) shouldBe 0
                // A's price is now bumped up to B's bid + increment (still capped at A's own max).
                freeBalanceOf(bidderA).compareTo(BigDecimal("10.00")) shouldBe 0
            }
        }

        // ── Sofortkauf ────────────────────────────────────────────────────────

        test("buyNow ends the auction immediately at the fixed price and is rejected once the live price already reached it") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("buynow-seller@example.org")
                val buyer = createTestMember("buynow-buyer@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(buyer, BigDecimal("100.00"))

                val auctionId =
                    client
                        .post(
                            "/test/create-listing?title=Bike&description=D&startingBid=1.00&buyNowPrice=50.00&durationHours=24",
                        ) { header("X-Member-Id", seller.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                val bought = client.post("/test/buy-now?auctionId=$auctionId") { header("X-Member-Id", buyer.toString()) }
                bought.status shouldBe HttpStatusCode.OK
                val fields = bought.bodyAsText().split("|")
                fields[1] shouldBe AuctionStatus.SETTLED.name
                fields[4] shouldBe buyer.toString()
                fields[5] shouldBe "50.00"

                freeBalanceOf(buyer).compareTo(BigDecimal("50.00")) shouldBe 0
                freeBalanceOf(seller).compareTo(BigDecimal("50.99")) shouldBe 0

                val again = client.post("/test/buy-now?auctionId=$auctionId") { header("X-Member-Id", buyer.toString()) }
                again.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("seller cannot bid on or buy their own auction") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("selfbid-seller@example.org")
                mintLtr(seller, BigDecimal("100.00"))
                val auctionId =
                    client
                        .post(
                            "/test/create-listing?title=Sofa&description=D&startingBid=1.00&buyNowPrice=10.00&durationHours=24",
                        ) { header("X-Member-Id", seller.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                val ownBid = client.post("/test/place-bid?auctionId=$auctionId&maxBid=5.00") { header("X-Member-Id", seller.toString()) }
                ownBid.status shouldBe HttpStatusCode.Conflict
                val ownBuy = client.post("/test/buy-now?auctionId=$auctionId") { header("X-Member-Id", seller.toString()) }
                ownBuy.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── Lazy-Close ────────────────────────────────────────────────────────

        test(
            "lazy-close on getAuction: overdue auction with bids settles to SETTLED at the second price, no bids settles to CLOSED_NO_SALE",
        ) {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("lazy-seller@example.org")
                val bidderA = createTestMember("lazy-a@example.org")
                val bidderB = createTestMember("lazy-b@example.org")
                mintLtr(bidderA, BigDecimal("100.00"))
                mintLtr(bidderB, BigDecimal("100.00"))

                val overdueWithBids = insertAuctionDirectly(seller, BigDecimal("1.00"), null, LocalDateTime(2020, 1, 1, 0, 0))
                transaction {
                    AuctionBidTable.insert {
                        it[id] = Uuid.random()
                        it[auctionId] = overdueWithBids
                        it[bidderMemberId] = bidderA
                        it[maxBidLtr] = BigDecimal("70.00")
                        it[createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
                    }
                    AuctionBidTable.insert {
                        it[id] = Uuid.random()
                        it[auctionId] = overdueWithBids
                        it[bidderMemberId] = bidderB
                        it[maxBidLtr] = BigDecimal("40.00")
                        it[createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
                    }
                }

                val resp =
                    client.get("/test/get-auction?id=$overdueWithBids") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split("|")
                resp[1] shouldBe AuctionStatus.SETTLED.name
                resp[4] shouldBe bidderA.toString()
                resp[5] shouldBe "40.01"
                freeBalanceOf(seller).compareTo(BigDecimal("40.01")) shouldBe 0

                val overdueNoBids = insertAuctionDirectly(seller, BigDecimal("1.00"), null, LocalDateTime(2020, 1, 1, 0, 0))
                val resp2 = client.get("/test/get-auction?id=$overdueNoBids") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split("|")
                resp2[1] shouldBe AuctionStatus.CLOSED_NO_SALE.name
            }
        }

        test("settleAuction rejects early settlement before endsAt but succeeds once due") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("settle-seller@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                val auctionId =
                    client
                        .post("/test/create-listing?title=Radio&description=D&startingBid=1.00&durationHours=24") {
                            header("X-Member-Id", seller.toString())
                        }.bodyAsText()
                        .split("|")[0]

                val early = client.post("/test/settle-auction?id=$auctionId") { header("X-Member-Id", MEMBER_ID) }
                early.status shouldBe HttpStatusCode.Conflict

                val overdueId = insertAuctionDirectly(seller, BigDecimal("1.00"), null, LocalDateTime(2020, 1, 1, 0, 0))
                val settled = client.post("/test/settle-auction?id=$overdueId") { header("X-Member-Id", MEMBER_ID) }
                settled.status shouldBe HttpStatusCode.OK
                settled.bodyAsText().split("|")[1] shouldBe AuctionStatus.CLOSED_NO_SALE.name
            }
        }

        // ── Wertobergrenze ────────────────────────────────────────────────────

        test("auctionMaxValueLtr caps startingBidLtr/buyNowPriceLtr at createListing, but never an individual bid") {
            enableAuctionDirectly()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionMaxValueLtr] = BigDecimal("50.00")
                }
            }
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("cap-seller@example.org")
                val bidder = createTestMember("cap-bidder@example.org")
                mintLtr(seller, BigDecimal("10.00"))
                mintLtr(bidder, BigDecimal("1000.00"))

                val overStarting =
                    client.post("/test/create-listing?title=X&description=D&startingBid=60.00&durationHours=24") {
                        header("X-Member-Id", seller.toString())
                    }
                overStarting.status shouldBe HttpStatusCode.Conflict

                val overBuyNow =
                    client.post("/test/create-listing?title=X&description=D&startingBid=10.00&buyNowPrice=60.00&durationHours=24") {
                        header("X-Member-Id", seller.toString())
                    }
                overBuyNow.status shouldBe HttpStatusCode.Conflict

                val ok =
                    client.post("/test/create-listing?title=X&description=D&startingBid=10.00&durationHours=24") {
                        header("X-Member-Id", seller.toString())
                    }
                ok.status shouldBe HttpStatusCode.OK
                val auctionId = ok.bodyAsText().split("|")[0]

                // A bid ABOVE the cap is allowed -- the cap only governs what may be LISTED.
                val bigBid =
                    client.post("/test/place-bid?auctionId=$auctionId&maxBid=500.00") { header("X-Member-Id", bidder.toString()) }
                bigBid.status shouldBe HttpStatusCode.OK
            }
        }

        // ── Cross-path reservation safety (the hardest correctness point) ───────

        test("a reserved auction hold cannot be spent again via PeerTransferService -- closes the V0.6.1-class gap") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing {
                        registerAuctionTestRoutes()
                        registerPeerTransferProbeRoute()
                    }
                }
                val seller = createTestMember("crosspath-seller@example.org")
                val bidder = createTestMember("crosspath-bidder@example.org")
                val victim = createTestMember("crosspath-victim@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(bidder, BigDecimal("100.00"))

                val auctionId =
                    client
                        .post("/test/create-listing?title=X&description=D&startingBid=1.00&durationHours=24") {
                            header("X-Member-Id", seller.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client.post("/test/place-bid?auctionId=$auctionId&maxBid=90.00") { header("X-Member-Id", bidder.toString()) }
                freeBalanceOf(bidder).compareTo(BigDecimal("10.00")) shouldBe 0

                // Bidder tries to transfer away MORE than their un-reserved free balance -- must fail.
                val overTransfer =
                    client.post("/test/peer-transfer-probe?recipientId=$victim&amount=50.00") {
                        header("X-Member-Id", bidder.toString())
                    }
                overTransfer.status shouldBe HttpStatusCode.Conflict
                freeBalanceOf(bidder).compareTo(BigDecimal("10.00")) shouldBe 0

                // Exactly their free (un-reserved) balance still works.
                val exactTransfer =
                    client.post("/test/peer-transfer-probe?recipientId=$victim&amount=10.00") {
                        header("X-Member-Id", bidder.toString())
                    }
                exactTransfer.status shouldBe HttpStatusCode.OK
                freeBalanceOf(bidder).compareTo(BigDecimal.ZERO.setScale(2)) shouldBe 0
            }
        }

        // ── Authorization ─────────────────────────────────────────────────────

        test("non-AKTIV member cannot create/bid/buy; ADMIN-only governance methods reject MEMBER") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val guest = createTestMember("authz-guest@example.org", status = MemberStatus.AUSGETRETEN)
                mintLtr(guest, BigDecimal("10.00"))

                val createResp =
                    client.post("/test/create-listing?title=X&description=D&startingBid=1.00&durationHours=24") {
                        header("X-Member-Id", guest.toString())
                    }
                createResp.status shouldBe HttpStatusCode.Forbidden

                val setMax = client.post("/test/set-max?value=10.00") { header("X-Member-Id", MEMBER_ID) }
                setMax.status shouldBe HttpStatusCode.Forbidden

                val disable = client.post("/test/disable-auction") { header("X-Member-Id", MEMBER_ID) }
                disable.status shouldBe HttpStatusCode.Forbidden
            }
        }

        // ── Race conditions ───────────────────────────────────────────────────

        test("two concurrent bids on the same auction never lose an update: exactly one final leader, exact final balances") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("race-seller@example.org")
                val bidderA = createTestMember("race-a@example.org")
                val bidderB = createTestMember("race-b@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(bidderA, BigDecimal("1000.00"))
                mintLtr(bidderB, BigDecimal("1000.00"))

                val auctionId =
                    client
                        .post("/test/create-listing?title=X&description=D&startingBid=1.00&durationHours=24") {
                            header("X-Member-Id", seller.toString())
                        }.bodyAsText()
                        .split("|")[0]

                runConcurrentBids(client, auctionId, bidderA, "200.00", bidderB, "300.00")

                val finalDto = client.get("/test/get-auction?id=$auctionId") { header("X-Member-Id", MEMBER_ID) }.bodyAsText().split("|")
                finalDto[8] shouldBe "2" // bidCount

                val aHeld = freeBalanceOf(bidderA).compareTo(BigDecimal("1000.00")) != 0
                val bHeld = freeBalanceOf(bidderB).compareTo(BigDecimal("1000.00")) != 0
                // Exactly one of the two is currently holding a reservation (the leader, B at 300.00).
                (aHeld && bHeld) shouldBe false
                freeBalanceOf(bidderB).compareTo(BigDecimal("700.00")) shouldBe 0
                freeBalanceOf(bidderA).compareTo(BigDecimal("1000.00")) shouldBe 0
            }
        }

        test("two concurrent buyNow attempts on the same auction: exactly one succeeds") {
            enableAuctionDirectly()
            testApplication {
                application {
                    install(StatusPages) { installAuctionExceptionHandlers() }
                    routing { registerAuctionTestRoutes() }
                }
                val seller = createTestMember("race-buynow-seller@example.org")
                val buyerA = createTestMember("race-buynow-a@example.org")
                val buyerB = createTestMember("race-buynow-b@example.org")
                mintLtr(seller, BigDecimal("1.00"))
                mintLtr(buyerA, BigDecimal("100.00"))
                mintLtr(buyerB, BigDecimal("100.00"))

                val auctionId =
                    client
                        .post(
                            "/test/create-listing?title=X&description=D&startingBid=1.00&buyNowPrice=50.00&durationHours=24",
                        ) { header("X-Member-Id", seller.toString()) }
                        .bodyAsText()
                        .split("|")[0]

                val outcomes = runConcurrentBuyNow(client, auctionId, buyerA, buyerB)
                val successCount = outcomes.count { it == HttpStatusCode.OK }
                successCount shouldBe 1
                val conflictCount = outcomes.count { it == HttpStatusCode.Conflict }
                conflictCount shouldBe 1

                val row = transaction { AuctionTable.selectAll().where { AuctionTable.id eq Uuid.parse(auctionId) }.single() }
                row[AuctionTable.status] shouldBe AuctionStatus.SETTLED
                row[AuctionTable.winnerMemberId] shouldBe
                    (if (freeBalanceOf(buyerA).compareTo(BigDecimal("50.00")) == 0) buyerA else buyerB)
            }
        }
    })

private fun runConcurrentBids(
    client: HttpClient,
    auctionId: String,
    bidderA: Uuid,
    amountA: String,
    bidderB: Uuid,
    amountB: String,
    timeoutSeconds: Long = 20,
) {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val failures = mutableListOf<Throwable>()

    fun bidThread(
        bidderId: Uuid,
        amount: String,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    client.post("/test/place-bid?auctionId=$auctionId&maxBid=$amount") { header("X-Member-Id", bidderId.toString()) }
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val t1 = bidThread(bidderA, amountA)
    val t2 = bidThread(bidderB, amountB)
    t1.start()
    t2.start()
    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent bids did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
}

private fun runConcurrentBuyNow(
    client: HttpClient,
    auctionId: String,
    buyerA: Uuid,
    buyerB: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = java.util.Collections.synchronizedList(mutableListOf<HttpStatusCode>())
    val failures = mutableListOf<Throwable>()

    fun buyThread(buyerId: Uuid): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response = client.post("/test/buy-now?auctionId=$auctionId") { header("X-Member-Id", buyerId.toString()) }
                    results += response.status
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val t1 = buyThread(buyerA)
    val t2 = buyThread(buyerB)
    t1.start()
    t2.start()
    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent buyNow attempts did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun StatusPagesConfig.installAuctionExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/** Minimal throwaway probe route for [PeerTransferService.transferLtr] -- used only by the cross-path reservation-safety test. */
private fun Route.registerPeerTransferProbeRoute() {
    post("/test/peer-transfer-probe") {
        val service = PeerTransferService(call)
        val q = call.request.queryParameters
        service.transferLtr(
            PeerTransferInput(
                recipientMemberId = q["recipientId"]!!,
                amountLtr = BigDecimal(q["amount"]!!),
                characterization = PeerTransferCharacterization.SONSTIGES,
                purpose = null,
            ),
        )
        call.respondText("ok")
    }
}

/** Shared throwaway routes for [AuctionService] -- mirrors [PeerTransferServiceTest]'s `registerPeerTransferTestRoutes` style. Fields are pipe-separated (`|`), never colon (auction ids/amounts never contain a pipe). */
private fun Route.registerAuctionTestRoutes() {
    post("/test/create-listing") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val dto =
            service.createListing(
                CreateAuctionListingInput(
                    title = q["title"]!!,
                    description = q["description"]!!,
                    startingBidLtr = BigDecimal(q["startingBid"] ?: "1.00"),
                    buyNowPriceLtr = q["buyNowPrice"]?.let { BigDecimal(it) },
                    durationHours = q["durationHours"]?.toInt() ?: 24,
                ),
            )
        call.respondText(dto.toPipeString())
    }
    post("/test/place-bid") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val result = service.placeBid(q["auctionId"]!!, BigDecimal(q["maxBid"]!!))
        call.respondText("${result.auctionId}|${result.youAreLeader}|${result.currentPriceLtr}|${result.yourMaxBidLtr}")
    }
    post("/test/buy-now") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val dto = service.buyNow(q["auctionId"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/get-auction") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val dto = service.getAuction(q["id"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/settle-auction") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val dto = service.settleAuction(q["id"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/get-disclaimer") {
        val service = AuctionService(call)
        val dto = service.getAuctionComplianceDisclaimer()
        call.respondText("${dto.version}|${dto.sha256}")
    }
    post("/test/enable-auction") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val dto = service.enableAuction(AuctionComplianceAcknowledgmentInput(q["version"]!!, q["sha256"]!!))
        call.respondText("${dto.auctionEnabled}")
    }
    post("/test/disable-auction") {
        val service = AuctionService(call)
        val dto = service.disableAuction()
        call.respondText("${dto.auctionEnabled}")
    }
    post("/test/set-max") {
        val service = AuctionService(call)
        val q = call.request.queryParameters
        val dto = service.setAuctionMaxValueLtr(q["value"]?.let { BigDecimal(it) })
        call.respondText("${dto.auctionMaxValueLtr}")
    }
    get("/test/get-settings") {
        val service = AuctionService(call)
        val dto = service.getAuctionSettings()
        call.respondText("${dto.auctionEnabled}|${dto.auctionMaxValueLtr}")
    }
}

/** id|status|effectiveStatus|currentPrice|winnerId|finalPrice|leaderIsMe|listingFee|bidCount */
private fun AuctionDto.toPipeString(): String =
    "$id|$status|$effectiveStatus|${currentPriceLtr ?: "null"}|${winnerMemberId ?: "null"}|${finalPriceLtr ?: "null"}|$leaderIsMe|$listingFeeLtr|$bidCount"
