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
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.gallery.GalleryApp
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.uuid.Uuid

private val RACE_KEY: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
private const val ROUNDS = 8

/**
 * V1.9.320 -- the races of the Fiat auction on H2 AND PostgreSQL. Every one of them goes through the auction row lock
 * (`lockAndCloseIfDue`), so exactly one outcome may win. Each block runs [ROUNDS] times: a single lucky interleaving proves nothing, and the counters
 * per block (not only the final state) catch a retry that masks a double award. On PostgreSQL the deadlock counter must stay at zero.
 */
abstract class AuctionFiatRaceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()

        fun svc(call: ApplicationCall) = AuctionService(call = call, secretEncryptionKey = RACE_KEY)

        fun statusOf(id: String) = transaction { AuctionTable.selectAll().where { AuctionTable.id eq Uuid.parse(id) }.single() }

        fun ledgerRows(ids: List<Uuid>): Long =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where {
                        (LtrLedgerEntryTable.referenceType eq LtrLedgerReferenceType.AUCTION) and
                            (LtrLedgerEntryTable.referenceId inList ids)
                    }.count()
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

        suspend fun GalleryApp.list(
            seller: Uuid,
            buyNow: Long? = null,
        ): AuctionDto {
            val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }
            if (!a.sellerAcknowledgedCurrentVersion) {
                call(member = seller) {
                    svc(
                        it,
                    ).acknowledgeFiatSellerDisclaimer(
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
                            buyNowPriceMinor = buyNow,
                            durationHours = 24,
                            paymentDetails =
                                AuctionPaymentDetailsInput(
                                    accountHolder = "Race Seller",
                                    iban = "DE89370400440532013000",
                                    paymentLink = null,
                                ),
                            sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                        ),
                    )
                }
            fx.auctionIds += Uuid.parse(created.id)
            return created
        }

        test("two createFiatListing at once with one slot left: exactly one is created, the other is refused, the limit holds") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                repeat(AuctionCurrencies.MAX_OPEN_PER_SELLER - 1) { list(seller) }
                val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }
                val input =
                    CreateFiatAuctionListingInput(
                        title = "Race",
                        description = "d",
                        currency = "EUR",
                        startingPriceMinor = 1000,
                        buyNowPriceMinor = null,
                        durationHours = 24,
                        paymentDetails =
                            AuctionPaymentDetailsInput(accountHolder = "Race Seller", iban = "DE89370400440532013000", paymentLink = null),
                        sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                        sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                    )
                val results =
                    coroutineScope {
                        List(
                            2,
                        ) { async(Dispatchers.Default) { attempt(member = seller) { c -> svc(c).createFiatListing(input) } } }.awaitAll()
                    }
                results.forEach { r -> r.getOrNull()?.let { fx.auctionIds += Uuid.parse(it.id) } }
                results.count { it.isSuccess } shouldBe 1
                results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                transaction {
                    AuctionTable
                        .selectAll()
                        .where {
                            (AuctionTable.sellerMemberId eq seller) and (AuctionTable.status eq AuctionStatus.OPEN)
                        }.count()
                } shouldBe AuctionCurrencies.MAX_OPEN_PER_SELLER.toLong()
            }
        }

        test("two Sofortkauf at once: exactly one buyer wins, the other is refused, and the ledger stays untouched") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            galleryTestApplication(fixtures = fx) {
                var winsB1 = 0
                var winsB2 = 0
                repeat(ROUNDS) {
                    val created = list(seller, buyNow = 5000)
                    val results =
                        coroutineScope {
                            listOf(b1, b2)
                                .map { buyer ->
                                    async(Dispatchers.Default) {
                                        buyer to
                                            attempt(member = buyer) { c -> svc(c).buyNow(created.id) }
                                    }
                                }.awaitAll()
                        }
                    val won = results.filter { it.second.isSuccess }
                    val lost = results.filter { it.second.isFailure }
                    won.size shouldBe 1
                    lost
                        .single()
                        .second
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    val row = statusOf(created.id)
                    row[AuctionTable.status] shouldBe AuctionStatus.AWAITING_PAYMENT
                    row[AuctionTable.winnerMemberId] shouldBe won.single().first
                    row[AuctionTable.finalPriceMinor] shouldBe 5000
                    if (won.single().first == b1) winsB1++ else winsB2++
                    ledgerRows(listOf(Uuid.parse(created.id))) shouldBe 0
                }
                (winsB1 + winsB2) shouldBe ROUNDS
            }
        }

        test(
            "a Sofortkauf against a bid: the buyer always ends as the winner at the Sofortkauf price, the bid is accepted or refused, never both wrong",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val bidder = fx.member(label = "bidder")
            galleryTestApplication(fixtures = fx) {
                repeat(ROUNDS) {
                    val created = list(seller, buyNow = 5000)
                    val results =
                        coroutineScope {
                            val buy = async(Dispatchers.Default) { attempt(member = buyer) { c -> svc(c).buyNow(created.id) } }
                            val bid =
                                async(
                                    Dispatchers.Default,
                                ) { attempt(member = bidder) { c -> svc(c).placeFiatBid(auctionId = created.id, maxBidMinor = 2000) } }
                            buy.await() to bid.await()
                        }
                    results.first.isSuccess shouldBe true
                    results.second.exceptionOrNull()?.shouldBeInstanceOf<ConflictException>()
                    val row = statusOf(created.id)
                    row[AuctionTable.status] shouldBe AuctionStatus.AWAITING_PAYMENT
                    row[AuctionTable.winnerMemberId] shouldBe buyer
                    row[AuctionTable.finalPriceMinor] shouldBe 5000
                    ledgerRows(listOf(Uuid.parse(created.id))) shouldBe 0
                }
            }
        }

        test("a Sofortkauf against the lazy close at the end time: one outcome only, CLOSED_NO_SALE, no winner, no payment details left") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val reader = fx.member(label = "reader")
            galleryTestApplication(fixtures = fx) {
                repeat(ROUNDS) {
                    val created = list(seller, buyNow = 5000)
                    transaction {
                        AuctionTable.update({ AuctionTable.id eq Uuid.parse(created.id) }) {
                            it[AuctionTable.endsAt] =
                                LocalDateTime(2020, 1, 1, 0, 0)
                        }
                    }
                    coroutineScope {
                        val buy = async(Dispatchers.Default) { attempt(member = buyer) { c -> svc(c).buyNow(created.id) } }
                        val read = async(Dispatchers.Default) { attempt(member = reader) { c -> svc(c).getAuction(created.id) } }
                        buy.await().exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                        read.await().isSuccess shouldBe true
                    }
                    val row = statusOf(created.id)
                    row[AuctionTable.status] shouldBe AuctionStatus.CLOSED_NO_SALE
                    row[AuctionTable.winnerMemberId] shouldBe null
                    transaction {
                        AuctionPaymentDetailsTable
                            .selectAll()
                            .where {
                                AuctionPaymentDetailsTable.auctionId eq
                                    Uuid.parse(
                                        created.id,
                                    )
                            }.count()
                    } shouldBe 0
                }
            }
        }

        test("seller and winner confirm at the same moment: both succeed exactly once and the auction ends COMPLETED") {
            val seller = fx.member(label = "seller")
            val winner = fx.member(label = "winner")
            galleryTestApplication(fixtures = fx) {
                repeat(ROUNDS) {
                    val created = list(seller, buyNow = 5000)
                    call(member = winner) { svc(it).buyNow(created.id) }
                    val results =
                        coroutineScope {
                            val pay =
                                async(Dispatchers.Default) { attempt(member = seller) { c -> svc(c).confirmPaymentReceived(created.id) } }
                            val deliver =
                                async(Dispatchers.Default) { attempt(member = winner) { c -> svc(c).confirmDeliveryReceived(created.id) } }
                            pay.await() to deliver.await()
                        }
                    results.first.isSuccess shouldBe true
                    results.second.isSuccess shouldBe true
                    val row = statusOf(created.id)
                    row[AuctionTable.status] shouldBe AuctionStatus.COMPLETED
                    (row[AuctionTable.paymentConfirmedAt] != null) shouldBe true
                    (row[AuctionTable.deliveryConfirmedAt] != null) shouldBe true
                }
            }
        }
    })
