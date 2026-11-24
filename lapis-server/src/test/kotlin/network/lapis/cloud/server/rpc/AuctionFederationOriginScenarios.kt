package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.auction.federation.AuctionFederationKey
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionOriginSignatures
import network.lapis.cloud.server.auction.federation.AuctionTeaserIds
import network.lapis.cloud.server.auction.federation.AuctionTeaserSnapshots
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedBuilder
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedCounters
import network.lapis.cloud.server.auction.federation.feed.BuiltAuctionTeaserFeed
import network.lapis.cloud.server.carpool.CarpoolOwnKeys
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.OWN_HOST
import network.lapis.cloud.server.carpool.feed.TEST_BOX
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionOfferTable
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.gallery.GalleryApp
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionHintResolutionDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.UpdateAuctionTermsInput
import network.lapis.cloud.shared.rpc.AuctionFederationEndsTooSoonException
import network.lapis.cloud.shared.rpc.AuctionFederationNotAllowedException
import network.lapis.cloud.shared.rpc.AuctionFederationShareLimitException
import network.lapis.cloud.shared.rpc.AuctionFederationTitleException
import network.lapis.cloud.shared.rpc.AuctionFederationUnavailableException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val KEY: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
private const val SELLER_NAME = "Sabine Verkäuferin-Test"
private const val IBAN = "DE89370400440532013000"

private suspend fun <T> withClockPlus(
    hours: Long,
    block: suspend () -> T,
): T {
    val original = ServerClock.source
    ServerClock.source =
        object : Clock {
            override fun now(): Instant = original.now() + hours.hours
        }
    try {
        return block()
    } finally {
        ServerClock.source = original
    }
}

/**
 * V1.9.322 (step c2) -- the ORIGIN side of the auction hint exchange end to end: the consent (every refusal, the audit entry, the DTO), every
 * trigger of a withdrawal, the throttle of the shown price, the data minimisation of the feed, the landing route and the admin switches. H2 AND
 * PostgreSQL with the same assertions.
 */
