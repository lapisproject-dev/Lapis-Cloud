package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionReceivedStore
import network.lapis.cloud.server.auction.federation.pull.AuctionOriginServer
import network.lapis.cloud.server.auction.federation.pull.applyAuctionFeed
import network.lapis.cloud.server.auction.federation.pull.auctionFeedBytes
import network.lapis.cloud.server.auction.federation.pull.hexId
import network.lapis.cloud.server.auction.federation.pull.newAuctionPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.gallery.GalleryApp
import network.lapis.cloud.server.gallery.GalleryFixtures
import network.lapis.cloud.server.gallery.galleryTestApplication
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.hours

private const val ORIGIN_HOST = "a.beispiel.de"

/**
 * V1.9.322 c3 -- the two RPC methods of the receiving side: who may see the partner auctions, the empty answer while a switch is off, the page check
 * AFTER the gate, who may hide, the uniform not-found, and the audit entry without title and amount. H2 AND PostgreSQL.
 */
abstract class AuctionPartnerHintsScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = GalleryFixtures()

        fun svc(call: io.ktor.server.application.ApplicationCall) = AuctionService(call = call)

        fun switches(
            federation: Boolean = true,
            auction: Boolean = true,
        ) {
            transaction { AuctionFederationStore.setSettings(enabled = federation, hopLimit = 2) }
            fx.setAuctionEnabled(auction)
        }

        fun clean() {
            transaction {
                AuctionReceivedHintTable.deleteAll()
                AuctionReceivedTombstoneTable.deleteAll()
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
            }
        }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeEach {
            clean()
            switches()
        }
        afterSpec {
            try {
                clean()
                transaction { AuctionFederationStore.setSettings(enabled = false, hopLimit = 1) }
                fx.setAuctionEnabled(false)
                fx.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun seed(count: Int = 1): AuctionOriginServer {
            val o = AuctionOriginServer(host = ORIGIN_HOST)
            val p = newAuctionPartner(o = o)
            val now = DbClock.nowLocalDateTime()
            val base = now.toInstant(TimeZone.UTC).let { kotlin.time.Instant.fromEpochSeconds(it.epochSeconds / 60 * 60) }
            val items =
                (1..count).map { n ->
                    o.item(seed = "h$n", teaser = o.teaser(seed = "h$n", endsIn = 10 + n, base = base, title = "Lastenrad $n"))
                }
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = items), now = now).applied shouldBe true
            return o
        }

        suspend fun GalleryApp.refused(
            member: kotlin.uuid.Uuid,
            op: suspend (io.ktor.server.application.ApplicationCall) -> Any?,
        ): Throwable = attempt(member = member, op = op).exceptionOrNull() ?: error("expected a refusal")

        test("an ACTIVE member sees the hints with exactly the DTO fields; guest, friend and donor are refused") {
            seed(count = 2)
            val member = fx.member(label = "m")
            val guest = fx.member(label = "g", status = MemberStatus.GUEST)
            val friend = fx.member(label = "f", status = MemberStatus.FRIEND)
            val donor = fx.member(label = "d", status = MemberStatus.DONOR)
            galleryTestApplication(fixtures = fx) {
                val page = call(member = member) { svc(it).listPartnerAuctionHints(page = 0) }
                page.items.map { it.title } shouldBe listOf("Lastenrad 1", "Lastenrad 2")
                page.hasMore shouldBe false
                page.items.first().let {
                    it.originHost shouldBe ORIGIN_HOST
                    it.currency shouldBe "EUR"
                    it.priceKind shouldBe AuctionTeaserPriceKind.CURRENT
                    it.priceMinor shouldBe 2500
                }
                listOf(guest, friend, donor).forEach { who ->
                    refused(who) { svc(it).listPartnerAuctionHints(page = 0) }.shouldBeInstanceOf<ForbiddenException>()
                    refused(who) { svc(it).hidePartnerAuctionHint(originHost = ORIGIN_HOST, teaserId = hexId("h1")) }
                        .shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("an empty page while the exchange switch or the auction module is off; the page is checked AFTER the gate") {
            seed()
            val member = fx.member(label = "m")
            val guest = fx.member(label = "g", status = MemberStatus.GUEST)
            galleryTestApplication(fixtures = fx) {
                switches(federation = false)
                call(member = member) { svc(it).listPartnerAuctionHints(page = 0) }.items.shouldBeEmpty()
                switches(auction = false)
                call(member = member) { svc(it).listPartnerAuctionHints(page = 0) }.items.shouldBeEmpty()
                switches()
                refused(member) { svc(it).listPartnerAuctionHints(page = 20) }.shouldBeInstanceOf<BadRequestException>()
                refused(member) { svc(it).listPartnerAuctionHints(page = -1) }.shouldBeInstanceOf<BadRequestException>()
                // a non-member learns nothing about the page range
                refused(guest) { svc(it).listPartnerAuctionHints(page = 20) }.shouldBeInstanceOf<ForbiddenException>()
                call(member = member) { svc(it).listPartnerAuctionHints(page = 19) }.items.shouldBeEmpty()
            }
        }

        test("the availability flag partnerHintsVisible follows the switch and the auction module") {
            val member = fx.member(label = "m")
            galleryTestApplication(fixtures = fx) {
                call(member = member) { svc(it).getAuctionFederationAvailability() }.partnerHintsVisible shouldBe true
                switches(federation = false)
                call(member = member) { svc(it).getAuctionFederationAvailability() }.partnerHintsVisible shouldBe false
                switches(auction = false)
                call(member = member) { svc(it).getAuctionFederationAvailability() }.partnerHintsVisible shouldBe false
            }
        }

        test("only an ADMIN hides; unknown, malformed and never-held are ONE answer; the audit names host and id, never title or amount") {
            seed(count = 2)
            val member = fx.member(label = "m")
            val admin = fx.member(label = "a", role = AccountRole.ADMIN)
            galleryTestApplication(fixtures = fx) {
                refused(member) { svc(it).hidePartnerAuctionHint(originHost = ORIGIN_HOST, teaserId = hexId("h1")) }
                    .shouldBeInstanceOf<ForbiddenException>()
                val unknown = refused(admin) { svc(it).hidePartnerAuctionHint(originHost = ORIGIN_HOST, teaserId = hexId("zzz")) }
                val malformedHost =
                    refused(admin) { svc(it).hidePartnerAuctionHint(originHost = "A.Beispiel.de:8080", teaserId = hexId("h1")) }
                val malformedId = refused(admin) { svc(it).hidePartnerAuctionHint(originHost = ORIGIN_HOST, teaserId = "not-an-id") }
                listOf(unknown, malformedHost, malformedId).forEach { it.shouldBeInstanceOf<NotFoundException>() }
                unknown.message shouldBe malformedHost.message
                unknown.message shouldBe malformedId.message
                call(member = admin) { svc(it).hidePartnerAuctionHint(originHost = ORIGIN_HOST, teaserId = hexId("h1")) }
                call(member = member) { svc(it).listPartnerAuctionHints(page = 0) }.items.map { it.title } shouldBe listOf("Lastenrad 2")
                transaction { AuctionReceivedTombstoneTable.selectAll().map { it[AuctionReceivedTombstoneTable.kind] } } shouldBe
                    listOf("HIDDEN")
                val audit =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTNER_SERVER }
                            .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
                            .filter { "AUCTION_RECEIVED_HINT_HIDDEN" in it }
                    }
                audit shouldHaveSize 1
                audit.single().let {
                    it shouldNotContain "Lastenrad"
                    it shouldNotContain "2500"
                    (ORIGIN_HOST in it) shouldBe true
                }
                // hiding the same hint again: it is gone, the answer is the uniform one
                refused(
                    admin,
                ) {
                    svc(
                        it,
                    ).hidePartnerAuctionHint(originHost = ORIGIN_HOST, teaserId = hexId("h1"))
                }.shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("a hint that ended is not listed even if the row is still stored") {
            val o = seed()
            val member = fx.member(label = "m")
            transaction {
                AuctionReceivedHintTable.update({ AuctionReceivedHintTable.originHost eq o.host }) {
                    it[endsAt] =
                        DbClock.nowLocalDateTime().toInstant(TimeZone.UTC).minus(1.hours).let { i ->
                            kotlinx.datetime.LocalDateTime.parse(i.toString().removeSuffix("Z"))
                        }
                }
            }
            galleryTestApplication(fixtures = fx) {
                call(member = member) { svc(it).listPartnerAuctionHints(page = 0) }.items.shouldBeEmpty()
                transaction { AuctionReceivedStore.countStoredRows() } shouldBe 1
            }
        }
    })
