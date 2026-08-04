package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.html.span

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
