package network.lapis.cloud.client

import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.PartgImportErrorCode
import network.lapis.cloud.shared.domain.PartgImportRejectionCode

/** One fixed text per error code of the Excel import (V1.9.225); free cell text never travels, so nothing here is built from server data. */
internal fun partgImportErrorText(code: PartgImportErrorCode): String =
    when (code) {
        PartgImportErrorCode.NOT_A_XLSX -> tr("Die Datei ist keine Excel-Datei (.xlsx).")
        PartgImportErrorCode.UNSUPPORTED_FILE -> tr("Diese Excel-Datei ist anders aufgebaut als erwartet und kann nicht gelesen werden.")
        PartgImportErrorCode.MACROS_NOT_ALLOWED -> tr("Dateien mit Makros werden nicht angenommen.")
        PartgImportErrorCode.TOO_LARGE -> tr("Die Datei ist zu groß oder zu umfangreich.")
        PartgImportErrorCode.TEMPLATE_VERSION -> tr("Das ist nicht die Vorlage dieser Version. Bitte laden Sie die Vorlage neu herunter.")
        PartgImportErrorCode.TARGET_MISMATCH -> tr("Die Datei gehört zu einer anderen Gliederung oder einem anderen Jahr.")
        PartgImportErrorCode.UNKNOWN_CODE -> tr("Diese Kennung gibt es nicht.")
        PartgImportErrorCode.DUPLICATE_CODE -> tr("Diese Kennung kommt mehrfach vor.")
        PartgImportErrorCode.NEGATIVE_VALUE -> tr("Negative Beträge sind nicht möglich.")
        PartgImportErrorCode.NOT_A_NUMBER -> tr("Hier steht kein gültiger Betrag.")
        PartgImportErrorCode.TOO_MANY_DECIMALS -> tr("Höchstens zwei Nachkommastellen sind möglich.")
        PartgImportErrorCode.VALUE_TOO_LARGE -> tr("Der Betrag ist zu groß.")
        PartgImportErrorCode.DATE_VALUE -> tr("Hier steht ein Datum statt eines Betrags.")
        PartgImportErrorCode.FORMULA_WITHOUT_RESULT ->
            tr(
                "Die Formel hat noch kein Ergebnis. Bitte speichern Sie die Datei in Excel erneut.",
            )
        PartgImportErrorCode.CELL_ERROR -> tr("Diese Zelle enthält einen Fehlerwert.")
        PartgImportErrorCode.ROW_LIMIT -> tr("Unterhalb von Zeile 500 steht Inhalt, der nicht gelesen wird.")
        PartgImportErrorCode.TOO_MANY_ERRORS -> tr("Es gibt weitere Fehler. Bitte beheben Sie zuerst die genannten.")
        PartgImportErrorCode.INCONSISTENT -> tr("Die Datei ist beschädigt oder widersprüchlich aufgebaut.")
    }

/** One fixed text per refusal of the import routes; `null` (network failure) is the generic text. */
internal fun partgRejectionText(code: PartgImportRejectionCode?): String =
    when (code) {
        PartgImportRejectionCode.FORBIDDEN -> tr("Keine Berechtigung für diese Aktion.")
        PartgImportRejectionCode.NOT_ENABLED -> tr("Der Rechenschaftsbericht ist für diese Organisation nicht verfügbar.")
        PartgImportRejectionCode.INVALID_INPUT -> tr("Ungültige Anfrage.")
        PartgImportRejectionCode.TOO_LARGE -> tr("Die Datei ist zu groß (höchstens 2 MB).")
        PartgImportRejectionCode.RATE_LIMITED -> tr("Zu viele Anfragen – bitte kurz warten.")
        PartgImportRejectionCode.TOKEN_INVALID -> tr("Die Vorschau ist abgelaufen. Bitte laden Sie die Datei erneut hoch.")
        PartgImportRejectionCode.REVISION_CONFLICT ->
            tr("Die Werte wurden inzwischen geändert. Bitte laden Sie die Datei erneut hoch.")
        PartgImportRejectionCode.NOT_FOUND -> tr("Nicht gefunden.")
        null -> tr("Die Verbindung ist fehlgeschlagen. Bitte versuchen Sie es erneut.")
    }

/** The display name of a reporting unit. */
internal fun partgUnitLabel(
    level: network.lapis.cloud.shared.domain.PartgUnitLevel,
    chapterName: String?,
): String =
    when (level) {
        network.lapis.cloud.shared.domain.PartgUnitLevel.FEDERAL -> tr("Bundesverband")
        network.lapis.cloud.shared.domain.PartgUnitLevel.STATE -> io.kvision.i18n.gettext("Landesverband %1", chapterName.orEmpty())
        network.lapis.cloud.shared.domain.PartgUnitLevel.SUBORDINATE ->
            io.kvision.i18n.gettext("Nachgeordnete Gebietsverbände in %1 (zusammengefasst)", chapterName.orEmpty())
    }
