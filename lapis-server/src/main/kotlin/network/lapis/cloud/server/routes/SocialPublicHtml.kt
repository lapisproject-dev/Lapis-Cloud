package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.body
import kotlinx.html.footer
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.stream.createHTML
import kotlinx.html.time
import kotlinx.html.title

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
    ): String {
        val pageTitle = if (view.page <= 1) "Soziales Netzwerk – Lapis Cloud" else "Soziales Netzwerk – Seite ${view.page} – Lapis Cloud"
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            renderHead(
                pageTitle = pageTitle,
                description = "Öffentliche Beiträge im sozialen Netzwerk von Lapis Cloud, sortiert nach Gesamtgewicht.",
                canonicalUrl = timelineCanonicalUrl(baseUrl = baseUrl, page = view.page),
                robots = if (view.page <= 1) "index,follow" else "noindex,follow",
                ogType = "website",
            )
            body {
                header { h1 { +"Soziales Netzwerk" } }
                main {
                    if (view.posts.isEmpty()) {
                        p { +"Noch keine öffentlichen Beiträge." }
                    } else {
                        view.posts.forEach { post -> renderTimelinePostSummary(post = post, baseUrl = baseUrl) }
                    }
                    nav {
                        if (view.page > 1) {
                            a(href = timelineCanonicalUrl(baseUrl = baseUrl, page = view.page - 1)) { +"← Vorherige Seite" }
                        }
                        if (view.hasNext) {
                            a(href = timelineCanonicalUrl(baseUrl = baseUrl, page = view.page + 1)) { +"Nächste Seite →" }
                        }
                    }
                }
                footer { p { +"Lapis Cloud" } }
            }
        }
    }

    fun postPage(
        view: PublicThreadView,
        baseUrl: String,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            renderHead(
                pageTitle = "${view.root.excerptTitle} – Lapis Cloud",
                description = view.root.excerpt(maxLen = DESCRIPTION_MAX_LEN),
                canonicalUrl = "$baseUrl/s/${view.root.id}",
                robots = "index,follow",
                ogType = "article",
            )
            body {
                nav { a(href = "$baseUrl/s") { +"← Zur Timeline" } }
                article {
                    h1 { +view.root.excerptTitle }
                    renderPostMeta(post = view.root)
                    view.root.contentLines.forEach { line -> p { +line } }
                }
                if (view.descendants.isNotEmpty() || view.truncated) {
                    section {
                        h2 { +"Antworten" }
                        // Security-Audit-Fund S-1 (2026-08-18): render descendants until the byte
                        // budget is exhausted, THEN stop -- see THREAD_DESCENDANTS_BYTE_BUDGET KDoc.
                        // `byteBudgetTruncated` deliberately ORs into the SAME notice as
                        // `view.truncated` (the row-count cap from SocialReadPipeline) below: a
                        // reader cannot tell, and does not need to be able to tell, which of the two
                        // independent caps stopped the list -- both mean exactly the same thing
                        // ("more replies exist, not shown here").
                        var bytesUsed = 0
                        var byteBudgetTruncated = false
                        for (node in view.descendants) {
                            val estimate = node.estimatedRenderedByteSize(baseUrl = baseUrl)
                            if (bytesUsed + estimate > THREAD_DESCENDANTS_BYTE_BUDGET) {
                                byteBudgetTruncated = true
                                break
                            }
                            renderThreadDescendant(post = node, baseUrl = baseUrl)
                            bytesUsed += estimate
                        }
                        if (view.truncated || byteBudgetTruncated) {
                            p { +"Weitere Antworten werden hier nicht angezeigt." }
                        }
                    }
                }
                footer { p { +"Lapis Cloud" } }
            }
        }

    /** `robots` is `noindex` (not `noindex,follow`) -- there is nothing on a 404 page worth a crawler following. */
    fun notFoundPage(baseUrl: String): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            renderHead(
                pageTitle = "Nicht gefunden – Lapis Cloud",
                description = "Dieser Beitrag ist nicht (mehr) öffentlich verfügbar.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body {
                h1 { +"Nicht gefunden" }
                p { +"Dieser Beitrag ist nicht (mehr) öffentlich verfügbar." }
                a(href = "$baseUrl/s") { +"Zur Timeline" }
            }
        }

    fun tooManyRequestsPage(baseUrl: String): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            renderHead(
                pageTitle = "Zu viele Anfragen – Lapis Cloud",
                description = "Bitte versuchen Sie es in Kürze erneut.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body {
                h1 { +"Zu viele Anfragen" }
                p { +"Bitte versuchen Sie es in Kürze erneut." }
                a(href = "$baseUrl/s") { +"Zur Timeline" }
            }
        }

    /**
     * M1-Fix (Review-Runde 1): the generic 500 page every public handler falls back to via
     * `SocialPublicRoutes.withPublicErrorHandling`. Deliberately a FIXED string with no interpolated
     * exception message/stack trace anywhere -- an internal error detail is never something an
     * anonymous, unauthenticated visitor should see (information disclosure), and it would also
     * break this file's own determinism guarantee (class KDoc point 4) if it varied per failure.
     * `robots` is `noindex` for the same reason as [notFoundPage] -- nothing here is worth a crawler
     * following.
     */
    fun serverErrorPage(baseUrl: String): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            renderHead(
                pageTitle = "Interner Fehler – Lapis Cloud",
                description = "Bei der Verarbeitung dieser Anfrage ist ein Fehler aufgetreten.",
                canonicalUrl = null,
                robots = "noindex",
                ogType = null,
            )
            body {
                h1 { +"Interner Fehler" }
                p { +"Bei der Verarbeitung dieser Anfrage ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut." }
                a(href = "$baseUrl/s") { +"Zur Timeline" }
            }
        }

    private fun timelineCanonicalUrl(
        baseUrl: String,
        page: Int,
    ): String = if (page <= 1) "$baseUrl/s" else "$baseUrl/s?page=$page"

    private fun HTML.renderHead(
        pageTitle: String,
        description: String,
        canonicalUrl: String?,
        robots: String,
        ogType: String?,
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
    ) {
        article {
            h2 { a(href = "$baseUrl/s/${post.id}") { +post.excerptTitle } }
            renderPostMeta(post = post)
            post.contentLines.take(SUMMARY_LINE_COUNT).forEach { line -> p { +line } }
            if (post.contentLines.size > SUMMARY_LINE_COUNT) {
                p(classes = "notice") {
                    +"Gekürzt — "
                    a(href = "$baseUrl/s/${post.id}") { +"vollständigen Beitrag ansehen" }
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
    ) {
        article {
            h2 { a(href = "$baseUrl/s/${post.id}") { +post.excerptTitle } }
            renderPostMeta(post = post)
            post.contentLines.forEach { line -> p { +line } }
        }
    }

    private fun FlowContent.renderPostMeta(post: PublicPostView) {
        p {
            +post.authorDisplayName
            +" · "
            time {
                attributes["datetime"] = post.publishedAtIso
                +post.publishedAtHuman
            }
            +" · Gesamtgewicht ${post.totalWeightLtr} LTR"
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
