package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto

/**
 * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" -- reine, DOM-freie Label-/Farb-/
 * Text-Bausteine, gleiche Grammatik wie `TravelExpenseLabels.kt`: `when` über `entries`,
 * erschöpfend, `tr(...)`.
 */
fun volunteerAllowanceStatusLabel(status: VolunteerAllowancePaymentStatus): String =
    when (status) {
        VolunteerAllowancePaymentStatus.DRAFT -> tr("Entwurf")
        VolunteerAllowancePaymentStatus.REQUESTED -> tr("Eingereicht")
        // NIEMALS "Genehmigt" -- laut chk_vap_execution_error_state trägt jede APPROVED-Zeile
        // einen executionError.
        VolunteerAllowancePaymentStatus.APPROVED -> tr("Buchung fehlgeschlagen")
        VolunteerAllowancePaymentStatus.REJECTED -> tr("Abgelehnt")
        VolunteerAllowancePaymentStatus.EXECUTED -> tr("Zur Auszahlung gebucht")
        VolunteerAllowancePaymentStatus.WITHDRAWN -> tr("Zurückgezogen")
    }

fun volunteerAllowanceStatusColor(status: VolunteerAllowancePaymentStatus): String =
    when (status) {
        VolunteerAllowancePaymentStatus.DRAFT -> "secondary"
        VolunteerAllowancePaymentStatus.REQUESTED -> "secondary"
        VolunteerAllowancePaymentStatus.APPROVED -> "danger"
        VolunteerAllowancePaymentStatus.REJECTED -> "danger"
        VolunteerAllowancePaymentStatus.EXECUTED -> "success"
        VolunteerAllowancePaymentStatus.WITHDRAWN -> "secondary"
    }

fun volunteerAllowanceCategoryLabel(category: VolunteerAllowanceCategory): String =
    when (category) {
        VolunteerAllowanceCategory.INSTRUCTOR -> tr("Übungsleiterpauschale")
        VolunteerAllowanceCategory.HONORARY -> tr("Ehrenamtspauschale")
    }

/** Icons verifiziert per grep auf Kollisionsfreiheit (Plan-Stolperfalle, V1.4.10.1-Lehre). */
fun volunteerAllowanceCategoryIcon(category: VolunteerAllowanceCategory): String =
    when (category) {
        VolunteerAllowanceCategory.INSTRUCTOR -> "fas fa-graduation-cap"
        VolunteerAllowanceCategory.HONORARY -> "fas fa-hands-helping"
    }

fun volunteerAllowanceCategoryColor(category: VolunteerAllowanceCategory): String =
    when (category) {
        VolunteerAllowanceCategory.INSTRUCTOR -> "info"
        VolunteerAllowanceCategory.HONORARY -> "warning"
    }

/** NIE "Restbetrag" -- siehe [network.lapis.cloud.shared.domain.VolunteerAllowanceYearStatusDto.remainingInThisOrganization] KDoc. */
fun volunteerAllowanceRemainingLabel(): String = tr("verbleibend in dieser Organisation")

fun volunteerAllowanceForeignOrgsDisclaimer(): String = tr("Andere Organisationen sind Lapis Cloud nicht bekannt.")

/**
 * Shown on `VolunteerAllowanceScreen`'s own-payments list instead of the self-declaration button
 * when the viewer is the REQUESTER but not the SUBJECT of an APPROVED/EXECUTED payment (a
 * BOARD/ADMIN `createDraft(subjectMemberId = someone else)` row) -- see
 * `volunteerAllowanceShowsSelfDeclaration` KDoc for why that button must never appear there.
 */
fun volunteerAllowanceForeignSubjectDeclarationHint(): String =
    tr(
        "Die Selbstauskunft kann nur die empfangende Person selbst hier bestätigen, oder Vorstand/" +
            "Administration in Papierform erfassen.",
    )

/**
 * Shown instead of [volunteerAllowancePostingErrorMessage] once the subject HAS just submitted the
 * missing self-declaration, but the APPROVED row itself is still waiting for the board's next
 * `retryPosting` -- avoids showing the stale "declaration missing" error right next to the
 * freshly-rendered confirmation badge (Review INFORMATIONAL finding).
 */
fun volunteerAllowanceSelfDeclarationPendingRepostMessage(): String =
    tr("Selbstauskunft liegt vor -- die Buchung wird vom Vorstand erneut angestoßen.")

/** `travelExpensePayoutDisclaimer()` wird wortgleich wiederverwendet (Jobs-Ruling) -- nicht neu formulieren. */
fun volunteerAllowancePayoutDisclaimer(): String = travelExpensePayoutDisclaimer()

