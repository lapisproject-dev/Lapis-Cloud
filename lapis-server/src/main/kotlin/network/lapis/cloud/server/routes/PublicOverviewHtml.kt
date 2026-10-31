package network.lapis.cloud.server.routes

import kotlinx.datetime.LocalDateTime
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import kotlinx.html.ul
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.events.series.SeriesTeaser
import network.lapis.cloud.server.partner.PartnerEventView
import network.lapis.cloud.server.theme.publicStylesheetLinks

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
 * more. V1.9.140: the chosen UI language is carried onto the detail pages (`?lang=en`, via [PublicChrome.languageUrl]; nothing is
 * appended for the default language), so the detail page's chrome stays in the language the visitor picked. The page body stays German.
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
        /** V1.9.243 -- non-null when this entry stands for a whole series (its next occurrence is the entry); `null` for a single event. */
        val series: SeriesTeaser? = null,
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
                            a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/aktuelles/${item.slug}", lang = lang)) {
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

    /** Which events `GET /veranstaltungen` lists (V1.9.245): everything (default) or only this server's own. */
    enum class EventSource { ALL, OWN }

    fun eventsPage(
        items: List<EventItem>,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        nav: PublicNavAvailability,
        partnerEvents: List<PartnerEventView> = emptyList(),
        source: EventSource = EventSource.ALL,
        showFilter: Boolean = false,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        val entries = mergedEntries(items = items, partnerEvents = partnerEvents)
        return skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/veranstaltungen",
            heading = strings.navEvents,
            active = PublicChrome.NavTarget.EVENTS,
            nav = nav,
            robots = if (source == EventSource.OWN) "noindex,follow" else "index,follow",
        ) {
            if (showFilter) sourceFilter(baseUrl = baseUrl, lang = lang, source = source)
            if (entries.isEmpty()) {
                p { +(if (source == EventSource.OWN) "Derzeit keine eigenen Veranstaltungen." else strings.eventsEmpty) }
            } else {
                ul(classes = "rank-list") {
                    attributes["lang"] = "de"
                    entries.forEach { entry ->
                        when (entry) {
                            is OverviewEntry.Partner -> partnerEventItem(entry.view)
                            is OverviewEntry.Own -> ownEventItem(item = entry.item, baseUrl = baseUrl, lang = lang)
                        }
                    }
                }
            }
        }
    }

    /** One entry of the merged list: an own event (or series) or a foreign event. */
    internal sealed interface OverviewEntry {
        val start: LocalDateTime

        data class Own(
            val item: EventItem,
        ) : OverviewEntry {
            override val start: LocalDateTime get() = item.startsAt
        }

        data class Partner(
            val view: PartnerEventView,
        ) : OverviewEntry {
            override val start: LocalDateTime get() = view.startsAtWall
        }
    }

    /** Sorted by `(start, own before partner, origin host, event id)`; the sort is stable, so own events keep their own order. */
    internal fun mergedEntries(
        items: List<EventItem>,
        partnerEvents: List<PartnerEventView>,
    ): List<OverviewEntry> =
        (items.map { OverviewEntry.Own(it) } + partnerEvents.map { OverviewEntry.Partner(it) }).sortedWith(
            compareBy<OverviewEntry>(
                { it.start },
                { if (it is OverviewEntry.Partner) 1 else 0 },
                { (it as? OverviewEntry.Partner)?.view?.originHost ?: "" },
                { (it as? OverviewEntry.Partner)?.view?.remoteEventId ?: "" },
            ),
        )

    private fun kotlinx.html.FlowContent.sourceFilter(
        baseUrl: String,
        lang: PublicLanguage,
        source: EventSource,
    ) {
        nav(classes = "jump event-source-filter") {
            attributes["aria-label"] = "Herkunft der Veranstaltungen"
            attributes["lang"] = "de"
            a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/veranstaltungen", lang = lang)) {
                if (source == EventSource.ALL) attributes["aria-current"] = "page"
                +"Alle"
            }
            a(href = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/veranstaltungen?quelle=eigene", lang = lang)) {
                if (source == EventSource.OWN) attributes["aria-current"] = "page"
                +"Nur eigene"
            }
        }
    }

    private fun UL.ownEventItem(
        item: EventItem,
        baseUrl: String,
        lang: PublicLanguage,
    ) {
        li {
            a(
                href =
                    PublicChrome.languageUrl(
                        baseUrl = baseUrl,
                        currentPath = "/veranstaltung/${item.slug}",
                        lang = lang,
                    ),
            ) {
                attributes["hreflang"] = "de"
                +item.title
            }
            val series = item.series
            if (series != null) {
                repeatMark(tip = series.repeatSentence.replaceFirstChar { it.uppercase() })
            }
            span(classes = "section-note") {
                +" · ${germanDate(item.startsAt)}, ${germanTime(item.startsAt)} Uhr"
            }
            if (series != null) {
                span(classes = "section-note series-note") {
                    +"${series.repeatSentence.replaceFirstChar { it.uppercase() }} · "
                    if (series.upcomingCount > 1) {
                        a(
                            href =
                                PublicChrome.languageUrl(
                                    baseUrl = baseUrl,
                                    currentPath = "/veranstaltung/${item.slug}",
                                    lang = lang,
                                ) + "#weitere-termine",
                        ) {
                            attributes["hreflang"] = "de"
                            +"Alle Termine (${series.upcomingCount})"
                        }
                    } else {
                        +"Letzter Termin dieser Serie"
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
                publicStylesheetLinks()
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
