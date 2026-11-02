package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OidcGuestLoginEventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerDisplayEpoch
import network.lapis.cloud.server.partner.PartnerLoginChoice
import network.lapis.cloud.server.partner.PartnerLoginChoices
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.addDirectPartner
import network.lapis.cloud.server.partner.wipePartnerTables
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File

private const val LOGIN = "/federation/oidc/rp/login"

/** V1.9.258 -- the partner choice on the guest sign-in page: rendering, search, POST rules, and the free-text path staying as it was. */
class OidcRpLoginPartnerChoiceTest :
    FunSpec({
        fun reset() {
            wipePartnerTables()
            transaction {
                OrganizationSettingsTable.update { it[partnerListHidden] = false }
                OidcGuestLoginEventTable.deleteAll()
            }
            PartnerLoginChoices.resetMemoForTests()
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { reset() }
        afterSpec { reset() }

        // The golden file was rendered with baseUrl http://localhost; the wired test module serves http://localhost:8080.
        fun golden() = File("src/test/resources/golden/rp-login-no-partners.html").readText()

        fun String.asGoldenBase() = replace("http://localhost:8080", "http://localhost")

        suspend fun HttpClient.postForm(vararg pairs: Pair<String, String>): HttpResponse =
            post(LOGIN) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(Parameters.build { pairs.forEach { (k, v) -> append(k, v) } }.formUrlEncode())
            }

        fun events() =
            transaction {
                OidcGuestLoginEventTable.selectAll().map { it[OidcGuestLoginEventTable.remoteParty] to it[OidcGuestLoginEventTable.reason] }
            }

        suspend fun getPage(
            query: String = "",
            block: suspend (HttpResponse, String) -> Unit,
        ) {
            testApplication {
                application { module() }
                val response = client.get(LOGIN + query)
                block(response, response.bodyAsText())
            }
        }

        // ---- GET ----

        test("the view without a picker is byte-identical to the golden file taken before V1.9.258") {
            rpLoginFormHtml(branding = TEST_AUTH_BRANDING, baseUrl = "http://localhost") shouldBe golden()
        }

        test("GET without any partner is byte-identical to the former form, also with ?q=") {
            getPage { r, body ->
                r.status shouldBe HttpStatusCode.OK
                body.asGoldenBase() shouldBe golden()
            }
            getPage("?q=x&partner=evil.org") { _, body -> body.asGoldenBase() shouldBe golden() }
        }

        test("GET with partners: search form, select, hint, free-text field not required, Weiter first submit") {
            addDirectPartner(host = "a.beispiel.de", displayName = "Alpha")
            addDirectPartner(host = "b.beispiel.de", displayName = "Beta")
            getPage { _, body ->
                body shouldContain "for=\"partner_q\""
                body shouldContain "for=\"partner_host\""
                body shouldContain "role=\"search\""
                body shouldContain "partner_host_hint"
                body shouldContain "<h2>Partnerserver auswählen</h2>"
                body shouldContain "<h2>Andere Adresse eingeben</h2>"
                body shouldContain "<p>oder</p>"
                body shouldContain "<option value=\"a.beispiel.de\">Alpha (a.beispiel.de)</option>"
                val search = body.indexOf("method=\"get\"")
                val post = body.indexOf("method=\"post\"")
                (search in 0 until post) shouldBe true
                val searchForm = body.substring(search, post)
                searchForm shouldNotContain "home_server"
                val postForm = body.substring(post)
                postForm.substringAfter("name=\"home_server\"").substringBefore(">") shouldNotContain "required"
                val firstSubmit = postForm.indexOf("type=\"submit\"")
                postForm.substring(firstSubmit).startsWith("type=\"submit\" class=\"auth-primary\">Weiter") shouldBe true
            }
        }

        test("search hits on name and host, shows the count, a single hit is preselected with the hidden partner field") {
            addDirectPartner(host = "a.beispiel.de", displayName = "Partei der Vernunft")
            addDirectPartner(host = "b.beispiel.de", displayName = "Anders")
            getPage("?q=vernunft") { _, body ->
                body shouldContain "1 Treffer für „vernunft“"
                body shouldContain "<option value=\"a.beispiel.de\" selected=\"selected\">"
                body shouldNotContain "b.beispiel.de\">"
                body shouldContain "name=\"partner\" value=\"a.beispiel.de\""
            }
            getPage("?q=B.BEISPIEL") { _, body -> body shouldContain "<option value=\"b.beispiel.de\" selected=\"selected\">" }
        }

        test("no hit: message, reset link, no select, free text required again") {
            addDirectPartner(host = "a.beispiel.de")
            getPage("?q=nichts") { _, body ->
                body shouldContain "Keine Treffer für „nichts“"
                body shouldContain "Suche zurücksetzen"
                body shouldNotContain "<select"
                body.substringAfter("name=\"home_server\"").substringBefore(">") shouldContain "required"
            }
        }

        test("q is reflected only escaped and cut to 100 code points") {
            addDirectPartner(host = "a.beispiel.de")
            val xss = java.net.URLEncoder.encode("<script>alert(1)</script>\"'", "UTF-8")
            getPage("?q=$xss") { _, body ->
                body shouldNotContain "<script>alert"
                body shouldContain "&lt;script&gt;"
                body shouldContain "&quot;"
            }
            getPage("?q=" + "a".repeat(5000)) { _, body ->
                val value = body.substringAfter("name=\"q\"").substringAfter("value=\"").substringBefore("\"")
                value.length shouldBe 100
            }
        }

        test("?partner= preselects only a host of the list; an unknown one never appears") {
            addDirectPartner(host = "a.beispiel.de")
            addDirectPartner(host = "b.beispiel.de")
            getPage("?partner=B.Beispiel.DE") { _, body -> body shouldContain "<option value=\"b.beispiel.de\" selected=\"selected\">" }
            getPage("?partner=evil.org") { _, body ->
                body shouldNotContain "evil.org"
                body shouldNotContain "selected"
            }
        }

        test("a hidden partner list: neither select nor search form, byte-identical to the plain form") {
            addDirectPartner(host = "a.beispiel.de")
            transaction { OrganizationSettingsTable.update { it[partnerListHidden] = true } }
            PartnerDisplayEpoch.bump()
            getPage("?q=a") { _, body -> body.asGoldenBase() shouldBe golden() }
        }

        test("hostile names reach the option only cleaned and escaped") {
            addDirectPartner(host = "a.beispiel.de", displayName = "Ev‮il <b>x</b>")
            getPage { _, body ->
                body shouldNotContain "‮"
                body shouldContain "Evil &lt;b&gt;x&lt;/b&gt; (a.beispiel.de)"
            }
        }

        test("the page view caps at 200 and says so") {
            val all = (1..201).map { PartnerLoginChoice(host = "h$it.beispiel.de", name = null) }
            val html =
                rpLoginFormHtml(
                    branding = TEST_AUTH_BRANDING,
                    baseUrl = "http://localhost",
                    picker =
                        RpPartnerPicker(
                            result = PartnerLoginChoices.filter(all = all, query = null),
                            totalAvailable = 201,
                            selectedHost = null,
                        ),
                )
            html shouldContain "Es werden die ersten 200 von 201 Partnerservern angezeigt."
            html.split("<option").size - 1 shouldBe 201 // 200 hosts + the placeholder
        }

        test("headers stay intact with a query") {
            addDirectPartner(host = "a.beispiel.de")
            getPage("?q=a") { r, _ ->
                r.headers["Cache-Control"] shouldBe "no-store"
                r.headers["Referrer-Policy"] shouldBe "same-origin"
                r.headers["Content-Security-Policy"]!! shouldContain "default-src 'none'"
            }
        }

        // ---- POST ----

        test("POST: a choice runs the same path as the free text 'https://host' (same status, audit reason and remote party)") {
            addDirectPartner(host = "wahl.beispiel.de")
            testApplication {
                application { module() }
                val choice = client.postForm("partner_host" to "wahl.beispiel.de")
                val free = client.postForm("home_server" to "https://wahl.beispiel.de")
                choice.status shouldBe free.status
                val rows = events()
                rows.size shouldBe 2
                rows[0] shouldBe rows[1]
                rows[0].first shouldBe "https://wahl.beispiel.de"
            }
        }

        test("POST: a choice that is not listed is refused with the fixed error page, an audit row and no fetch") {
            addDirectPartner(host = "a.beispiel.de")
            testApplication {
                application { module() }
                val r = client.postForm("partner_host" to "evil.beispiel.de")
                r.status shouldBe HttpStatusCode.BadRequest
                val body = r.bodyAsText()
                body shouldContain "Die Heimatserver-Adresse ist ungültig."
                body shouldNotContain "evil.beispiel.de"
                events() shouldBe listOf("evil.beispiel.de" to "PARTNER_CHOICE_NOT_LISTED")
            }
        }

        test("POST: free text wins over a choice; a bad free text is judged on its own") {
            addDirectPartner(host = "a.beispiel.de")
            testApplication {
                application { module() }
                val r = client.postForm("home_server" to "https://localhost", "partner_host" to "a.beispiel.de")
                r.status shouldBe HttpStatusCode.BadGateway
                events().single().first shouldBe "https://localhost"
                val bad = client.postForm("home_server" to "http://x", "partner_host" to "a.beispiel.de")
                bad.status shouldBe HttpStatusCode.BadRequest
                events().last().second shouldBe "INVALID_HOME_SERVER_URL"
            }
        }

        test("POST: empty free text with an empty choice is the old 400 text") {
            testApplication {
                application { module() }
                val r = client.postForm("home_server" to "  ", "partner_host" to "")
                r.status shouldBe HttpStatusCode.BadRequest
                r.bodyAsText() shouldBe "home_server is required"
                client.postForm().bodyAsText() shouldBe "home_server is required"
            }
        }

        test("POST: free text with an empty partner_host behaves like before") {
            testApplication {
                application { module() }
                val r = client.postForm("home_server" to "https://localhost", "partner_host" to "")
                r.status shouldBe HttpStatusCode.BadGateway
            }
        }

        test("POST: a hidden list refuses a choice at once, also inside the memo window") {
            addDirectPartner(host = "a.beispiel.de")
            testApplication {
                application { module() }
                PartnerLoginChoices.current().size shouldBe 1
                transaction { OrganizationSettingsTable.update { it[partnerListHidden] = true } }
                client.postForm("partner_host" to "a.beispiel.de").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("POST: a block refuses a choice at once") {
            addDirectPartner(host = "a.beispiel.de")
            testApplication {
                application { module() }
                PartnerLoginChoices.current().size shouldBe 1
                transaction {
                    PartnerServerStore.addBlock(
                        pattern = PartnerBlocklistPattern.parse("a.beispiel.de")!!,
                        now = kotlinx.datetime.LocalDateTime(2026, 11, 5, 10, 0, 0),
                    )
                }
                PartnerDisplayEpoch.bump()
                client.postForm("partner_host" to "a.beispiel.de").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("POST: cross-origin with partner_host is a 403 before anything else") {
            addDirectPartner(host = "a.beispiel.de")
            testApplication {
                application { module() }
                val r =
                    client.post(LOGIN) {
                        header("Sec-Fetch-Site", "cross-site")
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(Parameters.build { append("partner_host", "a.beispiel.de") }.formUrlEncode())
                    }
                r.status shouldBe HttpStatusCode.Forbidden
                events() shouldBe emptyList()
            }
        }

        test("POST: malformed choices are refused without a crash") {
            addDirectPartner(host = "host.org.beispiel.de")
            testApplication {
                application { module() }
                listOf(
                    "https://host.org.beispiel.de",
                    "host.org.beispiel.de:443",
                    "host.org.beispiel.de.",
                    "x".repeat(10_000),
                    "a\u0000b.de",
                    "localhost",
                ).forEach {
                    client.postForm("partner_host" to it).status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    })
