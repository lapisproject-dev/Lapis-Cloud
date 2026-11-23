package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.dsgvo.AuctionPersonalData
import network.lapis.cloud.server.gallery.GalleryApp
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionOfferDto
import network.lapis.cloud.shared.domain.AuctionOfferRules
import network.lapis.cloud.shared.domain.AuctionOfferStatus
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.UpdateAuctionTermsInput
import network.lapis.cloud.shared.rpc.AuctionCurrencyMismatchException
import network.lapis.cloud.shared.rpc.AuctionFiatDisabledException
import network.lapis.cloud.shared.rpc.AuctionOfferAmountInvalidException
import network.lapis.cloud.shared.rpc.AuctionOfferBlockedException
import network.lapis.cloud.shared.rpc.AuctionOfferLimitReachedException
import network.lapis.cloud.shared.rpc.AuctionOfferNotAllowedException
import network.lapis.cloud.shared.rpc.AuctionOfferNotOpenException
import network.lapis.cloud.shared.rpc.AuctionOfferOutbidException
import network.lapis.cloud.shared.rpc.AuctionOffersImmutableException
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

private val OFFER_KEY: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
private const val OFFER_IBAN = "DE89370400440532013000"

/**
 * V1.9.321 (Fiat wave b) -- the price offers of [AuctionService] end to end on H2 and PostgreSQL: the happy paths (offer -> accept, offer ->
 * counter-offer -> accept), every refusal, the expiry rules, that a stranger learns nothing (identical NOT_FOUND), that the audit trail carries no
 * amount and that the LTR ledger is never touched.
 */
