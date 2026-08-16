package network.lapis.cloud.client

import io.kvision.i18n.gettext
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
    }

fun memberStatusColor(status: MemberStatus): String =
    when (status) {
        MemberStatus.APPLICATION -> "warning"
        MemberStatus.ACTIVE -> "success"
        MemberStatus.GUEST -> "info"
        MemberStatus.WITHDRAWN -> "secondary"
        MemberStatus.REJECTED -> "danger"
        MemberStatus.FRIEND -> "info"
    }
