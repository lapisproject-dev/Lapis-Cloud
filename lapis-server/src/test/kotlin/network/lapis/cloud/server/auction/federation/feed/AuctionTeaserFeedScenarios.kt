package network.lapis.cloud.server.auction.federation.feed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.auction.federation.AuctionFederationKey
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionTeaserIds
import network.lapis.cloud.server.auction.federation.AuctionTeaserSnapshots
import network.lapis.cloud.server.auction.federation.AuctionWithdrawals
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.feed.CARPOOL_TEASER_FEED_PATH
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedCounters
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedLimits
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.OWN_HOST
import network.lapis.cloud.server.carpool.feed.SignedGet
import network.lapis.cloud.server.carpool.feed.TEST_BOX
import network.lapis.cloud.server.carpool.feed.fingerprint
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.carpool.feed.registerCarpoolTeaserFeedRoutes
import network.lapis.cloud.server.carpool.feed.signedGet
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val IDS = AuctionTeaserIds(TEST_BOX)

private class AuctionFeedLimits(
    val ip: Int = 1000,
    val partner: Int = 1000,
    val global: Int = 1000,
)

private fun auctionFeedApplication(
    limits: AuctionFeedLimits = AuctionFeedLimits(),
    ids: AuctionTeaserIds? = IDS,
    ownHost: String? = OWN_HOST,
    buildPermits: Semaphore = AuctionTeaserFeedLimits.newBuildPermits(),
    block: suspend ApplicationTestBuilder.(AuctionTeaserFeedCounters) -> Unit,
) {
    val counters = AuctionTeaserFeedCounters()
    testApplication {
        application {
            routing {
                registerAuctionTeaserFeedRoutes(
                    ipRateLimiter = FederationInboxRateLimiter(maxRequests = limits.ip, window = 1.minutes),
                    partnerRateLimiter = FederationInboxRateLimiter(maxRequests = limits.partner, window = 1.hours),
                    globalRateLimiter = FederationInboxRateLimiter(maxRequests = limits.global, window = 1.hours),
                    replayGuard = FederationReplayGuard(),
                    teaserIds = ids,
                    counters = counters,
                    ownHost = ownHost,
                    buildPermits = buildPermits,
                )
                // the carpool route next to it: a refusal must be byte-identical on both
                registerCarpoolTeaserFeedRoutes(
                    ipRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    partnerRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.hours),
                    globalRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.hours),
                    replayGuard = FederationReplayGuard(),
                    teaserIds =
                        network.lapis.cloud.server.carpool.feed
                            .CarpoolTeaserIds(TEST_BOX),
                    counters = CarpoolTeaserFeedCounters(),
                    ownHost = ownHost,
                    buildPermits = Semaphore(CarpoolTeaserFeedLimits.MAX_CONCURRENT_BUILDS),
                )
            }
        }
        block(counters)
    }
}

private fun auctionSigned(
    partner: FeedPartner,
    keyId: String = partner.keyId,
    privateKeyPem: String = partner.privateKeyPem,
    host: String = OWN_HOST,
    path: String = AUCTION_TEASER_FEED_PATH,
) = signedGet(partner = partner, keyId = keyId, privateKeyPem = privateKeyPem, host = host, path = path)

