package network.lapis.cloud.server.carpool.pull

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolOriginSignatures
import network.lapis.cloud.server.carpool.CarpoolOwnKeys
import network.lapis.cloud.server.carpool.CarpoolPullCounters
import network.lapis.cloud.server.carpool.CarpoolReceivedStore
import network.lapis.cloud.server.carpool.CarpoolRelayedTarget
import network.lapis.cloud.server.carpool.allTombstones
import network.lapis.cloud.server.carpool.clearTombstones
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedBuilder
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.OWN_HOST
import network.lapis.cloud.server.carpool.feed.TEST_IDS
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.carpool.recordServed
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationKeyPair
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.partner.DiscoveredWriteResult
import network.lapis.cloud.server.partner.PartnerDiscoveryStore
import network.lapis.cloud.server.partner.PartnerParseResult
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.PinnedSigningKey
import network.lapis.cloud.server.partner.RecursionCandidate
import network.lapis.cloud.server.partner.RecursionRoot
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.314 "Mitfahrzentrale-Foederation" W4 -- the store of received hints on H2 AND PostgreSQL: apply (signature, hop rules, paths, limits), the
 * pass-on of withdrawals, re-import, complete lists, switching off, staleness, retention, the real foreign keys, and one end-to-end chain
 * (origin feed builder -> reader -> store -> relay feed builder) in one database with a relay host of its own.
 */
