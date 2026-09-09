package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.title
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.shared.domain.CommitteeRole

/**
 * V1.3.0 "Öffentliche Transparenz-Startseite" (`GET /transparenz`) -- a SECOND unauthenticated
 * HTML-rendering surface, sibling to [SocialPublicHtml]. Same five non-negotiable properties that
 * file's own class KDoc lists apply here verbatim, restated for this file:
 *
 * 1. **The HTML-escape-bypassing `kotlinx.html` API is never used anywhere in this file** -- every
 *    user-controlled string (a member's `display_name`) reaches the output exclusively through
 *    `kotlinx.html`'s ordinary text-node (`+"..."`) API, which escapes automatically. Enforced by
 *    the same `SocialPublicHtmlTest` T6 source-text scan, extended to cover this file.
 * 2. **No `href`/`src` built from user-controlled data** -- every link here is either `baseUrl`-
 *    relative to a fixed path or absent entirely (this page has no per-member detail links at all,
 *    X7: no member UUIDs anywhere in this output).
 * 3. **[baseUrl] is always [network.lapis.cloud.server.federation.FederationConfig.publicBaseUrl]**,
 *    enforced by the caller ([PublicTransparencyRoutes]), never derived from the request `Host`.
 * 4. **No request-time-dependent output** beyond the domain data itself -- the ETag/304 mechanism
 *    in [PublicTransparencyRoutes] depends on two calls with identical input producing a
 *    byte-identical body.
 * 5. **Identical output for a crawler and a human** -- no `User-Agent` sniffing.
 *
 * Uses the SAME stylesheet as `/s` (`/s/assets/style.css`, [SocialPublicHtml.STYLESHEET]) --
 * extended with a handful of new classes for this page's stat strip/ranking rows, never a second
 * inline `<style>` block (same CSP `style-src 'self'` reasoning [SocialPublicRoutes]' own KDoc
 * documents for `/s`).
 */
