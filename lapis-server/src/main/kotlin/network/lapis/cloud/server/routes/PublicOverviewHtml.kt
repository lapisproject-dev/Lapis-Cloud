package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import kotlinx.html.ul
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * Welle V1.9.11 "Öffentliche Icon-Navigation" -- server-rendered public overview pages for the two
 * new, OPTIONAL chrome tabs: `GET /aktuelles` ([articlesPage]) and `GET /veranstaltungen`
 * ([eventsPage]). Same five non-negotiable rendering-safety properties every other public-HTML file
 * in this package establishes ([SocialPublicHtml] class KDoc) -- escape-only `kotlinx.html` API, no
 * request-time-dependent output, identical output for crawler and human.
 *
 * **Datensparsamkeit** (same posture as [PublicLandingHtml]): only title, slug, and the publish/start
 * date/time -- no excerpt, no author, no location, no capacity. Both list items link to the
 * respective DETAIL page ([ArticlePublicHtml]/`EventPublicHtml`), which is the one place that shows
 * more.
 *
 * **Entries are ALWAYS rendered `lang="de"`** with a German date/time (via [germanDate]/[germanTime]),
 * regardless of the surrounding chrome's [PublicLanguage] -- both `/aktuelles/{slug}` and
 * `/veranstaltung/{slug}` are themselves single-language German pages (see [ArticlePublicHtml]/
 * `EventPublicHtml` class KDoc), so this overview's list entries match what the reader lands on.
 * Only the CHROME (nav, `<h1>`, empty-state text) is translated.
 *
 * **No pagination, no images, no sitemap entry, capped at [PublicArticlesOverviewRoutes.MAX_ARTICLES]/
 * [PublicEventsOverviewRoutes.MAX_EVENTS]** -- documented limitation of this wave, not a defect.
 */
internal object PublicOverviewHtml {
    /** One published article, as shown on `GET /aktuelles` -- see class KDoc "Datensparsamkeit". */
    data class ArticleItem(
        val title: String,
        val slug: String,
        val publishedAt: LocalDateTime,
    )

    /** One upcoming, public event, as shown on `GET /veranstaltungen` -- see class KDoc "Datensparsamkeit". */
    data class EventItem(
        val title: String,
        val slug: String,
        val startsAt: LocalDateTime,
    )

    fun articlesPage(
        items: List<ArticleItem>,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        nav: PublicNavAvailability,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/aktuelles",
            heading = strings.navArticles,
            active = PublicChrome.NavTarget.ARTICLES,
            nav = nav,
        ) {
            if (items.isEmpty()) {
                p { +strings.articlesEmpty }
            } else {
                ul(classes = "rank-list") {
                    attributes["lang"] = "de"
                    items.forEach { item ->
                        li {
                            a(href = "$baseUrl/aktuelles/${item.slug}") {
                                attributes["hreflang"] = "de"
                                +item.title
                            }
                            span(classes = "section-note") { +" · ${germanDate(item.publishedAt)}" }
                        }
                    }
                }
            }
        }
    }

    fun eventsPage(
        items: List<EventItem>,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        nav: PublicNavAvailability,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/veranstaltungen",
            heading = strings.navEvents,
            active = PublicChrome.NavTarget.EVENTS,
            nav = nav,
        ) {
            if (items.isEmpty()) {
                p { +strings.eventsEmpty }
            } else {
                ul(classes = "rank-list") {
                    attributes["lang"] = "de"
                    items.forEach { item ->
                        li {
                            a(href = "$baseUrl/veranstaltung/${item.slug}") {
                                attributes["hreflang"] = "de"
                                +item.title
                            }
                            span(classes = "section-note") {
                                +" · ${germanDate(item.startsAt)}, ${germanTime(item.startsAt)} Uhr"
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The shared page shell of every overview page. `internal` since Welle V1.9.20 -- the
     * [PublicProfilesHtml] pages reuse it; [robots] is `index,follow` for the article/event lists
     * and `noindex,follow` for the pages that show PERSONAL data under a revocable consent.
     */
    internal fun skeleton(
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        currentPath: String,
        heading: String,
        active: PublicChrome.NavTarget,
        nav: PublicNavAvailability,
        robots: String = "index,follow",
        content: kotlinx.html.FlowContent.() -> Unit,
    ): String {
        val canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title { +"$heading – ${branding.title}" }
                meta(name = "robots", content = robots)
                link(rel = "canonical", href = canonicalUrl)
                link(rel = "stylesheet", href = "/s/assets/style.css")
                with(PublicChrome) { renderHreflangAlternates(baseUrl = baseUrl, currentPath = currentPath) }
                meta(content = heading) { attributes["property"] = "og:title" }
                meta(content = canonicalUrl) { attributes["property"] = "og:url" }
                meta(content = "website") { attributes["property"] = "og:type" }
                meta(content = "Lapis Cloud") { attributes["property"] = "og:site_name" }
                meta(name = "twitter:card", content = "summary")
            }
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = active,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                        nav = nav,
                    )
                }
                main {
                    attributes["id"] = "main"
                    h1 { +heading }
                    content()
                }
                with(PublicChrome) { renderPublicFooter(lang = lang, baseUrl = baseUrl, branding = branding) }
            }
        }
    }
}
