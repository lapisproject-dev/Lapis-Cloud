package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Placement
import io.kvision.core.PopoverOptions
import io.kvision.core.Trigger
import io.kvision.core.enablePopover
import io.kvision.html.Span
import io.kvision.html.icon
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
     * CSS custom properties from `theme.css` (V1.4.25, UI foundation wave -- these used to be the
     * literals `#A855F7`/`#FFFFFF`). Deliberately outside the four plausible organization brand hues
     * (yellow/blue/green/red) so the badge never blends into a visited server's own branding. The
     * badge always sits on a dark surface (navbar `--lapis-nav`, conference tile `--lapis-tile-bg`
     * -- both dark in every theme), so fill and glyph are IDENTICAL in the light, system-dark and
     * explicit-dark token blocks (`#A855F7` / `#FFFFFF`, the pair the design-team review verified:
     * >= 3:1 non-text against the dark surfaces, white glyph on the fill 4.0:1). A dark-mode
     * variant would only change the approved badge for no contrast gain.
     */
    const val FILL = "var(--lapis-guest-fill)"
    const val GLYPH = "var(--lapis-guest-glyph)"
}

private const val BADGE_SIZE_PX = 18

/**
 * V1.9.182: the one glyph of the visitor badge (guest AND friend) is the Font Awesome solid hiker with backpack and
 * stick ([ActionIcon.VISITOR], a compile-time constant), sized by this constant. It replaces the hand-authored inline
 * SVG of V0.8.4, so the badge no longer has any rich-HTML path.
 */
private const val GLYPH_SIZE_PX = 12

/**
 * Pure, jsTest-able popover/aria text -- factored out so it doesn't need a DOM harness (see
 * `GuestBadgeTest`).
 */
fun guestBadgeAriaLabel(homeserverUrl: String): String = gettext("Gast von %1", homeserverUrl)

fun guestBadgePopoverTitle(homeserverUrl: String): String = gettext("Gast von %1", homeserverUrl)

fun guestBadgePopoverBody(homeserverUrl: String): String = gettext("Angemeldet über den OIDC-Heimserver %1.", homeserverUrl)

/** V1.9.182: texts of the friend variant of the visitor badge (a friend account on this server, not a member). */
fun friendBadgeAriaLabel(): String = gettext("Freund")

fun friendBadgePopoverTitle(): String = gettext("Freund")

fun friendBadgePopoverBody(): String = gettext("Hat ein Freundeskonto auf diesem Server, ist aber kein Mitglied.")

/**
 * 18x18px circular guest indicator with a hover/focus/tap popover ("Gast von {homeserverUrl}") and
 * an `aria-label` on the badge itself, so screen-reader users get the home-server information
 * without needing to trigger the popover at all. Icon/color/size/interaction rationale: see this
 * file's top-level KDoc "Provenance" -- these are the design-team review's final decisions
 * (V1.9.182 swapped the hand-drawn SVG for the Font Awesome hiker, everything else is unchanged).
 *
 * No "Profil auf Heimserver ansehen"-link: no home-profile-URL field exists anywhere in this
 * codebase today, so the design spec's optional popover link is omitted rather than invented.
 *
 * **XSS**: [homeserverUrl] is remote-controlled data (the guest's home OIDC server). It reaches the DOM only via
 * [PopoverOptions.title]/[PopoverOptions.content] with `rich` left unset (Bootstrap Popover's own
 * default is `html: false`) and via `aria-label`, a plain attribute value -- both are text sinks,
 * never raw-HTML interpolation. The glyph is a CSS class from a compile-time constant; there is no rich-HTML path.
 */
fun Container.guestBadge(homeserverUrl: String): Span =
    visitorBadge(
        ariaLabel = guestBadgeAriaLabel(homeserverUrl),
        popoverTitle = guestBadgePopoverTitle(homeserverUrl),
        popoverBody = guestBadgePopoverBody(homeserverUrl),
    )

/** V1.9.182: the friend variant of the visitor badge -- same disc and glyph, own texts, no home server involved. */
fun Container.friendBadge(): Span =
    visitorBadge(
        ariaLabel = friendBadgeAriaLabel(),
        popoverTitle = friendBadgePopoverTitle(),
        popoverBody = friendBadgePopoverBody(),
    )

private fun Container.visitorBadge(
    ariaLabel: String,
    popoverTitle: String,
    popoverBody: String,
): Span =
    span(className = "d-inline-flex align-items-center justify-content-center rounded-circle") {
        width = BADGE_SIZE_PX.px
        height = BADGE_SIZE_PX.px
        flexShrink = 0
        setStyle("background-color", GuestBadgeColors.FILL)
        // The glyph paints in `currentColor` -- resolved from this wrapper.
        setStyle("color", GuestBadgeColors.GLYPH)
        role = "img"
        tabindex = 0
        setAttribute("aria-label", ariaLabel)
        // Gotcha: the navbar wrapper this badge sits inside re-applies Bootstrap 5's `.disabled`
        // class (`pointer-events: none`) -- see App.kt's refreshNavbar. Without this override, hover/click could
        // never reach the badge at all despite Trigger.HOVER/Trigger.CLICK being configured.
        setStyle("pointer-events", "auto")
        icon(ActionIcon.VISITOR.css) {
            setAttribute("aria-hidden", "true")
            setStyle("font-size", "${GLYPH_SIZE_PX}px")
            setStyle("line-height", "1")
        }
        enablePopover(
            PopoverOptions(
                title = popoverTitle,
                content = popoverBody,
                placement = Placement.BOTTOM,
                triggers = listOf(Trigger.HOVER, Trigger.FOCUS, Trigger.CLICK),
                // rich intentionally left unset -- see KDoc "XSS" above.
            ),
        )
    }
