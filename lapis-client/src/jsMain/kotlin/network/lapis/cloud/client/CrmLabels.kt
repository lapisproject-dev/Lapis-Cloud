package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis

// Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- label/badge-color tables for CrmContactsScreen.
// gettext() throughout (not tr()), same reasoning DonorsScreen.kt's own label functions document:
// these are plain Strings returned to a caller, not passed straight into a widget constructor, so
// tr()'s deferred-resolution marker would never resolve. contactType/lawfulBasis are `typeBadge`
// grammar (fixed classification, see StatusBadge.kt KDoc); interactionKindLabel has no badge (used
// only as timeline-entry prefix text).

fun crmContactTypeLabel(type: CrmContactType): String =
    when (type) {
        CrmContactType.INTERESSENT -> gettext("Interessent")
        CrmContactType.SYMPATHISANT -> gettext("Sympathisant")
        CrmContactType.FOERDERER -> gettext("Förderer")
        CrmContactType.EHEMALIGES_MITGLIED -> gettext("Ehemaliges Mitglied")
        CrmContactType.PRESSE -> gettext("Presse")
    }

fun crmContactTypeColor(type: CrmContactType): String =
    when (type) {
        CrmContactType.INTERESSENT -> "primary"
        CrmContactType.SYMPATHISANT -> "info"
        CrmContactType.FOERDERER -> "success"
        CrmContactType.EHEMALIGES_MITGLIED -> "secondary"
        CrmContactType.PRESSE -> "dark"
    }

fun crmLawfulBasisLabel(basis: CrmLawfulBasis): String =
    when (basis) {
        CrmLawfulBasis.CONSENT -> gettext("Einwilligung (Art. 6 Abs. 1 lit. a)")
        CrmLawfulBasis.LEGITIMATE_INTEREST -> gettext("Berechtigtes Interesse (Art. 6 Abs. 1 lit. f)")
        CrmLawfulBasis.CONTRACT -> gettext("Vertrag (Art. 6 Abs. 1 lit. b)")
    }

fun crmInteractionKindLabel(kind: CrmInteractionKind): String =
    when (kind) {
        CrmInteractionKind.CALL -> gettext("Anruf")
        CrmInteractionKind.MEETING -> gettext("Treffen")
        CrmInteractionKind.EMAIL -> gettext("E-Mail")
        CrmInteractionKind.LETTER -> gettext("Brief")
        CrmInteractionKind.EVENT -> gettext("Veranstaltung")
        CrmInteractionKind.NOTE -> gettext("Notiz")
    }

fun crmInteractionKindIcon(kind: CrmInteractionKind): String =
    when (kind) {
        CrmInteractionKind.CALL -> "fas fa-phone"
        CrmInteractionKind.MEETING -> "fas fa-people-arrows"
        CrmInteractionKind.EMAIL -> "fas fa-envelope"
        CrmInteractionKind.LETTER -> "fas fa-envelope-open-text"
        CrmInteractionKind.EVENT -> "fas fa-calendar-days"
        CrmInteractionKind.NOTE -> "fas fa-note-sticky"
    }