internal object PublicTransparencyHtml {
    fun page(
        view: PublicTransparencyView,
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
        val currentPath = "/transparenz"
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderHead(baseUrl = baseUrl, branding = branding, lang = lang)
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = PublicChrome.NavTarget.TRANSPARENCY,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                    )
                }
                main {
                    attributes["id"] = "main"
                    h1 { +strings.transparencyH1 }
                    nav(classes = "jump") {
                        a(href = "#kennzahlen") { +strings.jumpStats }
                        a(href = "#vorstand") { +strings.jumpBoard }
                        a(href = "#beitraege") { +strings.jumpPosts }
                        if (view.ltr != null) a(href = "#ltr") { +strings.jumpLtr }
                        if (view.donations != null) a(href = "#spenden") { +strings.jumpDonors }
                    }
                    renderStats(stats = view.stats, strings = strings)
                    renderBoard(board = view.board, strings = strings)
                    renderTopPosts(posts = view.topPosts, baseUrl = baseUrl, lang = lang, strings = strings)
                    view.ltr?.let { renderRankingSection(id = "ltr", title = strings.topLtrHolders, unit = "LTR", rankingSection = it) }
                    view.donations?.let {
                        renderRankingSection(
                            id = "spenden",
                            title = strings.topDonorsFormat.format(view.donationYear),
                            unit = "€",
                            rankingSection = it,
                        )
                    }
                }
                with(PublicChrome) { renderPublicFooter(lang = lang, baseUrl = baseUrl, branding = branding) }
            }
        }
    }

    private fun HTML.renderHead(
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
    ) {
        val strings = PublicChrome.stringsFor(lang)
        val pageTitle = "${strings.transparencyH1} – ${branding.title}"
        val description = "${strings.jumpStats}, ${strings.jumpBoard}, ${strings.topPosts} · ${branding.title}"
        val canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = "/transparenz", lang = lang)
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +pageTitle }
            meta(name = "description", content = description)
            // Security-Fix (Review): NICHT "index,follow". Die Seite enthält -- ausschließlich mit
            // wirksamer, jederzeit widerrufbarer Einwilligung (siehe
            // PublicRankingConsentDisclaimer) -- Anzeigenamen mit exaktem freien LTR-Guthaben bzw.
            // exakter Jahres-Spendensumme in den Ranglisten-Abschnitten. Der Einwilligungstext
            // verspricht, dass ein Widerruf den Namen binnen 60 Sekunden (der Cache-Zeit der Seite)
            // von der öffentlichen Liste entfernt -- diese Zusage kann die Route strukturell nicht
            // halten, sobald eine Suchmaschine die Seite indexiert oder ein Archivdienst (z. B.
            // archive.org) sie dauerhaft speichert. "noindex,follow" (statt "index,follow") plus
            // das begleitende "Disallow: /transparenz" in robots.txt (siehe SocialPublicRoutes)
            // hält Crawler grundsätzlich fern, ohne interne Links (z. B. zu /s/{postId}) zu
            // entwerten -- Kennzahlen/Vorstand/Beiträge bleiben über /s bzw. andere indexierte
            // Seiten weiterhin auffindbar.
            meta(name = "robots", content = "noindex,follow")
            link(rel = "canonical", href = canonicalUrl)
            link(rel = "stylesheet", href = "/s/assets/style.css")
            meta(content = pageTitle) { attributes["property"] = "og:title" }
            meta(content = description) { attributes["property"] = "og:description" }
            meta(content = canonicalUrl) { attributes["property"] = "og:url" }
            meta(content = "website") { attributes["property"] = "og:type" }
            meta(content = "Lapis Cloud") { attributes["property"] = "og:site_name" }
            meta(name = "twitter:card", content = "summary")
        }
    }

    private fun FlowContent.renderStats(
        stats: PublicTransparencyStats,
        strings: PublicUiStrings,
    ) {
        section {
            attributes["id"] = "kennzahlen"
            h2 { +strings.jumpStats }
            div(classes = "stats") {
                statTile(value = stats.activeMemberCount.toString(), label = strings.statMembers)
                statTile(value = "${stats.mintedLtrTotal} LTR", label = strings.statLtr)
                statTile(value = stats.publicPostCount.toString(), label = strings.statPosts)
            }
        }
    }

    private fun FlowContent.statTile(
        value: String,
        label: String,
    ) {
        div(classes = "stat") {
            div(classes = "stat-value") { +value }
            div(classes = "stat-label") { +label }
        }
    }

    private fun FlowContent.renderBoard(
        board: List<PublicBoardMemberRow>,
        strings: PublicUiStrings,
    ) {
        section {
            attributes["id"] = "vorstand"
            h2 { +strings.jumpBoard }
            if (board.isEmpty()) {
                p { +strings.boardEmpty }
            } else {
                ol(classes = "rank-list") {
                    board.forEach { member ->
                        li {
                            span(classes = "rank-name") { +member.displayName }
                            +" — "
                            span { +member.role.label(strings) }
                        }
                    }
                }
            }
        }
    }

    /** Reuses [PublicPostView] (the same data-minimized view model `/s` itself uses) for a small teaser list, never the full timeline summary renderer (no report link on an overview page). */
    private fun FlowContent.renderTopPosts(
        posts: List<PublicPostView>,
        baseUrl: String,
        lang: PublicLanguage,
        strings: PublicUiStrings,
    ) {
        section {
            attributes["id"] = "beitraege"
            h2 { +strings.topPosts }
            if (posts.isEmpty()) {
                p { +strings.noPosts }
            } else {
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
    }

    /**
     * D11: below the minimum cohort, [PublicTransparencyRoutes] never even constructs a non-null
     * [PublicRankingSection] for [view] -- so this function is only ever called with a section that
     * already cleared the threshold, and the section (INCLUDING its jump-menu anchor above) is
     * simply absent from the page otherwise, never rendered empty.
     */
    private fun FlowContent.renderRankingSection(
        id: String,
        title: String,
        unit: String,
        rankingSection: PublicRankingSection,
    ) {
        section {
            attributes["id"] = id
            h2 { +title }
            val top3 = rankingSection.rows.take(3)
            val rest = rankingSection.rows.drop(3)
            top3.forEachIndexed { index, row ->
                div(classes = "rank-top") {
                    span(classes = "rank-num") { +"${index + 1}" }
                    span(classes = "mono") { +monogramOf(row.displayName) }
                    span(classes = "rank-name") { +row.displayName }
                    span(classes = "rank-amount") { +"${row.amount} $unit" }
                }
            }
            if (rest.isNotEmpty()) {
                ol(classes = "rank-list") {
                    attributes["start"] = "4"
                    rest.forEach { row ->
                        li {
                            span(classes = "rank-name") { +row.displayName }
                            span(classes = "rank-amount") { +" ${row.amount} $unit" }
                        }
                    }
                }
            }
            // Jobs' Ruling (Design-Team-Beschluss): dieser Einwilligungssatz ist Rechtstext und
            // bleibt IMMER Deutsch, unabhängig von der Chrome-Sprache -- siehe PublicChrome.kt
            // Klassen-KDoc "Deliberately EXCLUDES `consentNote`".
            p(classes = "section-note") {
                attributes["lang"] = "de"
                +"Ihr Name erscheint nur, wenn mindestens fünf Mitglieder zugestimmt haben."
            }
        }
    }

    private fun CommitteeRole.label(strings: PublicUiStrings): String =
        when (this) {
            CommitteeRole.CHAIR -> strings.committeeRoleChair
            CommitteeRole.DEPUTY_CHAIR -> strings.committeeRoleDeputyChair
            CommitteeRole.SECRETARY -> strings.committeeRoleSecretary
            CommitteeRole.ASSESSOR -> strings.committeeRoleAssessor
            CommitteeRole.MEMBER -> strings.committeeRoleMember
        }

    private fun PublicPostView.excerptTitleForTeaser(): String {
        val firstLine = contentLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length <= 70) firstLine.ifBlank { "Beitrag" } else firstLine.take(69).trimEnd() + "…"
    }

    /**
     * Deterministic monogram from the first two non-empty whitespace-separated tokens of
     * [displayName], uppercased, at most 2 characters. A single-token name uses that token's first
     * character. An empty/blank [displayName] -- should never happen for a real member, defense in
     * depth -- falls back to `"·"`. Pure text node, `kotlinx.html` escapes it like any other string
     * (class KDoc point 1) -- no special-casing needed even though this is deliberately SHORT,
     * user-controlled text.
     */
    private fun monogramOf(displayName: String): String {
        val tokens = displayName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val monogram =
            when {
                tokens.isEmpty() -> ""
                tokens.size == 1 -> tokens[0].take(1)
                else -> tokens[0].take(1) + tokens[1].take(1)
            }.uppercase()
        return monogram.ifBlank { "·" }
    }
}

internal data class PublicTransparencyView(
    val stats: PublicTransparencyStats,
    val board: List<PublicBoardMemberRow>,
    val topPosts: List<PublicPostView>,
    /** `null` ⇔ below the minimum cohort (D11) -- the whole section, including its jump-menu anchor, is then absent. */
    val ltr: PublicRankingSection?,
    val donations: PublicRankingSection?,
    val donationYear: Int,
)
