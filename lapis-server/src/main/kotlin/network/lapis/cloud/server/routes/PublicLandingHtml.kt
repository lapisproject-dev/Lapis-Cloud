package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * Welle V1.4.6 "Öffentliche Startseite" (`GET /`) -- a THIRD unauthenticated, account-less public
 * HTML-rendering surface, sibling to [SocialPublicHtml] (`/s`) and [PublicTransparencyHtml]
 * (`/transparenz`). Same five non-negotiable properties those two files' own class KDoc list, restated
 * here verbatim:
 *
 * 1. **The HTML-escape-bypassing `kotlinx.html` API is never used anywhere in this file** -- every
 *    user/operator-controlled string ([brandTitle], a post's author display name, a post excerpt)
 *    reaches the output exclusively through `kotlinx.html`'s ordinary text-node (`+"..."`) API, which
 *    escapes automatically. Enforced by the same `SocialPublicHtmlTest` T6 source-text scan, extended
 *    to cover this file.
 * 2. **No `href`/`src` built from unconstrained user-controlled data** -- every link here is either
 *    [baseUrl]-relative to a fixed path, or `"$baseUrl/s/{uuid}"` (a UUID, never free text, exactly
 *    [PublicTransparencyHtml]'s own precedent).
 * 3. **[baseUrl] is always [network.lapis.cloud.server.federation.FederationConfig.publicBaseUrl]**,
 *    enforced by the caller ([PublicLandingRoutes]), never derived from the request `Host`.
 * 4. **No request-time-dependent output** beyond the domain data itself -- the ETag/304 mechanism in
 *    [PublicLandingRoutes] depends on two calls with identical input producing a byte-identical body.
 * 5. **Identical output for a crawler and a human** -- no `User-Agent` sniffing.
 *
 * Uses the SAME stylesheet as `/s`/`/transparenz` (`/s/assets/style.css`,
 * [SocialPublicHtml.STYLESHEET]) -- extended with a small hero/CTA block, never a second inline
 * `<style>` block (same CSP `style-src 'self'` reasoning [SocialPublicRoutes]' own KDoc documents).
 *
 * **Datensparsamkeit (the whole point of Option B over exposing the SPA's own dashboard at `/`,
 * Design-Team decision)**: this page shows ONLY aggregate counts ([PublicTransparencyStats]) and the
 * author names of already-`PUBLIC` posts -- the exact same names `/s` itself already indexes. It
 * NEVER shows board member names, LTR-holder names, or donor names, even though those exist on
 * `/transparenz`: that page carries `noindex,follow` precisely because its ranking sections show
 * names under a revocable consent a search engine cannot honor -- lifting any of those names onto an
 * `index,follow` page would defeat that consent model entirely (Zhuo/Jobs review chain, plan § 2).
 *
 * **Hash-Bridge (§ 1.2 of the implementation plan, opt-in via [PublicLandingRoutes]'s own wiring in
 * `Application.kt`)**: an already-in-flight `<base>/#/...` link -- a bookmark, a password-reset/
 * email-verification/Stripe-return mail sent before this wave's deploy, or a website embed snippet
 * ([network.lapis.cloud.client.EmbedIntegrationHttp.buildEmbedSnippet]'s No-JS fallback `<a>`, already
 * copied onto a third-party site before this deploy) -- would otherwise land on THIS page and lose its
 * fragment (the SPA that used to live at `/` now lives at `/app`, see `Application.kt`). A single,
 * external, non-inline `<script src="/s/assets/hash-bridge.js">` (only on THIS page, never on `/s`/
 * `/transparenz`) forwards a same-origin-only, allowlist-validated hash fragment to `/app`. Crawlers
 * are unaffected: they fetch `/` without executing JavaScript and without a `#...` fragment (a
 * fragment never even reaches an HTTP request), so they always see the full server-rendered content
 * below, never a client-side redirect.
 */
internal object PublicLandingHtml {
    /** How many top posts this teaser shows -- see [PublicLandingRoutes.LANDING_TOP_POSTS_LIMIT]. */
    fun page(
        view: PublicLandingView,
        baseUrl: String,
        /**
         * V1.2.5 White-Label-Branding, seit der Sprachumschalter-Welle das volle [ResolvedBranding]
         * statt nur `brandTitle: String` -- Default beibehalten, siehe [SocialPublicHtml.timelinePage]
         * eigene KDoc-Begründung (§ 4.4b).
         */
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        /** Sprachumschalter-Welle -- Default Deutsch. */
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(baseUrl = baseUrl, branding = branding, lang = lang)
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.HOME,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = "/",
                    )
                }
                renderHero(baseUrl = baseUrl, strings = strings)
                main {
                    attributes["id"] = "main"
                    view.stats?.let { renderStats(stats = it, strings = strings) }
                    if (view.topPosts.isNotEmpty()) renderTopPosts(posts = view.topPosts, baseUrl = baseUrl, lang = lang, strings = strings)
                    renderFurtherLinks(baseUrl = baseUrl, lang = lang, strings = strings)
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }
    }

    private fun HTML.renderHead(
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
    ) {
        val strings = PublicChrome.stringsFor(lang)
        val description = "${strings.statMembers}, ${strings.statPosts} · ${branding.title}"
        val canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/", lang = lang)
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +branding.title }
            meta(name = "description", content = description)
            // Bewusst "index,follow" -- der EINZIGE Unterschied zu PublicTransparencyHtml's
            // "noindex,follow" (siehe dessen begleitenden Kommentar dort): diese Seite trägt KEINE
            // widerrufbare, einwilligungsbasierte PII (siehe Klassen-KDoc "Datensparsamkeit"), es
            // gibt also kein Widerrufsversprechen, das ein Crawler/Archivdienst unterlaufen könnte.
            // Das ist die gesamte Rechtfertigung für Option B (server-gerendertes HTML statt der
            // client-seitigen SPA) an dieser Stelle.
            meta(name = "robots", content = "index,follow")
            link(rel = "canonical", href = canonicalUrl)
            link(rel = "stylesheet", href = "/s/assets/style.css")
            with(PublicChrome) { renderHreflangAlternates(baseUrl = baseUrl, currentPath = "/") }
            meta(content = branding.title) { attributes["property"] = "og:title" }
            meta(content = description) { attributes["property"] = "og:description" }
            meta(content = canonicalUrl) { attributes["property"] = "og:url" }
            meta(content = "website") { attributes["property"] = "og:type" }
            meta(content = "Lapis Cloud") { attributes["property"] = "og:site_name" }
            meta(name = "twitter:card", content = "summary")
            // Optionaler Hash-Bridge-Baustein (Plan § 1.2) -- externes, statisches Asset, KEIN
            // Inline-Skript, siehe Klassen-KDoc "Hash-Bridge". Nur auf DIESER Seite eingebunden --
            // PublicLandingRoutes ist der einzige Aufrufer, der applyPublicPageHeaders/
            // respondPublicCacheable mit scriptSrcSelf = true aufruft.
            script(src = "/s/assets/hash-bridge.js") {}
        }
    }

    /**
     * Sprachumschalter-Welle: der Login-Link entfällt hier komplett -- er lebt jetzt ausschließlich
     * im Chrome ([PublicChrome.renderChrome]), sonst gäbe es ihn doppelt auf der Seite. Der
     * Marken-Claim ([PublicUiStrings.tagline]) wird zum `<h1>` des Contents (der Markenname selbst
     * steht bereits im Chrome, siehe Umsetzungsplan § 4.6c) -- "Mitglied werden" bleibt als
     * primärer CTA im Hero.
     */
    private fun FlowContent.renderHero(
        baseUrl: String,
        strings: PublicUiStrings,
    ) {
        section(classes = "hero") {
            // TODO(Nutzer-Freigabe, Plan § 10 Punkt 4): Marken-Claim -- Platzhalter bis zur
            // Freigabe, gehört dem Nutzer, nicht dem Umsetzungsteam.
            h1 { +strings.tagline }
            a(href = "$baseUrl/app#/register", classes = "cta cta-primary") { +strings.register }
        }
    }

    private fun FlowContent.renderStats(
        stats: PublicTransparencyStats,
        strings: PublicUiStrings,
    ) {
        section {
            attributes["id"] = "kennzahlen"
            div(classes = "stats") {
                statTile(value = stats.activeMemberCount.toString(), label = strings.statMembers)
                statTile(value = "${stats.mintedLtrTotal} LTR", label = strings.statLtr)
                statTile(value = stats.publicPostCount.toString(), label = strings.statPosts)
            }
        }
    }

    /** Identisch zu [PublicTransparencyHtml]'s eigener, PRIVATER `statTile` -- absichtlich dupliziert, siehe Klassen-KDoc. */
    private fun FlowContent.statTile(
        value: String,
        label: String,
    ) {
        div(classes = "stat") {
            div(classes = "stat-value") { +value }
            div(classes = "stat-label") { +label }
        }
    }

    private fun FlowContent.renderTopPosts(
        posts: List<PublicPostView>,
        baseUrl: String,
        lang: PublicLanguage,
        strings: PublicUiStrings,
    ) {
        section {
            attributes["id"] = "beitraege"
            ol(classes = "rank-list") {
                posts.forEach { post ->
                    li {
                        a(
                            href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s/${post.id}", lang = lang),
                        ) { +post.excerptTitleForTeaser() }
                        span(classes = "section-note") { +" · ${post.authorDisplayName} · ${post.totalWeightLtr} LTR" }
                    }
                }
            }
            p { a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang)) { +strings.allPosts } }
        }
    }

    private fun FlowContent.renderFurtherLinks(
        baseUrl: String,
        lang: PublicLanguage,
        strings: PublicUiStrings,
    ) {
        section {
            p {
                a(
                    href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/transparenz", lang = lang),
                ) { +strings.linkTransparency }
            }
            p { a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang)) { +strings.statPosts } }
        }
    }

    /** Identisch zu [PublicTransparencyHtml]'s eigener, PRIVATER `excerptTitleForTeaser` -- absichtlich dupliziert, siehe Klassen-KDoc. */
    private fun PublicPostView.excerptTitleForTeaser(): String {
        val firstLine = contentLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length <= 70) firstLine.ifBlank { "Beitrag" } else firstLine.take(69).trimEnd() + "…"
    }
}

/**
 * `stats == null` ⇔ leere Installation (0 Mitglieder UND 0 öffentliche Beiträge, siehe
 * [PublicLandingRoutes]'s `buildView`) -- der Kennzahlenblock entfällt dann VOLLSTÄNDIG, statt drei
 * Nullen zu zeigen (Kare/Jobs, Leerzustand ist Pflicht).
 */
internal data class PublicLandingView(
    val stats: PublicTransparencyStats?,
    val topPosts: List<PublicPostView>,
)