abstract class AuctionOfferServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()

        fun svc(call: ApplicationCall) = AuctionService(call = call, secretEncryptionKey = OFFER_KEY)

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
                    it[AuctionTable.endsAt] =
                        LocalDateTime(2020, 1, 1, 0, 0)
                }
            }
        }

        fun expireOffer(offerId: String) {
            transaction {
                AuctionOfferTable.update({ AuctionOfferTable.id eq Uuid.parse(offerId) }) {
                    it[AuctionOfferTable.createdAt] = LocalDateTime(2019, 1, 1, 0, 0)
                    it[AuctionOfferTable.expiresAt] = LocalDateTime(2019, 1, 2, 0, 0)
                }
            }
        }

        fun setStatus(
            memberId: Uuid,
            status: MemberStatus,
        ) {
            transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[MemberTable.status] = status } }
        }

        fun offerRow(offerId: String) =
            transaction { AuctionOfferTable.selectAll().where { AuctionOfferTable.id eq Uuid.parse(offerId) }.single() }

        fun auctionRow(auctionId: String) =
            transaction { AuctionTable.selectAll().where { AuctionTable.id eq Uuid.parse(auctionId) }.single() }

        /** Direct filler rows (no service involved) to reach a limit. */
        fun insertOffers(
            auctionId: String,
            buyer: Uuid,
            count: Int,
            status: AuctionOfferStatus,
        ) {
            val now = DbClock.nowLocalDateTime()
            transaction {
                repeat(count) {
                    AuctionOfferTable.insert {
                        it[AuctionOfferTable.id] = Uuid.random()
                        it[AuctionOfferTable.auctionId] = Uuid.parse(auctionId)
                        it[AuctionOfferTable.buyerMemberId] = buyer
                        it[AuctionOfferTable.fromSeller] = false
                        it[AuctionOfferTable.parentOfferId] = null
                        it[AuctionOfferTable.amountMinor] = 2000
                        it[AuctionOfferTable.status] = status
                        it[AuctionOfferTable.createdAt] = now
                        it[AuctionOfferTable.expiresAt] = now
                        it[AuctionOfferTable.decidedAt] = now
                    }
                }
            }
        }

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

        suspend fun GalleryApp.listFiat(
            seller: Uuid,
            start: Long = 1000,
            buyNow: Long? = 9000,
            offers: Boolean = true,
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
                            title = "Fahrrad mit Preisvorschlag",
                            description = "Beschreibung",
                            currency = "EUR",
                            startingPriceMinor = start,
                            buyNowPriceMinor = buyNow,
                            durationHours = 24,
                            paymentDetails =
                                AuctionPaymentDetailsInput(
                                    accountHolder = "Sabine Verkäufer",
                                    iban = OFFER_IBAN,
                                    paymentLink = null,
                                ),
                            sellerDisclaimerVersion = availability.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = availability.sellerDisclaimerSha256,
                            offersEnabled = offers,
                        ),
                    )
                }
            fx.auctionIds += Uuid.parse(created.id)
            return created
        }

        suspend fun GalleryApp.offer(
            buyer: Uuid,
            auctionId: String,
            minor: Long,
        ): AuctionOfferDto = call(member = buyer) { svc(it).makeOffer(auctionId = auctionId, amountMinor = minor) }

        suspend fun <T> GalleryApp.refused(
            member: Uuid,
            op: suspend (ApplicationCall) -> T,
        ): Throwable = attempt(member = member, op = op).exceptionOrNull() ?: error("expected a refusal")

        fun mint(memberId: Uuid) {
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[LtrLedgerEntryTable.memberId] = memberId
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = BigDecimal("5.00")
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Offer test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        fun ledgerRowsOf(members: List<Uuid>): Long =
            transaction { LtrLedgerEntryTable.selectAll().where { LtrLedgerEntryTable.memberId inList members }.count() }

        test(
            "offer -> accept: the auction is awarded at the offered price, both parties open the payment details, the audit carries no amount",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val other = fx.member(label = "other")
            galleryTestApplication(fixtures = fx) {
                val ledgerBefore = ledgerRowsOf(listOf(seller, buyer, other))
                val created = listFiat(seller)
                created.offersEnabled shouldBe true
                created.openOfferCountForSeller shouldBe 0
                val made = offer(buyer, created.id, 4321)
                made.status shouldBe AuctionOfferStatus.OFFERED
                made.fromSeller shouldBe false
                made.callerCanWithdraw shouldBe true
                made.callerCanAccept shouldBe false
                made.buyerDisplayName shouldBe null
                // the seller sees the count, nobody else does
                call(member = seller) { svc(it).getAuction(created.id) }.openOfferCountForSeller shouldBe 1
                call(member = other) { svc(it).getAuction(created.id) }.openOfferCountForSeller shouldBe null
                call(member = buyer) { svc(it).getAuction(created.id) }.callerHasOpenOffer shouldBe true
                call(member = other) { svc(it).getAuction(created.id) }.callerHasOpenOffer shouldBe false

                val list = call(member = seller) { svc(it).listOffersForAuction(created.id) }
                list.single().buyerDisplayName shouldBe "Gallery-Test buyer"
                list.single().callerCanAccept shouldBe true
                list.single().callerCanCounter shouldBe true

                val awarded = call(member = seller) { svc(it).acceptOffer(made.id) }
                awarded.status shouldBe AuctionStatus.AWAITING_PAYMENT
                awarded.winnerMemberId shouldBe buyer.toString()
                awarded.finalPriceMinor shouldBe 4321
                offerRow(made.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.ACCEPTED
                call(member = buyer) { svc(it).getAuctionPaymentDetails(created.id) }.amountMinor shouldBe 4321
                call(member = seller) { svc(it).getAuctionPaymentDetails(created.id) }.amountMinor shouldBe 4321
                refused(other) { svc(it).getAuctionPaymentDetails(created.id) }.shouldBeInstanceOf<NotFoundException>()
                // a second acceptance (by the same or by a stale click) is refused
                refused(seller) { svc(it).acceptOffer(made.id) }.shouldBeInstanceOf<AuctionOfferNotOpenException>()
                call(member = seller) { svc(it).confirmPaymentReceived(created.id) }.status shouldBe AuctionStatus.PAID
                call(member = buyer) { svc(it).confirmDeliveryReceived(created.id) }.status shouldBe AuctionStatus.COMPLETED

                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.AUCTION) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(created.id))
                            }.map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    }
                audit.any { it.contains("AUCTION_OFFER_MADE") } shouldBe true
                audit.any { it.contains("AUCTION_OFFER_ACCEPTED") } shouldBe true
                audit.forEach { text ->
                    Regex("""[:"]4321[,}"]""").containsMatchIn(text) shouldBe false
                    text.shouldNotContain("Minor")
                    text.shouldNotContain("amount")
                    text.shouldNotContain(OFFER_IBAN)
                }
                ledgerRowsOf(listOf(seller, buyer, other)) shouldBe ledgerBefore
            }
        }

        test(
            "offer -> counter-offer -> the buyer accepts the counter-offer; a counter-offer cannot be countered, a second counter on the same proposal is refused",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                val made = offer(buyer, created.id, 3000)
                refused(
                    seller,
                ) { svc(it).counterOffer(offerId = made.id, amountMinor = 3000) }.shouldBeInstanceOf<AuctionOfferAmountInvalidException>()
                refused(
                    seller,
                ) { svc(it).counterOffer(offerId = made.id, amountMinor = 9000) }.shouldBeInstanceOf<AuctionOfferAmountInvalidException>()
                refused(buyer) { svc(it).counterOffer(offerId = made.id, amountMinor = 5000) }.shouldBeInstanceOf<NotFoundException>()
                val counter = call(member = seller) { svc(it).counterOffer(offerId = made.id, amountMinor = 5000) }
                counter.fromSeller shouldBe true
                counter.parentOfferId shouldBe made.id
                counter.callerCanAccept shouldBe false
                counter.callerCanWithdraw shouldBe true
                offerRow(made.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.COUNTERED
                // the proposal is answered: neither a second counter nor a decision on the old row
                refused(
                    seller,
                ) { svc(it).counterOffer(offerId = made.id, amountMinor = 6000) }.shouldBeInstanceOf<AuctionOfferNotOpenException>()
                refused(
                    seller,
                ) { svc(it).counterOffer(offerId = counter.id, amountMinor = 6000) }.shouldBeInstanceOf<AuctionOfferNotAllowedException>()
                // the seller cannot accept his own counter-offer, a stranger sees nothing
                refused(seller) { svc(it).acceptOffer(counter.id) }.shouldBeInstanceOf<NotFoundException>()
                // the buyer sees the counter-offer among his offers and can accept or decline it
                val mine = call(member = buyer) { svc(it).listMyOffers() }
                mine.first { it.id == counter.id }.callerCanAccept shouldBe true
                mine.first { it.id == counter.id }.callerCanDecline shouldBe true
                call(member = buyer) { svc(it).getAuction(created.id) }.callerHasOpenOffer shouldBe true

                val awarded = call(member = buyer) { svc(it).acceptOffer(counter.id) }
                awarded.status shouldBe AuctionStatus.AWAITING_PAYMENT
                awarded.finalPriceMinor shouldBe 5000
                awarded.winnerMemberId shouldBe buyer.toString()
            }
        }

        test(
            "a declined counter-offer does not count towards the block; three declined proposals block the buyer; a withdrawn offer frees the slot",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                val first = offer(buyer, created.id, 3000)
                val counter = call(member = seller) { svc(it).counterOffer(offerId = first.id, amountMinor = 5000) }
                call(member = buyer) { svc(it).declineOffer(counter.id) }.status shouldBe AuctionOfferStatus.DECLINED
                // only ONE open negotiation at a time
                val second = offer(buyer, created.id, 3100)
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3200) }.shouldBeInstanceOf<AuctionOfferLimitReachedException>()
                call(member = buyer) { svc(it).withdrawOffer(second.id) }.status shouldBe AuctionOfferStatus.WITHDRAWN
                refused(seller) { svc(it).withdrawOffer(second.id) }.shouldBeInstanceOf<NotFoundException>()
                val third = offer(buyer, created.id, 3300)
                call(member = seller) { svc(it).declineOffer(third.id) }.status shouldBe AuctionOfferStatus.DECLINED
                val fourth = offer(buyer, created.id, 3400)
                call(member = seller) { svc(it).declineOffer(fourth.id) }
                call(member = buyer) { svc(it).getAuction(created.id) }.callerOfferBlocked shouldBe false
                val fifth = offer(buyer, created.id, 3500)
                call(member = seller) { svc(it).declineOffer(fifth.id) }
                // three declined PROPOSALS (the declined counter-offer did not count)
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3600) }.shouldBeInstanceOf<AuctionOfferBlockedException>()
                call(member = buyer) { svc(it).getAuction(created.id) }.callerOfferBlocked shouldBe true
                // a different buyer is not affected
                val other = fx.member(label = "other")
                offer(other, created.id, 3600).status shouldBe AuctionOfferStatus.OFFERED
            }
        }

        test("the 21st live offer of an auction is refused; withdrawn rows do not count towards the cap") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val filler = fx.member(label = "filler")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                insertOffers(created.id, filler, count = 20, status = AuctionOfferStatus.WITHDRAWN)
                // 20 withdrawn rows are no budget use: a different buyer can still offer
                val ok = offer(buyer, created.id, 3000)
                call(member = buyer) { svc(it).withdrawOffer(ok.id) }
                insertOffers(created.id, filler, count = 20, status = AuctionOfferStatus.EXPIRED)
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3100) }.shouldBeInstanceOf<AuctionOfferLimitReachedException>()
                // the per-auction aggregate is no longer shown to a stranger
                call(member = buyer) { svc(it).getAuction(created.id) }.callerOfferBlocked shouldBe false
            }
        }

        test("a make+withdraw loop is stopped by the per-buyer cap and cannot lock out others or freeze the terms") {
            val seller = fx.member(label = "seller")
            val griefer = fx.member(label = "griefer")
            val other = fx.member(label = "other")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                repeat(AuctionOfferRules.MAX_PROPOSALS_PER_BUYER_PER_AUCTION) { i ->
                    val made = offer(griefer, created.id, 3000L + i)
                    call(member = griefer) { svc(it).withdrawOffer(made.id) }.status shouldBe AuctionOfferStatus.WITHDRAWN
                }
                refused(
                    griefer,
                ) { svc(it).makeOffer(auctionId = created.id, amountMinor = 4000) }.shouldBeInstanceOf<AuctionOfferBlockedException>()
                call(member = griefer) { svc(it).getAuction(created.id) }.callerOfferBlocked shouldBe true
                // everybody else is unaffected, the offer button stays
                call(member = other) { svc(it).getAuction(created.id) }.callerOfferBlocked shouldBe false
                offer(other, created.id, 3000).status shouldBe AuctionOfferStatus.OFFERED
                // withdrawn rows alone do not freeze the terms (checked on a second auction without live offers)
                val second = listFiat(seller)
                val made = offer(griefer, second.id, 3000)
                call(member = griefer) { svc(it).withdrawOffer(made.id) }
                call(member = seller) {
                    svc(it).updateAuctionTerms(
                        auctionId = second.id,
                        input =
                            UpdateAuctionTermsInput(
                                currency = "EUR",
                                startingPriceMinor = 1000,
                                buyNowPriceMinor = 9500,
                                startingBidLtr = BigDecimal("1.00"),
                                buyNowPriceLtr = null,
                                paymentDetails = null,
                                sellerDisclaimerVersion = null,
                                sellerDisclaimerSha256 = null,
                            ),
                    )
                }.buyNowPriceMinor shouldBe 9500
            }
        }

        test(
            "amount rules: range, the Sofortkauf price, the starting price, the visible price plus one euro; an offer never extends the auction",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val bidder = fx.member(label = "bidder")
            val bidder2 = fx.member(label = "bidder2")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 1000, buyNow = 9000)
                listOf(0L, -5L, 99L, 10_000_001L, 9000L, 9500L, 999L).forEach { bad ->
                    refused(
                        buyer,
                    ) {
                        svc(
                            it,
                        ).makeOffer(auctionId = created.id, amountMinor = bad)
                    }.shouldBeInstanceOf<AuctionOfferAmountInvalidException>()
                }
                call(member = bidder) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 6000) }
                call(member = bidder2) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 2000) }
                // the visible price is 2100: an offer must be at least the visible price plus one euro
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = created.id, amountMinor = 2100) }.shouldBeInstanceOf<AuctionOfferAmountInvalidException>()
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = created.id, amountMinor = 2199) }.shouldBeInstanceOf<AuctionOfferAmountInvalidException>()
                val endsBefore = auctionRow(created.id)[AuctionTable.endsAt]
                val made = offer(buyer, created.id, 2200)
                auctionRow(created.id)[AuctionTable.endsAt] shouldBe endsBefore
                (made.expiresAt <= endsBefore) shouldBe true
                // the offer is overtaken by a higher bid: the seller cannot accept it any more
                call(member = bidder2) { svc(it).placeFiatBid(auctionId = created.id, maxBidMinor = 4000) }
                call(member = seller) { svc(it).listOffersForAuction(created.id) }.single().belowCurrentPrice shouldBe true
                refused(seller) { svc(it).acceptOffer(made.id) }.shouldBeInstanceOf<AuctionOfferOutbidException>()
                // a counter-offer below the visible price is refused
                refused(
                    seller,
                ) { svc(it).counterOffer(offerId = made.id, amountMinor = 4000) }.shouldBeInstanceOf<AuctionOfferAmountInvalidException>()
                val counter = call(member = seller) { svc(it).counterOffer(offerId = made.id, amountMinor = 4100) }
                counter.amountMinor shouldBe 4100
            }
        }

        test("refusals: LTR, offers off, no Sofortkauf price, the seller himself, instance switch off") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                val noOffers = listFiat(seller, offers = false)
                noOffers.offersEnabled shouldBe false
                noOffers.openOfferCountForSeller shouldBe null
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = noOffers.id, amountMinor = 3000) }.shouldBeInstanceOf<AuctionOfferNotAllowedException>()
                // offers need a Sofortkauf price (service, and the CHECK as the second layer)
                val create =
                    CreateFiatAuctionListingInput(
                        title = "t",
                        description = "d",
                        currency = "EUR",
                        startingPriceMinor = 1000,
                        buyNowPriceMinor = null,
                        durationHours = 24,
                        paymentDetails =
                            AuctionPaymentDetailsInput(
                                accountHolder = "Sabine Verkäufer",
                                iban = OFFER_IBAN,
                                paymentLink = null,
                            ),
                        sellerDisclaimerVersion = "x",
                        sellerDisclaimerSha256 = "y",
                        offersEnabled = true,
                    )
                refused(seller) { svc(it).createFiatListing(create) }.shouldBeInstanceOf<AuctionOfferNotAllowedException>()

                mint(seller)
                val ltr =
                    call(member = seller) {
                        svc(it).createListing(
                            CreateAuctionListingInput(
                                title = "LTR",
                                description = "d",
                                startingBidLtr = BigDecimal("1.00"),
                                buyNowPriceLtr = null,
                                durationHours = 24,
                            ),
                        )
                    }.also { fx.auctionIds += Uuid.parse(it.id) }
                ltr.offersEnabled shouldBe false
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = ltr.id, amountMinor = 3000) }.shouldBeInstanceOf<AuctionCurrencyMismatchException>()

                val withOffers = listFiat(seller)
                refused(
                    seller,
                ) { svc(it).makeOffer(auctionId = withOffers.id, amountMinor = 3000) }.shouldBeInstanceOf<AuctionOfferNotAllowedException>()
                // switched off: no new offer, but a running negotiation can be decided
                val made = offer(buyer, withOffers.id, 3000)
                setFiat(false)
                refused(
                    buyer,
                ) { svc(it).makeOffer(auctionId = withOffers.id, amountMinor = 3100) }.shouldBeInstanceOf<AuctionFiatDisabledException>()
                call(member = seller) { svc(it).declineOffer(made.id) }.status shouldBe AuctionOfferStatus.DECLINED
            }
        }

        test(
            "expiry: a lapsed offer reads as EXPIRED without any write and cannot be accepted; the end of the auction expires every open offer",
        ) {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                val made = offer(b1, created.id, 3000)
                (made.expiresAt <= auctionRow(created.id)[AuctionTable.endsAt]) shouldBe true
                expireOffer(made.id)
                // reading writes nothing: the row is still OFFERED, the DTO says EXPIRED
                val seen = call(member = b1) { svc(it).listMyOffers() }.single()
                seen.status shouldBe AuctionOfferStatus.EXPIRED
                seen.endedByAuction shouldBe false
                seen.callerCanWithdraw shouldBe false
                offerRow(made.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.OFFERED
                call(member = seller) { svc(it).getAuction(created.id) }.openOfferCountForSeller shouldBe 0
                refused(seller) { svc(it).acceptOffer(made.id) }.shouldBeInstanceOf<AuctionOfferNotOpenException>()
                // a mutation persists it
                offerRow(made.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                // a new offer is possible again
                val again = offer(b1, created.id, 3100)
                val other = offer(b2, created.id, 3200)
                // the end of the auction by time
                endNow(created.id)
                call(member = seller) { svc(it).getAuction(created.id) }
                offerRow(again.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                offerRow(other.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                val ended = call(member = b2) { svc(it).listMyOffers() }.single()
                ended.status shouldBe AuctionOfferStatus.EXPIRED
                ended.endedByAuction shouldBe true
                refused(seller) { svc(it).acceptOffer(other.id) }.shouldBeInstanceOf<AuctionOfferNotOpenException>()
            }
        }

        test("a Sofortkauf and an accepted offer end the auction: every OTHER open offer expires with endedByAuction") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            val b3 = fx.member(label = "b3")
            galleryTestApplication(fixtures = fx) {
                val first = listFiat(seller)
                val o1 = offer(b1, first.id, 3000)
                call(member = b2) { svc(it).buyNow(first.id) }.status shouldBe AuctionStatus.AWAITING_PAYMENT
                offerRow(o1.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                call(member = b1) { svc(it).listMyOffers() }.single().endedByAuction shouldBe true

                val second = listFiat(seller)
                val p1 = offer(b1, second.id, 3000)
                val p2 = offer(b2, second.id, 3500)
                call(member = seller) { svc(it).acceptOffer(p2.id) }.winnerMemberId shouldBe b2.toString()
                offerRow(p1.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                offerRow(p2.id)[AuctionOfferTable.status] shouldBe AuctionOfferStatus.ACCEPTED
                refused(
                    b3,
                ) { svc(it).makeOffer(auctionId = second.id, amountMinor = 3600) }.shouldBeInstanceOf<AuctionOfferNotAllowedException>()
            }
        }

        test("a stranger, a random id and a malformed id are indistinguishable: the same NotFoundException message everywhere") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val stranger = fx.member(label = "stranger")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                val made = offer(buyer, created.id, 3000)
                val random = Uuid.random().toString()

                suspend fun messages(op: suspend (AuctionService, String) -> Any?): Set<String?> {
                    val real = refused(stranger) { op(svc(it), made.id) }
                    val fake = refused(stranger) { op(svc(it), random) }
                    val bad = refused(stranger) { op(svc(it), "not-a-uuid") }
                    listOf(real, fake, bad).forEach { it.shouldBeInstanceOf<NotFoundException>() }
                    return setOf(real.message, fake.message, bad.message)
                }
                messages { s, id -> s.acceptOffer(id) }.size shouldBe 1
                messages { s, id -> s.declineOffer(id) }.size shouldBe 1
                messages { s, id -> s.withdrawOffer(id) }.size shouldBe 1
                messages { s, id -> s.counterOffer(offerId = id, amountMinor = 5000) }.size shouldBe 1
                // the parties themselves: the buyer cannot accept his own proposal, the seller cannot withdraw the buyer's proposal
                refused(buyer) { svc(it).acceptOffer(made.id) }.message shouldBe refused(stranger) { svc(it).acceptOffer(random) }.message
                refused(seller) { svc(it).withdrawOffer(made.id) }.shouldBeInstanceOf<NotFoundException>()
                // the lists: a stranger and a buyer cannot read the offers of an auction, a stranger's own list is empty
                val listing = setOf(created.id, random, "not-a-uuid").map { id -> refused(stranger) { svc(it).listOffersForAuction(id) } }
                listing.forEach { it.shouldBeInstanceOf<NotFoundException>() }
                listing.map { it.message }.toSet().size shouldBe 1
                refused(buyer) { svc(it).listOffersForAuction(created.id) }.shouldBeInstanceOf<NotFoundException>()
                call(member = stranger) { svc(it).listMyOffers() }.size shouldBe 0
                call(member = buyer) { svc(it).listMyOffers() }.single().buyerDisplayName shouldBe null
                // the stranger's auction DTO carries no offer figure at all
                val dto = call(member = stranger) { svc(it).getAuction(created.id) }
                dto.openOfferCountForSeller shouldBe null
                dto.callerHasOpenOffer shouldBe false
                dto.callerOfferBlocked shouldBe false
            }
        }

        test(
            "terms are frozen once offers exist or when they would break the offer rules; a deactivated buyer cannot win; deactivated callers are refused",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)

                fun terms(
                    currency: String = "EUR",
                    buyNow: Long? = 9500,
                ) = UpdateAuctionTermsInput(
                    currency = currency,
                    startingPriceMinor = 1000,
                    buyNowPriceMinor = buyNow,
                    startingBidLtr = BigDecimal("1.00"),
                    buyNowPriceLtr = null,
                    paymentDetails = null,
                    sellerDisclaimerVersion = null,
                    sellerDisclaimerSha256 = null,
                )
                // without offers the prices may change but never into a shape the CHECK would refuse
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).updateAuctionTerms(
                        auctionId = created.id,
                        input = terms(buyNow = null),
                    )
                }.shouldBeInstanceOf<AuctionOffersImmutableException>()
                refused(
                    seller,
                ) {
                    svc(it).updateAuctionTerms(auctionId = created.id, input = terms(currency = "LTR"))
                }.shouldBeInstanceOf<AuctionOffersImmutableException>()
                call(member = seller) { svc(it).updateAuctionTerms(auctionId = created.id, input = terms()) }.buyNowPriceMinor shouldBe 9500
                val made = offer(buyer, created.id, 3000)
                refused(seller) {
                    svc(it).updateAuctionTerms(auctionId = created.id, input = terms())
                }.shouldBeInstanceOf<AuctionOffersImmutableException>()

                // a buyer who left meanwhile cannot win (answered like a closed offer)
                setStatus(buyer, MemberStatus.WITHDRAWN)
                refused(seller) { svc(it).acceptOffer(made.id) }.shouldBeInstanceOf<AuctionOfferNotOpenException>()
                refused(buyer) { svc(it).makeOffer(auctionId = created.id, amountMinor = 3100) }.shouldBeInstanceOf<ForbiddenException>()
                refused(buyer) { svc(it).listMyOffers() }.shouldBeInstanceOf<ForbiddenException>()
                refused(buyer) { svc(it).withdrawOffer(made.id) }.shouldBeInstanceOf<ForbiddenException>()
                setStatus(buyer, MemberStatus.ACTIVE)
                call(member = seller) { svc(it).acceptOffer(made.id) }.winnerMemberId shouldBe buyer.toString()
            }
        }

        test("getAuctionPaymentDetails refuses a deactivated party like confirmFiat does; the active parties keep access") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, offers = false)
                call(member = buyer) { svc(it).buyNow(created.id) }
                call(member = buyer) { svc(it).getAuctionPaymentDetails(created.id) }.currency shouldBe "EUR"
                setStatus(buyer, MemberStatus.WITHDRAWN)
                refused(buyer) { svc(it).getAuctionPaymentDetails(created.id) }.shouldBeInstanceOf<ForbiddenException>()
                setStatus(seller, MemberStatus.WITHDRAWN)
                refused(seller) { svc(it).getAuctionPaymentDetails(created.id) }.shouldBeInstanceOf<ForbiddenException>()
                setStatus(buyer, MemberStatus.ACTIVE)
                setStatus(seller, MemberStatus.ACTIVE)
                call(member = seller) { svc(it).getAuctionPaymentDetails(created.id) }.currency shouldBe "EUR"
            }
        }

        test("auction end: a lapsed offer keeps its own expiry as decided_at, a still-valid one gets the end time") {
            val seller = fx.member(label = "seller")
            val b1 = fx.member(label = "b1")
            val b2 = fx.member(label = "b2")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                val lapsed = offer(b1, created.id, 3000)
                val valid = offer(b2, created.id, 3500)
                expireOffer(lapsed.id)
                val lapsedExpiry = offerRow(lapsed.id)[AuctionOfferTable.expiresAt]
                val before = DbClock.nowLocalDateTime()

                // the end by TIME (not buyNow, which expires the lapsed row first via lockAndCloseIfDue)
                endNow(created.id)
                call(member = seller) { svc(it).getAuction(created.id) }

                val lapsedRow = offerRow(lapsed.id)
                lapsedRow[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                lapsedRow[AuctionOfferTable.decidedAt] shouldBe lapsedExpiry
                val validRow = offerRow(valid.id)
                validRow[AuctionOfferTable.status] shouldBe AuctionOfferStatus.EXPIRED
                (validRow[AuctionOfferTable.decidedAt]!! >= before) shouldBe true
                call(member = b1) { svc(it).listMyOffers() }.single().endedByAuction shouldBe false
                call(member = b2) { svc(it).listMyOffers() }.single().endedByAuction shouldBe true
            }
        }

        test(
            "DSGVO: erasing a buyer keeps awarded negotiation chains (incl. the parent of an accepted counter-offer) and deletes the rest",
        ) {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            galleryTestApplication(fixtures = fx) {
                val declined = listFiat(seller)
                val countered = listFiat(seller)
                val acceptedDirectly = listFiat(seller)
                val openOne = listFiat(seller)
                val now = DbClock.nowLocalDateTime()
                val parentId = Uuid.random()
                val counterId = Uuid.random()

                fun row(
                    id: Uuid,
                    auctionId: String,
                    fromSeller: Boolean,
                    parent: Uuid?,
                    status: AuctionOfferStatus,
                ) = transaction {
                    AuctionOfferTable.insert {
                        it[AuctionOfferTable.id] = id
                        it[AuctionOfferTable.auctionId] = Uuid.parse(auctionId)
                        it[AuctionOfferTable.buyerMemberId] = buyer
                        it[AuctionOfferTable.fromSeller] = fromSeller
                        it[AuctionOfferTable.parentOfferId] = parent
                        it[AuctionOfferTable.amountMinor] = 2500
                        it[AuctionOfferTable.status] = status
                        it[AuctionOfferTable.createdAt] = now
                        it[AuctionOfferTable.expiresAt] = now
                        it[AuctionOfferTable.decidedAt] = if (status == AuctionOfferStatus.OFFERED) null else now
                    }
                }
                row(Uuid.random(), declined.id, false, null, AuctionOfferStatus.DECLINED)
                row(parentId, countered.id, false, null, AuctionOfferStatus.COUNTERED)
                row(counterId, countered.id, true, parentId, AuctionOfferStatus.ACCEPTED)
                val acceptedId = Uuid.random()
                row(acceptedId, acceptedDirectly.id, false, null, AuctionOfferStatus.ACCEPTED)
                row(Uuid.random(), openOne.id, false, null, AuctionOfferStatus.OFFERED)

                // Export: the buyer sees all five rows, and so does the seller of the four auctions.
                transaction { AuctionPersonalData.exportMember(buyer) }
                    .jsonObject
                    .getValue("priceOffers")
                    .jsonArray
                    .size shouldBe 5
                val sellerOffers = transaction { AuctionPersonalData.exportMember(seller) }.jsonObject.getValue("priceOffers").jsonArray
                sellerOffers.size shouldBe 5
                sellerOffers
                    .map {
                        it.jsonObject
                            .getValue("status")
                            .jsonPrimitive.content
                    }.toSet() shouldBe
                    setOf("DECLINED", "COUNTERED", "ACCEPTED", "OFFERED")

                val outcome =
                    transaction { AuctionPersonalData.eraseMember(memberId = buyer, mode = ErasureMode.ANONYMIZE) }
                        .single { it.table == "auction_offer" }
                outcome.rowsDeleted shouldBe 2
                outcome.rowsRetained shouldBe 3
                val left =
                    transaction {
                        AuctionOfferTable
                            .selectAll()
                            .where {
                                AuctionOfferTable.auctionId inList fx.auctionIds
                            }.map { it[AuctionOfferTable.id] }
                    }
                left.toSet() shouldBe setOf(parentId, counterId, acceptedId)
            }
        }
    })
