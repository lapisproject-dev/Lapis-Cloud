package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.shared.domain.CommitteeRole
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes

private const val BASE = "http://localhost:8080"

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the three new, OPTIONAL chrome tabs: their availability is
 * computed by the SAME loaders the pages and feeds use (one definition of "visible"), they are appended
 * AFTER the existing tabs and bound to CSS classes (never an index), error pages render none of them,
 * `/transparenz` links to `/vorstand` only when that page would show something, and the stylesheet
 * carries the icons plus the touch-device label fix.
 */
class PublicProfilesNavTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()
        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }
        beforeTest { fixtures.neutralizeForeignProfiles() }

        test("availability agrees with the page loaders for board, politicians and chapters (T3c for the new tabs)") {
            fun checkAgreement() {
                val nav = loadPublicNavAvailability()
                nav.board shouldBe transaction { PublicProfilesReader.loadBoardCards(limit = 1) }.isNotEmpty()
                nav.politicians shouldBe transaction { PublicProfilesReader.loadPoliticianCards(limit = 1) }.isNotEmpty()
                nav.chapters shouldBe transaction { PublicProfilesReader.loadChapterCards(limit = 1) }.isNotEmpty()
            }
            checkAgreement()
            loadPublicNavAvailability().board shouldBe false
            loadPublicNavAvailability().politicians shouldBe false

            val board = fixtures.newMember()
            fixtures.addBoardSeat(memberId = board, role = CommitteeRole.CHAIR)
            checkAgreement()
            loadPublicNavAvailability().board shouldBe true

            val politician = fixtures.newMember()
            fixtures.makePolitician(memberId = politician)
            loadPublicNavAvailability().politicians shouldBe false // a profile WITHOUT a listing consent is not visible
            fixtures.grantConsent(memberId = politician)
            checkAgreement()
            loadPublicNavAvailability().politicians shouldBe true

            fixtures.newChapter()
            checkAgreement()
            loadPublicNavAvailability().chapters shouldBe true
        }

        test("a board tab can only appear for a member who is ACTIVE on an open seat of an ACTIVE executive board") {
            val closed = fixtures.newMember()
            fixtures.addBoardSeat(memberId = closed, role = CommitteeRole.CHAIR, until = kotlinx.datetime.LocalDate(2021, 1, 1))
            val inactive = fixtures.newMember()
            fixtures.addBoardSeat(memberId = inactive, role = CommitteeRole.CHAIR, committeeActive = false)
            loadPublicNavAvailability().board shouldBe false
        }

        test("the tabs are appended after the existing ones in a fixed order and bound to CSS classes; absent ones leave no hole") {
            val all = PublicNavAvailability(articles = true, events = true, board = true, politicians = true, chapters = true)
            val html =
                PublicProfilesHtml.boardPage(
                    cards = emptyList(),
                    baseUrl = BASE,
                    branding = branding,
                    lang = PublicLanguage.DE,
                    nav = all,
                )
            val order =
                listOf(
                    "nav-home",
                    "nav-transparency",
                    "nav-social",
                    "nav-articles",
                    "nav-events",
                    "nav-board",
                    "nav-politicians",
                    "nav-chapters",
                ).map { html.indexOf("class=\"$it\"") }
            order.forEach { (it >= 0) shouldBe true }
            order shouldBe order.sorted()

            val onlyChapters =
                PublicProfilesHtml.boardPage(
                    cards = emptyList(),
                    baseUrl = BASE,
                    branding = branding,
                    lang = PublicLanguage.DE,
                    nav = PublicNavAvailability(articles = false, events = false, board = false, politicians = false, chapters = true),
                )
            onlyChapters shouldContain "class=\"nav-chapters\""
            onlyChapters shouldNotContain "class=\"nav-board\""
            onlyChapters shouldNotContain "class=\"nav-politicians\""
        }

        test("the tab labels are translated and carry ?lang= for every language; the active tab is marked") {
            val all = PublicNavAvailability(articles = false, events = false, board = true, politicians = true, chapters = true)
            val en =
                PublicProfilesHtml.politiciansPage(
                    cards = emptyList(),
                    baseUrl = BASE,
                    branding = branding,
                    lang = PublicLanguage.EN,
                    nav = all,
                )
            en shouldContain "aria-label=\"Board\""
            en shouldContain "aria-label=\"Politicians\""
            en shouldContain "aria-label=\"Regional chapters\""
            en shouldContain "href=\"$BASE/landesverbaende?lang=en\""
            en shouldContain "class=\"nav-politicians\" aria-label=\"Politicians\" aria-current=\"page\""
            for (lang in PublicLanguage.entries) {
                val html = PublicProfilesHtml.chaptersPage(cards = emptyList(), baseUrl = BASE, branding = branding, lang = lang, nav = all)
                val strings = PublicChrome.stringsFor(lang)
                html shouldContain "aria-label=\"${strings.navChapters}\""
            }
        }

        test("an error page (PublicNavAvailability.NONE) renders none of the new tabs and never touches the database") {
            val html = SocialPublicHtml.notFoundPage(baseUrl = BASE, branding = branding, lang = PublicLanguage.DE)
            for (cssClass in listOf("nav-board", "nav-politicians", "nav-chapters", "nav-articles", "nav-events")) {
                html shouldNotContain "class=\"$cssClass\""
            }
            PublicNavAvailability.NONE.let { it.board || it.politicians || it.chapters } shouldBe false
        }

        test("the stylesheet carries the three nav icons (masks), the avatar/crest classes and the touch fix for narrow screens") {
            val css = SocialPublicHtml.STYLESHEET
            for (icon in listOf(".nav-board::before", ".nav-politicians::before", ".nav-chapters::before")) {
                val block = css.substringAfter(icon).substringBefore("}")
                block shouldContain "-webkit-mask-image: url(\"data:image/svg+xml,"
                block shouldContain "mask-image: url(\"data:image/svg+xml,"
            }
            for (cssClass in listOf(
                ".person-grid",
                ".person-card",
                ".avatar-initials",
                ".avatar-silhouette",
                ".crest-tile",
                ".crest-placeholder",
            )) {
                css shouldContain cssClass
            }
            // The touch fix: at <= 30rem the nav is a four-column grid and the label (the ::after) is always visible.
            val narrow = css.substringAfter("@media (max-width: 30rem) {")
            narrow shouldContain "grid-template-columns: repeat(4, minmax(0, 1fr))"
            narrow shouldContain "opacity: 1; visibility: visible"
            narrow shouldContain "-webkit-line-clamp: 2"
            // Both ends of the desktop tooltip rule are neutralized so the labels are not positioned off-screen.
            narrow shouldContain ".chrome-nav a:first-child::after"
            narrow shouldContain ".chrome-nav a:last-child::after"
        }

        test("/transparenz links to /vorstand only when that page would show a board; the link carries ?lang= for another language") {
            suspend fun fetch(path: String): String {
                var body = ""
                testApplication {
                    application {
                        install(XForwardedHeaders) { useLastProxy() }
                        install(AutoHeadResponse)
                        routing {
                            registerPublicTransparencyRoutes(
                                readRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                                branding = branding,
                                navAvailability = PublicNavAvailabilityProvider(),
                            )
                        }
                    }
                    body = client.get(path).bodyAsText()
                }
                return body
            }
            // The transparency page lists every current board member (Vorstand section) -- neutralized: none.
            fetch("/transparenz") shouldNotContain "Vorstand vorgestellt"

            val member = fixtures.newMember(displayName = "Link Lotte")
            fixtures.addBoardSeat(memberId = member, role = CommitteeRole.CHAIR)
            val de = fetch("/transparenz")
            de shouldContain "Vorstand vorgestellt"
            de shouldContain "href=\"$BASE/vorstand\""
            val en = fetch("/transparenz?lang=en")
            en shouldContain "Meet the board"
            en shouldContain "href=\"$BASE/vorstand?lang=en\""
        }

        test(
            "the /transparenz board section itself is unchanged: same members, same order, same labels after the shared-condition refactor",
        ) {
            val chair = fixtures.newMember(displayName = "Erste Vorsitz")
            val assessor = fixtures.newMember(displayName = "Zweite Beisitz")
            fixtures.addBoardSeat(memberId = assessor, role = CommitteeRole.ASSESSOR)
            fixtures.addBoardSeat(memberId = chair, role = CommitteeRole.CHAIR)
            val board = transaction { PublicTransparencyReader.loadBoard() }
            board.map { it.displayName } shouldBe listOf("Erste Vorsitz", "Zweite Beisitz")
            board.map { it.role } shouldBe listOf(CommitteeRole.CHAIR, CommitteeRole.ASSESSOR)
            // The new /vorstand reader selects exactly the same people.
            transaction { PublicProfilesReader.loadBoardCards() }.map { it.card.name } shouldBe board.map { it.displayName }
        }
    })
