package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.panel.SimplePanel

/**
 * V1.4.7 "Root-Verlinkung" -- das Marken-Lockup über jeder unauthentifizierten Karte (Login,
 * Registrierung, Freund-Registrierung, Passwort-Reset, E-Mail-Bestätigung). Vorher existierte "das
 * Logo" auf vier dieser fünf Screens überhaupt nicht (nur `LoginScreen` trug `Branding.title` als
 * `h1`) -- der Nutzerwunsch "Klick aufs Logo führt zu /" war dort ein Auftrag ohne Objekt
 * (UI/UX-Design-Team-Sitzung V1.4.7, Kare/Ive, Jobs' Abschlussreview).
 *
 * Verlinkt IMMER auf `/` als echte volle Seitennavigation -- `dataNavigo = false`, weil `App.start`
 * global `Link.useDataNavigoForLinks = true` setzt (siehe dort und [LoginScreen]'s OIDC-Link für
 * dasselbe Opt-out-Muster).
 *
 * Drei-Zustands-Regel identisch zu `App.start`'s Navbar-Brand (V1.2.5 White-Label-Branding): eigenes
 * Logo gewinnt immer; sonst zeigt die Lapis-Gem-Marke NUR neben dem unveränderten Default-Titel;
 * eigener Titel ohne eigenes Logo bekommt gar keine Marke.
 *
 * [brandLogoImgHtml]/[escapeHtmlAttribute] leben seit dieser Welle hier statt in `App.kt`, weil
 * beide Aufrufer (die Navbar-Marke UND dieses Lockup) sie jetzt teilen -- eine gemeinsame Datei
 * statt zweier, potenziell driftender Kopien.
 */
fun SimplePanel.brandLockup(): Link {
    val lockup = link(label = Branding.title, url = "/", dataNavigo = false, className = "lapis-lockup")
    lockup.labelFirst = false
    when {
        Branding.logoUrl != null ->
            lockup.span(
                content = brandLogoImgHtml(src = Branding.logoUrl!!, alt = Branding.title),
                rich = true,
                className = "lapis-brand-logo",
            )
        Branding.title == Branding.DEFAULT_TITLE ->
            lockup.span(content = LAPIS_GEM_MARK_SVG, rich = true, className = "lapis-brand-mark")
        else -> {
            // Custom title, no custom logo -- no mark, see class KDoc.
        }
    }
    return lockup
}

/**
 * V1.2.5 White-Label-Branding -- builds a real `<img>` tag for a brand mark's `rich = true` span
 * (used by both `App.start`'s navbar brand AND [brandLockup] above -- not `Link.image`, whose exact
 * mutation semantics on an already-constructed `Link` were not verified against this pinned KVision
 * version, see V1.2.5 plan "Offene Frage 1"; this path is proven to compile and render in this exact
 * codebase already). `src`/`alt` are HTML-attribute-escaped even though both ultimately derive from
 * server-injected, already-escaped values (`Branding.logoUrl`/`Branding.title`) -- defense in depth
 * costs nothing here and this function has no other caller to rely on that upstream discipline never
 * changing.
 */
internal fun brandLogoImgHtml(
    src: String,
    alt: String,
): String = """<img src="${escapeHtmlAttribute(src)}" alt="${escapeHtmlAttribute(alt)}">"""

internal fun escapeHtmlAttribute(value: String): String =
    buildString {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