fun volunteerAllowanceDeclarationBadge(declaration: VolunteerAllowanceSelfDeclarationDto): String =
    when (declaration.source) {
        VolunteerAllowanceDeclarationSource.IN_APP -> gettext("In Lapis bestätigt am %1", declaration.declaredAt.date.toString())
        VolunteerAllowanceDeclarationSource.ON_PAPER ->
            gettext(
                "Papierform, unterschrieben am %1, erfasst von %2",
                declaration.signedOn?.toString() ?: "-",
                declaration.recordedByDisplayName,
            )
    }

/** Der EINE Ort, an dem der rohe `executionError`-Wire-Code auf ein erschöpfendes `when` abgebildet wird. */
internal enum class VolunteerAllowancePostingErrorCode(
    val wireCode: String,
) {
    VOLUNTEER_ALLOWANCE_ACCOUNT_NOT_CONFIGURED("volunteer_allowance_account_not_configured"),
    PAYMENT_BANK_ACCOUNT_NOT_CONFIGURED("payment_bank_account_not_configured"),
    LEDGER_ACCOUNT_INACTIVE("ledger_account_inactive"),
    VOLUNTEER_ALLOWANCE_ACCOUNT_NOT_EXPENSE_TYPE("volunteer_allowance_account_not_expense_type"),
    CASH_REGISTER_BALANCE_INSUFFICIENT("cash_register_balance_insufficient"),
    CASH_VOUCHER_REQUIRED("cash_voucher_required"),
    SELF_DECLARATION_MISSING("self_declaration_missing"),
    ALLOWANCE_TOTAL_CHANGED_SINCE_DECISION("allowance_total_changed_since_decision"),
    PAYMENT_NO_LONGER_CONSISTENT("payment_no_longer_consistent"),
}

internal fun parseVolunteerAllowancePostingErrorCode(raw: String): VolunteerAllowancePostingErrorCode? =
    VolunteerAllowancePostingErrorCode.entries.firstOrNull { it.wireCode == raw }

/** Niemals der Rohcode im Ergebnis; unbekannter Code -> generischer Satz. */
fun volunteerAllowancePostingErrorMessage(raw: String?): String {
    val code = raw?.let { parseVolunteerAllowancePostingErrorCode(it) }
    return when (code) {
        null -> tr("Die Buchung ist fehlgeschlagen (unbekannte Ursache). Bitte wenden Sie sich an die Administration.")
        VolunteerAllowancePostingErrorCode.VOLUNTEER_ALLOWANCE_ACCOUNT_NOT_CONFIGURED ->
            tr("Das Aufwandskonto für Ehrenamtspauschalen ist noch nicht zugeordnet -- bitte einen ADMIN informieren.")
        VolunteerAllowancePostingErrorCode.PAYMENT_BANK_ACCOUNT_NOT_CONFIGURED ->
            tr("Das Bankkonto ist noch nicht zugeordnet -- bitte einen ADMIN informieren.")
        VolunteerAllowancePostingErrorCode.LEDGER_ACCOUNT_INACTIVE ->
            tr("Eines der zugeordneten Konten ist nicht mehr aktiv -- bitte einen ADMIN informieren.")
        VolunteerAllowancePostingErrorCode.VOLUNTEER_ALLOWANCE_ACCOUNT_NOT_EXPENSE_TYPE ->
            tr("Das Aufwandskonto für Ehrenamtspauschalen hat den falschen Kontentyp -- bitte einen ADMIN informieren.")
        VolunteerAllowancePostingErrorCode.CASH_REGISTER_BALANCE_INSUFFICIENT ->
            tr("Der Kassenbestand reicht derzeit nicht aus -- ein erneuter Versuch kann später erfolgreich sein.")
        VolunteerAllowancePostingErrorCode.CASH_VOUCHER_REQUIRED ->
            tr("Für diese Buchung wird ein Belegverweis benötigt -- bitte einen ADMIN informieren.")
        VolunteerAllowancePostingErrorCode.SELF_DECLARATION_MISSING ->
            tr("Die Selbstauskunft der empfangenden Person fehlt noch -- bitte zuerst erfassen, dann erneut versuchen.")
        VolunteerAllowancePostingErrorCode.ALLOWANCE_TOTAL_CHANGED_SINCE_DECISION ->
            tr("Der Freibetragsverbrauch hat sich seit der Entscheidung verändert -- bitte die Entscheidung neu prüfen.")
        VolunteerAllowancePostingErrorCode.PAYMENT_NO_LONGER_CONSISTENT ->
            tr("Die Zahlung hat sich seit der Genehmigung verändert und kann so nicht gebucht werden.")
    }
}
