package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel

/**
 * V1.2.5 White-Label-Branding -- the unremovable "Betrieben mit Lapis Cloud" credit the UI/UX
 * Design-Team review requires on EVERY deployment of this app, regardless of how much of the rest
 * of the branding an operator customizes (see `Branding` KDoc "PLATFORM_NAME/PLATFORM_URL never
 * come from BrandPayload"). Two call sites, both below the primary content, never inside the
 * navbar/brand area (which is the operator's own surface): [App.start] (app shell, every screen)
 * and `LoginScreen.kt` (below the login card, reachable before any session exists).
 *
 * **The literal substring "Lapis Cloud" inside [tr]'s argument is load-bearing, not incidental.**
 * `messages-*.po`'s translation of this exact key is REQUIRED to contain that substring verbatim
 * in every one of the seven translation catalogs -- see `LapisAttributionTest` "the literal Lapis
 * Cloud string survives every .po translation" for the regression this guards against (a
 * well-meaning translator localizing the PRODUCT name itself, which would silently defeat the
 * entire point of an unremovable attribution). Do not parameterize this string with `Branding
 * .PLATFORM_NAME` via `gettext("...%1", ...)` -- that would move "Lapis Cloud" out of the
 * translatable msgid entirely, and this file's own `.po` coverage would no longer catch a
 * mistranslation.
 */
fun SimplePanel.lapisAttribution(): SimplePanel =
    div(className = "lapis-attribution") {
        span(content = LAPIS_GEM_MARK_SVG, rich = true, className = "lapis-brand-mark lapis-attribution-mark")
        val attributionLink: Link =
            link(
                label = tr("Betrieben mit Lapis Cloud"),
                url = Branding.PLATFORM_URL,
                target = "_blank",
                // Real full-page navigation to a DIFFERENT origin -- not an SPA hash route, so this
                // must opt out of the global `Link.useDataNavigoForLinks = true` (App.kt main()),
                // same reasoning every other external `link(...)` call in this app already follows
                // (see e.g. LoginScreen.kt's OIDC entry point).
                dataNavigo = false,
            )
        // target="_blank" without rel="noopener noreferrer" lets the opened page's JavaScript
        // reach back into this tab via `window.opener` (reverse tabnabbing) -- KVision's `Link`
        // has no dedicated `rel` property, so this is set via the generic attribute API every
        // other KVision widget in this codebase already uses for a non-modeled attribute (see
        // `NavHighlight.kt`'s own `setAttribute("aria-current", "page")`).
        attributionLink.setAttribute("rel", "noopener noreferrer")
        attributionLink.setAttribute("title", Branding.PLATFORM_NAME)
    }
