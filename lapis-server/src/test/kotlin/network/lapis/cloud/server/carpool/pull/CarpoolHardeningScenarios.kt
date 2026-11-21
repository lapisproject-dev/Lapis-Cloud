package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolOriginSignatures
import network.lapis.cloud.server.carpool.CarpoolPullCounters
import network.lapis.cloud.server.carpool.CarpoolReceivedStore
import network.lapis.cloud.server.carpool.CarpoolRelayedTarget
import network.lapis.cloud.server.carpool.CarpoolWithdrawalTarget
import network.lapis.cloud.server.carpool.allTombstones
import network.lapis.cloud.server.carpool.clearTombstones
import network.lapis.cloud.server.carpool.feed.TEST_IDS
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.carpool.feed.provisionOwnSigningKey
import network.lapis.cloud.server.carpool.mayPullCarpoolFeed
import network.lapis.cloud.server.carpool.recordServed
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.db.generated.PartnerSigningKeyHoldTable
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.SigningKeyFingerprint
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
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
import network.lapis.cloud.shared.domain.CarpoolTeaser
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val LATER = PULL_NOW.let { LocalDateTime(it.year, 12, 4, 10, 0, 0) }

/**
 * V1.9.319 (wave 7) -- the four hardening fixes of the carpool federation, on H2 AND PostgreSQL with the same assertions:
 * F1 a changed signing key is never re-pinned silently (direct partner: pending key + pause + admin accept; discovered server: hold that survives
 * the eviction of the row), F2 a foreign partner cannot erase our OWN withdrawal (provenance of pass-on tombstones, cap per reporter),
 * F3 the local block against a replay lasts until the hint could no longer be imported, F4 a restore switches the exchange off and clears every consent.
 */
