package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSepaBlocker

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
        TravelExpenseLineKind.DAY_ALLOWANCE -> tr("Tagegeld")
        TravelExpenseLineKind.PUBLIC_TRANSPORT -> tr("ÖPNV")
        TravelExpenseLineKind.ACCOMMODATION -> tr("Übernachtung")
        TravelExpenseLineKind.HOSPITALITY -> tr("Bewirtung")
        TravelExpenseLineKind.OTHER -> tr("Sonstiges")
    }

fun travelExpenseLineKindColor(kind: TravelExpenseLineKind): String =
    when (kind) {
        TravelExpenseLineKind.MILEAGE -> "info"
        TravelExpenseLineKind.PER_DIEM -> "warning"
        TravelExpenseLineKind.RECEIPTED -> "secondary"
        TravelExpenseLineKind.DAY_ALLOWANCE -> "warning"
        TravelExpenseLineKind.PUBLIC_TRANSPORT -> "info"
        TravelExpenseLineKind.ACCOMMODATION -> "primary"
        TravelExpenseLineKind.HOSPITALITY -> "secondary"
        TravelExpenseLineKind.OTHER -> "secondary"
    }

fun travelExpenseLineKindIcon(kind: TravelExpenseLineKind): String =
    when (kind) {
        TravelExpenseLineKind.MILEAGE -> "fas fa-car"
        TravelExpenseLineKind.PER_DIEM -> "fas fa-calendar-day"
        TravelExpenseLineKind.RECEIPTED -> "fas fa-file-invoice"
        TravelExpenseLineKind.DAY_ALLOWANCE -> "fas fa-clock"
        TravelExpenseLineKind.PUBLIC_TRANSPORT -> "fas fa-train"
        TravelExpenseLineKind.ACCOMMODATION -> "fas fa-bed"
        TravelExpenseLineKind.HOSPITALITY -> "fas fa-utensils"
        TravelExpenseLineKind.OTHER -> "fas fa-ellipsis"
    }

/** The kinds a member can still ADD in the editor (V1.9.122): mileage plus the four receipted kinds -- never a day line, never the two legacy kinds. */
internal val TRAVEL_EXPENSE_ADDABLE_KINDS: List<TravelExpenseLineKind> =
    listOf(
        TravelExpenseLineKind.MILEAGE,
        TravelExpenseLineKind.PUBLIC_TRANSPORT,
        TravelExpenseLineKind.ACCOMMODATION,
        TravelExpenseLineKind.HOSPITALITY,
        TravelExpenseLineKind.OTHER,
    )

/** The two settlement choices (V1.9.122) -- shown as a radio group WITHOUT a preselection. */
fun travelExpensePayoutChoiceLabel(choice: TravelExpensePayoutChoice): String =
    when (choice) {
        TravelExpensePayoutChoice.PAYOUT -> tr("Auszahlen")
        TravelExpensePayoutChoice.DONATION_WAIVER -> tr("Spendenquittung (Aufwandsverzicht)")
    }

/** Weekday name for the day-grid legend; reuses the weekday msgids that already exist for the recurring-event editor. */
fun travelExpenseWeekdayLabel(day: LocalDate): String =
    when (day.dayOfWeek) {
        DayOfWeek.MONDAY -> tr("Montag")
        DayOfWeek.TUESDAY -> tr("Dienstag")
        DayOfWeek.WEDNESDAY -> tr("Mittwoch")
        DayOfWeek.THURSDAY -> tr("Donnerstag")
        DayOfWeek.FRIDAY -> tr("Freitag")
        DayOfWeek.SATURDAY -> tr("Samstag")
        DayOfWeek.SUNDAY -> tr("Sonntag")
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
    TRAVEL_ADVANCE_ACCOUNT_NOT_CONFIGURED("travel_advance_account_not_configured"),
    DONATION_WAIVER_ACCOUNT_NOT_CONFIGURED("donation_waiver_account_not_configured"),
    DONATION_WAIVER_ACCOUNT_NOT_INCOME_TYPE("donation_waiver_account_not_income_type"),
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
        TravelExpensePostingErrorCode.TRAVEL_ADVANCE_ACCOUNT_NOT_CONFIGURED ->
            tr("Das Konto für bereits gezahlte Vorschüsse ist noch nicht zugeordnet -- bitte einen ADMIN informieren.")
        TravelExpensePostingErrorCode.DONATION_WAIVER_ACCOUNT_NOT_CONFIGURED ->
            tr("Das Ertragskonto für Aufwandsspenden ist noch nicht zugeordnet -- bitte einen ADMIN informieren.")
        TravelExpensePostingErrorCode.DONATION_WAIVER_ACCOUNT_NOT_INCOME_TYPE ->
            tr("Das Konto für Aufwandsspenden hat den falschen Kontentyp -- bitte einen ADMIN informieren.")
    }
}

