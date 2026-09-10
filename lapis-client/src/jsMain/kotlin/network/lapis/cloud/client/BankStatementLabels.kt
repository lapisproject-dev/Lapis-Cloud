package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.BankCsvDialect
import network.lapis.cloud.shared.domain.BankStatementFormat
import network.lapis.cloud.shared.domain.BankStatementImportRejectionDto
import network.lapis.cloud.shared.domain.BankStatementImportWarningCode
import network.lapis.cloud.shared.domain.BankStatementLineStatus
import network.lapis.cloud.shared.domain.BankStatementRejectionCode

/**
 * Welle V1.4.5.1.1 -- deutsche Label-/Badge-Farb-Tabellen, gleiche Grammatik wie
 * `DunningLabels.kt`/`AccountingLabels.kt`: `when` über `entries`, erschöpfend, `gettext(...)`.
 *
 * **Farbregel (Design-Team, von Jobs bestätigt), als Test gepinnt**: [bankStatementLineStatusColor]
 * gibt für [BankStatementLineStatus.UNMATCHED] bewusst `"secondary"` (neutral-grau) zurück, NIE
 * `"danger"` -- unerledigte Zuordnungsarbeit ist kein Fehler.
 */
fun bankStatementLineStatusLabel(status: BankStatementLineStatus): String =
    when (status) {
        BankStatementLineStatus.UNMATCHED -> gettext("Nicht zugeordnet")
        BankStatementLineStatus.SUGGESTED -> gettext("Vorschlag")
        BankStatementLineStatus.AMBIGUOUS -> gettext("Mehrdeutig")
        BankStatementLineStatus.POSTED -> gettext("Gebucht")
        BankStatementLineStatus.IGNORED -> gettext("Ignoriert")
    }

fun bankStatementLineStatusColor(status: BankStatementLineStatus): String =
    when (status) {
        BankStatementLineStatus.UNMATCHED -> "secondary"
        BankStatementLineStatus.SUGGESTED -> "info"
        BankStatementLineStatus.AMBIGUOUS -> "warning"
        BankStatementLineStatus.POSTED -> "success"
        BankStatementLineStatus.IGNORED -> "light"
    }

fun bankStatementFormatLabel(format: BankStatementFormat): String =
    when (format) {
        BankStatementFormat.CSV -> gettext("CSV")
        BankStatementFormat.MT940 -> gettext("MT940")
    }

fun bankCsvDialectLabel(dialect: BankCsvDialect): String =
    when (dialect) {
        BankCsvDialect.SPARKASSE_CAMT -> gettext("Sparkasse (CAMT-CSV)")
        BankCsvDialect.VR_BANK -> gettext("VR-Bank")
        BankCsvDialect.DKB -> gettext("DKB")
        BankCsvDialect.POSTBANK -> gettext("Postbank")
        BankCsvDialect.COMDIRECT -> gettext("Comdirect")
        BankCsvDialect.GENERIC -> gettext("Generisches CSV-Format")
    }

/**
 * Übersetzte, handlungsanweisende Meldung je [BankStatementRejectionCode] -- rein, DOM-frei, direkt
 * testbar. Die angezeigte Meldung kommt IMMER von hier, nie aus [BankStatementImportRejectionDto
 * .detail] (siehe dessen KDoc).
 */
