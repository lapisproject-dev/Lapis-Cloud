package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.body
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.em
import kotlinx.html.emailInput
import kotlinx.html.footer
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.label
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.stream.createHTML
import kotlinx.html.submitInput
import kotlinx.html.textArea
import kotlinx.html.textInput
import kotlinx.html.time
import kotlinx.html.title
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.shared.domain.SocialPostReportCategory

/**
 * V1.1.3 Soziales Netzwerk "Öffentlicher SEO-Lesepfad" -- the FIRST unauthenticated HTML-rendering
 * surface in this codebase. Rendering happens exclusively through the `kotlinx.html` typed builder
 * DSL, rendered to a plain `String` (via `kotlinx.html.stream.createHTML`), never directly into the
 * Ktor response stream -- see `gradle/libs.versions.toml`'s `kotlinx-html` entry for why a `String`
 * result is required (ETag computed over the finished body, pure-function testability without
 * `testApplication`).
 *
 * **Non-negotiable properties of every render function in this file** (see also
 * `SocialPublicRoutesTest`'s XSS/no-raw-HTML-escape-bypass/determinism/no-cloaking test groups):
 *
 * 1. **The HTML-escape-bypassing `kotlinx.html` API is never used anywhere in this file.** A
 *    source-text scan test enforces this (see `SocialPublicRoutesTest` "T6"). Every user-controlled
 *    string (post content, display names) reaches the output exclusively through `kotlinx.html`'s
 *    ordinary text-node (`+"..."`) and attribute-value APIs, both of which escape automatically.
 * 2. **No `href`/`src` built from user-controlled data.** Every link in this file is either
 *    `"$baseUrl/s/$uuid"` (a UUID, never free text) or a hardcoded relative path. Post content is
 *    plain text with no markup/link support (S7 in the domain concept), so this never actually
 *    arises here -- documented anyway so a later Markdown-rendering wave does not silently violate
 *    it.
 * 3. **[baseUrl] is always [network.lapis.cloud.server.federation.FederationConfig.publicBaseUrl]**,
 *    never derived from the request's `Host` header -- a Host-header-injection attack would
 *    otherwise poison the `canonical`/`og:url`/sitemap URLs this renderer emits. Enforced by the
 *    caller (`SocialPublicRoutes.kt`), not by this file, but documented here because every function
 *    below trusts [baseUrl] unconditionally.
 * 4. **No request-time-dependent output** beyond the domain data itself -- no "as of now", no
 *    relative dates ("3 days ago"). Two calls with identical inputs MUST produce byte-identical
 *    output (the whole ETag/304 mechanism in `SocialPublicRoutes.kt` depends on this).
 * 5. **Identical output for a crawler and a human.** No `User-Agent` sniffing anywhere -- that would
 *    be cloaking.
 *
 * View models ([PublicPostView]/[PublicTimelineView]/[PublicThreadView]) are deliberately narrower
 * than [network.lapis.cloud.shared.domain.SocialPostDto] -- see [PublicPostView] KDoc for exactly
 * which fields are missing and why (data minimization BY CONSTRUCTION: a field this type does not
 * have cannot accidentally leak here, regardless of what the mapping code in `SocialPublicRoutes.kt`
 * does or does not remember to omit).
 */
