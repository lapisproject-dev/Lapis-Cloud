package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionOfferBuyerRestrictionTable
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.gallery.GalleryApp
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionOfferRules
import network.lapis.cloud.shared.domain.AuctionOfferStatus
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.rpc.AuctionOfferAlreadyAnsweredException
import network.lapis.cloud.shared.rpc.AuctionOfferAuctionFullException
import network.lapis.cloud.shared.rpc.AuctionOfferBlockedException
import network.lapis.cloud.shared.rpc.AuctionOfferBuyerHasOpenException
import network.lapis.cloud.shared.rpc.AuctionOfferNotOpenException
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val OFFER_RACE_KEY: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
private const val OFFER_ROUNDS = 6

/**
 * V1.9.321 -- the races of the price offers on H2 AND PostgreSQL. Every one of them goes through the auction row lock first and the offer row second,
 * so exactly one outcome may win and the loser must get the CONSTANT business exception (never a lock timeout, a deadlock or a constraint error).
 * Each block runs [OFFER_ROUNDS] times; on PostgreSQL the deadlock counter must stay at zero.
 */
abstract class AuctionOfferRaceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()

        fun svc(call: ApplicationCall) = AuctionService(call = call, secretEncryptionKey = OFFER_RACE_KEY)

        fun auctionOf(id: String) = transaction { AuctionTable.selectAll().where { AuctionTable.id eq Uuid.parse(id) }.single() }

        fun offerStatuses(auctionId: String): List<AuctionOfferStatus> =
            transaction {
                AuctionOfferTable
                    .selectAll()
                    .where {
                        AuctionOfferTable.auctionId eq
                            Uuid.parse(
                                auctionId,
                            )
                    }.map { it[AuctionOfferTable.status] }
            }

        fun endNow(id: String) {
            transaction {
                AuctionTable.update({ AuctionTable.id eq Uuid.parse(id) }) { it[AuctionTable.endsAt] = LocalDateTime(2020, 1, 1, 0, 0) }
            }
        }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeEach {
            fx.setAuctionEnabled(true)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionFiatEnabled] =
                        true
                }
            }
        }
        afterEach {
            transaction {
                AuctionOfferBuyerRestrictionTable.deleteWhere { AuctionOfferBuyerRestrictionTable.auctionId inList fx.auctionIds }
                AuctionOfferTable.deleteWhere {
                    (AuctionOfferTable.auctionId inList fx.auctionIds) and
                        (AuctionOfferTable.fromSeller eq true)
                }
                AuctionOfferTable.deleteWhere { AuctionOfferTable.auctionId inList fx.auctionIds }
                AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId inList fx.auctionIds }
                AuctionBidTable.deleteWhere { AuctionBidTable.auctionId inList fx.auctionIds }
                AuctionTable.deleteWhere { AuctionTable.id inList fx.auctionIds }
                AuctionFiatAcknowledgmentTable.deleteWhere { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId inList fx.memberIds }
            }
            fx.auctionIds.clear()
        }
        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionFiatEnabled] =
                        false
                }
                AuctionFiatAcknowledgmentTable.deleteWhere { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId inList fx.memberIds }
            }
            fx.cleanUp()
            db.deactivate()
        }

        suspend fun GalleryApp.list(seller: Uuid): AuctionDto {
            val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }
            if (!a.sellerAcknowledgedCurrentVersion) {
                call(member = seller) {
                    svc(it).acknowledgeFiatSellerDisclaimer(
                        AuctionComplianceAcknowledgmentInput(
                            disclaimerVersion = a.sellerDisclaimerVersion,
                            disclaimerSha256 = a.sellerDisclaimerSha256,
                        ),
                    )
                }
            }
            val created =
                call(member = seller) {
                    svc(it).createFiatListing(
                        CreateFiatAuctionListingInput(
                            title = "Race",
                            description = "d",
                            currency = "EUR",
                            startingPriceMinor = 1000,
                            buyNowPriceMinor = 9000,
                            durationHours = 24,
                            paymentDetails =
                                AuctionPaymentDetailsInput(
                                    accountHolder = "Race Seller",
                                    iban = "DE89370400440532013000",
                                    paymentLink = null,
                                ),
                            sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                            offersEnabled = true,
                        ),
                    )
                }
            fx.auctionIds += Uuid.parse(created.id)
            return created
        }

        /** Runs both operations at once and returns their results in order. */
        suspend fun <A, B> race(
            first: suspend () -> Result<A>,
            second: suspend () -> Result<B>,
        ): Pair<Result<A>, Result<B>> =
            coroutineScope {
                val a = async(Dispatchers.Default) { first() }
                val b = async(Dispatchers.Default) { second() }
                a.await() to b.await()
            }

        test("the seller accepts the same offer twice at once: exactly one acceptance, the other gets AuctionOfferNotOpenException") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    val offer = call(member = buyer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3000) }
                    val results =
                        coroutineScope {
                            List(
                                2,
                            ) { async(Dispatchers.Default) { attempt(member = seller) { c -> svc(c).acceptOffer(offer.id) } } }.awaitAll()
                        }
                    results.count { it.isSuccess } shouldBe 1
                    results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<AuctionOfferNotOpenException>()
                    auctionOf(created.id)[AuctionTable.winnerMemberId] shouldBe buyer
                    auctionOf(created.id)[AuctionTable.finalPriceMinor] shouldBe 3000
                    offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.ACCEPTED)
                }
            }
        }

        test("accept against Sofortkauf: one winner at one price, no OFFERED row is left behind") {
            val seller = fx.member(label = "seller")
            val offerer = fx.member(label = "offerer")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                var offerWins = 0
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    val offer = call(member = offerer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3000) }
                    val (accept, buy) =
                        race(
                            { attempt(member = seller) { c -> svc(c).acceptOffer(offer.id) } },
                            { attempt(member = buyer) { c -> svc(c).buyNow(created.id) } },
                        )
                    val row = auctionOf(created.id)
                    row[AuctionTable.status] shouldBe AuctionStatus.AWAITING_PAYMENT
                    if (accept.isSuccess) {
                        offerWins++
                        buy.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                        row[AuctionTable.winnerMemberId] shouldBe offerer
                        row[AuctionTable.finalPriceMinor] shouldBe 3000
                        offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.ACCEPTED)
                    } else {
                        accept.exceptionOrNull().shouldBeInstanceOf<AuctionOfferNotOpenException>()
                        row[AuctionTable.winnerMemberId] shouldBe buyer
                        row[AuctionTable.finalPriceMinor] shouldBe 9000
                        offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.EXPIRED)
                    }
                }
                (offerWins in 0..OFFER_ROUNDS) shouldBe true
            }
        }

        test(
            "accept against a higher bid: either the offer wins (the bid then fails) or the bid lands first (the acceptance is Outbid) -- never both",
        ) {
            val seller = fx.member(label = "seller")
            val offerer = fx.member(label = "offerer")
            val bidder = fx.member(label = "bidder")
            val bidder2 = fx.member(label = "bidder2")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    call(member = bidder) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 2000) }
                    val offer = call(member = offerer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 2100) }
                    val (accept, bid) =
                        race(
                            { attempt(member = seller) { c -> svc(c).acceptOffer(offer.id) } },
                            { attempt(member = bidder2) { c -> svc(c).placeFiatBid(auctionId = created.id, maxBidMinor = 5000) } },
                        )
                    val row = auctionOf(created.id)
                    if (accept.isSuccess) {
                        // the acceptance came first: the auction is awarded, a later bid is refused
                        row[AuctionTable.winnerMemberId] shouldBe offerer
                        row[AuctionTable.finalPriceMinor] shouldBe 2100
                        bid.exceptionOrNull()?.shouldBeInstanceOf<ConflictException>()
                    } else {
                        accept.exceptionOrNull().shouldBeInstanceOf<network.lapis.cloud.shared.rpc.AuctionOfferOutbidException>()
                        bid.isSuccess shouldBe true
                        row[AuctionTable.status] shouldBe AuctionStatus.OPEN
                        offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.OFFERED)
                    }
                }
            }
        }

        test("accept against the lazy close at the end time: the auction ends without sale, the acceptance is refused, the offer expires") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val reader = fx.member(label = "reader")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    val offer = call(member = buyer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3000) }
                    endNow(created.id)
                    val (accept, read) =
                        race(
                            { attempt(member = seller) { c -> svc(c).acceptOffer(offer.id) } },
                            { attempt(member = reader) { c -> svc(c).getAuction(created.id) } },
                        )
                    accept.exceptionOrNull().shouldBeInstanceOf<AuctionOfferNotOpenException>()
                    read.isSuccess shouldBe true
                    auctionOf(created.id)[AuctionTable.status] shouldBe AuctionStatus.CLOSED_NO_SALE
                    auctionOf(created.id)[AuctionTable.winnerMemberId] shouldBe null
                    offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.EXPIRED)
                }
            }
        }

        test(
            "accept against withdraw: exactly one of them wins, the loser gets a typed answer, the end state is consistent",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS * 3) {
                    val created = list(seller)
                    val offer = call(member = buyer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3000) }
                    val (accept, withdraw) =
                        race(
                            { attempt(member = seller) { c -> svc(c).acceptOffer(offer.id) } },
                            { attempt(member = buyer) { c -> svc(c).withdrawOffer(offer.id) } },
                        )
                    (accept.isSuccess xor withdraw.isSuccess) shouldBe true
                    if (accept.isSuccess) {
                        // The withdrawal lost: the author is told HOW the offer was answered, never a plain "not open".
                        withdraw.exceptionOrNull().shouldBeInstanceOf<AuctionOfferAlreadyAnsweredException>().answer shouldBe
                            AuctionOfferStatus.ACCEPTED
                        auctionOf(created.id)[AuctionTable.winnerMemberId] shouldBe buyer
                        offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.ACCEPTED)
                    } else {
                        accept.exceptionOrNull().shouldBeInstanceOf<AuctionOfferNotOpenException>()
                        auctionOf(created.id)[AuctionTable.status] shouldBe AuctionStatus.OPEN
                        offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.WITHDRAWN)
                    }
                }
            }
        }

        test("two offers of the same buyer at once: exactly one is created, the other gets AuctionOfferBuyerHasOpenException") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    val results =
                        coroutineScope {
                            listOf(3000L, 3100L)
                                .map { minor ->
                                    async(
                                        Dispatchers.Default,
                                    ) { attempt(member = buyer) { c -> svc(c).makeOffer(auctionId = created.id, amountMinor = minor) } }
                                }.awaitAll()
                        }
                    results.count { it.isSuccess } shouldBe 1
                    results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<AuctionOfferBuyerHasOpenException>()
                    offerStatuses(created.id) shouldBe listOf(AuctionOfferStatus.OFFERED)
                }
            }
        }

        test(
            "two buyers fill the last open slot at once: the limit of ${AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION} open offers holds",
        ) {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            val filler = fx.member(label = "filler")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    val now = DbClock.nowLocalDateTime()
                    transaction {
                        repeat(AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION - 1) {
                            AuctionOfferTable.insert {
                                it[AuctionOfferTable.id] = Uuid.random()
                                it[AuctionOfferTable.auctionId] = Uuid.parse(created.id)
                                it[AuctionOfferTable.buyerMemberId] = filler
                                it[AuctionOfferTable.fromSeller] = false
                                it[AuctionOfferTable.parentOfferId] = null
                                it[AuctionOfferTable.amountMinor] = 2000
                                it[AuctionOfferTable.status] = AuctionOfferStatus.OFFERED
                                it[AuctionOfferTable.createdAt] = now
                                it[AuctionOfferTable.expiresAt] =
                                    now.toInstant(ServerClock.zone).plus(1.hours).toLocalDateTime(ServerClock.zone)
                                it[AuctionOfferTable.decidedAt] = null
                            }
                        }
                    }
                    val results =
                        coroutineScope {
                            listOf(b1, b2)
                                .map { b ->
                                    async(
                                        Dispatchers.Default,
                                    ) { attempt(member = b) { c -> svc(c).makeOffer(auctionId = created.id, amountMinor = 3000) } }
                                }.awaitAll()
                        }
                    results.count { it.isSuccess } shouldBe 1
                    results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<AuctionOfferAuctionFullException>()
                    offerStatuses(created.id).size shouldBe AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION
                }
            }
        }

        test(
            "eleven buyers at once: exactly ${AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION} proposals are made, the eleventh gets AuctionOfferAuctionFullException, nobody gets a 500",
        ) {
            val seller = fx.member(label = "seller")
            val buyers = (1..AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION + 1).map { fx.member(label = "buyer$it") }
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS / 2) {
                    val created = list(seller)
                    val results =
                        coroutineScope {
                            buyers
                                .map { b ->
                                    async(
                                        Dispatchers.Default,
                                    ) { attempt(member = b) { c -> svc(c).makeOffer(auctionId = created.id, amountMinor = 3000) } }
                                }.awaitAll()
                        }
                    results.count { it.isSuccess } shouldBe AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION
                    results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<AuctionOfferAuctionFullException>()
                    offerStatuses(created.id).count { it == AuctionOfferStatus.OFFERED } shouldBe
                        AuctionOfferRules.MAX_OPEN_OFFERS_PER_AUCTION
                }
            }
        }

        test("the third decline against a new proposal of the same buyer: never blocked AND holding a new open proposal") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    repeat(AuctionOfferRules.MAX_DECLINES_BEFORE_BLOCK - 1) { i ->
                        val p = call(member = buyer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3000L + i) }
                        call(member = seller) { svc(it).declineOffer(p.id) }
                    }
                    val third = call(member = buyer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3100) }
                    val (decline, fresh) =
                        race(
                            { attempt(member = seller) { c -> svc(c).declineOffer(third.id) } },
                            { attempt(member = buyer) { c -> svc(c).makeOffer(auctionId = created.id, amountMinor = 3200) } },
                        )
                    decline.isSuccess shouldBe true
                    // The new proposal never wins: before the decline the buyer still has the third one open, afterwards he is blocked.
                    fresh.isFailure shouldBe true
                    val failure = fresh.exceptionOrNull()
                    (failure is AuctionOfferBuyerHasOpenException || failure is AuctionOfferBlockedException) shouldBe true
                    val restriction =
                        transaction {
                            AuctionOfferBuyerRestrictionTable
                                .selectAll()
                                .where { AuctionOfferBuyerRestrictionTable.auctionId eq Uuid.parse(created.id) }
                                .single()
                        }
                    (restriction[AuctionOfferBuyerRestrictionTable.blockedUntil] != null) shouldBe true
                    offerStatuses(created.id).count { it == AuctionOfferStatus.OFFERED } shouldBe 0
                }
            }
        }

        test("two declines of the buyer's proposals racing: the counter is raised exactly twice, never lost") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                repeat(OFFER_ROUNDS) {
                    val created = list(seller)
                    val now = DbClock.nowLocalDateTime()
                    // two OPEN proposals of the SAME buyer can only exist as legacy rows (the service allows one): construct them directly
                    val ids =
                        List(2) {
                            val id = Uuid.random()
                            transaction {
                                AuctionOfferTable.insert {
                                    it[AuctionOfferTable.id] = id
                                    it[AuctionOfferTable.auctionId] = Uuid.parse(created.id)
                                    it[AuctionOfferTable.buyerMemberId] = buyer
                                    it[AuctionOfferTable.fromSeller] = false
                                    it[AuctionOfferTable.parentOfferId] = null
                                    it[AuctionOfferTable.amountMinor] = 2500
                                    it[AuctionOfferTable.status] = AuctionOfferStatus.OFFERED
                                    it[AuctionOfferTable.createdAt] = now
                                    it[AuctionOfferTable.expiresAt] =
                                        now.toInstant(ServerClock.zone).plus(1.hours).toLocalDateTime(ServerClock.zone)
                                    it[AuctionOfferTable.decidedAt] = null
                                }
                            }
                            id
                        }
                    val results =
                        coroutineScope {
                            ids
                                .map { id ->
                                    async(Dispatchers.Default) { attempt(member = seller) { c -> svc(c).declineOffer(id.toString()) } }
                                }.awaitAll()
                        }
                    results.all { it.isSuccess } shouldBe true
                    val restriction =
                        transaction {
                            AuctionOfferBuyerRestrictionTable
                                .selectAll()
                                .where { AuctionOfferBuyerRestrictionTable.auctionId eq Uuid.parse(created.id) }
                                .single()
                        }
                    restriction[AuctionOfferBuyerRestrictionTable.declinesInCycle] shouldBe 2
                }
            }
        }
    })
