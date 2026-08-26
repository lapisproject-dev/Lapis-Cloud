package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Span
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus

/**
 * V0.11.0 -- the [MemberStatus] German label/badge-color table, following the established
 * convention ([ComplianceLabels.kt], [CrowdfundingScreen.kt]'s own status tables): a `when`
 * returning [gettext] plus a `...Color()` returning a Bootstrap variant, consumed via
 * `Container.statusBadge(label, color)` ([StatusBadge.kt]). No such helper existed anywhere in the
 * repo before this wave -- [MemberAdministrationScreen.kt] previously rendered the raw enum name.
 *
 * [statusBadge] grammar: [MemberStatus] is a *lifecycle status* (progresses over time --
 * APPLICATION -> ACTIVE/REJECTED, ACTIVE -> WITHDRAWN, FRIEND -> APPLICATION), so it uses the
 * filled/lifecycle variant [Container.statusBadge], not [Container.typeBadge].
 */
fun memberStatusLabel(status: MemberStatus): String =
    when (status) {
        MemberStatus.APPLICATION -> gettext("Antrag")
        MemberStatus.ACTIVE -> gettext("Aktiv")
        MemberStatus.GUEST -> gettext("Gast")
        MemberStatus.WITHDRAWN -> gettext("Ausgetreten")
        MemberStatus.REJECTED -> gettext("Abgelehnt")
        MemberStatus.FRIEND -> gettext("Freund")
        // V1.2.11 PdV-CSV-Import -- see MemberStatus KDoc.
        MemberStatus.DONOR -> gettext("Spender")
        MemberStatus.DECEASED -> gettext("Verstorben")
    }

fun memberStatusColor(status: MemberStatus): String =
    when (status) {
        MemberStatus.APPLICATION -> "warning"
        MemberStatus.ACTIVE -> "success"
        MemberStatus.GUEST -> "info"
        MemberStatus.WITHDRAWN -> "secondary"
        MemberStatus.REJECTED -> "danger"
        MemberStatus.FRIEND -> "info"
        // V1.2.11 PdV-CSV-Import: "primary" was an unused Bootstrap hue before this wave.
        MemberStatus.DONOR -> "primary"
        MemberStatus.DECEASED -> "dark"
    }

// ------------------------------------------------------------------------------------------------
// UI theme redesign wave (2026-08-20) -- [network.lapis.cloud.client.roleBadge] mapping for
// [AccountRole] and [MemberStatus], the seven PZB-heritage `--lapis-role-*` colors from
// `theme.css`. Distinct from [memberStatusColor] above (Bootstrap-hue `statusBadge` grammar) --
// `roleBadge` is reserved for these two enums specifically, the ones that represent WHO someone is
// / what standing they hold, not a generic per-enum lifecycle status.
//
// Mapping rationale (this codebase's actual [AccountRole]/[MemberStatus] literals, decided by
// judgment -- no prior convention to follow, this is the first wave to need it):
// - [AccountRole.ADMIN] -> `lapis-role-admin` -- full system authority, the most consequential role.
// - [AccountRole.BOARD] -> `lapis-role-board` -- Vorstand, governance authority.
// - [AccountRole.TREASURER] -> `lapis-role-bank` -- Schatzmeister, financial authority ("bank" is
//   the PZB-heritage name this wave's CSS tokens carry forward, see `theme.css`).
// - [AccountRole.MEMBER] -> `lapis-role-member` -- plain membership, the baseline role.
// - [MemberStatus.GUEST] / [MemberStatus.FRIEND] -> `lapis-role-guest` -- both are pre-membership,
//   non-voting identities (see [MemberStatus] KDoc); disambiguated by icon + label text, not color
//   -- the same "same hue may be reused across enums without ambiguity, color is never the sole
//   signal" rule `StatusBadge.kt`'s own KDoc already documents for `statusBadge`/`typeBadge`.
// - [MemberStatus.WITHDRAWN] / [MemberStatus.REJECTED] -> `lapis-role-inactive` -- both a terminal,
//   no-longer/never-active standing.
// - [MemberStatus.APPLICATION] -> `lapis-role-inactive` too -- deliberately the SAME hue as
//   WITHDRAWN/REJECTED: all three describe "not currently an active member" standings, and the
//   "not yet" vs. "no longer" distinction is carried by the label text/icon, not the color.
// - [MemberStatus.ACTIVE] has no separate mapping here -- an ACTIVE member's most useful role badge
//   is their [AccountRole] (every ACTIVE member has one), not a generic "Aktiv" pill duplicating
//   what [memberStatusColor]'s existing Bootstrap badge already covers where that IS what's wanted.
// - `lapis-role-party` is deliberately NOT assigned by this mapping -- neither [AccountRole] nor
//   [MemberStatus] has a party-affiliation-type literal today (PZB's historical role set had one;
//   this codebase's model doesn't yet). The CSS token stays reserved for that, if/when such a role
//   is modeled, rather than being force-fit onto an unrelated literal now.
// ------------------------------------------------------------------------------------------------

