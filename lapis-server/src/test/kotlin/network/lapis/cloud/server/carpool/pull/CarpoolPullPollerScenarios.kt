package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.partner.FakeNetwork
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.fakeFetcherClientFactory
import network.lapis.cloud.server.partner.json
import network.lapis.cloud.server.partner.publicTargetFor
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.314 W4 -- the pull poller tick on H2 AND PostgreSQL with a fake network: what it pulls and when, the schedule after a success and a failure, a `304`,
 * the switches, the missing own key, copies lost by a restore, and the staleness sweep. The request itself is covered by [CarpoolTeaserFeedClientTest].
 */
abstract class CarpoolPullPollerScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)

        fun clean() =
            transaction {
                CarpoolReceivedHintTable.deleteAll()
                CarpoolReceivedTombstoneTable.deleteAll()
                PartnerDiscoveredServerTable.deleteAll()
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }
        beforeTest { clean() }
        afterSpec {
            try {
                clean()
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
            } finally {
                db.deactivate()
            }
        }

        var nowRef = PULL_NOW
        val random = Random(7)

        fun poller(
            net: FakeNetwork,
            handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        ) = CarpoolPullPoller(
            client =
                CarpoolTeaserFeedClient(
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

        val departure = OrganizationTimeZone.today().plus(DatePeriod(days = 5))

        fun partnerRow(id: kotlin.uuid.Uuid) = transaction { PartnerServerStore.findPartner(id)!! }

        fun copies() = transaction { CarpoolReceivedHintTable.selectAll().count() }

        test(
            "a due partner is pulled once: the hint is stored, the schedule is 30 to 40 minutes ahead, the ETag is kept, a second tick in between asks nothing",
        ) {
            nowRef = PULL_NOW
            val o = OriginServer(host = "a.beispiel.de")
            val p = newFeedPartner(host = o.host, keyPair = o.keyPair)
            val net = FakeNetwork()
            val poller = poller(net) { json(body = feedBytes(items = listOf(o.item(seed = "1", date = departure))), etag = "\"v1\"") }
            poller.tick()
            net.requests.get() shouldBe 1
            copies() shouldBe 1L
            val row = partnerRow(p.id)
            row.carpoolPullStatus shouldBe "OK"
            row.carpoolPullEtag shouldBe "\"v1\""
            row.carpoolPullFailures shouldBe 0
            row.carpoolPullLastAccepted shouldBe 1
            val next = row.carpoolPullNextAt!!
            (next >= plus(PULL_NOW, 30.minutes) && next <= plus(PULL_NOW, 40.minutes)) shouldBe true
            poller.tick()
            net.requests.get() shouldBe 1
        }

        test("the next pull is conditional and a 304 keeps the copies and the ETag") {
            nowRef = PULL_NOW
            val o = OriginServer(host = "a.beispiel.de")
            val p = newFeedPartner(host = o.host, keyPair = o.keyPair)
            var seenEtag: String? = null
            val net = FakeNetwork()
            val poller =
                poller(net) { request ->
                    seenEtag = request.headers[HttpHeaders.IfNoneMatch]
                    if (seenEtag ==
                        null
                    ) {
                        json(body = feedBytes(items = listOf(o.item(seed = "1", date = departure))), etag = "\"v1\"")
                    } else {
                        respond("", HttpStatusCode.NotModified)
                    }
                }
            poller.tick()
            nowRef = plus(PULL_NOW, 1.hours)
            poller.tick()
            seenEtag shouldBe "\"v1\""
            copies() shouldBe 1L
            partnerRow(p.id).let {
                it.carpoolPullStatus shouldBe "NOT_MODIFIED"
                it.carpoolPullEtag shouldBe "\"v1\""
            }
        }

        test("a failure backs off 15 minutes, then 30; the copies of the last success stay until they are stale") {
            nowRef = PULL_NOW
            val o = OriginServer(host = "a.beispiel.de")
            val p = newFeedPartner(host = o.host, keyPair = o.keyPair)
            var fail = false
            val poller =
                poller(FakeNetwork()) {
                    if (fail) {
                        json(
                            body = ByteArray(0),
                            status = HttpStatusCode.InternalServerError,
                        )
                    } else {
                        json(body = feedBytes(items = listOf(o.item(seed = "1", date = departure))))
                    }
                }
            poller.tick()
            fail = true
            nowRef = plus(PULL_NOW, 1.hours)
            poller.tick()
            partnerRow(p.id).let {
                it.carpoolPullFailures shouldBe 1
                it.carpoolPullError shouldBe "HTTP_STATUS"
                val delay = it.carpoolPullNextAt!!.toInstant(TimeZone.UTC) - nowRef.toInstant(TimeZone.UTC)
                (delay >= 15.minutes && delay <= 25.minutes) shouldBe true
            }
            nowRef = plus(nowRef, 30.minutes)
            poller.tick()
            partnerRow(p.id).let {
                it.carpoolPullFailures shouldBe 2
                val delay = it.carpoolPullNextAt!!.toInstant(TimeZone.UTC) - nowRef.toInstant(TimeZone.UTC)
                (delay >= 30.minutes && delay <= 40.minutes) shouldBe true
            }
            copies() shouldBe 1L
            // 25 hours after the last success the tick drops the copies (the pass-on of withdrawals stays timely)
            nowRef = plus(PULL_NOW, 26.hours)
            poller.tick()
            copies() shouldBe 0L
        }

        test("with the instance switch off nothing is requested; a partner without sharing or without a pinned key is not pulled") {
            nowRef = PULL_NOW
            val net = FakeNetwork()
            val poller = poller(net) { json(body = feedBytes(items = emptyList())) }
            val o = OriginServer(host = "a.beispiel.de")
            newFeedPartner(host = o.host, keyPair = o.keyPair)
            transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
            poller.tick()
            net.requests.get() shouldBe 0
            transaction { CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2) }
            transaction { PartnerServerTable.deleteAll() }
            newFeedPartner(host = "nosharing.beispiel.de", sharing = false)
            newFeedPartner(host = "nokey.beispiel.de", pinned = false)
            newFeedPartner(host = "inactive.beispiel.de", active = false)
            poller.tick()
            net.requests.get() shouldBe 0
        }

        test("without an own signing key no request is made and the partner records NO_OWN_KEY") {
            nowRef = PULL_NOW
            val o = OriginServer(host = "a.beispiel.de")
            val p = newFeedPartner(host = o.host, keyPair = o.keyPair)
            val net = FakeNetwork()
            val poller = poller(net) { json(body = feedBytes(items = emptyList())) }
            transaction { FederationActorKeyTable.deleteAll() }
            try {
                poller.tick()
            } finally {
                provisionOwnSigningKey()
            }
            net.requests.get() shouldBe 0
            partnerRow(p.id).carpoolPullError shouldBe "NO_OWN_KEY"
        }

        test("a restore brings the ETag back but not the copies: a 304 then clears the ETag and asks again at once") {
            nowRef = PULL_NOW
            val o = OriginServer(host = "a.beispiel.de")
            val p = newFeedPartner(host = o.host, keyPair = o.keyPair)
            var requests = 0
            val poller =
                poller(FakeNetwork()) { request ->
                    requests++
                    if (request.headers[HttpHeaders.IfNoneMatch] == null) {
                        json(body = feedBytes(items = listOf(o.item(seed = "1", date = departure))), etag = "\"v1\"")
                    } else {
                        respond("", HttpStatusCode.NotModified)
                    }
                }
            poller.tick()
            transaction { CarpoolReceivedHintTable.deleteAll() } // what a restore does to the unbacked-up cache
            nowRef = plus(PULL_NOW, 1.hours)
            poller.tick()
            partnerRow(p.id).carpoolPullEtag shouldBe null
            nowRef = plus(nowRef, 2.minutes)
            poller.tick()
            copies() shouldBe 1L
            requests shouldBe 3
        }

        test("an unusable answer (wrong schema) is a failure with its code; nothing is stored") {
            nowRef = PULL_NOW
            val o = OriginServer(host = "a.beispiel.de")
            val p = newFeedPartner(host = o.host, keyPair = o.keyPair)
            val poller =
                poller(
                    FakeNetwork(),
                ) { json(body = """{"schemaVersion":9,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[]}""".toByteArray()) }
            poller.tick()
            partnerRow(p.id).carpoolPullError shouldBe "SCHEMA"
            copies() shouldBe 0L
        }
    })