fun bankStatementRejectionMessage(rejection: BankStatementImportRejectionDto): String =
    when (rejection.code) {
        BankStatementRejectionCode.FILE_TOO_LARGE ->
            gettext("Die Datei ist größer als das Limit von %1.", bankStatementMaxUploadSizeLabel())
        BankStatementRejectionCode.NO_FILE_PART ->
            gettext("Es wurde keine Datei übertragen. Bitte eine CSV- oder MT940-Datei auswählen.")
        BankStatementRejectionCode.RATE_LIMITED ->
            gettext("Zu viele Upload-Versuche in kurzer Folge. Bitte in einigen Minuten erneut versuchen.")
        BankStatementRejectionCode.FORMAT_UNRECOGNIZED -> {
            val observedHeaderFields = rejection.observedHeaderFields
            if (observedHeaderFields.isNullOrEmpty()) {
                gettext(
                    "Das Dateiformat wurde nicht erkannt. Unterstützt werden Sparkassen-CAMT-CSV, ein " +
                        "generischer CSV-Auffangdialekt und MT940.",
                )
            } else {
                gettext(
                    "Das Dateiformat wurde nicht erkannt. Gefundene Spalten: %1. Unterstützt werden " +
                        "Sparkassen-CAMT-CSV, ein generischer CSV-Auffangdialekt und MT940.",
                    observedHeaderFields.joinToString(", "),
                )
            }
        }
        BankStatementRejectionCode.PARSE_FAILED ->
            if (rejection.lineNumber != null) {
                gettext("Zeile %1 konnte nicht gelesen werden.", rejection.lineNumber)
            } else {
                gettext("Die Datei konnte nicht gelesen werden.")
            }
        BankStatementRejectionCode.MT940_BALANCE_MISMATCH ->
            gettext(
                "Der Auszug ist in sich nicht schlüssig: Anfangssaldo plus Umsätze ergeben nicht den " +
                    "Endsaldo. Bitte den Auszug bei der Bank neu anfordern.",
            )
        BankStatementRejectionCode.FOREIGN_ACCOUNT ->
            gettext("Dieser Auszug gehört zu einem anderen Konto als dem in den Organisationseinstellungen hinterlegten.")
        BankStatementRejectionCode.TOO_MANY_LINES ->
            gettext("Der Auszug hat mehr als %1 Zeilen. Bitte einen kürzeren Zeitraum exportieren.", MAX_STATEMENT_LINES_LABEL)
        BankStatementRejectionCode.CONTROL_CHARACTER ->
            if (rejection.lineNumber != null) {
                gettext("Zeile %1 enthält ein ungültiges Steuerzeichen und kann nicht verarbeitet werden.", rejection.lineNumber)
            } else {
                gettext("Die Datei enthält ein ungültiges Steuerzeichen und kann nicht verarbeitet werden.")
            }
        BankStatementRejectionCode.ALREADY_IMPORTED ->
            gettext("Diese Datei wurde bereits importiert. Der frühere Import steht in der Liste oben.")
    }

/** Mirrors `BankStatementImportService.MAX_STATEMENT_LINES` (server, 2000) -- see [BankStatementHttp] KDoc "hand-sync" convention. */
private const val MAX_STATEMENT_LINES_LABEL = 2000

/** `"5 MB"` -- derived from [BankStatementHttp.MAX_UPLOAD_BYTES], never a second hardcoded literal. */
private fun bankStatementMaxUploadSizeLabel(): String = "${BankStatementHttp.MAX_UPLOAD_BYTES / (1024 * 1024)} MB"

/**
 * Review fix (MAJOR, Welle V1.4.5.1.1 Runde 2) -- übersetzte Meldung je
 * [BankStatementImportWarningCode], gleiche Grammatik wie [bankStatementRejectionMessage]. Die
 * angezeigte Meldung kommt IMMER von hier, nie aus [network.lapis.cloud.shared.domain
 * .BankStatementImportResultDto.warnings] (siehe dessen KDoc). [IBAN_MATCHING_UNAVAILABLE] nennt
 * bewusst NICHT den Namen der Umgebungsvariable (`LAPIS_SECRET_ENCRYPTION_KEY`) -- ein internes
 * Betriebsdetail, das den Schatzmeister nichts angeht.
 */
fun bankStatementImportWarningMessage(code: BankStatementImportWarningCode): String =
    when (code) {
        BankStatementImportWarningCode.NO_BANK_ACCOUNT_CONFIGURED ->
            gettext("Kein Bankkonto in den Organisationseinstellungen hinterlegt -- Kontoprüfung übersprungen.")
        BankStatementImportWarningCode.LEGACY_ACCOUNT_IBAN_FORMAT ->
            gettext("Die Kontokennung des Auszugs ist keine gültige IBAN (Altformat?) -- Kontoprüfung übersprungen.")
        BankStatementImportWarningCode.IBAN_MATCHING_UNAVAILABLE ->
            gettext("Der IBAN-Abgleich gegen SEPA-Mandate ist auf diesem Server derzeit nicht verfügbar.")
    }