fun accountRoleLabel(role: AccountRole): String =
    when (role) {
        AccountRole.MEMBER -> gettext("Mitglied")
        AccountRole.BOARD -> gettext("Vorstand")
        AccountRole.TREASURER -> gettext("Schatzmeister")
        AccountRole.ADMIN -> gettext("Administrator")
    }

fun accountRoleBadgeClass(role: AccountRole): String =
    when (role) {
        AccountRole.MEMBER -> "lapis-role-member"
        AccountRole.BOARD -> "lapis-role-board"
        AccountRole.TREASURER -> "lapis-role-bank"
        AccountRole.ADMIN -> "lapis-role-admin"
    }

fun accountRoleIcon(role: AccountRole): String =
    when (role) {
        AccountRole.MEMBER -> "fas fa-user"
        AccountRole.BOARD -> "fas fa-people-group"
        AccountRole.TREASURER -> "fas fa-coins"
        AccountRole.ADMIN -> "fas fa-user-shield"
    }

/** Convenience wrapper around [network.lapis.cloud.client.roleBadge] -- the usual call site never
 * needs to spell out label/class/icon separately. */
fun Container.accountRoleBadge(role: AccountRole): Span =
    roleBadge(accountRoleLabel(role), accountRoleBadgeClass(role), accountRoleIcon(role))

fun memberStatusBadgeClass(status: MemberStatus): String =
    when (status) {
        MemberStatus.APPLICATION -> "lapis-role-inactive"
        MemberStatus.ACTIVE -> "lapis-role-member"
        MemberStatus.GUEST -> "lapis-role-guest"
        MemberStatus.WITHDRAWN -> "lapis-role-inactive"
        MemberStatus.REJECTED -> "lapis-role-inactive"
        MemberStatus.FRIEND -> "lapis-role-guest"
        // V1.2.11 PdV-CSV-Import: DONOR is a non-member standing, like GUEST/FRIEND; DECEASED is
        // terminal, like WITHDRAWN/REJECTED.
        MemberStatus.DONOR -> "lapis-role-guest"
        MemberStatus.DECEASED -> "lapis-role-inactive"
    }

fun memberStatusIcon(status: MemberStatus): String =
    when (status) {
        MemberStatus.APPLICATION -> "fas fa-hourglass-half"
        MemberStatus.ACTIVE -> "fas fa-user-check"
        MemberStatus.GUEST -> "fas fa-id-badge"
        MemberStatus.WITHDRAWN -> "fas fa-right-from-bracket"
        MemberStatus.REJECTED -> "fas fa-user-xmark"
        MemberStatus.FRIEND -> "fas fa-user-plus"
        // V1.2.11 PdV-CSV-Import: a ribbon, not a cross -- religiously neutral, the mandatory text
        // label carries the meaning (WCAG 1.4.1, see StatusBadge.kt KDoc).
        MemberStatus.DONOR -> "fas fa-hand-holding-heart"
        MemberStatus.DECEASED -> "fas fa-ribbon"
    }

/** Convenience wrapper around [network.lapis.cloud.client.roleBadge] -- reuses [memberStatusLabel]
 * for the text (identical wording as the existing Bootstrap-badge grammar; only color/icon differ
 * between the two badge kinds). */
fun Container.memberStatusRoleBadge(status: MemberStatus): Span =
    roleBadge(memberStatusLabel(status), memberStatusBadgeClass(status), memberStatusIcon(status))
