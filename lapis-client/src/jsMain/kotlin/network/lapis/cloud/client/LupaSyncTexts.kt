package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbNoticeCode
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus

/*
 * V1.9.226 "LupaBB-Abgleich" -- every text of the sync page. The server sends enum CODES only (never a German free text); this file turns them
 * into the translated words. A reason code never carries a name or an address; the one parameter that is data (a status pair, a field key or
 * a regional association's name) is shown by [lupaReasonParam] and never put into a translated sentence.
 */

internal fun lupaFieldLabel(field: LupaBbField): String =
    when (field) {
        LupaBbField.PERSON_NUMBER -> tr("Personennummer")
        LupaBbField.DISPLAY_NAME -> tr("Anzeigename")
        LupaBbField.GIVEN_NAME -> tr("Vorname")
        LupaBbField.FAMILY_NAME -> tr("Nachname")
        LupaBbField.TITLE -> tr("Titel")
        LupaBbField.NAME_AFFIX -> tr("Namenszusatz")
        LupaBbField.COMPANY -> tr("Firma")
        LupaBbField.PERSON_TYPE -> tr("Personentyp")
        LupaBbField.STREET -> tr("Straße")
        LupaBbField.ADDRESS_SUPPLEMENT -> tr("Adresszusatz")
        LupaBbField.PO_BOX -> tr("Postfach")
        LupaBbField.POSTAL_CODE -> tr("Postleitzahl")
        LupaBbField.CITY -> tr("Ort")
        LupaBbField.COUNTRY -> tr("Land")
        LupaBbField.DATE_OF_BIRTH -> tr("Geburtsdatum")
        LupaBbField.PHONE -> tr("Telefon")
        LupaBbField.MOBILE -> tr("Mobil")
        LupaBbField.REGIONAL_CHAPTER -> tr("Landesverband")
        LupaBbField.STATUS -> tr("Status")
        LupaBbField.MISSING_FLAG -> tr("Markierung \"nicht mehr in LupaBB\"")
    }

internal fun lupaCategoryLabel(category: LupaBbCategory): String =
    when (category) {
        LupaBbCategory.NEW -> tr("Neu")
        LupaBbCategory.CHANGED -> tr("Geändert")
        LupaBbCategory.UNCHANGED -> tr("Unverändert")
        LupaBbCategory.AMBIGUOUS -> tr("Unklare Zuordnung")
        LupaBbCategory.SKIPPED -> tr("Übersprungen")
        LupaBbCategory.ERROR -> tr("Fehler")
        LupaBbCategory.MISSING -> tr("Nicht mehr in LupaBB")
    }

/** The symbol that goes with the word (never a symbol or a colour alone). */
internal fun lupaCategorySymbol(category: LupaBbCategory): String =
    when (category) {
        LupaBbCategory.NEW -> "+"
        LupaBbCategory.CHANGED -> "~"
        LupaBbCategory.UNCHANGED -> "="
        LupaBbCategory.AMBIGUOUS -> "?"
        LupaBbCategory.SKIPPED -> "↷"
        LupaBbCategory.ERROR -> "!"
        LupaBbCategory.MISSING -> "-"
    }

internal fun lupaCategoryCss(category: LupaBbCategory): String =
    when (category) {
        LupaBbCategory.NEW -> "text-success"
        LupaBbCategory.CHANGED -> "lapis-lupabb-changed"
        LupaBbCategory.UNCHANGED -> "text-secondary"
        LupaBbCategory.AMBIGUOUS -> "text-warning fw-bold"
        LupaBbCategory.SKIPPED -> "text-secondary"
        LupaBbCategory.ERROR -> "text-danger fw-bold"
        LupaBbCategory.MISSING -> "text-secondary"
    }

/** Word and number only: "Neu (3)". The words are the category words, so no filter has a text of its own except the two that are not a category. */
internal fun lupaFilterLabel(
    filter: LupaBbPreviewFilter,
    count: Int,
): String {
    val word =
        when (filter) {
            LupaBbPreviewFilter.ACTION_NEEDED -> tr("Handlungsbedarf")
            LupaBbPreviewFilter.NEW -> lupaCategoryLabel(LupaBbCategory.NEW)
            LupaBbPreviewFilter.CHANGED -> lupaCategoryLabel(LupaBbCategory.CHANGED)
            LupaBbPreviewFilter.UNCHANGED -> lupaCategoryLabel(LupaBbCategory.UNCHANGED)
            LupaBbPreviewFilter.AMBIGUOUS -> lupaCategoryLabel(LupaBbCategory.AMBIGUOUS)
            LupaBbPreviewFilter.SKIPPED -> lupaCategoryLabel(LupaBbCategory.SKIPPED)
            LupaBbPreviewFilter.ERROR -> lupaCategoryLabel(LupaBbCategory.ERROR)
            LupaBbPreviewFilter.MISSING -> lupaCategoryLabel(LupaBbCategory.MISSING)
            LupaBbPreviewFilter.ALL -> tr("Alle")
        }
    // the segment label is a finished text: resolve the translation marker BEFORE the number is appended
    return "${resolvedAttributeText(word)} ($count)"
}