internal object SocialPublicHtml {
    /**
     * Static stylesheet, served under `/s/assets/style.css` -- a plain `const val` with zero
     * interpolation. Exists so no inline `<style>` block is ever needed anywhere in this file, which
     * is what lets `SocialPublicRoutes`' Content-Security-Policy restrict `style-src` to `'self'`
     * instead of having to allow inline styles as a CSP source keyword (see class KDoc point 1 for
     * this file's own, separate rendering-safety guarantee).
     */
    const val STYLESHEET: String =
        """
        :root { color-scheme: light dark; }
        body { font-family: system-ui, -apple-system, sans-serif; max-width: 42rem; margin: 0 auto; padding: 1.5rem; line-height: 1.55; }
        header, footer { margin-bottom: 1.5rem; }
        footer { margin-top: 2rem; font-size: 0.85rem; color: #888; }
        article { border-bottom: 1px solid rgba(128, 128, 128, 0.3); padding: 1rem 0; }
        article h2 { margin: 0 0 0.25rem 0; font-size: 1.05rem; }
        article p { margin: 0.35rem 0; white-space: pre-wrap; overflow-wrap: anywhere; }
        .meta { color: #888; font-size: 0.85rem; }
        .notice { color: #888; font-size: 0.85rem; font-style: italic; }
        nav { display: flex; justify-content: space-between; margin-top: 1.5rem; gap: 1rem; }
        a { color: inherit; }
        .hp { position: absolute; left: -9999px; top: -9999px; }

        /* V1.3.0 "Öffentliche Transparenz-Startseite" (/transparenz) -- shares this stylesheet, see
           PublicTransparencyHtml class KDoc. */
        nav.jump { justify-content: flex-start; flex-wrap: wrap; }
        .stats { display: flex; flex-wrap: wrap; gap: 1.5rem; margin: 0.5rem 0 1rem 0; }
        .stat { min-width: 8rem; }
        .stat-value { font-size: 1.4rem; font-weight: 600; }
        .stat-label { color: #888; font-size: 0.85rem; }
        .rank-top { display: flex; align-items: center; gap: 0.6rem; padding: 0.4rem 0; }
        .rank-num { font-size: 1.3rem; font-weight: 700; width: 1.6rem; text-align: right; }
        .mono {
            display: inline-flex; align-items: center; justify-content: center;
            width: 2rem; height: 2rem; border-radius: 50%;
            background: rgba(128, 128, 128, 0.2); font-size: 0.75rem; font-weight: 600;
        }
        .rank-name { font-weight: 600; }
        .rank-amount { margin-left: auto; color: #888; }
        .rank-list { padding-left: 1.6rem; }
        .rank-list li { padding: 0.2rem 0; }
        .section-note { color: #888; font-size: 0.8rem; font-style: italic; }

        /* Welle V1.4.1a "Öffentliche Website-Integration" (/embed/v1/login) -- shares this
           stylesheet, same reasoning as the V1.3.0 block above: no second stylesheet, no inline
           <style>, see network.lapis.cloud.server.routes.EmbedHtml class KDoc. */
        .embed-page { max-width: 22rem; margin: 3rem auto; }
        .embed-field { margin-bottom: 0.9rem; }
        .embed-field label { display: block; margin-bottom: 0.3rem; font-size: 0.9rem; }
        .embed-field input { width: 100%; padding: 0.5rem; box-sizing: border-box; }
        .embed-error { color: #b00020; font-size: 0.85rem; min-height: 1.2rem; }
        .embed-submit { width: 100%; padding: 0.6rem; margin-top: 0.4rem; }

        /* Welle V1.4.6 "Öffentliche Startseite" (/) -- shares this stylesheet, same reasoning as
           every block above: no second stylesheet, no inline <style>, see
           network.lapis.cloud.server.routes.PublicLandingHtml class KDoc. body's max-width: 42rem
           above applies here too -- the hero stays in that column, no exception. */
        .hero { margin-bottom: 2rem; }
        .hero p { color: #888; }
        .cta {
            display: inline-block; padding: 0.55rem 1.1rem; margin-right: 0.6rem;
            border: 1px solid currentColor; border-radius: 0.35rem; text-decoration: none;
        }
        .cta-primary { background: rgba(128, 128, 128, 0.15); font-weight: 600; }

        /* Welle "Einheitlicher Kopfbereich + Sprachumschalter" -- der gemeinsame Kopfbereich aller
           drei öffentlichen Seiten (/, /s, /transparenz), siehe PublicChrome class KDoc. Farbwerte
           1:1 aus theme.css' --lapis-nav-* / --lapis-fleck-Tokens übernommen (kein neuer, frei
           erfundener Hex-Wert), damit /app und die drei server-gerenderten Seiten visuell konsistent
           wirken. Additiv -- body's max-width bleibt für .page erhalten, EmbedHtml/EventPublicHtml
           setzen "has-chrome" nie und sind von diesem Block unberührt (Byte-Identitäts-Regressionstest
           in SocialPublicHtmlTest). */
        body.has-chrome { max-width: none; margin: 0; padding: 0; }
        body.has-chrome > main,
        body.has-chrome > footer { max-width: 42rem; margin: 0 auto; padding: 0 1.5rem; }
        body.has-chrome > footer { padding-bottom: 1.5rem; }
        .chrome { background: #14181E; color: #EDEAE3; }
        .chrome-inner {
            max-width: 42rem; margin: 0 auto; padding: 0.6rem 1.5rem;
            display: flex; align-items: center; gap: 1rem; flex-wrap: wrap;
        }
        .chrome a { color: #EDEAE3; text-decoration: none; }
        .chrome-logo { max-height: 22px; width: auto; vertical-align: middle; }
        .chrome-wordmark { font-family: Georgia, serif; letter-spacing: 0.01em; }
        .chrome-nav { display: flex; gap: 0.9rem; margin: 0; }
        .chrome-nav a { display: inline-flex; align-items: center; border-bottom: 2px solid transparent; padding: 0.2rem 0; }
        .chrome-nav a[aria-current="page"] { color: #C9A227; border-bottom-color: #C9A227; }
        /* Nutzer-Feedback 2026-09-09: passende Icons je Reiter -- rein dekorativ (CSS
           mask-image, kein <img>/<svg> im Markup), deshalb ohne jede a11y-Auswirkung: ein
           Screenreader sieht weiterhin nur den Linktext. currentColor via background-color +
           mask übernimmt automatisch die aktive-Seite-Goldfarbe (siehe Regel oben) ohne eigene
           Farbregel je Icon. Feste nth-child-Reihenfolge, weil PublicChrome.renderChrome die
           drei Links immer in genau dieser Reihenfolge rendert (Start/Transparenz/Soziales
           Netzwerk) -- siehe dessen eigene KDoc. */
        .chrome-nav a::before {
            content: ""; display: inline-block; width: 1.05em; height: 1.05em; margin-right: 0.4em;
            background-color: currentColor;
            -webkit-mask-repeat: no-repeat; mask-repeat: no-repeat;
            -webkit-mask-position: center; mask-position: center;
            -webkit-mask-size: contain; mask-size: contain;
            flex-shrink: 0;
        }
        .chrome-nav a:nth-child(1)::before {
            -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23000' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M3 11.5 12 4l9 7.5'/%3E%3Cpath d='M5.5 10v9h13v-9'/%3E%3C/svg%3E");
            mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23000' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M3 11.5 12 4l9 7.5'/%3E%3Cpath d='M5.5 10v9h13v-9'/%3E%3C/svg%3E");
        }
        .chrome-nav a:nth-child(2)::before {
            -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23000' stroke-width='2' stroke-linecap='round'%3E%3Cpath d='M4 20V10M12 20V4M20 20v-7'/%3E%3C/svg%3E");
            mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23000' stroke-width='2' stroke-linecap='round'%3E%3Cpath d='M4 20V10M12 20V4M20 20v-7'/%3E%3C/svg%3E");
        }
        .chrome-nav a:nth-child(3)::before {
            -webkit-mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23000' stroke-width='2' stroke-linecap='round'%3E%3Ccircle cx='8' cy='8' r='3'/%3E%3Ccircle cx='17' cy='9' r='2.5'/%3E%3Cpath d='M3 20c0-3 2.5-5 5-5s5 2 5 5'/%3E%3Cpath d='M14.5 20c0-2.2 1.8-4 4-4s4 1.8 4 4'/%3E%3C/svg%3E");
            mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23000' stroke-width='2' stroke-linecap='round'%3E%3Ccircle cx='8' cy='8' r='3'/%3E%3Ccircle cx='17' cy='9' r='2.5'/%3E%3Cpath d='M3 20c0-3 2.5-5 5-5s5 2 5 5'/%3E%3Cpath d='M14.5 20c0-2.2 1.8-4 4-4s4 1.8 4 4'/%3E%3C/svg%3E");
        }
        .chrome-lang { margin-left: auto; position: relative; }
        .chrome-lang summary {
            cursor: pointer; list-style: none; padding: 0.5rem 0.6rem; min-height: 44px;
            display: inline-flex; align-items: center;
        }
        .chrome-lang summary::-webkit-details-marker { display: none; }
        .chrome-lang[open] > div {
            position: absolute; right: 0; z-index: 1; background: #14181E;
            border: 1px solid #1E242E; padding: 0.3rem 0; min-width: 9rem;
        }
        .chrome-lang a { display: block; padding: 0.55rem 0.9rem; }
        .chrome-lang a[aria-current="true"] { color: #C9A227; }
        .chrome-cta { border: 1px solid currentColor; border-radius: 0.35rem; padding: 0.4rem 0.9rem; }
        .skip-link { position: absolute; left: -9999px; top: 0; }
        .skip-link:focus { position: static; display: block; padding: 0.5rem 1.5rem; background: #C9A227; color: #14181E; }
        @media (max-width: 30rem) { .chrome-nav { order: 3; width: 100%; } }
        """