/** V1.9.123: why a booked report cannot be put into a SEPA file right now (shown as visible text next to the disabled checkbox). */
fun travelExpenseSepaBlockerLabel(blocker: TravelExpenseSepaBlocker): String =
    when (blocker) {
        TravelExpenseSepaBlocker.NOT_BOOKED -> tr("Noch nicht gebucht")
        TravelExpenseSepaBlocker.NOT_PAYOUT -> tr("Verzicht als Spende")
        TravelExpenseSepaBlocker.NOTHING_PAYABLE -> tr("Nichts auszuzahlen")
        TravelExpenseSepaBlocker.NO_REFUND_IBAN -> tr("Keine Erstattungs-IBAN")
        TravelExpenseSepaBlocker.ALREADY_EXPORTED -> tr("Bereits exportiert")
        TravelExpenseSepaBlocker.OWN_REPORT -> tr("Eigene Abrechnung")
        TravelExpenseSepaBlocker.AMOUNT_MISMATCH_WITH_BOOKING -> tr("Betrag weicht von der Buchung ab")
        TravelExpenseSepaBlocker.ENCRYPTION_KEY_MISSING -> tr("Verschlüsselungsschlüssel fehlt")
        TravelExpenseSepaBlocker.PAID_OUTSIDE_LAPIS -> tr("Außerhalb von Lapis bezahlt")
    }

/** V1.9.123: the sentence for a refusal code of the SEPA routes; unknown codes get one generic sentence (never the raw code). */
fun travelExpenseSepaErrorMessage(code: String): String =
    when (code) {
        "execution_date_past" -> tr("Das Ausführungsdatum darf nicht in der Vergangenheit liegen.")
        "execution_date_too_far" -> tr("Das Ausführungsdatum darf höchstens 60 Tage in der Zukunft liegen.")
        "execution_date_not_target2_day" ->
            tr(
                "An diesem Tag werden keine SEPA-Überweisungen ausgeführt (Wochenende oder TARGET2-Feiertag).",
            )
        "debtor_account_missing" ->
            tr(
                "Es ist kein Belastungskonto hinterlegt. Bitte legen Sie unter Einstellungen › Bankkonten ein Konto an.",
            )
        "organization_name_missing" -> tr("Der Name der Organisation fehlt in den Einstellungen.")
        "encryption_key_missing" -> tr("Auf dem Server ist kein Verschlüsselungsschlüssel gesetzt.")
        "too_many_transactions" -> tr("Eine Datei darf höchstens 500 Überweisungen enthalten.")
        "reason_length_invalid" -> tr("Die Begründung muss 10 bis 500 Zeichen lang sein.")
        "reason_contains_account_number" -> tr("Die Begründung darf keine Kontonummer enthalten.")
        "rate_limited" -> tr("Zu viele Anfragen. Bitte warten Sie eine Minute.")
        "forbidden" -> tr("Nur Vorstand und Verwaltung dürfen SEPA-Dateien erzeugen.")
        else -> tr("Die SEPA-Datei konnte nicht erzeugt werden.")
    }

/** V1.9.123: the running total of a selection, in whole cents. The server recomputes the binding amount; this is display only. */
fun travelExpenseSepaSelectionSumCents(selected: Map<String, Long>): Long = selected.values.sum()

/** V1.9.123: `true` when the account holder differs from the member's name (compared case-insensitively, trimmed). */
fun travelExpenseSepaHolderDiffers(
    holder: String?,
    memberName: String,
): Boolean = holder != null && holder.trim().lowercase() != memberName.trim().lowercase()
