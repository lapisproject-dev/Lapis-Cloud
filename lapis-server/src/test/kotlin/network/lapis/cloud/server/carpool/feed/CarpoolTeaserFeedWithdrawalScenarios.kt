package network.lapis.cloud.server.carpool.feed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolOwnKeys
import network.lapis.cloud.server.carpool.CarpoolParticipations
import network.lapis.cloud.server.carpool.CarpoolTeaserTombstonePolicy
import network.lapis.cloud.server.carpool.CarpoolWithdrawalTarget
import network.lapis.cloud.server.carpool.allTombstones
import network.lapis.cloud.server.carpool.clearTombstones
import network.lapis.cloud.server.carpool.recordServed
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable as Tomb

/**
 * V1.9.313 "Mitfahrzentrale-Foederation" W3 -- the wire of the withdrawal list (`withdrawn`) and the trailing answer, on H2 AND PostgreSQL: the shape,
 * the scope per partner, "item wins", the caps, the ETag, the trailing answer for a partner that is no longer shared with (and its byte-identical
 * refusals), the rate limits, the audit entry and the read order under a concurrent withdrawal.
 */
abstract class CarpoolTeaserFeedWithdrawalScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()
        val json = Json

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
            provisionOwnSigningKey()
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            transaction {
                exec("UPDATE carpool_posting SET federation_opt_in = FALSE")
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
                Tomb.deleteAll()
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }
        }
        afterSpec {
            try {
                transaction {
                    PartnerEventTable.deleteAll()
                    PartnerServerTable.deleteAll()
                    PartnerBlocklistTable.deleteAll()
                    Tomb.deleteAll()
                    CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2)
                }
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun hintId(n: Int) = TEST_IDS.of(Uuid.parse("00000000-0000-4000-8000-" + n.toString().padStart(12, '0')))

        fun withdraw(
            teaserOf: Uuid,
            partnerId: Uuid? = null,
            now: LocalDateTime = DbClock.nowLocalDateTime(),
            departure: LocalDate = OrganizationTimeZone.today().plus(DatePeriod(days = 10)),
        ) = transaction {
            CarpoolFederationStore.withdraw(
                targets = listOf(CarpoolWithdrawalTarget(postingId = teaserOf, departureDate = departure)),
                partnerId = partnerId,
                ids = TEST_IDS,
                now = now,
                clearOptIn = false,
                requireServed = false,
            )
        }

        fun withdrawn(body: String) =
            json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("withdrawn")
                .jsonArray
                .map { it.jsonObject }

        fun ids(body: String) = withdrawn(body).map { it.getValue("teaserId").jsonPrimitive.content }

        fun plusSeconds(
            at: LocalDateTime,
            s: Int,
        ) = (at.toInstant(TimeZone.UTC) + s.seconds).toLocalDateTime(TimeZone.UTC)

        test("the list carries exactly the pseudonymous id and the UTC day; empty by default; the envelope keeps its shape") {
            val partner = newFeedPartner()
            val trip = Uuid.random()
            feedApplication {
                val empty = json.parseToJsonElement(client.feedGet(signed = signedGet(partner = partner)).bodyAsText()).jsonObject
                empty.getValue("withdrawn").jsonArray shouldHaveSize 0
                withdraw(teaserOf = trip)
                val body = client.feedGet(signed = signedGet(partner = partner)).bodyAsText()
                val entry = withdrawn(body).single()
                entry.keys shouldBe setOf("teaserId", "withdrawnAt")
                entry.getValue("teaserId").jsonPrimitive.content shouldBe TEST_IDS.of(trip)
                entry.getValue("withdrawnAt").jsonPrimitive.content shouldBe
                    DbClock.nowLocalDateTime().date.toString()
                body shouldNotContain trip.toString()
                json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("complete")
                    .jsonPrimitive.boolean shouldBe true
            }
        }

        test("a tombstone scoped to one partner reaches only that partner; a global one reaches everybody; an expired one nobody") {
            val p = newFeedPartner()
            val q = newFeedPartner()
            val forP = Uuid.random()
            val global = Uuid.random()
            val old = Uuid.random()
            withdraw(teaserOf = forP, partnerId = p.id)
            withdraw(teaserOf = global)
            withdraw(teaserOf = old)
            transaction {
                Tomb.update({ Tomb.teaserId eq TEST_IDS.of(old) }) {
                    it[createdAt] = plusSeconds(DbClock.nowLocalDateTime(), -7200)
                    it[expiresAt] = plusSeconds(DbClock.nowLocalDateTime(), -3600)
                }
            }
            feedApplication {
                ids(client.feedGet(signed = signedGet(partner = p)).bodyAsText()).toSet() shouldBe
                    setOf(TEST_IDS.of(forP), TEST_IDS.of(global))
                ids(client.feedGet(signed = signedGet(partner = q)).bodyAsText()) shouldBe listOf(TEST_IDS.of(global))
            }
        }

        test("one entry per hint (the earliest day wins), sorted by day descending and then by id") {
            val p = newFeedPartner()
            val now = DbClock.nowLocalDateTime()
            val a = Uuid.random()
            val b = Uuid.random()
            val c = Uuid.random()
            withdraw(teaserOf = a, now = plusSeconds(now, -2 * 86_400))
            withdraw(teaserOf = a, partnerId = p.id, now = plusSeconds(now, -3 * 86_400))
            withdraw(teaserOf = b, now = plusSeconds(now, -86_400))
            withdraw(teaserOf = c, now = now)
            feedApplication {
                val list = withdrawn(client.feedGet(signed = signedGet(partner = p)).bodyAsText())
                list shouldHaveSize 3
                list.map { it.getValue("teaserId").jsonPrimitive.content } shouldBe
                    listOf(TEST_IDS.of(c), TEST_IDS.of(b), TEST_IDS.of(a))
                list
                    .last()
                    .getValue("withdrawnAt")
                    .jsonPrimitive.content shouldBe
                    plusSeconds(now, -3 * 86_400).date.toString()
            }
        }

        test("item wins: a hint that is an item again is never also in the withdrawals") {
            val author = data.member(label = "wdf-item")
            val trip = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")
            val other = Uuid.random()
            transaction { CarpoolFederationStore.setOptIn(postingId = trip, value = true) }
            withdraw(teaserOf = trip)
            withdraw(teaserOf = other)
            val p = newFeedPartner()
            feedApplication {
                val body = client.feedGet(signed = signedGet(partner = p)).bodyAsText()
                val items =
                    json
                        .parseToJsonElement(body)
                        .jsonObject
                        .getValue("items")
                        .jsonArray
                        .map {
                            it.jsonObject
                                .getValue("teaser")
                                .jsonObject
                                .getValue("teaserId")
                                .jsonPrimitive.content
                        }
                items shouldBe listOf(TEST_IDS.of(trip))
                ids(body) shouldBe listOf(TEST_IDS.of(other))
            }
        }

        test("the caps: the list is cut at MAX_WITHDRAWN and by bytes (items first, then the list) and `complete` turns false") {
            val p = newFeedPartner()
            val now = DbClock.nowLocalDateTime()
            transaction {
                (1..CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN + 5).forEach { n ->
                    CarpoolFederationStore.withdraw(
                        targets =
                            listOf(
                                CarpoolWithdrawalTarget(
                                    postingId = Uuid.random(),
                                    departureDate = OrganizationTimeZone.today().plus(DatePeriod(days = 10)),
                                ),
                            ),
                        partnerId = null,
                        ids = TEST_IDS,
                        now = plusSeconds(now, -n),
                        clearOptIn = false,
                        requireServed = false,
                    )
                }
            }
            val row = transaction { PartnerServerStore.findPartner(p.id)!! }
            val settings = CarpoolFederationStore.let { transaction { it.settings() } }
            val capped =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = row,
                        settings = settings,
                        ids = TEST_IDS,
                        ownKey = CarpoolOwnKeys.load(),
                        originHost = OWN_HOST,
                        today = OrganizationTimeZone.today(),
                        now = now,
                    )
                }
            capped.withdrawnCount shouldBe CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN
            capped.complete shouldBe false
            val small =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = row,
                        settings = settings,
                        ids = TEST_IDS,
                        ownKey = CarpoolOwnKeys.load(),
                        originHost = OWN_HOST,
                        today = OrganizationTimeZone.today(),
                        maxBytes = 4_000,
                        now = now,
                    )
                }
            (small.body.size <= 4_000) shouldBe true
            (small.withdrawnCount < CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN) shouldBe true
            small.complete shouldBe false
        }

        test("the ETag changes with a new and with an expired tombstone; an old ETag after a withdrawal gets 200, the new one 304") {
            val p = newFeedPartner()
            feedApplication {
                val first = client.feedGet(signed = signedGet(partner = p))
                val etag0 = first.headers[HttpHeaders.ETag]!!
                val trip = Uuid.random()
                withdraw(teaserOf = trip)
                val after = client.feedGet(signed = signedGet(partner = p)) { header(HttpHeaders.IfNoneMatch, etag0) }
                after.status shouldBe HttpStatusCode.OK
                val etag1 = after.headers[HttpHeaders.ETag]!!
                etag1 shouldNotBe etag0
                client.feedGet(signed = signedGet(partner = p)) { header(HttpHeaders.IfNoneMatch, etag1) }.status shouldBe
                    HttpStatusCode.NotModified
                transaction {
                    Tomb.update({ Tomb.teaserId eq TEST_IDS.of(trip) }) {
                        it[createdAt] = plusSeconds(DbClock.nowLocalDateTime(), -7200)
                        it[expiresAt] = plusSeconds(DbClock.nowLocalDateTime(), -3600)
                    }
                }
                client.feedGet(signed = signedGet(partner = p)).headers[HttpHeaders.ETag] shouldBe etag0
            }
        }

        test("trailing: a served partner still gets the live withdrawals (never an item) after the instance or its own switch went off") {
            val author = data.member(label = "wdf-trail")
            val trip = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")
            transaction { CarpoolFederationStore.setOptIn(postingId = trip, value = true) }
            val p = newFeedPartner()
            val q = newFeedPartner()
            feedApplication {
                client.feedGet(signed = signedGet(partner = p)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = q)).status shouldBe HttpStatusCode.OK
                withdraw(teaserOf = Uuid.random())
                // the partner switch of p goes off, the instance stays on
                transaction { PartnerServerStore.setCarpoolSharing(id = p.id, enabled = false) }
                val viaPartner = client.feedGet(signed = signedGet(partner = p))
                viaPartner.status shouldBe HttpStatusCode.OK
                json
                    .parseToJsonElement(viaPartner.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray shouldHaveSize 0
                ids(viaPartner.bodyAsText()) shouldHaveSize 1
                // the instance goes off: q gets the trailing answer too
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                val viaInstance = client.feedGet(signed = signedGet(partner = q))
                viaInstance.status shouldBe HttpStatusCode.OK
                json
                    .parseToJsonElement(viaInstance.bodyAsText())
                    .jsonObject
                    .getValue("items")
                    .jsonArray shouldHaveSize 0
                ids(viaInstance.bodyAsText()) shouldHaveSize 1
                viaInstance.bodyAsText() shouldNotContain "Braunschweig"
            }
        }

        test("trailing: the refusal stays byte-identical for blocked, never served, inactive, no tombstones and a changed pin") {
            val unknown = newFeedPartner(pinned = false)
            val tombstone = Uuid.random()
            feedApplication {
                val reference = fingerprint(client.feedGet(signed = signedGet(partner = unknown)))
                val served = newFeedPartner()
                client.feedGet(signed = signedGet(partner = served)).status shouldBe HttpStatusCode.OK
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                // nothing live to report yet: the same refusal
                fingerprint(client.feedGet(signed = signedGet(partner = served))) shouldBe reference
                withdraw(teaserOf = tombstone)
                // a served partner now gets the trailing answer ...
                client.feedGet(signed = signedGet(partner = served)).status shouldBe HttpStatusCode.OK
                // ... but none of these does
                val never = newFeedPartner()
                fingerprint(client.feedGet(signed = signedGet(partner = never))) shouldBe reference
                val blocked = newFeedPartner(host = "gesperrt2.beispiel.de")
                transaction { recordServed(partnerId = blocked.id) }
                transaction { PartnerServerStore.addBlock(pattern = "gesperrt2.beispiel.de", now = LocalDateTime(2026, 11, 5, 10, 0, 0)) }
                fingerprint(client.feedGet(signed = signedGet(partner = blocked))) shouldBe reference
                val inactive = newFeedPartner()
                recordServed(partnerId = inactive.id)
                transaction { PartnerServerStore.setActive(id = inactive.id, active = false, now = LocalDateTime(2026, 11, 5, 10, 0, 0)) }
                fingerprint(client.feedGet(signed = signedGet(partner = inactive))) shouldBe reference
                val repinned = newFeedPartner()
                recordServed(partnerId = repinned.id)
                val stranger = FederationKeyPairGenerator.generate()
                transaction {
                    PartnerServerTable.update({ PartnerServerTable.id eq repinned.id }) { it[signingPublicKeyPem] = stranger.publicKeyPem }
                }
                fingerprint(client.feedGet(signed = signedGet(partner = repinned))) shouldBe reference
            }
        }

        test("the rate limits apply to the trailing answer too") {
            val served = newFeedPartner()
            feedApplication(limits = FeedLimits(partner = 2)) {
                client.feedGet(signed = signedGet(partner = served)).status shouldBe HttpStatusCode.OK
                withdraw(teaserOf = Uuid.random())
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                client.feedGet(signed = signedGet(partner = served)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = served)).status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("the audit entry of a 200 carries counts only (items, complete, withdrawn), never an id") {
            val p = newFeedPartner()
            val trip = Uuid.random()
            withdraw(teaserOf = trip)
            feedApplication {
                client.feedGet(signed = signedGet(partner = p)).status shouldBe HttpStatusCode.OK
            }
            val snapshot =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.CARPOOL_FEDERATION_FEED) and
                                (AuditLogEntryTable.entityId eq p.id)
                        }.map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
                        .single()
                }
            snapshot shouldBe "{\"itemCount\":0,\"complete\":true,\"withdrawnCount\":1}"
            snapshot shouldNotContain TEST_IDS.of(trip)
        }

        test("a consent withdrawn WHILE the snapshot is built: never item and tombstone together, and the next fetch shows the tombstone") {
            val author = data.member(label = "wdf-race")
            val trip = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")
            transaction {
                CarpoolFederationStore.setOptIn(
                    postingId = trip,
                    value = true,
                    now = plusSeconds(DbClock.nowLocalDateTime(), -120),
                )
            }
            val p = newFeedPartner()
            recordServed(partnerId = p.id)
            val row = transaction { PartnerServerStore.findPartner(p.id)!! }
            val settings = transaction { CarpoolFederationStore.settings() }
            // On PostgreSQL the feed holds FOR SHARE on the trips it hands out, so the racing withdrawal waits for the end of this
            // transaction: it is joined AFTER it (a join inside would wait for a lock this very thread holds).
            var racer: Thread? = null
            val built =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = row,
                        settings = settings,
                        ids = TEST_IDS,
                        ownKey = CarpoolOwnKeys.load(),
                        originHost = OWN_HOST,
                        today = OrganizationTimeZone.today(),
                        beforeWithdrawalRead = {
                            racer =
                                thread {
                                    transaction {
                                        CarpoolFederationStore.withdraw(
                                            targets =
                                                listOf(
                                                    CarpoolWithdrawalTarget(
                                                        postingId = trip,
                                                        departureDate = OrganizationTimeZone.today().plus(DatePeriod(days = 10)),
                                                    ),
                                                ),
                                            partnerId = null,
                                            ids = TEST_IDS,
                                            now = DbClock.nowLocalDateTime(),
                                            clearOptIn = true,
                                            requireServed = true,
                                        )
                                    }
                                }
                        },
                    )
                }
            racer?.join()
            val first = json.parseToJsonElement(built.body.decodeToString()).jsonObject
            val itemCount = first.getValue("items").jsonArray.size
            val withdrawnCount = first.getValue("withdrawn").jsonArray.size
            (itemCount + withdrawnCount) shouldBe 1
            allTombstones() shouldHaveSize 1
            val next =
                transaction {
                    CarpoolTeaserFeedBuilder.build(
                        partner = row,
                        settings = settings,
                        ids = TEST_IDS,
                        ownKey = CarpoolOwnKeys.load(),
                        originHost = OWN_HOST,
                        today = OrganizationTimeZone.today(),
                    )
                }
            val second = json.parseToJsonElement(next.body.decodeToString()).jsonObject
            second.getValue("items").jsonArray shouldHaveSize 0
            second.getValue("withdrawn").jsonArray shouldHaveSize 1
            clearTombstones()
        }

        test(
            "the FIRST fetch after a consent: a withdrawal that races it is never lost (no item without a tombstone), without a prior fetch",
        ) {
            if (!db.isPostgres) return@test // H2 has no FOR SHARE: the lock that orders the two transactions exists on PostgreSQL only
            val author = data.member(label = "wdf-first")
            val trip = data.posting(author = author, fromPlace = "38100 Braunschweig", toPlace = "30159 Hannover")
            transaction {
                CarpoolFederationStore.setOptIn(postingId = trip, value = true, now = plusSeconds(DbClock.nowLocalDateTime(), -120))
            }
            val p = newFeedPartner()
            val row = transaction { PartnerServerStore.findPartner(p.id)!! }
            val settings = transaction { CarpoolFederationStore.settings() }
            var racer: Thread? = null
            val built =
                transaction {
                    val b =
                        CarpoolTeaserFeedBuilder.build(
                            partner = row,
                            settings = settings,
                            ids = TEST_IDS,
                            ownKey = CarpoolOwnKeys.load(),
                            originHost = OWN_HOST,
                            today = OrganizationTimeZone.today(),
                            beforeWithdrawalRead = {
                                racer =
                                    thread {
                                        transaction {
                                            // Like every real trigger: the trip is locked FIRST (the lock the feed's FOR SHARE orders against).
                                            CarpoolParticipations.lockPosting(trip)
                                            CarpoolFederationStore.withdraw(
                                                targets =
                                                    listOf(
                                                        CarpoolWithdrawalTarget(
                                                            postingId = trip,
                                                            departureDate = OrganizationTimeZone.today().plus(DatePeriod(days = 10)),
                                                        ),
                                                    ),
                                                partnerId = null,
                                                ids = TEST_IDS,
                                                now = DbClock.nowLocalDateTime(),
                                                clearOptIn = true,
                                                requireServed = true,
                                            )
                                        }
                                    }
                                Thread.sleep(500) // let the racer reach its lock wait
                            },
                        )
                    PartnerServerStore.recordCarpoolFeedServed(id = row.id, itemCount = b.itemCount, now = DbClock.nowLocalDateTime())
                    b
                }
            racer?.join()
            json
                .parseToJsonElement(built.body.decodeToString())
                .jsonObject
                .getValue("items")
                .jsonArray shouldHaveSize 1
            // The item went out, so the withdrawal MUST have left its tombstone.
            allTombstones() shouldHaveSize 1
            clearTombstones()
        }
    })