    /** Title length ceiling -- shared by `<title>` and `og:title`. */
    private const val TITLE_MAX_LEN = 70

    /** Description length ceiling -- shared by `<meta name="description">` and `og:description`. */
    private const val DESCRIPTION_MAX_LEN = 160

    /**
     * How many lines of a TIMELINE (root-post) summary's content are shown before a "read more" link
     * takes over -- see [renderTimelinePostSummary] KDoc for why this truncation is safe there
     * (unlike for thread descendants, see [renderThreadDescendant]).
     */
    private const val SUMMARY_LINE_COUNT = 5

    /**
     * Security-Audit-Fund S-1 (2026-08-18): hard UTF-8 byte budget for the DESCENDANTS section of a
     * thread page ([postPage]) -- independent of, and in addition to,
     * `SocialReadPipeline.SocialReadCaps.PUBLIC.threadMaxNodes` (the row-count cap applied further
     * upstream, in the DB query). The row-count cap alone was not sufficient: a single node's
     * `content` can be up to 5 000 characters (`SocialNetworkService.MAX_CONTENT_LENGTH`), and
     * [renderThreadDescendant] intentionally never truncates a descendant's content (see its own
     * KDoc, M4-Fix Review-Runde 1) -- so a thread crafted with many nodes whose content is
     * overwhelmingly `\n` (one `<p>` per line) could reach an unbounded, measured-in-tens-of-MB body
     * per anonymous, uncached (`HEAD` included, `AutoHeadResponse` is global) request, well past what
     * a `-Xmx1g` heap can absorb under a handful of concurrent requests. This is a SEPARATE,
     * independent defense layer, not a replacement for the row-count cap -- see
     * `SocialReadPipeline.SocialReadCaps.PUBLIC` KDoc for that layer.
     *
     * Chosen order of magnitude: 1.5 MB. Generous for every legitimate thread observed so far (a
     * 1 000-node thread of ordinary, mostly-single-line comments renders to a small fraction of
     * this), while keeping the worst case for a SINGLE request firmly in "a few requests do not dent
     * the heap" territory, unlike the ~80 MB (pretty-printed) / ~35 MB (compact) worst case measured
     * before this fix. Rendering STOPS once the budget is exhausted -- it does not silently cut a
     * node in half -- and the existing `truncated` notice ("Weitere Antworten werden hier nicht
     * angezeigt.") is shown, preserving the M4 principle of never a silent cutoff: a byte-budget
     * truncation looks, to the reader, identical to a row-count truncation.
     */
    private const val THREAD_DESCENDANTS_BYTE_BUDGET = 1_500_000

    /**
     * Security-Audit-Fund S2-2 (Runde 2, 2026-08-18): conservative fixed per-post rendering overhead
     * for the `<article>`/`<h2>`/`<time>`/meta-line markup surrounding a descendant, EXCLUDING the
     * one piece of that markup whose size depends on [network.lapis.cloud.server.federation
     * .FederationConfig.publicBaseUrl]'s length -- the `href="$baseUrl/s/$id"` link in
     * [renderThreadDescendant]. That piece is added separately, as `baseUrl.length`, in
     * [PublicPostView.estimatedRenderedByteSize]. Splitting the two means a long
     * `LAPIS_PUBLIC_BASE_URL` can no longer silently erode the safety margin the way a single
     * baseUrl-length-oblivious constant did before this fix: the OLD KDoc here claimed "actual
     * overhead ... is well under this" for a flat 200, which stopped being true once `baseUrl`
     * exceeded roughly 41 characters (200 minus the ~159-byte fixed-markup measurement below) -- an
     * UNDER-estimate is exactly the failure mode [THREAD_DESCENDANTS_BYTE_BUDGET] exists to prevent.
     *
     * Measured fixed markup (bytes, `prettyPrint = false`, everything that does NOT scale with
     * `baseUrl` or user content): `<article>`(9) + `<h2>`(4) + `<a href="`(9) + `/s/`(3) + UUID(36) +
     * `">`(2) + `</a>`(4) + `</h2>`(5) + `<p>`(3) + `<time datetime="`(17) + ISO-8601 timestamp(~29)
     * + `">`(2) + `</time>`(7) + two ` · ` separators(8) + `Gesamtgewicht `(14) + ` LTR`(4) + `</p>`(4)
     * + `</article>`(10) = ~174 bytes. Rounded up to 220 for margin.
     */
    private const val PER_POST_RENDER_OVERHEAD_BYTES = 220

    /**
     * Security-Audit-Fund S2-1 (Runde 2, 2026-08-18): strict upper bound on how many UTF-8 bytes a
     * single UTF-16 code unit (one `Char`/one unit of `String.length`) can turn into once
     * `kotlinx.html` writes it out. Two independent expansion mechanisms are covered by the SAME
     * constant, and 6 is a safe bound for both:
     *
     * - **HTML-entity escaping**: `kotlinx.html` escapes `"` (in attribute values) to `&quot;` -- 1
     *   input byte becomes 6 output bytes, the worst case among `"`/`&`/`<`/`>` (`&quot;`=6,
     *   `&amp;`=5, `&lt;`/`&gt;`=4).
     * - **Multi-byte UTF-8 encoding**: an un-escaped BMP character encodes to at most 3 UTF-8 bytes
     *   per UTF-16 code unit; a surrogate pair (2 code units) encodes its single codepoint to at most
     *   4 UTF-8 bytes total, i.e. at most 2 bytes per code unit -- both well under 6.
     *
     * Using `length * MAX_ESCAPED_BYTES_PER_CHAR` is therefore a correct upper bound for EVERY
     * possible character, with no need to actually inspect/escape the string to find out (unlike the
     * PREVIOUS estimate, which used `line.toByteArray(Charsets.UTF_8).size` -- the RAW input's byte
     * size, not the size AFTER `kotlinx.html`'s escaping. A content line of 5 000 `"` characters
     * previously estimated at 5 000 bytes but actually rendered to 30 000 bytes -- a thread filled
     * with such content could reach ~8.7 MB instead of the intended ~1.5 MB, a 5.8x breach of
     * [THREAD_DESCENDANTS_BYTE_BUDGET]). This length-based bound also drops the `toByteArray()`
     * allocation entirely (Runde-2 finding S2-5) -- pure integer arithmetic, no per-node allocation.
     */
    private const val MAX_ESCAPED_BYTES_PER_CHAR = 6

    /**
     * Conservative fixed per-line overhead (`<p>` + `</p>`, 7 bytes with `prettyPrint = false`,
     * rounded up) added per content line -- this is what makes a content string that is
     * overwhelmingly `\n` (many short/empty lines, each becoming its own paragraph) expensive: the
     * PER-LINE overhead, not the character count, dominates that worst case.
     */
    private const val PER_LINE_RENDER_OVERHEAD_BYTES = 10

