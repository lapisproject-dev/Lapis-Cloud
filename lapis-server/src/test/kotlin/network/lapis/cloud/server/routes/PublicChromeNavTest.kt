package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.body
import kotlinx.html.stream.createHTML
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation" -- pure rendering tests for [PublicChrome.renderChrome]'s
 * icon-only nav (NO `testApplication`, NO DB), mirroring [SocialPublicHtmlTest]'s own house style.
 */
class PublicChromeNavTest :
    FunSpec({
        val baseUrl = "https://cloud.lapisproject.dev"
        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)

        fun renderNav(
            lang: PublicLanguage = PublicLanguage.DE,
            nav: PublicNavAvailability,
            active: PublicChrome.NavTarget? = null,
        ): String =
            createHTML(prettyPrint = false).body {
                with(PublicChrome) {
                    renderChrome(lang = lang, active = active, baseUrl = baseUrl, branding = branding, currentPath = "/", nav = nav)
                }
            }

        fun navSection(html: String): String {
            val start = html.indexOf("<nav class=\"chrome-nav\"")
            val end = html.indexOf("</nav>", start) + "</nav>".length
            return html.substring(start, end)
        }

        // ── T1: labeling ─────────────────────────────────────────────────────────
        test("T1: every link has an aria-label equal to its strings entry, no text node, no title attribute") {
            val strings = PublicChrome.stringsFor(PublicLanguage.DE)
            val nav = navSection(renderNav(nav = PublicNavAvailability(articles = true, events = true)))
            nav shouldContain "aria-label=\"${strings.navMain}\""
            nav shouldContain "aria-label=\"${strings.navHome}\""
            nav shouldContain "aria-label=\"${strings.navTransparency}\""
            nav shouldContain "aria-label=\"${strings.navSocial}\""
            nav shouldContain "aria-label=\"${strings.navArticles}\""
            nav shouldContain "aria-label=\"${strings.navEvents}\""
            nav shouldNotContain "title="
            // No <a ...>text</a> -- every anchor is immediately self-closed of content (kotlinx.html
            // still emits an explicit closing tag with nothing between).
            Regex("""<a [^>]*>[^<]+</a>""").containsMatchIn(nav) shouldBe false
        }

        test("T1: <nav> itself carries aria-label = navMain") {
            val strings = PublicChrome.stringsFor(PublicLanguage.DE)
            val nav = navSection(renderNav(nav = PublicNavAvailability.NONE))
            nav shouldContain "<nav class=\"chrome-nav\" aria-label=\"${strings.navMain}\">"
        }

        // ── T2: order and condition ──────────────────────────────────────────────
        test("T2: PublicNavAvailability.NONE renders exactly three links, no /aktuelles or /veranstaltungen href") {
            val nav = navSection(renderNav(nav = PublicNavAvailability.NONE))
            nav shouldNotContain "/aktuelles"
            nav shouldNotContain "/veranstaltungen"
            Regex("<a ").findAll(nav).count() shouldBe 3
        }

        test("T2: (true, true) renders all five links in the fixed order /, /transparenz, /s, /aktuelles, /veranstaltungen") {
            val nav = navSection(renderNav(nav = PublicNavAvailability(articles = true, events = true)))
            val hrefOrder = Regex("""href="([^"]*)"""").findAll(nav).map { it.groupValues[1] }.toList()
            hrefOrder shouldBe
                listOf(
                    baseUrl,
                    "$baseUrl/transparenz",
                    "$baseUrl/s",
                    "$baseUrl/aktuelles",
                    "$baseUrl/veranstaltungen",
                )
        }

        test("T2: (true, false) renders /aktuelles but not /veranstaltungen") {
            val nav = navSection(renderNav(nav = PublicNavAvailability(articles = true, events = false)))
            nav shouldContain "/aktuelles"
            nav shouldNotContain "/veranstaltungen"
        }

        test("T2: (false, true) renders /veranstaltungen but not /aktuelles") {
            val nav = navSection(renderNav(nav = PublicNavAvailability(articles = false, events = true)))
            nav shouldContain "/veranstaltungen"
            nav shouldNotContain "/aktuelles"
        }

        test(
            "T2: with lang = EN, every link -- fixed (home/transparenz/social) AND optional " +
                "(aktuelles/veranstaltungen) -- carries ?lang=en (review fix: unifies the previously " +
                "inconsistent behaviour where only the two optional tabs kept the language on navigation)",
        ) {
            val nav = navSection(renderNav(lang = PublicLanguage.EN, nav = PublicNavAvailability(articles = true, events = true)))
            nav shouldContain "$baseUrl?lang=en"
            nav shouldContain "$baseUrl/transparenz?lang=en"
            nav shouldContain "$baseUrl/s?lang=en"
            nav shouldContain "$baseUrl/aktuelles?lang=en"
            nav shouldContain "$baseUrl/veranstaltungen?lang=en"
        }

        test("T2: with lang = DE (the default/unparameterized language), no nav link carries ?lang=") {
            val nav = navSection(renderNav(lang = PublicLanguage.DE, nav = PublicNavAvailability(articles = true, events = true)))
            nav shouldNotContain "?lang="
        }

        test("active tab carries aria-current=\"page\", others do not") {
            val nav =
                navSection(renderNav(nav = PublicNavAvailability(articles = true, events = true), active = PublicChrome.NavTarget.ARTICLES))
            Regex("""<a href="[^"]*aktuelles[^"]*"[^>]*aria-current="page"""").containsMatchIn(nav) shouldBe true
            Regex("""<a href="$baseUrl"[^>]*aria-current="page"""").containsMatchIn(nav) shouldBe false
        }

        // ── T5: byte-identical consistency across every route family's chrome call ──
        test("T5: the same nav renders a byte-identical <nav> across PublicLandingHtml, SocialPublicHtml, and PublicTransparencyHtml") {
            val nav = PublicNavAvailability(articles = true, events = true)
            val landing =
                navSection(
                    PublicLandingHtml.page(
                        view = PublicLandingView(stats = null, topPosts = emptyList()),
                        baseUrl = baseUrl,
                        branding = branding,
                        nav = nav,
                    ),
                )
            val social =
                navSection(
                    SocialPublicHtml.timelinePage(
                        view = PublicTimelineView(posts = emptyList(), page = 1, hasNext = false),
                        baseUrl = baseUrl,
                        branding = branding,
                        nav = nav,
                    ),
                )
            val transparency =
                navSection(
                    PublicTransparencyHtml.page(
                        view =
                            PublicTransparencyView(
                                stats =
                                    PublicTransparencyStats(
                                        activeMemberCount = 1L,
                                        mintedLtrTotal = "0.00",
                                        mintedLtrPositive = false,
                                        publicPostCount = 0L,
                                    ),
                                board = emptyList(),
                                topPosts = emptyList(),
                                ltr = null,
                                donations = null,
                                donationYear = 2026,
                            ),
                        baseUrl = baseUrl,
                        branding = branding,
                        nav = nav,
                    ),
                )

            // Every one of these was rendered with active = a DIFFERENT NavTarget (HOME/SOCIAL/
            // TRANSPARENCY) -- strip aria-current before comparing, only the SET of links/hrefs/labels
            // is asserted to be identical, not which one is "active".
            fun stripActive(html: String) = html.replace(""" aria-current="page"""", "")
            stripActive(landing) shouldBe stripActive(social)
            stripActive(social) shouldBe stripActive(transparency)
        }

        // ── T8: stylesheet ────────────────────────────────────────────────────────
        test("T8: the stylesheet no longer uses :nth-child for nav icons, and has the new class-based icons + tooltip") {
            SocialPublicHtml.STYLESHEET shouldNotContain ":nth-child"
            SocialPublicHtml.STYLESHEET shouldContain ".nav-articles::before"
            SocialPublicHtml.STYLESHEET shouldContain ".nav-events::before"
            SocialPublicHtml.STYLESHEET shouldContain ".chrome-nav a:focus-visible::after"
            SocialPublicHtml.STYLESHEET shouldContain "content: attr(aria-label)"
            SocialPublicHtml.STYLESHEET shouldNotContain "pointer-events: none"
        }

        // ── T9: tooltip direction (review fix) ───────────────────────────────────
        test(
            "T9: the tooltip opens DOWNWARDS, not upwards -- `<header class=\"chrome\">` sits at the " +
                "very top of the document, so an upward-opening bubble (`bottom: calc(100% + ...)`) " +
                "gets clipped by the viewport above the ~30rem breakpoint where `.chrome-nav` shares " +
                "the header's first row instead of wrapping to its own",
        ) {
            val afterRule =
                requireNotNull(Regex("""\.chrome-nav a::after \{[^}]*}""").find(SocialPublicHtml.STYLESHEET)) {
                    "`.chrome-nav a::after` rule not found in STYLESHEET"
                }.value
            afterRule shouldContain "top: calc(100% + 0.3rem)"
            afterRule shouldNotContain "bottom: calc(100% + 0.3rem)"
        }
    })