abstract class CarpoolHardeningScenarios(
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
                PartnerSigningKeyHoldTable.deleteAll()
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

        fun direct(o: OriginServer) = newFeedPartner(host = o.host, keyPair = o.keyPair)

        fun ids() = transaction { CarpoolReceivedHintTable.selectAll().map { it[CarpoolReceivedHintTable.teaserId] }.toSet() }

        fun partnerRow(id: Uuid) = transaction { PartnerServerStore.findPartner(id)!! }

        fun poll(
            id: Uuid,
            key: PinnedSigningKey?,
            now: LocalDateTime = PULL_NOW,
        ) = transaction {
            PartnerServerStore.applyFetchSuccess(
                id = id,
                parsed =
                    PartnerParseResult.Ok(
                        remoteServerId = partnerRow(id).remoteServerId!!,
                        remoteName = "P",
                        events = emptyList(),
                        partnersJson = null,
                        droppedEvents = 0,
                        signingKey = key,
                    ),
                etag = null,
                now = now,
            )
        }

        fun mayPull(id: Uuid) = transaction { partnerRow(id).mayPullCarpoolFeed(PartnerServerStore.blockPatterns()) }

        // ── F1: direct partner ────────────────────────────────────────────────────────────────────────────────────────────

        test(
            "F1: a changed key is never re-pinned; the exchange pauses, the copies go, the pass-on is reported, the pull is no longer due",
        ) {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "k1", maxHops = 2))))
            ids() shouldBe setOf(hexId("k1"))
            recordServed(partnerId = p.id, at = LocalDateTime(2026, 11, 19, 10, 5, 0))
            mayPull(p.id) shouldBe true
            transaction { PartnerServerStore.dueForCarpoolPull(now = PULL_NOW, limit = 10) }.map { it.id } shouldBe listOf(p.id)

            val intruder = FederationKeyPairGenerator.generate()
            poll(p.id, PinnedSigningKey(keyId = o.keyId, publicKeyPem = intruder.publicKeyPem))

            val row = partnerRow(p.id)
            row.signingPublicKeyPem shouldBe o.keyPair.publicKeyPem
            row.signingKeyPendingPem shouldBe intruder.publicKeyPem
            row.signingKeyChangedAt shouldBe PULL_NOW
            mayPull(p.id) shouldBe false
            transaction { PartnerServerStore.dueForCarpoolPull(now = PULL_NOW, limit = 10) }.shouldBeEmpty()
            transaction { PartnerServerStore.pinnedSigningKeyFor("a.beispiel.de") } shouldBe null
            ids().shouldBeEmpty()
            // hops 1 < max 2: the copy could have travelled, so it is reported as withdrawn
            allTombstones().map { it[CarpoolTeaserTombstoneTable.teaserId] } shouldBe listOf(hexId("k1"))
            // an apply based on the old pin is void
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(o.item(seed = "k2")))).applied shouldBe false
        }

        test("F1: a feed without a key, then a feed with a NEW key, never re-pins (the second silent path)") {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            poll(p.id, null)
            partnerRow(p.id).signingKeyChangedAt shouldBe PULL_NOW
            val other = FederationKeyPairGenerator.generate()
            poll(p.id, PinnedSigningKey(keyId = o.keyId, publicKeyPem = other.publicKeyPem), now = LATER)
            val row = partnerRow(p.id)
            row.signingPublicKeyPem shouldBe o.keyPair.publicKeyPem
            row.signingKeyPendingPem shouldBe other.publicKeyPem
            row.signingKeyChangedAt shouldBe PULL_NOW // the first sighting stays the moment of the change
            mayPull(p.id) shouldBe false
        }

        test("F1: the identical old key coming back ends the pause (no new trust)") {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            poll(p.id, PinnedSigningKey(keyId = o.keyId, publicKeyPem = FederationKeyPairGenerator.generate().publicKeyPem))
            mayPull(p.id) shouldBe false
            poll(p.id, PinnedSigningKey(keyId = o.keyId, publicKeyPem = o.keyPair.publicKeyPem), now = LATER)
            partnerRow(p.id).signingKeyChangedAt shouldBe null
            partnerRow(p.id).signingKeyPendingPem shouldBe null
            mayPull(p.id) shouldBe true
        }

        test(
            "F1: accepting needs the exact fingerprint; a wrong one changes nothing; the right one pins, ends the pause and makes the pull due",
        ) {
            val o = OriginServer(host = "a.beispiel.de")
            val p = direct(o)
            val other = FederationKeyPairGenerator.generate()
            poll(p.id, PinnedSigningKey(keyId = o.keyId, publicKeyPem = other.publicKeyPem))
            val wrong = SigningKeyFingerprint.of(o.keyPair.publicKeyPem)
            transaction { PartnerServerStore.acceptPendingSigningKey(id = p.id, expectedFingerprint = wrong, now = LATER) } shouldBe
                PartnerServerStore.AcceptKeyResult.Mismatch
            partnerRow(p.id).signingPublicKeyPem shouldBe o.keyPair.publicKeyPem
            val result =
                transaction {
                    PartnerServerStore.acceptPendingSigningKey(
                        id = p.id,
                        expectedFingerprint = SigningKeyFingerprint.of(other.publicKeyPem),
                        now = LATER,
                    )
                }
            result.shouldBeInstanceOf<PartnerServerStore.AcceptKeyResult.Accepted>()
            result.oldFingerprint shouldBe wrong
            partnerRow(p.id).signingPublicKeyPem shouldBe other.publicKeyPem
            partnerRow(p.id).signingKeyChangedAt shouldBe null
            partnerRow(p.id).carpoolPullNextAt shouldBe LATER
            mayPull(p.id) shouldBe true
            // nothing is pending any more
            transaction {
                PartnerServerStore.acceptPendingSigningKey(
                    id = p.id,
                    expectedFingerprint = result.newFingerprint,
                    now = LATER,
                )
            } shouldBe
                PartnerServerStore.AcceptKeyResult.Mismatch
        }

        // ── F1: discovered server ─────────────────────────────────────────────────────────────────────────────────────────

        test(
            "F1: a discovered server whose key changed is held; the hold survives the eviction and re-creation of the row; an admin releases it",
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
                        etag = null,
                        now = PULL_NOW,
                    )
                }

            fun pinned() = transaction { PartnerDiscoveryStore.pinnedSigningKeyFor("o.beispiel.de") }

            fun held() = transaction { PartnerDiscoveryStore.heldHosts() }
            write(
                PinnedSigningKey(keyId = o.keyId, publicKeyPem = o.keyPair.publicKeyPem),
            ).shouldBeInstanceOf<DiscoveredWriteResult.Stored>()
            pinned() shouldBe o.keyPair.publicKeyPem
            held().shouldBeEmpty()
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = listOf(o.item(seed = "d1", maxHops = 3, hops = 1, via = listOf("a.beispiel.de")))),
            ).accepted shouldBe 1

            val intruder = FederationKeyPairGenerator.generate()
            write(PinnedSigningKey(keyId = o.keyId, publicKeyPem = intruder.publicKeyPem))
            pinned() shouldBe null
            held() shouldBe setOf("o.beispiel.de")
            ids().shouldBeEmpty() // the copies this host signed as origin are gone
            // the row is evicted and created anew (what the recursion does to stale rows): the hold still stands
            transaction { PartnerDiscoveredServerTable.deleteAll() }
            write(PinnedSigningKey(keyId = o.keyId, publicKeyPem = intruder.publicKeyPem))
            pinned() shouldBe null
            val r =
                applyFeed(
                    sourceId = pa.id,
                    bytes = feedBytes(items = listOf(o.item(seed = "d2", maxHops = 3, hops = 1, via = listOf("a.beispiel.de")))),
                )
            r.accepted shouldBe 0
            r.unknownOriginKey shouldBe 1
            // an administrator releases the hold: the next fetch pins anew
            transaction { PartnerDiscoveryStore.releaseSigningKeyHold("o.beispiel.de") } shouldBe true
            transaction { PartnerDiscoveryStore.releaseSigningKeyHold("o.beispiel.de") } shouldBe false
            write(PinnedSigningKey(keyId = o.keyId, publicKeyPem = intruder.publicKeyPem))
            pinned() shouldBe intruder.publicKeyPem
        }

        // ── F2: provenance of pass-on tombstones ──────────────────────────────────────────────────────────────────────────

        test("F2: a partner that learned our teaser id cannot erase our OWN withdrawal; both rows exist, the wire reports the hint once") {
            val h = OriginServer(host = "h.beispiel.de")
            val p = direct(h)
            val posting = Uuid.random()
            val x = TEST_IDS.of(posting)
            transaction {
                CarpoolFederationStore.withdraw(
                    targets = listOf(CarpoolWithdrawalTarget(postingId = posting, departureDate = PULL_DEPARTURE)),
                    partnerId = null,
                    ids = TEST_IDS,
                    now = PULL_NOW,
                    clearOptIn = false,
                    requireServed = false,
                )
            }

            fun item() =
                run {
                    val teaser = CarpoolTeaser(teaserId = x, departureDate = PULL_DEPARTURE, fromPostcodeArea = "38", toPostcodeArea = "10")
                    network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem(
                        originHost = h.host,
                        hops = 0,
                        maxHops = 2,
                        teaser = teaser,
                        via = emptyList(),
                        originSignature = CarpoolOriginSignatures.sign(teaser = teaser, originHost = h.host, maxHops = 2, key = h.signing),
                    )
                }
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(item()))).accepted shouldBe 1
            recordServed(partnerId = p.id, at = LocalDateTime(2026, 11, 19, 10, 5, 0))
            applyFeed(sourceId = p.id, bytes = feedBytes(items = emptyList(), withdrawn = listOf(x), complete = false)).withdrawn shouldBe 1
            allTombstones() shouldHaveSize 2
            allTombstones().count { it[CarpoolTeaserTombstoneTable.relayOriginHost] == null } shouldBe 1
            allTombstones().single {
                it[CarpoolTeaserTombstoneTable.relayOriginHost] != null
            }[CarpoolTeaserTombstoneTable.relayReporterPartnerId] shouldBe
                p.id
            transaction {
                CarpoolFederationStore.liveWithdrawals(
                    partnerId = Uuid.random(),
                    now = PULL_NOW,
                    scanLimit = 100,
                )
            }.rows shouldHaveSize
                1
            // the "attack": the partner lists the hint as its own again
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(item()))).accepted shouldBe 1
            val remaining = allTombstones()
            remaining shouldHaveSize 1
            remaining.single()[CarpoolTeaserTombstoneTable.relayOriginHost] shouldBe null
            remaining.single()[CarpoolTeaserTombstoneTable.teaserId] shouldBe x
        }

        test("F2: a hostile partner listing our withdrawn id as its own item does not make us drop our withdrawal for a THIRD partner") {
            val h = OriginServer(host = "h.beispiel.de")
            val p = direct(h)
            val third = newFeedPartner(host = "p2.beispiel.de")
            val posting = Uuid.random()
            val x = TEST_IDS.of(posting)
            transaction {
                CarpoolFederationStore.withdraw(
                    targets = listOf(CarpoolWithdrawalTarget(postingId = posting, departureDate = PULL_DEPARTURE)),
                    partnerId = null,
                    ids = TEST_IDS,
                    now = PULL_NOW,
                    clearOptIn = false,
                    requireServed = false,
                )
            }
            val teaser = CarpoolTeaser(teaserId = x, departureDate = PULL_DEPARTURE, fromPostcodeArea = "38", toPostcodeArea = "10")
            val hostile =
                network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem(
                    originHost = h.host,
                    hops = 0,
                    maxHops = 2,
                    teaser = teaser,
                    via = emptyList(),
                    originSignature = CarpoolOriginSignatures.sign(teaser = teaser, originHost = h.host, maxHops = 2, key = h.signing),
                )
            applyFeed(sourceId = p.id, bytes = feedBytes(items = listOf(hostile))).accepted shouldBe 1
            val partner = transaction { PartnerServerStore.findPartner(third.id)!! }
            val built =
                transaction {
                    network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedBuilder.build(
                        partner = partner,
                        settings = CarpoolFederationStore.settings(),
                        ids = TEST_IDS,
                        originHost = PULL_OWN_HOST,
                        today = PULL_TODAY,
                        now = PULL_NOW,
                        ownKey =
                            network.lapis.cloud.server.carpool.CarpoolOwnKeys
                                .load(),
                    )
                }
            val feed = parseOk(bytes = built.body, sourceHost = PULL_OWN_HOST, ownHost = "p2.beispiel.de")
            feed.items.map { it.teaser.teaserId }.contains(x) shouldBe false
            feed.withdrawnIds shouldBe listOf(x)
        }

        test("F2: the owner giving the consent again removes only the OWN tombstone, never a pass-on row") {
            val posting = Uuid.random()
            val x = TEST_IDS.of(posting)
            transaction {
                CarpoolFederationStore.withdraw(
                    targets = listOf(CarpoolWithdrawalTarget(postingId = posting, departureDate = PULL_DEPARTURE)),
                    partnerId = null,
                    ids = TEST_IDS,
                    now = PULL_NOW,
                    clearOptIn = false,
                    requireServed = false,
                )
                CarpoolFederationStore.withdrawRelayed(
                    targets = listOf(CarpoolRelayedTarget(teaserId = x, departureDate = PULL_DEPARTURE, originHost = "h.beispiel.de")),
                    partnerId = null,
                    now = PULL_NOW,
                    requireServedSince = null,
                )
            }
            allTombstones() shouldHaveSize 2
            transaction { CarpoolFederationStore.forgetOwnGlobalTombstone(x) }
            allTombstones().single()[CarpoolTeaserTombstoneTable.relayOriginHost] shouldBe "h.beispiel.de"
        }

        test("F2: a window full of pass-on rows cannot push a later OWN withdrawal out of the served list") {
            clearTombstones()
            val earlier = LocalDateTime(PULL_NOW.date, kotlinx.datetime.LocalTime(1, 0, 0))
            val expires = PULL_NOW.date.plus(DatePeriod(days = 10)).let { LocalDateTime(it, PULL_NOW.time) }
            val own = hexId("own-withdrawal")
            val reporter = Uuid.random()
            transaction {
                (1..150).forEach { n ->
                    CarpoolTeaserTombstoneTable.insert {
                        it[id] = Uuid.random()
                        it[teaserId] = hexId("relay$n")
                        it[relayOriginHost] = "o.beispiel.de"
                        it[relayReporterPartnerId] = reporter
                        it[createdAt] = earlier
                        it[expiresAt] = expires
                    }
                }
                CarpoolTeaserTombstoneTable.insert {
                    it[id] = Uuid.random()
                    it[teaserId] = own
                    it[createdAt] = PULL_NOW
                    it[expiresAt] = expires
                }
            }
            val live = transaction { CarpoolFederationStore.liveWithdrawals(partnerId = Uuid.random(), now = PULL_NOW, scanLimit = 50) }
            live.scanCapped shouldBe true
            live.rows.first().teaserId shouldBe own
            live.rows.first().own shouldBe true
            clearTombstones()
        }

        test(
            "F2: a window full of pass-on rows of ONE reporter cannot push a later pass-on row of ANOTHER reporter out of the served list",
        ) {
            clearTombstones()
            val earlier = LocalDateTime(PULL_NOW.date, kotlinx.datetime.LocalTime(1, 0, 0))
            val later = LocalDateTime(PULL_NOW.date, kotlinx.datetime.LocalTime(2, 0, 0))
            val expires = PULL_NOW.date.plus(DatePeriod(days = 10)).let { LocalDateTime(it, PULL_NOW.time) }
            val hostile = Uuid.random()
            val honest = Uuid.random()
            val honestRow = hexId("honest-relay")
            transaction {
                (1..300).forEach { n ->
                    CarpoolTeaserTombstoneTable.insert {
                        it[id] = Uuid.random()
                        it[teaserId] = hexId("hostile$n")
                        it[relayOriginHost] = "o.beispiel.de"
                        it[relayReporterPartnerId] = hostile
                        it[createdAt] = earlier
                        it[expiresAt] = expires
                    }
                }
                CarpoolTeaserTombstoneTable.insert {
                    it[id] = Uuid.random()
                    it[teaserId] = honestRow
                    it[relayOriginHost] = "o.beispiel.de"
                    it[relayReporterPartnerId] = honest
                    it[createdAt] = later
                    it[expiresAt] = expires
                }
            }
            val live = transaction { CarpoolFederationStore.liveWithdrawals(partnerId = Uuid.random(), now = PULL_NOW, scanLimit = 200) }
            live.scanCapped shouldBe true
            live.rows.map { it.teaserId } shouldContain honestRow
            clearTombstones()
        }

        test("F2: one reporter cannot use up the table; the pass-on tombstones of another reporter are still written") {
            CarpoolReceivedStore.MAX_RELAY_TOMBSTONES_PER_REPORTER shouldBe 5000
            val h = Uuid.random()
            val k = Uuid.random()

            fun targets(
                prefix: String,
                n: Int,
            ) = (1..n).map {
                CarpoolRelayedTarget(teaserId = hexId("$prefix$it"), departureDate = PULL_DEPARTURE, originHost = "o.beispiel.de")
            }
            // the foreign keys do not exist for these reporters (the column has deliberately none)
            val fromH =
                transaction {
                    CarpoolFederationStore.withdrawRelayed(
                        targets = targets("fh", 60),
                        partnerId = null,
                        now = PULL_NOW,
                        requireServedSince = null,
                        maxRelayRows = 1000,
                        reporterId = h,
                        maxRelayRowsPerReporter = 50,
                    )
                }
            fromH shouldBe 50
            val fromK =
                transaction {
                    CarpoolFederationStore.withdrawRelayed(
                        targets = targets("fk", 10),
                        partnerId = null,
                        now = PULL_NOW,
                        requireServedSince = null,
                        maxRelayRows = 1000,
                        reporterId = k,
                        maxRelayRowsPerReporter = 50,
                    )
                }
            fromK shouldBe 10
            allTombstones() shouldHaveSize 60
            CarpoolPullCounters.relayTombstonesSkipped.get() shouldBe 10L
        }

        // ── F3: the block against a replay ────────────────────────────────────────────────────────────────────────────────

        test("F3: the local block lasts until two days after the departure; a replay after 15 days over another path is still blocked") {
            val far = PULL_TODAY.plus(DatePeriod(days = 40))
            val o = OriginServer(host = "o.beispiel.de")
            val relayA = OriginServer(host = "a.beispiel.de")
            val relayB = OriginServer(host = "b2.beispiel.de")
            val pa = direct(relayA)
            val pb = direct(relayB)
            transaction {
                PartnerDiscoveredServerTable.insert {
                    it[id] = Uuid.random()
                    it[host] = o.host
                    it[rootPartnerServerId] = pa.id
                    it[hops] = 2.toShort()
                    it[createdAt] = PULL_NOW
                    it[nextFetchAt] = PULL_NOW
                    it[signingKeyId] = o.keyId
                    it[signingPublicKeyPem] = o.keyPair.publicKeyPem
                    it[signingKeyPinnedAt] = PULL_NOW
                }
            }
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = listOf(o.item(seed = "z", maxHops = 3, hops = 1, via = listOf("a.beispiel.de"), date = far))),
            ).accepted shouldBe 1
            applyFeed(
                sourceId = pa.id,
                bytes = feedBytes(items = emptyList(), withdrawn = listOf(hexId("z")), complete = false),
            ).withdrawn shouldBe
                1
            val block = transaction { CarpoolReceivedTombstoneTable.selectAll().single() }
            CarpoolReceivedStore.CarpoolReceivedBlockPolicy.expiresAt(far) shouldBe block[CarpoolReceivedTombstoneTable.expiresAt]
            // the wire tombstone keeps its 14 days (a partner never needs it longer)
            // 15 days later another relay offers the SAME signed hint
            val replay =
                applyFeed(
                    sourceId = pb.id,
                    bytes =
                        feedBytes(
                            items = listOf(o.item(seed = "z", maxHops = 3, hops = 1, via = listOf("b2.beispiel.de"), date = far)),
                        ),
                    now = LATER,
                )
            replay.accepted shouldBe 0
            replay.rejected shouldBe 1
            CarpoolPullCounters.rejectedByReason()[CarpoolReceivedRejectReason.BLOCKED] shouldBe 1L
            ids().shouldBeEmpty()
        }

        // ── F4: restore ───────────────────────────────────────────────────────────────────────────────────────────────────

        test(
            "F4: after a restore the exchange is off and every consent is cleared; one audit entry without actor; nothing to reset writes nothing",
        ) {
            val owner = data.member(label = "w7-f4")
            val posting = data.posting(author = owner)
            transaction { CarpoolFederationStore.setOptIn(postingId = posting, value = true, now = PULL_NOW) }
            transaction { CarpoolFederationStore.optIn(posting) } shouldBe true
            val auditsBefore = transaction { AuditLogEntryTable.selectAll().count() }
            transaction { CarpoolFederationStore.resetAfterRestore(PULL_NOW) } shouldBe true
            transaction { CarpoolFederationStore.settings().enabled } shouldBe false
            transaction { CarpoolFederationStore.optIn(posting) } shouldBe false
            transaction {
                CarpoolPostingTable.selectAll().where { CarpoolPostingTable.id eq posting }.single()[CarpoolPostingTable.federationOptInAt]
            } shouldBe null
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditsBefore + 1
            // a second reset has nothing to do
            transaction { CarpoolFederationStore.resetAfterRestore(PULL_NOW) } shouldBe false
            transaction { AuditLogEntryTable.selectAll().count() } shouldBe auditsBefore + 1
        }
    })