    fun timelinePage(
        view: PublicTimelineView,
        baseUrl: String,
        /**
         * V1.2.5 White-Label-Branding, seit der Sprachumschalter-Welle das volle [ResolvedBranding]
         * statt nur `brandTitle: String` -- siehe [registerSocialPublicRoutes]' eigene `branding`
         * KDoc. Default beibehalten (anders als bei den `register*Routes`-Funktionen) -- diese
         * Pure-Render-Funktion hat viele bestehende Tests, die mit Branding/Sprache nichts zu tun
         * haben, siehe [PublicChrome] Klassen-KDoc "Implementierungslücken der Design-Spec § 4.4b".
         */
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        /** Sprachumschalter-Welle -- steuert Chrome UND Body-Text dieser Seite, Default Deutsch. */
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        val pageTitle =
            if (view.page <=
                1
            ) {
                "${strings.socialH1} – ${branding.title}"
            } else {
                "${strings.socialH1} – ${strings.jumpPosts} ${view.page} – ${branding.title}"
            }
        val currentPath = timelinePathOnly(page = view.page)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = pageTitle,
                description = "${strings.statPosts} · ${branding.title}",
                canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = lang),
                robots = if (view.page <= 1) "index,follow" else "noindex,follow",
                ogType = "website",
                hreflangBaseUrl = baseUrl,
                hreflangCurrentPath = currentPath,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.SOCIAL,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                    )
                }
                main {
                    attributes["id"] = "main"
                    h1 { +strings.socialH1 }
                    if (view.posts.isEmpty()) {
                        p { +strings.noPosts }
                    } else {
                        view.posts.forEach { post -> renderTimelinePostSummary(post = post, baseUrl = baseUrl, lang = lang) }
                    }
                    nav {
                        if (view.page > 1) {
                            a(
                                href =
                                    PublicChrome.languageUrl(
                                        baseUrl = baseUrl,
                                        currentPath = timelinePathOnly(page = view.page - 1),
                                        lang = lang,
                                    ),
                            ) { +"← ${strings.prevPage}" }
                        }
                        if (view.hasNext) {
                            a(
                                href =
                                    PublicChrome.languageUrl(
                                        baseUrl = baseUrl,
                                        currentPath = timelinePathOnly(page = view.page + 1),
                                        lang = lang,
                                    ),
                            ) { +"${strings.nextPage} →" }
                        }
                    }
                    // V1.3.0 "Öffentliche Transparenz-Startseite" -- mutual link between the two
                    // public HTML route families, see PublicTransparencyRoutes KDoc.
                    p {
                        a(
                            href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/transparenz", lang = lang),
                        ) { +strings.navTransparency }
                    }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }
    }

    fun postPage(
        view: PublicThreadView,
        baseUrl: String,
        /** V1.2.5 White-Label-Branding -- siehe [timelinePage]'s eigene `branding` KDoc. */
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        /** Sprachumschalter-Welle -- siehe [timelinePage]'s eigene `lang` KDoc. */
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String =
        createHTML(prettyPrint = false).html {
            val strings = PublicChrome.stringsFor(lang)
            val currentPath = "/s/${view.root.id}"
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "${view.root.excerptTitle} – ${branding.title}",
                description = view.root.excerpt(maxLen = DESCRIPTION_MAX_LEN),
                canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = lang),
                robots = "index,follow",
                ogType = "article",
                hreflangBaseUrl = baseUrl,
                hreflangCurrentPath = currentPath,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.SOCIAL,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                    )
                }
                main {
                    attributes["id"] = "main"
                    nav {
                        a(
                            href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang),
                        ) { +"← ${strings.backToTimeline}" }
                    }
                    article {
                        h1 { +view.root.excerptTitle }
                        renderPostMeta(post = view.root, baseUrl = baseUrl, lang = lang)
                        renderPostContent(post = view.root, lines = view.root.contentLines)
                    }
                    if (view.descendants.isNotEmpty() || view.truncated) {
                        section {
                            h2 { +strings.replies }
                            // Security-Audit-Fund S-1 (2026-08-18): render descendants until the byte
                            // budget is exhausted, THEN stop -- see THREAD_DESCENDANTS_BYTE_BUDGET
                            // KDoc. `byteBudgetTruncated` deliberately ORs into the SAME notice as
                            // `view.truncated` (the row-count cap from SocialReadPipeline) below: a
                            // reader cannot tell, and does not need to be able to tell, which of the
                            // two independent caps stopped the list -- both mean exactly the same
                            // thing ("more replies exist, not shown here").
                            var bytesUsed = 0
                            var byteBudgetTruncated = false
                            for (node in view.descendants) {
                                val estimate = node.estimatedRenderedByteSize(baseUrl = baseUrl)
                                if (bytesUsed + estimate > THREAD_DESCENDANTS_BYTE_BUDGET) {
                                    byteBudgetTruncated = true
                                    break
                                }
                                renderThreadDescendant(post = node, baseUrl = baseUrl, lang = lang)
                                bytesUsed += estimate
                            }
                            if (view.truncated || byteBudgetTruncated) {
                                p { +strings.moreRepliesHidden }
                            }
                        }
                    }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }

    /**
     * `robots` is `noindex` (not `noindex,follow`) -- there is nothing on a 404 page worth a crawler
     * following. Sprachumschalter-Welle (Entscheidung § 5.2): OHNE natürlichen "aktuellen Pfad" (eine
     * 404 ist von einer beliebigen URL erreichbar) -- der Sprachumschalter im Chrome verlinkt deshalb
     * auf `/s`, nicht auf einen Versuch, die fehlerhafte URL erneut aufzurufen.
     */
    fun notFoundPage(
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "Nicht gefunden – ${branding.title}",
                description = "Dieser Beitrag ist nicht (mehr) öffentlich verfügbar.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) { renderChrome(lang = lang, active = null, baseUrl = baseUrl, branding = branding, currentPath = "/s") }
                main {
                    attributes["id"] = "main"
                    h1 { +"Nicht gefunden" }
                    p { +"Dieser Beitrag ist nicht (mehr) öffentlich verfügbar." }
                    a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang)) { +strings.backToTimeline }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }
    }

    fun tooManyRequestsPage(
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "Zu viele Anfragen – ${branding.title}",
                description = "Bitte versuchen Sie es in Kürze erneut.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) { renderChrome(lang = lang, active = null, baseUrl = baseUrl, branding = branding, currentPath = "/s") }
                main {
                    attributes["id"] = "main"
                    h1 { +"Zu viele Anfragen" }
                    p { +"Bitte versuchen Sie es in Kürze erneut." }
                    a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang)) { +strings.backToTimeline }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }
    }

    /**
     * Security-Audit-Fund MAJOR-1/MINOR-3 (Runde 1, 2026-08-19): the generic 400/413 page
     * `POST /s/{id}/report` falls back to for a malformed request rejected BEFORE the body is ever
     * parsed (oversized body, missing `Content-Length`, wrong `Content-Type`) -- see
     * `SocialPublicRoutes.respondPublicMalformedRequest`. Deliberately a single fixed string
     * covering every one of those reasons, same "no internals to an anonymous caller" discipline
     * as [serverErrorPage]. `robots` is `noindex` for the same reason as [notFoundPage].
     */
    fun malformedRequestPage(
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "Ungültige Anfrage – ${branding.title}",
                description = "Diese Anfrage konnte nicht verarbeitet werden.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) { renderChrome(lang = lang, active = null, baseUrl = baseUrl, branding = branding, currentPath = "/s") }
                main {
                    attributes["id"] = "main"
                    h1 { +"Ungültige Anfrage" }
                    p { +"Diese Anfrage konnte nicht verarbeitet werden. Bitte versuchen Sie es erneut." }
                    a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang)) { +strings.backToTimeline }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }
    }

    /**
     * M1-Fix (Review-Runde 1): the generic 500 page every public handler falls back to via
     * `SocialPublicRoutes.withPublicErrorHandling`. Deliberately a FIXED string with no interpolated
     * exception message/stack trace anywhere -- an internal error detail is never something an
     * anonymous, unauthenticated visitor should see (information disclosure), and it would also
     * break this file's own determinism guarantee (class KDoc point 4) if it varied per failure.
     * `robots` is `noindex` for the same reason as [notFoundPage] -- nothing here is worth a crawler
     * following. Sprachumschalter-Welle (Entscheidung § 5.3): KEIN `lang`-Parameter -- die Chrome-
     * Sprache ist hier IMMER Deutsch, ein unerwarteter Fehler kann bereits VOR der `lang`-Auflösung
     * auftreten (siehe `SocialPublicRoutes.withPublicErrorHandling` KDoc).
     */
    fun serverErrorPage(
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            renderHead(
                pageTitle = "Interner Fehler – ${branding.title}",
                description = "Bei der Verarbeitung dieser Anfrage ist ein Fehler aufgetreten.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(lang = PublicLanguage.DEFAULT, active = null, baseUrl = baseUrl, branding = branding, currentPath = "/s")
                }
                main {
                    attributes["id"] = "main"
                    h1 { +"Interner Fehler" }
                    p { +"Bei der Verarbeitung dieser Anfrage ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut." }
                    a(href = "$baseUrl/s") { +"Zur Timeline" }
                }
                footer { p { +"${branding.title} · Betrieben mit Lapis Cloud" } }
            }
        }

    /**
     * Welle V1.1.5 (E-B). Trägt AUSSCHLIESSLICH: die Tatsache der Entfernung, das Datum und die
     * Begründung. Ausdrücklich NICHT: den ursprünglichen Inhalt, einen Ausschnitt davon, den
     * Autorennamen, Gewichte, Antwortzahlen, einen Melde-Link (an einem bereits entfernten Beitrag
     * gibt es nichts mehr zu melden). `description` ist ein FESTER Satz und trägt die Begründung
     * NICHT -- eine Meta-Description wandert in Such-Snippets, die Begründung soll gelesen werden
     * können, nicht ausgestreut. `robots = "noindex"` wie [notFoundPage].
     *
     * Der Autorenname fehlt bewusst: E-B macht die BEGRÜNDUNG öffentlich, nicht die Zuordnung einer
     * mutmaßlichen Rechtsverletzung zu einer namentlich benannten Person. Sprachumschalter-Welle: der
     * Chrome ist mehrsprachig, der eigentliche Begründungstext ([view.reasonLines]) bleibt UNVERÄNDERT
     * (kommt aus der Domäne, nicht aus [PublicUiStrings]) -- diese Seite hat, anders als die
     * Fehlerseiten oben, eine echte Post-ID und bekommt deshalb `currentPath = "/s/{id}"` statt "/s".
     */
    fun legallyRemovedPage(
        view: PublicRemovalNoticeView,
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        val currentPath = "/s/${view.postId}"
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "Beitrag aus rechtlichen Gründen entfernt – ${branding.title}",
                description = "Dieser Beitrag wurde aus rechtlichen Gründen entfernt.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.SOCIAL,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                    )
                }
                main {
                    attributes["id"] = "main"
                    h1 { +"Beitrag aus rechtlichen Gründen entfernt" }
                    p {
                        +"Dieser Beitrag wurde am ${view.removedAtHuman} aus rechtlichen Gründen entfernt. "
                        +"Der ursprüngliche Inhalt ist nicht mehr verfügbar."
                    }
                    section {
                        h2 { +"Begründung" }
                        view.reasonLines.forEach { line -> p { +line } }
                    }
                    p { +"Die Entfernung erfolgt nach den Vorgaben des Digital Services Act (Verordnung (EU) 2022/2065)." }
                }
                footer {
                    p {
                        a(
                            href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang),
                        ) { +strings.backToTimeline }
                    }
                }
            }
        }
    }

    /**
     * Welle V1.1.5 (Plan § 4.2) -- DSA Art. 16 Meldeformular für [postId]. Ein klassisches
     * `<form method=post>` ohne jedes JavaScript (die CSP dieses Pfads erlaubt keine Skripte).
     * `contact` ist OPTIONAL (Entscheidungspunkt E-F), `goodFaith` ist ein PFLICHT-Checkbox (DSA
     * Art. 16 Abs. 2 lit. d). Enthält ein per CSS ausgeblendetes Honeypot-Feld (`website`) --
     * klassischer No-JS-Bot-Schutz, ausgefüllt ⇒ stiller No-Op serverseitig. **Bewusst ein ganz
     * normales `<input type="text">`** (kein `type="hidden"`) -- ein simpler Scraper füllt üblicherweise
     * nur sichtbare Textfelder aus und überspringt `type="hidden"` von vornherein, was den Honeypot
     * wirkungslos machen würde (Review-Fund 4, Runde 1 2026-08-19). Stattdessen versteckt allein die
     * `.hp`-Regel in [STYLESHEET] das umschließende `<div>` (`position: absolute; left: -9999px`) --
     * für einen menschlichen Besucher unsichtbar, im DOM aber ein ganz normal aussehendes Textfeld.
     */
    fun reportFormPage(
        postId: String,
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        /**
         * Sprachumschalter-Welle: steuert NUR den umgebenden Chrome (Nav/Sprachumschalter/CTAs) --
         * das eigentliche Formular (Rechtstext, DSA Art. 16) bleibt für JEDE Sprache vollständig
         * Deutsch, in ein `<div lang="de">` gewrappt, siehe diese Funktions-KDoc.
         */
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String =
        createHTML(prettyPrint = false).html {
            val strings = PublicChrome.stringsFor(lang)
            val currentPath = "/s/$postId/report"
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "Beitrag melden – ${branding.title}",
                description = "Diesen Beitrag wegen eines möglichen Rechtsverstoßes melden.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.SOCIAL,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                    )
                }
                main {
                    attributes["id"] = "main"
                    attributes["lang"] = "de"
                    nav {
                        a(
                            href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s/$postId", lang = lang),
                        ) { +"← Zurück zum Beitrag" }
                    }
                    h1 { +"Beitrag melden" }
                    p {
                        +(
                            "Bitte begründen Sie, warum dieser Beitrag rechtswidrig ist (Digital Services Act, " +
                                "Verordnung (EU) 2022/2065, Art. 16). Name und E-Mail-Adresse sind freiwillig -- " +
                                "ohne Angabe können wir Ihnen den Eingang und die Entscheidung nicht mitteilen."
                        )
                    }
                    form(
                        action = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s/$postId/report", lang = lang),
                        method = FormMethod.post,
                    ) {
                        // Honeypot -- siehe diese Funktions-KDoc. Muss ein normales, im Markup
                        // sichtbares `<input type="text">` sein (nur per CSS ausgeblendet), damit ein
                        // simpler Scraper es tatsaechlich befuellt.
                        div(classes = "hp") {
                            attributes["aria-hidden"] = "true"
                            label {
                                htmlFor = "website"
                                +"Website"
                            }
                            textInput(name = "website") {
                                attributes["id"] = "website"
                                attributes["tabindex"] = "-1"
                                attributes["autocomplete"] = "off"
                            }
                        }
                        label {
                            htmlFor = "category"
                            +"Kategorie"
                        }
                        select {
                            attributes["id"] = "category"
                            name = "category"
                            SocialPostReportCategory.entries.forEach { category ->
                                option {
                                    value = category.name
                                    +reportCategoryLabel(category)
                                }
                            }
                        }
                        label {
                            htmlFor = "description"
                            +"Begründung (Pflichtfeld)"
                        }
                        textArea {
                            attributes["id"] = "description"
                            name = "description"
                            rows = "5"
                            attributes["maxlength"] = "4000"
                            attributes["required"] = "required"
                        }
                        label {
                            htmlFor = "contact"
                            +"E-Mail-Adresse (optional)"
                        }
                        emailInput {
                            attributes["id"] = "contact"
                            name = "contact"
                        }
                        label {
                            checkBoxInput {
                                name = "goodFaith"
                                attributes["required"] = "required"
                            }
                            +" Ich erkläre, dass diese Meldung nach bestem Wissen zutreffend und in gutem Glauben abgegeben wird."
                        }
                        p { +"Der genaue Beitrag wird automatisch aus der Adresse dieser Seite übernommen." }
                        submitInput(classes = null) { value = "Meldung absenden" }
                    }
                    p {
                        +(
                            "Welche Daten wir zu welchem Zweck erheben: die von Ihnen eingegebenen Angaben (Kategorie, " +
                                "Begründung, optional E-Mail) sowie der Zeitpunkt der Meldung. Ihre IP-Adresse wird " +
                                // MINOR-2 (Security-Audit Runde 1, 2026-08-19): "wird NICHT gespeichert" war eine
                                // unqualifizierte Absolutaussage -- tatsaechlich haelt FederationInboxRateLimiter
                                // einen von der IP abgeleiteten Schluessel bis zu Ablauf des Rate-Limit-Fensters im
                                // Speicher, und der vorgeschaltete Apache-Reverse-Proxy loggt Client-IPs per Default
                                // in seinem Access-Log. Praezisiert auf "nicht MIT Ihrer Meldung" -- korrekt, weil
                                // die IP nie in der social_post_report-Zeile selbst landet.
                                "nicht MIT Ihrer Meldung gespeichert. Die Daten dienen ausschließlich der Bearbeitung " +
                                "dieser Meldung nach Art. 16 DSA."
                        )
                    }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }

    /** Welle V1.1.5 -- IMMER dieselbe Antwort (Plan § 4.3), egal ob die Meldung gespeichert wurde, egal ob der Post existiert, egal ob das Honeypot-Feld ausgefüllt war. */
    fun reportSubmittedPage(
        baseUrl: String,
        branding: ResolvedBranding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null),
        lang: PublicLanguage = PublicLanguage.DEFAULT,
    ): String =
        createHTML(prettyPrint = false).html {
            val strings = PublicChrome.stringsFor(lang)
            attributes["lang"] = lang.code
            renderHead(
                pageTitle = "Meldung übermittelt – ${branding.title}",
                description = "Ihre Meldung wurde übermittelt.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.SOCIAL,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = "/s",
                    )
                }
                main {
                    attributes["id"] = "main"
                    attributes["lang"] = "de"
                    h1 { +"Meldung übermittelt" }
                    p { +"Vielen Dank. Ihre Meldung wird geprüft." }
                    a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s", lang = lang)) { +strings.backToTimeline }
                }
                footer { p { +"${branding.title} · ${strings.operatedBy}" } }
            }
        }

    private fun reportCategoryLabel(category: SocialPostReportCategory): String =
        when (category) {
            SocialPostReportCategory.ILLEGAL_CONTENT -> "Rechtswidriger Inhalt"
            SocialPostReportCategory.DEFAMATION -> "Üble Nachrede/Verleumdung"
            SocialPostReportCategory.COPYRIGHT -> "Urheberrechtsverletzung"
            SocialPostReportCategory.PERSONAL_DATA -> "Personenbezogene Daten"
            SocialPostReportCategory.HATE_SPEECH -> "Hassrede"
            SocialPostReportCategory.SPAM -> "Spam"
            SocialPostReportCategory.OTHER -> "Sonstiges"
        }

    /** Path-only-Pendant zu `SocialPublicRoutes`' privater `timelinePathOnly` -- absichtlich dupliziert, siehe Klassen-KDoc "duplication would be this welle's actual risk" (dasselbe Argument gilt hier). */
    private fun timelinePathOnly(page: Int): String = if (page <= 1) "/s" else "/s?page=$page"

    /**
     * [hreflangBaseUrl]/[hreflangCurrentPath] (Sprachumschalter-Welle): wenn BEIDE gesetzt sind,
     * werden [PublicChrome.renderHreflangAlternates] die vollständigen 8+1 hreflang-Alternates
     * emittiert -- nur für `index,follow`-Seiten (`timelinePage`/`postPage`), NIEMALS für eine
     * `noindex`-Seite (404/429/400/500/451), deren Aufrufer diese beiden Parameter deshalb `null`
     * lässt.
     */
    private fun HTML.renderHead(
        pageTitle: String,
        description: String,
        canonicalUrl: String?,
        robots: String,
        ogType: String?,
        hreflangBaseUrl: String? = null,
        hreflangCurrentPath: String? = null,
    ) {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +pageTitle }
            meta(name = "description", content = description)
            meta(name = "robots", content = robots)
            if (canonicalUrl != null) {
                link(rel = "canonical", href = canonicalUrl)
            }
            link(rel = "stylesheet", href = "/s/assets/style.css")
            if (hreflangBaseUrl != null && hreflangCurrentPath != null) {
                with(PublicChrome) { renderHreflangAlternates(baseUrl = hreflangBaseUrl, currentPath = hreflangCurrentPath) }
            }
            // OpenGraph: kotlinx.html's `meta()` DSL only supports name/content/charset/http-equiv
            // directly -- `property` is set via `attributes[]`, which is escaped exactly like every
            // other attribute-value write in this library (class KDoc point 1).
            meta(content = pageTitle) { attributes["property"] = "og:title" }
            meta(content = description) { attributes["property"] = "og:description" }
            if (canonicalUrl != null) {
                meta(content = canonicalUrl) { attributes["property"] = "og:url" }
            }
            if (ogType != null) {
                meta(content = ogType) { attributes["property"] = "og:type" }
            }
            meta(content = "Lapis Cloud") { attributes["property"] = "og:site_name" }
            // No og:image in this wave -- see implementation plan § 4.2, "Kein og:image".
            meta(name = "twitter:card", content = "summary")
        }
    }

    /**
     * A root post's summary in the TIMELINE listing (`/s`). Truncation to [SUMMARY_LINE_COUNT] lines
     * is safe here -- and, since M4-Fix (Review-Runde 1), explicitly announced with a "read more"
     * link -- because [post] IS a root post: `"$baseUrl/s/${post.id}"` resolves to a DIFFERENT, FULL
     * page ([postPage]) that renders every line. Contrast [renderThreadDescendant], where that same
     * link shape 308-redirects back to the SAME page and therefore cannot serve this purpose.
     */
    private fun FlowContent.renderTimelinePostSummary(
        post: PublicPostView,
        baseUrl: String,
        lang: PublicLanguage,
    ) {
        val postUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s/${post.id}", lang = lang)
        article {
            h2 { a(href = postUrl) { +post.excerptTitle } }
            renderPostMeta(post = post, baseUrl = baseUrl, lang = lang)
            renderPostContent(post = post, lines = post.contentLines.take(SUMMARY_LINE_COUNT))
            if (post.contentLines.size > SUMMARY_LINE_COUNT) {
                p(classes = "notice") {
                    +"Gekürzt — "
                    a(href = postUrl) { +"vollständigen Beitrag ansehen" }
                }
            }
        }
    }

    /**
     * A comment (non-root node) inside a thread page ([postPage]). Renders [post]'s content lines IN
     * FULL, with NO truncation -- M4-Fix (Review-Runde 1): a descendant has no page of its own. `GET
     * /s/{commentId}` 308-redirects back to THIS SAME thread page (K4), so a truncate-plus-"read
     * more"-link treatment (as used for [renderTimelinePostSummary]) would point a reader at a link
     * that resolves to exactly the page they are already on -- the rest of the content would be
     * unreachable ANYWHERE in the public path, which is precisely the "never a silent cutoff"
     * violation this fix addresses. The resulting worst-case page size is bound by THREE independent
     * caps: `SocialReadPipeline.SocialReadCaps.PUBLIC.threadMaxNodes` (row count, DB-side),
     * `SocialNetworkService`'s `MAX_CONTENT_LENGTH` (5 000 chars per post), and, since Security-Audit
     * S-1 (2026-08-18), [THREAD_DESCENDANTS_BYTE_BUDGET] (the actual rendered-body byte ceiling --
     * the first two caps alone turned out NOT to bound the rendered output tightly enough, see that
     * constant's KDoc).
     */
    private fun FlowContent.renderThreadDescendant(
        post: PublicPostView,
        baseUrl: String,
        lang: PublicLanguage,
    ) {
        article {
            h2 { a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s/${post.id}", lang = lang)) { +post.excerptTitle } }
            renderPostMeta(post = post, baseUrl = baseUrl, lang = lang)
            renderPostContent(post = post, lines = post.contentLines)
        }
    }

    /**
     * Welle V1.1.5 (Plan § 4.6): trägt seit dieser Welle auch den Melde-Link
     * (`$baseUrl/s/{id}/report`) -- damit erscheint der Link an Wurzelpost UND jedem gerenderten
     * Nachfahren (`renderThreadDescendant`) sowie in der Timeline-Zusammenfassung, jeder der drei
     * Aufrufer hat [baseUrl] bereits im Scope.
     */
    private fun FlowContent.renderPostMeta(
        post: PublicPostView,
        baseUrl: String,
        lang: PublicLanguage,
    ) {
        p {
            +post.authorDisplayName
            +" · "
            time {
                attributes["datetime"] = post.publishedAtIso
                +post.publishedAtHuman
            }
            +" · Gesamtgewicht ${post.totalWeightLtr} LTR"
            +" · "
            a(
                href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/s/${post.id}/report", lang = lang),
            ) { +"Diesen Beitrag melden" }
        }
    }

    /**
     * Welle V1.1.5 (Plan § 5.3): rendert [lines] als getombstonete, gedämpfte Kursivschrift
     * (`<em>`), wenn [PublicPostView.contentErased] gilt -- sonst wie bisher als normale Absätze.
     * Rein kosmetisch, die Datenminimierung ist bereits durch die Überschreibung von `content` in
     * der Datenbank erledigt (der Marker-Text selbst kommt unverändert aus [lines]).
     */
    private fun FlowContent.renderPostContent(
        post: PublicPostView,
        lines: List<String>,
    ) {
        if (post.contentErased) {
            lines.forEach { line -> p { em { +line } } }
        } else {
            lines.forEach { line -> p { +line } }
        }
    }

    /**
     * Title-safe excerpt: the first non-blank content line, truncated to [TITLE_MAX_LEN] -- never
     * blank (falls back to a generic label so `<title>`/`og:title` are never empty).
     */
    private val PublicPostView.excerptTitle: String
        get() = excerpt(maxLen = TITLE_MAX_LEN).ifBlank { "Beitrag" }

    private fun PublicPostView.excerpt(maxLen: Int): String {
        val firstLine = contentLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length <= maxLen) firstLine else firstLine.take(maxLen - 1).trimEnd() + "…"
    }

    /**
     * Security-Audit-Fund S-1 (2026-08-18), corrected under Runde-2 finding S2-1 (2026-08-18):
     * conservative UPPER-BOUND estimate (never an under-estimate) of how many UTF-8 bytes
     * [renderThreadDescendant] will emit for this node -- used by [postPage] to enforce
     * [THREAD_DESCENDANTS_BYTE_BUDGET] BEFORE actually rendering a node, since `kotlinx.html`'s
     * streaming builder has no way to measure or "undo" output once written.
     *
     * **S2-1 fix**: every user-controlled field is bounded by `length * [MAX_ESCAPED_BYTES_PER_CHAR]`
     * -- a STRICT upper bound valid for every possible character (see that constant's KDoc) -- instead
     * of the PREVIOUS `toByteArray(Charsets.UTF_8).size`, which measured the RAW input's byte size and
     * completely missed `kotlinx.html`'s HTML-escaping expansion on write (a `"` character alone turns
     * 1 raw byte into 6 rendered bytes). This is deliberately NOT byte-exact (that would require
     * rendering the fragment twice, once to measure and once to emit) -- it is a bound, chosen so the
     * true rendered size can never exceed the estimate, for ANY content, not just "realistic" content.
     * Content-Zeilen (`contentLines`), [excerptTitle] (derived from `contentLines`), and
     * [authorDisplayName] are all user-controlled and go through this multiplier.
     * [publishedAtHuman] is server-formatted (`"DD.MM.YYYY"`, see [PublicPostView] KDoc) but is
     * included via the same conservative multiplier anyway -- it costs nothing to be extra safe here.
     * [totalWeightLtr] is the one field that genuinely needs no escaping margin: it is always a
     * `BigDecimal.toPlainString()` result (digits, optional `-`/`.` only, see [PublicPostView] KDoc
     * "der Renderer rechnet nie") -- none of those characters are ever HTML-escaped, so its raw
     * `length` already equals its exact rendered byte count.
     *
     * This length-based approach also removes every `toByteArray()` allocation from the hot path
     * (Runde-2 finding S2-5) -- pure integer arithmetic, no per-node/per-line allocation.
     */
    private fun PublicPostView.estimatedRenderedByteSize(baseUrl: String): Int {
        val contentBytes =
            contentLines.sumOf { line -> line.length * MAX_ESCAPED_BYTES_PER_CHAR + PER_LINE_RENDER_OVERHEAD_BYTES }
        val titleBytes = excerptTitle.length * MAX_ESCAPED_BYTES_PER_CHAR
        val metaBytes =
            authorDisplayName.length * MAX_ESCAPED_BYTES_PER_CHAR +
                publishedAtHuman.length * MAX_ESCAPED_BYTES_PER_CHAR +
                totalWeightLtr.length
        return contentBytes + titleBytes + metaBytes + PER_POST_RENDER_OVERHEAD_BYTES + baseUrl.length
    }
}

