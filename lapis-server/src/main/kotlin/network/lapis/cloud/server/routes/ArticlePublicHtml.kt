package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import kotlinx.html.unsafe
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- the server-rendered, unauthenticated
 * public surface for a single published article (`registerArticlePublicRoutes`' `GET
 * /aktuelles/{slug}`). Structurally a close mirror of [EventPublicHtml]'s own `skeleton` (a self-
 * contained `<body>`, no [PublicChrome] multi-language chrome -- this page is single-language
 * German, same as every `/veranstaltung` page): a skip-link/nav bar and eight-language switcher
 * would be disproportionate ceremony for a single article's public read surface.
 *
 * **[body] is the ONE deliberate `unsafe { }` call in this entire file** -- see [articlePage]'s own
 * KDoc for why it is safe. Every other value (title, excerpt, cover alt text, branding strings)
 * flows through `kotlinx.html`'s ordinary, auto-escaping text-node/attribute API, same discipline
 * [EventPublicHtml]/[SocialPublicHtml] already establish.
 *
 * **Carries no author name and no internal id** -- Design-Team decision "keine Namen nach aussen"
 * (see `ArticleReviewDto` KDoc), applied here too: nothing in this page's HTML lets a reader learn
 * WHO wrote the article, only what it says.
 */
internal object ArticlePublicHtml {
    /** One published article's publicly-safe view -- assembled by the route handler OUTSIDE any transaction (Slowloris rule, see [registerArticlePublicRoutes] KDoc). */
    data class View(
        val title: String,
        val slug: String,
        val excerpt: String,
        /** Server-rendered HTML from `ArticleMarkdown.render` -- ALREADY sanitized (HTML escaped, images alt-text-only, link-scheme allowlisted, headings shifted to h2-h4) before this class ever sees it. See [articlePage] KDoc "unsafe". */
        val renderedBodyHtml: String,
        val coverImageUrl: String?,
        val publishedAt: LocalDateTime,
        /** ISO-8601 with an explicit UTC offset, for `article:published_time` -- see [articlePage]'s own meta-tag block. */
        val publishedAtIso: String,
    )

    /**
     * **Why [renderedBodyHtml] is inserted via `unsafe { +view.renderedBodyHtml }` rather than
     * `kotlinx.html`'s normal escaping text API**: that HTML is not raw author input -- it is the
     * OUTPUT of `network.lapis.cloud.server.articles.ArticleMarkdown.render`, the exact same
     * sanitizing pipeline the board's own review screen (`ArticleReviewDto.renderedBodyHtml`)
     * already renders unescaped in the SPA. `ArticleMarkdown.render`'s own KDoc documents the
     * sanitization it performs (raw HTML characters escaped before Markdown parsing, so a
     * `<script>` in the source can never become a live tag; images degrade to alt-text only; link
     * targets are scheme-allowlisted; headings are shifted to h2-h4 so no article can inject an
     * `<h1>` that visually impersonates this page's own title). Escaping it a SECOND time here
     * would double-encode every entity the renderer already produced (`&amp;lt;` instead of
     * `&lt;`), which is exactly the "raw XML entities in text" defect the renderer-validation
     * routine's own checklist flags.
     */
    fun articlePage(
        branding: ResolvedBranding,
        baseUrl: String,
        view: View,
    ): String =
        skeleton(branding = branding, heading = view.title, view = view) {
            if (view.coverImageUrl != null) {
                img(src = view.coverImageUrl, alt = view.title, classes = "lapis-article-cover")
            }
            h1 { +view.title }
            p(classes = "lapis-article-meta") {
                +"Veröffentlicht am ${germanDate(view.publishedAt)} · ${branding.title}"
            }
            p(classes = "lapis-article-lead") { +view.excerpt }
            article(classes = "lapis-article-body") {
                unsafe { +view.renderedBodyHtml }
            }
            if (branding.websiteUrl != null) {
                p { a(href = branding.websiteUrl) { +"Alle Neuigkeiten →" } }
            }
        }

    fun notFoundPage(branding: ResolvedBranding): String =
        plainSkeleton(branding = branding, heading = "Nicht gefunden") {
            p { +"Diese Seite existiert nicht oder ist nicht öffentlich zugänglich." }
        }

    fun tooManyRequestsPage(branding: ResolvedBranding): String =
        plainSkeleton(branding = branding, heading = "Zu viele Anfragen") {
            p { +"Bitte versuchen Sie es später erneut." }
        }

    fun serverErrorPage(branding: ResolvedBranding): String =
        plainSkeleton(branding = branding, heading = "Fehler") {
            p { +"Es ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut." }
        }

    private fun plainSkeleton(
        branding: ResolvedBranding,
        heading: String,
        content: FlowContent.() -> Unit,
    ): String = skeleton(branding = branding, heading = heading, view = null, content = content)

    private fun skeleton(
        branding: ResolvedBranding,
        heading: String,
        view: View?,
        content: FlowContent.() -> Unit,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = "de"
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"$heading – ${branding.title}" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
                if (view != null) {
                    meta {
                        attributes["property"] = "og:title"
                        attributes["content"] = view.title
                    }
                    meta {
                        attributes["property"] = "og:description"
                        attributes["content"] = view.excerpt
                    }
                    meta {
                        attributes["property"] = "og:type"
                        attributes["content"] = "article"
                    }
                    meta {
                        attributes["property"] = "article:published_time"
                        attributes["content"] = view.publishedAtIso
                    }
                    if (view.coverImageUrl != null) {
                        meta {
                            attributes["property"] = "og:image"
                            attributes["content"] = view.coverImageUrl
                        }
                    }
                }
            }
            body {
                div("embed-page") {
                    if (branding.logoAvailable) {
                        img(src = "/api/branding/logo", alt = branding.title, classes = "chrome-logo")
                    } else {
                        span { +branding.title }
                    }
                    h2 { +heading }
                    content()
                    p { a(href = "/") { +"Zur Startseite" } }
                    p(classes = "section-note") {
                        a(href = "/impressum") { +"Impressum" }
                        +" · "
                        a(href = "/datenschutz") { +"Datenschutz" }
                    }
                }
            }
        }

    /** German day/month/year, no timezone conversion -- [LocalDateTime] is already wall-clock in the server's own default zone (same convention every other rendered German date in this codebase uses, e.g. `EventPublicHtml`'s bare `${view.startsAt}` -- this one is merely nicer-looking, not a different semantic). */
    private val GERMAN_MONTHS =
        listOf(
            "Januar",
            "Februar",
            "März",
            "April",
            "Mai",
            "Juni",
            "Juli",
            "August",
            "September",
            "Oktober",
            "November",
            "Dezember",
        )

    private fun germanDate(dt: LocalDateTime): String = "${dt.dayOfMonth}. ${GERMAN_MONTHS[dt.monthNumber - 1]} ${dt.year}"
}
