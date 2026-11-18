package network.lapis.cloud.server.carpool.feed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.partner.ParsedPartnerEvent
import network.lapis.cloud.server.partner.PartnerParseResult
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.PinnedSigningKey
import network.lapis.cloud.server.rpc.CarpoolTestData
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.312 "Mitfahrzentrale-Foederation" W2 -- the origin side of the teaser feed, on H2 AND PostgreSQL with the same assertions: the happy path and
 * the shape of the wire format, that no personal field leaves, every signature and approval failure giving ONE byte-identical answer, the
 * eligibility rules, ETag/304, the three rate limits, the caps and `complete`, the audit entry and the counters.
 */
abstract class CarpoolTeaserFeedScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val data = CarpoolTestData()
        val json = Json

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            transaction {
                exec("UPDATE carpool_posting SET federation_opt_in = FALSE")
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
                CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2)
            }
        }
        afterSpec {
            try {
                transaction {
                    PartnerEventTable.deleteAll()
                    PartnerServerTable.deleteAll()
                    PartnerBlocklistTable.deleteAll()
                    CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2)
                }
                if (!db.isPostgres) data.cleanUp()
            } finally {
                db.deactivate()
            }
        }

        fun offer(
            author: Uuid,
            daysAhead: Int = 10,
            fromPlace: String = "38100 Braunschweig",
            toPlace: String = "30159 Hannover",
            seats: Int? = 3,
            type: CarpoolPostingType = CarpoolPostingType.OFFER,
            optIn: Boolean = true,
            notes: String? = null,
            meetingNote: String? = null,
        ): Uuid {
            val id =
                data.posting(
                    author = author,
                    seats = seats,
                    type = type,
                    daysAhead = daysAhead,
                    fromPlace = fromPlace,
                    toPlace = toPlace,
                    notes = notes,
                    meetingNote = meetingNote,
                )
            if (optIn) transaction { CarpoolFederationStore.setOptIn(postingId = id, value = true) }
            return id
        }

        fun auditCount(partner: FeedPartner): Int =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.CARPOOL_FEDERATION_FEED) and
                            (AuditLogEntryTable.entityId eq partner.id)
                    }.count()
                    .toInt()
            }

        fun partnerRow(partner: FeedPartner) = transaction { PartnerServerStore.findPartner(partner.id)!! }

        fun teaserIdsOf(body: String): List<String> =
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

        test("an approved partner gets 200 with exactly the documented shape: four teaser keys, two-digit areas, hops 0, own hop limit") {
            val author = data.member(label = "feed-shape")
            val id = offer(author)
            val partner = newFeedPartner()
            feedApplication {
                val response = client.feedGet(signed = signedGet(partner = partner))
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
                root.keys shouldBe setOf("schemaVersion", "kind", "complete", "items")
                root.getValue("schemaVersion").jsonPrimitive.int shouldBe 1
                root.getValue("kind").jsonPrimitive.content shouldBe "lapis-carpool-teaser-feed"
                root.getValue("complete").jsonPrimitive.boolean shouldBe true
                val item =
                    root
                        .getValue("items")
                        .jsonArray
                        .single()
                        .jsonObject
                item.keys shouldBe setOf("originHost", "hops", "maxHops", "teaser")
                item.getValue("originHost").jsonPrimitive.content shouldBe OWN_HOST
                item.getValue("hops").jsonPrimitive.int shouldBe 0
                item.getValue("maxHops").jsonPrimitive.int shouldBe 2
                val teaser = item.getValue("teaser").jsonObject
                teaser.keys shouldBe setOf("teaserId", "departureDate", "fromPostcodeArea", "toPostcodeArea")
                teaser.getValue("fromPostcodeArea").jsonPrimitive.content shouldBe "38"
                teaser.getValue("toPostcodeArea").jsonPrimitive.content shouldBe "30"
                id shouldNotBe null
            }
        }

        test(
            "marker leak: nothing but the four teaser fields leaves; the pseudonymous id is a keyed hash, stable per key and different under another key",
        ) {
            val author = data.member(label = "feed-leak", displayName = "MARKER-ANZEIGENAME")
            val id =
                offer(
                    author = author,
                    seats = 7,
                    fromPlace = "MARKER-ORT-A 38100",
                    toPlace = "MARKER-ORT-B 30159",
                    notes = "MARKER-NOTIZ",
                    meetingNote = "MARKER-TREFFPUNKT",
                )
            transaction {
                CarpoolPostingTable.update({ CarpoolPostingTable.id eq id }) { it[departureTime] = java.time.LocalTime.of(13, 37) }
            }
            val partner = newFeedPartner()
            var first = ""
            feedApplication {
                val a = client.feedGet(signed = signedGet(partner = partner)).bodyAsText()
                val b = client.feedGet(signed = signedGet(partner = partner)).bodyAsText()
                a shouldBe b
                first = a
                listOf("MARKER", "13:37", author.toString(), id.toString(), "notes", "seats", "meeting", "time", "Braunschweig", "Hannover")
                    .forEach { a shouldNotContain it }
                val teaserId = teaserIdsOf(a).single()
                Regex("^[0-9a-f]{64}$").matches(teaserId) shouldBe true
                teaserId shouldNotBe id.toString()
            }
            feedApplication(
                ids =
                    CarpoolTeaserIds(
                        network.lapis.cloud.server.crypto
                            .SecretBox(ByteArray(32) { 99 }),
                    ),
            ) {
                teaserIdsOf(client.feedGet(signed = signedGet(partner = partner)).bodyAsText()) shouldNotBe teaserIdsOf(first)
            }
        }

        test("every signature failure gives the SAME bytes as an unknown key: status, body and headers, with no ETag") {
            val author = data.member(label = "feed-sig")
            offer(author)
            val partner = newFeedPartner()
            val other = newFeedPartner()
            val strangerKey = FederationKeyPairGenerator.generate()
            feedApplication { counters ->
                val prints = linkedMapOf<String, String>()

                suspend fun refused(
                    name: String,
                    request: suspend () -> io.ktor.client.statement.HttpResponse,
                ) {
                    val response = request()
                    response.headers[HttpHeaders.ETag] shouldBe null
                    prints[name] = fingerprint(response)
                }
                refused("no headers") { client.feedGet(signed = null) }
                refused(
                    "only Signature",
                ) { client.get(CARPOOL_TEASER_FEED_PATH) { header("Signature", signedGet(partner = partner).signature) } }
                refused("no Digest") {
                    val s = signedGet(partner = partner)
                    client.get(CARPOOL_TEASER_FEED_PATH) {
                        header("Signature", s.signature)
                        header(HttpHeaders.Date, s.date)
                    }
                }
                refused("wrong Digest") {
                    val s = signedGet(partner = partner)
                    client.get(CARPOOL_TEASER_FEED_PATH) {
                        header("Signature", s.signature)
                        header(HttpHeaders.Date, s.date)
                        header("Digest", "SHA-256=AAAA")
                    }
                }
                refused("shortened headers list") {
                    val s = signedGet(partner = partner)
                    client.feedGet(
                        signed =
                            SignedGet(
                                signature = s.signature.replace("headers=\"(request-target) host date digest\"", "headers=\"date\""),
                                date = s.date,
                                digest = s.digest,
                            ),
                    )
                }
                refused(
                    "wrong private key",
                ) { client.feedGet(signed = signedGet(partner = partner, privateKeyPem = strangerKey.privateKeyPem)) }
                refused("unknown key id") {
                    client.feedGet(signed = signedGet(partner = partner, keyId = "https://unbekannt.beispiel.de/federation/actor#main-key"))
                }
                refused(
                    "key id of another partner, signed with my key",
                ) { client.feedGet(signed = signedGet(partner = partner, keyId = other.keyId)) }
                refused(
                    "date 6 minutes old",
                ) { client.feedGet(signed = signedGet(partner = partner, now = Clock.System.now() - 6.minutes)) }
                refused(
                    "date 6 minutes ahead",
                ) { client.feedGet(signed = signedGet(partner = partner, now = Clock.System.now() + 6.minutes)) }
                refused("signed for another host") { client.feedGet(signed = signedGet(partner = partner, host = "anderer.beispiel.de")) }
                refused(
                    "signed for another path",
                ) { client.feedGet(signed = signedGet(partner = partner, path = "/api/federation/carpool-feed/v2")) }
                refused("query string") {
                    val s = signedGet(partner = partner)
                    client.get("$CARPOOL_TEASER_FEED_PATH?x=1") {
                        header("Signature", s.signature)
                        header(HttpHeaders.Date, s.date)
                        header("Digest", s.digest)
                    }
                }
                val replayed = signedGet(partner = partner)
                client.feedGet(signed = replayed).status shouldBe HttpStatusCode.OK
                refused("replay of a valid request") { client.feedGet(signed = replayed) }

                prints.values.toSet() shouldHaveSize 1
                prints.values.first().startsWith("404|{\"error\":\"unavailable\"}|") shouldBe true
                counters.snapshot().refusedUnauthenticated shouldBe prints.size.toLong()
            }
        }

        test(
            "every approval failure gives the same bytes; sharing on, active, not blocked, the switch on and the key present make it 200",
        ) {
            val author = data.member(label = "feed-appr")
            offer(author)
            feedApplication { counters ->
                val unknown = fingerprint(client.feedGet(signed = signedGet(partner = newFeedPartner(pinned = false))))
                val prints = mutableSetOf(unknown)

                suspend fun expectRefused(partner: FeedPartner) {
                    val response = client.feedGet(signed = signedGet(partner = partner))
                    response.headers[HttpHeaders.ETag] shouldBe null
                    prints += fingerprint(response)
                }
                expectRefused(newFeedPartner(active = false))
                expectRefused(newFeedPartner(sharing = false))
                val blocked = newFeedPartner(host = "gesperrt.beispiel.de")
                transaction { PartnerServerStore.addBlock(pattern = "gesperrt.beispiel.de", now = LocalDateTime(2026, 11, 5, 10, 0, 0)) }
                expectRefused(blocked)
                val removed = newFeedPartner()
                transaction { PartnerServerStore.remove(removed.id) }
                expectRefused(removed)
                val good = newFeedPartner()
                transaction { CarpoolFederationStore.setSettings(enabled = false, hopLimit = 2) }
                expectRefused(good)
                transaction { CarpoolFederationStore.setSettings(enabled = true, hopLimit = 2) }
                client.feedGet(signed = signedGet(partner = good)).status shouldBe HttpStatusCode.OK
                prints shouldHaveSize 1
                (counters.snapshot().refusedNotApproved >= 4L) shouldBe true
            }
            // No teaser key configured: the feed refuses everybody, with the same bytes.
            val good = newFeedPartner()
            var keyless = ""
            feedApplication(ids = null) { keyless = fingerprint(client.feedGet(signed = signedGet(partner = good))) }
            feedApplication { keyless shouldBe fingerprint(client.feedGet(signed = signedGet(partner = newFeedPartner(pinned = false)))) }
        }

        test(
            "eligibility: only opted-in future offers with a free seat, an active author and a postcode in both places; today and today+90 are in",
        ) {
            val author = data.member(label = "feed-elig")
            val gone = data.member(label = "feed-elig-gone", status = MemberStatus.GUEST)
            val full = offer(author, seats = 1)
            data.participation(
                postingId = full,
                personId = data.member(label = "feed-elig-p"),
                status = CarpoolParticipationStatus.CONFIRMED,
            )
            val ok = offer(author, daysAhead = 5)
            val today = offer(author, daysAhead = 0)
            val last = offer(author, daysAhead = 90)
            val bad =
                listOf(
                    offer(author, optIn = false),
                    offer(author, type = CarpoolPostingType.REQUEST, seats = null),
                    offer(author, daysAhead = 91),
                    offer(author, seats = null),
                    offer(author, fromPlace = "Braunschweig"),
                    offer(author, toPlace = "Hannover"),
                    offer(gone),
                    full,
                )
            val partner = newFeedPartner()
            val expected = listOf(ok, today, last).map { TEST_IDS.of(it) }.toSet()
            feedApplication {
                val ids = teaserIdsOf(client.feedGet(signed = signedGet(partner = partner)).bodyAsText())
                ids.toSet() shouldBe expected
                bad.forEach { ids shouldNotBe listOf(TEST_IDS.of(it)) }
                bad.map { TEST_IDS.of(it) }.forEach { (it in ids) shouldBe false }
            }
        }

        test(
            "a partner with sharing off is refused even if another partner gets the same trips; withdrawing consent shows on the very next fetch",
        ) {
            val author = data.member(label = "feed-withdraw")
            val id = offer(author)
            val yes = newFeedPartner()
            val no = newFeedPartner(sharing = false)
            feedApplication {
                client.feedGet(signed = signedGet(partner = no)).status shouldBe HttpStatusCode.NotFound
                val before = client.feedGet(signed = signedGet(partner = yes))
                teaserIdsOf(before.bodyAsText()) shouldContainExactly listOf(TEST_IDS.of(id))
                transaction { CarpoolFederationStore.setOptIn(postingId = id, value = false) }
                val after = client.feedGet(signed = signedGet(partner = yes))
                teaserIdsOf(after.bodyAsText()) shouldBe emptyList()
                after.headers[HttpHeaders.ETag] shouldNotBe before.headers[HttpHeaders.ETag]
            }
        }

        test(
            "ETag: a repeated fetch with If-None-Match is 304 without a body and the same cache headers; weak, list and * variants match",
        ) {
            val author = data.member(label = "feed-etag")
            offer(author)
            val partner = newFeedPartner()
            feedApplication { counters ->
                val first = client.feedGet(signed = signedGet(partner = partner))
                val etag = first.headers[HttpHeaders.ETag]!!
                val second = client.feedGet(signed = signedGet(partner = partner)) { header(HttpHeaders.IfNoneMatch, etag) }
                second.status shouldBe HttpStatusCode.NotModified
                second.bodyAsText() shouldBe ""
                second.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                client.feedGet(signed = signedGet(partner = partner)) { header(HttpHeaders.IfNoneMatch, "W/$etag") }.status shouldBe
                    HttpStatusCode.NotModified
                client.feedGet(signed = signedGet(partner = partner)) { header(HttpHeaders.IfNoneMatch, "\"x\", $etag") }.status shouldBe
                    HttpStatusCode.NotModified
                client.feedGet(signed = signedGet(partner = partner)) { header(HttpHeaders.IfNoneMatch, "*") }.status shouldBe
                    HttpStatusCode.NotModified
                client.feedGet(signed = signedGet(partner = partner)) { header(HttpHeaders.IfNoneMatch, "\"nope\"") }.status shouldBe
                    HttpStatusCode.OK
                // an unauthenticated request never sees the ETag branch
                client.get(CARPOOL_TEASER_FEED_PATH) { header(HttpHeaders.IfNoneMatch, etag) }.status shouldBe HttpStatusCode.NotFound
                counters.snapshot().notModified shouldBe 4L
            }
        }

        test(
            "rate limits: per IP before the signature, per partner and global after the approval; an unapproved partner never gets a 429",
        ) {
            val author = data.member(label = "feed-rate")
            offer(author)
            val partner = newFeedPartner()
            val unapproved = newFeedPartner(sharing = false)
            feedApplication(limits = FeedLimits(ip = 2)) { counters ->
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.OK
                val limited = client.feedGet(signed = signedGet(partner = partner))
                limited.status shouldBe HttpStatusCode.TooManyRequests
                limited.headers[HttpHeaders.RetryAfter] shouldBe "60"
                counters.snapshot().rateLimited shouldBe 1L
            }
            feedApplication(limits = FeedLimits(partner = 2)) { _ ->
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.TooManyRequests
                repeat(5) { client.feedGet(signed = signedGet(partner = unapproved)).status shouldBe HttpStatusCode.NotFound }
            }
            val second = newFeedPartner()
            feedApplication(limits = FeedLimits(global = 2)) { _ ->
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = second)).status shouldBe HttpStatusCode.OK
                client.feedGet(signed = signedGet(partner = partner)).status shouldBe HttpStatusCode.TooManyRequests
                client.feedGet(signed = signedGet(partner = unapproved)).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("caps: more items than the cap, a hit scan limit or too many bytes cut the list deterministically and mark it incomplete") {
            val author = data.member(label = "feed-caps")
            (0 until 6).forEach { offer(author, daysAhead = it + 1) }
            val partner = newFeedPartner()
            val row = partnerRow(partner)
            val today = OrganizationTimeZone.today()

            fun build(
                maxItems: Int = 1000,
                scanLimit: Int = 5000,
                maxBytes: Int = 256 * 1024,
            ) = transaction {
                CarpoolTeaserFeedBuilder.build(
                    partner = row,
                    settings = CarpoolFederationStore.settings(),
                    ids = TEST_IDS,
                    originHost = OWN_HOST,
                    today = today,
                    maxItems = maxItems,
                    scanLimit = scanLimit,
                    maxBytes = maxBytes,
                )
            }
            val all = build()
            all.itemCount shouldBe 6
            all.complete shouldBe true
            val cut = build(maxItems = 3)
            cut.itemCount shouldBe 3
            cut.complete shouldBe false
            String(build(maxItems = 3).body) shouldBe String(cut.body)
            build(scanLimit = 4).complete shouldBe false
            build(scanLimit = 4).itemCount shouldBe 4
            val small = build(maxBytes = 600)
            small.complete shouldBe false
            (small.body.size <= 600) shouldBe true
            (small.itemCount < 6) shouldBe true
        }

        test("audit: one entry per 200 with only the count and the complete flag; none for a 304 or any refusal") {
            val author = data.member(label = "feed-audit")
            offer(author)
            offer(author, daysAhead = 6)
            val partner = newFeedPartner()
            feedApplication {
                auditCount(partner) shouldBe 0
                client.feedGet(signed = signedGet(partner = newFeedPartner(sharing = false))).status shouldBe HttpStatusCode.NotFound
                client
                    .feedGet(
                        signed = signedGet(partner = partner, privateKeyPem = FederationKeyPairGenerator.generate().privateKeyPem),
                    ).status shouldBe
                    HttpStatusCode.NotFound
                val ok = client.feedGet(signed = signedGet(partner = partner))
                auditCount(partner) shouldBe 1
                client
                    .feedGet(
                        signed = signedGet(partner = partner),
                    ) { header(HttpHeaders.IfNoneMatch, ok.headers[HttpHeaders.ETag]!!) }
                    .status shouldBe
                    HttpStatusCode.NotModified
                auditCount(partner) shouldBe 1
                val entry =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.CARPOOL_FEDERATION_FEED) and
                                    (AuditLogEntryTable.entityId eq partner.id)
                            }.single()
                    }
                entry[AuditLogEntryTable.afterSnapshot] shouldBe "{\"itemCount\":2,\"complete\":true}"
                entry[AuditLogEntryTable.actorMemberId] shouldBe null
                entry[AuditLogEntryTable.actorRole] shouldBe null
            }
        }

        test("counters: served count and time on 200 and 304, the last item count only on a 200") {
            val author = data.member(label = "feed-count")
            offer(author)
            offer(author, daysAhead = 7)
            val partner = newFeedPartner()
            partnerRow(partner).carpoolFeedServedCount shouldBe 0L
            partnerRow(partner).carpoolFeedServedAt shouldBe null
            feedApplication { counters ->
                val ok = client.feedGet(signed = signedGet(partner = partner))
                partnerRow(partner).carpoolFeedServedCount shouldBe 1L
                partnerRow(partner).carpoolFeedLastItems shouldBe 2
                client.feedGet(signed = signedGet(partner = partner)) { header(HttpHeaders.IfNoneMatch, ok.headers[HttpHeaders.ETag]!!) }
                val row = partnerRow(partner)
                row.carpoolFeedServedCount shouldBe 2L
                row.carpoolFeedLastItems shouldBe 2
                (row.carpoolFeedServedAt != null) shouldBe true
                counters.snapshot().served shouldBe 1L
            }
        }

        test("TOCTOU: a pin replaced between the signature check and the approval, or a removed partner, is refused by the approval") {
            val partner = newFeedPartner()
            val gate =
                CarpoolTeaserFeedGate(
                    replayGuard = FederationReplayGuard(),
                    ownHost = OWN_HOST,
                    clock = { Clock.System.now() },
                )
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
            val newKey = FederationKeyPairGenerator.generate()
            transaction {
                PartnerServerStore.applyFetchSuccess(
                    id = partner.id,
                    parsed =
                        PartnerParseResult.Ok(
                            remoteServerId = partnerRow(partner).remoteServerId!!,
                            remoteName = "Partner",
                            events = emptyList<ParsedPartnerEvent>(),
                            partnersJson = null,
                            droppedEvents = 0,
                            signingKey = PinnedSigningKey(keyId = partner.keyId, publicKeyPem = newKey.publicKeyPem),
                        ),
                    etag = null,
                    now = LocalDateTime(2026, 11, 5, 11, 0, 0),
                )
            }
            transaction { gate.approve(verified = verified, settings = settings) } shouldBe null
            transaction { PartnerServerStore.remove(partner.id) }
            transaction { gate.approve(verified = verified, settings = settings) } shouldBe null
        }

        test(
            "the gate refuses a body, a transfer encoding, oversized headers and a repeated signature without touching the partner state",
        ) {
            val partner = newFeedPartner()
            val gate = CarpoolTeaserFeedGate(replayGuard = FederationReplayGuard(), ownHost = OWN_HOST, clock = { Clock.System.now() })

            fun auth(
                s: SignedGet,
                contentLength: String? = null,
                transferEncoding: String? = null,
                signature: String = s.signature,
            ) = gate.authenticate(
                signature = signature,
                date = s.date,
                digest = s.digest,
                contentLength = contentLength,
                transferEncoding = transferEncoding,
                queryString = "",
            )
            auth(signedGet(partner = partner), contentLength = "5") shouldBe null
            auth(signedGet(partner = partner), transferEncoding = "chunked") shouldBe null
            auth(signedGet(partner = partner), signature = "x".repeat(3000)) shouldBe null
            (auth(signedGet(partner = partner), contentLength = "0") != null) shouldBe true
            val once = signedGet(partner = partner)
            (auth(once) != null) shouldBe true
            auth(once) shouldBe null
        }
    })
