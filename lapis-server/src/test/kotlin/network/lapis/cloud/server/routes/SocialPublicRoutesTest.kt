package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostReportTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.SocialReadPipeline
import network.lapis.cloud.server.rpc.SocialVisibility
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * `testApplication` with [XForwardedHeaders]`{ useLastProxy() }` + [AutoHeadResponse] +
 * `routing { registerSocialPublicRoutes(...) }`, **ohne jede Auth-Installation** -- der Punkt ist
 * gerade, dass keine gebraucht wird. Fixtures gehen direkt per Exposed-`insert` (nicht über
 * [network.lapis.cloud.server.rpc.SocialNetworkService]) -- so lassen sich auch `MEMBERS_ONLY`-
 * Zeilen unter einer öffentlichen Wurzel und andere sonst unerreichbare Zustände erzeugen (T12).
 *
 * `afterTest` räumt wie in `SocialNetworkServiceTest` tiefen-zuerst auf (Stolperfalle 12:
 * selbstreferenzierender FK über `root_id`/`parent_id`).
 */
class SocialPublicRoutesTest :
    FunSpec({
        val createdPostIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                if (createdPostIds.isNotEmpty()) {
                    SocialPostBoostTable.deleteWhere { SocialPostBoostTable.postId inList createdPostIds }
                    val remaining = createdPostIds.toMutableList()
                    while (remaining.isNotEmpty()) {
                        val depthById =
                            SocialPostTable
                                .select(SocialPostTable.id, SocialPostTable.depth)
                                .where { SocialPostTable.id inList remaining }
                                .associate { it[SocialPostTable.id] to it[SocialPostTable.depth] }
                        if (depthById.isEmpty()) break
                        val maxDepth = depthById.values.max()
                        val toDelete = depthById.filterValues { it == maxDepth }.keys.toList()
                        SocialPostTable.deleteWhere { SocialPostTable.id inList toDelete }
                        remaining.removeAll(toDelete)
                    }
                }
                if (createdMemberIds.isNotEmpty()) {
                    LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
            createdPostIds.clear()
            createdMemberIds.clear()
        }

        fun createAuthor(displayName: String = "Public Test Autor"): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "public-test-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
            }
            createdMemberIds += id
            return id
        }

        fun insertPost(
            authorMemberId: Uuid,
            id: Uuid = Uuid.random(),
            parentId: Uuid? = null,
            rootId: Uuid? = null,
            depth: Int = 0,
            content: String = "Testinhalt ${Uuid.random()}",
            visibility: SocialPostVisibility = SocialPostVisibility.PUBLIC,
            initialWeightLtr: BigDecimal = BigDecimal("1.00"),
            publishedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            state: SocialPostState = SocialPostState.VISIBLE,
        ): Uuid {
            val actualRootId = rootId ?: id
            transaction {
                SocialPostTable.insert {
                    it[SocialPostTable.id] = id
                    it[SocialPostTable.parentId] = parentId
                    it[SocialPostTable.rootId] = actualRootId
                    it[SocialPostTable.depth] = depth
                    it[SocialPostTable.authorMemberId] = authorMemberId
                    it[SocialPostTable.content] = content
                    it[SocialPostTable.visibility] = visibility
                    it[SocialPostTable.initialWeightLtr] = initialWeightLtr
                    it[SocialPostTable.publishedAt] = publishedAt
                    it[SocialPostTable.state] = state
                    it[SocialPostTable.stateChangedAt] = null
                    it[SocialPostTable.stateChangedBy] = null
                    it[SocialPostTable.stateReason] = null
                }
            }
            createdPostIds += id
            return id
        }

        fun insertBoost(
            postId: Uuid,
            memberId: Uuid,
            amount: BigDecimal = BigDecimal("1.00"),
        ) {
            transaction {
                SocialPostBoostTable.insert {
                    it[SocialPostBoostTable.id] = Uuid.random()
                    it[SocialPostBoostTable.postId] = postId
                    it[SocialPostBoostTable.memberId] = memberId
                    it[SocialPostBoostTable.amountLtr] = amount
                    it[boostedAt] = DbClock.nowLocalDateTime()
                }
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        fun extractTotalWeight(html: String): String =
            Regex("Gesamtgewicht ([0-9.]+) LTR").find(html)?.groupValues?.get(1) ?: error("Gesamtgewicht not found in: $html")

        /** G1-Fix (Review-Runde 1): reads the `<lastmod>` of the `<url>` block for [id] -- `<loc>` always precedes `<lastmod>` within the same block, see `SocialPublicSitemap.renderUrlset`. */
        fun extractLastmod(
            xml: String,
            id: Uuid,
        ): String =
            Regex("<loc>[^<]*$id</loc>\\s*<lastmod>([^<]+)</lastmod>").find(xml)?.groupValues?.get(1)
                ?: error("no <url> block for $id found in: $xml")

        fun assertSecurityHeaders(headers: Headers) {
            (headers["Content-Security-Policy"] ?: "") shouldContain "default-src 'none'"
            headers["X-Content-Type-Options"] shouldBe "nosniff"
            headers["Referrer-Policy"] shouldBe "no-referrer"
            headers["X-Frame-Options"] shouldBe "DENY"
            // N1-Fix (Review-Runde 2): Permissions-Policy was set by applyPublicPageHeaders() from
            // the start but never actually asserted anywhere -- adding it here strengthens every
            // existing call site (T15's 200/304/404/429 cases included), not just the new 500 test.
            headers["Permissions-Policy"] shouldNotBe null
        }

        suspend fun testApp(
            readLimiter: FederationInboxRateLimiter = generousLimiter(),
            sitemapLimiter: FederationInboxRateLimiter = generousLimiter(),
            reportLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        registerSocialPublicRoutes(
                            readRateLimiter = readLimiter,
                            sitemapRateLimiter = sitemapLimiter,
                            reportRateLimiter = reportLimiter,
                        )
                    }
                }
                block()
            }
        }

        // ── T1 ──────────────────────────────────────────────────────────────────────────
        test("T1: anonymous GET /s/{id} of a PUBLIC/VISIBLE post returns 200 with content, no cookie/header needed") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, content = "Oeffentlicher Test Inhalt")
                val response = client.get("/s/$id")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Oeffentlicher Test Inhalt"
            }
        }

        // ── T2 ──────────────────────────────────────────────────────────────────────────
        // Welle V1.1.5 (E-B): a PUBLIC + REMOVED_LEGAL root is no longer 404 -- see the dedicated
        // 451 test group below. This test keeps every OTHER non-public state/visibility combination
        // (including a REMOVED_LEGAL post that was NEVER PUBLIC) at plain 404.
        test("T2: non-public visibility/state, random, and malformed ids all 404 -- never 403, never 500") {
            testApp {
                val author = createAuthor()
                val membersOnly = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_ONLY)
                val membersExt = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_AND_EXTERNAL)
                val hidden = insertPost(authorMemberId = author, state = SocialPostState.HIDDEN_BY_AUTHOR)
                val membersOnlyRemoved =
                    insertPost(
                        authorMemberId = author,
                        visibility = SocialPostVisibility.MEMBERS_ONLY,
                        state = SocialPostState.REMOVED_LEGAL,
                    )
                val ids =
                    listOf(membersOnly, membersExt, hidden, membersOnlyRemoved).map { it.toString() } +
                        listOf(Uuid.random().toString(), "not-a-uuid", "..%2Fetc%2Fpasswd", "%00", "x".repeat(300))
                ids.forEach { id ->
                    val response = client.get("/s/$id")
                    response.status shouldBe HttpStatusCode.NotFound
                    val body = response.bodyAsText()
                    body shouldNotContain "Exception"
                    body shouldNotContain "at network.lapis"
                }
            }
        }

        // ── Welle V1.1.5 (E-B) -- the public 451 "Unavailable For Legal Reasons" page ──────────

        test("T43: a PUBLIC post rechtlich entfernt liefert 451 (nicht 404), mit Cache-Control: no-store, ohne ETag, robots noindex") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, state = SocialPostState.REMOVED_LEGAL)
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq id }) {
                        it[stateReason] = "Verstoss gegen geltendes Recht"
                        it[stateChangedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val response = client.get("/s/$id")
                response.status.value shouldBe 451
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers[HttpHeaders.ETag] shouldBe null
                response.bodyAsText() shouldContain "noindex"
                assertSecurityHeaders(response.headers)

                // Fehlt weiterhin in Timeline und Sitemap.
                client.get("/s").bodyAsText() shouldNotContain id.toString()
                client.get("/sitemap.xml").bodyAsText() shouldNotContain id.toString()
            }
        }

        test("T44: kein stale 200/304 ueber If-None-Match nach der Entfernung -- 451, niemals 304") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, content = "Wird gleich entfernt")
                val etag = client.get("/s/$id").headers[HttpHeaders.ETag]
                etag shouldNotBe null

                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq id }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                        it[stateReason] = "x"
                        it[stateChangedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val response = client.get("/s/$id") { header(HttpHeaders.IfNoneMatch, etag!!) }
                response.status.value shouldBe 451
            }
        }

        test(
            "T45: der 451-Body enthaelt den Originalinhalt nirgends (auch nicht in title/description) und nicht den Autorennamen, aber die Begruendung",
        ) {
            testApp {
                val author = createAuthor(displayName = "Geheimer Autorenname")
                val id = insertPost(authorMemberId = author, content = "GeheimerOriginalInhaltXyz")
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq id }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                        it[stateReason] = "OeffentlicheBegruendungAbc"
                        it[stateChangedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val body = client.get("/s/$id").bodyAsText()
                body shouldNotContain "GeheimerOriginalInhaltXyz"
                body shouldNotContain "Geheimer Autorenname"
                body shouldContain "OeffentlicheBegruendungAbc"
            }
        }

        test(
            "T46: kein Orakel -- ein rechtlich entfernter MEMBERS_ONLY-Post und ein HIDDEN_BY_AUTHOR-PUBLIC-Post bleiben 404, wie eine unbekannte UUID",
        ) {
            testApp {
                val author = createAuthor()
                val membersOnlyRemoved =
                    insertPost(
                        authorMemberId = author,
                        visibility = SocialPostVisibility.MEMBERS_ONLY,
                        state = SocialPostState.REMOVED_LEGAL,
                    )
                val publicHidden =
                    insertPost(authorMemberId = author, visibility = SocialPostVisibility.PUBLIC, state = SocialPostState.HIDDEN_BY_AUTHOR)
                client.get("/s/$membersOnlyRemoved").status shouldBe HttpStatusCode.NotFound
                client.get("/s/$publicHidden").status shouldBe HttpStatusCode.NotFound
                client.get("/s/${Uuid.random()}").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("T47: XSS -- eine reason mit Skript-Payload wird im 451-Body escaped ausgeliefert") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, state = SocialPostState.REMOVED_LEGAL)
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq id }) {
                        it[stateReason] = "<script>alert(1)</script>"
                        it[stateChangedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val body = client.get("/s/$id").bodyAsText()
                body shouldNotContain "<script>"
                body shouldContain "&lt;script&gt;"
            }
        }

        test(
            "T48: Orthogonalitaet -- getombstoneter UND rechtlich entfernter Post liefert die 451-Seite, weder Originalinhalt noch Tombstone-Marker (in beiden Reihenfolgen)",
        ) {
            testApp {
                val author = createAuthor()
                val idA = insertPost(authorMemberId = author, content = "OriginalA")
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq idA }) {
                        it[content] = "TOMBSTONE_MARKER_A"
                        it[contentErasedAt] = DbClock.nowLocalDateTime()
                    }
                }
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq idA }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                        it[stateReason] = "x"
                        it[stateChangedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val bodyA = client.get("/s/$idA").bodyAsText()
                bodyA shouldNotContain "OriginalA"
                bodyA shouldNotContain "TOMBSTONE_MARKER_A"

                val idB = insertPost(authorMemberId = author, content = "OriginalB")
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq idB }) {
                        it[state] = SocialPostState.REMOVED_LEGAL
                        it[stateReason] = "x"
                        it[stateChangedAt] = DbClock.nowLocalDateTime()
                    }
                }
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq idB }) {
                        it[content] = "TOMBSTONE_MARKER_B"
                        it[contentErasedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val bodyB = client.get("/s/$idB").bodyAsText()
                bodyB shouldNotContain "OriginalB"
                bodyB shouldNotContain "TOMBSTONE_MARKER_B"
            }
        }

        // ── Welle V1.1.5 -- oeffentlicher Melde-Weg (DSA Art. 16) ──────────────────────────────

        test("T-Report-1: GET /s/{id}/report on a public post renders the form, noindex, no-store") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                val response = client.get("/s/$id/report")
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = response.bodyAsText()
                body shouldContain "noindex"
                body shouldContain "category"
                // Review-Fund 4 (Runde 1, 2026-08-19): the honeypot must be a REAL text field, only
                // CSS-hidden (class "hp", see SocialPublicHtml.STYLESHEET) -- a naive scraper skips
                // type="hidden" fields, which would make the honeypot a no-op. No type="hidden" field
                // exists anywhere on this page at all.
                body shouldContain "class=\"hp\""
                body shouldContain "name=\"website\""
                body shouldNotContain "type=\"hidden\""
            }
        }

        test("T-Report-2: GET /s/{id}/report on a non-public/unknown post is 404") {
            testApp {
                val author = createAuthor()
                val membersOnly = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_ONLY)
                client.get("/s/$membersOnly/report").status shouldBe HttpStatusCode.NotFound
                client.get("/s/${Uuid.random()}/report").status shouldBe HttpStatusCode.NotFound
            }
        }

        test(
            "T-Report-3: POST /s/{id}/report creates an anonymous report row (reporter_member_id IS NULL); honeypot and non-public post yield the SAME confirmation page but no row",
        ) {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                val membersOnly = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_ONLY)

                val legit =
                    client.submitForm(
                        url = "/s/$id/report",
                        formParameters =
                            Parameters.build {
                                append("category", "SPAM")
                                append("description", "Testmeldung")
                                append("goodFaith", "on")
                            },
                    )
                legit.status shouldBe HttpStatusCode.OK
                legit.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                val honeypot =
                    client.submitForm(
                        url = "/s/$id/report",
                        formParameters =
                            Parameters.build {
                                append("category", "SPAM")
                                append("description", "Bot-Meldung")
                                append("goodFaith", "on")
                                append("website", "http://spam.example")
                            },
                    )
                honeypot.status shouldBe legit.status
                honeypot.bodyAsText() shouldBe legit.bodyAsText()

                val nonPublic =
                    client.submitForm(
                        url = "/s/$membersOnly/report",
                        formParameters =
                            Parameters.build {
                                append("category", "SPAM")
                                append("description", "x")
                                append("goodFaith", "on")
                            },
                    )
                nonPublic.status shouldBe legit.status
                nonPublic.bodyAsText() shouldBe legit.bodyAsText()

                val rows =
                    transaction {
                        SocialPostReportTable
                            .selectAll()
                            .where { SocialPostReportTable.postId eq id }
                            .toList()
                    }
                rows.size shouldBe 1
                rows.single()[SocialPostReportTable.reporterMemberId] shouldBe null

                transaction {
                    SocialPostReportTable.deleteWhere { SocialPostReportTable.postId inList listOf(id, membersOnly) }
                }
            }
        }

        test("T-Report-4: robots.txt disallows /s/*/report") {
            testApp {
                client.get("/robots.txt").bodyAsText() shouldContain "Disallow: /s/*/report"
            }
        }

        test("T-Report-5: CSP now allows form-action 'self' (up from 'none'), all other directives unchanged") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                val response = client.get("/s/$id")
                response.status shouldBe HttpStatusCode.OK
                val cspHeader = response.headers["Content-Security-Policy"] ?: ""
                cspHeader shouldContain "form-action 'self'"
                cspHeader shouldContain "default-src 'none'"
                cspHeader shouldContain "frame-ancestors 'none'"
            }
        }

        test(
            "MAJOR-1: an oversized POST /s/{id}/report body is rejected (413) BEFORE receiveParameters() " +
                "ever buffers it, and no report row is written -- same pattern as FederationRoutesTest's own " +
                "\"an oversized body is rejected (413) before any JSON parsing\"",
        ) {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)

                // 16 KiB is REPORT_MAX_BODY_BYTES in SocialPublicRoutes.kt (private there, mirrored
                // here) -- well past it, but nowhere near the domain-level MAX_DESCRIPTION_LENGTH
                // (4000 chars) check in SocialReportSubmission.submitPublic, which only runs AFTER
                // the body is fully buffered -- proving THIS rejection happens strictly earlier, at
                // the transport/framework level.
                val oversizedBody = ("category=SPAM&goodFaith=on&description=" + "x".repeat(20_000)).toByteArray(Charsets.UTF_8)
                val response =
                    client.post("/s/$id/report") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(oversizedBody)
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                transaction {
                    SocialPostReportTable.selectAll().where { SocialPostReportTable.postId eq id }.count()
                } shouldBe 0L
            }
        }

        test(
            "MAJOR-1 unit: reportBodyExceedsLimit -- null (no Content-Length, e.g. chunked encoding) and " +
                "over-ceiling both reject; at-ceiling and under do not",
        ) {
            reportBodyExceedsLimit(contentLength = null) shouldBe true
            reportBodyExceedsLimit(contentLength = 16 * 1024L + 1) shouldBe true
            reportBodyExceedsLimit(contentLength = 16 * 1024L) shouldBe false
            reportBodyExceedsLimit(contentLength = 100L) shouldBe false
        }

        test("MINOR-3: POST /s/{id}/report with a non-form-encoded Content-Type is a clean 400, not a raw 500") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)

                val response =
                    client.post("/s/$id/report") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"category":"SPAM"}""")
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                transaction {
                    SocialPostReportTable.selectAll().where { SocialPostReportTable.postId eq id }.count()
                } shouldBe 0L
            }
        }

        test("T-Tombstone-Sitemap: content_erased_at alone bumps the thread's sitemap lastmod") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, publishedAt = LocalDateTime(2020, 1, 1, 0, 0, 0))
                val before = extractLastmod(client.get("/sitemap.xml").bodyAsText(), id)
                transaction {
                    SocialPostTable.update({ SocialPostTable.id eq id }) {
                        it[content] = "Tombstone-Marker"
                        it[contentErasedAt] = DbClock.nowLocalDateTime()
                    }
                }
                val after = extractLastmod(client.get("/sitemap.xml").bodyAsText(), id)
                (after > before) shouldBe true
            }
        }

        // ── T3 ──────────────────────────────────────────────────────────────────────────
        test("T3: hideOwnPost-equivalent state change removes a root from /s/{id}, /s, and the sitemap") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, content = "Wird versteckt")
                client.get("/s/$id").status shouldBe HttpStatusCode.OK

                transaction { SocialPostTable.update({ SocialPostTable.id eq id }) { it[state] = SocialPostState.HIDDEN_BY_AUTHOR } }

                client.get("/s/$id").status shouldBe HttpStatusCode.NotFound
                client.get("/s").bodyAsText() shouldNotContain id.toString()
                client.get("/sitemap.xml").bodyAsText() shouldNotContain id.toString()
            }
        }

        test("T3: a hidden comment vanishes from the thread, root total weight stays unchanged (E3)") {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author, content = "Wurzel", initialWeightLtr = BigDecimal("1.00"))
                val comment =
                    insertPost(
                        authorMemberId = author,
                        parentId = root,
                        rootId = root,
                        depth = 1,
                        content = "Kommentar-Text-Einzigartig",
                        initialWeightLtr = BigDecimal("2.00"),
                    )
                val before = client.get("/s/$root").bodyAsText()
                before shouldContain "Kommentar-Text-Einzigartig"
                val weightBefore = extractTotalWeight(before)

                transaction { SocialPostTable.update({ SocialPostTable.id eq comment }) { it[state] = SocialPostState.HIDDEN_BY_AUTHOR } }

                val after = client.get("/s/$root").bodyAsText()
                after shouldNotContain "Kommentar-Text-Einzigartig"
                extractTotalWeight(after) shouldBe weightBefore
            }
        }

        // ── T4 ──────────────────────────────────────────────────────────────────────────
        test("T4 (wichtigster Sicherheitstest): the id set linked from /s exactly matches publicReadableCondition() root ids") {
            testApp {
                val author = createAuthor()
                val publicRoot1 = insertPost(authorMemberId = author, content = "Public Root Eins")
                val publicRoot2 = insertPost(authorMemberId = author, content = "Public Root Zwei")
                val membersOnlyRoot = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_ONLY)
                val hiddenRoot = insertPost(authorMemberId = author, state = SocialPostState.HIDDEN_BY_AUTHOR)
                val comment = insertPost(authorMemberId = author, parentId = publicRoot1, rootId = publicRoot1, depth = 1)

                val html = client.get("/s").bodyAsText()
                val linkedIds = Regex("/s/([0-9a-fA-F-]{36})").findAll(html).map { it.groupValues[1] }.toSet()

                val expectedIds =
                    transaction {
                        SocialPostTable
                            .selectAll()
                            .where { SocialVisibility.publicReadableCondition() and SocialPostTable.parentId.isNull() }
                            .map { it[SocialPostTable.id].toString() }
                            .toSet()
                    }
                linkedIds shouldBe expectedIds
                linkedIds shouldContain publicRoot1.toString()
                linkedIds shouldContain publicRoot2.toString()
                linkedIds shouldNotContain membersOnlyRoot.toString()
                linkedIds shouldNotContain hiddenRoot.toString()
                linkedIds shouldNotContain comment.toString()
            }
        }

        // ── T7 ──────────────────────────────────────────────────────────────────────────
        test("T7: ETag/304 semantics -- match gives 304 with empty body, star matches, garbage does not, content change updates the ETag") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, content = "ETag Test")
                val first = client.get("/s/$id")
                val etag = first.headers[HttpHeaders.ETag]
                etag shouldNotBe null

                val notModified = client.get("/s/$id") { header(HttpHeaders.IfNoneMatch, etag!!) }
                notModified.status shouldBe HttpStatusCode.NotModified
                notModified.bodyAsText() shouldBe ""
                notModified.headers[HttpHeaders.ETag] shouldBe etag
                notModified.headers[HttpHeaders.CacheControl] shouldNotBe null

                client.get("/s/$id") { header(HttpHeaders.IfNoneMatch, "*") }.status shouldBe HttpStatusCode.NotModified
                client.get("/s/$id") { header(HttpHeaders.IfNoneMatch, "\"garbage-value\"") }.status shouldBe HttpStatusCode.OK

                insertPost(authorMemberId = author, parentId = id, rootId = id, depth = 1, content = "Neue Antwort")
                client.get("/s/$id").headers[HttpHeaders.ETag] shouldNotBe etag

                val stableA = client.get("/s/$id").headers[HttpHeaders.ETag]
                val stableB = client.get("/s/$id").headers[HttpHeaders.ETag]
                stableA shouldBe stableB
            }
        }

        test("T7: boosting a post changes its ETag") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                val before = client.get("/s/$id").headers[HttpHeaders.ETag]
                insertBoost(postId = id, memberId = author, amount = BigDecimal("5.00"))
                val after = client.get("/s/$id").headers[HttpHeaders.ETag]
                after shouldNotBe before
            }
        }

        // ── T8 ──────────────────────────────────────────────────────────────────────────
        test("T8: exceeding maxRequests gives 429+Retry-After for that IP only, another IP is unaffected") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 3, window = 1.minutes)) {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                repeat(3) {
                    client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "203.0.113.9") }.status shouldBe HttpStatusCode.OK
                }
                val blocked = client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "203.0.113.9") }
                blocked.status shouldBe HttpStatusCode.TooManyRequests
                blocked.headers[HttpHeaders.RetryAfter] shouldBe "60"

                client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "203.0.113.10") }.status shouldBe HttpStatusCode.OK
            }
        }

        test("T8: spoofing the FIRST X-Forwarded-For entry does not reset the bucket (useLastProxy)") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "1.1.1.1, 203.0.113.9") }.status shouldBe HttpStatusCode.OK
                client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "2.2.2.2, 203.0.113.9") }.status shouldBe HttpStatusCode.OK
                client
                    .get("/s/$id") { header(HttpHeaders.XForwardedFor, "3.3.3.3, 203.0.113.9") }
                    .status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("T8: IPv6 addresses sharing a /64 share a bucket, a different /64 does not") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "2001:db8:1:1::1") }.status shouldBe HttpStatusCode.OK
                client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "2001:db8:1:1::2") }.status shouldBe HttpStatusCode.OK
                client
                    .get("/s/$id") { header(HttpHeaders.XForwardedFor, "2001:db8:1:1::3") }
                    .status shouldBe HttpStatusCode.TooManyRequests
                client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "2001:db8:1:2::1") }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── T9 ──────────────────────────────────────────────────────────────────────────
        test("T9: HEAD requests count against the same rate-limit bucket as GET") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)) {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)
                client.head("/s/$id") { header(HttpHeaders.XForwardedFor, "198.51.100.5") }.status shouldBe HttpStatusCode.OK
                client.head("/s/$id") { header(HttpHeaders.XForwardedFor, "198.51.100.5") }.status shouldBe HttpStatusCode.OK
                client
                    .head("/s/$id") { header(HttpHeaders.XForwardedFor, "198.51.100.5") }
                    .status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        // ── T10 ─────────────────────────────────────────────────────────────────────────
        test("T10: free LTR balance and author member UUID never leak into the public HTML (X7)") {
            testApp {
                val author = createAuthor()
                transaction {
                    LtrLedgerEntryTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = author
                        it[entryType] = LtrLedgerEntryType.MINT
                        it[amountLtr] = BigDecimal("4242.42")
                        it[referenceType] = null
                        it[referenceId] = null
                        it[note] = "Test seed"
                        it[createdBy] = null
                        it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    }
                }
                val id = insertPost(authorMemberId = author)
                val html = client.get("/s/$id").bodyAsText()
                html shouldNotContain "4242"
                html shouldNotContain author.toString()
            }
        }

        test("T10 unit: SocialReadPipeline.post with viewerStatus=null yields authorFreeBalanceLtr=null") {
            val author = createAuthor()
            transaction {
                LtrLedgerEntryTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = author
                    it[entryType] = LtrLedgerEntryType.MINT
                    it[amountLtr] = BigDecimal("999.00")
                    it[referenceType] = null
                    it[referenceId] = null
                    it[note] = "Test seed"
                    it[createdBy] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            val id = insertPost(authorMemberId = author)
            val dto =
                transaction {
                    SocialReadPipeline.post(
                        postUuid = id,
                        condition = SocialVisibility.publicReadableCondition(),
                        now = DbClock.nowLocalDateTime(),
                        viewerStatus = null,
                        caps = SocialReadPipeline.SocialReadCaps.PUBLIC,
                        ltrBalanceProvider = LedgerBackedLtrBalanceProvider(),
                    )
                }
            dto?.authorFreeBalanceLtr shouldBe null
        }

        // ── T5 end-to-end (M5-Fix, Review-Runde 1) ────────────────────────────────────────
        test(
            "T5 XSS-Katalog end-to-end: malicious root-post content, COMMENT content, and display " +
                "name are all escaped over real HTTP -- not just in the pure renderer test",
        ) {
            // The pure-renderer catalog (SocialPublicHtmlTest) proves kotlinx.html itself escapes
            // correctly, but never runs a single byte through testApplication/the real handler
            // pipeline. This closes that gap for the three user-controlled strings that reach the
            // public HTML body: post content, comment (descendant) content, and member display name.
            testApp {
                // N3-Fix (Review-Runde 2): root and comment now carry DISTINGUISHABLE payloads.
                // Before this fix both used the identical "<script>alert(1)</script>" string, so a
                // bug that dropped the comment from the render entirely (never even attempted to
                // escape it) would have left this test green anyway -- shouldNotContain on a string
                // that never appeared in the first place proves nothing. Distinct payloads plus the
                // positive assertions below close that gap.
                val rootPayload = "<script>alert(root)</script>"
                val commentPayload = "<script>alert(comment)</script>"
                val author = createAuthor(displayName = "<script>alert('author')</script>")
                val root = insertPost(authorMemberId = author, content = rootPayload)
                insertPost(authorMemberId = author, parentId = root, rootId = root, depth = 1, content = commentPayload)

                val timelineHtml = client.get("/s").bodyAsText()
                val threadHtml = client.get("/s/$root").bodyAsText()

                listOf(timelineHtml, threadHtml).forEach { html ->
                    html shouldNotContain "<script>alert(root)"
                    html shouldNotContain "<script>alert(comment)"
                    html shouldNotContain "<script>alert('author')"
                }

                // Positive assertions: the ESCAPED form must actually be present -- proving the
                // content was rendered (and escaped), not silently omitted.
                timelineHtml shouldContain "&lt;script&gt;alert(root)"
                threadHtml shouldContain "&lt;script&gt;alert(root)"
                threadHtml shouldContain "&lt;script&gt;alert(comment)"
            }
        }

        // ── T11 ─────────────────────────────────────────────────────────────────────────
        test("T11: no comment-/boost-count marker in the public output; displayed total weight sums every descendant") {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author, content = "T11 Wurzel", initialWeightLtr = BigDecimal("10.00"))
                insertPost(
                    authorMemberId = author,
                    parentId = root,
                    rootId = root,
                    depth = 1,
                    content = "T11 Kommentar Eins",
                    initialWeightLtr = BigDecimal("3.00"),
                )
                insertPost(
                    authorMemberId = author,
                    parentId = root,
                    rootId = root,
                    depth = 1,
                    content = "T11 Kommentar Zwei",
                    initialWeightLtr = BigDecimal("2.00"),
                )
                val html = client.get("/s/$root").bodyAsText()

                // X5: no counter marker of any kind (comment count, boost count, "N Antworten" etc.)
                // -- the public view model has no field for one, this is a regression guard.
                html shouldNotContain "Kommentare:"
                html shouldNotContain "Antworten ("
                html shouldNotContain "Boosts:"

                // The one number that IS shown -- the total weight -- must equal the sum of every
                // descendant's own weight (E3/aggregateWeightsUnrounded semantics), created close
                // enough together here that decay is negligible: 10.00 + 3.00 + 2.00 = 15.00.
                extractTotalWeight(html) shouldBe "15.00"
            }
        }

        // ── T12 ─────────────────────────────────────────────────────────────────────────
        test("T12 (X4 Defense in Depth): a directly-inserted MEMBERS_ONLY comment under a PUBLIC root is not rendered") {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author, content = "Public Root For X4")
                insertPost(
                    authorMemberId = author,
                    parentId = root,
                    rootId = root,
                    depth = 1,
                    visibility = SocialPostVisibility.MEMBERS_ONLY,
                    content = "Injected Members Only Comment Unique Text",
                )
                val html = client.get("/s/$root").bodyAsText()
                html shouldNotContain "Injected Members Only Comment Unique Text"
            }
        }

        // ── T13 ─────────────────────────────────────────────────────────────────────────
        test("T13: sitemap has only PUBLIC+VISIBLE roots, no comment ids, valid XML, no Host-header injection") {
            testApp {
                val author = createAuthor()
                val publicRoot = insertPost(authorMemberId = author)
                val hiddenRoot = insertPost(authorMemberId = author, state = SocialPostState.HIDDEN_BY_AUTHOR)
                val membersOnlyRoot = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_ONLY)
                val comment = insertPost(authorMemberId = author, parentId = publicRoot, rootId = publicRoot, depth = 1)

                val response = client.get("/sitemap.xml") { header(HttpHeaders.Host, "evil.example") }
                val xml = response.bodyAsText()
                xml shouldContain publicRoot.toString()
                xml shouldNotContain hiddenRoot.toString()
                xml shouldNotContain membersOnlyRoot.toString()
                xml shouldNotContain comment.toString()
                xml shouldNotContain "evil.example"

                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.newDocumentBuilder().parse(xml.byteInputStream())
            }
        }

        test("T13: sitemap lastmod rises after a new comment is added, and a hidden root disappears") {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author)
                val beforeXml = client.get("/sitemap.xml").bodyAsText()
                beforeXml shouldContain root.toString()
                val lastmodBefore = extractLastmod(xml = beforeXml, id = root)

                // G1-Fix (Review-Runde 1): lastmod is now a full W3C Datetime (microsecond
                // precision, via DbClock), not merely a date -- so this asserts a genuine VALUE
                // increase, not just presence, closing the gap the original (date-only) test left.
                transaction { Thread.sleep(5) } // ensure a measurable wall-clock delta
                insertPost(authorMemberId = author, parentId = root, rootId = root, depth = 1)
                val afterXml = client.get("/sitemap.xml").bodyAsText()
                afterXml shouldContain root.toString()
                val lastmodAfter = extractLastmod(xml = afterXml, id = root)
                // Parsed as java.time.Instant (Comparable), not compared as raw strings -- the
                // W3C-Datetime output trims trailing fractional-second zeros (java.time.Instant
                // .toString() semantics), so two timestamps can have different digit counts and
                // would sort incorrectly under plain lexicographic string comparison.
                (java.time.Instant.parse(lastmodAfter) > java.time.Instant.parse(lastmodBefore)) shouldBe true

                transaction { SocialPostTable.update({ SocialPostTable.id eq root }) { it[state] = SocialPostState.HIDDEN_BY_AUTHOR } }
                client.get("/sitemap.xml").bodyAsText() shouldNotContain root.toString()
            }
        }

        // ── N2 (Review-Runde 2): sitemap SHARD path ────────────────────────────────────────
        // The M2 rewrite (Review-Runde 1) replaced the whole sitemap loading strategy with a
        // per-shard SQL-paginated one -- see SocialPublicSitemap KDoc "Review-Runde-1 finding M2" --
        // but Review-Runde 1 only ever exercised /sitemap.xml (single-file case, one Test-Wurzel,
        // always shard 1 under the covers). None of /sitemap-{n}.xml, renderSitemapIndex, or
        // loadEntriesForShard(shard > 1) had a single assertion of their own. Per the reviewer's
        // suggestion, this closes the gap WITHOUT 45 000 fixtures: shard 1 with the handful of
        // Wurzeln this suite already creates is enough to prove the shard route works at all, and
        // shard 2 being empty (offset 45 000 into a test DB with far fewer public roots) is enough
        // to prove the 404-on-exhausted-shard path.
        test("N2: /sitemap-1.xml is 200 with the test root, /sitemap-2.xml 404s (shard 2 is empty in this DB)") {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author, content = "N2 Shard Eins")

                val shard1 = client.get("/sitemap-1.xml")
                shard1.status shouldBe HttpStatusCode.OK
                val xml = shard1.bodyAsText()
                xml shouldContain root.toString()
                val factory = DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                factory.newDocumentBuilder().parse(xml.byteInputStream())

                // offset (shard - 1) * MAX_URLS_PER_FILE = 45 000 -- far beyond this test DB's
                // handful of public roots, so shard 2 is genuinely empty -> 404 (not a crash).
                client.get("/sitemap-2.xml").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("N2: /sitemap-{shard}.xml 404s for shard 0, a shard past MAX_SHARDS, and a non-numeric shard") {
            testApp {
                listOf("0", (SocialPublicSitemap.MAX_SHARDS + 1).toString(), "abc").forEach { shard ->
                    client.get("/sitemap-$shard.xml").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("N2: renderSitemapIndex(shardCount = 3) is a pure function -- lists exactly sitemap-1..3.xml") {
            val xml = SocialPublicSitemap.renderSitemapIndex(baseUrl = "https://cloud.lapisproject.dev", shardCount = 3)
            xml shouldContain "<sitemapindex"
            (1..3).forEach { n -> xml shouldContain "<loc>https://cloud.lapisproject.dev/sitemap-$n.xml</loc>" }
            xml shouldNotContain "sitemap-4.xml"
            xml shouldNotContain "sitemap-0.xml"
        }

        test("N2: loadEntriesForShard(1) returns its entries sorted ascending by id (determinism from M2)") {
            val author = createAuthor()
            val ids = (1..5).map { insertPost(authorMemberId = author, content = "N2 Sort Test") }
            val entries = transaction { SocialPublicSitemap.loadEntriesForShard(shard = 1) }
            val relevant = entries.filter { it.id in ids }
            relevant.size shouldBe ids.size
            relevant.map { it.id.toString() } shouldBe relevant.map { it.id.toString() }.sorted()
        }

        // ── T14 ─────────────────────────────────────────────────────────────────────────
        test("T14: robots.txt is text/plain, references the sitemap, and disallows the internal route families") {
            testApp {
                val response = client.get("/robots.txt")
                response.status shouldBe HttpStatusCode.OK
                (response.headers[HttpHeaders.ContentType] ?: "") shouldContain "text/plain"
                val body = response.bodyAsText()
                body shouldContain "Sitemap:"
                body shouldContain "/sitemap.xml"
                body shouldContain "Disallow: /api/"
                body shouldContain "Disallow: /federation/"
                body shouldContain "Disallow: /.well-known/"
                // N4-Fix (Review-Runde 2): the /rpc/ entry was added in Fix-Runde 1 (G2) but this
                // test never grew the matching assertion.
                body shouldContain "Disallow: /rpc/"
                body shouldContain "Allow: /s"
                // Security-Fix (Review): /transparenz is now Disallow, not Allow -- the ranking
                // sections carry revocable, widely-crawlable PII (see PublicTransparencyHtml's
                // "noindex,follow" robots meta, the belt-and-suspenders companion to this).
                body shouldContain "Disallow: /transparenz"
                body shouldNotContain "Allow: /transparenz"
            }
        }

        // ── T15 ─────────────────────────────────────────────────────────────────────────
        test("T15: security headers are present on 200, 304, 404, and 429") {
            testApp(readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author)

                val ok = client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "192.0.2.50") }
                ok.status shouldBe HttpStatusCode.OK
                assertSecurityHeaders(ok.headers)
                val etag = ok.headers[HttpHeaders.ETag]!!

                val notModified =
                    client.get("/s/$id") {
                        header(HttpHeaders.XForwardedFor, "192.0.2.51")
                        header(HttpHeaders.IfNoneMatch, etag)
                    }
                notModified.status shouldBe HttpStatusCode.NotModified
                assertSecurityHeaders(notModified.headers)

                val notFound = client.get("/s/${Uuid.random()}") { header(HttpHeaders.XForwardedFor, "192.0.2.52") }
                notFound.status shouldBe HttpStatusCode.NotFound
                assertSecurityHeaders(notFound.headers)

                val tooMany = client.get("/s/$id") { header(HttpHeaders.XForwardedFor, "192.0.2.50") }
                tooMany.status shouldBe HttpStatusCode.TooManyRequests
                assertSecurityHeaders(tooMany.headers)
            }
        }

        // ── N1 (Review-Runde 2): withPublicErrorHandling / respondPublicServerError ───────
        // The M1-Fix wrapper (Review-Runde 1) is the single most security-critical piece of new code
        // this welle added -- it is the ONLY thing standing between an unforeseen exception and a
        // bare Ktor 500 with no security headers and no `Cache-Control: no-store`. Yet it had zero
        // direct test coverage: T2/T15/T16 exercise the EXPECTED-error paths (404/429), never a
        // genuinely thrown exception. `withPublicErrorHandling`/`respondPublicServerError` were made
        // `internal` (N1-Fix) specifically so this test can drive them directly via a throwaway test
        // route, instead of needing to somehow provoke a real failure deep inside
        // `registerSocialPublicRoutes`'s DB-backed handlers.
        test(
            "N1: withPublicErrorHandling turns an unforeseen exception into a security-header-complete, " +
                "no-store 500 with no stack trace / exception detail leaked",
        ) {
            testApplication {
                application {
                    routing {
                        get("/n1-test-throw") {
                            call.withPublicErrorHandling(baseUrl = "https://cloud.lapisproject.dev") {
                                throw IllegalStateException("super-secret-internal-detail-must-never-leak")
                            }
                        }
                    }
                }
                val response = client.get("/n1-test-throw")
                response.status shouldBe HttpStatusCode.InternalServerError
                assertSecurityHeaders(response.headers)
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                val body = response.bodyAsText()
                body shouldNotContain "IllegalStateException"
                body shouldNotContain "super-secret-internal-detail-must-never-leak"
                body shouldNotContain "Exception"
                body shouldNotContain "at network.lapis"
            }
        }

        // ── T16 ─────────────────────────────────────────────────────────────────────────
        test("T16: pagination never 500s on invalid/out-of-range ?page values") {
            testApp {
                // S2-3 (Runde 2, 2026-08-18): every value below is now a NON-canonical `page` value
                // (an invalid/out-of-range input clamps to a page whose canonical decimal string
                // differs from what was sent), so each 308-redirects to its canonical URL instead of
                // rendering directly -- `noRedirectClient` observes the redirect itself rather than
                // following it (the target is an absolute `FederationConfig.publicBaseUrl` URL whose
                // authority the in-process test engine cannot resolve, same reason T17/S2-3 below use
                // the same pattern). The invariant T16 actually protects -- never a 500, however
                // garbage `?page=` is -- holds regardless of which of the two now applies.
                val noRedirectClient = createClient { followRedirects = false }
                listOf("0", "-1", "abc", "999999", "1e9").forEach { value ->
                    val status = noRedirectClient.get("/s?page=$value").status.value
                    (status == 200 || status == 308) shouldBe true
                }
                // Ktor's `Parameters.get` returns the FIRST occurrence ("2") -- already canonical for
                // page 2, so this renders directly, no redirect.
                client.get("/s?page=2&page=3").status shouldBe HttpStatusCode.OK

                // `?page=1` is itself non-canonical since S2-3 (the canonical page-1 URL carries no
                // `page` parameter at all) -- assert the ACTUAL canonical URLs render with the
                // expected `robots` value instead of following the redirect through it.
                client.get("/s").bodyAsText() shouldContain "index,follow"
                client.get("/s?page=2").bodyAsText() shouldContain "noindex,follow"
            }
        }

        // ── T17 ─────────────────────────────────────────────────────────────────────────
        test("T17: GET /s/{commentId} 308-redirects to /s/{rootId}") {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author)
                val comment = insertPost(authorMemberId = author, parentId = root, rootId = root, depth = 1)
                val noRedirectClient = createClient { followRedirects = false }
                val response = noRedirectClient.get("/s/$comment")
                response.status.value shouldBe 308
                (response.headers[HttpHeaders.Location] ?: "") shouldContain "/s/$root"
            }
        }

        test("G5 (Review-Runde 1): GET /s/{commentId} never 308-redirects to a root that is itself not public-readable") {
            testApp {
                val author = createAuthor()
                // Directly-inserted, S5-invariant-violating fixture (same technique as T12): a
                // public, visible comment under a root that is NOT public-readable. Under the real
                // write path this cannot happen -- this proves the redirect defends in depth anyway.
                val root = insertPost(authorMemberId = author, visibility = SocialPostVisibility.MEMBERS_ONLY)
                val comment = insertPost(authorMemberId = author, parentId = root, rootId = root, depth = 1)
                client.get("/s/$comment").status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── T18 ─────────────────────────────────────────────────────────────────────────
        test("T18: identical body for a crawler UA and a browser UA (no cloaking)") {
            testApp {
                val author = createAuthor()
                val id = insertPost(authorMemberId = author, content = "Kein Cloaking")
                val bot = client.get("/s/$id") { header(HttpHeaders.UserAgent, "Googlebot/2.1") }.bodyAsText()
                val human = client.get("/s/$id") { header(HttpHeaders.UserAgent, "Mozilla/5.0 (Test Browser)") }.bodyAsText()
                bot shouldBe human
            }
        }

        // ── T19 ─────────────────────────────────────────────────────────────────────────
        // Covered by SocialNetworkServiceTest itself (unchanged and green) -- see SocialReadPipeline
        // KDoc "Beweis, dass die Extraktion verhaltensneutral war".

        // ── T20 ─────────────────────────────────────────────────────────────────────────
        test("T20: public timeline sorts by total weight descending") {
            testApp {
                val author = createAuthor()
                val low = insertPost(authorMemberId = author, content = "Low Weight Post", initialWeightLtr = BigDecimal("1.00"))
                val high = insertPost(authorMemberId = author, content = "High Weight Post", initialWeightLtr = BigDecimal("50.00"))
                val html = client.get("/s").bodyAsText()
                val highIndex = html.indexOf(high.toString())
                val lowIndex = html.indexOf(low.toString())
                highIndex shouldNotBe -1
                lowIndex shouldNotBe -1
                (highIndex < lowIndex) shouldBe true
            }
        }

        // ── S-1 (Security-Audit 2026-08-18) ────────────────────────────────────────────
        test(
            "S-1: a crafted thread with many newline-heavy comments yields a body well under the " +
                "byte budget, and is announced with the truncation notice, instead of growing unbounded",
        ) {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author, content = "S-1 Wurzel")
                // Each child's content is exactly at MAX_CONTENT_LENGTH (5 000 chars,
                // `SocialNetworkService.MAX_CONTENT_LENGTH`), overwhelmingly newlines -- 2 500 lines
                // of "x\n", the exact shape the audit finding used to blow the body size up to ~80 MB
                // (pretty-printed) / ~35 MB (compact) BEFORE THREAD_DESCENDANTS_BYTE_BUDGET existed:
                // one `<p>` per line, so the per-line TAG overhead dominates over the actual content.
                // 60 such children is deliberately well BELOW SocialReadCaps.PUBLIC.threadMaxNodes
                // (300) -- this proves the BYTE budget (not just the row-count cap) is what stops the
                // render; the row-count cap alone would not have triggered here.
                val childCount = 60
                val childContent = "x\n".repeat(2_500)
                childContent.length shouldBe 5_000
                val childIds = (0 until childCount).map { Uuid.random() }
                transaction {
                    SocialPostTable.batchInsert(childIds, shouldReturnGeneratedValues = false) { childId ->
                        this[SocialPostTable.id] = childId
                        this[SocialPostTable.parentId] = root
                        this[SocialPostTable.rootId] = root
                        this[SocialPostTable.depth] = 1
                        this[SocialPostTable.authorMemberId] = author
                        this[SocialPostTable.content] = childContent
                        this[SocialPostTable.visibility] = SocialPostVisibility.PUBLIC
                        this[SocialPostTable.initialWeightLtr] = BigDecimal("0.01")
                        this[SocialPostTable.publishedAt] = DbClock.nowLocalDateTime()
                        this[SocialPostTable.state] = SocialPostState.VISIBLE
                        this[SocialPostTable.stateChangedAt] = null
                        this[SocialPostTable.stateChangedBy] = null
                        this[SocialPostTable.stateReason] = null
                    }
                }
                createdPostIds += childIds

                val response = client.get("/s/$root")
                response.status shouldBe HttpStatusCode.OK
                val bodyBytes = response.bodyAsText().toByteArray(Charsets.UTF_8).size
                // Comfortably above THREAD_DESCENDANTS_BYTE_BUDGET (1.5 MB) to allow for the root
                // post/head/nav/footer overhead the budget itself does not cover, but WORLDS below
                // the tens-of-MB the pre-fix renderer would have produced for 60 * 5 000-char,
                // newline-heavy nodes (well over 1 MB per node alone under the old prettyPrint=true
                // renderer).
                (bodyBytes < 2_000_000) shouldBe true
                response.bodyAsText() shouldContain "Weitere Antworten werden hier nicht angezeigt."
            }
        }

        // ── S2-1 / S2-4 (Security-Audit-Runde 2, 2026-08-18) ───────────────────────────
        // The S-1 test above (newline-heavy content) exercises a case where the ORIGINAL, buggy
        // `estimatedRenderedByteSize()` (`line.toByteArray(Charsets.UTF_8).size`, the RAW input's
        // byte size) happened to OVER-estimate relative to the actual rendered output -- newlines
        // themselves need no HTML-escaping, so that test would have stayed green even with the S2-1
        // bug present. This test uses QUOTE-heavy content instead -- `"` is the single worst-case
        // HTML-escape character (`&quot;`, 1 raw byte -> 6 rendered bytes) -- which is exactly the
        // shape Runde-2 finding S2-1 identified as making the pre-fix estimate UNDER-count by a
        // factor of up to 5.8x. `childCount` (100) is chosen ABOVE what the OLD estimate's per-node
        // cost (~5 312 bytes: raw byte size, not escaped) would have capped at (~282 nodes) -- so the
        // old code would have rendered ALL 100 nodes, producing an ACTUAL (escaped) body comfortably
        // over 3 MB, well past the `bodyBytes < 2_000_000` assertion below (this test would have been
        // RED before the S2-1 fix). The NEW, corrected estimate (`length *
        // MAX_ESCAPED_BYTES_PER_CHAR`) instead caps the render at roughly 48 nodes (~30 800 bytes
        // estimated each against the 1.5 MB budget), keeping the actual body under 2 MB -- proving the
        // byte estimate is now a genuine upper bound, not merely a coincidentally-safe one for
        // newline-shaped content.
        test(
            "S2-4: a crafted thread with many quote-heavy comments (the HTML-escape worst case) yields " +
                "a body well under the byte budget -- proves the S2-1 fix, not just the original S-1 case",
        ) {
            testApp {
                val author = createAuthor()
                val root = insertPost(authorMemberId = author, content = "S2-4 Wurzel")
                // Exactly MAX_CONTENT_LENGTH (5 000 chars), entirely `"` -- the single worst-case
                // HTML-escape expansion (`&quot;`, 6 rendered bytes per raw byte).
                val childCount = 100
                val childContent = "\"".repeat(5_000)
                childContent.length shouldBe 5_000
                val childIds = (0 until childCount).map { Uuid.random() }
                transaction {
                    SocialPostTable.batchInsert(childIds, shouldReturnGeneratedValues = false) { childId ->
                        this[SocialPostTable.id] = childId
                        this[SocialPostTable.parentId] = root
                        this[SocialPostTable.rootId] = root
                        this[SocialPostTable.depth] = 1
                        this[SocialPostTable.authorMemberId] = author
                        this[SocialPostTable.content] = childContent
                        this[SocialPostTable.visibility] = SocialPostVisibility.PUBLIC
                        this[SocialPostTable.initialWeightLtr] = BigDecimal("0.01")
                        this[SocialPostTable.publishedAt] = DbClock.nowLocalDateTime()
                        this[SocialPostTable.state] = SocialPostState.VISIBLE
                        this[SocialPostTable.stateChangedAt] = null
                        this[SocialPostTable.stateChangedBy] = null
                        this[SocialPostTable.stateReason] = null
                    }
                }
                createdPostIds += childIds

                val response = client.get("/s/$root")
                response.status shouldBe HttpStatusCode.OK
                val bodyText = response.bodyAsText()
                val bodyBytes = bodyText.toByteArray(Charsets.UTF_8).size
                // Same threshold as the S-1 test: comfortably above THREAD_DESCENDANTS_BYTE_BUDGET
                // (1.5 MB) for root/head/nav/footer overhead, but WORLDS below the >3 MB the pre-S2-1
                // renderer would have produced for 100 * 5 000-quote nodes (each escaping to ~30 KB).
                (bodyBytes < 2_000_000) shouldBe true
                bodyText shouldContain "Weitere Antworten werden hier nicht angezeigt."
                // Escaping is actually happening (sanity check that the fixture content really does
                // expand on render, not just in theory).
                bodyText shouldContain "&quot;"
            }
        }

        // ── S2-3 (Security-Audit-Runde 2, 2026-08-18) ───────────────────────────────────
        test(
            "S2-3: GET /s canonicalizes non-canonical ?page VALUES (not just unknown parameter names) " +
                "via 308-redirect, so every equivalent request collapses to one cache key",
        ) {
            testApp {
                val noRedirectClient = createClient { followRedirects = false }

                // `?page=1` explicitly present -> non-canonical, the canonical URL for page 1 carries
                // no `page` parameter at all. `FederationConfig.publicBaseUrl` defaults to
                // `http://localhost:8080` (`LAPIS_PUBLIC_BASE_URL` unset in tests) -- every emitted
                // canonical/redirect URL in this file is built from THAT, never from the request's
                // own `Host`, see [SocialPublicHtml] class KDoc point 3.
                val explicitPage1 = noRedirectClient.get("/s?page=1")
                explicitPage1.status.value shouldBe 308
                explicitPage1.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/s"

                // Leading-zero / non-canonical decimal forms of an otherwise valid page number.
                val leadingZero = noRedirectClient.get("/s?page=02")
                leadingZero.status.value shouldBe 308
                leadingZero.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/s?page=2"

                // Out-of-range values still canonicalize to whatever `parsePage` clamps them to,
                // consistent with T16's "never werfend geparst" contract -- a redirect, never a 404/500.
                val outOfRange = noRedirectClient.get("/s?page=999999")
                outOfRange.status.value shouldBe 308
                outOfRange.headers[HttpHeaders.Location] shouldBe "http://localhost:8080/s?page=25"

                // The one already-canonical case: no redirect, direct 200.
                val canonical = noRedirectClient.get("/s?page=2")
                canonical.status.value shouldBe 200

                // No `page` parameter at all is already canonical for page 1.
                val noParam = noRedirectClient.get("/s")
                noParam.status.value shouldBe 200
            }
        }

        // ── rateLimitKeyFor unit tests ──────────────────────────────────────────────────
        test("rateLimitKeyFor: IPv4 is unchanged, IPv6 addresses sharing a /64 map to the same key") {
            rateLimitKeyFor("203.0.113.9") shouldBe "ip:203.0.113.9"
            rateLimitKeyFor("2001:db8:1:1::1") shouldBe rateLimitKeyFor("2001:db8:1:1::2")
            (rateLimitKeyFor("2001:db8:1:1::1") == rateLimitKeyFor("2001:db8:1:2::1")) shouldBe false
            // Zone id stripped before parsing.
            rateLimitKeyFor("fe80::1%eth0") shouldBe rateLimitKeyFor("fe80::2%eth0")
            // Unparseable input never throws, falls back to the raw string.
            rateLimitKeyFor("not-an-ip-at-all") shouldBe "ip:not-an-ip-at-all"
        }

        test("G6 (Review-Runde 1): rateLimitKeyFor strips the zone id in the FALLBACK branch too, not only the IPv6 success path") {
            // "not-an-ip%eth0" is not a parseable IPv6 literal (ipv6Slash64Prefix returns null for
            // it), so this exercises the fallback branch specifically. Before the fix, the fallback
            // built its key from the ORIGINAL remoteHost (zone id included), contradicting the
            // function's own KDoc -- two requests differing only by zone id landed in different
            // buckets.
            rateLimitKeyFor("not-an-ip%eth0") shouldBe rateLimitKeyFor("not-an-ip%wlan0")
            rateLimitKeyFor("not-an-ip%eth0") shouldBe "ip:not-an-ip"
        }
    })
