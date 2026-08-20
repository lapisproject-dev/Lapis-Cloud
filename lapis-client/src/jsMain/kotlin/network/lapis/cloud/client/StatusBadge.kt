package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.icon
import io.kvision.html.span
import io.kvision.i18n.tr

/**
 * Governance UI wave -- shared badge-rendering grammar, per this project's mandatory UI/UX-Design-
 * Team review (root `CLAUDE.md` "UI/UX-Design-Team"). Eight Governance enums (`MotionStatus`,
 * `MeetingStatus`, `VoteStatus`, `AttendanceStatus`, `ResolutionStatus`, `ResolutionMode`, plus
 * this screen's own `CommitteeType`/`CommitteeRole`/active-flag) need badges, and several of them
 * co-occur in the same view -- Bootstrap 5.3.8 only offers eight semantic hues
 * (`primary/secondary/success/danger/warning/info/dark/light`), not enough to hand every enum a
 * fully disjoint color set once things co-occur. The design review settled one cross-cutting rule
 * instead of a per-enum color table:
 *
 * - [statusBadge] (solid `badge rounded-pill text-bg-{color}`) = a *lifecycle status* -- something
 *   that changes over time and is the thing the user is watching (`MotionStatus`, `MeetingStatus`,
 *   `VoteStatus`, `AttendanceStatus`, `ResolutionStatus`, quorum met/not-met, active/inactive).
 * - [typeBadge] (outline `badge rounded-pill border border-{color} text-{color}`, no fill) = a
 *   *fixed category/type label* -- doesn't progress, is a classification (`ResolutionMode`,
 *   `CommitteeType`, `CommitteeRole`, the "Änderungsantrag" amendment-type tag).
 *
 * The same hue may be reused across the two kinds, and even across different enums of the same
 * kind, without ambiguity: fill-vs-no-fill is a second, independent channel, and every badge always
 * carries a German text label alongside its color -- color is never the sole signal (WCAG 1.4.1).
 * Every screen calls these two functions; no screen hand-writes a `text-bg-*`/`border-*` string
 * inline -- see `CommitteesScreen.kt` for this wave's first concrete label/color tables.
 */
fun Container.statusBadge(
    text: String,
    color: String,
): Span = span(text) { addCssClasses("badge rounded-pill text-bg-$color") }

fun Container.typeBadge(
    text: String,
    color: String,
): Span = span(text) { addCssClasses("badge rounded-pill border border-$color text-$color") }

/**
 * Accounting UI wave, design decision D9: promotes the plain `if (active) "Aktiv" else "Inaktiv"`
 * ternary every screen with a deactivate-able entity (`CommitteesScreen` included) previously wrote
 * inline into one generic helper -- zero domain-specific knowledge, so it belongs here rather than
 * in a per-domain labels file. Used by `LedgerScreen.kt` (accounts), and by the later cost-center/
 * donor screens. `CommitteesScreen.kt`'s own pre-existing inline version is deliberately left
 * untouched this wave -- retrofitting it is out of scope, avoid unrelated diff noise.
 */
fun Container.activeStatusBadge(active: Boolean): Span =
    statusBadge(if (active) tr("Aktiv") else tr("Inaktiv"), if (active) "success" else "secondary")

/**
 * UI theme redesign wave (2026-08-20) -- a THIRD badge grammar, alongside [statusBadge]/[typeBadge]
 * above, but on a different axis entirely: those two render a Bootstrap badge-hue (one of eight),
 * `roleBadge` renders one of the seven semantic `--lapis-role-*` colors from `theme.css` (a legacy
 * pattern from PZB, an older sister project, that color-coded role icons throughout its UI) plus a
 * FontAwesome icon -- for WHO someone is (their [network.lapis.cloud.shared.domain.AccountRole]) or
 * what standing they hold (their [network.lapis.cloud.shared.domain.MemberStatus]), not a generic
 * per-enum status. Same WCAG 1.4.1 rule as [statusBadge]/[typeBadge]: color is never the only
 * signal, so the text label is mandatory, not optional -- there is no icon-only overload. See
 * `MemberStatusLabels.kt`'s `accountRoleBadge`/`memberStatusRoleBadge` for the concrete
 * enum-to-`roleClass`/icon mapping and its rationale (including why `lapis-role-party` is currently
 * unassigned).
 *
 * @param text the German label, always shown next to the icon
 * @param roleClass one of `lapis-role-admin`/`-party`/`-bank`/`-board`/`-member`/`-guest`/`-inactive`
 * @param faIcon a FontAwesome icon class string, e.g. `"fas fa-user-shield"`
 */
fun Container.roleBadge(
    text: String,
    roleClass: String,
    faIcon: String,
): Span =
    span {
        addCssClasses("lapis-role-badge $roleClass")
        icon(faIcon)
        span(text)
    }
