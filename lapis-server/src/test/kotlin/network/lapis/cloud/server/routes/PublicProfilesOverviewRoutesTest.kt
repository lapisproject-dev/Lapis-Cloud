package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.chapters.ChapterCrestFormat
import network.lapis.cloud.server.chapters.ChapterCrestStorage
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.rpc.PublicRankingConsentStore
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PublicRankingKind
import kotlin.time.Duration.Companion.minutes

private const val BASE = "http://localhost:8080"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- route-level tests for `GET /vorstand`, `/politiker` and
 * `/landesverbaende`: who is visible (and who is not), that a withdrawal shows on the very next
 * request (no body memo, `no-store`), escaping of every user text, the CSP `img-src 'self'`, the
 * `noindex` of the two person pages, language switching and the canonical-URL guard.
 */
class PublicProfilesOverviewRoutesTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()
        val photoStorage = MemberPhotoStorage(MemberPhotoFixtures.freshRoot("public-profiles-overview"))

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup(photoStorage = photoStorage) }
        beforeTest { fixtures.neutralizeForeignProfiles() }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            limiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)
                        val nav = PublicNavAvailabilityProvider()
                        registerPublicBoardOverviewRoutes(readRateLimiter = limiter, branding = branding, navAvailability = nav)
                        registerPublicPoliticiansOverviewRoutes(readRateLimiter = limiter, branding = branding, navAvailability = nav)
                        registerPublicChaptersOverviewRoutes(readRateLimiter = limiter, branding = branding, navAvailability = nav)
                    }
                }
                block()
            }
        }

        // ── /vorstand ─────────────────────────────────────────────────────────────

        test(
            "/vorstand: a board member appears with name and translated role; headers are cache-free, CSP allows same-origin images, noindex",
        ) {
            val member = fixtures.newMember(displayName = "Vera Vorsitz")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            testApp {
                val response = client.get("/vorstand")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "Vera Vorsitz"
                body shouldContain "Vorsitz"
                body shouldContain "<h1>Vorstand</h1>"
                body shouldContain "name=\"robots\" content=\"noindex,follow\""
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                (response.headers["Content-Security-Policy"] ?: "") shouldContain "img-src 'self'"
                // The chrome marks the page as the active tab and renders the new tab at all.
                body shouldContain "class=\"nav-board\""
                body shouldContain "aria-current=\"page\""
            }
        }

        test("/vorstand: a person without a photo gets initials, never a broken image; nothing but name and role is rendered") {
            val member = fixtures.newMember(displayName = "Anna-Lena Müller")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.SECRETARY)
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                body shouldContain "avatar avatar-initials"
                body shouldContain ">AM<"
                body shouldNotContain "/public/member-photos/"
                body shouldNotContain "class=\"bio\""
                body shouldNotContain member.toString()
                body shouldNotContain "public-profiles-$member@example.org"
            }
        }

        test(
            "/vorstand: a PUBLIC photo shows as an absolute, pattern-checked token URL with an empty alt and lazy loading; a PRIVATE one does not",
        ) {
            val shown = fixtures.newMember(displayName = "Foto Freigegeben")
            val hidden = fixtures.newMember(displayName = "Foto Privat")
            fixtures.addBoardSeat(memberId = shown, role = CommitteeRole.CHAIR)
            fixtures.addBoardSeat(memberId = hidden, role = CommitteeRole.DEPUTY_CHAIR)
            val token = fixtures.seedPhoto(storage = photoStorage, memberId = shown, publish = true)!!
            fixtures.seedPhoto(storage = photoStorage, memberId = hidden, publish = false)
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                body shouldContain "<img alt=\"\" src=\"$BASE/public/member-photos/$token\" class=\"avatar\""
                body shouldContain "loading=\"lazy\""
                // Exactly one photo link on the page: the private photo produced none.
                Regex("/public/member-photos/").findAll(body).count() shouldBe 1
            }
        }

        test("/vorstand: a bio appears ONLY while its consent is effective; withdrawing shows on the very next request") {
            val member = fixtures.newMember(displayName = "Bio Bernd")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            fixtures.seedBio(memberId = member, text = "Ich engagiere mich seit 2010.\nZweite Zeile.", publish = true)
            testApp {
                val with = client.get("/vorstand").bodyAsText()
                with shouldContain "class=\"bio\""
                with shouldContain "Ich engagiere mich seit 2010.\nZweite Zeile."
                with shouldContain "lang=\"de\""

                network.lapis.cloud.server.memberbio.MemberPublicBioStore.let { store ->
                    org.jetbrains.exposed.v1.jdbc.transactions
                        .transaction { store.unpublish(member) }
                }
                val without = client.get("/vorstand").bodyAsText()
                without shouldNotContain "Ich engagiere mich seit 2010."
                without shouldNotContain "class=\"bio\""
                without shouldContain "Bio Bernd"
            }
        }

        test("/vorstand: a consent under an OLD wording hides the bio (stored, never shown)") {
            val member = fixtures.newMember(displayName = "Alt Alfred")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            fixtures.seedBio(memberId = member, text = "Alter Einwilligungstext", publish = true, consentVersion = "member-bio-public-v0")
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                body shouldContain "Alt Alfred"
                body shouldNotContain "Alter Einwilligungstext"
            }
        }

        test("/vorstand: a member who is no longer ACTIVE, a closed seat and an inactive committee never appear") {
            val withdrawn = fixtures.newMember(displayName = "Ausgetreten Anton")
            fixtures.addBoardSeat(memberId = withdrawn, role = CommitteeRole.CHAIR)
            fixtures.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
            val ended = fixtures.newMember(displayName = "Beendet Berta")
            fixtures.addBoardSeat(memberId = ended, role = CommitteeRole.CHAIR, until = kotlinx.datetime.LocalDate(2021, 1, 1))
            val inactive = fixtures.newMember(displayName = "Inaktiv Ida")
            fixtures.addBoardSeat(memberId = inactive, role = CommitteeRole.CHAIR, committeeActive = false)
            val visible = fixtures.newMember(displayName = "Sichtbar Sigrid")
            fixtures.addBoardSeat(memberId = visible, role = CommitteeRole.CHAIR)
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                body shouldContain "Sichtbar Sigrid"
                body shouldNotContain "Ausgetreten Anton"
                body shouldNotContain "Beendet Berta"
                body shouldNotContain "Inaktiv Ida"
            }
        }

        test("/vorstand: user text is escaped -- markup in the name and the bio never becomes markup") {
            val member = fixtures.newMember(displayName = "<script>alert(1)</script> {{7*7}}")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            fixtures.seedBio(memberId = member, text = "\"><img src=x onerror=alert(1)> & <b>fett</b>", publish = true)
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                body shouldNotContain "<script>alert(1)</script>"
                body shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;"
                body shouldNotContain "<img src=x onerror"
                body shouldContain "&quot;&gt;&lt;img src=x onerror=alert(1)&gt; &amp; &lt;b&gt;fett&lt;/b&gt;"
                body shouldContain "{{7*7}}"
            }
        }

        test("/vorstand: two roles of one person are two cards, ordered by role rank; the list is capped at 30") {
            val member = fixtures.newMember(displayName = "Doppel Dora")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.MEMBER)
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                Regex("Doppel Dora").findAll(body).count() shouldBe 2
                (body.indexOf("Vorsitz") < body.indexOf("Mitglied")) shouldBe true
            }
        }

        test("/vorstand: at most BOARD_MAX cards are rendered") {
            repeat(PublicProfilesLimits.BOARD_MAX + 2) { index ->
                val member = fixtures.newMember(displayName = "Kappe $index")
                fixtures.addBoardSeat(memberId = member, role = CommitteeRole.ASSESSOR)
            }
            testApp {
                val body = client.get("/vorstand").bodyAsText()
                Regex("class=\"person-card\"").findAll(body).count() shouldBe PublicProfilesLimits.BOARD_MAX
            }
        }

        test("/vorstand: an empty board is a 200 with the empty state, never a 404") {
            testApp {
                val response = client.get("/vorstand")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Derzeit kein besetzter Vorstand."
            }
        }

        test("/vorstand?lang=en: chrome, heading and role are translated, the user text keeps lang=de, tab links carry ?lang=en") {
            val member = fixtures.newMember(displayName = "Eva English")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            testApp {
                val body = client.get("/vorstand?lang=en").bodyAsText()
                body shouldContain "<h1>Board</h1>"
                body shouldContain ">Chair<"
                body shouldContain "href=\"$BASE/vorstand?lang=en\""
                body shouldContain "<h2 class=\"person-name\" lang=\"de\">Eva English</h2>"
            }
        }

        test("an unknown query parameter or a non-canonical lang is redirected to the canonical URL") {
            testApp {
                val client2 = createClient { followRedirects = false }
                val extra = client2.get("/vorstand?utm=1")
                extra.status shouldBe HttpStatusCode.PermanentRedirect
                extra.headers[HttpHeaders.Location] shouldBe "$BASE/vorstand"
                val bad = client2.get("/politiker?lang=xx")
                bad.status shouldBe HttpStatusCode.PermanentRedirect
                bad.headers[HttpHeaders.Location] shouldBe "$BASE/politiker"
            }
        }

        test("the per-page rate limit answers 429") {
            testApp(limiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.get("/vorstand").status shouldBe HttpStatusCode.OK
                client.get("/vorstand").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        // ── /politiker ────────────────────────────────────────────────────────────

        test("/politiker: only an ACTIVE politician with an effective consent is listed, with name and office -- nothing else") {
            val listed = fixtures.newMember(displayName = "Paula Politikerin")
            fixtures.makePolitician(memberId = listed, mandateText = "Abgeordnete des Landtags")
            fixtures.grantConsent(memberId = listed)
            val noConsent = fixtures.newMember(displayName = "Ohne Zustimmung")
            fixtures.makePolitician(memberId = noConsent)
            val stale = fixtures.newMember(displayName = "Alte Zustimmung")
            fixtures.makePolitician(memberId = stale)
            fixtures.grantConsent(memberId = stale, stale = true)
            val former = fixtures.newMember(displayName = "Ehemaliger Emil")
            fixtures.makePolitician(memberId = former, status = PoliticianProfileStatus.FORMER)
            fixtures.grantConsent(memberId = former)
            val withdrawn = fixtures.newMember(displayName = "Ausgetretene Agnes")
            fixtures.makePolitician(memberId = withdrawn)
            fixtures.grantConsent(memberId = withdrawn)
            fixtures.setStatus(memberId = withdrawn, status = MemberStatus.WITHDRAWN)
            testApp {
                val response = client.get("/politiker")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "Paula Politikerin"
                body shouldContain "Abgeordnete des Landtags"
                body shouldContain "<h1>Politiker</h1>"
                body shouldContain "name=\"robots\" content=\"noindex,follow\""
                for (hidden in listOf("Ohne Zustimmung", "Alte Zustimmung", "Ehemaliger Emil", "Ausgetretene Agnes")) {
                    body shouldNotContain
                        hidden
                }
                // No ranking, no trust figure, no id or e-mail.
                body shouldNotContain listed.toString()
                body shouldNotContain "Vertrauen"
                body shouldNotContain "trust"
            }
        }

        test("/politiker: sorted with a German collator -- an umlaut sorts with its base letter, not after Z") {
            val names = listOf("Zora Zander", "Ägidius Ahorn", "Bruno Berg")
            names.forEach { name ->
                val member = fixtures.newMember(displayName = name)
                fixtures.makePolitician(memberId = member)
                fixtures.grantConsent(memberId = member)
            }
            testApp {
                val body = client.get("/politiker").bodyAsText()
                val positions = names.associateWith { body.indexOf(it) }
                (positions.getValue("Ägidius Ahorn") < positions.getValue("Bruno Berg")) shouldBe true
                (positions.getValue("Bruno Berg") < positions.getValue("Zora Zander")) shouldBe true
            }
        }

        test("/politiker: a photo and a bio appear only with their own consents, and a revoked listing removes the whole card at once") {
            val member = fixtures.newMember(displayName = "Komplett Karl")
            fixtures.makePolitician(memberId = member)
            fixtures.grantConsent(memberId = member)
            fixtures.seedBio(memberId = member, text = "Politiker-Kurztext", publish = true)
            val token = fixtures.seedPhoto(storage = photoStorage, memberId = member, publish = true)!!
            testApp {
                val body = client.get("/politiker").bodyAsText()
                body shouldContain "Politiker-Kurztext"
                body shouldContain "/public/member-photos/$token"

                org.jetbrains.exposed.v1.jdbc.transactions.transaction {
                    PublicRankingConsentStore.revoke(
                        memberId = member,
                        kind = PublicRankingKind.POLITICIAN_LISTING,
                        now =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime(),
                    )
                }
                val after = client.get("/politiker").bodyAsText()
                after shouldNotContain "Komplett Karl"
                after shouldNotContain "Politiker-Kurztext"
                after shouldNotContain "/public/member-photos/$token"
                after shouldContain "Derzeit sind keine Politiker gelistet."
            }
        }

        test("/politiker: the office text is one line, at most 200 code points, escaped") {
            val member = fixtures.newMember(displayName = "Lang Lena")
            fixtures.makePolitician(memberId = member, mandateText = "<b>Amt</b>\n\n   mit   Umbruch " + "y".repeat(500))
            fixtures.grantConsent(memberId = member)
            testApp {
                val body = client.get("/politiker").bodyAsText()
                body shouldContain "&lt;b&gt;Amt&lt;/b&gt; mit Umbruch y"
                body shouldNotContain "y".repeat(201)
            }
        }

        test("/politiker: an empty list is a 200 with the empty state") {
            testApp {
                val response = client.get("/politiker")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain "Derzeit sind keine Politiker gelistet."
            }
        }

        // ── /landesverbaende ──────────────────────────────────────────────────────

        test("/landesverbaende: a chapter shows its name, description and crest; one without a crest shows an aria-hidden placeholder") {
            val withCrest = fixtures.newChapter(name = "Landesverband Nord", description = "Zustaendig fuer den Norden.")
            val token =
                fixtures.seedCrest(
                    storage = ChapterCrestStorage(MemberPhotoFixtures.freshRoot("crest-page")),
                    chapterId = withCrest,
                )
            fixtures.newChapter(name = "Landesverband Sued")
            testApp {
                val response = client.get("/landesverbaende")
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain "<h1>Landesverbände</h1>"
                body shouldContain "Landesverband Nord"
                body shouldContain "Zustaendig fuer den Norden."
                body shouldContain "<img alt=\"Wappen Landesverband Nord\" src=\"$BASE/public/chapter-crests/$token\" class=\"crest-image\""
                body shouldContain "crest-placeholder"
                body shouldContain "aria-hidden=\"true\""
                // Chapters carry no personal data: indexable, unlike the two person pages.
                body shouldContain "name=\"robots\" content=\"index,follow\""
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
            }
        }

        test(
            "/landesverbaende: an SVG crest is referenced ONLY through an img tag with the token URL (no object/embed/iframe/inline svg)",
        ) {
            val id = fixtures.newChapter(name = "Landesverband Vektor")
            val token =
                fixtures.seedCrest(
                    storage = ChapterCrestStorage(MemberPhotoFixtures.freshRoot("crest-svg-page")),
                    chapterId = id,
                    format = ChapterCrestFormat.SVG,
                )
            testApp {
                val body = client.get("/landesverbaende").bodyAsText()
                body shouldContain
                    "<img alt=\"Wappen Landesverband Vektor\" src=\"$BASE/public/chapter-crests/$token\" class=\"crest-image\""
                val at = body.indexOf("Wappen Landesverband Vektor")
                val tile = body.substring(body.lastIndexOf("<li", at), body.indexOf("</li>", at))
                for (forbidden in listOf("<object", "<embed", "<iframe", "<svg")) tile shouldNotContain forbidden
            }
        }

        test("/landesverbaende: name and description are escaped, also inside the crest alt text") {
            val id = fixtures.newChapter(name = "LV <b>x</b> \"q\"", description = "<script>1</script>")
            fixtures.seedCrest(
                storage = ChapterCrestStorage(MemberPhotoFixtures.freshRoot("crest-escape")),
                chapterId = id,
            )
            testApp {
                val body = client.get("/landesverbaende").bodyAsText()
                body shouldNotContain "<b>x</b>"
                body shouldNotContain "<script>1</script>"
                body shouldContain "alt=\"Wappen LV &lt;b&gt;x&lt;/b&gt; &quot;q&quot;\""
            }
        }

        test("the empty chapters page renders the empty state (pure render, independent of other specs' chapters)") {
            val html =
                PublicProfilesHtml.chaptersPage(
                    cards = emptyList(),
                    baseUrl = BASE,
                    branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                    lang = PublicLanguage.DE,
                    nav = PublicNavAvailability.NONE,
                )
            html shouldContain "Noch keine Landesverbände angelegt."
            html shouldNotContain "person-grid"
        }

        test("a corrupt token in the data never becomes a link (only pattern-matching tokens are rendered)") {
            val html =
                PublicProfilesHtml.chaptersPage(
                    cards = listOf(PublicChapterCard(name = "X", crestToken = "\"><script>", description = null)),
                    baseUrl = BASE,
                    branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
                    lang = PublicLanguage.DE,
                    nav = PublicNavAvailability.NONE,
                )
            html shouldNotContain "chapter-crests"
            html shouldNotContain "<script>"
            html shouldContain "crest-placeholder"
        }
    })