/**
 * Was ein anonymer Besucher von einem Post zu sehen bekommt -- und NUR das. Dieses Modell hat
 * bewusst KEIN Feld für:
 *  - `authorMemberId` (X7: keine Member-UUIDs im öffentlichen HTML, sonst wird die Mitgliederliste
 *    über durchprobierte Autorenfilter erschließbar -- einen Autorenfilter gibt es im öffentlichen
 *    Pfad nicht und darf es nicht geben),
 *  - `authorFreeBalanceLtr` (Security-Fund S-1 -- der Wert ist im DTO für einen anonymen Leser
 *    ohnehin `null`, hier existiert er nicht einmal als Feld),
 *  - `directCommentCount`/`totalDescendantCount`/`boostCount` (X5: ein Zähler, der auch
 *    nicht-öffentliche Kinder mitzählte, machte deren Existenz aus einer Zahl ablesbar; das
 *    GESAMTGEWICHT zählt dagegen weiterhin alle Nachfahren, weil es die ökonomische Wahrheit ist --
 *    ohne veröffentlichten Zähler ist daraus nichts rückrechenbar),
 *  - `visibility`/`state`/`stateReason` (im öffentlichen Pfad per Konstruktion PUBLIC/VISIBLE).
 */
internal data class PublicPostView(
    val id: String,
    val depth: Int,
    val authorDisplayName: String,
    /** Plain Text, an Zeilenumbrüchen gesplittet (S7) -- jede Zeile wird als eigener Textknoten ausgegeben. */
    val contentLines: List<String>,
    /** Welle V1.1.5 -- `true` ⇒ [contentLines] ist der Tombstone-Marker, kein Nutzertext mehr (`SocialPostDto.contentErasedAt != null`). Rein kosmetisch (`<em>` statt `<p>`-Absatz). */
    val contentErased: Boolean = false,
    /** Bereits auf 2 Nachkommastellen formatiert -- der Renderer rechnet nie. */
    val totalWeightLtr: String,
    val ownWeightLtr: String,
    /** ISO-8601, für `<time datetime="...">`. */
    val publishedAtIso: String,
    /** Absolutes Datum, NIEMALS relativ ("vor 3 Tagen") -- siehe [SocialPublicHtml] KDoc Punkt 4 (ETag-Determinismus). */
    val publishedAtHuman: String,
)

internal data class PublicTimelineView(
    val posts: List<PublicPostView>,
    val page: Int,
    val hasNext: Boolean,
)

internal data class PublicThreadView(
    val root: PublicPostView,
    /** Präorder ohne die Wurzel, `depth` gefüllt. */
    val descendants: List<PublicPostView>,
    /** `true` ⇒ Hinweistext "Weitere Antworten werden hier nicht angezeigt", niemals stilles Abschneiden. */
    val truncated: Boolean,
)

/** Welle V1.1.5 (E-B) -- siehe [SocialPublicHtml.legallyRemovedPage] KDoc für das Datenminimierungs-Prinzip dieses View-Modells. */
internal data class PublicRemovalNoticeView(
    val postId: String,
    val reasonLines: List<String>,
    val removedAtIso: String,
    val removedAtHuman: String,
)
