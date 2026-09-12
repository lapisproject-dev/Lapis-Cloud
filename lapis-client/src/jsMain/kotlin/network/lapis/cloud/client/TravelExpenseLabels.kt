package network.lapis.cloud.client

import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus

/**
 * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" -- reine, DOM-freie
 * Label-/Farb-/Text-Bausteine, gleiche Grammatik wie `ContributionReliefLabels.kt`: `when` über
 * `entries`, erschöpfend, `gettext(...)`.
 */
fun travelExpenseStatusLabel(status: TravelExpenseReportStatus): String =
    when (status) {
        TravelExpenseReportStatus.DRAFT -> tr("Entwurf")
        TravelExpenseReportStatus.REQUESTED -> tr("Eingereicht")
        // NIEMALS "Genehmigt" -- laut chk_ter_execution_error_state trägt jede APPROVED-Zeile
        // einen executionError (der Antrag ist genehmigt, aber die Buchung ist gescheitert).
        TravelExpenseReportStatus.APPROVED -> tr("Buchung fehlgeschlagen")
        TravelExpenseReportStatus.REJECTED -> tr("Abgelehnt")
        // NICHT "Ausgeführt" (Zhuo/Jobs-Ruling) -- der Endzustand bedeutet eine Buchung, keine
        // Auszahlung, siehe travelExpensePayoutDisclaimer().
        TravelExpenseReportStatus.EXECUTED -> tr("Zur Auszahlung gebucht")
        TravelExpenseReportStatus.WITHDRAWN -> tr("Zurückgezogen")
    }

fun travelExpenseStatusColor(status: TravelExpenseReportStatus): String =
    when (status) {
        TravelExpenseReportStatus.DRAFT -> "secondary"
        TravelExpenseReportStatus.REQUESTED -> "secondary"
        TravelExpenseReportStatus.APPROVED -> "danger"
        TravelExpenseReportStatus.REJECTED -> "danger"
        TravelExpenseReportStatus.EXECUTED -> "success"
        TravelExpenseReportStatus.WITHDRAWN -> "secondary"
    }

fun travelExpenseLineKindLabel(kind: TravelExpenseLineKind): String =
    when (kind) {
        TravelExpenseLineKind.MILEAGE -> tr("Fahrt")
        TravelExpenseLineKind.PER_DIEM -> tr("Tagespauschale")
        TravelExpenseLineKind.RECEIPTED -> tr("Beleg-Kosten")
    }

fun travelExpenseLineKindColor(kind: TravelExpenseLineKind): String =
    when (kind) {
        TravelExpenseLineKind.MILEAGE -> "info"
        TravelExpenseLineKind.PER_DIEM -> "warning"
        TravelExpenseLineKind.RECEIPTED -> "secondary"
    }

fun travelExpenseLineKindIcon(kind: TravelExpenseLineKind): String =
    when (kind) {
        TravelExpenseLineKind.MILEAGE -> "fas fa-car"
        TravelExpenseLineKind.PER_DIEM -> "fas fa-calendar-day"
        TravelExpenseLineKind.RECEIPTED -> "fas fa-file-invoice"
    }

/** Pflicht-Hinweiszeile unter JEDER EXECUTED-Karte (Jobs-Ruling: kein grüner Haken ohne sie). */
fun travelExpensePayoutDisclaimer(): String = tr("Die Überweisung veranlasst die Kasse separat — dieser Antrag löst keine Zahlung aus.")

enum class TravelExpenseStepState { PAST, CURRENT, FUTURE }

/**
 * Vier Pillen: Entwurf · Eingereicht · Entschieden · Zur Auszahlung gebucht. [TravelExpenseReportStatus
 * .APPROVED] wird EXPLIZIT wie ein Zwischenzustand behandelt (Pille 1-2 PAST, Pille 3 CURRENT,
 * Pille 4 FUTURE) -- ein gescheiterter Buchungsversuch ist kein Endzustand, `retryPosting` kann
 * ihn noch nach EXECUTED bringen.
 */
fun travelExpenseStepStates(status: TravelExpenseReportStatus): List<TravelExpenseStepState> =
    when (status) {
        TravelExpenseReportStatus.DRAFT ->
            listOf(
                TravelExpenseStepState.CURRENT,
                TravelExpenseStepState.FUTURE,
                TravelExpenseStepState.FUTURE,
                TravelExpenseStepState.FUTURE,
            )
        TravelExpenseReportStatus.REQUESTED ->
            listOf(
                TravelExpenseStepState.PAST,
                TravelExpenseStepState.CURRENT,
                TravelExpenseStepState.FUTURE,
                TravelExpenseStepState.FUTURE,
            )
        TravelExpenseReportStatus.APPROVED ->
            listOf(TravelExpenseStepState.PAST, TravelExpenseStepState.PAST, TravelExpenseStepState.CURRENT, TravelExpenseStepState.FUTURE)
        TravelExpenseReportStatus.REJECTED ->
            listOf(TravelExpenseStepState.PAST, TravelExpenseStepState.PAST, TravelExpenseStepState.CURRENT, TravelExpenseStepState.FUTURE)
        TravelExpenseReportStatus.WITHDRAWN ->
            listOf(TravelExpenseStepState.PAST, TravelExpenseStepState.PAST, TravelExpenseStepState.CURRENT, TravelExpenseStepState.FUTURE)
        TravelExpenseReportStatus.EXECUTED ->
            listOf(TravelExpenseStepState.PAST, TravelExpenseStepState.PAST, TravelExpenseStepState.PAST, TravelExpenseStepState.CURRENT)
    }

