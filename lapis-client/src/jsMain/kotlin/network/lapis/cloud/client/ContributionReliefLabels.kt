package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionStatus

/**
 * Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche" -- reine, DOM-freie Label-/Farb-/
 * Text-Bausteine für [ContributionReliefKind]/[ContributionReliefStatus]/[ContributionReliefReason]
 * und die vom Server nur als Rohcode gelieferten Ausführungsfehler, gleiche Grammatik wie
 * `DunningLabels.kt`/`SocialModerationScreen.kt`s Label-Tabellen: `when` über `entries`,
 * erschöpfend, `gettext(...)`.
 *
 * **F5-Auflösung** (siehe Plan Abschnitt 1): [ContributionReliefRequestDto.deferralPreviousDueDate]
 * ist laut eigenem KDoc erst ab [ContributionReliefStatus.EXECUTED] gesetzt -- [reliefEffectDescription]
 * zeigt für eine noch offene DEFERRAL-Anfrage deshalb nur "Neue Fälligkeit: %1", nicht die volle
 * "alt -> neu"-Zeile (die bräuchte einen zweiten RPC-Aufruf pro Karte, das N+1-Muster, das das
 * Design-Team für REDUCTION bereits verworfen hat).
 *
 * [reliefKindColor] ist die EINE Quelle der Wahrheit für die Kind-Farbe -- sowohl
 * [ContributionReliefQueueScreen] als auch die Selbstbedienungs-Liste in `ContributionsScreen.kt`
 * rufen exakt diese Funktion, keine zweite, unabhängig gepflegte Farbtabelle.
 *
 * `effectDescription` (das serverseitig berechnete Feld auf [ContributionReliefRequestDto]) wird
 * bewusst NIRGENDS in diesem Client gerendert -- [reliefEffectDescription] hier ist die einzige
 * Quelle für die "was bewirkt dieser Antrag"-Zeile, damit es nicht in einer späteren Welle parallel
 * wieder eingebaut wird und die beiden Texte auseinanderlaufen.
 */
fun reliefKindLabel(kind: ContributionReliefKind): String =
    when (kind) {
        ContributionReliefKind.DEFERRAL -> tr("Stundung")
        ContributionReliefKind.EXEMPTION -> tr("Beitragsbefreiung")
        ContributionReliefKind.REDUCTION -> tr("Sozialermäßigung")
    }

fun reliefKindColor(kind: ContributionReliefKind): String =
    when (kind) {
        ContributionReliefKind.DEFERRAL -> "warning"
        ContributionReliefKind.EXEMPTION -> "secondary"
        ContributionReliefKind.REDUCTION -> "info"
    }

/**
 * "Genehmigt" kommt hier -- wie im Schritt-Tracker unten -- bewusst NICHT vor: Jobs' Ruling gilt
 * global, nicht nur für den Step-Tracker. [ContributionReliefStatus.APPROVED] ist laut Zustandsautomat
 * (siehe `ContributionReliefService` KDoc "Keine Sackgasse") niemals ein reiner Erfolgszustand --
 * jede APPROVED-Zeile hat einen gesetzten `executionError` (DB-CHECK `chk_crr_execution_error_state`),
 * ist also faktisch immer ein gescheiterter Ausführungsversuch, retryable.
 */
fun reliefStatusLabel(status: ContributionReliefStatus): String =
    when (status) {
        ContributionReliefStatus.REQUESTED -> tr("Beantragt")
        ContributionReliefStatus.APPROVED -> tr("Ausführung fehlgeschlagen")
        ContributionReliefStatus.REJECTED -> tr("Abgelehnt")
        ContributionReliefStatus.EXECUTED -> tr("Ausgeführt")
        ContributionReliefStatus.WITHDRAWN -> tr("Zurückgezogen")
    }

fun reliefStatusColor(status: ContributionReliefStatus): String =
    when (status) {
        ContributionReliefStatus.REQUESTED -> "secondary"
        ContributionReliefStatus.APPROVED -> "danger"
        ContributionReliefStatus.REJECTED -> "danger"
        ContributionReliefStatus.EXECUTED -> "success"
        ContributionReliefStatus.WITHDRAWN -> "secondary"
    }

fun reliefReasonLabel(reason: ContributionReliefReason): String =
    when (reason) {
        ContributionReliefReason.FINANCIAL_HARDSHIP -> tr("Finanzielle Notlage")
        ContributionReliefReason.UNEMPLOYMENT -> tr("Arbeitslosigkeit")
        ContributionReliefReason.STUDENT_TRAINEE -> tr("Schüler oder Auszubildende(r)")
        ContributionReliefReason.ILLNESS_DISABILITY -> tr("Krankheit oder Behinderung")
        ContributionReliefReason.PARENTAL_CARE -> tr("Elternzeit oder Pflege")
        ContributionReliefReason.OTHER -> tr("Sonstiges")
    }

/**
 * Drei-Pillen-Schritt-Tracker, gleiches Muster wie `DsgvoRightsScreen.kt`s `ErasureStepState`/
 * `erasureStepStates` -- ein EIGENER Typ statt Wiederverwendung von `ErasureStepState`, weil die
 * Semantik strukturell anders ist ([ContributionReliefStatus] hat fünf Literale, nicht vier, und
 * [ContributionReliefStatus.APPROVED] ist ein reaktivierbarer Retry-Zustand, keine Endstation).
 */
