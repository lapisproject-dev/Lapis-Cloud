package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.auction.AuctionPaymentDetailsRetentionPoller
import network.lapis.cloud.server.auction.AuctionPaymentDetailsStore
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.gallery.GalleryApp
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.UpdateAuctionTermsInput
import network.lapis.cloud.shared.rpc.AuctionAlreadyConfirmedException
import network.lapis.cloud.shared.rpc.AuctionAmountOutOfRangeException
import network.lapis.cloud.shared.rpc.AuctionCurrencyLockedException
import network.lapis.cloud.shared.rpc.AuctionCurrencyMismatchException
import network.lapis.cloud.shared.rpc.AuctionCurrencyUnsupportedException
import network.lapis.cloud.shared.rpc.AuctionFiatDisabledException
import network.lapis.cloud.shared.rpc.AuctionFiatDisclaimerRequiredException
import network.lapis.cloud.shared.rpc.AuctionFiatEncryptionUnavailableException
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsInvalidException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import kotlin.uuid.Uuid

private const val GOOD_IBAN = "DE89370400440532013000"
private const val GOOD_LINK = "https://pay.example.org/seller-abc"
private const val HOLDER = "Sabine Verkäuferin-Test"
private val KEY: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

private fun details(
    iban: String? = GOOD_IBAN,
    link: String? = null,
    holder: String = HOLDER,
) = AuctionPaymentDetailsInput(accountHolder = holder, iban = iban, paymentLink = link)

/**
 * V1.9.320 -- the Fiat (EUR, direct payment) path of [AuctionService] end to end: the full lifecycle, every refusal, who may see the payment
 * details, that nothing of them leaks into a DTO, an audit entry, a message or the database, and that the LTR ledger is never touched.
 */