/** Dritte Pille -- das tatsächliche Entscheidungsergebnis, nie das rohe Enum-Literal. */
fun travelExpenseStep3Label(status: TravelExpenseReportStatus): String =
    when (status) {
        TravelExpenseReportStatus.DRAFT -> tr("Entschieden")
        TravelExpenseReportStatus.REQUESTED -> tr("Entschieden")
        TravelExpenseReportStatus.APPROVED -> tr("Buchung fehlgeschlagen")
        TravelExpenseReportStatus.REJECTED -> tr("Abgelehnt")
        TravelExpenseReportStatus.WITHDRAWN -> tr("Zurückgezogen")
        TravelExpenseReportStatus.EXECUTED -> tr("Entschieden")
    }

/** Symbol nach Dateityp -- Ives Zugeständnis statt Inline-Vorschau (Forstall-Ruling: MIME-Allowlist ohne SVG). */
fun receiptIcon(mimeType: String): String =
    when (mimeType) {
        "application/pdf" -> "fas fa-file-pdf"
        "image/jpeg", "image/png" -> "fas fa-file-image"
        else -> "fas fa-file"
    }

fun receiptSizeLabel(sizeBytes: Long): String =
    when {
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> "${sizeBytes / (1024 * 1024)} MB"
    }

/**
 * Der EINE Ort, an dem der rohe `executionError`-Wire-Code auf ein erschöpfendes `when`
 * abgebildet wird. Literale wortgleich zu `TravelExpensePostingBridge`/`TravelExpenseExecution`.
 */
internal enum class TravelExpensePostingErrorCode(
    val wireCode: String,
) {
    TRAVEL_EXPENSE_ACCOUNT_NOT_CONFIGURED("travel_expense_account_not_configured"),
    PAYMENT_BANK_ACCOUNT_NOT_CONFIGURED("payment_bank_account_not_configured"),
    LEDGER_ACCOUNT_INACTIVE("ledger_account_inactive"),
    TRAVEL_EXPENSE_ACCOUNT_NOT_EXPENSE_TYPE("travel_expense_account_not_expense_type"),
    REPORT_NO_LONGER_CONSISTENT("report_no_longer_consistent"),
    CASH_REGISTER_BALANCE_INSUFFICIENT("cash_register_balance_insufficient"),
    CASH_VOUCHER_REQUIRED("cash_voucher_required"),
}

internal fun parseTravelExpensePostingErrorCode(raw: String): TravelExpensePostingErrorCode? =
    TravelExpensePostingErrorCode.entries.firstOrNull { it.wireCode == raw }

/** Niemals der Rohcode im Ergebnis; unbekannter Code -> generischer Satz. */
fun travelExpensePostingErrorMessage(raw: String?): String {
    val code = raw?.let { parseTravelExpensePostingErrorCode(it) }
    return when (code) {
        null -> tr("Die Buchung ist fehlgeschlagen (unbekannte Ursache). Bitte wenden Sie sich an die Administration.")
        TravelExpensePostingErrorCode.TRAVEL_EXPENSE_ACCOUNT_NOT_CONFIGURED ->
            tr("Das Reisekosten-Aufwandskonto ist noch nicht zugeordnet -- bitte einen ADMIN informieren.")
        TravelExpensePostingErrorCode.PAYMENT_BANK_ACCOUNT_NOT_CONFIGURED ->
            tr("Das Bankkonto ist noch nicht zugeordnet -- bitte einen ADMIN informieren.")
        TravelExpensePostingErrorCode.LEDGER_ACCOUNT_INACTIVE ->
            tr("Eines der zugeordneten Konten ist nicht mehr aktiv -- bitte einen ADMIN informieren.")
        TravelExpensePostingErrorCode.TRAVEL_EXPENSE_ACCOUNT_NOT_EXPENSE_TYPE ->
            tr("Das Reisekosten-Aufwandskonto hat den falschen Kontentyp -- bitte einen ADMIN informieren.")
        TravelExpensePostingErrorCode.REPORT_NO_LONGER_CONSISTENT ->
            tr("Der Antrag hat sich seit der Genehmigung verändert und kann so nicht gebucht werden.")
        TravelExpensePostingErrorCode.CASH_REGISTER_BALANCE_INSUFFICIENT ->
            tr("Der Kassenbestand reicht derzeit nicht aus -- ein erneuter Versuch kann später erfolgreich sein.")
        TravelExpensePostingErrorCode.CASH_VOUCHER_REQUIRED ->
            tr("Für diese Buchung wird ein Belegverweis benötigt -- bitte einen ADMIN informieren.")
    }
}