enum class ReliefStepState { PAST, CURRENT, FUTURE }

/**
 * Pille 1 = "Beantragt", Pille 2 = das Entscheidungsergebnis (oder noch offen), Pille 3 = "Ausgeführt".
 * [ContributionReliefStatus.APPROVED] ist EXPLIZIT wie ein Zwischenzustand behandelt (Pille 1 PAST,
 * Pille 2 CURRENT, Pille 3 FUTURE) -- siehe F1 in Plan/Stolperfallen: ein gescheiterter
 * Ausführungsversuch ist kein Endzustand, [network.lapis.cloud.shared.rpc.IContributionReliefService
 * .retryReliefExecution] kann ihn noch nach EXECUTED bringen.
 */
fun reliefStepStates(status: ContributionReliefStatus): List<ReliefStepState> =
    when (status) {
        ContributionReliefStatus.REQUESTED -> listOf(ReliefStepState.CURRENT, ReliefStepState.FUTURE, ReliefStepState.FUTURE)
        ContributionReliefStatus.APPROVED -> listOf(ReliefStepState.PAST, ReliefStepState.CURRENT, ReliefStepState.FUTURE)
        ContributionReliefStatus.REJECTED -> listOf(ReliefStepState.PAST, ReliefStepState.CURRENT, ReliefStepState.FUTURE)
        ContributionReliefStatus.WITHDRAWN -> listOf(ReliefStepState.PAST, ReliefStepState.CURRENT, ReliefStepState.FUTURE)
        ContributionReliefStatus.EXECUTED -> listOf(ReliefStepState.PAST, ReliefStepState.PAST, ReliefStepState.CURRENT)
    }

/** Zweite Pille -- "Entschieden" (noch offen/Platzhalter) vs. das tatsächliche Ergebnis, nie das rohe Enum-Literal. */
fun reliefStep2Label(status: ContributionReliefStatus): String =
    when (status) {
        ContributionReliefStatus.REQUESTED -> tr("Entschieden")
        ContributionReliefStatus.APPROVED -> tr("Ausführung fehlgeschlagen")
        ContributionReliefStatus.REJECTED -> tr("Abgelehnt")
        ContributionReliefStatus.WITHDRAWN -> tr("Zurückgezogen")
        // Bewusst NICHT "Genehmigt" -- neutrale Vergangenheitsform, siehe Klassen-KDoc oben.
        ContributionReliefStatus.EXECUTED -> tr("Entschieden")
    }

fun reliefStep2Color(status: ContributionReliefStatus): String =
    when (status) {
        ContributionReliefStatus.REQUESTED -> "secondary"
        ContributionReliefStatus.APPROVED -> "danger"
        ContributionReliefStatus.REJECTED -> "danger"
        ContributionReliefStatus.WITHDRAWN -> "secondary"
        ContributionReliefStatus.EXECUTED -> "success"
    }

/**
 * Die "was bewirkt dieser Antrag"-Zeile -- rein client-seitig gebaut (siehe Klassen-KDoc oben),
 * NICHT [ContributionReliefRequestDto.effectDescription].
 *
 * @param tierAmountLabel für REDUCTION: ein bereits fertig formatiertes "Name (Betrag / Intervall)"
 *   -- vom Aufrufer EINMAL pro Screen aus `listMembershipTiers()` gebaut (siehe
 *   `ContributionsScreen.kt`/`ContributionReliefQueueScreen.kt`), niemals hier per RPC nachgeladen.
 *   `null`, wenn die Tier-Map die Ziel-Stufe nicht (mehr) enthält -- fällt dann auf
 *   [ContributionReliefRequestDto.reductionTargetTierName] zurück.
 */
fun reliefEffectDescription(
    request: ContributionReliefRequestDto,
    tierAmountLabel: String?,
): String =
    when (request.kind) {
        ContributionReliefKind.DEFERRAL -> {
            val previous = request.deferralPreviousDueDate
            if (previous != null) {
                gettext("Fälligkeit %1 → %2", previous, request.deferralNewDueDate)
            } else {
                gettext("Neue Fälligkeit: %1", request.deferralNewDueDate)
            }
        }
        ContributionReliefKind.EXEMPTION -> {
            val until = request.exemptionUntil
            if (until != null) {
                gettext("Beitragspflicht ausgesetzt %1 bis %2", request.exemptionFrom, until)
            } else {
                gettext("Beitragspflicht ausgesetzt ab %1 (unbefristet)", request.exemptionFrom)
            }
        }
        ContributionReliefKind.REDUCTION ->
            gettext("Neuer Beitragssatz: %1", tierAmountLabel ?: request.reductionTargetTierName ?: tr("unbekannte Beitragsstufe"))
    }

