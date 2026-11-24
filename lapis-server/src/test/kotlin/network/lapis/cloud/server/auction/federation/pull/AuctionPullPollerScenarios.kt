package network.lapis.cloud.server.auction.federation.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionPullCounters
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.partner.FakeNetwork
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.fakeFetcherClientFactory
import network.lapis.cloud.server.partner.json
import network.lapis.cloud.server.partner.publicTargetFor
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.322 c3 -- the auction pull poller tick on H2 AND PostgreSQL with a fake network: what it pulls and when, the schedule after a success and a
 * failure, a 304, the switches (instance AND auction module), copies lost by a restore, housekeeping while the exchange is off, and staleness.
 */
abstract class AuctionPullPollerScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)

        fun switches(
            federation: Boolean = true,
            auction: Boolean = true,
        ) = transaction {
            AuctionFederationStore.setSettings(enabled = federation, hopLimit = 2)
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[auctionEnabled] = auction }
        }

        fun clean() {
            transaction {
                AuctionReceivedHintTable.deleteAll()
                AuctionReceivedTombstoneTable.deleteAll()
                AuctionTeaserTombstoneTable.deleteAll()
                PartnerDiscoveredServerTable.deleteAll()
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
            }
            switches()
        }
        beforeTest {
            clean()
            AuctionPullCounters.resetForTest()
        }
        afterSpec {
            try {
                clean()
                switches(federation = false, auction = false)
            } finally {
                db.deactivate()
            }
        }

        var nowRef = AUCTION_PULL_NOW
        val random = Random(7)

        fun poller(
            net: FakeNetwork,
            handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        ) = AuctionPullPoller(
            client =
                AuctionTeaserFeedClient(
                    resolveTarget = { publicTargetFor(it) },
                    clientFactory = fakeFetcherClientFactory(net = net, handler = handler),
                ),
            clock = { nowRef },
            random = random,
        )

        fun plus(
            at: LocalDateTime,
            d: Duration,
        ) = (at.toInstant(TimeZone.UTC) + d).toLocalDateTime(TimeZone.UTC)

        fun row(id: kotlin.uuid.Uuid) = transaction { PartnerServerStore.findPartner(id)!! }

        fun copies() = transaction { AuctionReceivedHintTable.selectAll().count() }

        test(
            "a due partner is pulled once: the hint is stored, the next pull is 30 to 40 minutes ahead, the ETag is kept, a second tick asks nothing",
        ) {
            nowRef = AUCTION_PULL_NOW
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = newAuctionPartner(o = o)
            val net = FakeNetwork()
            val poller = poller(net) { json(body = auctionFeedBytes(items = listOf(o.item(seed = "1"))), etag = "\"v1\"") }
            poller.tick()
            net.requests.get() shouldBe 1
            copies() shouldBe 1L
            val r = row(p.id)
            r.auctionPullStatus shouldBe "OK"
            r.auctionPullEtag shouldBe "\"v1\""
            r.auctionPullFailures shouldBe 0
            r.auctionPullLastAccepted shouldBe 1
            val next = r.auctionPullNextAt!!
            (next >= plus(AUCTION_PULL_NOW, 30.minutes) && next <= plus(AUCTION_PULL_NOW, 40.minutes)) shouldBe true
            poller.tick()
            net.requests.get() shouldBe 1
            AuctionPullCounters.accepted.get() shouldBe 1
        }

        test("the next pull is conditional and a 304 keeps the copies and the ETag") {
            nowRef = AUCTION_PULL_NOW
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = newAuctionPartner(o = o)
            var seen: String? = null
            val poller =
                poller(FakeNetwork()) { request ->
                    seen = request.headers[HttpHeaders.IfNoneMatch]
                    if (seen ==
                        null
                    ) {
                        json(body = auctionFeedBytes(items = listOf(o.item(seed = "1"))), etag = "\"v1\"")
                    } else {
                        respond("", HttpStatusCode.NotModified)
                    }
                }
            poller.tick()
            nowRef = plus(AUCTION_PULL_NOW, 1.hours)
            poller.tick()
            seen shouldBe "\"v1\""
            copies() shouldBe 1L
            row(p.id).let {
                it.auctionPullStatus shouldBe "NOT_MODIFIED"
                it.auctionPullEtag shouldBe "\"v1\""
            }
        }

        test("a 304 after a restore (ETag back, copies gone) forgets the ETag and retries in a minute") {
            nowRef = AUCTION_PULL_NOW
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = newAuctionPartner(o = o)
            val poller =
                poller(FakeNetwork()) { request ->
                    if (request.headers[HttpHeaders.IfNoneMatch] ==
                        null
                    ) {
                        json(body = auctionFeedBytes(items = listOf(o.item(seed = "1"))), etag = "\"v1\"")
                    } else {
                        respond("", HttpStatusCode.NotModified)
                    }
                }
            poller.tick()
            transaction { AuctionReceivedHintTable.deleteAll() }
            nowRef = plus(AUCTION_PULL_NOW, 1.hours)
            poller.tick()
            row(p.id).auctionPullEtag shouldBe null
            (row(p.id).auctionPullNextAt!! <= plus(nowRef, 2.minutes)) shouldBe true
        }

        test("a failure backs off 15 minutes, then 30; the copies of the last success stay until they are stale (24 hours)") {
            nowRef = AUCTION_PULL_NOW
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = newAuctionPartner(o = o)
            var fail = false
            val poller =
                poller(FakeNetwork()) {
                    if (fail) {
                        json(
                            body = ByteArray(0),
                            status = HttpStatusCode.InternalServerError,
                        )
                    } else {
                        json(body = auctionFeedBytes(items = listOf(o.item(seed = "1"))))
                    }
                }
            poller.tick()
            fail = true
            nowRef = plus(AUCTION_PULL_NOW, 1.hours)
            poller.tick()
            row(p.id).let {
                it.auctionPullFailures shouldBe 1
                it.auctionPullError shouldBe "HTTP_STATUS"
                val delay = it.auctionPullNextAt!!.toInstant(TimeZone.UTC) - nowRef.toInstant(TimeZone.UTC)
                (delay >= 15.minutes && delay <= 25.minutes) shouldBe true
            }
            nowRef = plus(nowRef, 30.minutes)
            poller.tick()
            row(p.id).auctionPullFailures shouldBe 2
            copies() shouldBe 1L
            nowRef = plus(AUCTION_PULL_NOW, 26.hours)
            poller.tick()
            copies() shouldBe 0L
        }

        test("with the instance switch OR the auction module off nothing is requested; the housekeeping still runs") {
            nowRef = AUCTION_PULL_NOW
            val o = AuctionOriginServer(host = "a.beispiel.de")
            newAuctionPartner(o = o)
            val net = FakeNetwork()
            val poller =
                poller(
                    net,
                ) { json(body = auctionFeedBytes(items = listOf(o.item(seed = "short", teaser = o.teaser(seed = "short", endsIn = 3))))) }
            poller.tick()
            copies() shouldBe 1L
            net.requests.get() shouldBe 1
            switches(federation = false)
            // the hint has ended 5 hours later: the sweep deletes it although the exchange is off
            nowRef = plus(AUCTION_PULL_NOW, 5.hours)
            poller.tick()
            net.requests.get() shouldBe 1
            copies() shouldBe 0L
            switches(auction = false)
            poller.tick()
            net.requests.get() shouldBe 1
        }

        test("a partner without auction sharing, without a pinned key or with a pending key is not pulled") {
            nowRef = AUCTION_PULL_NOW
            val net = FakeNetwork()
            val poller = poller(net) { json(body = auctionFeedBytes(items = emptyList())) }
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val b = AuctionOriginServer(host = "b2.beispiel.de")
            newAuctionPartner(o = a, sharing = false)
            val pb = newAuctionPartner(o = b)
            transaction { PartnerServerTable.update({ PartnerServerTable.id eq pb.id }) { it[signingKeyChangedAt] = AUCTION_PULL_NOW } }
            poller.tick()
            net.requests.get() shouldBe 0
        }

        test("a hint of the feed that was rejected is counted, a feed with the wrong kind fails the pull with SCHEMA") {
            nowRef = AUCTION_PULL_NOW
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = newAuctionPartner(o = o)
            val poller =
                poller(
                    FakeNetwork(),
                ) { json(body = """{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[]}""".toByteArray()) }
            poller.tick()
            row(p.id).let {
                it.auctionPullStatus shouldBe "FAILED"
                it.auctionPullError shouldBe "SCHEMA"
            }
        }
    })
