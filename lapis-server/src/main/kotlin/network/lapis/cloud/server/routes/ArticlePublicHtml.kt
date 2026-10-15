package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.a
import kotlinx.html.article
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.unsafe
import network.lapis.cloud.server.articles.ArticleLead

/**
 * Welle V1.4.36 "Nachrichten-/Artikel-Modul, Folgewelle" -- the server-rendered, unauthenticated
 * public surface for a single published article (`registerArticlePublicRoutes`' `GET
 * /aktuelles/{slug}`). Since V1.9.130 it mirrors [EventPublicHtml]: two modes selected by [ArticlePageContext].
 *
 * - **STANDARD**: the full Lapis header and footer ([PublicChrome]), a wide layout (`body.article-wide`): title, meta line, cover
 *   (56rem) and a 42rem text column.
 * - **EMBED** (`?embed=1`): the slim, header-less variant for an `<iframe>` on a partner site; the frame links (all news, start page, legal
 *   links) leave the frame (`target="_top"`). Links inside the article text are decided by [network.lapis.cloud.server.articles.ArticleMarkdown]:
 *   every `http(s)` link opens a new window, except (V1.9.140) an absolute link to the instance's own host, which gets `_top` in this mode.
 *
 * The title appears exactly once (as `<h1>`). The excerpt is shown as a lead paragraph only if the body does not already start with it
 * ([ArticleLead]).
 *
 * **[articleBody] holds the ONE deliberate `unsafe { }` call in this entire file** -- see [articlePage]'s own
 * KDoc for why it is safe. Every other value (title, excerpt, byline, cover URL, branding strings)
 * flows through `kotlinx.html`'s ordinary, auto-escaping text-node/attribute API, same discipline
 * [EventPublicHtml]/[SocialPublicHtml] already establish.
 *
 * **Carries no internal id and no author identity -- except the byline.** Design-Team decision "keine Namen nach aussen"
 * (see `ArticleReviewDto` KDoc) still holds; since V1.9.105 the page shows the article's byline if one is set. A personal name appears
 * ONLY because the author consented (GDPR Art. 6(1)(a), revocable); otherwise it is an organization label or an imported legacy line.
 * It is plain escaped text (no link, no `<meta name="author">`: that would be a further distribution to crawlers the author did not agree to).
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
        /** Welle V1.9.105 -- the author line (consented personal name, organization label or imported line); `null` = nobody is named. Rendered as escaped text, no link, no chip, no `<meta name="author">`. */
        val byline: String? = null,
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
        ctx: ArticlePageContext,
        view: View,
    ): String =
        skeleton(
            ctx = ctx,
            heading = view.title,
            currentPath = "/aktuelles/${view.slug}",
            canonicalPath = "/aktuelles/${view.slug}",
            view = view,
        ) {
            if (ctx.isEmbed) {
                h1(classes = "article-title") { +view.title }
                metaLine(view)
                cover(view)
                articleLead(view)
                articleBody(view)
            } else {
                header(classes = "article-column") {
                    h1(classes = "article-title") { +view.title }
                    metaLine(view)
                }
                cover(view)
                div(classes = "article-column") {
                    articleLead(view)
                    articleBody(view)
                    p(classes = "article-back") {
                        a(
                            href = PublicChrome.languageUrl(baseUrl = ctx.baseUrl, currentPath = "/aktuelles", lang = ctx.lang),
                        ) { +"← Alle Neuigkeiten" }
                    }
                }
            }
        }

    fun notFoundPage(ctx: ArticlePageContext): String =
        errorPage(ctx = ctx, heading = "Nicht gefunden", text = "Diese Seite existiert nicht oder ist nicht öffentlich zugänglich.")

    fun tooManyRequestsPage(ctx: ArticlePageContext): String =
        errorPage(ctx = ctx, heading = "Zu viele Anfragen", text = "Bitte versuchen Sie es später erneut.")

    fun serverErrorPage(ctx: ArticlePageContext): String =
        errorPage(ctx = ctx, heading = "Fehler", text = "Es ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut.")

    /** Error pages never contain the slug (404 neutrality: byte-identical for every unknown/unpublished/malformed slug), so `currentPath` is the fixed overview path. */
    private fun errorPage(
        ctx: ArticlePageContext,
        heading: String,
        text: String,
    ): String =
        skeleton(ctx = ctx, heading = heading, currentPath = "/aktuelles", canonicalPath = null, view = null) {
            if (ctx.isEmbed) {
                h1(classes = "article-title") { +heading }
                p { +text }
            } else {
                div(classes = "article-column") {
                    h1(classes = "article-title") { +heading }
                    p { +text }
                }
            }
        }

    private fun HEAD.renderArticleOgMetas(view: View) {
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

    /** "Von <byline> · <date>" -- no "Veröffentlicht am", no organization name (the header already carries the brand). */
    private fun FlowContent.metaLine(view: View) {
        p(classes = "lapis-article-meta") {
            val byline = view.byline
            if (byline != null) {
                span(classes = "article-byline") { +"Von $byline" }
                +" · "
            }
            time {
                attributes["datetime"] = view.publishedAtIso
                +germanDate(view.publishedAt)
            }
        }
    }

    /** Decorative (the title already says it): empty alt. */
    private fun FlowContent.cover(view: View) {
        if (view.coverImageUrl != null) {
            img(src = view.coverImageUrl, alt = "", classes = "lapis-article-cover")
        }
    }

    private fun FlowContent.articleLead(view: View) {
        if (ArticleLead.shouldShow(excerpt = view.excerpt, renderedBodyHtml = view.renderedBodyHtml)) {
            p(classes = "lapis-article-lead") { +view.excerpt }
        }
    }

    private fun FlowContent.articleBody(view: View) {
        article(classes = "lapis-article-body") {
            unsafe { +view.renderedBodyHtml }
        }
    }

    private fun skeleton(
        ctx: ArticlePageContext,
        heading: String,
        currentPath: String,
        canonicalPath: String?,
        view: View?,
        content: FlowContent.() -> Unit,
    ): String =
        createHTML(prettyPrint = false).html {
            attributes["lang"] = if (ctx.isEmbed) "de" else ctx.lang.code
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"$heading – ${ctx.brandTitle}" }
                link(rel = "stylesheet", href = "/s/assets/style.css")
                if (ctx.isEmbed) {
                    meta(name = "robots", content = "noindex")
                    if (canonicalPath != null) link(rel = "canonical", href = "${ctx.baseUrl}$canonicalPath")
                } else if (canonicalPath != null) {
                    link(
                        rel = "canonical",
                        href = PublicChrome.languageUrl(baseUrl = ctx.baseUrl, currentPath = canonicalPath, lang = ctx.lang),
                    )
                    with(PublicChrome) { renderHreflangAlternates(baseUrl = ctx.baseUrl, currentPath = canonicalPath) }
                }
                if (view != null) renderArticleOgMetas(view)
            }
            if (ctx.isEmbed) {
                body {
                    div("embed-page article-embed") {
                        content()
                        // Site-relative, `target="_top"`: leaves the iframe; neither target has an embed mode.
                        p { a(href = "/aktuelles", target = "_top") { +"Alle Neuigkeiten →" } }
                        p { a(href = "/", target = "_top") { +"Zur Startseite" } }
                        p(classes = "section-note") {
                            a(href = "/impressum", target = "_top") { +"Impressum" }
                            +" · "
                            a(href = "/datenschutz", target = "_top") { +"Datenschutz" }
                        }
                    }
                }
            } else {
                body(classes = "has-chrome article-wide") {
                    with(PublicChrome) {
                        renderChrome(
                            lang = ctx.lang,
                            active = PublicChrome.NavTarget.ARTICLES,
                            baseUrl = ctx.baseUrl,
                            branding = ctx.branding,
                            currentPath = currentPath,
                            nav = ctx.nav,
                        )
                    }
                    main {
                        attributes["id"] = "main"
                        // The article content is German in every UI language.
                        div {
                            attributes["lang"] = "de"
                            content()
                        }
                    }
                    with(PublicChrome) { renderPublicFooter(lang = ctx.lang, baseUrl = ctx.baseUrl, branding = ctx.branding) }
                }
            }
        }

    // germanDate/germanTime moved to GermanDateFormat.kt (Welle V1.9.11) -- see that file's own KDoc.
}
