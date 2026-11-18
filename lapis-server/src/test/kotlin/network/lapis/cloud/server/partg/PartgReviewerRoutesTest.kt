package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
import network.lapis.cloud.server.db.generated.PartgReviewerInvitationTable
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.server.routes.PartgReviewerRateLimits
import network.lapis.cloud.server.routes.registerPartgReportExportRoutes
import network.lapis.cloud.server.routes.registerPartgReviewerRoutes
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.PartgReportFormat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.310 (R6b) -- the public routes of the auditor over HTTP: the answers that must not tell a valid link from an invalid one are identical, the
 * cookie (own name, own path, HttpOnly, SameSite=Strict, Secure on demand) and its separation from the members' session in BOTH directions, session
 * fixation, the scope (one 404 for everything else), the limits (429 with the neutral body), the headers on every answer, origin and content type,
 * the party switch, the integrity check and the byte-identical JSON.
 */
class PartgReviewerRoutesTest :
    FunSpec({
        val db = TestDatabase.H2
        val fx = PartgFixtures()
        val year = 2009
        val sent = mutableListOf<Pair<String, String>>()
        val open = FederationInboxRateLimiter(maxRequests = 100_000, window = 60.minutes)

        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest {
            fx.setParty(true)
            sent.clear()
        }
        afterTest {
            ServerClock.source = Clock.System
            fx.cleanUp()
        }
        afterSpec { db.deactivate() }

        fun limits(
            landing: Int = 100_000,
            codeIp: Int = 100_000,
            codeToken: Int = 100_000,
            verify: Int = 100_000,
            read: Int = 100_000,
        ) = PartgReviewerRateLimits(
            landing = FederationInboxRateLimiter(maxRequests = landing, window = 60.minutes),
            codeRequestIp = FederationInboxRateLimiter(maxRequests = codeIp, window = 60.minutes),
            codeRequestToken = FederationInboxRateLimiter(maxRequests = codeToken, window = 60.minutes),
            codeVerifyIp = FederationInboxRateLimiter(maxRequests = verify, window = 60.minutes),
            read = FederationInboxRateLimiter(maxRequests = read, window = 60.minutes),
        )

        fun withRoutes(
            limits: PartgReviewerRateLimits = limits(),
            cookieSecure: Boolean = false,
            renderer: (suspend (PartgStoredVersionRenderer.Verified, PartgReportFormat) -> PartgStoredVersionRenderer.Rendered)? = null,
            block: suspend HttpClient.() -> Unit,
        ) = testApplication {
            val access =
                if (renderer == null) {
                    PartgReviewerAccess(sendCode = { email, code -> sent += email to code })
                } else {
                    PartgReviewerAccess(sendCode = { email, code -> sent += email to code }, renderer = renderer)
                }
            application {
                routing {
                    registerPartgReviewerRoutes(limits = limits, access = access, cookieSecure = cookieSecure)
                    registerPartgReportExportRoutes(rateLimiter = open, concurrency = Semaphore(2))
                }
            }
            client.block()
        }

        fun ops() = PartgReviewerOperations(writeRateLimiter = open)

        fun board() = fx.member(label = "board", role = AccountRole.BOARD)

        fun realVersion(member: Uuid): Uuid =
            PartgVersionWriter.freeze(
                current = fx.currentOf(memberId = member),
                year = year,
                scope = network.lapis.cloud.shared.domain.PartgReportScope.All,
                comment = null,
                now = ServerClock.now(),
            )

        fun fakeVersion(
            by: Uuid,
            reportYear: Int,
            number: Int = 1,
        ): Uuid =
            transaction {
                val at = LocalDateTime(2026, 1, 1, 0, 0)
                val counter = PartgVersionStore.lockCounter(year = reportYear, scopeKey = "ALL", actor = by, now = at)
                val json = "{\"fake\":$number}"
                val id =
                    PartgVersionStore.insertVersion(
                        counter = counter,
                        number = number,
                        formatVersion = PartgVersionFormat.CURRENT,
                        contentJson = json,
                        contentHash = PartgVersionFormat.hash(json),
                        byteSize = json.length,
                        hasThirdPartyData = false,
                        comment = null,
                        actor = by,
                        now = at,
                    )
                PartgVersionStore.bumpCounter(counter = counter, number = number, actor = by, now = at)
                id
            }

        fun tokenOf(link: String) = link.substringAfter("#t=")

        fun invite(
            member: Uuid,
            versions: List<Uuid>,
            days: Int = 14,
        ) = ops().create(
            current = fx.currentOf(memberId = member),
            year = year,
            versionIds =
                versions.map {
                    it.toString()
                },
            validityDays = days,
            email = "pruefer@example.org",
        )

        fun ownOrigin(): String {
            val uri = URI(FederationConfig.publicBaseUrl)
            return "${uri.scheme}://${uri.host}" + if (uri.port == -1) "" else ":${uri.port}"
        }

        suspend fun HttpClient.postJson(
            path: String,
            body: String,
            vararg extra: Pair<String, String>,
        ): HttpResponse =
            post(path) {
                contentType(ContentType.Application.Json)
                extra.forEach { (k, v) -> header(k, v) }
                setBody(body)
            }

        suspend fun HttpClient.askCode(token: String) = postJson("/api/pruefung/code", """{"token":"$token"}""")

        suspend fun HttpClient.login(
            token: String,
            code: String,
            vararg extra: Pair<String, String>,
        ) = postJson("/api/pruefung/session", """{"token":"$token","code":"$code"}""", *extra)

        fun HttpResponse.cookieValue(): String? =
            headers
                .getAll(HttpHeaders.SetCookie)
                .orEmpty()
                .firstOrNull { it.startsWith("lapis_pruefung=") }
                ?.substringAfter("lapis_pruefung=")
                ?.substringBefore(';')

        fun HttpResponse.rawCookie(): String = headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith("lapis_pruefung=") }

        suspend fun HttpClient.signIn(token: String): String {
            askCode(token)
            val response = login(token, sent.last().second)
            response.status shouldBe HttpStatusCode.NoContent
            return checkNotNull(response.cookieValue())
        }

        fun HttpResponse.hasSecurityHeaders() {
            headers[HttpHeaders.CacheControl] shouldBe "no-store"
            headers["Referrer-Policy"] shouldBe "no-referrer"
            headers["X-Content-Type-Options"] shouldBe "nosniff"
        }

        suspend fun HttpClient.versionGet(
            id: String,
            what: String,
            cookie: String,
        ) = get("/api/pruefung/versions/$id/$what") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }

        test("asking for a code: a valid link and every kind of invalid link get the same answer; only the valid one gets a mail") {
            val member = board()
            val v = fakeVersion(member, year)
            val valid = tokenOf(invite(member, listOf(v)).link)
            val revoked = invite(member, listOf(v))
            ops().revoke(current = fx.currentOf(memberId = member), invitationId = revoked.invitation.id)
            val locked = invite(member, listOf(v))
            transaction {
                PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq Uuid.parse(locked.invitation.id) }) {
                    it[lockedAt] = ServerClock.now()
                }
            }
            val flipped = valid.dropLast(1) + if (valid.last() == 'A') 'B' else 'A'
            val tokens =
                listOf(
                    valid to true,
                    PartgReviewerTokens.newLinkToken() to false,
                    tokenOf(revoked.link) to false,
                    tokenOf(locked.link) to false,
                    flipped to false,
                    "a".repeat(80) to false,
                    "not*a*token*of*any*alphabet*at*all" to false,
                    "short" to false,
                )
            withRoutes {
                val answers = tokens.map { (t, _) -> askCode(t) }
                answers.map { it.status }.toSet() shouldBe setOf(HttpStatusCode.Accepted)
                answers.map { it.bodyAsText() }.toSet().size shouldBe 1
                answers.forEach { it.hasSecurityHeaders() }
            }
            sent.map { it.first } shouldBe listOf("pruefer@example.org")
        }

        test("an ended link looks like an unknown one: the expired invitation gets nothing and the same answer") {
            val member = board()
            val v = fakeVersion(member, year)
            val expired = invite(member, listOf(v), days = 7)
            transaction {
                PartgReviewerInvitationTable.update({ PartgReviewerInvitationTable.id eq Uuid.parse(expired.invitation.id) }) {
                    it[expiresAt] = ServerClock.now().plusDuration(1.minutes)
                }
            }
            withRoutes {
                val before = askCode(PartgReviewerTokens.newLinkToken())
                ServerClock.source = FixedClock(ServerClock.nowInstant() + 2.hours)
                val after = askCode(tokenOf(expired.link))
                after.status shouldBe before.status
                after.bodyAsText() shouldBe before.bodyAsText()
            }
            sent.size shouldBe 0
        }

        test(
            "signing in: a wrong code and an unknown link get the same 401, the right code gives a 204 and a cookie with the right attributes",
        ) {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes(cookieSecure = true) {
                askCode(token)
                val code = sent.last().second
                val wrong = login(token, if (code == "000000") "111111" else "000000")
                val unknown = login(PartgReviewerTokens.newLinkToken(), code)
                val malformed = login(token, "abc")
                listOf(wrong, unknown, malformed).forEach {
                    it.status shouldBe HttpStatusCode.Unauthorized
                    it.hasSecurityHeaders()
                }
                setOf(wrong.bodyAsText(), unknown.bodyAsText(), malformed.bodyAsText()).size shouldBe 1
                val ok = login(token, code)
                ok.status shouldBe HttpStatusCode.NoContent
                ok.hasSecurityHeaders()
                val cookie = ok.rawCookie()
                cookie shouldContain "HttpOnly"
                cookie shouldContain "SameSite=Strict"
                cookie shouldContain "Path=/api/pruefung"
                cookie shouldContain "Secure"
                cookie shouldContain "Max-Age="
                (cookie.substringAfter("Max-Age=").substringBefore(';').toInt() in 1..4 * 3600) shouldBe true
            }
        }

        test("the cookie is not Secure when the instance runs on plain HTTP in development") {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes(cookieSecure = false) {
                askCode(token)
                login(token, sent.last().second).rawCookie() shouldNotContain "Secure"
            }
        }

        test(
            "session fixation: a cookie sent along is replaced, the old one stops working, and nothing the client sends becomes the session",
        ) {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes {
                val old = signIn(token)
                askCode(token)
                val response = login(token, sent.last().second, HttpHeaders.Cookie to "lapis_pruefung=$old")
                val fresh = checkNotNull(response.cookieValue())
                (fresh == old) shouldBe false
                get("/api/pruefung/session") { header(HttpHeaders.Cookie, "lapis_pruefung=$old") }.status shouldBe
                    HttpStatusCode.Unauthorized
                get("/api/pruefung/session") { header(HttpHeaders.Cookie, "lapis_pruefung=$fresh") }.status shouldBe HttpStatusCode.OK
                // an attacker's own value is never accepted as a session
                get(
                    "/api/pruefung/session",
                ) { header(HttpHeaders.Cookie, "lapis_pruefung=${PartgReviewerTokens.newLinkToken()}") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test(
            "reading: the session info, the list, the document byte for byte, the files and the comparison; logging out ends the session",
        ) {
            val member = board()
            val v = realVersion(member)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes {
                val cookie = signIn(token)
                val info = get("/api/pruefung/session") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                info.status shouldBe HttpStatusCode.OK
                info.bodyAsText() shouldContain "\"year\":$year"
                val list = get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                list.status shouldBe HttpStatusCode.OK
                list.bodyAsText() shouldContain v.toString()
                list.bodyAsText() shouldNotContain "frozenBy"
                list.hasSecurityHeaders()
                val stored = transaction { PartgReportVersionTable.selectAll().where { PartgReportVersionTable.id eq v }.single() }
                val doc = versionGet(v.toString(), "document", cookie)
                doc.status shouldBe HttpStatusCode.OK
                doc.readRawBytes().toString(Charsets.UTF_8) shouldBe stored[PartgReportVersionTable.contentJson]
                doc.headers[HttpHeaders.ContentType]!! shouldContain "application/json"
                val download =
                    get("/api/pruefung/versions/$v/document?download=1") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                download.headers[HttpHeaders.ContentDisposition]!! shouldContain "rechenschaftsbericht-$year-version-1.json"
                val pdf = versionGet(v.toString(), "pdf", cookie)
                pdf.status shouldBe HttpStatusCode.OK
                pdf.headers[HttpHeaders.ContentDisposition]!! shouldContain "rechenschaftsbericht-$year-version-1.pdf"
                pdf.hasSecurityHeaders()
                String(pdf.readRawBytes().copyOf(4)) shouldBe "%PDF"
                val xlsx = versionGet(v.toString(), "xlsx", cookie)
                xlsx.headers[HttpHeaders.ContentDisposition]!! shouldContain ".xlsx"
                val compare = get("/api/pruefung/compare?a=$v&b=$v") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                compare.status shouldBe HttpStatusCode.OK
                val end = post("/api/pruefung/session/end") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                end.status shouldBe HttpStatusCode.NoContent
                end.rawCookie() shouldContain "Max-Age=0"
                get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test("without a session every read is the same 401, also with a made-up id") {
            withRoutes {
                val answers =
                    listOf(
                        get("/api/pruefung/session"),
                        get("/api/pruefung/versions"),
                        get("/api/pruefung/versions/${Uuid.random()}/document"),
                        get("/api/pruefung/versions/${Uuid.random()}/pdf"),
                        get("/api/pruefung/versions/${Uuid.random()}/xlsx"),
                        get("/api/pruefung/compare?a=${Uuid.random()}&b=${Uuid.random()}"),
                    )
                answers.map { it.status }.toSet() shouldBe setOf(HttpStatusCode.Unauthorized)
                answers.map { it.bodyAsText() }.toSet().size shouldBe 1
                answers.forEach { it.hasSecurityHeaders() }
            }
        }

        test("scope: a version outside the invitation, of another year, a random id and a malformed id are one and the same 404") {
            val member = board()
            val inScope = realVersion(member)
            val outsideSameYear = fakeVersion(member, year, number = 9)
            val otherYear = fakeVersion(member, year + 1)
            val token = tokenOf(invite(member, listOf(inScope)).link)
            withRoutes {
                val cookie = signIn(token)
                val ids =
                    listOf(outsideSameYear.toString(), otherYear.toString(), Uuid.random().toString(), "not-a-uuid", "0".repeat(40))
                val answers = ids.flatMap { id -> listOf("document", "pdf", "xlsx").map { versionGet(id, it, cookie) } }
                answers.map { it.status }.toSet() shouldBe setOf(HttpStatusCode.NotFound)
                answers.map { it.bodyAsText() }.toSet().size shouldBe 1
                answers.forEach { it.hasSecurityHeaders() }
                listOf(
                    "/api/pruefung/compare?a=$inScope&b=$outsideSameYear",
                    "/api/pruefung/compare?a=$outsideSameYear&b=$inScope",
                    "/api/pruefung/compare?a=$inScope",
                    "/api/pruefung/compare?a=x&b=y",
                ).forEach { path ->
                    val r = get(path) { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                    r.status shouldBe HttpStatusCode.NotFound
                    r.bodyAsText() shouldBe answers.first().bodyAsText()
                }
            }
        }

        test("the two sessions are separate: the auditor's cookie is nothing for the members' routes, a member's session is nothing here") {
            val member = board()
            val v = realVersion(member)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes {
                val cookie = signIn(token)
                val asMember = get("/api/partg-report/version/pdf?id=$v") { header(HttpHeaders.Cookie, "lapis_session=$cookie") }
                asMember.status.value shouldBeGreaterThanOrEqual 400
                val asBearer = get("/api/partg-report/version/pdf?id=$v") { header(HttpHeaders.Authorization, "Bearer $cookie") }
                asBearer.status.value shouldBeGreaterThanOrEqual 400
                val own = get("/api/partg-report/version/pdf?id=$v") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }
                own.status.value shouldBeGreaterThanOrEqual 400
                // the other direction: a member's identity and a member cookie open nothing below /api/pruefung
                val member1 =
                    get("/api/pruefung/versions") {
                        header("X-Member-Id", member.toString())
                        header(HttpHeaders.Cookie, "lapis_session=$cookie")
                        header(HttpHeaders.Authorization, "Bearer $cookie")
                    }
                member1.status shouldBe HttpStatusCode.Unauthorized
                get("/api/pruefung/session") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("an invitation revoked during the session ends it at once; so does the party switch") {
            val member = board()
            val v = fakeVersion(member, year)
            val created = invite(member, listOf(v))
            val token = tokenOf(created.link)
            withRoutes {
                val cookie = signIn(token)
                get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }.status shouldBe HttpStatusCode.OK
                fx.setParty(false)
                get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }.status shouldBe
                    HttpStatusCode.Unauthorized
                fx.setParty(true)
                val second = signIn(token)
                ops().revoke(current = fx.currentOf(memberId = member), invitationId = created.invitation.id)
                get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$second") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test("the party switch off gives the neutral answers everywhere") {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            fx.setParty(false)
            withRoutes {
                val code = askCode(token)
                code.status shouldBe HttpStatusCode.Accepted
                login(token, "123456").status shouldBe HttpStatusCode.Unauthorized
                get("/api/pruefung/versions").status shouldBe HttpStatusCode.Unauthorized
            }
            sent.size shouldBe 0
        }

        test("the stored text must still match its checksum: a tampered version is a 409 and nothing is sent") {
            val member = board()
            val v = realVersion(member)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes {
                val cookie = signIn(token)
                transaction {
                    PartgReportVersionTable.update({ PartgReportVersionTable.id eq v }) { it[contentJson] = "{\"tampered\":true}" }
                }
                listOf("document", "pdf", "xlsx").forEach {
                    val r = versionGet(v.toString(), it, cookie)
                    r.status shouldBe HttpStatusCode.Conflict
                    r.bodyAsText() shouldNotContain "tampered"
                }
            }
        }

        test("a version that is too large for the PDF or XLSX limits answers 413 with a JSON body, not a 500") {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            listOf<(String) -> Throwable>(
                {
                    throw network.lapis.cloud.server.pdf
                        .PdfLimitExceeded(it)
                },
                {
                    throw network.lapis.cloud.server.partg.report
                        .PartgReportTooLargeException(it)
                },
            ).forEach { boom ->
                withRoutes(renderer = { _, _ -> throw boom("limit") }) {
                    val cookie = signIn(token)
                    listOf("pdf", "xlsx").forEach { what ->
                        val answer = versionGet(v.toString(), what, cookie)
                        answer.status shouldBe HttpStatusCode.PayloadTooLarge
                        answer.bodyAsText() shouldContain "message"
                        answer.hasSecurityHeaders()
                    }
                }
            }
        }

        test("the limits answer 429 with the neutral body of the route and no Retry-After") {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes(limits = limits(codeIp = 2)) {
                val first = askCode(token)
                askCode(token)
                val third = askCode(token)
                third.status shouldBe HttpStatusCode.TooManyRequests
                third.bodyAsText() shouldBe first.bodyAsText()
                third.headers[HttpHeaders.RetryAfter] shouldBe null
                third.hasSecurityHeaders()
            }
            withRoutes(limits = limits(codeToken = 1)) {
                val a = askCode(token)
                val b = askCode(token)
                b.status shouldBe HttpStatusCode.TooManyRequests
                b.bodyAsText() shouldBe a.bodyAsText()
                // another token is not limited by this one
                askCode(PartgReviewerTokens.newLinkToken()).status shouldBe HttpStatusCode.Accepted
            }
            withRoutes(limits = limits(verify = 2)) {
                val a = login(token, "000000")
                login(token, "000000")
                val c = login(token, "000000")
                c.status shouldBe HttpStatusCode.TooManyRequests
                c.bodyAsText() shouldBe a.bodyAsText()
            }
            withRoutes(limits = limits(landing = 3)) {
                repeat(3) { get("/api/pruefung/versions") }
                get("/api/pruefung/versions").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("the read limit is per session: the 429 comes after the allowed requests of one session only") {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes(limits = limits(read = 3)) {
                val cookie = signIn(token)
                repeat(3) {
                    get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }.status shouldBe
                        HttpStatusCode.OK
                }
                get("/api/pruefung/versions") { header(HttpHeaders.Cookie, "lapis_pruefung=$cookie") }.status shouldBe
                    HttpStatusCode.TooManyRequests
            }
        }

        test("a foreign origin, a form post and an oversized body get the neutral answer and nothing happens") {
            val member = board()
            val v = fakeVersion(member, year)
            val token = tokenOf(invite(member, listOf(v)).link)
            withRoutes {
                val foreign = postJson("/api/pruefung/code", """{"token":"$token"}""", HttpHeaders.Origin to "https://evil.example")
                foreign.status shouldBe HttpStatusCode.Forbidden
                foreign.hasSecurityHeaders()
                val form =
                    post("/api/pruefung/code") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody("token=$token")
                    }
                form.status shouldBe HttpStatusCode.Accepted
                val big = postJson("/api/pruefung/code", """{"token":"$token","pad":"${"x".repeat(3000)}"}""")
                big.status shouldBe HttpStatusCode.Accepted
                val badSession = postJson("/api/pruefung/session", """{"token":"$token"}""")
                badSession.status shouldBe HttpStatusCode.Unauthorized
                sent.size shouldBe 0
                // the page's own origin works
                postJson("/api/pruefung/code", """{"token":"$token"}""", HttpHeaders.Origin to ownOrigin()).status shouldBe
                    HttpStatusCode.Accepted
                sent.size shouldBe 1
            }
        }
    })

/** A fixed clock for the one scenario that needs "later". */
private class FixedClock(
    private val at: kotlin.time.Instant,
) : Clock {
    override fun now(): kotlin.time.Instant = at
}
