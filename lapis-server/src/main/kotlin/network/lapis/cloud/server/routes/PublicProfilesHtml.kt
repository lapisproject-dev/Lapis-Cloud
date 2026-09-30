package network.lapis.cloud.server.routes

import kotlinx.html.UL
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import network.lapis.cloud.server.branding.ResolvedBranding
import java.text.BreakIterator
import java.util.Locale

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- server-rendered `/vorstand`, `/politiker` and
 * `/landesverbaende`. Same rendering-safety properties as every public-HTML file in this package
 * ([SocialPublicHtml] class KDoc): the escape-only `kotlinx.html` API (no raw-HTML bypass, no
 * script), no request-time-dependent output, identical output for crawler and human.
 *
 * - **Name, bio and office text are user/third-party text**: rendered as text nodes only and
 *   marked `lang="de"` (they are written in German regardless of the chrome language); the
 *   chrome-translated role label stays in the chrome language.
 * - **Image URLs** are built by [PublicProfileUrls] from pattern-checked tokens only; a photo
 *   `<img>` is decorative (`alt=""`) because the name stands right next to it. A crest's `alt`
 *   is the translated prefix plus the chapter name -- both escaped.
 * - **No photo -> initials ([initialsOf]) or, for names without a letter, a silhouette glyph.**
 *   The avatar has a fixed size, so a missing photo never shifts the layout.
 * - **Nothing else about a person**: no detail link, no id, no date, no number.
 */
internal object PublicProfilesHtml {
    fun boardPage(
        cards: List<PublicBoardCard>,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        nav: PublicNavAvailability,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return PublicOverviewHtml.skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/vorstand",
            heading = strings.navBoard,
            active = PublicChrome.NavTarget.BOARD,
            nav = nav,
            robots = "noindex,follow",
        ) {
            if (cards.isEmpty()) {
                p { +strings.boardEmpty }
            } else {
                ul(classes = "person-grid") {
                    cards.forEach { item ->
                        personCard(
                            card = item.card,
                            roleText = item.role.publicLabel(strings),
                            baseUrl = baseUrl,
                        )
                    }
                }
            }
        }
    }

    fun politiciansPage(
        cards: List<PublicPersonCard>,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        nav: PublicNavAvailability,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return PublicOverviewHtml.skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/politiker",
            heading = strings.navPoliticians,
            active = PublicChrome.NavTarget.POLITICIANS,
            nav = nav,
            robots = "noindex,follow",
        ) {
            if (cards.isEmpty()) {
                p { +strings.politiciansEmpty }
            } else {
                ul(classes = "person-grid") {
                    cards.forEach { card -> personCard(card = card, roleText = null, baseUrl = baseUrl) }
                }
            }
        }
    }

    fun chaptersPage(
        cards: List<PublicChapterCard>,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        nav: PublicNavAvailability,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return PublicOverviewHtml.skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/landesverbaende",
            heading = strings.navChapters,
            active = PublicChrome.NavTarget.CHAPTERS,
            nav = nav,
            // Chapters carry no personal data -- indexable, unlike the two person pages.
            robots = "index,follow",
        ) {
            if (cards.isEmpty()) {
                p { +strings.chaptersEmpty }
            } else {
                ul(classes = "person-grid") {
                    cards.forEach { card -> chapterCard(card = card, crestAltPrefix = strings.crestAltPrefix, baseUrl = baseUrl) }
                }
            }
        }
    }

    private fun UL.personCard(
        card: PublicPersonCard,
        roleText: String?,
        baseUrl: String,
    ) {
        li(classes = "person-card") {
            val photoUrl = PublicProfileUrls.photoUrl(baseUrl = baseUrl, token = card.photoToken)
            if (photoUrl != null) {
                img(src = photoUrl, alt = "", classes = "avatar") {
                    attributes["loading"] = "lazy"
                    attributes["width"] = "96"
                    attributes["height"] = "96"
                }
            } else {
                val initials = initialsOf(card.name)
                if (initials != null) {
                    span(classes = "avatar avatar-initials") {
                        attributes["aria-hidden"] = "true"
                        +initials
                    }
                } else {
                    span(classes = "avatar avatar-silhouette") { attributes["aria-hidden"] = "true" }
                }
            }
            h2(classes = "person-name") {
                attributes["lang"] = "de"
                +card.name
            }
            if (roleText != null) {
                p(classes = "person-role") { +roleText }
            } else if (card.roleOrOffice != null) {
                p(classes = "person-role") {
                    attributes["lang"] = "de"
                    +card.roleOrOffice
                }
            }
            if (card.bio != null) {
                p(classes = "bio") {
                    attributes["lang"] = "de"
                    +card.bio
                }
            }
        }
    }

    private fun UL.chapterCard(
        card: PublicChapterCard,
        crestAltPrefix: String,
        baseUrl: String,
    ) {
        li(classes = "person-card chapter-card") {
            div(classes = "crest-tile") {
                val crestUrl = PublicProfileUrls.crestUrl(baseUrl = baseUrl, token = card.crestToken)
                if (crestUrl != null) {
                    img(src = crestUrl, alt = "$crestAltPrefix ${card.name}", classes = "crest-image") {
                        attributes["loading"] = "lazy"
                        attributes["width"] = "64"
                        attributes["height"] = "64"
                    }
                } else {
                    span(classes = "crest-placeholder") { attributes["aria-hidden"] = "true" }
                }
            }
            div(classes = "chapter-text") {
                h2(classes = "person-name") {
                    attributes["lang"] = "de"
                    +card.name
                }
                if (card.description != null) {
                    p(classes = "bio") {
                        attributes["lang"] = "de"
                        +card.description
                    }
                }
            }
        }
    }

    /**
     * Initials for the photo-less avatar: the first LETTER of the first and of the last word,
     * uppercased ("Anna-Lena Müller" -> "AM", "Ölaf" -> "Ö"). Works on grapheme clusters
     * ([BreakIterator]), so an accent built from combining marks or an emoji is never cut in
     * half. A word that does not start with a letter contributes nothing; `null` (-> silhouette)
     * when no word does -- e.g. a name of digits or emoji only.
     */
    fun initialsOf(name: String): String? {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val picked = if (words.size == 1) listOf(words.first()) else listOf(words.first(), words.last())
        val letters = picked.mapNotNull { firstLetterGrapheme(it) }
        return letters.joinToString("").ifEmpty { null }?.uppercase(Locale.ROOT)
    }

    private fun firstLetterGrapheme(word: String): String? {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(word)
        val end = iterator.next()
        if (end == BreakIterator.DONE) return null
        val grapheme = word.substring(0, end)
        return if (Character.isLetter(grapheme.codePointAt(0))) grapheme else null
    }
}