@Suppress("CyclomaticComplexMethod")
internal fun lupaReasonText(code: LupaBbRowReasonCode): String =
    when (code) {
        LupaBbRowReasonCode.NO_EMAIL -> tr("In LupaBB ist keine E-Mail-Adresse hinterlegt.")
        LupaBbRowReasonCode.INVALID_EMAIL -> tr("Die E-Mail-Adresse in LupaBB ist ungültig.")
        LupaBbRowReasonCode.INVALID_DATE -> tr("Ein Datum in LupaBB ist ungültig.")
        LupaBbRowReasonCode.FIELD_TOO_LONG -> tr("Ein Feld in LupaBB ist zu lang.")
        LupaBbRowReasonCode.CONTROL_CHARACTER -> tr("Ein Feld in LupaBB enthält ein unzulässiges Steuerzeichen.")
        LupaBbRowReasonCode.INVALID_PERSON_NUMBER -> tr("Die Personennummer ist ungültig.")
        LupaBbRowReasonCode.DUPLICATE_PERSON_NUMBER -> tr("Die Personennummer kommt in LupaBB mehrfach vor.")
        LupaBbRowReasonCode.NO_NAME -> tr("In LupaBB ist weder ein Name noch eine Firma hinterlegt.")
        LupaBbRowReasonCode.UNKNOWN_STATUS -> tr("Der Status aus LupaBB ist unbekannt.")
        LupaBbRowReasonCode.CANCELLED_STATUS -> tr("Der Datensatz ist in LupaBB storniert.")
        LupaBbRowReasonCode.ANONYMIZED -> tr("Das Mitglied wurde in Lapis Cloud anonymisiert.")
        LupaBbRowReasonCode.ERASED_IN_LAPIS -> tr("Die Person wurde in Lapis Cloud datenschutzrechtlich gelöscht.")
        LupaBbRowReasonCode.ADMIN_ROLE_MANUAL -> tr("Mitglied mit Verwaltungsrolle: bitte von Hand abgleichen.")
        LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED -> tr("Dieser Statuswechsel ist in Lapis Cloud nicht erlaubt.")
        LupaBbRowReasonCode.CHAPTER_REQUIRED -> tr("Ein aktives Mitglied braucht einen Landesverband.")
        LupaBbRowReasonCode.EMAIL_DIFFERS ->
            tr("Die E-Mail-Adresse in LupaBB weicht ab. Änderung bitte über die E-Mail-Änderung.")
        LupaBbRowReasonCode.EMAIL_BELONGS_TO_OTHER ->
            tr(
                "Die E-Mail-Adresse gehört einem Mitglied, das schon mit einer anderen Person verknüpft ist.",
            )
        LupaBbRowReasonCode.NAME_MISMATCH -> tr("Die E-Mail-Adresse passt, der Name nicht.")
        LupaBbRowReasonCode.MULTIPLE_CANDIDATES -> tr("Es kommen mehrere Mitglieder in Frage.")
        LupaBbRowReasonCode.COUNTRY_AMBIGUOUS -> tr("Das Land lässt sich nicht eindeutig zuordnen und bleibt unverändert.")
        LupaBbRowReasonCode.CHAPTER_NOT_FOUND -> tr("Diesen Landesverband gibt es in Lapis Cloud nicht.")
        LupaBbRowReasonCode.EMAIL_TAKEN -> tr("Die E-Mail-Adresse wurde inzwischen einem anderen Mitglied zugeordnet.")
        LupaBbRowReasonCode.PERSON_NUMBER_TAKEN -> tr("Die Personennummer wurde inzwischen einem anderen Mitglied zugeordnet.")
        LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW -> tr("Das Mitglied wurde seit der Vorschau geändert.")
        LupaBbRowReasonCode.WRITE_FAILED -> tr("Der Datensatz konnte nicht gespeichert werden.")
        LupaBbRowReasonCode.LINKED_WAITING -> tr("Verknüpft: wird beim nächsten Abgleich übernommen.")
        LupaBbRowReasonCode.MISSING_IN_LUPABB -> tr("Das Mitglied kommt in LupaBB nicht mehr vor. Es wird nur markiert, nicht gelöscht.")
        LupaBbRowReasonCode.REAPPEARED -> tr("Das Mitglied ist in LupaBB wieder aufgetaucht, die Markierung entfällt.")
        LupaBbRowReasonCode.LEAVES_DECEASED -> tr("Das Mitglied war als verstorben geführt und wird wieder aktiviert bzw. umgestuft.")
    }

internal fun lupaNoticeText(notice: LupaBbNoticeCode): String =
    when (notice) {
        LupaBbNoticeCode.CHAPTER_NOT_SUPPLIED ->
            tr("LupaBB liefert die Landesverbände nicht mit den Mitgliedern. Die Zuordnung zu Landesverbänden bleibt unverändert.")
    }

/** A value of the comparison shown in words: a status by name, a person type, a date in the local format; anything else as it is. */
internal fun lupaDiffValue(
    field: LupaBbField,
    raw: String?,
): String {
    if (raw == null) return "–"
    return when (field) {
        LupaBbField.STATUS -> runCatching { memberStatusLabel(MemberStatus.valueOf(raw)) }.getOrDefault(raw)
        LupaBbField.PERSON_TYPE ->
            when (raw) {
                "NATURAL" -> tr("Natürliche Person")
                "LEGAL" -> tr("Juristische Person")
                else -> raw
            }
        LupaBbField.DATE_OF_BIRTH, LupaBbField.MISSING_FLAG -> runCatching { formatDate(LocalDate.parse(raw)) }.getOrDefault(raw)
        else -> raw
    }
}