abstract class AuctionFederationOriginScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()
        val ids = AuctionTeaserIds(TEST_BOX)
        val json = Json
        val partners = mutableListOf<FeedPartner>()

        fun svc(
            call: io.ktor.server.application.ApplicationCall,
            limiter: FederationInboxRateLimiter? = null,
        ) = AuctionService(call = call, secretEncryptionKey = KEY, hintResolveLimiter = limiter)

        fun partnerSvc(call: io.ktor.server.application.ApplicationCall) =
            PartnerServerService(
                call = call,
                probeRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                auctionFeedCounters = AuctionTeaserFeedCounters(),
                auctionFeedKeyAvailable = true,
            )

        fun setFiat(enabled: Boolean) {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionFiatEnabled] = enabled
                }
            }
        }

        fun federation(
            enabled: Boolean,
            hops: Int = 2,
        ) = transaction { AuctionFederationStore.setSettings(enabled = enabled, hopLimit = hops) }

        fun sharingPartner(sharing: Boolean = true): FeedPartner {
            val p = newFeedPartner(sharing = false)
            if (sharing) transaction { PartnerServerStore.setAuctionSharing(id = p.id, enabled = true) }
            partners += p
            return p
        }

        fun build(
            partner: FeedPartner,
            now: LocalDateTime = DbClock.nowLocalDateTime(),
        ): BuiltAuctionTeaserFeed =
            transaction {
                AuctionTeaserFeedBuilder.build(
                    partner = PartnerServerStore.findPartner(partner.id)!!,
                    settings = AuctionFederationStore.settings(),
                    ids = ids,
                    originHost = OWN_HOST,
                    now = now,
                    ownKey = CarpoolOwnKeys.load(),
                )
            }

        fun served(partner: FeedPartner) {
            transaction { PartnerServerStore.recordAuctionFeedServed(id = partner.id, itemCount = 1, now = DbClock.nowLocalDateTime()) }
        }

        fun body(feed: BuiltAuctionTeaserFeed): JsonObject = json.parseToJsonElement(String(feed.body, Charsets.UTF_8)).jsonObject

        fun itemIds(feed: BuiltAuctionTeaserFeed): List<String> =
            body(
                feed,
            ).getValue("items").jsonArray.map {
                it.jsonObject
                    .getValue("teaser")
                    .jsonObject
                    .getValue("teaserId")
                    .jsonPrimitive.content
            }

        fun withdrawnIds(feed: BuiltAuctionTeaserFeed): List<String> =
            body(feed).getValue("withdrawn").jsonArray.map {
                it.jsonObject
                    .getValue("teaserId")
                    .jsonPrimitive.content
            }

        fun teaserIdOf(auctionId: String): String = ids.of(Uuid.parse(auctionId))

        fun endNow(auctionId: String) {
            transaction {
                AuctionTable.update({ AuctionTable.id eq Uuid.parse(auctionId) }) {
                    it[AuctionTable.endsAt] =
                        LocalDateTime(2020, 1, 1, 0, 0)
                }
            }
        }

        fun endsIn(
            auctionId: String,
            d: kotlin.time.Duration,
        ) {
            val at =
                (
                    DbClock.nowLocalDateTime().toInstant(
                        TimeZone.UTC,
                    ) + d
                ).let { kotlinx.datetime.Instant.fromEpochSeconds(it.epochSeconds) }
            transaction {
                AuctionTable.update({ AuctionTable.id eq Uuid.parse(auctionId) }) {
                    it[AuctionTable.endsAt] = kotlinx.datetime.LocalDateTime.parse(at.toString().removeSuffix("Z"))
                }
            }
        }

        fun auditEvents(auctionId: String): List<String> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.AUCTION) and
                            (AuditLogEntryTable.entityId eq Uuid.parse(auctionId))
                    }.map { it[AuditLogEntryTable.afterSnapshot] ?: "" }
            }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)

        beforeEach {
            fx.setAuctionEnabled(true)
            setFiat(true)
            federation(enabled = true)
            AuctionFederationKey.resetForTest()
            AuctionFederationKey.configure(ids)
        }

        fun cleanUp() {
            transaction {
                AuctionTeaserTombstoneTable.deleteAll()
                AuctionTeaserSnapshotTable.deleteAll()
                AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId inList fx.auctionIds }
                AuctionOfferTable.deleteWhere { AuctionOfferTable.auctionId inList fx.auctionIds }
                AuctionBidTable.deleteWhere { AuctionBidTable.auctionId inList fx.auctionIds }
                AuctionTable.deleteWhere { AuctionTable.id inList fx.auctionIds }
                AuctionFiatAcknowledgmentTable.deleteWhere { AuctionFiatAcknowledgmentTable.acknowledgedByMemberId inList fx.memberIds }
                LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList fx.memberIds }
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
            }
            fx.auctionIds.clear()
            partners.clear()
            setFiat(false)
            federation(enabled = false, hops = 1)
            AuctionFederationKey.resetForTest()
        }
        afterEach { cleanUp() }
        afterSpec {
            try {
                cleanUp()
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        suspend fun GalleryApp.listFiat(
            seller: Uuid,
            start: Long = 1000,
            buyNow: Long? = null,
            offers: Boolean = false,
            title: String = "Lastenrad in Euro",
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
                            description = "Geheime Beschreibung des Verkäufers",
                            currency = "EUR",
                            startingPriceMinor = start,
                            buyNowPriceMinor = buyNow,
                            durationHours = 24,
                            paymentDetails = AuctionPaymentDetailsInput(accountHolder = SELLER_NAME, iban = IBAN, paymentLink = null),
                            sellerDisclaimerVersion = availability.sellerDisclaimerVersion,
                            sellerDisclaimerSha256 = availability.sellerDisclaimerSha256,
                            offersEnabled = offers,
                        ),
                    )
                }
            fx.auctionIds += Uuid.parse(created.id)
            return created
        }

        suspend fun GalleryApp.consent(
            seller: Uuid,
            auctionId: String,
            on: Boolean = true,
        ): AuctionDto = call(member = seller) { svc(it).setAuctionFederationOptIn(auctionId = auctionId, optIn = on) }

        suspend fun GalleryApp.refused(
            member: Uuid,
            op: suspend (io.ktor.server.application.ApplicationCall) -> Any?,
        ): Throwable = attempt(member = member, op = op).exceptionOrNull() ?: error("expected a refusal")

        // ---------------------------------------------------------------- consent

        test("the consent is off by default, can be given by the seller, writes the snapshot and ONE audit entry without amount or id") {
            val seller = fx.member(label = "seller")
            val other = fx.member(label = "other")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 123456)
                created.federationOptIn shouldBe false
                created.federationOptInAt shouldBe null
                val on = consent(seller, created.id)
                on.federationOptIn shouldBe true
                (on.federationOptInAt != null) shouldBe true
                // nobody else learns whether (or since when) the auction is shared
                val seen = call(member = other) { svc(it).getAuction(created.id) }
                seen.federationOptIn shouldBe false
                seen.federationOptInAt shouldBe null
                val snapshot = transaction { AuctionFederationStore.snapshot(Uuid.parse(created.id)) }!!
                snapshot.teaserId shouldBe teaserIdOf(created.id)
                snapshot.priceKind shouldBe AuctionTeaserPriceKind.START
                val events = auditEvents(created.id)
                events.count { it.contains("AUCTION_FEDERATION_CONSENT_ON") } shouldBe 1
                events.forEach {
                    it shouldNotContain snapshot.teaserId
                    it shouldNotContain "123456"
                }
                // idempotent: a second call writes nothing more
                consent(seller, created.id).federationOptIn shouldBe true
                auditEvents(created.id).count { it.contains("AUCTION_FEDERATION_CONSENT_ON") } shouldBe 1
            }
        }

        test("every refusal has its own typed answer, a stranger's and an unknown auction get the SAME not found") {
            val seller = fx.member(label = "seller")
            val stranger = fx.member(label = "stranger")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                val strangerAnswer = refused(stranger) { svc(it).setAuctionFederationOptIn(auctionId = created.id, optIn = true) }
                val unknownAnswer =
                    refused(stranger) { svc(it).setAuctionFederationOptIn(auctionId = Uuid.random().toString(), optIn = true) }
                val malformedAnswer = refused(stranger) { svc(it).setAuctionFederationOptIn(auctionId = "not-an-id-123", optIn = true) }
                strangerAnswer.shouldBeInstanceOf<NotFoundException>()
                unknownAnswer.shouldBeInstanceOf<NotFoundException>()
                malformedAnswer.shouldBeInstanceOf<NotFoundException>()
                strangerAnswer.message shouldBe unknownAnswer.message
                strangerAnswer.message shouldBe malformedAnswer.message
                (strangerAnswer.message ?: "") shouldNotContain created.id

                federation(enabled = false)
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(
                        auctionId = created.id,
                        optIn = true,
                    )
                }.shouldBeInstanceOf<AuctionFederationUnavailableException>()
                federation(enabled = true)
                AuctionFederationKey.configure(null)
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(
                        auctionId = created.id,
                        optIn = true,
                    )
                }.shouldBeInstanceOf<AuctionFederationUnavailableException>()
                AuctionFederationKey.configure(ids)
                setFiat(false)
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(
                        auctionId = created.id,
                        optIn = true,
                    )
                }.shouldBeInstanceOf<AuctionFederationUnavailableException>()
                setFiat(true)

                endsIn(created.id, 1.hours)
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(
                        auctionId = created.id,
                        optIn = true,
                    )
                }.shouldBeInstanceOf<AuctionFederationEndsTooSoonException>()

                val urlTitle = listFiat(seller, title = "Mein Rad http://spam.example")
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(auctionId = urlTitle.id, optIn = true)
                }.shouldBeInstanceOf<AuctionFederationTitleException>()

                val ltr = fx.auction(seller = seller)
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(
                        auctionId = ltr.toString(),
                        optIn = true,
                    )
                }.shouldBeInstanceOf<AuctionFederationNotAllowedException>()

                val ended = listFiat(seller, title = "Schon vorbei")
                endNow(ended.id)
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(auctionId = ended.id, optIn = true)
                }.shouldBeInstanceOf<AuctionFederationNotAllowedException>()
                // nothing was written by any refusal
                transaction { AuctionFederationStore.countOptIns() } shouldBe 0L
            }
        }

        test("the 21st shared auction of one seller is refused") {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                val first = listFiat(seller)
                // twenty more consented rows (inserted directly: the open-auction limit of the service would stop the 21st listing)
                val rows =
                    (1..20).map {
                        val id = Uuid.random()
                        transaction {
                            AuctionTable.insert { r ->
                                r[AuctionTable.id] = id
                                r[title] = "Füller $it"
                                r[description] = "d"
                                r[status] = AuctionStatus.OPEN
                                r[sellerMemberId] = seller
                                r[listingFeeLtr] = BigDecimal("0.00")
                                r[createdAt] = DbClock.nowLocalDateTime()
                                r[AuctionTable.endsAt] = LocalDateTime(DbClock.nowLocalDateTime().year + 1, 1, 1, 0, 0)
                                r[currency] = "EUR"
                                r[startingPriceMinor] = 1000L
                                r[sellerDisclaimerVersion] = "test"
                                r[federationOptIn] = true
                                r[federationOptInAt] = DbClock.nowLocalDateTime()
                            }
                        }
                        fx.auctionIds += id
                        id
                    }
                rows.size shouldBe 20
                refused(
                    seller,
                ) {
                    svc(
                        it,
                    ).setAuctionFederationOptIn(auctionId = first.id, optIn = true)
                }.shouldBeInstanceOf<AuctionFederationShareLimitException>()
                transaction { AuctionFederationStore.optIn(Uuid.parse(first.id)) } shouldBe false
            }
        }

        // ---------------------------------------------------------------- feed content, data minimisation

        test("the feed carries the hint with a valid origin signature and NOTHING else about the auction, its bidders or its payment") {
            val seller = fx.member(label = "seller")
            val bidder = fx.member(label = "bidder")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 2000, buyNow = 9000, offers = true)
                fx.bid(auctionId = Uuid.parse(created.id), bidder = bidder)
                consent(seller, created.id)
                val feed = build(partner)
                feed.itemCount shouldBe 1
                feed.complete shouldBe true
                val text = String(feed.body, Charsets.UTF_8)
                // key sets, recursively
                val root = body(feed)
                root.keys shouldBe setOf("schemaVersion", "kind", "complete", "items", "withdrawn")
                val item =
                    root
                        .getValue("items")
                        .jsonArray
                        .single()
                        .jsonObject
                item.keys shouldBe setOf("originHost", "hops", "maxHops", "teaser", "via", "originSignature")
                item.getValue("teaser").jsonObject.keys shouldBe
                    setOf(
                        "teaserId",
                        "revision",
                        "title",
                        "currency",
                        "priceKind",
                        "priceMinor",
                        "buyNowMinor",
                        "offersPossible",
                        "endsAt",
                        "priceAsOf",
                    )
                // strings that must never appear
                listOf(
                    "Sabine",
                    SELLER_NAME,
                    IBAN,
                    "Geheime Beschreibung",
                    created.id,
                    seller.toString(),
                    bidder.toString(),
                    "Gallery-Test",
                    "bidder",
                ).forEach { text shouldNotContain it }
                // the signature verifies against the own public key
                val teaser =
                    json.decodeFromJsonElement(
                        network.lapis.cloud.shared.domain.AuctionTeaser
                            .serializer(),
                        item.getValue("teaser"),
                    )
                val pem =
                    transaction {
                        network.lapis.cloud.server.db.generated.FederationActorKeyTable
                            .selectAll()
                            .first()[network.lapis.cloud.server.db.generated.FederationActorKeyTable.publicKeyPem]
                    }
                AuctionOriginSignatures.verify(
                    teaser = teaser,
                    originHost = OWN_HOST,
                    maxHops = 2,
                    signatureB64 = item.getValue("originSignature").jsonPrimitive.content,
                    publicKeyPem = pem,
                ) shouldBe true
                teaser.currency shouldBe "EUR"
                teaser.offersPossible shouldBe true
                teaser.buyNowMinor shouldBe 9000
                // a stable body: the same content gives the same bytes (the ETag stays)
                String(build(partner).body, Charsets.UTF_8) shouldBe text
            }
        }

        test("no consent, no item; LTR, ended, lazily expired and inactive-seller auctions are never items") {
            val seller = fx.member(label = "seller")
            val leaver = fx.member(label = "leaver")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                listFiat(seller)
                build(partner).itemCount shouldBe 0
                val shared = listFiat(seller, title = "Geteilt")
                consent(seller, shared.id)
                build(partner).itemCount shouldBe 1
                val lazy = listFiat(seller, title = "Lazy")
                consent(seller, lazy.id)
                build(partner).itemCount shouldBe 2
                // ends without anybody touching it: the status still says OPEN in the database
                endsIn(lazy.id, (-5).minutes)
                transaction {
                    AuctionTable
                        .selectAll()
                        .where {
                            AuctionTable.id eq
                                Uuid.parse(
                                    lazy.id,
                                )
                        }.single()[AuctionTable.status]
                } shouldBe
                    AuctionStatus.OPEN
                val feed = build(partner)
                itemIds(feed) shouldBe listOf(teaserIdOf(shared.id))
                // a consented auction whose seller left is not an item either
                val leavers = listFiat(leaver, title = "Von Austretendem")
                consent(leaver, leavers.id)
                build(partner).itemCount shouldBe 2
                transaction {
                    network.lapis.cloud.server.db.generated.MemberTable.update({
                        network.lapis.cloud.server.db.generated.MemberTable.id eq
                            leaver
                    }) {
                        it[status] = network.lapis.cloud.shared.domain.MemberStatus.WITHDRAWN
                    }
                }
                itemIds(build(partner)) shouldBe listOf(teaserIdOf(shared.id))
            }
        }

        // ---------------------------------------------------------------- withdrawal

        test(
            "taking the consent back: without a served feed no tombstone, afterwards a tombstone; a new consent brings the hint back higher",
        ) {
            val seller = fx.member(label = "seller")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val first = listFiat(seller, title = "Nie ausgeliefert")
                consent(seller, first.id)
                consent(seller, first.id, on = false).federationOptIn shouldBe false
                transaction { AuctionFederationStore.countLiveWithdrawals(DbClock.nowLocalDateTime()) } shouldBe 0
                auditEvents(first.id).count { it.contains("AUCTION_FEDERATION_CONSENT_OFF") } shouldBe 1
                // a second take-back is idempotent
                consent(seller, first.id, on = false)
                auditEvents(first.id).count { it.contains("AUCTION_FEDERATION_CONSENT_OFF") } shouldBe 1

                val second = listFiat(seller, title = "Ausgeliefert")
                consent(seller, second.id)
                itemIds(build(partner)) shouldContain teaserIdOf(second.id)
                served(partner)
                val revisionBefore =
                    build(partner).let { f ->
                        body(
                            f,
                        ).getValue(
                            "items",
                        ).jsonArray
                            .single()
                            .jsonObject
                            .getValue("teaser")
                            .jsonObject
                            .getValue("revision")
                            .jsonPrimitive.content
                            .toLong()
                    }
                consent(seller, second.id, on = false)
                val after = build(partner)
                itemIds(after).shouldBeEmpty()
                withdrawnIds(after) shouldBe listOf(teaserIdOf(second.id))
                // the seller changes his mind: the tombstone goes and the revision jumps above the old one
                consent(seller, second.id)
                val back = build(partner)
                withdrawnIds(back).shouldBeEmpty()
                val revisionAfter =
                    body(
                        back,
                    ).getValue(
                        "items",
                    ).jsonArray
                        .single()
                        .jsonObject
                        .getValue("teaser")
                        .jsonObject
                        .getValue("revision")
                        .jsonPrimitive.content
                        .toLong()
                revisionAfter shouldBeGreaterThan revisionBefore
            }
        }

        test("a Sofortkauf withdraws the hint; the consent ends with the auction") {
            val seller = fx.member(label = "seller")
            val buyer = fx.member(label = "buyer")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 1000, buyNow = 5000)
                consent(seller, created.id)
                build(partner).itemCount shouldBe 1
                served(partner)
                call(member = buyer) { svc(it).buyNow(created.id) }
                val feed = build(partner)
                itemIds(feed).shouldBeEmpty()
                withdrawnIds(feed) shouldBe listOf(teaserIdOf(created.id))
                transaction { AuctionFederationStore.optIn(Uuid.parse(created.id)) } shouldBe false
            }
        }

        test("the time-based close (lazy, through getAuction) withdraws the hint; so does the sweep of an auction nobody touched") {
            val seller = fx.member(label = "seller")
            val viewer = fx.member(label = "viewer")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val touched = listFiat(seller, title = "Berührt")
                val untouched = listFiat(seller, title = "Unberührt")
                consent(seller, touched.id)
                consent(seller, untouched.id)
                build(partner).itemCount shouldBe 2
                served(partner)
                withClockPlus(hours = 25) {
                    call(member = viewer) { svc(it).getAuction(touched.id) }.status shouldBe AuctionStatus.CLOSED_NO_SALE
                    val feed = build(partner)
                    itemIds(feed).shouldBeEmpty()
                    // `touched` by the hook, `untouched` by the lazy sweep of the build
                    withdrawnIds(feed).toSet() shouldBe setOf(teaserIdOf(touched.id), teaserIdOf(untouched.id))
                }
            }
        }

        test(
            "a change of the offer from EUR to LTR ends the consent at once (even if never served) and a switch back does NOT share again",
        ) {
            val seller = fx.member(label = "seller")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, title = "Wird LTR")
                consent(seller, created.id)
                build(partner).itemCount shouldBe 1
                transaction {
                    LtrLedgerEntryTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = seller
                        it[entryType] = LtrLedgerEntryType.MINT
                        it[amountLtr] = BigDecimal("5.00")
                        it[referenceType] = null
                        it[referenceId] = null
                        it[note] = "federation test seed"
                        it[createdBy] = null
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    }
                }
                val toLtr =
                    call(member = seller) {
                        svc(it).updateAuctionTerms(
                            auctionId = created.id,
                            input = UpdateAuctionTermsInput(currency = "LTR", startingBidLtr = BigDecimal("1.00")),
                        )
                    }
                toLtr.federationOptIn shouldBe false
                itemIds(build(partner)).shouldBeEmpty()
                transaction { AuctionFederationStore.countLiveWithdrawals(DbClock.nowLocalDateTime()) } shouldBe 1
            }
        }

        test(
            "switching the instance off withdraws every shared hint for everybody and keeps the consents; switching it on brings them back higher",
        ) {
            val seller = fx.member(label = "seller")
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val p1 = sharingPartner()
            val p2 = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                consent(seller, created.id)
                val before = build(p1)
                build(p2).itemCount shouldBe 1
                call(member = admin) { partnerSvc(it).setAuctionFederationSettings(enabled = false, hopLimit = 2) }
                // the consent stays ...
                transaction { AuctionFederationStore.optIn(Uuid.parse(created.id)) } shouldBe true
                transaction { AuctionFederationStore.countLiveWithdrawals(DbClock.nowLocalDateTime()) } shouldBe 1
                // ... a partner that was served before still hears about the withdrawal (trailing answer), and is told nothing else
                served(p1)
                val trailing =
                    transaction {
                        AuctionTeaserFeedBuilder.buildWithdrawalsOnly(
                            partner = PartnerServerStore.findPartner(p1.id)!!,
                            ids = ids,
                        )
                    }
                trailing.itemCount shouldBe 0
                withdrawnIds(trailing) shouldBe listOf(teaserIdOf(created.id))
                call(member = admin) { partnerSvc(it).setAuctionFederationSettings(enabled = true, hopLimit = 2) }
                val back = build(p1)
                itemIds(back) shouldBe listOf(teaserIdOf(created.id))
                withdrawnIds(back).shouldBeEmpty()
                val revBefore =
                    body(
                        before,
                    ).getValue(
                        "items",
                    ).jsonArray
                        .single()
                        .jsonObject
                        .getValue("teaser")
                        .jsonObject
                        .getValue("revision")
                        .jsonPrimitive.content
                        .toLong()
                val revAfter =
                    body(
                        back,
                    ).getValue(
                        "items",
                    ).jsonArray
                        .single()
                        .jsonObject
                        .getValue("teaser")
                        .jsonObject
                        .getValue("revision")
                        .jsonPrimitive.content
                        .toLong()
                revAfter shouldBeGreaterThan revBefore
            }
        }

        test("switching the auction module or the Euro module off withdraws the shared hints") {
            val seller = fx.member(label = "seller")
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val a = listFiat(seller, title = "A")
                consent(seller, a.id)
                call(member = admin) { svc(it).setAuctionFiatEnabled(enabled = false, input = null) }
                transaction { AuctionFederationStore.countLiveWithdrawals(DbClock.nowLocalDateTime()) } shouldBe 1
                // the modules are off: the feed gate refuses a full snapshot (see AuctionTeaserFeedScenarios), the trailing answer carries the withdrawal
                transaction { AuctionFederationStore.modulesOn() } shouldBe false
                transaction {
                    AuctionFederationStore.hasLiveWithdrawalsFor(
                        partnerId = partner.id,
                        now = DbClock.nowLocalDateTime(),
                    )
                } shouldBe
                    true
                setFiat(true)
                transaction { AuctionTeaserTombstoneTable.deleteAll() }
                call(member = admin) { svc(it).disableAuction() }
                transaction { AuctionFederationStore.countLiveWithdrawals(DbClock.nowLocalDateTime()) } shouldBe 1
            }
        }

        test("switching ONE partner off withdraws the hints for that partner only; switching it on again forgets the tombstones") {
            val seller = fx.member(label = "seller")
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val p1 = sharingPartner()
            val p2 = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                consent(seller, created.id)
                build(p1).itemCount shouldBe 1
                call(member = admin) { partnerSvc(it).setPartnerAuctionSharing(partnerId = p1.id.toString(), enabled = false) }
                // the other partner is not affected
                itemIds(build(p2)) shouldBe listOf(teaserIdOf(created.id))
                withdrawnIds(build(p2)).shouldBeEmpty()
                transaction { AuctionFederationStore.hasLiveWithdrawalsFor(partnerId = p1.id, now = DbClock.nowLocalDateTime()) } shouldBe
                    true
                transaction { AuctionFederationStore.hasLiveWithdrawalsFor(partnerId = p2.id, now = DbClock.nowLocalDateTime()) } shouldBe
                    false
                // the switched-off partner gets no items from the builder (the eligibility rule says no)
                itemIds(build(p1)).shouldBeEmpty()
                call(member = admin) { partnerSvc(it).setPartnerAuctionSharing(partnerId = p1.id.toString(), enabled = true) }
                transaction { AuctionFederationStore.hasLiveWithdrawalsFor(partnerId = p1.id, now = DbClock.nowLocalDateTime()) } shouldBe
                    false
                itemIds(build(p1)) shouldBe listOf(teaserIdOf(created.id))
            }
        }

        test("a missing key still clears the consent, writes no tombstone and counts the miss") {
            val seller = fx.member(label = "seller")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                consent(seller, created.id)
                build(partner)
                served(partner)
                AuctionFederationKey.configure(null)
                consent(seller, created.id, on = false).federationOptIn shouldBe false
                AuctionFederationKey.skippedNoKey.get() shouldBe 1L
                transaction { AuctionFederationStore.countLiveWithdrawals(DbClock.nowLocalDateTime()) } shouldBe 0
            }
        }

        // ---------------------------------------------------------------- throttle

        test("the shown price is throttled: bids reach the feed at most every 30 minutes, a start price change without a bid at once") {
            val seller = fx.member(label = "seller")
            val bidder = fx.member(label = "bidder")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller, start = 1000, buyNow = 9000)
                consent(seller, created.id)
                val id = Uuid.parse(created.id)
                val t0 = DbClock.nowLocalDateTime()

                fun publish(at: LocalDateTime) =
                    transaction {
                        val row = AuctionTable.selectAll().where { AuctionTable.id eq id }.single()
                        AuctionTeaserSnapshots.publishable(row = row, ids = ids, fiatEnabled = true, now = at)!!
                    }

                fun minutesLater(m: Long) =
                    (t0.toInstant(TimeZone.UTC) + m.minutes).let { kotlinx.datetime.LocalDateTime.parse(it.toString().removeSuffix("Z")) }

                val start = publish(t0)
                start.priceKind shouldBe AuctionTeaserPriceKind.START
                start.priceMinor shouldBe 1000
                // an unchanged auction keeps its revision however often it is read
                publish(minutesLater(5)).revision shouldBe start.revision
                // a start price change without a bid goes out at once
                transaction { AuctionTable.update({ AuctionTable.id eq id }) { it[startingPriceMinor] = 1500L } }
                val changed = publish(minutesLater(6))
                changed.priceMinor shouldBe 1500
                changed.revision shouldBeGreaterThan start.revision
                // a bid arrives: the first transition is throttled against the last price change
                fx.bid(auctionId = id, bidder = bidder)
                transaction {
                    AuctionBidTable.update({ AuctionBidTable.auctionId eq id }) {
                        it[maxBidMinor] = 4000L
                        it[maxBidLtr] = null
                    }
                }
                val early = publish(minutesLater(10))
                early.priceKind shouldBe AuctionTeaserPriceKind.START
                early.priceMinor shouldBe 1500
                early.revision shouldBe changed.revision
                val late = publish(minutesLater(40))
                late.priceKind shouldBe AuctionTeaserPriceKind.CURRENT
                late.revision shouldBeGreaterThan changed.revision
                // no further change after another half hour: no new revision
                publish(minutesLater(75)).revision shouldBe late.revision
                // the Sofortkauf price changes the content at once
                transaction { AuctionTable.update({ AuctionTable.id eq id }) { it[buyNowPriceMinor] = 9500L } }
                val bn = publish(minutesLater(76))
                bn.buyNowMinor shouldBe 9500
                bn.revision shouldBeGreaterThan late.revision
                // the timestamps never move beyond the end
                (bn.priceAsOf <= bn.endsAt) shouldBe true
            }
        }

        test(
            "the snapshot update is conditional: a second writer with the old revision loses; revisions never fall behind the wall clock",
        ) {
            val seller = fx.member(label = "seller")
            galleryTestApplication(fixtures = fx) {
                val created = listFiat(seller)
                consent(seller, created.id)
                val id = Uuid.parse(created.id)
                val now = DbClock.nowLocalDateTime()
                val old = transaction { AuctionFederationStore.snapshot(id) }!!
                transaction {
                    AuctionFederationStore.advanceSnapshot(
                        old = old,
                        teaserId = old.teaserId,
                        priceKind = old.priceKind,
                        priceMinor = old.priceMinor,
                        priceAsOf = old.priceAsOf,
                        contentSha256 = "f".repeat(64),
                        now = now,
                    )
                } shouldBe true
                transaction {
                    AuctionFederationStore.advanceSnapshot(
                        old = old,
                        teaserId = old.teaserId,
                        priceKind = old.priceKind,
                        priceMinor = old.priceMinor,
                        priceAsOf = old.priceAsOf,
                        contentSha256 = "e".repeat(64),
                        now = now,
                    )
                } shouldBe false
                transaction { AuctionFederationStore.snapshot(id) }!!.contentSha256 shouldBe "f".repeat(64)
                (AuctionFederationStore.newRevision(prev = 5, now = now) > 5) shouldBe true
                (AuctionFederationStore.newRevision(prev = Long.MAX_VALUE / 2, now = now) == Long.MAX_VALUE / 2 + 1) shouldBe true
            }
        }

        // ---------------------------------------------------------------- landing route

        test("the landing route resolves only a plainly open shared auction; everything else is the ONE answer") {
            val seller = fx.member(label = "seller")
            val viewer = fx.member(label = "viewer")
            galleryTestApplication(fixtures = fx) {
                val open = listFiat(seller, title = "Offen")
                val ended = listFiat(seller, title = "Zu Ende")
                val withdrawn = listFiat(seller, title = "Zurückgezogen")
                listOf(open, ended, withdrawn).forEach { consent(seller, it.id) }
                consent(seller, withdrawn.id, on = false)
                val rowsBefore =
                    transaction {
                        AuctionTeaserSnapshotTable.selectAll().count() +
                            AuctionTeaserTombstoneTable.selectAll().count()
                    }

                suspend fun resolve(t: String) = call(member = viewer) { svc(it).resolveAuctionHint(t) }

                resolve(teaserIdOf(open.id)) shouldBe AuctionHintResolutionDto.Available(open.id)
                val unavailable = AuctionHintResolutionDto.Unavailable
                resolve(teaserIdOf(withdrawn.id)).shouldBeSameInstanceAs(unavailable)
                resolve(ids.of(Uuid.random())).shouldBeSameInstanceAs(unavailable)
                resolve("zz").shouldBeSameInstanceAs(unavailable)
                resolve("A".repeat(64)).shouldBeSameInstanceAs(unavailable)
                resolve("a".repeat(63)).shouldBeSameInstanceAs(unavailable)
                // lazily expired: still OPEN in the database, but over
                endsIn(ended.id, (-5).minutes)
                resolve(teaserIdOf(ended.id)).shouldBeSameInstanceAs(unavailable)
                transaction {
                    AuctionTable
                        .selectAll()
                        .where {
                            AuctionTable.id eq
                                Uuid.parse(
                                    ended.id,
                                )
                        }.single()[AuctionTable.status]
                } shouldBe
                    AuctionStatus.OPEN
                // the switches
                federation(enabled = false)
                resolve(teaserIdOf(open.id)).shouldBeSameInstanceAs(unavailable)
                federation(enabled = true)
                setFiat(false)
                resolve(teaserIdOf(open.id)).shouldBeSameInstanceAs(unavailable)
                setFiat(true)
                resolve(teaserIdOf(open.id)) shouldBe AuctionHintResolutionDto.Available(open.id)
                // it writes nothing
                transaction { AuctionTeaserSnapshotTable.selectAll().count() + AuctionTeaserTombstoneTable.selectAll().count() } shouldBe
                    rowsBefore
            }
        }

        test("after a rotation of the key the feed publishes the new id AND the hint link with the new id resolves") {
            val seller = fx.member(label = "seller")
            val viewer = fx.member(label = "viewer")
            val partner = sharingPartner()
            galleryTestApplication(fixtures = fx) {
                val open = listFiat(seller)
                consent(seller, open.id)
                val oldId = teaserIdOf(open.id)
                itemIds(build(partner)) shouldBe listOf(oldId)
                val rotated = AuctionTeaserIds(SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 77).toByte() }))
                val newId = rotated.of(Uuid.parse(open.id))
                (newId != oldId) shouldBe true
                val feed =
                    transaction {
                        AuctionTeaserFeedBuilder.build(
                            partner = PartnerServerStore.findPartner(partner.id)!!,
                            settings = AuctionFederationStore.settings(),
                            ids = rotated,
                            originHost = OWN_HOST,
                            now = DbClock.nowLocalDateTime(),
                            ownKey = CarpoolOwnKeys.load(),
                        )
                    }
                itemIds(feed) shouldBe listOf(newId)
                call(member = viewer) { svc(it).resolveAuctionHint(newId) } shouldBe AuctionHintResolutionDto.Available(open.id)
            }
        }

        test("an exhausted per-member budget answers like an unknown hint (no rate-limit oracle)") {
            val seller = fx.member(label = "seller")
            val viewer = fx.member(label = "viewer")
            galleryTestApplication(fixtures = fx) {
                val open = listFiat(seller)
                consent(seller, open.id)
                val limiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
                val t = teaserIdOf(open.id)
                call(member = viewer) { svc(it, limiter).resolveAuctionHint(t) } shouldBe AuctionHintResolutionDto.Available(open.id)
                call(member = viewer) { svc(it, limiter).resolveAuctionHint(t) } shouldBe AuctionHintResolutionDto.Available(open.id)
                call(
                    member = viewer,
                ) { svc(it, limiter).resolveAuctionHint(t) }.shouldBeSameInstanceAs(AuctionHintResolutionDto.Unavailable)
            }
        }

        test("the availability for members and the reach list follow the switches and the partner prerequisites") {
            val member = fx.member(label = "member")
            val good = sharingPartner()
            sharingPartner(sharing = false)
            galleryTestApplication(fixtures = fx) {
                call(member = member) { svc(it).getAuctionFederationAvailability() }.enabled shouldBe true
                val reach = call(member = member) { svc(it).getAuctionFederationReach() }
                reach.enabled shouldBe true
                reach.partners.size shouldBe 1
                reach.partners.single() shouldBe good.host
                reach.maxHops shouldBe 2
                federation(enabled = false)
                call(member = member) { svc(it).getAuctionFederationAvailability() }.enabled shouldBe false
                call(member = member) { svc(it).getAuctionFederationReach() }.enabled shouldBe false
                federation(enabled = true)
                AuctionFederationKey.configure(null)
                call(member = member) { svc(it).getAuctionFederationAvailability() }.enabled shouldBe false
            }
        }

        // ---------------------------------------------------------------- administration

        test(
            "the admin settings: ADMIN only, a hop limit outside 1..2 is refused and changes nothing, every change is audited, the counter counts",
        ) {
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val member = fx.member(label = "member")
            val seller = fx.member(label = "seller")
            val partner = sharingPartner(sharing = false)
            galleryTestApplication(fixtures = fx) {
                refused(member) { partnerSvc(it).getAuctionFederationSettings() }.shouldBeInstanceOf<ForbiddenException>()
                refused(
                    member,
                ) { partnerSvc(it).setAuctionFederationSettings(enabled = true, hopLimit = 2) }.shouldBeInstanceOf<ForbiddenException>()
                refused(
                    member,
                ) {
                    partnerSvc(
                        it,
                    ).setPartnerAuctionSharing(partnerId = partner.id.toString(), enabled = true)
                }.shouldBeInstanceOf<ForbiddenException>()
                refused(
                    admin,
                ) { partnerSvc(it).setAuctionFederationSettings(enabled = true, hopLimit = 0) }.shouldBeInstanceOf<BadRequestException>()
                refused(
                    admin,
                ) { partnerSvc(it).setAuctionFederationSettings(enabled = true, hopLimit = 3) }.shouldBeInstanceOf<BadRequestException>()
                transaction { AuctionFederationStore.settings().hopLimit } shouldBe 2
                refused(
                    admin,
                ) {
                    partnerSvc(
                        it,
                    ).setPartnerAuctionSharing(partnerId = Uuid.random().toString(), enabled = true)
                }.shouldBeInstanceOf<NotFoundException>()
                refused(
                    admin,
                ) { partnerSvc(it).setPartnerAuctionSharing(partnerId = "nope", enabled = true) }.shouldBeInstanceOf<NotFoundException>()

                val created = listFiat(seller)
                consent(seller, created.id)
                val s = call(member = admin) { partnerSvc(it).getAuctionFederationSettings() }
                s.enabled shouldBe true
                s.sharedActive shouldBe 1
                s.feedKeyAvailable shouldBe true

                val partnerAudits = {
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                AuditLogEntryTable.entityType eq
                                    AuditEntityType.PARTNER_SERVER
                            }.count()
                    }
                }
                val before = partnerAudits()
                call(member = admin) { partnerSvc(it).setAuctionFederationSettings(enabled = true, hopLimit = 1) }
                partnerAudits() shouldBe before + 1
                call(member = admin) { partnerSvc(it).setAuctionFederationSettings(enabled = true, hopLimit = 1) }
                partnerAudits() shouldBe before + 1
                call(member = admin) { partnerSvc(it).setPartnerAuctionSharing(partnerId = partner.id.toString(), enabled = true) }
                partnerAudits() shouldBe before + 2
                call(member = admin) { partnerSvc(it).list() }.partners.single { it.id == partner.id.toString() }.auctionSharing shouldBe
                    true
            }
        }

        test("PartnerServerStore.latestAuctionFeedServedAt follows the served moment") {
            val partner = sharingPartner()
            transaction { PartnerServerStore.latestAuctionFeedServedAt() } shouldBe null
            served(partner)
            (transaction { PartnerServerStore.latestAuctionFeedServedAt() } != null) shouldBe true
            transaction { PartnerServerStore.findPartner(partner.id)!!.auctionFeedServedCount } shouldBe 1L
        }
    })
