package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolPullCounters
import network.lapis.cloud.server.carpool.CarpoolReceivedStore
import network.lapis.cloud.server.carpool.clearTombstones
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.carpool.pull.OriginServer
import network.lapis.cloud.server.carpool.pull.PULL_NOW
import network.lapis.cloud.server.carpool.pull.applyFeed
import network.lapis.cloud.server.carpool.pull.feedBytes
import network.lapis.cloud.server.carpool.pull.hexId
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.PartnerServerService
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolFederationSettingsDto
import network.lapis.cloud.shared.domain.CarpoolPullState
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
import network.lapis.cloud.shared.domain.PartnerServerOverviewDto
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.318 (wave 6) -- what the administrator sees and what the partner switches do, through a real RPC call context on H2: the pull state, the counters,
 * the immediate deletion on deactivation and the reset that "sharing off and on" stands for. The denial for non-administrators of both reads is pinned by
 * `PartnerServerServiceScenarioTest` (every method refuses MEMBER and BOARD).
 */
class CarpoolFederationOperationsAdminTest :
    FunSpec({
        val fx = CommitteeSeatFixture()

        fun clean() =
            transaction {
                CarpoolReceivedHintTable.deleteAll()
                CarpoolReceivedTombstoneTable.deleteAll()
                PartnerDiscoveredServerTable.deleteAll()
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }.also { clearTombstones() }

        // Its own in-memory database (like the store scenarios): the own signing key of the carpool feed must not leak into other test classes.
        val db = TestDatabase.H2
        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            clean()
            CarpoolPullCounters.resetForTest()
        }
        afterSpec {
            try {
                clean()
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun service(call: io.ktor.server.application.ApplicationCall) =
            PartnerServerService(
                call = call,
                probeRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
                fetcher = fakeFetcher { json(body = feedJson()) },
                publicBaseUrl = "https://own.example.com",
                clock = { PULL_NOW },
            )

        fun direct(o: OriginServer): FeedPartner = newFeedPartner(host = o.host, keyPair = o.keyPair)

        fun copies() = transaction { CarpoolReceivedHintTable.selectAll().count() }

        fun fail(
            partner: FeedPartner,
            times: Int,
        ) = transaction {
            repeat(times) {
                PartnerServerStore.recordCarpoolPullFailure(
                    id = partner.id,
                    error = "TIMEOUT",
                    next = LocalDateTime(2026, 11, 20, 10, 0, 0),
                    now = PULL_NOW,
                )
            }
        }

        fun overview(admin: Uuid): PartnerServerOverviewDto {
            var out: PartnerServerOverviewDto? = null
            withServiceCaller { out = call(caller = admin) { service(it).list() }.getOrThrow() }
            return requireNotNull(out)
        }

        fun settings(admin: Uuid): CarpoolFederationSettingsDto {
            var out: CarpoolFederationSettingsDto? = null
            withServiceCaller { out = call(caller = admin) { service(it).getCarpoolFederationSettings() }.getOrThrow() }
            return requireNotNull(out)
        }

        test("the pull state per partner follows the failure counter and shows only with sharing on and an active partner") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val a = direct(OriginServer(host = "a.beispiel.de"))
            val off = newFeedPartner(host = "off.beispiel.de", sharing = false)
            val paused = newFeedPartner(host = "paused.beispiel.de", active = false)
            overview(admin).partners.single { it.id == a.id.toString() }.carpoolPullState shouldBe CarpoolPullState.READY
            fail(partner = a, times = 3)
            overview(admin).partners.single { it.id == a.id.toString() }.carpoolPullState shouldBe CarpoolPullState.BACKING_OFF
            fail(partner = a, times = 5)
            val row = overview(admin).partners.single { it.id == a.id.toString() }
            row.carpoolPullState shouldBe CarpoolPullState.PAUSED
            row.carpoolPullFailures shouldBe 8
            row.carpoolPullNextAt shouldBe LocalDateTime(2026, 11, 20, 10, 0, 0)
            overview(admin).partners.single { it.id == off.id.toString() }.carpoolPullState shouldBe null
            overview(admin).partners.single { it.id == paused.id.toString() }.carpoolPullState shouldBe null
            overview(admin).partners.single { it.id == paused.id.toString() }.carpoolPullNextAt shouldBe null
        }

        test("deactivating a partner deletes its copies and its ETag at once; switching it on again starts with a clean slate") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "1"), o.item(seed = "2")), complete = false))
            copies() shouldBe 2L
            transaction { PartnerServerStore.findPartner(p.id)!!.carpoolPullEtag } shouldBe "\"e\""
            fail(partner = p, times = 9)
            withServiceCaller { call(caller = admin) { service(it).setActive(id = p.id.toString(), active = false) }.getOrThrow() }
            copies() shouldBe 0L
            transaction { PartnerServerStore.findPartner(p.id)!!.carpoolPullEtag } shouldBe null
            // still failing, but inactive: no state is shown
            overview(admin).partners.single { it.id == p.id.toString() }.carpoolPullState shouldBe null
            withServiceCaller { call(caller = admin) { service(it).setActive(id = p.id.toString(), active = true) }.getOrThrow() }
            val row = transaction { PartnerServerStore.findPartner(p.id)!! }
            row.carpoolPullFailures shouldBe 0
            row.carpoolPullNextAt shouldBe PULL_NOW
            overview(admin).partners.single { it.id == p.id.toString() }.carpoolPullState shouldBe CarpoolPullState.READY
        }

        test("switching sharing off deletes the copies, switching it on again resets the back-off, the next pull time and the ETag") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "1")), complete = false))
            fail(partner = p, times = 9)
            withServiceCaller {
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = p.id.toString(), enabled = false) }.getOrThrow()
            }
            copies() shouldBe 0L
            // already off: the failure history is kept until it is switched on again
            transaction { PartnerServerStore.findPartner(p.id)!!.carpoolPullFailures } shouldBe 9
            withServiceCaller {
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = p.id.toString(), enabled = true) }.getOrThrow()
            }
            val row = transaction { PartnerServerStore.findPartner(p.id)!! }
            row.carpoolPullFailures shouldBe 0
            row.carpoolPullNextAt shouldBe PULL_NOW
            row.carpoolPullEtag shouldBe null
            // switching on when it already is on changes nothing (no reset of a running back-off)
            fail(partner = p, times = 2)
            withServiceCaller {
                call(caller = admin) { service(it).setPartnerCarpoolSharing(partnerId = p.id.toString(), enabled = true) }.getOrThrow()
            }
            transaction { PartnerServerStore.findPartner(p.id)!!.carpoolPullFailures } shouldBe 2
        }

        test("removing a partner and blocking its host delete its copies at once") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val a = OriginServer(host = "a.beispiel.de")
            val b = OriginServer(host = "b4.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1")), complete = false))
            applyFeed(sourceId = pb.id, bytes = feedBytes(items = listOf(b.item(seed = "2")), complete = false))
            copies() shouldBe 2L
            withServiceCaller { call(caller = admin) { service(it).remove(pa.id.toString()) }.getOrThrow() }
            copies() shouldBe 1L
            withServiceCaller { call(caller = admin) { service(it).addBlock("b4.beispiel.de") }.getOrThrow() }
            copies() shouldBe 0L
        }

        test(
            "the counters: start, accepted, stored versus live, blocks (in total and per reporter), rejections by cause with every cause present",
        ) {
            val admin = fx.member(role = AccountRole.ADMIN)
            CarpoolPullCounters.markStarted(PULL_NOW)
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyFeed(
                sourceId = p.id,
                bytes =
                    feedBytes(
                        items = listOf(o.item(seed = "1"), o.item(seed = "2"), o.item(seed = "3", signature = "AAAA")),
                        complete = false,
                    ),
            )
            applyFeed(sourceId = p.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("2")), complete = false))
            val s = settings(admin)
            s.serverStartedAt shouldBe PULL_NOW
            s.carpoolReceivedAcceptedSinceStart shouldBe 2L
            s.carpoolReceivedStoredRows shouldBe 1
            s.carpoolReceivedHintsTotal shouldBe 1
            s.carpoolReceivedBlocksActive shouldBe 1
            s.carpoolReceivedRejectedSinceStart shouldBe 1L
            s.carpoolReceivedRejectedByReason.keys shouldBe CarpoolReceivedRejectReason.entries.map { it.name }.toSet()
            s.carpoolReceivedRejectedByReason.getValue(CarpoolReceivedRejectReason.SIGNATURE.name) shouldBe 1L
            s.carpoolReceivedRejectedByReason.values.sum() shouldBe s.carpoolReceivedRejectedSinceStart
            overview(admin).partners.single { it.id == p.id.toString() }.carpoolReceivedBlocks shouldBe 1
            // an expired row that the sweep has not deleted yet is the visible lag
            transaction { CarpoolReceivedStore.countStoredRows() } shouldBe 1
        }

        test("nothing of a hint leaves the server through the two admin reads: no origin host, no id, no signature") {
            val admin = fx.member(role = AccountRole.ADMIN)
            // a relayed hint of another origin whose key is unknown here: rejected, and its host appears in no counter
            val far = OriginServer(host = "farorigin.beispiel.de")
            val relay = direct(OriginServer(host = "relay.beispiel.de"))
            val unknown = far.item(seed = "secret-1", hops = 1, via = listOf("relay.beispiel.de"))
            applyFeed(sourceId = relay.id, bytes = feedBytes(items = listOf(unknown), complete = false)).accepted shouldBe 0
            // a stored hint of a direct partner
            val real = OriginServer(host = "real.beispiel.de")
            val pr = direct(real)
            val item = real.item(seed = "secret-2")
            applyFeed(sourceId = pr.id, bytes = feedBytes(items = listOf(item), complete = false)).accepted shouldBe 1
            val settingsJson = Json.encodeToString(CarpoolFederationSettingsDto.serializer(), settings(admin))
            val listJson = Json.encodeToString(PartnerServerOverviewDto.serializer(), overview(admin))
            settingsJson shouldNotContain "farorigin"
            settingsJson shouldNotContain "real.beispiel.de"
            listOf(settingsJson, listJson).forEach { text ->
                text shouldNotContain hexId("secret-1")
                text shouldNotContain hexId("secret-2")
                text shouldNotContain (unknown.originSignature ?: "")
                text shouldNotContain (item.originSignature ?: "")
                text shouldNotContain "farorigin"
            }
        }
    })