/**
 * F4-Auflösung (siehe Plan): der Server liefert `executionError` als reinen String-Fehlercode
 * (teils mit `:`-Suffix, siehe [ContributionReliefExecutionErrorCode.CONTRIBUTION_NOT_DEFERRABLE]),
 * niemals übersetzt -- dieses Enum ist der EINE Ort, an dem der Rohcode auf ein erschöpfendes
 * `when` abgebildet wird ("when ohne else", Repo-Konvention), statt an jeder Anzeige-Stelle erneut
 * einen String zu vergleichen. Werte spiegeln `ContributionReliefExecution`s
 * `ReliefExecutionOutcome.Failed.reason`-Literale wortgleich (verifiziert gegen die Server-Datei).
 */
internal enum class ContributionReliefExecutionErrorCode(
    val wireCode: String,
) {
    CONTRIBUTION_NOT_FOUND("contribution_not_found"),
    CONTRIBUTION_NOT_DEFERRABLE("contribution_not_deferrable"),
    NEW_DUE_DATE_NOT_IN_FUTURE("new_due_date_not_in_future"),
    MEMBER_NOT_FOUND("member_not_found"),
    MEMBER_ANONYMIZED("member_anonymized"),
    MEMBERSHIP_ENDED("membership_ended"),
    TARGET_TIER_NOT_FOUND("target_tier_not_found"),
    TARGET_TIER_NOT_ACTIVE("target_tier_not_active"),
    MEMBER_HAS_NO_TIER_OF_THEIR_OWN("member_has_no_tier_of_their_own"),
    TARGET_TIER_ALREADY_CURRENT("target_tier_already_current"),
    TARGET_TIER_NOT_CHEAPER_THAN_CURRENT("target_tier_not_cheaper_than_current"),
}

/** Splittet [raw] am ERSTEN ':' -- nur [ContributionReliefExecutionErrorCode.CONTRIBUTION_NOT_DEFERRABLE] trägt einen Suffix. */
internal fun parseReliefExecutionErrorCode(raw: String): ContributionReliefExecutionErrorCode? {
    val code = raw.substringBefore(':')
    return ContributionReliefExecutionErrorCode.entries.firstOrNull { it.wireCode == code }
}

/**
 * Übersetzt [raw] (den rohen `executionError`-String, siehe [ContributionReliefRequestDto.executionError])
 * in einen deutschen Satz. Niemals der Rohcode im Ergebnis -- ein unbekannter/unparsbarer Code
 * bekommt einen generischen Hinweis, ein unparsbarer [ContributionReliefExecutionErrorCode
 * .CONTRIBUTION_NOT_DEFERRABLE]-Suffix einen Satz ohne Statusangabe statt das rohe Enum-Literal.
 */
fun reliefExecutionErrorMessage(raw: String?): String {
    val code = raw?.let { parseReliefExecutionErrorCode(it) }
    return when (code) {
        null -> tr("Die Ausführung ist fehlgeschlagen (unbekannte Ursache). Bitte wenden Sie sich an die Administration.")
        ContributionReliefExecutionErrorCode.CONTRIBUTION_NOT_FOUND -> tr("Der zugehörige Beitrag wurde nicht gefunden.")
        ContributionReliefExecutionErrorCode.CONTRIBUTION_NOT_DEFERRABLE -> {
            val statusName = raw.substringAfter(':', missingDelimiterValue = "")
            val status = runCatching { ContributionStatus.valueOf(statusName) }.getOrNull()
            if (status != null) {
                gettext("Der Beitrag kann nicht gestundet werden (Status: %1).", contributionStatusLabel(status))
            } else {
                tr("Der Beitrag kann nicht gestundet werden.")
            }
        }
        ContributionReliefExecutionErrorCode.NEW_DUE_DATE_NOT_IN_FUTURE -> tr("Das neue Fälligkeitsdatum liegt nicht in der Zukunft.")
        ContributionReliefExecutionErrorCode.MEMBER_NOT_FOUND -> tr("Das Mitglied wurde nicht gefunden.")
        ContributionReliefExecutionErrorCode.MEMBER_ANONYMIZED -> tr("Das Mitglied wurde DSGVO-anonymisiert.")
        ContributionReliefExecutionErrorCode.MEMBERSHIP_ENDED -> tr("Die Mitgliedschaft ist beendet.")
        ContributionReliefExecutionErrorCode.TARGET_TIER_NOT_FOUND -> tr("Die Ziel-Beitragsstufe wurde nicht gefunden.")
        ContributionReliefExecutionErrorCode.TARGET_TIER_NOT_ACTIVE -> tr("Die Ziel-Beitragsstufe ist nicht aktiv.")
        ContributionReliefExecutionErrorCode.MEMBER_HAS_NO_TIER_OF_THEIR_OWN ->
            tr("Das Mitglied hat keine eigene Beitragsstufe (Familienmitgliedschaft).")
        ContributionReliefExecutionErrorCode.TARGET_TIER_ALREADY_CURRENT ->
            tr("Die Ziel-Beitragsstufe entspricht bereits der aktuellen Stufe.")
        ContributionReliefExecutionErrorCode.TARGET_TIER_NOT_CHEAPER_THAN_CURRENT ->
            tr("Die Ziel-Beitragsstufe ist nicht günstiger als die aktuelle Stufe.")
    }
}
