package network.lapis.cloud.server.auction.federation.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.auction.federation.AuctionFederationSettings
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionOriginSignatures
import network.lapis.cloud.server.auction.federation.AuctionPullCounters
import network.lapis.cloud.server.auction.federation.AuctionReceivedStore
import network.lapis.cloud.server.auction.federation.AuctionTeaserIds
import network.lapis.cloud.server.auction.federation.AuctionTeaserSnapshots
import network.lapis.cloud.server.auction.federation.AuctionTeaserTombstonePolicy
import network.lapis.cloud.server.auction.federation.feed.AUCTION_TEASER_FEED_JSON
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedBuilder
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedV1
import network.lapis.cloud.server.carpool.CarpoolOwnKeys
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.OWN_HOST
import network.lapis.cloud.server.carpool.feed.TEST_BOX
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuctionReceivedRejectReason
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * V1.9.322 c3 -- the store of received auction hints on H2 AND PostgreSQL: apply (signature, revision, hop rules, paths, caps), withdrawals and their
 * pass-on, HIDDEN blocks, the member read path, retention, switching off. Time is fixed ([AUCTION_PULL_NOW]).
 */
abstract class AuctionReceivedScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)

        fun settings(
            enabled: Boolean = true,
            hop: Int = 2,
            auction: Boolean = true,
        ) = transaction {
            AuctionFederationStore.setSettings(enabled = enabled, hopLimit = hop)
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
            settings()
        }
        beforeTest {
            clean()
            AuctionPullCounters.resetForTest()
        }
        afterSpec {
            try {
                clean()
                settings(enabled = false, hop = 1, auction = false)
            } finally {
                db.deactivate()
            }
        }

        class Row(
            val teaserId: String,
            val origin: String,
            val hops: Int,
            val maxHops: Int,
            val via: String?,
            val source: Uuid,
            val revision: Long,
            val title: String,
            val price: Long,
            val signature: String,
            val endsAt: LocalDateTime,
            val expires: LocalDateTime,
        )

        fun rows(): List<Row> =
            transaction {
                AuctionReceivedHintTable.selectAll().map {
                    Row(
                        teaserId = it[AuctionReceivedHintTable.teaserId],
                        origin = it[AuctionReceivedHintTable.originHost],
                        hops = it[AuctionReceivedHintTable.hops].toInt(),
                        maxHops = it[AuctionReceivedHintTable.maxHops].toInt(),
                        via = it[AuctionReceivedHintTable.viaHosts],
                        source = it[AuctionReceivedHintTable.sourcePartnerId],
                        revision = it[AuctionReceivedHintTable.revision],
                        title = it[AuctionReceivedHintTable.title],
                        price = it[AuctionReceivedHintTable.priceMinor],
                        signature = it[AuctionReceivedHintTable.originSignature],
                        endsAt = it[AuctionReceivedHintTable.endsAt],
                        expires = it[AuctionReceivedHintTable.expiresAt],
                    )
                }
            }

        fun ids() = rows().map { it.teaserId }.toSet()

        class Block(
            val teaserId: String,
            val kind: String,
            val reporter: Uuid?,
            val expires: LocalDateTime,
        )

        fun blocks() =
            transaction {
                AuctionReceivedTombstoneTable.selectAll().map {
                    Block(
                        teaserId = it[AuctionReceivedTombstoneTable.teaserId],
                        kind = it[AuctionReceivedTombstoneTable.kind],
                        reporter = it[AuctionReceivedTombstoneTable.reportedByPartnerId],
                        expires = it[AuctionReceivedTombstoneTable.expiresAt],
                    )
                }
            }

        fun passOnTombstones() =
            transaction {
                AuctionTeaserTombstoneTable
                    .selectAll()
                    .filter { it[AuctionTeaserTombstoneTable.relayOriginHost] != null }
                    .map { it[AuctionTeaserTombstoneTable.teaserId] }
            }

        fun direct(o: AuctionOriginServer): FeedPartner = newAuctionPartner(o = o)

        fun served(
            partnerId: Uuid,
            at: LocalDateTime,
        ) = transaction { PartnerServerStore.recordAuctionFeedServed(id = partnerId, itemCount = 1, now = at) }

        fun pinDiscovered(
            o: AuctionOriginServer,
            root: Uuid,
        ) = transaction {
            PartnerDiscoveredServerTable.insert {
                it[id] = Uuid.random()
                it[host] = o.host
                it[rootPartnerServerId] = root
                it[hops] = 2.toShort()
                it[createdAt] = AUCTION_PULL_NOW
                it[nextFetchAt] = AUCTION_PULL_NOW
                it[signingKeyId] = o.keyId
                it[signingPublicKeyPem] = o.keyPair.publicKeyPem
                it[signingKeyPinnedAt] = AUCTION_PULL_NOW
            }
        }

        val servedAfter = LocalDateTime(2026, 11, 24, 10, 5, 0)

        test("hop 1: the copy holds exactly the hint, one hop, the signature of the origin; the pull state is written last") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            val r = applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"))))
            r.applied shouldBe true
            r.accepted shouldBe 1
            r.rejected shouldBe 0
            val row = rows().single()
            row.teaserId shouldBe hexId("1")
            row.origin shouldBe "a.beispiel.de"
            row.hops shouldBe 1
            row.maxHops shouldBe 2
            row.via shouldBe null
            row.source shouldBe p.id
            row.revision shouldBe 5
            row.title shouldBe "Fahrrad 1"
            row.price shouldBe 2500
            row.expires shouldBe row.endsAt
            transaction { AuctionReceivedStore.countBySource()[p.id] } shouldBe 1
            transaction { PartnerServerStore.findPartner(p.id)!! }.let {
                it.auctionPullStatus shouldBe "OK"
                it.auctionPullEtag shouldBe "\"e\""
                it.auctionPullLastAccepted shouldBe 1
                it.auctionPullFailures shouldBe 0
            }
        }

        test("the stored copy is rebuilt EXACTLY as signed: a relay hands on a teaser that verifies against the origin key") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val other = AuctionOriginServer(host = "c.beispiel.de")
            val p = direct(o)
            val pc = direct(other)
            applyAuctionFeed(
                sourceId = p.id,
                bytes =
                    auctionFeedBytes(
                        items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", buyNowMinor = 9900, offersPossible = true))),
                    ),
            )
            val handed =
                transaction {
                    AuctionReceivedStore.republishableFor(
                        partner = pc.row(),
                        ownHopLimit = 2,
                        now = AUCTION_PULL_NOW,
                        limit = 10,
                    )
                }
            handed shouldHaveSize 1
            val h = handed.single()
            h.storedHops shouldBe 1
            AuctionOriginSignatures.verify(
                teaser = h.teaser,
                originHost = h.originHost,
                maxHops = h.maxHops,
                signatureB64 = h.originSignature,
                publicKeyPem = o.keyPair.publicKeyPem,
            ) shouldBe true
        }

        test("a forged price, a raised hop limit, a signature by another key and a garbage signature are rejected; nothing is stored") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val evil = AuctionOriginServer(host = "evil.beispiel.de")
            val p = direct(o)
            val good = o.item(seed = "1")
            val cheaper = good.copy(teaser = good.teaser.copy(priceMinor = 100))
            val raised = o.item(seed = "2", maxHops = 1, signedMaxHops = 2)
            val byOther = o.item(seed = "3", signedBy = evil)
            val noise = o.item(seed = "4", signature = "AAAA")
            val r = applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(cheaper, raised, byOther, noise)))
            r.accepted shouldBe 0
            r.rejected shouldBe 4
            rows().shouldBeEmpty()
            AuctionPullCounters.rejectedByReason()[AuctionReceivedRejectReason.SIGNATURE] shouldBe 4
        }

        test("a hint of a distant origin: the key comes from the recursion pin; an unknown origin is dropped and counted") {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val o = AuctionOriginServer(host = "o.beispiel.de")
            val unknown = AuctionOriginServer(host = "unknown.beispiel.de")
            val pa = direct(a)
            pinDiscovered(o, root = pa.id)
            val r =
                applyAuctionFeed(
                    sourceId = pa.id,
                    bytes =
                        auctionFeedBytes(
                            items =
                                listOf(
                                    o.item(seed = "2", hops = 1, via = listOf("a.beispiel.de")),
                                    unknown.item(seed = "3", hops = 1, via = listOf("a.beispiel.de")),
                                ),
                        ),
                )
            r.accepted shouldBe 1
            r.unknownOriginKey shouldBe 1
            val row = rows().single()
            row.origin shouldBe "o.beispiel.de"
            row.hops shouldBe 2
            row.via shouldBe "a.beispiel.de"
            AuctionPullCounters.unknownOriginKey.get() shouldBe 1
        }

        test(
            "revisions: an older one is REPLAY, the same revision with other content is TAMPER, a newer one replaces content and signature",
        ) {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", revision = 5)))),
            )
            val replay =
                applyAuctionFeed(
                    sourceId = p.id,
                    bytes =
                        auctionFeedBytes(
                            items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", revision = 4, priceMinor = 100))),
                        ),
                )
            replay.accepted shouldBe 0
            rows().single().revision shouldBe 5
            // the SAME revision, correctly signed by the real key, but another title: TAMPER
            val tamper =
                applyAuctionFeed(
                    sourceId = p.id,
                    bytes =
                        auctionFeedBytes(
                            items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", revision = 5, title = "Ganz was anderes"))),
                        ),
                )
            tamper.accepted shouldBe 0
            rows().single().title shouldBe "Fahrrad 1"
            val counters = AuctionPullCounters.rejectedByReason()
            counters[AuctionReceivedRejectReason.REPLAY] shouldBe 1
            counters[AuctionReceivedRejectReason.TAMPER] shouldBe 1
            // the same revision and content again: only last seen moves
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", revision = 5)))),
            )
            rows().single().revision shouldBe 5
            // a newer revision replaces content AND signature
            val before = rows().single().signature
            applyAuctionFeed(
                sourceId = p.id,
                bytes =
                    auctionFeedBytes(
                        items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", revision = 6, priceMinor = 3000))),
                    ),
            ).accepted shouldBe 1
            val after = rows().single()
            after.revision shouldBe 6
            after.price shouldBe 3000
            (after.signature == before) shouldBe false
        }

        test("a newer revision from ANOTHER source replaces the content but keeps the path of the first source") {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val b = AuctionOriginServer(host = "b2.beispiel.de")
            val o = AuctionOriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            pinDiscovered(o, root = pa.id)
            applyAuctionFeed(
                sourceId = pa.id,
                bytes =
                    auctionFeedBytes(
                        items =
                            listOf(
                                o.item(seed = "x", hops = 1, via = listOf("a.beispiel.de"), teaser = o.teaser(seed = "x", revision = 5)),
                            ),
                    ),
            )
            applyAuctionFeed(
                sourceId = pb.id,
                bytes =
                    auctionFeedBytes(
                        items =
                            listOf(
                                o.item(
                                    seed = "x",
                                    hops = 1,
                                    via = listOf("b2.beispiel.de"),
                                    teaser = o.teaser(seed = "x", revision = 6, priceMinor = 4000),
                                ),
                            ),
                    ),
            )
            val row = rows().single()
            row.revision shouldBe 6
            row.price shouldBe 4000
            row.source shouldBe pa.id
            row.via shouldBe "a.beispiel.de"
        }

        test("the pin: a changed key between request and answer voids the answer; a pending key holds the pull and the apply") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            val snapshot = transaction { PartnerServerStore.findPartner(p.id)!! }
            val changed =
                AuctionOriginServer(host = "a.beispiel.de").let { other ->
                    transaction {
                        PartnerServerTable.update(
                            { PartnerServerTable.id eq p.id },
                        ) { it[signingPublicKeyPem] = other.keyPair.publicKeyPem }
                    }
                }
            changed shouldBe 1
            val r = applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"))), snapshot = snapshot)
            r.applied shouldBe false
            rows().shouldBeEmpty()
            // a pending key (signing_key_changed_at set): not due, and not applied
            transaction {
                PartnerServerTable.update({ PartnerServerTable.id eq p.id }) {
                    it[signingPublicKeyPem] = o.keyPair.publicKeyPem
                    it[signingKeyChangedAt] = AUCTION_PULL_NOW
                }
            }
            transaction { PartnerServerStore.dueForAuctionPull(now = AUCTION_PULL_NOW, limit = 10) }.shouldBeEmpty()
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1")))).applied shouldBe false
        }

        test("hop limit: a relayed hint is taken with limit 2 and refused by the store with limit 1, even if the reader saw limit 2") {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val o = AuctionOriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            pinDiscovered(o, root = pa.id)
            val bytes = auctionFeedBytes(items = listOf(o.item(seed = "1", hops = 1, via = listOf("a.beispiel.de"))))
            settings(hop = 1)
            val r = applyAuctionFeed(sourceId = pa.id, bytes = bytes, ownHopLimit = 2)
            r.accepted shouldBe 0
            AuctionPullCounters.rejectedByReason()[AuctionReceivedRejectReason.HOP_LIMIT] shouldBe 1
            settings(hop = 2)
            applyAuctionFeed(sourceId = pa.id, bytes = bytes).accepted shouldBe 1
        }

        test("a hint of a direct origin is accepted with limit 1") {
            settings(hop = 1)
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = listOf(o.item(seed = "1", maxHops = 1))),
                ownHopLimit = 1,
            ).accepted shouldBe
                1
        }

        test("capacity: a full source turns the next hint away and never deletes a row of another source") {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val b = AuctionOriginServer(host = "b2.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            applyAuctionFeed(sourceId = pb.id, bytes = auctionFeedBytes(items = listOf(b.item(seed = "b1"))))
            val cap = AuctionReceivedStore.effectivePerSourceCap(sharingPartners = 2)
            cap shouldBe AuctionReceivedStore.MAX_PER_SOURCE
            val first = (1..AuctionReceivedStore.MAX_PER_SOURCE).map { a.item(seed = "a$it", maxHops = 1) }
            applyAuctionFeed(sourceId = pa.id, bytes = auctionFeedBytes(items = first, complete = false)).accepted shouldBe
                AuctionReceivedStore.MAX_PER_SOURCE
            val over =
                applyAuctionFeed(
                    sourceId = pa.id,
                    bytes = auctionFeedBytes(items = listOf(a.item(seed = "overflow", maxHops = 1)), complete = false),
                )
            over.accepted shouldBe 0
            AuctionPullCounters.rejectedByReason()[AuctionReceivedRejectReason.CAPACITY] shouldBe 1
            transaction { AuctionReceivedStore.countBySource() }.let {
                it[pa.id] shouldBe AuctionReceivedStore.MAX_PER_SOURCE
                it[pb.id] shouldBe 1
            }
            AuctionReceivedStore.effectivePerSourceCap(sharingPartners = 100) shouldBe AuctionReceivedStore.MIN_PER_SOURCE
            AuctionReceivedStore.effectivePerSourceCap(sharingPartners = 10) shouldBe 500
        }

        test("a complete list drops what the source no longer lists, without a block; an incomplete list drops nothing") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"), o.item(seed = "2"))))
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1")), complete = false))
            ids() shouldBe setOf(hexId("1"), hexId("2"))
            val r = applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1")), complete = true))
            r.removed shouldBe 1
            ids() shouldBe setOf(hexId("1"))
            blocks().shouldBeEmpty()
        }

        test(
            "a withdrawal removes the copy, writes a WITHDRAWN block and is passed on only if the copy could travel and a partner fetched our feed",
        ) {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyAuctionFeed(
                sourceId = p.id,
                bytes =
                    auctionFeedBytes(
                        items = listOf(o.item(seed = "w1", maxHops = 2), o.item(seed = "w2", maxHops = 1), o.item(seed = "keep")),
                    ),
            )
            served(partnerId = p.id, at = servedAfter)
            val r =
                applyAuctionFeed(
                    sourceId = p.id,
                    bytes =
                        auctionFeedBytes(
                            items = emptyList(),
                            withdrawn = listOf(hexId("w1"), hexId("w2"), hexId("unknown")),
                            complete = false,
                        ),
                )
            r.withdrawn shouldBe 2
            ids() shouldBe setOf(hexId("keep"))
            blocks().map { it.teaserId }.toSet() shouldBe setOf(hexId("w1"), hexId("w2"))
            blocks().all { it.kind == "WITHDRAWN" && it.reporter == p.id } shouldBe true
            // only w1 (hops 1 < max 2) could have been handed on
            passOnTombstones() shouldContainExactly listOf(hexId("w1"))
        }

        test("no pass-on without a fetch of our feed, and no forged withdrawal from a stranger") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val s = AuctionOriginServer(host = "s.beispiel.de")
            val p = direct(o)
            val ps = direct(s)
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"))))
            applyAuctionFeed(
                sourceId = ps.id,
                bytes = auctionFeedBytes(items = emptyList(), withdrawn = listOf(hexId("1")), complete = false),
            ).withdrawn shouldBe
                0
            ids() shouldBe setOf(hexId("1"))
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = emptyList(), withdrawn = listOf(hexId("1")), complete = false),
            )
            ids().shouldBeEmpty()
            passOnTombstones().shouldBeEmpty()
        }

        test(
            "re-import after a withdrawal: another path stays blocked; the origin at hop 0 brings it back and the block and the pass-on are gone",
        ) {
            val b = AuctionOriginServer(host = "b2.beispiel.de")
            val o = AuctionOriginServer(host = "o.beispiel.de")
            val pb = direct(b)
            val po = direct(o)
            pinDiscovered(o, root = pb.id)
            applyAuctionFeed(sourceId = po.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "x"))))
            served(partnerId = po.id, at = servedAfter)
            applyAuctionFeed(
                sourceId = po.id,
                bytes = auctionFeedBytes(items = emptyList(), withdrawn = listOf(hexId("x")), complete = false),
            )
            ids().shouldBeEmpty()
            passOnTombstones() shouldContainExactly listOf(hexId("x"))
            val blocked =
                applyAuctionFeed(
                    sourceId = pb.id,
                    bytes = auctionFeedBytes(items = listOf(o.item(seed = "x", hops = 1, via = listOf("b2.beispiel.de")))),
                )
            blocked.accepted shouldBe 0
            ids().shouldBeEmpty()
            // the origin itself (direct, hop 0) renews its consent: allowed, the blocks are gone
            val back = applyAuctionFeed(sourceId = po.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "x"))))
            back.accepted shouldBe 1
            ids() shouldBe setOf(hexId("x"))
            blocks().shouldBeEmpty()
            passOnTombstones().shouldBeEmpty()
        }

        test(
            "HIDDEN: the copy goes, nothing brings it back (not even the origin), a later withdrawal does not weaken it, a newer end prolongs it",
        ) {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"))))
            served(partnerId = p.id, at = servedAfter)
            val hidden =
                transaction {
                    PartnerServerStore.lockPartner(p.id)
                    AuctionReceivedStore.hide(originHost = "a.beispiel.de", teaserId = hexId("1"), now = AUCTION_PULL_NOW)
                }
            hidden!!.sourcePartnerId shouldBe p.id
            ids().shouldBeEmpty()
            blocks().single().let {
                it.kind shouldBe "HIDDEN"
                it.reporter shouldBe null
            }
            // hiding reports nothing to partners
            passOnTombstones().shouldBeEmpty()
            // the origin itself lists it again at hop 0: still blocked
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1")))).accepted shouldBe 0
            ids().shouldBeEmpty()
            blocks().single().kind shouldBe "HIDDEN"
            // a withdrawal of the same hint never turns HIDDEN into WITHDRAWN (the row is gone, so nothing is hit; the block stays)
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = emptyList(), withdrawn = listOf(hexId("1")), complete = false),
            )
            blocks().single().kind shouldBe "HIDDEN"
            // a newer revision with a LATER end prolongs the block
            val before = blocks().single().expires
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = listOf(o.item(seed = "1", teaser = o.teaser(seed = "1", revision = 9, endsIn = 96)))),
            ).accepted shouldBe 0
            (blocks().single().expires > before) shouldBe true
            // hiding a hint we never held writes nothing
            transaction {
                AuctionReceivedStore.hide(
                    originHost = "a.beispiel.de",
                    teaserId = hexId("nope"),
                    now = AUCTION_PULL_NOW,
                )
            } shouldBe
                null
            blocks() shouldHaveSize 1
        }

        test("HIDDEN upgrades an existing WITHDRAWN block and a hidden hint is not handed on") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val c = AuctionOriginServer(host = "c.beispiel.de")
            val p = direct(o)
            val pc = direct(c)
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"), o.item(seed = "2"))))
            transaction {
                AuctionReceivedStore.republishableFor(partner = pc.row(), ownHopLimit = 2, now = AUCTION_PULL_NOW, limit = 10)
            } shouldHaveSize
                2
            transaction {
                PartnerServerStore.lockPartner(p.id)
                AuctionReceivedStore.hide(originHost = "a.beispiel.de", teaserId = hexId("1"), now = AUCTION_PULL_NOW)
            }
            transaction { AuctionReceivedStore.republishableFor(partner = pc.row(), ownHopLimit = 2, now = AUCTION_PULL_NOW, limit = 10) }
                .map { it.teaser.teaserId } shouldBe listOf(hexId("2"))
            // WITHDRAWN first, then hidden
            applyAuctionFeed(
                sourceId = p.id,
                bytes = auctionFeedBytes(items = emptyList(), withdrawn = listOf(hexId("2")), complete = false),
            )
            blocks().first { it.teaserId == hexId("2") }.kind shouldBe "WITHDRAWN"
            applyAuctionFeed(sourceId = p.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "2"))))
            ids().single() shouldBe hexId("2")
            transaction {
                PartnerServerStore.lockPartner(p.id)
                AuctionReceivedStore.hide(originHost = "a.beispiel.de", teaserId = hexId("2"), now = AUCTION_PULL_NOW)
            }
            blocks().first { it.teaserId == hexId("2") }.kind shouldBe "HIDDEN"
        }

        test(
            "republishable: never to the source or the origin or a relay on the path, only inside min(max hops, own limit, 2), never blocked ones",
        ) {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val c = AuctionOriginServer(host = "c.beispiel.de")
            val pa = direct(a)
            val pc = direct(c)
            applyAuctionFeed(
                sourceId = pa.id,
                bytes = auctionFeedBytes(items = listOf(a.item(seed = "1"), a.item(seed = "short", maxHops = 1))),
            )

            fun toward(
                p: FeedPartner,
                limit: Int = 2,
            ) = transaction {
                AuctionReceivedStore.republishableFor(partner = p.row(), ownHopLimit = limit, now = AUCTION_PULL_NOW, limit = 10)
            }.map { it.teaser.teaserId }
            // to the origin and source itself: nothing
            toward(pa).shouldBeEmpty()
            // to a third partner: the hint with max hops 2 only (hops 1 + 1 <= 2); the one with max hops 1 stays here
            toward(pc) shouldBe listOf(hexId("1"))
            // our own limit 1 hands on nothing
            toward(pc, limit = 1).shouldBeEmpty()
            // the source switches off sharing: its copies are not handed on meanwhile
            transaction { PartnerServerStore.setAuctionSharing(id = pa.id, enabled = false) }
            toward(pc).shouldBeEmpty()
        }

        fun builtFeed(
            partner: FeedPartner,
            hopLimit: Int = 2,
        ): AuctionTeaserFeedV1 {
            val built =
                transaction {
                    AuctionTeaserFeedBuilder.build(
                        partner = PartnerServerStore.findPartner(partner.id)!!,
                        settings = AuctionFederationSettings(enabled = true, hopLimit = hopLimit),
                        ids = AuctionTeaserIds(TEST_BOX),
                        originHost = OWN_HOST,
                        now = AUCTION_PULL_NOW,
                        ownKey = CarpoolOwnKeys.load(),
                    )
                }
            return AUCTION_TEASER_FEED_JSON.decodeFromString(AuctionTeaserFeedV1.serializer(), String(built.body, Charsets.UTF_8))
        }

        test(
            "the feed builder relays a received hint only with hop limit 2: path extended by this server, origin signature untouched, verifiable",
        ) {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val c = AuctionOriginServer(host = "c.beispiel.de")
            val pa = direct(a)
            val pc = direct(c)
            applyAuctionFeed(sourceId = pa.id, bytes = auctionFeedBytes(items = listOf(a.item(seed = "1"))))
            builtFeed(pc, hopLimit = 1).items.shouldBeEmpty()
            val relayed = builtFeed(pc, hopLimit = 2).items.single()
            relayed.originHost shouldBe "a.beispiel.de"
            relayed.hops shouldBe 1
            relayed.via shouldBe listOf(OWN_HOST)
            AuctionOriginSignatures.verify(
                teaser = relayed.teaser,
                originHost = relayed.originHost,
                maxHops = relayed.maxHops,
                signatureB64 = relayed.originSignature,
                publicKeyPem = a.keyPair.publicKeyPem,
            ) shouldBe true
            // never back to the source (it is the origin here)
            builtFeed(pa, hopLimit = 2).items.shouldBeEmpty()
        }

        test(
            "a pass-on withdrawal reaches every partner except the one that reported it; a relayed hint with a live block is not handed on",
        ) {
            val o = AuctionOriginServer(host = "o.beispiel.de")
            val c = AuctionOriginServer(host = "c.beispiel.de")
            val po = direct(o)
            val pc = direct(c)
            applyAuctionFeed(sourceId = po.id, bytes = auctionFeedBytes(items = listOf(o.item(seed = "1"))))
            served(partnerId = po.id, at = servedAfter)
            applyAuctionFeed(
                sourceId = po.id,
                bytes = auctionFeedBytes(items = emptyList(), withdrawn = listOf(hexId("1")), complete = false),
            )
            builtFeed(pc).withdrawn.map { it.teaserId } shouldBe listOf(hexId("1"))
            builtFeed(po).withdrawn.shouldBeEmpty()
            builtFeed(pc).items.shouldBeEmpty()
        }

        test("an own withdrawal of an id beats a stored foreign copy with the same id: it is published and the copy is not relayed") {
            val m = AuctionOriginServer(host = "m.beispiel.de")
            val c = AuctionOriginServer(host = "c.beispiel.de")
            val pm = direct(m)
            val pc = direct(c)
            // a direct partner publishes an item that carries the id of one of OUR hints
            applyAuctionFeed(sourceId = pm.id, bytes = auctionFeedBytes(items = listOf(m.item(seed = "1"))))
            rows().single().teaserId shouldBe hexId("1")
            transaction {
                AuctionTeaserTombstoneTable.insert {
                    it[id] = AuctionFederationStore.tombstoneId(teaserId = hexId("1"), partnerId = null)
                    it[teaserId] = hexId("1")
                    it[partnerId] = null
                    it[relayOriginHost] = null
                    it[relayReporterPartnerId] = null
                    it[createdAt] = AUCTION_PULL_NOW
                    it[expiresAt] = AUCTION_PULL_NOW.toInstantUtc().plus(48.hours).toLocalDateTime(TimeZone.UTC)
                }
            }
            val feed = builtFeed(pc, hopLimit = 2)
            feed.items.shouldBeEmpty()
            feed.withdrawn.map { it.teaserId } shouldBe listOf(hexId("1"))
        }

        test("a stored foreign copy with the id of an own ACTIVE item is not relayed; only our own item is published") {
            val m = AuctionOriginServer(host = "m.beispiel.de")
            val c = AuctionOriginServer(host = "c.beispiel.de")
            val pm = direct(m)
            val pc = direct(c)
            val ids = AuctionTeaserIds(TEST_BOX)
            val seller = Uuid.random()
            val auction = Uuid.random()
            try {
                transaction {
                    MemberTable.insert {
                        it[id] = seller
                        it[displayName] = "Received Own Seller"
                        it[email] = "received-own-seller@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                        it[membershipTierId] = null
                    }
                    AuctionTable.insert {
                        it[id] = auction
                        it[title] = "Eigenes Lastenrad"
                        it[description] = "geheim"
                        it[status] = AuctionStatus.OPEN
                        it[sellerMemberId] = seller
                        it[listingFeeLtr] = BigDecimal("0.00")
                        it[createdAt] = AUCTION_PULL_NOW
                        it[endsAt] = LocalDateTime(2027, 11, 24, 10, 0, 0)
                        it[currency] = "EUR"
                        it[startingPriceMinor] = 1000L
                        it[sellerDisclaimerVersion] = "test"
                    }
                    AuctionFederationStore.setOptIn(auctionId = auction, value = true, now = AUCTION_PULL_NOW)
                    val row = AuctionTable.selectAll().where { AuctionTable.id eq auction }.single()
                    AuctionTeaserSnapshots.createOnConsent(row = row, ids = ids, fiatEnabled = true, now = AUCTION_PULL_NOW)
                }
                val ownId = ids.of(auction)
                applyAuctionFeed(
                    sourceId = pm.id,
                    bytes = auctionFeedBytes(items = listOf(m.item(seed = "own", teaser = m.teaser(seed = "own").copy(teaserId = ownId)))),
                )
                rows().single().teaserId shouldBe ownId
                val published = builtFeed(pc, hopLimit = 2).items.single()
                published.originHost shouldBe OWN_HOST
                published.hops shouldBe 0
            } finally {
                transaction {
                    AuctionTeaserSnapshotTable.deleteAll()
                    AuctionTable.deleteWhere { AuctionTable.id eq auction }
                    MemberTable.deleteWhere { MemberTable.id eq seller }
                }
            }
        }

        test(
            "the member read path: ordered by end, id, host; hides ended, blocked, foreign-currency and over-limit hints; no signature or path in the result",
        ) {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val o = AuctionOriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            pinDiscovered(o, root = pa.id)
            applyAuctionFeed(
                sourceId = pa.id,
                bytes =
                    auctionFeedBytes(
                        items =
                            listOf(
                                a.item(seed = "late", teaser = a.teaser(seed = "late", endsIn = 72)),
                                a.item(seed = "early", teaser = a.teaser(seed = "early", endsIn = 2)),
                                o.item(
                                    seed = "relayed",
                                    hops = 1,
                                    via = listOf("a.beispiel.de"),
                                    teaser = o.teaser(seed = "relayed", endsIn = 10),
                                ),
                            ),
                    ),
            )
            val page = transaction { AuctionReceivedStore.listForMembers(now = AUCTION_PULL_NOW, hopLimit = 2, page = 0) }
            page.items.map { it.teaserId } shouldBe listOf(hexId("early"), hexId("relayed"), hexId("late"))
            page.hasMore shouldBe false
            // the hop limit now is 1: the relayed copy is no longer shown
            transaction {
                AuctionReceivedStore.listForMembers(
                    now = AUCTION_PULL_NOW,
                    hopLimit = 1,
                    page = 0,
                )
            }.items.map { it.teaserId } shouldBe
                listOf(hexId("early"), hexId("late"))
            // after the end of "early" it is gone at once (the row still exists until the sweep)
            val later = AUCTION_PULL_NOW.toInstantUtc().plus(3.hours).toLocalDateTime(TimeZone.UTC)
            transaction { AuctionReceivedStore.listForMembers(now = later, hopLimit = 2, page = 0) }.items.map { it.teaserId } shouldBe
                listOf(hexId("relayed"), hexId("late"))
            // a source that stopped sharing hides its copies at once
            transaction { PartnerServerStore.setAuctionSharing(id = pa.id, enabled = false) }
            transaction { AuctionReceivedStore.listForMembers(now = AUCTION_PULL_NOW, hopLimit = 2, page = 0) }.items.shouldBeEmpty()
        }

        test("retention: expired copies and blocks go with the sweep; a stale source loses its copies") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyAuctionFeed(
                sourceId = p.id,
                bytes =
                    auctionFeedBytes(
                        items = listOf(o.item(seed = "short", teaser = o.teaser(seed = "short", endsIn = 3)), o.item(seed = "long")),
                    ),
            )
            val later = AUCTION_PULL_NOW.toInstantUtc().plus(4.hours).toLocalDateTime(TimeZone.UTC)
            transaction { AuctionReceivedStore.deleteExpired(now = later, limit = 100) } shouldBe 1
            ids() shouldBe setOf(hexId("long"))
            // stale: the last success is older than 24 hours
            val muchLater = AUCTION_PULL_NOW.toInstantUtc().plus(26.hours).toLocalDateTime(TimeZone.UTC)
            transaction { AuctionReceivedStore.dropStale(now = muchLater) } shouldBe 1
            ids().shouldBeEmpty()
        }

        test(
            "switching off: the instance switch drops every copy and forgets every ETag; a partner switch and a removal drop only that source",
        ) {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val b = AuctionOriginServer(host = "b2.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            applyAuctionFeed(sourceId = pa.id, bytes = auctionFeedBytes(items = listOf(a.item(seed = "1"))))
            applyAuctionFeed(sourceId = pb.id, bytes = auctionFeedBytes(items = listOf(b.item(seed = "2"))))
            transaction { AuctionReceivedStore.dropFromSource(sourceId = pa.id, now = AUCTION_PULL_NOW) } shouldBe 1
            ids() shouldBe setOf(hexId("2"))
            transaction { AuctionReceivedStore.withdrawAllForSwitchOff(AUCTION_PULL_NOW) } shouldBe 1
            ids().shouldBeEmpty()
            transaction { PartnerServerStore.findPartner(pb.id)!! }.auctionPullEtag shouldBe null
            applyAuctionFeed(sourceId = pb.id, bytes = auctionFeedBytes(items = listOf(b.item(seed = "3"))))
            transaction { PartnerServerStore.remove(pb.id) }
            ids().shouldBeEmpty()
        }

        test("the apply is refused while the instance switch or the auction module is off, or the partner is inactive") {
            val o = AuctionOriginServer(host = "a.beispiel.de")
            val p = direct(o)
            val bytes = auctionFeedBytes(items = listOf(o.item(seed = "1")))
            settings(enabled = false)
            applyAuctionFeed(sourceId = p.id, bytes = bytes).applied shouldBe false
            settings(auction = false)
            applyAuctionFeed(sourceId = p.id, bytes = bytes).applied shouldBe false
            settings()
            transaction { PartnerServerStore.setActive(id = p.id, active = false, now = AUCTION_PULL_NOW) }
            applyAuctionFeed(sourceId = p.id, bytes = bytes).applied shouldBe false
            rows().shouldBeEmpty()
        }

        test("a block pattern drops the copies whose source or origin it matches") {
            val a = AuctionOriginServer(host = "a.beispiel.de")
            val b = AuctionOriginServer(host = "b2.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            applyAuctionFeed(sourceId = pa.id, bytes = auctionFeedBytes(items = listOf(a.item(seed = "1"))))
            applyAuctionFeed(sourceId = pb.id, bytes = auctionFeedBytes(items = listOf(b.item(seed = "2"))))
            transaction { PartnerServerStore.addBlock(pattern = "a.beispiel.de", now = AUCTION_PULL_NOW) }
            ids() shouldBe setOf(hexId("2"))
        }

        test("the pass-on tombstone id of an OWN withdrawal is byte-identical to c2 (live rows must still be found); a relay id differs") {
            val own = AuctionFederationStore.tombstoneId(teaserId = "a".repeat(64), partnerId = null)
            own.toString() shouldBe "6636231d-bbd0-4bf0-904d-19d2ff765202"
            val relay = AuctionFederationStore.tombstoneId(teaserId = "a".repeat(64), partnerId = null, relayOriginHost = "o.beispiel.de")
            (relay == own) shouldBe false
            AuctionTeaserTombstonePolicy.TTL_DAYS shouldBe 14
        }
    })

private fun LocalDateTime.toInstantUtc() = toInstant(TimeZone.UTC)

private fun FeedPartner.row() = this.let { p -> transaction { PartnerServerStore.findPartner(p.id)!! } }
