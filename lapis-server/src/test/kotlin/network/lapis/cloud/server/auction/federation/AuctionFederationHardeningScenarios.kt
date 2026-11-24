package network.lapis.cloud.server.auction.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.carpool.feed.TEST_BOX
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionPaymentDetailsTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.dsgvo.AuctionPersonalData
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private val IDS = AuctionTeaserIds(TEST_BOX)

/** The largest possible id: sorts after every random id, so the "inactive seller" row is always beyond the first page of a limited scan. */
private val LAST_ID = Uuid.parse("ffffffff-ffff-4fff-bfff-ffffffffffff")

/**
 * V1.9.322 (step c4) -- hardening of the auction hint exchange on H2 AND PostgreSQL: the member-exit sweep without starvation, the retention of
 * snapshots, the withdrawal after a restore, the stale-consent reset, and the GDPR export/erasure of the consent.
 */
abstract class AuctionFederationHardeningScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)

        val auctions = mutableListOf<Uuid>()
        val members = mutableListOf<Uuid>()

        fun member(
            status: MemberStatus = MemberStatus.ACTIVE,
            anonymized: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Hardening Member"
                    it[email] = "hardening-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = null
                }
                if (anonymized) {
                    MemberTable.update({ MemberTable.id eq id }) { it[anonymizedAt] = DbClock.nowLocalDateTime() }
                }
            }
            members += id
            return id
        }

        fun auction(
            seller: Uuid,
            id: Uuid = Uuid.random(),
            consent: Boolean = true,
            winner: Uuid? = null,
            consentAt: LocalDateTime = DbClock.nowLocalDateTime(),
        ): Uuid {
            val now = DbClock.nowLocalDateTime()
            transaction {
                AuctionTable.insert {
                    it[AuctionTable.id] = id
                    it[title] = "Hardening Auction"
                    it[description] = "desc"
                    it[status] = AuctionStatus.OPEN
                    it[sellerMemberId] = seller
                    it[winnerMemberId] = winner
                    it[listingFeeLtr] = BigDecimal("0.00")
                    it[createdAt] = now
                    it[endsAt] = now.toInstant(TimeZone.UTC).plus(48.hours).toLocalDateTime(TimeZone.UTC)
                    it[currency] = "EUR"
                    it[startingPriceMinor] = 1000L
                    it[sellerDisclaimerVersion] = "test"
                }
                if (consent) AuctionFederationStore.setOptIn(auctionId = id, value = true, now = consentAt)
            }
            auctions += id
            return id
        }

        fun snapshot(
            auctionId: Uuid,
            at: LocalDateTime,
        ) = transaction {
            AuctionFederationStore.upsertSnapshotOnConsent(
                auctionId = auctionId,
                teaserId = IDS.of(auctionId),
                priceKind = AuctionTeaserPriceKind.START,
                priceMinor = 1000,
                priceAsOf = at,
                contentSha256 = "00".repeat(32),
                now = at,
            )
        }

        fun optIn(id: Uuid) = transaction { AuctionFederationStore.optIn(id) }

        fun globalTombstones(auctionId: Uuid): Long =
            transaction {
                AuctionTeaserTombstoneTable
                    .selectAll()
                    .where {
                        (
                            AuctionTeaserTombstoneTable.teaserId eq
                                IDS.of(
                                    auctionId,
                                )
                        ) and AuctionTeaserTombstoneTable.partnerId.isNull()
                    }.count()
            }

        fun cleanUp() =
            transaction {
                if (auctions.isNotEmpty()) {
                    AuctionPaymentDetailsTable.deleteWhere { AuctionPaymentDetailsTable.auctionId inList auctions }
                    AuctionTeaserSnapshotTable.deleteWhere { AuctionTeaserSnapshotTable.auctionId inList auctions }
                    AuctionTable.deleteWhere { AuctionTable.id inList auctions }
                }
                AuctionTeaserTombstoneTable.deleteWhere { AuctionTeaserTombstoneTable.id neq Uuid.NIL }
                PartnerServerTable.update({ PartnerServerTable.auctionFeedServedAt.isNotNull() }) { it[auctionFeedServedAt] = null }
                auctions.clear()
                if (members.isNotEmpty()) MemberTable.deleteWhere { MemberTable.id inList members }
                members.clear()
                AuctionFederationStore.setSettings(enabled = false, hopLimit = 1)
            }
        beforeTest {
            cleanUp()
            AuctionFederationKey.resetForTest()
            AuctionFederationKey.configure(IDS)
        }
        afterSpec {
            try {
                AuctionFederationKey.resetForTest()
                cleanUp()
            } finally {
                db.deactivate()
            }
        }

        test(
            "the member-exit sweep finds the auction of a withdrawn seller although more than the scan limit of auctions of active sellers come first",
        ) {
            val active = member()
            val gone = member(status = MemberStatus.WITHDRAWN)
            repeat(60) { auction(seller = active) }
            val lost = auction(seller = gone, id = LAST_ID)
            val withdrawn = transaction { AuctionWithdrawals.sweep(now = DbClock.nowLocalDateTime(), ids = IDS, limit = 50) }
            withdrawn shouldBe 1
            optIn(lost) shouldBe false
            auctions.filter { it != lost }.all { optIn(it) } shouldBe true
        }

        test("the sweep also catches an anonymized seller and never an active one") {
            val active = member()
            val anon = member(anonymized = true)
            val a = auction(seller = active)
            val b = auction(seller = anon)
            transaction { AuctionWithdrawals.sweep(now = DbClock.nowLocalDateTime(), ids = IDS, limit = 50) } shouldBe 1
            optIn(a) shouldBe true
            optIn(b) shouldBe false
        }

        test("a snapshot without consent is deleted after 14 days, a younger one and one with consent stay, the limit holds") {
            val now = DbClock.nowLocalDateTime()
            val seller = member()

            fun ago(days: Int) = now.toInstant(TimeZone.UTC).minus(days.days).toLocalDateTime(TimeZone.UTC)
            val old = auction(seller = seller, consent = false)
            val young = auction(seller = seller, consent = false)
            val consented = auction(seller = seller, consent = true)
            val old2 = auction(seller = seller, consent = false)
            val oldRevision = snapshot(old, ago(15)).revision
            snapshot(young, ago(13))
            snapshot(consented, ago(20))
            snapshot(old2, ago(16))
            transaction { AuctionFederationStore.deleteStaleSnapshots(now = now, limit = 1) } shouldBe 1
            transaction { AuctionFederationStore.deleteStaleSnapshots(now = now, limit = 10) } shouldBe 1
            transaction { AuctionFederationStore.snapshot(old) } shouldBe null
            transaction { AuctionFederationStore.snapshot(old2) } shouldBe null
            (transaction { AuctionFederationStore.snapshot(young) } != null) shouldBe true
            (transaction { AuctionFederationStore.snapshot(consented) } != null) shouldBe true
            // consenting again after the delete: the revision never falls behind the old one
            val again = snapshot(old, now)
            (again.revision > oldRevision) shouldBe true
        }

        test("withdrawAfterRestore writes one global tombstone per target, is idempotent, and writes nothing without the key") {
            val seller = member()
            val a = auction(seller = seller, consent = false)
            val b = auction(seller = seller, consent = false)
            val now = DbClock.nowLocalDateTime()
            val targets =
                transaction {
                    AuctionTable
                        .selectAll()
                        .where { AuctionTable.id inList listOf(a, b) }
                        .map { r -> AuctionWithdrawalTarget(auctionId = r[AuctionTable.id], endsAt = r[AuctionTable.endsAt]) }
                }
            transaction { AuctionFederationStore.withdrawAfterRestore(targets = targets, now = now) } shouldBe 2
            globalTombstones(a) shouldBe 1L
            globalTombstones(b) shouldBe 1L
            transaction { AuctionFederationStore.withdrawAfterRestore(targets = targets, now = now) } shouldBe 0
            AuctionFederationKey.configure(null)
            val c = auction(seller = seller, consent = false)
            val other =
                transaction {
                    AuctionTable
                        .selectAll()
                        .where { AuctionTable.id eq c }
                        .map { r -> AuctionWithdrawalTarget(auctionId = r[AuctionTable.id], endsAt = r[AuctionTable.endsAt]) }
                }
            transaction { AuctionFederationStore.withdrawAfterRestore(targets = other, now = now) } shouldBe 0
        }

        test("resetAfterRestore also clears a stale consent moment on a row without consent") {
            val seller = member()
            val a = auction(seller = seller, consent = false)
            transaction { AuctionTable.update({ AuctionTable.id eq a }) { it[federationOptInAt] = DbClock.nowLocalDateTime() } }
            transaction { AuctionFederationStore.resetAfterRestore(DbClock.nowLocalDateTime()) } shouldBe false
            transaction { AuctionFederationStore.optInAt(a) } shouldBe null
        }

        test("the export shows the seller the consent, never the winner; the erasure withdraws the hints first and ends the consent") {
            val seller = member()
            val winner = member()
            val stranger = member()
            val consentAt = DbClock.nowLocalDateTime().let { it.toInstant(TimeZone.UTC).minus(1.hours).toLocalDateTime(TimeZone.UTC) }
            val mine = auction(seller = seller, winner = winner, consentAt = consentAt)
            val mine2 = auction(seller = seller)
            val foreign = auction(seller = stranger)
            // a partner fetched the feed after the consents were given: the hints were handed out
            val partner =
                network.lapis.cloud.server.carpool.feed
                    .newFeedPartner()
            transaction { PartnerServerStore.recordAuctionFeedServed(id = partner.id, itemCount = 3, now = DbClock.nowLocalDateTime()) }

            val sellerExport = transaction { AuctionPersonalData.exportMember(seller) }.jsonObject
            val sold = sellerExport.getValue("auctionsSold").jsonArray.map { it.jsonObject }
            sold.size shouldBe 2
            sold.all { it.getValue("sharedWithPartnerServers").jsonPrimitive.content == "true" } shouldBe true
            val winnerExport = transaction { AuctionPersonalData.exportMember(winner) }.jsonObject
            val won =
                winnerExport
                    .getValue("auctionsWon")
                    .jsonArray
                    .single()
                    .jsonObject
            won.containsKey("sharedWithPartnerServers") shouldBe false
            won.containsKey("sharedWithPartnerServersSince") shouldBe false

            transaction { AuctionPersonalData.eraseMember(memberId = seller, mode = ErasureMode.ANONYMIZE) }
            optIn(mine) shouldBe false
            optIn(mine2) shouldBe false
            transaction { AuctionFederationStore.optInAt(mine) } shouldBe null
            globalTombstones(mine) shouldBe 1L
            globalTombstones(mine2) shouldBe 1L
            // the auction row is retained, a foreign auction is untouched
            transaction { AuctionTable.selectAll().where { AuctionTable.id eq mine }.count() } shouldBe 1L
            optIn(foreign) shouldBe true
            globalTombstones(foreign) shouldBe 0L
            transaction { PartnerServerTable.deleteWhere { PartnerServerTable.id eq partner.id } }
        }

        test("the erasure without the teaser key still ends the consent and does not fail") {
            val seller = member()
            val a = auction(seller = seller)
            AuctionFederationKey.configure(null)
            transaction { AuctionPersonalData.eraseMember(memberId = seller, mode = ErasureMode.ANONYMIZE) }
            optIn(a) shouldBe false
        }
    })