private suspend fun HttpClient.auctionGet(
    signed: SignedGet?,
    block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    get(AUCTION_TEASER_FEED_PATH) {
        if (signed != null) {
            header("Signature", signed.signature)
            header(HttpHeaders.Date, signed.date)
            header("Digest", signed.digest)
        }
        block()
    }

/**
 * V1.9.322 (step c2) -- the signed auction teaser feed on H2 AND PostgreSQL: the shape of a 200, ETag and 304, ONE byte-identical refusal for every
 * signature and approval failure (also identical to the carpool refusal), a signature for another path never works, the three budgets and the build
 * permits, the audit entry and the trailing answer after a switch-off.
 */
abstract class AuctionTeaserFeedScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val json = Json
        val auctionIds = mutableListOf<Uuid>()
        val ownSeller = Uuid.random()
        var sellerInserted = false

        fun seller(): Uuid {
            if (!sellerInserted) {
                transaction {
                    MemberTable.insert {
                        it[id] = ownSeller
                        it[displayName] = "Auction Feed Seller"
                        it[email] = "auction-feed-seller@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2027, 1, 1)
                        it[membershipTierId] = null
                    }
                }
                sellerInserted = true
            }
            return ownSeller
        }

        fun sharedAuction(title: String = "Lastenrad"): Uuid {
            val id = Uuid.random()
            val s = seller()
            val now = DbClock.nowLocalDateTime()
            transaction {
                AuctionTable.insert {
                    it[AuctionTable.id] = id
                    it[AuctionTable.title] = title
                    it[description] = "geheim"
                    it[status] = AuctionStatus.OPEN
                    it[sellerMemberId] = s
                    it[listingFeeLtr] = BigDecimal("0.00")
                    it[createdAt] = now
                    it[AuctionTable.endsAt] = LocalDateTime(now.year + 1, 1, 1, 0, 0)
                    it[currency] = "EUR"
                    it[startingPriceMinor] = 1000L
                    it[sellerDisclaimerVersion] = "test"
                }
                AuctionFederationStore.setOptIn(auctionId = id, value = true, now = now)
                val row = AuctionTable.selectAll().where { AuctionTable.id eq id }.single()
                AuctionTeaserSnapshots.createOnConsent(row = row, ids = IDS, fiatEnabled = true, now = now)
            }
            auctionIds += id
            return id
        }

        fun partner(
            sharing: Boolean = true,
            active: Boolean = true,
            pinned: Boolean = true,
            host: String = "p" + Uuid.random().toString().take(8) + ".beispiel.de",
        ): FeedPartner {
            val p = newFeedPartner(host = host, sharing = false, active = active, pinned = pinned)
            if (sharing) transaction { PartnerServerStore.setAuctionSharing(id = p.id, enabled = true) }
            return p
        }

        fun setModules(
            auction: Boolean = true,
            fiat: Boolean = true,
        ) = transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionEnabled] = auction
                it[auctionFiatEnabled] = fiat
            }
        }

        fun auditCount(p: FeedPartner): Int =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.AUCTION_FEDERATION_FEED) and
                            (AuditLogEntryTable.entityId eq p.id)
                    }.count()
                    .toInt()
            }

        fun cleanUp() {
            transaction {
                AuctionTeaserTombstoneTable.deleteAll()
                AuctionTeaserSnapshotTable.deleteAll()
                AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId inList auctionIds }
                AuctionTable.deleteWhere { AuctionTable.id inList auctionIds }
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[auctionEnabled] = false
                    it[auctionFiatEnabled] = false
                }
                AuctionFederationStore.setSettings(enabled = false, hopLimit = 1)
            }
            auctionIds.clear()
        }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            cleanUp()
            setModules()
            transaction { AuctionFederationStore.setSettings(enabled = true, hopLimit = 2) }
            AuctionFederationKey.resetForTest()
            AuctionFederationKey.configure(IDS)
        }
        afterSpec {
            try {
                AuctionFederationKey.resetForTest()
                cleanUp()
                if (sellerInserted) transaction { MemberTable.deleteWhere { MemberTable.id eq ownSeller } }
            } finally {
                db.deactivate()
            }
        }

        test("an approved partner gets 200 with the documented shape, an audit entry with counts only, and the served state moves") {
            sharedAuction("Lastenrad in Braunschweig")
            val p = partner()
            auctionFeedApplication { counters ->
                val response = client.auctionGet(signed = auctionSigned(partner = p))
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                (response.headers[HttpHeaders.ETag] != null) shouldBe true
                val text = response.bodyAsText()
                val root = json.parseToJsonElement(text).jsonObject
                root.getValue("kind").jsonPrimitive.content shouldBe "lapis-auction-teaser-feed"
                root.getValue("complete").jsonPrimitive.content shouldBe "true"
                root.getValue("items").jsonArray shouldHaveSize 1
                text shouldNotContain "geheim"
                text shouldNotContain "Auction Feed Seller"
                counters.snapshot().served shouldBe 1L
                auditCount(p) shouldBe 1
                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityId eq p.id }
                            .single()[AuditLogEntryTable.afterSnapshot]
                    } ?: ""
                audit shouldNotContain "Lastenrad"
                audit shouldNotContain "teaserId"
                transaction { PartnerServerStore.findPartner(p.id)!! }.let {
                    it.auctionFeedServedCount shouldBe 1L
                    it.auctionFeedLastItems shouldBe 1
                }
            }
        }

        test("a matching If-None-Match gives 304 without an audit entry; a change of the content changes the ETag") {
            sharedAuction()
            val p = partner()
            auctionFeedApplication { counters ->
                val first = client.auctionGet(signed = auctionSigned(partner = p))
                val etag = first.headers[HttpHeaders.ETag]!!
                val again = client.auctionGet(signed = auctionSigned(partner = p)) { header(HttpHeaders.IfNoneMatch, etag) }
                again.status shouldBe HttpStatusCode.NotModified
                again.headers[HttpHeaders.ETag] shouldBe etag
                counters.snapshot().notModified shouldBe 1L
                auditCount(p) shouldBe 1
                sharedAuction("Zweite Auktion")
                val changed = client.auctionGet(signed = auctionSigned(partner = p)) { header(HttpHeaders.IfNoneMatch, etag) }
                changed.status shouldBe HttpStatusCode.OK
                (changed.headers[HttpHeaders.ETag] != etag) shouldBe true
            }
        }

        test(
            "every signature failure gives the SAME bytes as an unknown key, also identical to the carpool refusal; a carpool signature does not work here",
        ) {
            sharedAuction()
            val p = partner()
            val other = partner()
            val strangerKey = FederationKeyPairGenerator.generate()
            auctionFeedApplication { counters ->
                val prints = linkedMapOf<String, String>()

                suspend fun refused(
                    name: String,
                    request: suspend () -> HttpResponse,
                ) {
                    val response = request()
                    response.headers[HttpHeaders.ETag] shouldBe null
                    prints[name] = fingerprint(response)
                }
                refused("no headers") { client.auctionGet(signed = null) }
                refused(
                    "wrong private key",
                ) { client.auctionGet(signed = auctionSigned(partner = p, privateKeyPem = strangerKey.privateKeyPem)) }
                refused("unknown key id") {
                    client.auctionGet(
                        signed = auctionSigned(partner = p, keyId = "https://unbekannt.beispiel.de/federation/actor#main-key"),
                    )
                }
                refused("key id of another partner") { client.auctionGet(signed = auctionSigned(partner = p, keyId = other.keyId)) }
                refused("signed for another host") { client.auctionGet(signed = auctionSigned(partner = p, host = "anderer.beispiel.de")) }
                refused(
                    "signed for the carpool path",
                ) { client.auctionGet(signed = auctionSigned(partner = p, path = CARPOOL_TEASER_FEED_PATH)) }
                refused("query string") {
                    val s = auctionSigned(partner = p)
                    client.get("$AUCTION_TEASER_FEED_PATH?x=1") {
                        header("Signature", s.signature)
                        header(HttpHeaders.Date, s.date)
                        header("Digest", s.digest)
                    }
                }
                val replayed = auctionSigned(partner = p)
                client.auctionGet(signed = replayed).status shouldBe HttpStatusCode.OK
                refused("replay") { client.auctionGet(signed = replayed) }
                // the carpool refusal of an unsigned request
                val carpoolRefusal = fingerprint(client.get(CARPOOL_TEASER_FEED_PATH))
                (prints.values + carpoolRefusal).toSet() shouldHaveSize 1
                prints.values.first().startsWith("404|{\"error\":\"unavailable\"}|") shouldBe true
                counters.snapshot().refusedUnauthenticated shouldBe prints.size.toLong()
            }
        }

        test(
            "every approval failure gives the same bytes; only sharing on, active, not blocked, no key hold, switches on and the key present make it 200",
        ) {
            sharedAuction()
            auctionFeedApplication { _ ->
                val unknown = fingerprint(client.auctionGet(signed = auctionSigned(partner = partner(pinned = false))))
                val prints = mutableSetOf(unknown)

                suspend fun expectRefused(p: FeedPartner) {
                    val response = client.auctionGet(signed = auctionSigned(partner = p))
                    response.headers[HttpHeaders.ETag] shouldBe null
                    prints += fingerprint(response)
                }
                expectRefused(partner(active = false))
                expectRefused(partner(sharing = false))
                val blocked = partner(host = "gesperrt.beispiel.de")
                transaction { PartnerServerStore.addBlock(pattern = "gesperrt.beispiel.de", now = LocalDateTime(2026, 11, 5, 10, 0, 0)) }
                expectRefused(blocked)
                val removed = partner()
                transaction { PartnerServerStore.remove(removed.id) }
                expectRefused(removed)
                val held = partner()
                transaction {
                    PartnerServerTable.update({ PartnerServerTable.id eq held.id }) {
                        it[signingKeyChangedAt] =
                            LocalDateTime(2026, 11, 5, 10, 0, 0)
                    }
                }
                expectRefused(held)
                val good = partner()
                transaction { AuctionFederationStore.setSettings(enabled = false, hopLimit = 2) }
                expectRefused(good)
                transaction { AuctionFederationStore.setSettings(enabled = true, hopLimit = 2) }
                setModules(auction = false)
                expectRefused(good)
                setModules(fiat = false)
                expectRefused(good)
                setModules()
                client.auctionGet(signed = auctionSigned(partner = good)).status shouldBe HttpStatusCode.OK
                prints shouldHaveSize 1
            }
            // no key configured: everything is refused
            auctionFeedApplication(ids = null) { _ ->
                val response = client.auctionGet(signed = auctionSigned(partner = partner()))
                response.status shouldBe HttpStatusCode.NotFound
            }
            auctionFeedApplication(ownHost = null) { _ ->
                client.auctionGet(signed = auctionSigned(partner = partner())).status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "a carpool partner that does not share auctions gets no auction feed, and an auction partner that does not share carpool gets no carpool feed",
        ) {
            sharedAuction()
            val onlyCarpool = newFeedPartner(sharing = true)
            val onlyAuction = partner()
            auctionFeedApplication { _ ->
                client.auctionGet(signed = auctionSigned(partner = onlyCarpool)).status shouldBe HttpStatusCode.NotFound
                client.auctionGet(signed = auctionSigned(partner = onlyAuction)).status shouldBe HttpStatusCode.OK
                val carpoolSigned = signedGet(partner = onlyAuction)
                val r =
                    client.get(CARPOOL_TEASER_FEED_PATH) {
                        header("Signature", carpoolSigned.signature)
                        header(HttpHeaders.Date, carpoolSigned.date)
                        header("Digest", carpoolSigned.digest)
                    }
                r.status shouldBe HttpStatusCode.NotFound
                transaction { CarpoolFederationStore.settings().enabled } shouldBe false
            }
        }

        test("the budgets: per IP before the signature, per partner and globally only for an approved partner (so a 429 reveals nothing)") {
            sharedAuction()
            auctionFeedApplication(limits = AuctionFeedLimits(ip = 3)) { counters ->
                val p = partner()
                repeat(3) { client.auctionGet(signed = null) }
                client.auctionGet(signed = auctionSigned(partner = p)).status shouldBe HttpStatusCode.TooManyRequests
                counters.snapshot().rateLimited shouldBe 1L
            }
            auctionFeedApplication(limits = AuctionFeedLimits(partner = 1)) { _ ->
                val p = partner()
                val stranger = partner(sharing = false)
                client.auctionGet(signed = auctionSigned(partner = p)).status shouldBe HttpStatusCode.OK
                client.auctionGet(signed = auctionSigned(partner = p)).status shouldBe HttpStatusCode.TooManyRequests
                // a partner that is not approved never sees a 429, whatever the budget
                repeat(3) { client.auctionGet(signed = auctionSigned(partner = stranger)).status shouldBe HttpStatusCode.NotFound }
            }
            auctionFeedApplication(limits = AuctionFeedLimits(global = 1)) { _ ->
                client.auctionGet(signed = auctionSigned(partner = partner())).status shouldBe HttpStatusCode.OK
                client.auctionGet(signed = auctionSigned(partner = partner())).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("the build permits: an exhausted pool answers with the rate-limit bytes and a permit comes back after every build") {
            sharedAuction()
            val permits = Semaphore(1)
            auctionFeedApplication(buildPermits = permits) { _ ->
                val p = partner()
                permits.tryAcquire() shouldBe true
                val limited = client.auctionGet(signed = auctionSigned(partner = p))
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.bodyAsText() shouldBe "{\"error\":\"rate_limited\"}"
                permits.release()
                client.auctionGet(signed = auctionSigned(partner = p)).status shouldBe HttpStatusCode.OK
                permits.availablePermits shouldBe 1
            }
        }

        test(
            "after a switch-off a partner that was served before gets the live withdrawals and no item; a partner never served gets the refusal",
        ) {
            val id = sharedAuction()
            val served = partner()
            val never = partner()
            auctionFeedApplication { _ ->
                client.auctionGet(signed = auctionSigned(partner = served)).status shouldBe HttpStatusCode.OK
                transaction {
                    AuctionFederationStore.setSettings(enabled = false, hopLimit = 2)
                    AuctionWithdrawals.onInstanceOff(DbClock.nowLocalDateTime())
                }
                val trailing = client.auctionGet(signed = auctionSigned(partner = served))
                trailing.status shouldBe HttpStatusCode.OK
                val root = json.parseToJsonElement(trailing.bodyAsText()).jsonObject
                root.getValue("items").jsonArray shouldHaveSize 0
                root
                    .getValue("withdrawn")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("teaserId")
                    .jsonPrimitive.content shouldBe IDS.of(id)
                client.auctionGet(signed = auctionSigned(partner = never)).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("a snapshot is created lazily for a consented auction that has none (a restore, an old row) and the feed still works") {
            val id = sharedAuction()
            transaction { AuctionTeaserSnapshotTable.deleteAll() }
            val p = partner()
            auctionFeedApplication { _ ->
                val r = client.auctionGet(signed = auctionSigned(partner = p))
                r.status shouldBe HttpStatusCode.OK
                json
                    .parseToJsonElement(r.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray shouldHaveSize 1
                transaction { AuctionFederationStore.snapshot(id) } shouldBe transaction { AuctionFederationStore.snapshot(id) }
                (transaction { AuctionFederationStore.snapshot(id) } != null) shouldBe true
            }
        }
    })
