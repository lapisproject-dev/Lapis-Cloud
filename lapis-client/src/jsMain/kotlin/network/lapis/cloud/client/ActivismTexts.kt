package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismForm
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismViewerRole

/**
 * V1.9.246 "Aktionen A1" -- the fixed, translated texts of the action pages. Every text here is static (`gettext`), never data from the server.
 * The default notice per kind is a NEUTRAL, general reminder to check the rules yourself; the operator replaces it per country and kind.
 * Nothing here judges a text a person wrote, and no legal advice is given.
 */
internal object ActivismTexts {
    fun kind(kind: ActivismKind): String =
        when (kind) {
            ActivismKind.INFO_STAND -> gettext("Infostand")
            ActivismKind.POSTERING -> gettext("Plakatieren")
            ActivismKind.SIGNATURES -> gettext("Unterschriftensammlung")
            ActivismKind.DEMONSTRATION -> gettext("Kundgebung oder Demonstration")
            ActivismKind.ACTION_DAY -> gettext("Aktionstag")
            ActivismKind.DOOR_TO_DOOR -> gettext("Haustürgespräche")
            ActivismKind.ONLINE -> gettext("Online-Aktion")
            ActivismKind.OTHER -> gettext("Sonstiges")
        }

    fun status(status: ActivismStatus): String =
        when (status) {
            ActivismStatus.IDEA -> gettext("Idee")
            ActivismStatus.PLANNED -> gettext("Geplant")
            ActivismStatus.RUNNING -> gettext("Läuft")
            ActivismStatus.COMPLETED -> gettext("Abgeschlossen")
            ActivismStatus.AWAITING_REVIEW -> gettext("Wartet auf Freigabe")
            ActivismStatus.WITHDRAWN -> gettext("Zurückgezogen")
        }

    fun form(form: ActivismForm): String =
        when (form) {
            ActivismForm.APPOINTMENT -> gettext("Termin")
            ActivismForm.PROGRESS -> gettext("Fortschritt")
            ActivismForm.SIGNAL -> gettext("Ich komme")
        }

    fun contributionStatus(status: ActivismContributionStatus): String =
        when (status) {
            ActivismContributionStatus.REPORTED -> gettext("Gemeldet")
            ActivismContributionStatus.CONFIRMED -> gettext("Bestätigt")
            ActivismContributionStatus.REJECTED -> gettext("Abgelehnt")
        }

    fun role(role: ActivismViewerRole): String =
        when (role) {
            ActivismViewerRole.MEMBER -> gettext("Mitglied")
            ActivismViewerRole.RESPONSIBLE -> gettext("Verantwortlich")
            ActivismViewerRole.MANAGER -> gettext("Vorstand oder Administration")
        }

    /** Shown above "Speichern" in the form. A reminder, no check, no tick box, no block. */
    fun warningSentence(): String =
        gettext("Bitte schreiben Sie keine Namen oder Angaben über andere Personen in diese Aktion. Alle Mitglieder können sie lesen.")

    fun legalDisclaimer(): String = gettext("Keine Rechtsberatung.")

    /** The standard notice of a kind when the operator has not written one. */
    fun defaultNotice(kind: ActivismKind): String =
        when (kind) {
            ActivismKind.INFO_STAND ->
                gettext("Bitte klären Sie vorab selbst, ob für den Infostand eine Erlaubnis oder Anmeldung nötig ist.")
            ActivismKind.POSTERING ->
                gettext("Bitte klären Sie vorab selbst, wo und wie lange plakatiert werden darf und ob eine Erlaubnis nötig ist.")
            ActivismKind.SIGNATURES ->
                gettext("Bitte klären Sie vorab selbst, ob für das Sammeln von Unterschriften eine Erlaubnis nötig ist und was dabei gilt.")
            ActivismKind.DEMONSTRATION ->
                gettext("Bitte klären Sie vorab selbst, ob die Versammlung angezeigt oder angemeldet werden muss und bis wann.")
            ActivismKind.ACTION_DAY ->
                gettext("Bitte klären Sie vorab selbst, ob für die einzelnen Teile des Aktionstags eine Erlaubnis nötig ist.")
            ActivismKind.DOOR_TO_DOOR ->
                gettext("Bitte klären Sie vorab selbst, was bei Gesprächen an der Haustür gilt, und beachten Sie den Datenschutz.")
            ActivismKind.ONLINE ->
                gettext("Bitte beachten Sie die Regeln der genutzten Plattform und den Datenschutz.")
            ActivismKind.OTHER ->
                gettext("Bitte klären Sie vorab selbst, ob für Ihre Aktion Regeln oder Genehmigungen gelten.")
        }

    fun noticeCountryOptions(): List<Pair<String, String>> =
        listOf(
            "DE" to gettext("Deutschland"),
            "AT" to gettext("Österreich"),
            "CH" to gettext("Schweiz"),
            "GE" to gettext("Georgien"),
            "LI" to gettext("Liechtenstein"),
            "LU" to gettext("Luxemburg"),
        )
}
