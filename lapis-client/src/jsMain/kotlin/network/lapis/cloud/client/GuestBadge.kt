package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Placement
import io.kvision.core.PopoverOptions
import io.kvision.core.Trigger
import io.kvision.core.enablePopover
import io.kvision.html.Span
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.utils.px

/**
 * V0.8.4 Guest Badge -- shared visual token for a federated OIDC guest's presence in this UI.
 *
 * Provenance (corrected 2026-07-28, round-3 -- a round-2 pass had this backwards): this
 * component's icon, color, size, and interaction ARE the direct output of this project's
 * mandatory UI/UX-Design-Team review (root `CLAUDE.md` "UI/UX-Design-Team" -- Kare, Tesler,
 * Atkinson, Kay, Norman, Raskin, Rams, Ive, Forstall, Duarte, Zhuo, with Jobs' final call),
 * actually convened for this feature before implementation began. The review settled
 * Wanderer-over-Passport (legibility at badge scale), `#A855F7`/`#FFFFFF` (deliberately outside
 * the four plausible org brand hues, WCAG-AA verified against both navbar surfaces), the
 * 18x18px size, and hover+focus+tap (never hover-only, for touch/keyboard reachability) as final
 * decisions, not interim placeholders. The vault's own "Offene Fragen Federation" -> "Timeline-
 * Markierung" open-questions entry predates this review and still shows the icon/color as open --
 * that entry is stale documentation to be updated after this wave merges, not evidence the review
 * didn't happen.
 *
 * Today's ONLY real call site is the navbar identity display in `App.kt`'s `refreshNavbar`.
 * Deliberately built as a reusable component anyway: this codebase has no "Timeline"/"Post"
 * content entity yet (confirmed across three prior federation waves, V0.8.1-V0.8.3), but once one
 * ships avatars/posts, this exact glyph/color is expected to reappear there.
 */
object GuestBadgeColors {
    /**
     * Deliberately outside the four plausible organization brand hues (yellow/blue/green/red) so
     * the badge never blends into a visited server's own branding. WCAG-AA verified: vs Bootstrap
     * navbar-light `#F8F9FA` = 3.7:1, vs navbar-dark `#212529` = 3.9:1 (both clear the 3:1
     * non-text/UI-component bar); white glyph on this fill = 4.0:1. Final value from this
     * project's UI/UX-Design-Team review -- see the provenance note in this file's top-level
     * KDoc above.
     */
    const val FILL = "#A855F7"
    const val GLYPH = "#FFFFFF"
}

private const val BADGE_SIZE_PX = 18
private const val GLYPH_SIZE_PX = 12

/**
 * Static, hand-authored wanderer/hiker glyph -- head circle + one three-subpath body (planted
 * leg, forward-leaning torso/swinging arm, trailing arm-with-stick). Picks the "Wanderer" option
 * over "Reisepass mit Visum-Stempel" (passport/visa-stamp) per the design-team review's own
 * verdict (poor legibility at badge scale) -- see the provenance note on this file's top-level
 * KDoc.
 *
 * NEVER concatenated with any request-derived string -- see [guestBadge] KDoc "XSS".
 */
private val WANDERER_SVG =
    """
    <svg viewBox="0 0 24 24" width="$GLYPH_SIZE_PX" height="$GLYPH_SIZE_PX" xmlns="http://www.w3.org/2000/svg"
         stroke="${GuestBadgeColors.GLYPH}" stroke-width="2.4" fill="none" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="12" cy="5" r="2" fill="${GuestBadgeColors.GLYPH}" stroke="none"/>
      <path d="M12 9 L9 14 L7 20 M12 9 L15 13 L18 16 M11 10.5 L8 12.5 L6 19"/>
    </svg>
    """.trimIndent()

/**
 * Pure, jsTest-able popover/aria text -- factored out so it doesn't need a DOM harness (see
 * `GuestBadgeTest`).
 */
fun guestBadgeAriaLabel(homeserverUrl: String): String = gettext("Gast von %1", homeserverUrl)

fun guestBadgePopoverTitle(homeserverUrl: String): String = gettext("Gast von %1", homeserverUrl)

fun guestBadgePopoverBody(homeserverUrl: String): String = gettext("Angemeldet über den OIDC-Heimserver %1.", homeserverUrl)

/**
 * 18x18px circular guest indicator with a hover/focus/tap popover ("Gast von {homeserverUrl}") and
 * an `aria-label` on the badge itself, so screen-reader users get the home-server information
 * without needing to trigger the popover at all. Icon/color/size/interaction rationale: see this
 * file's top-level KDoc "Provenance" -- these are the design-team review's final decisions.
 *
 * No "Profil auf Heimserver ansehen"-link: no home-profile-URL field exists anywhere in this
 * codebase today (checked against [network.lapis.cloud.server.federation.OidcGuestClaims] and
 * `OidcGuestProfileTable` -- neither carries one), so the design spec's optional popover link is
 * omitted this wave rather than invented.
 *
 * **XSS**: [homeserverUrl] is remote-controlled data (the guest's home OIDC server, ultimately
 * `OidcGuestProfileTable.homeserverUrl` -- see
 * [network.lapis.cloud.server.federation.OidcGuestMemberStore] KDoc). It reaches the DOM only via
 * [PopoverOptions.title]/[PopoverOptions.content] with `rich` left unset (Bootstrap Popover's own
 * default is `html: false`) and via `aria-label`, a plain attribute value -- both are text sinks,
 * never raw-HTML interpolation. The glyph SVG IS injected as `rich = true`, but [WANDERER_SVG] is
 * a compile-time constant that never incorporates [homeserverUrl] or any other request-derived
 * value.
 */
fun Container.guestBadge(homeserverUrl: String): Span =
    span(className = "d-inline-flex align-items-center justify-content-center rounded-circle") {
        width = BADGE_SIZE_PX.px
        height = BADGE_SIZE_PX.px
        flexShrink = 0
        setStyle("background-color", GuestBadgeColors.FILL)
        role = "img"
        tabindex = 0
        setAttribute("aria-label", guestBadgeAriaLabel(homeserverUrl))
        // Gotcha: the navbar wrapper this badge sits inside re-applies Bootstrap 5's `.disabled`
        // class (`pointer-events: none`) to preserve the pre-V0.8.4 visual weight of the identity
        // display -- see App.kt's refreshNavbar. Without this override, hover/click could never
        // reach the badge at all despite Trigger.HOVER/Trigger.CLICK being configured.
        setStyle("pointer-events", "auto")
        span(WANDERER_SVG, rich = true)
        enablePopover(
            PopoverOptions(
                title = guestBadgePopoverTitle(homeserverUrl),
                content = guestBadgePopoverBody(homeserverUrl),
                placement = Placement.BOTTOM,
                triggers = listOf(Trigger.HOVER, Trigger.FOCUS, Trigger.CLICK),
                // rich intentionally left unset -- see KDoc "XSS" above.
            ),
        )
    }