abstract class CarpoolReceivedScenarios(
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
                exec("UPDATE carpool_posting SET federation_opt_in = FALSE")
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }.also { clearTombstones() }
        beforeTest {
            clean()
            CarpoolPullCounters.resetForTest()
        }
        afterSpec {
            try {
                clean()
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                if (!db.isPostgres) data.cleanUp()
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
            val date: LocalDate,
            val from: String,
            val to: String,
            val signature: String,
            val expires: LocalDateTime,
        )

        fun rows(): List<Row> =
            transaction {
                CarpoolReceivedHintTable.selectAll().map {
                    Row(
                        teaserId = it[CarpoolReceivedHintTable.teaserId],
                        origin = it[CarpoolReceivedHintTable.originHost],
                        hops = it[CarpoolReceivedHintTable.hops].toInt(),
                        maxHops = it[CarpoolReceivedHintTable.maxHops].toInt(),
                        via = it[CarpoolReceivedHintTable.viaHosts],
                        source = it[CarpoolReceivedHintTable.sourcePartnerId],
                        date = it[CarpoolReceivedHintTable.departureDate],
                        from = it[CarpoolReceivedHintTable.fromPostcodeArea],
                        to = it[CarpoolReceivedHintTable.toPostcodeArea],
                        signature = it[CarpoolReceivedHintTable.originSignature],
                        expires = it[CarpoolReceivedHintTable.expiresAt],
                    )
                }
            }

        fun ids() = rows().map { it.teaserId }.toSet()

        fun receivedBlocks() = transaction { CarpoolReceivedTombstoneTable.selectAll().map { it[CarpoolReceivedTombstoneTable.teaserId] } }

        fun globalTombstones() =
            allTombstones()
                .filter {
                    it[network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable.partnerId] ==
                        null
                }.map { it[network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable.teaserId] }

        fun direct(o: OriginServer): FeedPartner = newFeedPartner(host = o.host, keyPair = o.keyPair)

        fun pinDiscovered(
            o: OriginServer,
            root: Uuid,
        ) = transaction {
            PartnerDiscoveredServerTable.insert {
                it[id] = Uuid.random()
                it[host] = o.host
                it[rootPartnerServerId] = root
                it[hops] = 2.toShort()
                it[createdAt] = PULL_NOW
                it[nextFetchAt] = PULL_NOW
                it[signingKeyId] = o.keyId
                it[signingPublicKeyPem] = o.keyPair.publicKeyPem
                it[signingKeyPinnedAt] = PULL_NOW
            }
        }

        val servedAfter = LocalDateTime(2026, 11, 19, 10, 5, 0)

        test("hop 1: the copy holds exactly the hint, one hop, the signature of the origin; nothing personal exists in the row") {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            val r = applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "1"))))
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
            row.date shouldBe PULL_DEPARTURE
            row.from shouldBe "38"
            row.to shouldBe "10"
            row.expires shouldBe LocalDateTime(2026, 11, 25, 0, 0, 0)
            CarpoolOriginSignatures.verify(
                teaser = o.teaser(seed = "1"),
                originHost = "a.beispiel.de",
                maxHops = 2,
                signatureB64 = row.signature,
                publicKeyPem = o.keyPair.publicKeyPem,
            ) shouldBe true
            transaction { CarpoolReceivedStore.countBySource()[p.id] } shouldBe 1
            transaction { CarpoolReceivedStore.countTotal(PULL_NOW) } shouldBe 1
            // the pull state was written last, with the ETag of the answer
            transaction { PartnerServerStore.findPartner(p.id)!! }.let {
                it.carpoolPullStatus shouldBe "OK"
                it.carpoolPullEtag shouldBe "\"e\""
                it.carpoolPullLastAccepted shouldBe 1
                it.carpoolPullFailures shouldBe 0
            }
        }

        test(
            "hop 2: the key of a distant origin comes from the pin of the partner recursion; without any pin the hint is dropped and counted",
        ) {
            val a = OriginServer(host = "a.beispiel.de")
            val o = OriginServer(host = "o.beispiel.de")
            val unknown = OriginServer(host = "unknown.beispiel.de")
            val pa = direct(a)
            pinDiscovered(o, root = pa.id)
            val r =
                applyFeed(
                    sourceId = pa.id,
                    bytes =
                        feedBytes(
                            items =
                                listOf(
                                    o.item(seed = "2", hops = 1, via = listOf("a.beispiel.de")),
                                    unknown.item(seed = "3", hops = 1, via = listOf("a.beispiel.de")),
                                ),
                        ),
                )
            r.accepted shouldBe 1
            r.unknownOriginKey shouldBe 1
            r.rejected shouldBe 1
            val row = rows().single()
            row.origin shouldBe "o.beispiel.de"
            row.hops shouldBe 2
            row.via shouldBe "a.beispiel.de"
            CarpoolPullCounters.unknownOriginKey.get() shouldBe 1
            CarpoolPullCounters.rejected.get() shouldBe 1
        }

        test("a forged signature, a changed day, a raised hop limit and a signature by another key are all rejected; nothing is stored") {
            val o = OriginServer(host = "a.beispiel.de")
            val evil = OriginServer(host = "evil.beispiel.de")
            val p = direct(o)
            val good = o.item(seed = "1")
            val changedDay = good.copy(teaser = good.teaser.copy(departureDate = LocalDate(2026, 11, 25)))
            val raised = o.item(seed = "2", maxHops = 3, signedMaxHops = 2)
            val byOther = o.item(seed = "3", signedBy = evil)
            val noise = o.item(seed = "4", signature = "AAAA")
            val r = applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(changedDay, raised, byOther, noise)))
            r.accepted shouldBe 0
            r.rejected shouldBe 4
            rows().shouldBeEmpty()
        }

        test(
            "a withdrawal from the source or the origin removes the copy, blocks a re-import and is passed on when the copy could have travelled",
        ) {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyFeed(
                sourceId = p.id,
                bytes =
                    feedBytes(
                        items = listOf(o.item(seed = "w1", maxHops = 2), o.item(seed = "w2", maxHops = 1), o.item(seed = "keep")),
                    ),
            )
            rows() shouldHaveSize 3
            recordServed(partnerId = p.id, at = servedAfter)
            val r =
                applyFeed(
                    sourceId = p.id,
                    bytes =
                        feedBytes(
                            items = emptyList(),
                            withdrawn = listOf(hexId("w1"), hexId("w2"), hexId("unknown")),
                            complete = false,
                        ),
                )
            r.withdrawn shouldBe 2
            ids() shouldBe setOf(hexId("keep"))
            receivedBlocks().toSet() shouldBe setOf(hexId("w1"), hexId("w2"))
            // only w1 (hops 1 < max 2) could have been handed on: only it is reported to the partners; "unknown" writes nothing
            globalTombstones() shouldContainExactly listOf(hexId("w1"))
        }

        test("a withdrawal from a server that is neither the source nor the origin is ignored (no forged withdrawals)") {
            val o = OriginServer(host = "a.beispiel.de")
            val stranger = OriginServer(host = "s.beispiel.de")
            val p = direct(o)
            val ps = direct(stranger)
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "1"))))
            recordServed(partnerId = p.id, at = servedAfter)
            val r = applyFeed(sourceId = ps.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("1")), complete = false))
            r.withdrawn shouldBe 0
            ids() shouldBe setOf(hexId("1"))
            receivedBlocks().shouldBeEmpty()
            globalTombstones().shouldBeEmpty()
        }

        test("the pass-on tombstone is written only if a partner fetched our feed since we got the hint") {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "1"))))
            applyFeed(sourceId = p.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("1")), complete = false))
            ids().shouldBeEmpty()
            globalTombstones().shouldBeEmpty()
        }

        test("re-import after a withdrawal: another path stays blocked, the reporting source or the origin itself may bring it back") {
            val a = OriginServer(host = "a.beispiel.de")
            val b = OriginServer(host = "b2.beispiel.de")
            val o = OriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            val po = direct(o)
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = listOf(o.item(seed = "x", maxHops = 3, hops = 1, via = listOf("a.beispiel.de")))),
            )
            ids() shouldBe setOf(hexId("x"))
            recordServed(partnerId = pa.id, at = servedAfter)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("x")), complete = false))
            ids().shouldBeEmpty()
            globalTombstones() shouldContainExactly listOf(hexId("x"))
            // another relay offers it again: blocked
            val blocked =
                applyFeed(
                    sourceId = pb.id,
                    bytes = feedBytes(items = listOf(o.item(seed = "x", maxHops = 3, hops = 1, via = listOf("b2.beispiel.de")))),
                )
            blocked.accepted shouldBe 0
            blocked.rejected shouldBe 1
            ids().shouldBeEmpty()
            // the origin itself (direct, hop 0) renews its consent: allowed, the blocks are gone
            val back = applyFeed(sourceId = po.id, bytes = feedBytes(items = listOf(o.item(seed = "x", maxHops = 3))))
            back.accepted shouldBe 1
            ids() shouldBe setOf(hexId("x"))
            receivedBlocks().shouldBeEmpty()
            globalTombstones().shouldBeEmpty()
        }

        test("the reporter itself may bring the hint back (item wins over its own withdrawal)") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "y"))))
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("y")), complete = false))
            ids().shouldBeEmpty()
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "y"))))
            ids() shouldBe setOf(hexId("y"))
        }

        test("a complete list removes what the source no longer lists, without a tombstone; an incomplete list removes nothing") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1"), a.item(seed = "2"), a.item(seed = "3"))))
            recordServed(partnerId = pa.id, at = servedAfter)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1")), complete = false))
            ids() shouldBe setOf(hexId("1"), hexId("2"), hexId("3"))
            val r =
                applyFeed(
                    sourceId = pa.id,
                    bytes = feedBytes(items = listOf(a.item(seed = "1")), withdrawn = listOf(hexId("3")), complete = true),
                )
            r.removed shouldBe 1
            ids() shouldBe setOf(hexId("1"))
            // "2" is only missing (no tombstone, no block); "3" was withdrawn explicitly
            receivedBlocks() shouldContainExactly listOf(hexId("3"))
            globalTombstones() shouldContainExactly listOf(hexId("3"))
        }

        test("the shortest path wins; a longer path or another server never replaces it; the same source updates its own copy") {
            val a = OriginServer(host = "a.beispiel.de")
            val o = OriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            val po = direct(o)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(o.item(seed = "d", hops = 1, via = listOf("a.beispiel.de")))))
            rows().single().hops shouldBe 2
            rows().single().source shouldBe pa.id
            applyFeed(sourceId = po.id, bytes = feedBytes(items = listOf(o.item(seed = "d"))))
            rows().single().hops shouldBe 1
            rows().single().source shouldBe po.id
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(o.item(seed = "d", hops = 1, via = listOf("a.beispiel.de")))))
            rows().single().hops shouldBe 1
            rows().single().source shouldBe po.id
        }

        test("a hostile origin that reuses a foreign pseudonym under its own host and signature displaces nothing") {
            val o = OriginServer(host = "o.beispiel.de")
            val evil = OriginServer(host = "evil.beispiel.de")
            val po = direct(o)
            val pe = direct(evil)
            applyFeed(sourceId = po.id, bytes = feedBytes(items = listOf(o.item(seed = "same"))))
            applyFeed(sourceId = pe.id, bytes = feedBytes(items = listOf(evil.item(seed = "same"))))
            rows() shouldHaveSize 2
            rows().single { it.origin == "o.beispiel.de" }.source shouldBe po.id
            // and a withdrawal by the hostile origin removes only ITS row
            applyFeed(sourceId = pe.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("same")), complete = false))
            rows().single().origin shouldBe "o.beispiel.de"
        }

        test("the limit per source: a source that already holds 1000 copies gets no more") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val first =
                applyFeed(
                    sourceId = pa.id,
                    bytes =
                        feedBytes(
                            items =
                                (1..CarpoolReceivedStore.MAX_PER_SOURCE).map {
                                    a.item(seed = "bulk$it")
                                },
                            complete = false,
                        ),
                )
            first.accepted shouldBe CarpoolReceivedStore.MAX_PER_SOURCE
            val more = applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "one-too-many")), complete = false))
            more.accepted shouldBe 0
            more.rejected shouldBe 1
            rows() shouldHaveSize CarpoolReceivedStore.MAX_PER_SOURCE
        }

        test(
            "what may be handed on: hop limits, never back to the source, the origin or a relay of the path, blocked origins, the day window",
        ) {
            val a = OriginServer(host = "a.beispiel.de")
            val o2 = OriginServer(host = "o2.beispiel.de")
            val o3 = OriginServer(host = "o3.beispiel.de")
            val pa = direct(a)
            pinDiscovered(o2, root = pa.id)
            pinDiscovered(o3, root = pa.id)
            applyFeed(
                sourceId = pa.id,
                bytes =
                    feedBytes(
                        items =
                            listOf(
                                a.item(seed = "r1", maxHops = 2),
                                o2.item(seed = "r2", maxHops = 3, hops = 1, via = listOf("a.beispiel.de")),
                                o3.item(seed = "r3", maxHops = 2, hops = 1, via = listOf("a.beispiel.de")),
                            ),
                    ),
            )
            val target = newFeedPartner(host = "p.beispiel.de")
            val row = transaction { PartnerServerStore.findPartner(target.id)!! }

            fun hand(
                partner: network.lapis.cloud.server.partner.PartnerRow = row,
                limit: Int,
                today: LocalDate = PULL_TODAY,
            ) = transaction {
                CarpoolReceivedStore.republishableFor(partner = partner, ownHopLimit = limit, today = today, now = PULL_NOW, limit = 100)
            }.map { it.teaser.teaserId }
            hand(limit = 3) shouldContainExactly listOf(hexId("r1"), hexId("r2"))
            hand(limit = 2) shouldContainExactly listOf(hexId("r1"))
            hand(limit = 1).shouldBeEmpty()
            // never back to the source
            hand(partner = transaction { PartnerServerStore.findPartner(pa.id)!! }, limit = 3).shouldBeEmpty()
            // never to the origin itself
            val po2 = direct(o2)
            hand(partner = transaction { PartnerServerStore.findPartner(po2.id)!! }, limit = 3) shouldContainExactly listOf(hexId("r1"))
            // the day window
            hand(limit = 3, today = LocalDate(2026, 11, 25)).shouldBeEmpty()
            hand(limit = 3, today = LocalDate(2026, 8, 1)).shouldBeEmpty()
            // a block on an origin removes its copies at once
            transaction { PartnerServerStore.addBlock(pattern = "o2.beispiel.de", now = PULL_NOW) }
            hand(limit = 3) shouldContainExactly listOf(hexId("r1"))
            ids() shouldBe setOf(hexId("r1"), hexId("r3"))
        }

        test("the wire shape of a relayed item: the origin's hops and limit, the path including this server, the untouched signature") {
            val a = OriginServer(host = "a.beispiel.de")
            val o = OriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            pinDiscovered(o, root = pa.id)
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = listOf(o.item(seed = "z", maxHops = 3, hops = 1, via = listOf("a.beispiel.de")))),
            )
            val target = newFeedPartner(host = "p.beispiel.de")
            val partner = transaction { PartnerServerStore.findPartner(target.id)!! }
            val built =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = partner,
                        settings = CarpoolFederationStore.settings().copy(hopLimit = 3),
                        ids = TEST_IDS,
                        originHost = PULL_OWN_HOST,
                        today = PULL_TODAY,
                        now = PULL_NOW,
                        ownKey = CarpoolOwnKeys.load(),
                    )
                }
            val item = parseOk(bytes = built.body, sourceHost = PULL_OWN_HOST, ownHost = "p.beispiel.de").items.single()
            item.originHost shouldBe "o.beispiel.de"
            item.wireHops shouldBe 2
            item.maxHops shouldBe 3
            item.via shouldBe listOf("a.beispiel.de", PULL_OWN_HOST)
            CarpoolOriginSignatures.verify(
                teaser = item.teaser,
                originHost = item.originHost,
                maxHops = item.maxHops,
                signatureB64 = item.originSignature,
                publicKeyPem = o.keyPair.publicKeyPem,
            ) shouldBe
                true
        }

        test("switching the instance off: every copy goes, the forwardable ones are reported as withdrawn, every ETag is forgotten") {
            val a = OriginServer(host = "a.beispiel.de")
            val b = OriginServer(host = "b2.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1"), a.item(seed = "2", maxHops = 1))))
            applyFeed(sourceId = pb.id, bytes = feedBytes(items = listOf(b.item(seed = "3"))))
            recordServed(partnerId = pa.id, at = servedAfter)
            val n = transaction { CarpoolReceivedStore.withdrawAllForSwitchOff(now = servedAfter) }
            n shouldBe 3
            rows().shouldBeEmpty()
            globalTombstones().toSet() shouldBe setOf(hexId("1"), hexId("3"))
            transaction { PartnerServerStore.findPartner(pa.id)!!.carpoolPullEtag } shouldBe null
            transaction { PartnerServerStore.findPartner(pb.id)!!.carpoolPullEtag } shouldBe null
        }

        test("dropping one source, a block, a partner that is removed, and the scope tombstones of a partner that is switched off") {
            val a = OriginServer(host = "a.beispiel.de")
            val b = OriginServer(host = "b2.beispiel.de")
            val c = OriginServer(host = "c2.beispiel.de")
            val pa = direct(a)
            val pb = direct(b)
            val pc = direct(c)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1"))))
            applyFeed(sourceId = pb.id, bytes = feedBytes(items = listOf(b.item(seed = "2"))))
            applyFeed(sourceId = pc.id, bytes = feedBytes(items = listOf(c.item(seed = "3"))))
            transaction { CarpoolReceivedStore.dropFromSource(sourceId = pa.id, now = PULL_NOW) } shouldBe 1
            ids() shouldBe setOf(hexId("2"), hexId("3"))
            transaction { PartnerServerStore.addBlock(pattern = "b2.beispiel.de", now = PULL_NOW) }
            ids() shouldBe setOf(hexId("3"))
            transaction { PartnerServerStore.remove(pc.id) }
            rows().shouldBeEmpty()
            // what a partner may have received from the copies we hold
            val e = OriginServer(host = "e2.beispiel.de")
            val pe = direct(e)
            applyFeed(sourceId = pe.id, bytes = feedBytes(items = listOf(e.item(seed = "5"), e.item(seed = "6", maxHops = 1))))
            val target = newFeedPartner(host = "q.beispiel.de")
            val targets = transaction { CarpoolReceivedStore.scopedTombstoneTargetsFor(PartnerServerStore.findPartner(target.id)!!) }
            targets.map { it.teaserId } shouldBe listOf(hexId("5"))
        }

        test("a source that was not pulled successfully for 24 hours loses its copies; a fresh one keeps them") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1"))))
            transaction { CarpoolReceivedStore.dropStale(LocalDateTime(2026, 11, 20, 9, 0, 0)) } shouldBe 0
            transaction { CarpoolReceivedStore.dropStale(LocalDateTime(2026, 11, 20, 11, 0, 0)) } shouldBe 1
            rows().shouldBeEmpty()
        }

        test("retention: a copy and a block expire with the day of departure") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1"), a.item(seed = "2"))))
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("2")), complete = false))
            transaction { CarpoolReceivedStore.deleteExpired(now = LocalDateTime(2026, 11, 24, 12, 0, 0)) } shouldBe 0
            transaction { CarpoolReceivedStore.deleteExpired(now = LocalDateTime(2026, 11, 26, 0, 0, 0)) } shouldBe 2
            rows().shouldBeEmpty()
            receivedBlocks().shouldBeEmpty()
        }

        test("an answer is voided when the source changed meanwhile: another pin, sharing off, instance off, blocked, removed") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val snapshot = transaction { PartnerServerStore.findPartner(pa.id)!! }
            val bytes = feedBytes(items = listOf(a.item(seed = "1")))
            transaction {
                PartnerServerTable.update({ PartnerServerTable.id eq pa.id }) {
                    it[signingPublicKeyPem] =
                        OriginServer(host = "x.beispiel.de").keyPair.publicKeyPem
                }
            }
            applyFeed(sourceId = pa.id, bytes = bytes, snapshot = snapshot).applied shouldBe false
            transaction {
                PartnerServerTable.update(
                    { PartnerServerTable.id eq pa.id },
                ) { it[signingPublicKeyPem] = a.keyPair.publicKeyPem }
            }
            transaction { PartnerServerStore.setCarpoolSharing(id = pa.id, enabled = false) }
            applyFeed(sourceId = pa.id, bytes = bytes).applied shouldBe false
            transaction { PartnerServerStore.setCarpoolSharing(id = pa.id, enabled = true) }
            transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
            applyFeed(sourceId = pa.id, bytes = bytes).applied shouldBe false
            transaction { CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2) }
            transaction { PartnerServerStore.addBlock(pattern = "a.beispiel.de", now = PULL_NOW) }
            applyFeed(sourceId = pa.id, bytes = bytes).applied shouldBe false
            rows().shouldBeEmpty()
        }

        test("the real foreign keys: a removed partner takes its copies along, its reported blocks stay with a NULL reporter") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "1"), a.item(seed = "2"))))
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("2")), complete = false))
            transaction { PartnerServerTable.deleteWhere { PartnerServerTable.id eq pa.id } }
            rows().shouldBeEmpty()
            transaction { CarpoolReceivedTombstoneTable.selectAll().map { it[CarpoolReceivedTombstoneTable.reportedByPartnerId] } } shouldBe
                listOf(null)
        }

        test("the real constraints: hops 1..3, hops <= max, unique (origin, id), 64 character id") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)

            fun insert(
                id: Uuid = Uuid.random(),
                teaser: String = hexId("c"),
                hops: Int = 1,
                max: Int = 2,
            ) = transaction {
                CarpoolReceivedHintTable.insert {
                    it[CarpoolReceivedHintTable.id] = id
                    it[teaserId] = teaser
                    it[originHost] = "a.beispiel.de"
                    it[CarpoolReceivedHintTable.hops] = hops.toShort()
                    it[maxHops] = max.toShort()
                    it[sourcePartnerId] = pa.id
                    it[departureDate] = PULL_DEPARTURE
                    it[fromPostcodeArea] = "38"
                    it[toPostcodeArea] = "10"
                    it[originSignature] = "AAAA"
                    it[receivedAt] = PULL_NOW
                    it[lastSeenAt] = PULL_NOW
                    it[expiresAt] = LocalDateTime(2026, 11, 25, 0, 0, 0)
                }
            }
            shouldThrow<Exception> { insert(hops = 4, max = 3) }
            shouldThrow<Exception> { insert(hops = 3, max = 2) }
            shouldThrow<Exception> { insert(hops = 0) }
            shouldThrow<Exception> { insert(teaser = "short") }
            insert()
            shouldThrow<Exception> { insert() }
        }

        test(
            "end to end in one database with a relay of its own: origin feed, reader, store, relay feed; the withdrawal travels back the same way",
        ) {
            val author = data.member(label = "w4-e2e")
            val trip = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")
            val today = OrganizationTimeZone.today()
            val now =
                network.lapis.cloud.server.db.DbClock
                    .nowLocalDateTime()
            val actor =
                transaction {
                    FederationActorKeyTable.selectAll().single().let {
                        FederationKeyPair(
                            publicKeyPem = it[FederationActorKeyTable.publicKeyPem],
                            privateKeyPem = it[FederationActorKeyTable.privateKeyPem],
                        )
                    }
                }
            // the origin: this very database as "origin.beispiel.de" (the actor key IS the key it announces)
            val originPartner = newFeedPartner(host = OWN_HOST, keyPair = actor)
            val downstream = newFeedPartner(host = "down.beispiel.de")
            transaction { CarpoolFederationStore.setOptIn(postingId = trip, value = true) }
            val downstreamRow = transaction { PartnerServerStore.findPartner(downstream.id)!! }
            val originFeed =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = downstreamRow,
                        settings = CarpoolFederationStore.settings(),
                        ids = TEST_IDS,
                        originHost = OWN_HOST,
                        today = today,
                        now = now,
                        ownKey = CarpoolOwnKeys.load(),
                    )
                }
            originFeed.complete shouldBe true
            originFeed.itemCount shouldBe 1
            // the relay "b.beispiel.de" reads it as the answer of the partner origin.beispiel.de
            val parsed =
                (
                    network.lapis.cloud.server.carpool.pull.CarpoolTeaserFeedReader.parse(
                        bytes = originFeed.body,
                        sourceHost = OWN_HOST,
                        ownHost = PULL_OWN_HOST,
                        today = today,
                        blockPatterns = emptyList(),
                    ) as TeaserFeedParse.Ok
                ).feed
            parsed.items shouldHaveSize 1
            val stored =
                transaction {
                    CarpoolReceivedStore.apply(
                        source = PartnerServerStore.findPartner(originPartner.id)!!,
                        feed = parsed,
                        etag = null,
                        next = now,
                        now = now,
                    )
                }
            stored.accepted shouldBe 1
            rows().single().hops shouldBe 1
            // the relay stops being the origin of the trip, then builds its feed for a third server
            transaction { CarpoolFederationStore.setOptIn(postingId = trip, value = false) }
            val third = newFeedPartner(host = "third.beispiel.de")
            val thirdRow = transaction { PartnerServerStore.findPartner(third.id)!! }
            val relayFeed =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = thirdRow,
                        settings = CarpoolFederationStore.settings(),
                        ids = TEST_IDS,
                        originHost = PULL_OWN_HOST,
                        today = today,
                        now = now,
                        ownKey = CarpoolOwnKeys.load(),
                    )
                }
            relayFeed.itemCount shouldBe 1
            val relayed =
                (
                    network.lapis.cloud.server.carpool.pull.CarpoolTeaserFeedReader.parse(
                        bytes = relayFeed.body,
                        sourceHost = PULL_OWN_HOST,
                        ownHost = "third.beispiel.de",
                        today = today,
                        blockPatterns = emptyList(),
                    ) as TeaserFeedParse.Ok
                ).feed.items.single()
            relayed.originHost shouldBe OWN_HOST
            relayed.wireHops shouldBe 1
            relayed.via shouldBe listOf(PULL_OWN_HOST)
            CarpoolOriginSignatures.verify(
                teaser = relayed.teaser,
                originHost = relayed.originHost,
                maxHops = relayed.maxHops,
                signatureB64 = relayed.originSignature,
                publicKeyPem = actor.publicKeyPem,
            ) shouldBe
                true
            // never back to where it came from
            val backRow = transaction { PartnerServerStore.findPartner(originPartner.id)!! }
            val back =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = backRow,
                        settings = CarpoolFederationStore.settings(),
                        ids = TEST_IDS,
                        originHost = PULL_OWN_HOST,
                        today = today,
                        now = now,
                        ownKey = CarpoolOwnKeys.load(),
                    )
                }
            back.itemCount shouldBe 0
            // an item that this server cannot sign (no own key) is left out and the list says so
            val unsigned =
                transaction {
                    CarpoolFederationStore.setOptIn(postingId = trip, value = true)
                    CarpoolTeaserFeedBuilder.build(
                        partner = thirdRow,
                        settings = CarpoolFederationStore.settings(),
                        ids = TEST_IDS,
                        originHost = PULL_OWN_HOST,
                        today = today,
                        now = now,
                        ownKey = null,
                    )
                }
            unsigned.complete shouldBe false
            // the origin withdraws: the relay removes its copy and reports it onwards (a partner fetched since the copy arrived)
            recordServed(partnerId = third.id, at = now)
            val withdrawal = feedBytes(items = emptyList(), withdrawn = listOf(relayed.teaser.teaserId), complete = false)
            val parsedWithdrawal =
                (
                    network.lapis.cloud.server.carpool.pull.CarpoolTeaserFeedReader.parse(
                        bytes = withdrawal,
                        sourceHost = OWN_HOST,
                        ownHost = PULL_OWN_HOST,
                        today = today,
                        blockPatterns = emptyList(),
                    ) as TeaserFeedParse.Ok
                ).feed
            val result =
                transaction {
                    CarpoolReceivedStore.apply(
                        source = PartnerServerStore.findPartner(originPartner.id)!!,
                        feed = parsedWithdrawal,
                        etag = null,
                        next = now,
                        now = now,
                    )
                }
            result.withdrawn shouldBe 1
            rows().shouldBeEmpty()
            transaction { CarpoolFederationStore.setOptIn(postingId = trip, value = false) }
        }

        test(
            "the recursion pins the announced key from a 200 and a feed without a valid key clears it; pinnedSigningKeyFor trusts only a key id on the host",
        ) {
            val a = OriginServer(host = "a.beispiel.de")
            val o = OriginServer(host = "o.beispiel.de")
            val pa = direct(a)
            val serverId = Uuid.random()
            val candidate =
                RecursionCandidate(
                    host = "o.beispiel.de",
                    hops = 2,
                    root =
                        RecursionRoot(
                            partnerId = pa.id,
                            host = "a.beispiel.de",
                            name = "a",
                            remoteServerId = null,
                            partners = listOf("o.beispiel.de"),
                        ),
                    via = listOf("a.beispiel.de"),
                    lastFetchAt = null,
                )

            fun write(key: PinnedSigningKey?) =
                transaction {
                    PartnerDiscoveryStore.applyDiscoveredSuccess(
                        c = candidate,
                        parsed =
                            PartnerParseResult.Ok(
                                remoteServerId = serverId,
                                remoteName = "O",
                                events = emptyList(),
                                partnersJson = null,
                                droppedEvents = 0,
                                signingKey = key,
                            ),
                        etag = "\"x\"",
                        now = PULL_NOW,
                    )
                }

            fun pinned() = transaction { PartnerDiscoveryStore.pinnedSigningKeyFor("o.beispiel.de") }
            pinned() shouldBe null
            write(
                PinnedSigningKey(keyId = o.keyId, publicKeyPem = o.keyPair.publicKeyPem),
            ).shouldBeInstanceOf<DiscoveredWriteResult.Stored>()
            pinned() shouldBe o.keyPair.publicKeyPem
            // the pin is used for the signature of a hop-2 hint
            val r =
                applyFeed(
                    sourceId = pa.id,
                    bytes = feedBytes(items = listOf(o.item(seed = "p1", maxHops = 3, hops = 1, via = listOf("a.beispiel.de")))),
                )
            r.accepted shouldBe 1
            // a feed without a valid key clears the pin
            write(null).shouldBeInstanceOf<DiscoveredWriteResult.Stored>()
            pinned() shouldBe null
            // a key id on ANOTHER host is never trusted
            write(PinnedSigningKey(keyId = "https://evil.beispiel.de/federation/actor#main-key", publicKeyPem = o.keyPair.publicKeyPem))
            pinned() shouldBe null
            // a blocked host has no usable pin
            write(PinnedSigningKey(keyId = o.keyId, publicKeyPem = o.keyPair.publicKeyPem))
            pinned() shouldBe o.keyPair.publicKeyPem
            transaction { PartnerServerStore.addBlock(pattern = "o.beispiel.de", now = PULL_NOW) }
            pinned() shouldBe null
        }

        test("V162 clears the stored ETag of discovered servers, so a 304 can never keep a server without a pinned key") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val discovered = Uuid.random()
            transaction {
                PartnerDiscoveredServerTable.insert {
                    it[id] = discovered
                    it[host] = "o.beispiel.de"
                    it[rootPartnerServerId] = pa.id
                    it[hops] = 2.toShort()
                    it[createdAt] = PULL_NOW
                    it[nextFetchAt] = PULL_NOW
                    it[etag] = "\"old\""
                }
            }
            val sql =
                checkNotNull(
                    CarpoolReceivedScenarios::class.java.classLoader.getResourceAsStream("db/migration/V162__carpool_federation_pull.sql"),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            val clearing =
                sql
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(";")
                    .map { it.trim() }
                    .filter { it.contains("partner_discovered_server SET etag") }
            clearing shouldHaveSize 1
            transaction { exec(clearing.single()) }
            transaction {
                PartnerDiscoveredServerTable
                    .selectAll()
                    .where { PartnerDiscoveredServerTable.id eq discovered }
                    .single()[PartnerDiscoveredServerTable.etag]
            } shouldBe null
        }

        test("an item that another source already holds on a shorter path is neither stored nor counted as accepted") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val pb = direct(OriginServer(host = "c.beispiel.de"))
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "s1"))))
            val r =
                applyFeed(
                    sourceId = pb.id,
                    bytes = feedBytes(items = listOf(a.item(seed = "s1", maxHops = 3, hops = 1, via = listOf("c.beispiel.de")))),
                )
            r.accepted shouldBe 0
            r.rejected shouldBe 0
            rows().single().source shouldBe pa.id
            transaction { CarpoolReceivedStore.countBySource()[pb.id] } shouldBe null
        }

        test("a deactivated partner is no source we hand on from; switching it on again restores it") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(a.item(seed = "d1"))))
            val target = newFeedPartner(host = "q.beispiel.de")

            fun hand() =
                transaction {
                    CarpoolReceivedStore.republishableFor(
                        partner = PartnerServerStore.findPartner(target.id)!!,
                        ownHopLimit = 2,
                        today = PULL_TODAY,
                        now = PULL_NOW,
                        limit = 100,
                    )
                }.map { it.teaser.teaserId }
            hand() shouldContainExactly listOf(hexId("d1"))
            transaction { PartnerServerStore.setActive(id = pa.id, active = false, now = PULL_NOW) }
            hand().shouldBeEmpty()
            transaction { PartnerServerStore.setActive(id = pa.id, active = true, now = PULL_NOW) }
            hand() shouldContainExactly listOf(hexId("d1"))
        }

        // ───────────────────────── V1.9.318 (wave 6): operations ─────────────────────────

        /** [n] more partners that share (rows only; they never deliver): they shrink the fair share of every source. */
        fun sharingCrowd(n: Int) {
            val kp = FederationKeyPairGenerator.generate()
            repeat(n) { newFeedPartner(keyPair = kp) }
        }

        fun blocksOf(reporter: Uuid) =
            transaction {
                CarpoolReceivedTombstoneTable
                    .selectAll()
                    .where { CarpoolReceivedTombstoneTable.reportedByPartnerId eq reporter }
                    .map { it[CarpoolReceivedTombstoneTable.teaserId] }
            }

        fun preloadBlocks(
            reporter: Uuid,
            count: Int,
            tag: String,
        ) = transaction {
            CarpoolReceivedTombstoneTable.batchInsert((1..count).toList(), shouldReturnGeneratedValues = false) { n ->
                this[CarpoolReceivedTombstoneTable.id] = Uuid.random()
                this[CarpoolReceivedTombstoneTable.teaserId] = hexId("$tag-$n")
                this[CarpoolReceivedTombstoneTable.originHost] = "pre.beispiel.de"
                this[CarpoolReceivedTombstoneTable.reportedByPartnerId] = reporter
                this[CarpoolReceivedTombstoneTable.createdAt] = LocalDateTime(2026, 11, 18, 10, 0, n % 59)
                this[CarpoolReceivedTombstoneTable.expiresAt] = LocalDateTime(2026, 12, 1, 0, 0, 0)
            }
        }

        test("the fair share of one source: 1000 for one to five partners, then 5000 / partners, never below 200") {
            listOf(1, 2, 5, 6, 10, 25, 30, 500).map { CarpoolReceivedStore.effectivePerSourceCap(it) } shouldBe
                listOf(1000, 1000, 1000, 833, 500, 200, 200, 200)
            CarpoolReceivedStore.effectivePerSourceCap(0) shouldBe 1000
        }

        test("with 30 partners that share, a source keeps its 200 nearest departures; the rest is rejected as CAPACITY") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            sharingCrowd(29)
            val near = (1..100).map { a.item(seed = "near$it", date = PULL_DEPARTURE) }
            val far = (1..150).map { a.item(seed = "far$it", date = PULL_DEPARTURE.plus(DatePeriod(days = 20))) }
            // the feed lists the far ones first: the ORDER of taking is by day, not by position in the list
            val r = applyFeed(sourceId = pa.id, bytes = feedBytes(items = far + near, complete = false))
            r.accepted shouldBe 200
            r.rejected shouldBe 50
            val stored = ids()
            stored.containsAll((1..100).map { hexId("near$it") }) shouldBe true
            CarpoolPullCounters.rejectedByReason().getValue(CarpoolReceivedRejectReason.CAPACITY) shouldBe 50L
            CarpoolPullCounters.rejected.get() shouldBe 50L
            CarpoolPullCounters.accepted.get() shouldBe 200L
        }

        test(
            "a hostile partner with many fresh items displaces no row of another source; five hostile partners each stay within their share",
        ) {
            val honest = OriginServer(host = "h.beispiel.de")
            val ph = direct(honest)
            applyFeed(sourceId = ph.id, bytes = feedBytes(items = (1..40).map { honest.item(seed = "honest$it") }, complete = false))
            sharingCrowd(24)
            val hostile = (1..5).map { OriginServer(host = "x$it.beispiel.de") }
            val rowsOfHostile =
                hostile.map { o ->
                    val p = direct(o)
                    applyFeed(sourceId = p.id, bytes = feedBytes(items = (1..300).map { o.item(seed = "${o.host}-$it") }, complete = false))
                    p.id
                }
            val cap = CarpoolReceivedStore.effectivePerSourceCap(1 + 24 + 5)
            rows().count { it.source == ph.id } shouldBe 40
            rowsOfHostile.forEach { id -> (rows().count { it.source == id } <= cap) shouldBe true }
            (rows().size <= CarpoolReceivedStore.MAX_TOTAL) shouldBe true
            CarpoolPullCounters.rejectedByReason().getValue(CarpoolReceivedRejectReason.CAPACITY) shouldBe (5 * (300 - cap)).toLong()
        }

        test("a shrinking share removes nothing: new items of that source are turned away, its updates stay allowed") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val items = (1..300).map { a.item(seed = "s$it") }
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = items, complete = false)).accepted shouldBe 300
            sharingCrowd(29)
            val again = applyFeed(sourceId = pa.id, bytes = feedBytes(items = items + a.item(seed = "new"), complete = false))
            again.accepted shouldBe 300
            again.rejected shouldBe 1
            rows() shouldHaveSize 300
        }

        test("rejections are counted by cause and their sum is the total: signature, unknown key of the origin, expired, capacity") {
            val a = OriginServer(host = "a.beispiel.de")
            val stranger = OriginServer(host = "stranger.beispiel.de")
            val pa = direct(a)
            val forged = a.item(seed = "f1", signature = "AAAA")
            val unknownKey = stranger.item(seed = "u1", hops = 1, via = listOf("a.beispiel.de"))
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = listOf(forged, unknownKey), complete = false))
            val expiring = a.item(seed = "e1", date = PULL_TODAY)
            // the reader still sees the day as today, the store (later "now") sees it as over
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = listOf(expiring), complete = false),
                now = LocalDateTime(2026, 11, 21, 10, 0, 0),
            )
            val by = CarpoolPullCounters.rejectedByReason()
            by.getValue(CarpoolReceivedRejectReason.SIGNATURE) shouldBe 1L
            by.getValue(CarpoolReceivedRejectReason.UNKNOWN_ORIGIN_KEY) shouldBe 1L
            by.getValue(CarpoolReceivedRejectReason.EXPIRED) shouldBe 1L
            CarpoolPullCounters.rejected.get() shouldBe by.values.sum()
            CarpoolPullCounters.unknownOriginKey.get() shouldBe 1L
            CarpoolReceivedRejectReason.entries.all { by.containsKey(it) } shouldBe true
        }

        test("local blocks of one source stay at 5000: the oldest of THAT source make room, another source keeps all of its blocks") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val other = direct(OriginServer(host = "o9.beispiel.de"))
            preloadBlocks(reporter = other.id, count = 50, tag = "other")
            preloadBlocks(reporter = pa.id, count = CarpoolReceivedStore.MAX_BLOCKS_PER_SOURCE - 10, tag = "old")
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = (1..20).map { a.item(seed = "w$it") }, complete = false))
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = emptyList(), withdrawn = (1..20).map { hexId("w$it") }, complete = false),
            ).withdrawn shouldBe 20
            // 4990 + 20 = 5010 -> the ten oldest of this source went; the 20 new ones are in
            blocksOf(pa.id) shouldHaveSize CarpoolReceivedStore.MAX_BLOCKS_PER_SOURCE
            blocksOf(pa.id).containsAll((1..20).map { hexId("w$it") }) shouldBe true
            blocksOf(other.id) shouldHaveSize 50
            rows().shouldBeEmpty()
        }

        test("at 20000 live blocks in total a withdrawal still deletes the copy but writes no block") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            val q = direct(OriginServer(host = "q9.beispiel.de"))
            preloadBlocks(reporter = q.id, count = CarpoolReceivedStore.MAX_BLOCKS_TOTAL - 3, tag = "q")
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = (1..10).map { a.item(seed = "t$it") }, complete = false))
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = emptyList(), withdrawn = (1..10).map { hexId("t$it") }, complete = false),
            ).withdrawn shouldBe 10
            rows().shouldBeEmpty()
            blocksOf(pa.id) shouldHaveSize 3
            transaction { CarpoolReceivedStore.countBlocksActive(PULL_NOW) } shouldBe CarpoolReceivedStore.MAX_BLOCKS_TOTAL
        }

        test("the pass-on tombstones of relayed withdrawals stop at their cap and are counted; without a cap everything is written") {
            val targets =
                (1..4).map {
                    CarpoolRelayedTarget(
                        teaserId = hexId("rl$it"),
                        departureDate = PULL_DEPARTURE,
                        originHost = "o.beispiel.de",
                    )
                }
            val written =
                transaction {
                    CarpoolFederationStore.withdrawRelayed(
                        targets = targets,
                        partnerId = null,
                        now = PULL_NOW,
                        requireServedSince = null,
                        maxRelayRows = 2,
                    )
                }
            written shouldBe 2
            CarpoolPullCounters.relayTombstonesSkipped.get() shouldBe 2L
            transaction {
                CarpoolFederationStore.withdrawRelayed(targets = targets, partnerId = null, now = PULL_NOW, requireServedSince = null)
            }
            allTombstones() shouldHaveSize 4
            CarpoolPullCounters.relayTombstonesSkipped.get() shouldBe 2L
        }

        test("deleteExpired with a limit deletes in batches, is idempotent and never touches a live row; the count helpers see the lag") {
            val a = OriginServer(host = "a.beispiel.de")
            val pa = direct(a)
            applyFeed(sourceId = pa.id, bytes = feedBytes(items = (1..7).map { a.item(seed = "x$it") }, complete = false))
            val later = LocalDateTime(2026, 12, 1, 0, 0, 0)
            transaction { CarpoolReceivedStore.deleteExpired(now = PULL_NOW, limit = 3) } shouldBe 0
            transaction { CarpoolReceivedStore.countStoredRows() } shouldBe 7
            transaction { CarpoolReceivedStore.countTotal(later) } shouldBe 0
            transaction { CarpoolReceivedStore.deleteExpired(now = later, limit = 3) } shouldBe 3
            transaction { CarpoolReceivedStore.deleteExpired(now = later, limit = 3) } shouldBe 3
            transaction { CarpoolReceivedStore.deleteExpired(now = later, limit = 3) } shouldBe 1
            transaction { CarpoolReceivedStore.deleteExpired(now = later, limit = 3) } shouldBe 0
            rows().shouldBeEmpty()
            shouldThrow<IllegalArgumentException> { transaction { CarpoolReceivedStore.deleteExpired(now = later, limit = 0) } }
        }

        test("expired pass-on tombstones are deleted in batches too") {
            transaction {
                CarpoolFederationStore.withdrawRelayed(
                    targets =
                        (1..5).map {
                            CarpoolRelayedTarget(
                                teaserId = hexId("dt$it"),
                                departureDate = PULL_DEPARTURE,
                                originHost = "o.beispiel.de",
                            )
                        },
                    partnerId = null,
                    now = PULL_NOW,
                    requireServedSince = null,
                )
            }
            val later = LocalDateTime(2026, 12, 20, 0, 0, 0)
            transaction { CarpoolFederationStore.deleteExpiredTombstones(now = PULL_NOW, limit = 2) } shouldBe 0
            transaction { CarpoolFederationStore.deleteExpiredTombstones(now = later, limit = 2) } shouldBe 2
            transaction { CarpoolFederationStore.deleteExpiredTombstones(now = later, limit = 2) } shouldBe 2
            transaction { CarpoolFederationStore.deleteExpiredTombstones(now = later, limit = 2) } shouldBe 1
            transaction { CarpoolFederationStore.deleteExpiredTombstones(now = later, limit = 2) } shouldBe 0
            transaction { CarpoolFederationStore.countTombstoneRows() } shouldBe 0
        }

        test("blocks per reporter: only live ones count") {
            val a = direct(OriginServer(host = "a.beispiel.de"))
            val b = direct(OriginServer(host = "b3.beispiel.de"))
            preloadBlocks(reporter = a.id, count = 3, tag = "ca")
            preloadBlocks(reporter = b.id, count = 2, tag = "cb")
            transaction { CarpoolReceivedStore.countBlocksByReporter(PULL_NOW) } shouldBe mapOf(a.id to 3, b.id to 2)
            transaction { CarpoolReceivedStore.countBlocksByReporter(LocalDateTime(2026, 12, 2, 0, 0, 0)) } shouldBe emptyMap()
        }
    })