abstract class AuctionFiatServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()

        fun svc(
            call: io.ktor.server.application.ApplicationCall,
            key: ByteArray? = KEY,
        ) = AuctionService(call = call, secretEncryptionKey = key)

        fun setFiat(enabled: Boolean) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionFiatEnabled] = enabled
                }
            }
        }

        fun endNow(auctionId: String) {
            transaction {
                AuctionTable.update({ AuctionTable.id eq Uuid.parse(auctionId) }) {
                    it[AuctionTable.endsAt] = LocalDateTime(2020, 1, 1, 0, 0)
                }
            }
        }

        fun ledgerRowsOf(auctionIds: List<Uuid>): Long =
            transaction {
                LtrLedgerEntryTable
                    .selectAll()
                    .where {
                        (LtrLedgerEntryTable.referenceType eq LtrLedgerReferenceType.AUCTION) and
                            (LtrLedgerEntryTable.referenceId inList auctionIds)
                    }.count()
            }

        fun mint(
            memberId: Uuid,
            amount: String,
        ) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = BigDecimal(amount)
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Fiat test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        fun memberLedgerRows(members: List<Uuid>): Long =
            transaction { LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.memberId inList members }.count() }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)

        beforeEach {
            fx.setAuctionEnabled(true)
            setFiat(true)
        }

        afterEach {
            transaction {
                AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId inList fx.auctionIds }
                AuctionBidTable.deleteWhere { AuctionBidTable.auctionId inList fx.auctionIds }
                AuctionTable.deleteWhere { AuctionTable.id inList fx.auctionIds }
                AuctionFiatAcknowledgmentTable.deleteWhere { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId inList fx.memberIds }
            }
            fx.auctionIds.clear()
            setFiat(false)
        }

        afterSpec {
            transaction {
                AuctionFiatAcknowledgmentTable.deleteWhere { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId inList fx.memberIds }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList fx.memberIds }
            }
            fx.cleanUp()
            db.deactivate()
        }

        /** The seller acknowledges the CURRENT notice and lists a Fiat auction. */
        suspend fun GalleryApp.listFiat(
            seller: Uuid,
            start: Long = 1000,
            buyNow: Long? = null,
            paymentDetails: AuctionPaymentDetailsInput = details(),
            title: String = "Fahrrad in Euro",
        ): AuctionDto {
            val availability = call(member = seller) { svc(it).getAuctionFiatAvailability() }
            if (!availability.sellerAcknowledgedCurrentVersion) {
                call(member = seller) {
                    svc(it).acknowledgeFiatSellerDisclaimer(
                        AuctionComplianceAcknowledgmentInput(
                            disclaimerVersion = availability.sellerDisclaimerVersion,
                            disclaimerSha256 = availability.sellerDisclaimerSha256,
                        ),
                    )
                }
            }
            val created =
                call(member = seller) {
                    svc(it).createFiatListing(
                        CreateFiatAuctionListingInput(
                            title = title,
                            description = "Beschreibung",
                            currency = "EUR",
                            startingPriceMinor = start,
                            buyNowPriceMinor = buyNow,
                            durationHours = 24,
                            paymentDetails = paymentDetails,
                            sellerDisclaimerVersion = availability.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = availability.sellerDisclaimerSha256,
                        ),
                    )
                }
            fx.auctionIds += Uuid.parse(created.id)
            return created
        }

        suspend fun GalleryApp.bid(
            bidder: Uuid,
            auctionId: String,
            minor: Long,
        ) = call(member = bidder) { svc(it).placeFiatBid(auctionId = auctionId, maxBidMinor = minor) }

        suspend fun <T> GalleryApp.refused(
            member: Uuid,
            op: suspend (io.ktor.server.application.ApplicationCall) -> T,
        ): Throwable = attempt(member = member, op = op).exceptionOrNull() ?: error("expected a refusal")

        test(
            "lifecycle: two bidders, award at the second price plus one euro, payment confirmed by the seller, goods by the winner -- no ledger row at all",
        ) {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            val outsider = fx.member(label = "outsider")
            galleryTestApplication(fixtures = fx) {
                val ledgerBefore = memberLedgerRows(listOf(seller, b1, b2))
                val created = listFiat(seller, start = 1000, buyNow = 9000)
                created.currency shouldBe "EUR"
                created.status shouldBe AuctionStatus.OPEN
                created.startingBidLtr shouldBe null
                created.startingPriceMinor shouldBe 1000
                created.listingFeeLtr.compareTo(BigDecimal.ZERO) shouldBe 0
                bid(b1, created.id, 3000).youAreLeader shouldBe true
                val second = bid(b2, created.id, 2000)
                second.youAreLeader shouldBe false
                second.currentPriceMinor shouldBe 2100
                second.currentPriceLtr shouldBe null
                // a bidder may only raise
                refused(b2) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 2000) }.shouldBeInstanceOf<ConflictException>()
                refused(b2) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 1500) }.shouldBeInstanceOf<ConflictException>()

                endNow(created.id)
                val awarded = call(member = outsider) { svc(it).getAuction(created.id) }
                // the payment progress is private: a stranger sees plain SETTLED, the winner and the seller the real status
                awarded.status shouldBe AuctionStatus.SETTLED
                awarded.effectiveStatus shouldBe AuctionStatus.SETTLED
                call(member = b1) { svc(it).getAuction(created.id) }.status shouldBe AuctionStatus.AWAITING_PAYMENT
                awarded.winnerMemberId shouldBe b1.toString()
                awarded.finalPriceMinor shouldBe 2100
                awarded.currentPriceMinor shouldBe 2100
                // the stranger does not see who confirmed what
                awarded.paymentConfirmedAt shouldBe null
                awarded.hasPaymentLink shouldBe false
                awarded.callerIsSeller shouldBe false

                val details = call(member = b1) { svc(it).getAuctionPaymentDetails(created.id) }
                details.amountMinor shouldBe 2100
                details.currency shouldBe "EUR"
                details.accountHolder shouldBe HOLDER
                details.ibanFormatted shouldBe "DE89 3704 0044 0532 0130 00"
                details.reference shouldBe "Lapis-Auktion ${created.id.take(8)}"
                call(member = seller) { svc(it).getAuctionPaymentDetails(created.id) }.amountMinor shouldBe 2100

                // the goods first: the status stays, the payment is still open
                val deliveredFirst = call(member = b1) { svc(it).confirmDeliveryReceived(created.id) }
                deliveredFirst.status shouldBe AuctionStatus.AWAITING_PAYMENT
                (deliveredFirst.deliveryConfirmedAt != null) shouldBe true
                deliveredFirst.callerIsWinner shouldBe true
                val paid = call(member = seller) { svc(it).confirmPaymentReceived(created.id) }
                paid.status shouldBe AuctionStatus.COMPLETED
                refused(seller) { svc(it).confirmPaymentReceived(created.id) }.shouldBeInstanceOf<AuctionAlreadyConfirmedException>()
                refused(b1) { svc(it).confirmDeliveryReceived(created.id) }.shouldBeInstanceOf<AuctionAlreadyConfirmedException>()

                ledgerRowsOf(listOf(Uuid.parse(created.id))) shouldBe 0
                memberLedgerRows(listOf(seller, b1, b2)) shouldBe ledgerBefore
            }
        }

        test("lifecycle in the usual order: seller confirms the payment (PAID), then the winner the goods (COMPLETED)") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                bid(b1, created.id, 1500)
                endNow(created.id)
                val first = call(member = seller) { svc(it).confirmPaymentReceived(created.id) }
                first.status shouldBe AuctionStatus.PAID
                first.winnerMemberId shouldBe b1.toString()
                first.finalPriceMinor shouldBe 1000
                call(member = b1) { svc(it).confirmDeliveryReceived(created.id) }.status shouldBe AuctionStatus.COMPLETED
            }
        }

        test("an auction without a bid closes without a sale and its payment details are deleted at once") {
            val seller = fx.member(label = "seller")
            val reader = fx.member(label = "reader")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                transaction {
                    AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq Uuid.parse(created.id) }.count()
                } shouldBe 1
                endNow(created.id)
                call(member = reader) { svc(it).getAuction(created.id) }.status shouldBe AuctionStatus.CLOSED_NO_SALE
                transaction {
                    AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq Uuid.parse(created.id) }.count()
                } shouldBe 0
                refused(seller) { svc(it).getAuctionPaymentDetails(created.id) }.shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("Sofortkauf in Euro awards at the Sofortkauf price at once, also when the buyer already leads; no ledger") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 1000, buyNow = 5000)
                bid(b1, created.id, 2000)
                val bought = call(member = b1) { svc(it).buyNow(created.id) }
                bought.status shouldBe AuctionStatus.AWAITING_PAYMENT
                bought.winnerMemberId shouldBe b1.toString()
                bought.finalPriceMinor shouldBe 5000
                refused(b2) { svc(it).buyNow(created.id) }.shouldBeInstanceOf<ConflictException>()
                ledgerRowsOf(listOf(Uuid.parse(created.id))) shouldBe 0
                call(member = b1) { svc(it).getAuctionPaymentDetails(created.id) }.amountMinor shouldBe 5000
            }
        }

        test("Sofortkauf is refused for the seller, without a Sofortkauf price and once the current price reached it") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            val b3 = fx.member(label = "b3")
            galleryTestApplication(fixtures = fx) {
                val noPrice = listFiat(seller, title = "ohne Sofortkauf")
                refused(b1) { svc(it).buyNow(noPrice.id) }.shouldBeInstanceOf<ConflictException>()
                val withPrice = listFiat(seller, start = 1000, buyNow = 2000, title = "mit Sofortkauf")
                refused(seller) { svc(it).buyNow(withPrice.id) }.shouldBeInstanceOf<ConflictException>()
                bid(b1, withPrice.id, 3000)
                bid(b2, withPrice.id, 2500)
                // the second price (2500 + 100) is above the Sofortkauf price: no Sofortkauf any more
                refused(b3) { svc(it).buyNow(withPrice.id) }.shouldBeInstanceOf<ConflictException>()
            }
        }

        test(
            "listing refusals: switch off, missing or stale acknowledgment, unsupported currency, amounts, durations, payment details, no key",
        ) {
            val seller = fx.member(label = "seller")
            val acked = fx.member(label = "acked")
            galleryTestApplication(fixtures = fx) {
                val availability = call(member = seller) { svc(it).getAuctionFiatAvailability() }
                availability.fiatEnabled shouldBe true
                availability.sellerAcknowledgedCurrentVersion shouldBe false

                fun input(
                    currency: String = "EUR",
                    start: Long = 1000,
                    buyNow: Long? = null,
                    hours: Int = 24,
                    pd: AuctionPaymentDetailsInput = details(),
                    version: String = availability.sellerDisclaimerVersion,
                    sha: String = availability.sellerDisclaimerSha256,
                    title: String = "t",
                ) = CreateFiatAuctionListingInput(
                    title = title,
                    description = "d",
                    currency = currency,
                    startingPriceMinor = start,
                    buyNowPriceMinor = buyNow,
                    durationHours = hours,
                    paymentDetails = pd,
                    sellerDisclaimerVersion = version,
                    sellerDisclaimerSha256 = sha,
                )

                // not acknowledged yet
                refused(seller) { svc(it).createFiatListing(input()) }.shouldBeInstanceOf<AuctionFiatDisclaimerRequiredException>()
                // a wrong hash on the acknowledgment itself
                refused(seller) {
                    svc(
                        it,
                    ).acknowledgeFiatSellerDisclaimer(
                        AuctionComplianceAcknowledgmentInput(
                            disclaimerVersion = availability.sellerDisclaimerVersion,
                            disclaimerSha256 = "00".repeat(32),
                        ),
                    )
                }.shouldBeInstanceOf<AuctionFiatDisclaimerRequiredException>()
                call(member = seller) {
                    svc(it).acknowledgeFiatSellerDisclaimer(
                        AuctionComplianceAcknowledgmentInput(
                            disclaimerVersion = availability.sellerDisclaimerVersion,
                            disclaimerSha256 = availability.sellerDisclaimerSha256,
                        ),
                    )
                }.sellerAcknowledgedCurrentVersion shouldBe true
                // acknowledged, but the echo is stale
                refused(seller) { svc(it).createFiatListing(input(version = "1999-01-01.v0")) }
                    .shouldBeInstanceOf<AuctionFiatDisclaimerRequiredException>()
                refused(seller) { svc(it).createFiatListing(input(sha = "11".repeat(32))) }
                    .shouldBeInstanceOf<AuctionFiatDisclaimerRequiredException>()
                // another member did not acknowledge: his echo is correct but he has no acknowledgment
                refused(acked) { svc(it).createFiatListing(input()) }.shouldBeInstanceOf<AuctionFiatDisclaimerRequiredException>()

                listOf("USD", "eur", "LT", "").forEach { code ->
                    refused(
                        seller,
                    ) { svc(it).createFiatListing(input(currency = code)) }.shouldBeInstanceOf<AuctionCurrencyUnsupportedException>()
                }
                listOf(99L, 0L, -5L, 10_000_001L).forEach { amount ->
                    refused(
                        seller,
                    ) { svc(it).createFiatListing(input(start = amount)) }.shouldBeInstanceOf<AuctionAmountOutOfRangeException>()
                }
                refused(
                    seller,
                ) { svc(it).createFiatListing(input(buyNow = 10_000_001)) }.shouldBeInstanceOf<AuctionAmountOutOfRangeException>()
                refused(seller) { svc(it).createFiatListing(input(start = 1000, buyNow = 1000)) }.shouldBeInstanceOf<ConflictException>()
                refused(seller) { svc(it).createFiatListing(input(start = 1000, buyNow = 900)) }.shouldBeInstanceOf<ConflictException>()
                refused(seller) { svc(it).createFiatListing(input(hours = 0)) }.shouldBeInstanceOf<ConflictException>()
                refused(seller) { svc(it).createFiatListing(input(hours = 2161)) }.shouldBeInstanceOf<ConflictException>()
                refused(seller) { svc(it).createFiatListing(input(title = "  ")) }.shouldBeInstanceOf<ConflictException>()
                refused(seller) { svc(it).createFiatListing(input(pd = details(iban = "DE89370400440532013001"))) }
                    .shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
                refused(seller) { svc(it).createFiatListing(input(pd = details(iban = null, link = null))) }
                    .shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
                refused(seller) { svc(it).createFiatListing(input(pd = details(link = "http://pay.example.org/x"))) }
                    .shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
                // no encryption key configured on this instance
                refused(
                    seller,
                ) { svc(it, key = null).createFiatListing(input()) }.shouldBeInstanceOf<AuctionFiatEncryptionUnavailableException>()
                // the instance switch off, even for an acknowledged member
                setFiat(false)
                refused(seller) { svc(it).createFiatListing(input()) }.shouldBeInstanceOf<AuctionFiatDisabledException>()
                refused(seller) {
                    svc(it).acknowledgeFiatSellerDisclaimer(
                        AuctionComplianceAcknowledgmentInput(
                            disclaimerVersion = availability.sellerDisclaimerVersion,
                            disclaimerSha256 = availability.sellerDisclaimerSha256,
                        ),
                    )
                }.shouldBeInstanceOf<AuctionFiatDisabledException>()
                // and the auction feature itself off
                setFiat(true)
                fx.setAuctionEnabled(false)
                refused(seller) { svc(it).createFiatListing(input()) }.shouldBeInstanceOf<ConflictException>()
            }
        }

        test("at most 20 open Euro auctions per seller") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                repeat(20) { listFiat(seller, title = "Nr $it") }
                refused(seller) {
                    val a = svc(it).getAuctionFiatAvailability()
                    svc(it).createFiatListing(
                        CreateFiatAuctionListingInput(
                            title = "zu viel",
                            description = "d",
                            currency = "EUR",
                            startingPriceMinor = 1000,
                            buyNowPriceMinor = null,
                            durationHours = 24,
                            paymentDetails = details(),
                            sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                        ),
                    )
                }.shouldBeInstanceOf<ConflictException>()
            }
        }

        test(
            "the payment progress is private: a stranger sees SETTLED for AWAITING_PAYMENT, PAID and COMPLETED, also through the list filter",
        ) {
            val seller = fx.member(label = "seller")
            val winner = fx.member(label = "winner")
            val stranger = fx.member(label = "stranger")
            val loser = fx.member(label = "loser")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, title = "Privater Fortschritt")
                bid(loser, created.id, 1200)
                bid(winner, created.id, 1500)
                endNow(created.id)

                suspend fun loserBidStatus(): AuctionStatus =
                    call(member = loser) { svc(it).listMyBids() }.single { it.auctionId == created.id }.auctionStatus

                suspend fun strangerView(): AuctionDto = call(member = stranger) { svc(it).getAuction(created.id) }

                suspend fun idsFor(
                    member: Uuid,
                    filter: AuctionStatus,
                ): List<String> = call(member = member) { svc(it).listAuctions(filter) }.map { it.id }
                strangerView().status shouldBe AuctionStatus.SETTLED
                loserBidStatus() shouldBe AuctionStatus.SETTLED
                (created.id in idsFor(stranger, AuctionStatus.AWAITING_PAYMENT)) shouldBe false
                (created.id in idsFor(stranger, AuctionStatus.SETTLED)) shouldBe true
                (created.id in idsFor(seller, AuctionStatus.AWAITING_PAYMENT)) shouldBe true
                (created.id in idsFor(winner, AuctionStatus.AWAITING_PAYMENT)) shouldBe true

                call(member = seller) { svc(it).confirmPaymentReceived(created.id) }.status shouldBe AuctionStatus.PAID
                strangerView().status shouldBe AuctionStatus.SETTLED
                loserBidStatus() shouldBe AuctionStatus.SETTLED
                (created.id in idsFor(stranger, AuctionStatus.PAID)) shouldBe false
                (created.id in idsFor(seller, AuctionStatus.PAID)) shouldBe true

                call(member = winner) { svc(it).confirmDeliveryReceived(created.id) }.status shouldBe AuctionStatus.COMPLETED
                strangerView().status shouldBe AuctionStatus.SETTLED
                loserBidStatus() shouldBe AuctionStatus.SETTLED
                (created.id in idsFor(stranger, AuctionStatus.COMPLETED)) shouldBe false
                (created.id in idsFor(stranger, AuctionStatus.SETTLED)) shouldBe true
                (created.id in idsFor(winner, AuctionStatus.COMPLETED)) shouldBe true
                // a party does not find its own auction under SETTLED any more (it sees the real status)
                (created.id in idsFor(seller, AuctionStatus.SETTLED)) shouldBe false
            }
        }

        test("the open-Euro limit also holds when LTR auctions are switched to EUR") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                mint(seller, "100.00")
                repeat(20) { listFiat(seller, title = "Nr $it") }
                val ltr =
                    call(member = seller) {
                        svc(it).createListing(
                            CreateAuctionListingInput(
                                title = "LTR zum Umstellen",
                                description = "d",
                                startingBidLtr = BigDecimal("5.00"),
                                buyNowPriceLtr = null,
                                durationHours = 24,
                            ),
                        )
                    }
                fx.auctionIds += Uuid.parse(ltr.id)
                val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }
                refused(seller) {
                    svc(it).updateAuctionTerms(
                        auctionId = ltr.id,
                        input =
                            UpdateAuctionTermsInput(
                                currency = "EUR",
                                startingPriceMinor = 1000,
                                paymentDetails = details(),
                                sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                                sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                            ),
                    )
                }.shouldBeInstanceOf<ConflictException>()
                call(member = seller) { svc(it).getAuction(ltr.id) }.currency shouldBe "LTR"
            }
        }

        test("switching EUR to LTR to EUR to LTR charges the listing fee only once") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                mint(seller, "100.00")
                val created = listFiat(seller)
                val id = Uuid.parse(created.id)
                val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }

                fun feeRows(): Long =
                    transaction {
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.referenceId eq id) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.AUCTION_LISTING_FEE)
                            }.count()
                    }

                suspend fun GalleryApp.toLtr() =
                    call(member = seller) {
                        svc(it).updateAuctionTerms(
                            auctionId = created.id,
                            input = UpdateAuctionTermsInput(currency = "LTR", startingBidLtr = BigDecimal("5.00")),
                        )
                    }

                suspend fun GalleryApp.toEur() =
                    call(member = seller) {
                        svc(it).updateAuctionTerms(
                            auctionId = created.id,
                            input =
                                UpdateAuctionTermsInput(
                                    currency = "EUR",
                                    startingPriceMinor = 1000,
                                    paymentDetails = details(),
                                    sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                                    sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                                ),
                        )
                    }
                feeRows() shouldBe 0
                toLtr().listingFeeLtr.compareTo(BigDecimal("0.01")) shouldBe 0
                feeRows() shouldBe 1
                toEur().currency shouldBe "EUR"
                val again = toLtr()
                again.currency shouldBe "LTR"
                again.listingFeeLtr.compareTo(BigDecimal("0.01")) shouldBe 0
                feeRows() shouldBe 1
            }
        }

        test(
            "bid refusals: seller, below the starting price, over the maximum, an LTR bid on a Euro auction and a Euro bid on an LTR auction",
        ) {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 1000)
                refused(seller) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 2000) }.shouldBeInstanceOf<ConflictException>()
                refused(
                    b1,
                ) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 50) }.shouldBeInstanceOf<AuctionAmountOutOfRangeException>()
                refused(b1) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 999) }.shouldBeInstanceOf<ConflictException>()
                refused(b1) {
                    svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 10_000_001)
                }.shouldBeInstanceOf<AuctionAmountOutOfRangeException>()
                call(member = b1) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 1500) }.youAreLeader shouldBe true
                refused(b1) {
                    svc(it).placeBid(auctionId = created.id, maxBidLtr = BigDecimal("5.00"))
                }.shouldBeInstanceOf<AuctionCurrencyMismatchException>()

                val ltr = fx.auction(seller = seller)
                refused(
                    b1,
                ) {
                    svc(
                        it,
                    ).placeFiatBid(auctionId = ltr.toString(), maxBidMinor = 2000)
                }.shouldBeInstanceOf<AuctionCurrencyMismatchException>()
            }
        }

        test(
            "who may open the payment details: only the winner and the seller of an AWARDED Euro auction -- everybody else gets the same NotFound",
        ) {
            val seller = fx.member(label = "seller")
            val winner = fx.member(label = "winner")
            val loser = fx.member(label = "loser")
            val otherSeller = fx.member(label = "otherSeller")
            val stranger = fx.member(label = "stranger")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                bid(winner, created.id, 3000)
                bid(loser, created.id, 2000)
                // still open: nobody, not even the seller or the leader
                val openAnswers =
                    listOf(seller, winner, loser, stranger).map { m ->
                        refused(m) { svc(it).getAuctionPaymentDetails(created.id) }
                    }
                openAnswers.forEach { it.shouldBeInstanceOf<NotFoundException>() }
                endNow(created.id)
                call(member = stranger) { svc(it).getAuction(created.id) }
                val answers =
                    listOf(loser, stranger, otherSeller).map { m -> refused(m) { svc(it).getAuctionPaymentDetails(created.id) } }
                answers.forEach { it.shouldBeInstanceOf<NotFoundException>() }
                val missing = refused(stranger) { svc(it).getAuctionPaymentDetails(Uuid.random().toString()) }
                val garbage = refused(stranger) { svc(it).getAuctionPaymentDetails("not-a-uuid") }
                val ltrAuction = fx.auction(seller = seller)
                val ltr = refused(seller) { svc(it).getAuctionPaymentDetails(ltrAuction.toString()) }
                (answers + openAnswers + missing + garbage + ltr).map { it.message }.toSet().size shouldBe 1
                (answers + missing + ltr).forEach { e -> (e.message ?: "").shouldNotContain(created.id) }
                call(member = winner) { svc(it).getAuctionPaymentDetails(created.id) }.accountHolder shouldBe HOLDER
                call(member = seller) { svc(it).getAuctionPaymentDetails(created.id) }.accountHolder shouldBe HOLDER
            }
        }

        test(
            "the confirmations: the wrong party gets Forbidden, a stranger NotFound, an unawarded auction Conflict, a second one AlreadyConfirmed",
        ) {
            val seller = fx.member(label = "seller")
            val winner = fx.member(label = "winner")
            val stranger = fx.member(label = "stranger")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                refused(seller) { svc(it).confirmPaymentReceived(created.id) }.shouldBeInstanceOf<ConflictException>()
                bid(winner, created.id, 3000)
                endNow(created.id)
                refused(winner) { svc(it).confirmPaymentReceived(created.id) }.shouldBeInstanceOf<ForbiddenException>()
                refused(seller) { svc(it).confirmDeliveryReceived(created.id) }.shouldBeInstanceOf<ForbiddenException>()
                refused(stranger) { svc(it).confirmPaymentReceived(created.id) }.shouldBeInstanceOf<NotFoundException>()
                refused(stranger) { svc(it).confirmDeliveryReceived(created.id) }.shouldBeInstanceOf<NotFoundException>()
                call(member = seller) { svc(it).confirmPaymentReceived(created.id) }.status shouldBe AuctionStatus.PAID
                refused(seller) { svc(it).confirmPaymentReceived(created.id) }.shouldBeInstanceOf<AuctionAlreadyConfirmedException>()
                // an LTR auction has no confirmations
                refused(seller) { svc(it).confirmPaymentReceived(fx.auction(seller = seller).toString()) }
                    .shouldBeInstanceOf<AuctionCurrencyMismatchException>()
            }
        }

        test("the payment details are replaceable while OPEN and frozen from the award on; the currency is locked by the first bid") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val other = fx.member(label = "other")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                refused(
                    other,
                ) {
                    svc(
                        it,
                    ).updateAuctionPaymentDetails(auctionId = created.id, input = details())
                }.shouldBeInstanceOf<ForbiddenException>()
                refused(seller) { svc(it).updateAuctionPaymentDetails(auctionId = created.id, input = details(iban = "DE00")) }
                    .shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
                call(member = seller) {
                    svc(it).updateAuctionPaymentDetails(auctionId = created.id, input = details(holder = "Neuer Inhaber"))
                }.status shouldBe
                    AuctionStatus.OPEN
                // the terms may change before the first bid ...
                call(member = seller) {
                    svc(
                        it,
                    ).updateAuctionTerms(
                        auctionId = created.id,
                        input = UpdateAuctionTermsInput(currency = "EUR", startingPriceMinor = 1200, buyNowPriceMinor = 4000),
                    )
                }.startingPriceMinor shouldBe 1200
                bid(b1, created.id, 2000)
                // ... but not after it
                refused(seller) {
                    svc(
                        it,
                    ).updateAuctionTerms(
                        auctionId = created.id,
                        input = UpdateAuctionTermsInput(currency = "EUR", startingPriceMinor = 1300),
                    )
                }.shouldBeInstanceOf<AuctionCurrencyLockedException>()
                endNow(created.id)
                refused(
                    seller,
                ) { svc(it).updateAuctionPaymentDetails(auctionId = created.id, input = details()) }.shouldBeInstanceOf<ConflictException>()
                call(member = b1) { svc(it).getAuctionPaymentDetails(created.id) }.accountHolder shouldBe "Neuer Inhaber"
            }
        }

        test(
            "currency change before the first bid: EUR to LTR books the listing fee, LTR to EUR needs the acknowledgment and the details",
        ) {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                // no LTR balance: the LTR fee cannot be paid
                refused(seller) {
                    svc(
                        it,
                    ).updateAuctionTerms(
                        auctionId = created.id,
                        input = UpdateAuctionTermsInput(currency = "LTR", startingBidLtr = BigDecimal("5.00")),
                    )
                }.shouldBeInstanceOf<ConflictException>()
                mint(seller, "100.00")
                val asLtr =
                    call(member = seller) {
                        svc(
                            it,
                        ).updateAuctionTerms(
                            auctionId = created.id,
                            input = UpdateAuctionTermsInput(currency = "LTR", startingBidLtr = BigDecimal("5.00")),
                        )
                    }
                asLtr.currency shouldBe "LTR"
                asLtr.startingPriceMinor shouldBe null
                asLtr.listingFeeLtr.compareTo(BigDecimal("0.01")) shouldBe 0
                transaction {
                    AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq Uuid.parse(created.id) }.count()
                } shouldBe 0
                // back to EUR without the details: refused
                val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }
                refused(seller) {
                    svc(it).updateAuctionTerms(
                        auctionId = created.id,
                        input =
                            UpdateAuctionTermsInput(
                                currency = "EUR",
                                startingPriceMinor = 1000,
                                sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                                sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                            ),
                    )
                }.shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
                call(member = seller) {
                    svc(it).updateAuctionTerms(
                        auctionId = created.id,
                        input =
                            UpdateAuctionTermsInput(
                                currency = "EUR",
                                startingPriceMinor = 1000,
                                paymentDetails = details(),
                                sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                                sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                            ),
                    )
                }.currency shouldBe "EUR"
            }
        }

        test(
            "an LTR auction is unchanged: listing fee, hold and sale are still booked, a Fiat call on it is refused, its DTO has no Fiat fields",
        ) {
            val seller = fx.member(label = "seller")
            val bidder = fx.member(label = "bidder")
            galleryTestApplication(fixtures = fx) {
                mint(seller, "10.00")
                mint(bidder, "50.00")
                val ltr =
                    call(member = seller) {
                        svc(
                            it,
                        ).createListing(
                            CreateAuctionListingInput(
                                title = "LTR-Ding",
                                description = "d",
                                startingBidLtr = BigDecimal("5.00"),
                                buyNowPriceLtr = null,
                                durationHours = 24,
                            ),
                        )
                    }
                fx.auctionIds += Uuid.parse(ltr.id)
                ltr.currency shouldBe "LTR"
                ltr.startingPriceMinor shouldBe null
                ltr.startingBidLtr!!.compareTo(BigDecimal("5.00")) shouldBe 0
                call(member = bidder) { svc(it).placeBid(auctionId = ltr.id, maxBidLtr = BigDecimal("20.00")) }.youAreLeader shouldBe true
                // the fee and the hold are real ledger rows of this auction
                (ledgerRowsOf(listOf(Uuid.parse(ltr.id))) >= 2) shouldBe true
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).updateAuctionPaymentDetails(
                        auctionId = ltr.id,
                        input = details(),
                    )
                }.shouldBeInstanceOf<AuctionCurrencyMismatchException>()
                refused(
                    bidder,
                ) { svc(it).placeFiatBid(auctionId = ltr.id, maxBidMinor = 2000) }.shouldBeInstanceOf<AuctionCurrencyMismatchException>()
                call(member = bidder) { svc(it).getAuction(ltr.id) }.callerIsSeller shouldBe false
            }
        }

        test("the retention poller deletes a due row although more than 500 rows that are not due exist") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                val now = DbClock.nowLocalDateTime()
                val settledRecently = LocalDateTime(now.year, now.month, now.day, 0, 0)
                val dueId = Uuid.parse("ffffffff-ffff-7fff-bfff-ffffffffffff")
                val notDue =
                    transaction {
                        fun insertAuction(
                            auctionId: Uuid,
                            auctionStatus: AuctionStatus,
                        ) {
                            AuctionTable.insert {
                                it[id] = auctionId
                                it[title] = "Retention"
                                it[description] = "d"
                                it[currency] = "EUR"
                                it[startingPriceMinor] = 1000
                                it[sellerDisclaimerVersion] = "test"
                                it[status] = auctionStatus
                                it[sellerMemberId] = seller
                                it[winnerMemberId] = null
                                it[listingFeeLtr] = BigDecimal.ZERO
                                it[createdAt] = settledRecently
                                it[endsAt] = settledRecently
                                it[settledAt] = settledRecently
                            }
                            AuctionPaymentDetailsTable.insert {
                                it[AuctionPaymentDetailsTable.auctionId] = auctionId
                                it[detailsCiphertext] = "x"
                                it[hasIban] = true
                                it[hasPaymentLink] = false
                                it[updatedAt] = settledRecently
                            }
                        }
                        val ids = List(501) { Uuid.random() }
                        ids.forEach { insertAuction(auctionId = it, auctionStatus = AuctionStatus.AWAITING_PAYMENT) }
                        insertAuction(auctionId = dueId, auctionStatus = AuctionStatus.CLOSED_NO_SALE)
                        ids
                    }
                fx.auctionIds += notDue
                fx.auctionIds += dueId
                AuctionPaymentDetailsRetentionPoller().tick()

                fun remaining(ids: List<Uuid>): Long =
                    transaction { AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId inList ids }.count() }
                remaining(listOf(dueId)) shouldBe 0
                remaining(notDue) shouldBe 501
            }
        }

        test("nothing of the payment details leaks: not into a DTO, a list, an audit entry, an exception message or the database") {
            val seller = fx.member(label = "seller")
            val winner = fx.member(label = "winner")
            val stranger = fx.member(label = "stranger")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, paymentDetails = details(link = GOOD_LINK), title = "Leak-Test")
                bid(winner, created.id, 2000)
                endNow(created.id)
                val awarded = call(member = stranger) { svc(it).getAuction(created.id) }
                call(member = seller) { svc(it).confirmPaymentReceived(created.id) }
                val secrets = listOf(GOOD_IBAN, "DE89 3704", GOOD_LINK, "pay.example.org", HOLDER, "Verkäuferin-Test")

                val json = Json { encodeDefaults = true }
                val serialized =
                    listOf(
                        json.encodeToString(AuctionDto.serializer(), created),
                        json.encodeToString(AuctionDto.serializer(), awarded),
                        json.encodeToString(
                            ListSerializer(AuctionDto.serializer()),
                            call(member = stranger) { svc(it).listAuctions(null) },
                        ),
                        json.encodeToString(ListSerializer(AuctionDto.serializer()), call(member = winner) { svc(it).listMyAuctions() }),
                        json.encodeToString(ListSerializer(AuctionDto.serializer()), call(member = seller) { svc(it).listMyAuctions() }),
                    )
                serialized.forEach { text -> secrets.forEach { secret -> text.shouldNotContain(secret) } }
                // the seller's own DTO tells only that a link exists
                call(member = seller) { svc(it).getAuction(created.id) }.hasPaymentLink shouldBe true
                call(member = stranger) { svc(it).getAuction(created.id) }.hasPaymentLink shouldBe false

                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.AUCTION) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(created.id))
                            }.map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    }
                audit.size shouldBe 3 // created, awarded, payment confirmed
                audit.forEach { text ->
                    secrets.forEach { secret -> text.shouldNotContain(secret) }
                    // only the event, the currency, the status and the time: never an amount
                    text.shouldNotContain("Minor")
                    text.shouldNotContain("amount")
                }

                val rawCipher =
                    transaction {
                        AuctionPaymentDetailsTable
                            .selectAll()
                            .where {
                                AuctionPaymentDetailsTable.auctionId eq
                                    Uuid.parse(
                                        created.id,
                                    )
                            }.single()
                    }[AuctionPaymentDetailsTable.detailsCiphertext]
                secrets.forEach { rawCipher.shouldNotContain(it) }
                rawCipher.startsWith("v1:") shouldBe true

                val bad =
                    refused(seller) {
                        svc(
                            it,
                        ).updateAuctionPaymentDetails(
                            auctionId = created.id,
                            input = details(iban = "DE89370400440532013001", holder = "Geheimer Name"),
                        )
                    }
                bad.shouldBeInstanceOf<AuctionPaymentDetailsInvalidException>()
                listOf(bad.message ?: "", bad.toString()).forEach { text ->
                    text.shouldNotContain("DE8937")
                    text.shouldNotContain("Geheimer")
                }
            }
        }

        test(
            "the sealed blob is bound to its auction: copied to another auction's row it no longer opens, and without the key it cannot be read",
        ) {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                val first = listFiat(seller, title = "erste")
                val second = listFiat(seller, title = "zweite")
                val firstId = Uuid.parse(first.id)
                val secondId = Uuid.parse(second.id)
                val store = AuctionPaymentDetailsStore(KEY)
                transaction { store.open(firstId)!!.accountHolder } shouldBe HOLDER
                val firstCipher =
                    transaction {
                        AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq firstId }.single()
                    }[
                        AuctionPaymentDetailsTable.detailsCiphertext,
                    ]
                transaction {
                    AuctionPaymentDetailsTable.update({ AuctionPaymentDetailsTable.auctionId eq secondId }) {
                        it[detailsCiphertext] =
                            firstCipher
                    }
                }
                shouldThrow<network.lapis.cloud.server.auction.PaymentDetailsUnavailableException> { transaction { store.open(secondId) } }
                shouldThrow<network.lapis.cloud.server.auction.PaymentDetailsUnavailableException> {
                    transaction { AuctionPaymentDetailsStore(null).open(firstId) }
                }
                // a different key (a restore on another instance) is just as unreadable, and the message is constant
                val e =
                    shouldThrow<network.lapis.cloud.server.auction.PaymentDetailsUnavailableException> {
                        transaction { AuctionPaymentDetailsStore(ByteArray(32) { 1 }).open(firstId) }
                    }
                (e.message ?: "").shouldNotContain(HOLDER)
            }
        }

        test(
            "the instance switch: the operator acknowledgment is mandatory to switch on, none to switch off; running auctions stay settleable",
        ) {
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val member = fx.member(label = "member")
            galleryTestApplication(fixtures = fx) {
                setFiat(false)
                refused(member) { svc(it).getAuctionFiatOperatorDisclaimer() }.shouldBeInstanceOf<ForbiddenException>()
                refused(member) { svc(it).setAuctionFiatEnabled(enabled = false, input = null) }.shouldBeInstanceOf<ForbiddenException>()
                val notice = call(member = admin) { svc(it).getAuctionFiatOperatorDisclaimer() }
                notice.version shouldBe AuctionFiatOperatorDisclaimer.VERSION
                notice.sha256 shouldBe AuctionFiatOperatorDisclaimer.SHA256
                refused(admin) { svc(it).setAuctionFiatEnabled(enabled = true, input = null) }.shouldBeInstanceOf<ConflictException>()
                refused(
                    admin,
                ) {
                    svc(
                        it,
                    ).setAuctionFiatEnabled(
                        enabled = true,
                        input =
                            AuctionComplianceAcknowledgmentInput(
                                disclaimerVersion = notice.version,
                                disclaimerSha256 = "ab".repeat(32),
                            ),
                    )
                }.shouldBeInstanceOf<ConflictException>()
                // the SELLER notice is not the operator notice
                refused(admin) {
                    svc(
                        it,
                    ).setAuctionFiatEnabled(
                        enabled = true,
                        input =
                            AuctionComplianceAcknowledgmentInput(
                                disclaimerVersion = AuctionFiatSellerDisclaimer.VERSION,
                                disclaimerSha256 = AuctionFiatSellerDisclaimer.SHA256,
                            ),
                    )
                }.shouldBeInstanceOf<ConflictException>()
                call(member = member) { svc(it).getAuctionFiatAvailability() }.fiatEnabled shouldBe false

                val on =
                    call(member = admin) {
                        svc(
                            it,
                        ).setAuctionFiatEnabled(
                            enabled = true,
                            input =
                                AuctionComplianceAcknowledgmentInput(
                                    disclaimerVersion = notice.version,
                                    disclaimerSha256 = notice.sha256,
                                ),
                        )
                    }
                on.auctionFiatEnabled shouldBe true
                on.fiatLastDisclaimerVersion shouldBe notice.version
                (on.fiatLastAcknowledgedAt != null) shouldBe true
                call(member = member) { svc(it).getAuctionFiatAvailability() }.fiatEnabled shouldBe true
                transaction {
                    AuctionFiatAcknowledgmentTable.selectAll().where { AuctionFiatAcknowledgmentTable.kind eq "OPERATOR" }.count()
                } shouldBe 1
                val switchAudit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS) and
                                    (AuditLogEntryTable.entityId eq ORGANIZATION_SETTINGS_ID) and
                                    (AuditLogEntryTable.actorMemberId eq admin)
                            }.count()
                    }
                (switchAudit >= 1) shouldBe true

                val created = listFiat(seller)
                bid(b1, created.id, 2000)
                // switched off: no new listing, but the running auction is settled and confirmed as usual
                val off = call(member = admin) { svc(it).setAuctionFiatEnabled(enabled = false, input = null) }
                off.auctionFiatEnabled shouldBe false
                val a = call(member = seller) { svc(it).getAuctionFiatAvailability() }
                a.fiatEnabled shouldBe false
                refused(seller) {
                    svc(it).createFiatListing(
                        CreateFiatAuctionListingInput(
                            title = "x",
                            description = "d",
                            currency = "EUR",
                            startingPriceMinor = 1000,
                            buyNowPriceMinor = null,
                            durationHours = 24,
                            paymentDetails = details(),
                            sellerDisclaimerVersion = a.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = a.sellerDisclaimerSha256,
                        ),
                    )
                }.shouldBeInstanceOf<AuctionFiatDisabledException>()
                call(member = b1) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 2500) }.youAreLeader shouldBe true
                endNow(created.id)
                call(member = b1) { svc(it).getAuctionPaymentDetails(created.id) }.amountMinor shouldBe 1000
                call(member = seller) { svc(it).confirmPaymentReceived(created.id) }.status shouldBe AuctionStatus.PAID
            }
        }

        test("DSGVO: the export of a seller names only that sealed details exist; the erasure deletes them") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, paymentDetails = details(link = GOOD_LINK))
                val exported =
                    transaction {
                        network.lapis.cloud.server.dsgvo.AuctionPersonalData
                            .exportMember(seller)
                    }.toString()
                exported.shouldNotContain(GOOD_IBAN)
                exported.shouldNotContain(GOOD_LINK)
                exported.shouldNotContain(HOLDER)
                exported.shouldNotContain("v1:")
                exported.contains("paymentDetailsStored") shouldBe true
                exported.contains("\"hasIban\":true") shouldBe true
                exported.contains("fiatAcknowledgments") shouldBe true
                val outcomes =
                    transaction {
                        network.lapis.cloud.server.dsgvo.AuctionPersonalData.eraseMember(
                            memberId = seller,
                            mode = network.lapis.cloud.shared.domain.ErasureMode.ANONYMIZE,
                        )
                    }
                outcomes.single { it.table == "auction_payment_details" }.rowsDeleted shouldBe 1
                transaction {
                    AuctionPaymentDetailsTable.selectAll().where { AuctionPaymentDetailsTable.auctionId eq Uuid.parse(created.id) }.count()
                } shouldBe 0
            }
        }
    })
