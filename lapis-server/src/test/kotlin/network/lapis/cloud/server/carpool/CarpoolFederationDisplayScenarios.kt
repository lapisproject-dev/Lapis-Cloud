package network.lapis.cloud.server.carpool

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedGate
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.OWN_HOST
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.carpool.feed.signedGet
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.rpc.carpoolTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartnerCarpoolHintDto
import network.lapis.cloud.shared.domain.PartnerCarpoolHintLimits
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.317 "Mitfahrzentrale-Foederation" W5 -- what an ACTIVE member sees of the received hints and of the reach of an own hint, on H2 AND PostgreSQL.
 * The hint rows are written directly (test files may touch the received tables; only `src/main` is pinned by the tripwire).
 */
abstract class CarpoolFederationDisplayScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()

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
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        val json = Json

        fun today(): LocalDate = OrganizationTimeZone.today()

        fun nowUtc(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC)

        fun hex(seed: String) = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

        fun expiresOf(day: LocalDate) = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toLocalDateTime(TimeZone.UTC)

        /** Writes one received hint directly. */
        fun hint(
            seed: String,
            source: FeedPartner,
            day: LocalDate = today().plus(3, DateTimeUnit.DAY),
            from: String = "38",
            to: String = "30",
            origin: String = "o.beispiel.de",
            expires: LocalDateTime = expiresOf(day),
            receivedAt: LocalDateTime = nowUtc().let { LocalDateTime(it.year, it.month, it.day, 0, 0, 0) },
        ): String {
            val tid = hex(seed)
            transaction {
                CarpoolReceivedHintTable.insert {
                    it[id] = Uuid.random()
                    it[teaserId] = tid
                    it[originHost] = origin
                    it[hops] = 1.toShort()
                    it[maxHops] = 2.toShort()
                    it[viaHosts] = null
                    it[sourcePartnerId] = source.id
                    it[departureDate] = day
                    it[fromPostcodeArea] = from
                    it[toPostcodeArea] = to
                    it[originSignature] = "sig"
                    it[CarpoolReceivedHintTable.receivedAt] = receivedAt
                    it[lastSeenAt] = receivedAt
                    it[expiresAt] =
                        if (expires > receivedAt) expires else receivedAt.let { r -> LocalDateTime(r.year, r.month, r.day, 0, 0, 1) }
                }
            }
            return tid
        }

        fun list(
            now: LocalDateTime = nowUtc(),
            page: Int = 0,
            day: LocalDate = today(),
        ) = transaction { CarpoolReceivedStore.listForMembers(today = day, now = now, page = page) }

        fun origins(page: MemberHintPage) = page.items.map { it.originHost }

        test("excluded: expired, past day, a block of the SAME (origin, teaser); a block of another origin does not hide the hint") {
            val p = newFeedPartner()
            hint("keep", p, origin = "keep.beispiel.de")
            hint("expired", p, origin = "expired.beispiel.de", expires = nowUtc().let { LocalDateTime(it.year, it.month, it.day, 0, 0, 1) })
            hint("past", p, day = today().plus(-1, DateTimeUnit.DAY), origin = "past.beispiel.de", expires = nowUtc().let { it.plusHour() })
            val blockedId = hint("blocked", p, origin = "blocked.beispiel.de")
            val otherId = hint("other", p, origin = "other.beispiel.de")
            transaction {
                CarpoolReceivedTombstoneTable.insert {
                    it[id] = Uuid.random()
                    it[teaserId] = blockedId
                    it[originHost] = "blocked.beispiel.de"
                    it[reportedByPartnerId] = p.id
                    it[createdAt] = nowUtc()
                    it[expiresAt] = nowUtc().plusHour()
                }
                // same teaser id, ANOTHER origin: must not hide "other"
                CarpoolReceivedTombstoneTable.insert {
                    it[id] = Uuid.random()
                    it[teaserId] = otherId
                    it[originHost] = "someone-else.beispiel.de"
                    it[reportedByPartnerId] = p.id
                    it[createdAt] = nowUtc()
                    it[expiresAt] = nowUtc().plusHour()
                }
            }
            origins(list()) shouldContainExactly listOf("keep.beispiel.de", "other.beispiel.de")
        }

        test("many live tombstones that match no hint neither hide anything nor are needed (only candidates are looked up)") {
            val p = newFeedPartner()
            val keepId = hint("keep2", p, origin = "keep2.beispiel.de")
            val blockedId = hint("blocked2", p, origin = "blocked2.beispiel.de")
            transaction {
                repeat(1500) { i ->
                    CarpoolReceivedTombstoneTable.insert {
                        it[id] = Uuid.random()
                        it[teaserId] = hex("unmatched-$i")
                        it[originHost] = "gone.beispiel.de"
                        it[reportedByPartnerId] = p.id
                        it[createdAt] = nowUtc()
                        it[expiresAt] = nowUtc().plusHour()
                    }
                }
                CarpoolReceivedTombstoneTable.insert {
                    it[id] = Uuid.random()
                    it[teaserId] = blockedId
                    it[originHost] = "blocked2.beispiel.de"
                    it[reportedByPartnerId] = p.id
                    it[createdAt] = nowUtc()
                    it[expiresAt] = nowUtc().plusHour()
                }
            }
            keepId shouldNotBe blockedId
            origins(list()) shouldContainExactly listOf("keep2.beispiel.de")
        }

        test("an injected 'today' around midnight: yesterday is out, today is in") {
            val p = newFeedPartner()
            val d = today()
            hint("a", p, day = d, origin = "a.beispiel.de", expires = nowUtc().plusHour())
            origins(list(day = d)) shouldContainExactly listOf("a.beispiel.de")
            origins(list(day = d.plus(1, DateTimeUnit.DAY))).shouldBeEmpty()
        }

        test(
            "the source partner decides: inactive, sharing off, blocked or missing sources hide their copies; a blocked origin is hidden",
        ) {
            val good = newFeedPartner()
            val inactive = newFeedPartner(active = false)
            val noSharing = newFeedPartner(sharing = false)
            val blockedSource = newFeedPartner(host = "blocked-source.beispiel.de")
            hint("g", good, origin = "good.beispiel.de")
            hint("i", inactive, origin = "inactive.beispiel.de")
            hint("n", noSharing, origin = "nosharing.beispiel.de")
            hint("b", blockedSource, origin = "viablocked.beispiel.de")
            hint("o", good, origin = "bad-origin.beispiel.de")
            transaction {
                PartnerServerStore.addBlock(pattern = "blocked-source.beispiel.de", now = nowUtc())
                PartnerServerStore.addBlock(pattern = "bad-origin.beispiel.de", now = nowUtc())
            }
            // addBlock drops matching rows in the app flow only through other services; the filter must hold even if rows linger
            origins(list()) shouldContainExactly listOf("good.beispiel.de")
        }

        test("rows with a bad area or a bad host are skipped (the database already forbids other lengths)") {
            val p = newFeedPartner()
            hint("ok", p, origin = "ok.beispiel.de")
            hint("area1", p, from = "3a", origin = "area1.beispiel.de")
            hint("area2", p, to = "ab", origin = "area2.beispiel.de")
            hint("host1", p, origin = "<script>")
            hint("host2", p, origin = "a..b")
            hint("host3", p, origin = "UPPER.beispiel.de")
            hint("host4", p, origin = "x".repeat(70) + ".de")
            origins(list()) shouldContainExactly listOf("ok.beispiel.de")
        }

        test("order: day, from, to, origin host, then id; page 0 of 250 rows is 100 with more, the last page is 50 without") {
            val p = newFeedPartner()
            val d = today().plus(2, DateTimeUnit.DAY)
            hint("s1", p, day = d.plus(1, DateTimeUnit.DAY), from = "10", to = "10", origin = "z.beispiel.de")
            hint("s2", p, day = d, from = "20", to = "10", origin = "z.beispiel.de")
            hint("s3", p, day = d, from = "10", to = "20", origin = "z.beispiel.de")
            hint("s4", p, day = d, from = "10", to = "10", origin = "b.beispiel.de")
            hint("s5", p, day = d, from = "10", to = "10", origin = "a.beispiel.de")
            list().items.map { "${it.departureDate}|${it.fromPostcodeArea}|${it.toPostcodeArea}|${it.originHost}" } shouldContainExactly
                listOf(
                    "$d|10|10|a.beispiel.de",
                    "$d|10|10|b.beispiel.de",
                    "$d|10|20|z.beispiel.de",
                    "$d|20|10|z.beispiel.de",
                    "${d.plus(1, DateTimeUnit.DAY)}|10|10|z.beispiel.de",
                )
            clean()
            val q = newFeedPartner()
            (1..250).forEach { hint("m$it", q, origin = "h$it.beispiel.de", day = today().plus(1 + it / 100, DateTimeUnit.DAY)) }
            val sizes = (0..2).map { list(page = it) }
            sizes.map { it.items.size } shouldContainExactly listOf(100, 100, 50)
            sizes.map { it.hasMore } shouldContainExactly listOf(true, true, false)
        }

        test("hasMore is false on the last reachable page even when rows remain") {
            val p = newFeedPartner()
            (1..1100).forEach { n ->
                hint(
                    "big$n",
                    p,
                    origin = "h${n % 1000}.beispiel.de",
                    day = today().plus(1 + n / 200, DateTimeUnit.DAY),
                    from =
                        "%02d".format(
                            n % 100,
                        ),
                )
            }
            list(page = PartnerCarpoolHintLimits.MAX_PAGE).hasMore shouldBe false
            list(page = PartnerCarpoolHintLimits.MAX_PAGE - 1).hasMore shouldBe true
        }

        test(
            "the RPC: ACTIVE passes, FRIEND and GUEST are refused on both methods, a bad page is Forbidden for them but BadRequest for a member",
        ) {
            carpoolTestApplication {
                val member = data.member(label = "disp-member")
                val guest = data.member(label = "disp-guest", status = MemberStatus.GUEST)
                val friend = data.member(label = "disp-friend", status = MemberStatus.FRIEND)
                val p = newFeedPartner()
                hint("rpc", p, origin = "rpc.beispiel.de")
                call(member = member) { listPartnerCarpoolHints(0) }.items.map { it.originHost } shouldContainExactly
                    listOf("rpc.beispiel.de")
                call(member = member) { getCarpoolFederationReach() }.enabled shouldBe true
                listOf(guest, friend).forEach {
                    attempt(member = it) { listPartnerCarpoolHints(0) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = it) { listPartnerCarpoolHints(99) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = it) { getCarpoolFederationReach() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
                listOf(-1, 10).forEach {
                    attempt(member = member) { listPartnerCarpoolHints(it) }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                }
            }
        }

        test("the DTO has exactly four fields and its JSON carries nothing of the routing or the signature") {
            val d = PartnerCarpoolHintDto.serializer().descriptor
            (0 until d.elementsCount).map { d.getElementName(it) } shouldContainExactly
                listOf("departureDate", "fromPostcodeArea", "toPostcodeArea", "originHost")
            carpoolTestApplication {
                val member = data.member(label = "disp-json")
                val p = newFeedPartner()
                hint("json", p, origin = "json.beispiel.de")
                val text = json.encodeToString(call(member = member) { listPartnerCarpoolHints(0) })
                listOf("teaser", "hops", "via", "signature", "source", "expires", "sig").forEach { text shouldNotContain it }
            }
        }

        test("switching the exchange off empties the view and the reach, although rows are still stored") {
            carpoolTestApplication {
                val member = data.member(label = "disp-off")
                val p = newFeedPartner()
                hint("off", p, origin = "off.beispiel.de")
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                call(member = member) { listPartnerCarpoolHints(0) }.let {
                    it.items.shouldBeEmpty()
                    it.hasMore shouldBe false
                }
                call(member = member) { getCarpoolFederationReach() }.let {
                    it.enabled shouldBe false
                    it.partners.shouldBeEmpty()
                    it.maxHops shouldBe 0
                }
            }
        }

        test("reach: only partners that can really pull; label is the cleaned display name or the host, never the announced name") {
            carpoolTestApplication {
                val member = data.member(label = "disp-reach")
                val withName =
                    newFeedPartner(host = "named.beispiel.de", displayName = "  Verein\u0007 Süd ", remoteName = "ANNOUNCED-NAME")
                val noName = newFeedPartner(host = "plain.beispiel.de", remoteName = "SECRET-REMOTE")
                newFeedPartner(host = "nokey.beispiel.de", pinned = false)
                newFeedPartner(host = "nosharing.beispiel.de", sharing = false)
                newFeedPartner(host = "inactive.beispiel.de", active = false)
                val blocked = newFeedPartner(host = "blocked.beispiel.de")
                transaction { PartnerServerStore.addBlock(pattern = "blocked.beispiel.de", now = nowUtc()) }
                val reach = call(member = member) { getCarpoolFederationReach() }
                reach.partners.size shouldBe 2
                reach.partners shouldContainExactly reach.partners.sortedWith(compareBy({ it.lowercase() }, { it }))
                reach.partners shouldContainExactly listOf("plain.beispiel.de", "Verein Süd")
                reach.partners.joinToString() shouldNotContain "ANNOUNCED-NAME"
                reach.partners.joinToString() shouldNotContain "SECRET-REMOTE"
                reach.partnersTruncated shouldBe false
                // consistency: every listed partner really passes the feed gate's approval
                val gate = CarpoolTeaserFeedGate(replayGuard = FederationReplayGuard(), ownHost = OWN_HOST, clock = { Clock.System.now() })
                listOf(withName, noName).forEach { partner ->
                    val s = signedGet(partner = partner)
                    val verified =
                        gate.authenticate(
                            signature = s.signature,
                            date = s.date,
                            digest = s.digest,
                            contentLength = null,
                            transferEncoding = null,
                            queryString = "",
                        )!!
                    val settings = transaction { CarpoolFederationStore.settings() }
                    transaction { gate.approve(verified = verified, settings = settings) } shouldNotBe null
                }
                blocked.host shouldBe "blocked.beispiel.de"
            }
        }

        test("reach: capped at 50 with a truncation flag, and maxHops follows the hop limit") {
            carpoolTestApplication {
                val member = data.member(label = "disp-cap")
                (1..52).forEach { newFeedPartner(host = "p%02d.beispiel.de".format(it)) }
                val reach = call(member = member) { getCarpoolFederationReach() }
                reach.partners.size shouldBe PartnerCarpoolHintLimits.MAX_REACH_PARTNERS
                reach.partnersTruncated shouldBe true
                (1..CarpoolFederationLimits.MAX_HOPS).forEach { h ->
                    transaction { CarpoolFederationStore.setSettings(enabled = true, hopLimit = h) }
                    call(member = member) { getCarpoolFederationReach() }.maxHops shouldBe h
                }
            }
        }

        test("a hostile display name is cleaned") {
            carpoolTestApplication {
                val member = data.member(label = "disp-hostile")
                newFeedPartner(host = "h.beispiel.de", displayName = "A‮B\n" + "x".repeat(90))
                val label = call(member = member) { getCarpoolFederationReach() }.partners.single()
                (label.length <= 100) shouldBe true
                label shouldNotContain "A\u202E"
                label shouldNotContain "\n"
                label shouldNotContain "‮"
            }
        }
    })

private fun LocalDateTime.plusHour(): LocalDateTime = (this.toInstant(TimeZone.UTC) + 1.hours).toLocalDateTime(TimeZone.UTC)
