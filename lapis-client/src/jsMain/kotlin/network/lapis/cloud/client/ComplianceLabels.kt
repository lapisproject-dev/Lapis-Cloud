package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.AvvStatus
import network.lapis.cloud.shared.domain.BackupOperationStatus
import network.lapis.cloud.shared.domain.BackupOperationType
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DpiaRiskBand
import network.lapis.cloud.shared.domain.DsfaStatus
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.RiskLevel
import network.lapis.cloud.shared.domain.TomCategory

// Compliance UI wave -- shared German label/badge-color tables for enums used by more than one
// Compliance screen, mirroring `AccountingLabels.kt`'s exact shape (label function + color function
// per enum, no inline `when` blocks duplicated per screen). Design decision D12 is the binding
// source for every color assignment in this file -- see the approved design document's consolidated
// table for the full cross-screen rationale (Bootstrap's 8-hue ceiling, hue reuse across enums
// explicitly sanctioned by `StatusBadge.kt`'s own KDoc since every badge always also carries text).
//
// `AuditAction`/`AuditEntityType` (screen 1, `AuditLogScreen.kt`), `BackupOperationType`/
// `BackupOperationStatus` (screen 2, `BackupScreen.kt`), `AvvStatus`/`TomCategory`/`DsfaStatus`/
// `BreachStatus`/`BreachDeadlineStatus`/`DpiaRiskBand`/`RiskLevel` (screen 3, `DsgvoComplianceScreen.kt`),
// and `ErasureStatus`/`ErasureMode`/`DsgvoAuditAction` (screen 4, `DsgvoRightsScreen.kt`) are added so
// far -- this file's remaining enum from D12's table (`BoardChangeType`) belongs to screen 5, built by
// the next agent in this wave's implementation sequence, who adds its own section to this same file
// rather than inventing a parallel one.

/**
 * D12: `statusBadge` grammar (filled, per `StatusBadge.kt`) -- `AuditAction` is the *kind* of write
 * that happened to an entity at a specific point in its lifecycle (CREATE -> possibly UPDATE ->
 * possibly POST), not a fixed classification of the entity itself.
 */
fun auditActionLabel(action: AuditAction): String =
    when (action) {
        AuditAction.CREATE -> gettext("Erstellt")
        AuditAction.UPDATE -> gettext("Geändert")
        AuditAction.POST -> gettext("Gebucht")
    }

fun auditActionColor(action: AuditAction): String =
    when (action) {
        AuditAction.CREATE -> "info"
        AuditAction.UPDATE -> "secondary"
        AuditAction.POST -> "success"
    }

/**
 * D12: `typeBadge` grammar (outline, per `StatusBadge.kt`) -- `AuditEntityType` is a fixed
 * classification of which entity kind an audit-log row covers, not a lifecycle status.
 */
fun auditEntityTypeLabel(entityType: AuditEntityType): String =
    when (entityType) {
        AuditEntityType.JOURNAL_ENTRY -> gettext("Buchung")
        AuditEntityType.PARTY_DONATION_VERDICT -> gettext("Spendenprüfung")
        AuditEntityType.RESOLUTION -> gettext("Beschluss")
        AuditEntityType.BOARD_MEMBERSHIP -> gettext("Vorstandsmitgliedschaft")
        // V1.0 Videokonferenzen, Wave 2 "Aufzeichnung" -- "Aufzeichnung" is the ONLY term used
        // across badge/banner/dialog/Lobby for this concept, see the Wave 2 design review's D15
        // "Terminology lock".
        AuditEntityType.CONFERENCE_RECORDING -> gettext("Aufzeichnung")
        // V1.0 Videokonferenzen, Wave 3 "Externes Streaming" -- "Live-Stream"/"Streaming-Ziel" are
        // the SAME terms the Wave 3 design review's D3/D9 use throughout the badge/dialog/Lobby
        // surfaces, same "one term, everywhere" discipline as Wave 2's D15.
        AuditEntityType.CONFERENCE_STREAM -> gettext("Live-Stream")
        AuditEntityType.CONFERENCE_STREAM_DESTINATION -> gettext("Streaming-Ziel")
        // V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- "Gastzugang" is the ONLY term
        // used for the per-room federation-guest-access capability across badge/button/dialog/
        // invite, see the Wave 5 design review's D2 "Terminology lock".
        AuditEntityType.CONFERENCE_ROOM -> gettext("Gastzugang")
        // Soziales Netzwerk, Welle V1.1.5 -- "Beitrag" ist der Begriff, den `SocialNetworkScreen.kt`
        // durchgängig für einen `SocialPost` verwendet ("Beitrag verfassen"/"Beitrag melden"...).
        AuditEntityType.SOCIAL_POST -> gettext("Beitrag")
        // Zahlungsverkehr, Welle V1.2.1, Security Round 1 (2026-08-19, MAJOR-2) -- eine Änderung der
        // Zahlungs-Konto-Zuordnung (`paymentBankAccountId`/`paymentFeeAccountId`/
        // `contributionIncomeAccountId`), die entscheidet, wohin jeder künftige Mitgliedsbeitrag
        // gebucht wird -- siehe `OrganizationSettingsService.updateOrganizationSettings` KDoc.
        AuditEntityType.ORGANIZATION_SETTINGS -> gettext("Organisationseinstellungen")
        // Zahlungsverkehr, Welle V1.2.2 "SEPA-Lastschriftmandate" -- "SEPA-Mandat"/"SEPA-Lastschriftlauf"
        // sind die Begriffe, die SepaMandateScreen.kt/SepaBatchScreen.kt durchgängig verwenden.
        AuditEntityType.SEPA_MANDATE -> gettext("SEPA-Mandat")
        AuditEntityType.SEPA_DEBIT_BATCH -> gettext("SEPA-Lastschriftlauf")
    }

fun auditEntityTypeColor(entityType: AuditEntityType): String =
    when (entityType) {
        AuditEntityType.JOURNAL_ENTRY -> "primary"
        AuditEntityType.PARTY_DONATION_VERDICT -> "danger"
        AuditEntityType.RESOLUTION -> "dark"
        AuditEntityType.BOARD_MEMBERSHIP -> "secondary"
        AuditEntityType.CONFERENCE_RECORDING -> "info"
        // "warning" -- distinct from CONFERENCE_RECORDING's "info", matching the Wave 3 design
        // review's D3 "streaming and recording must never be visually confusable" verdict, carried
        // into this audit-log badge too, not just the in-meeting indicator.
        AuditEntityType.CONFERENCE_STREAM -> "warning"
        AuditEntityType.CONFERENCE_STREAM_DESTINATION -> "light"
        // "info" -- matches the Wave 5 design review's D3 in-call "Gastzugang" status badge color
        // ("open guest access is a state of affairs, not an achievement").
        AuditEntityType.CONFERENCE_ROOM -> "info"
        // "danger" -- eine rechtliche Entfernung/Melde-Entscheidung ist die folgenreichste
        // Eintragsart dieses Log, konsistent zu PARTY_DONATION_VERDICT's Einstufung oben.
        AuditEntityType.SOCIAL_POST -> "danger"
        // "warning" -- eine Zahlungs-Konto-Zuordnungsänderung ist administrativ, nicht per se ein
        // Fehlverhalten (anders als PARTY_DONATION_VERDICT/SOCIAL_POST's "danger"), aber finanziell
        // hoch relevant genug, um dieselbe Aufmerksamkeits-Stufe wie CONFERENCE_STREAM zu verdienen.
        AuditEntityType.ORGANIZATION_SETTINGS -> "warning"
        // "primary" -- ein erteiltes/widerrufenes Mandat ist ein finanziell zentraler Vorgang,
        // dieselbe Einstufung wie JOURNAL_ENTRY oben.
        AuditEntityType.SEPA_MANDATE -> "primary"
        // "warning" -- ein Lastschriftlauf-Statuswechsel ist administrativ, dieselbe Einstufung wie
        // ORGANIZATION_SETTINGS oben.
        AuditEntityType.SEPA_DEBIT_BATCH -> "warning"
    }

// ------------------------------------------------------------------------------------------------
// Screen 2 of 5 -- BackupScreen.kt. Not part of the original D12 table (that table only lists the
// twelve enums shared by *later* screens' DTOs) -- `BackupOperationType`/`BackupOperationStatus`
// are added here now, following the same file/shape convention, because `BackupScreen.kt`'s
// operations log needs exactly the same `statusBadge`-driven grammar every other screen's log/list
// view already uses.
// ------------------------------------------------------------------------------------------------

/** `typeBadge` grammar (outline) -- EXPORT/RESTORE is a fixed classification of which kind of
 * operation a `backup_operation_log` row records, not a lifecycle status. */
fun backupOperationTypeLabel(type: BackupOperationType): String =
    when (type) {
        BackupOperationType.EXPORT -> gettext("Export")
        BackupOperationType.RESTORE -> gettext("Wiederherstellung")
    }

fun backupOperationTypeColor(type: BackupOperationType): String =
    when (type) {
        BackupOperationType.EXPORT -> "primary"
        BackupOperationType.RESTORE -> "warning"
    }

/** `statusBadge` grammar (filled) -- the outcome of one already-finished attempt, per the Backup
 * screen design decision ("Operations log ... reuse `statusBadge` for SUCCEEDED=success/
 * FAILED=danger"). */
fun backupOperationStatusLabel(status: BackupOperationStatus): String =
    when (status) {
        BackupOperationStatus.SUCCEEDED -> gettext("Erfolgreich")
        BackupOperationStatus.FAILED -> gettext("Fehlgeschlagen")
    }

fun backupOperationStatusColor(status: BackupOperationStatus): String =
    when (status) {
        BackupOperationStatus.SUCCEEDED -> "success"
        BackupOperationStatus.FAILED -> "danger"
    }

// ------------------------------------------------------------------------------------------------
// Screen 3 of 5 -- DsgvoComplianceScreen.kt (AVV-Register/TOMs/DSFA/Datenpannenmeldung). Design
// decision D12's consolidated table is the binding source for every color below.
// ------------------------------------------------------------------------------------------------

/** `statusBadge` grammar (filled) -- an AVV-register row's lifecycle status. Always rendered
 * paired with [activeStatusBadge] (D11), never alone -- see `DsgvoComplianceScreen.kt`. */
fun avvStatusLabel(status: AvvStatus): String =
    when (status) {
        AvvStatus.NONE -> gettext("Kein AVV")
        AvvStatus.DRAFT -> gettext("Entwurf")
        AvvStatus.SIGNED -> gettext("Unterzeichnet")
    }

fun avvStatusColor(status: AvvStatus): String =
    when (status) {
        AvvStatus.NONE -> "secondary"
        AvvStatus.DRAFT -> "warning"
        AvvStatus.SIGNED -> "success"
    }

/** `typeBadge` grammar (outline) -- the eight standard TOM categories, a fixed classification, not
 * a lifecycle status. German names per Anlage zu §64 BDSG / Orientierungshilfe der
 * Aufsichtsbehörden -- see [TomCategory] KDoc for the "verify against current text" caveat this
 * label set inherits unchanged. `PHYSICAL_ACCESS_CONTROL`/`SEPARATION_CONTROL` deliberately share
 * `dark` (D12: "both perimeter-flavored, least likely pair needing snap disambiguation"). */
fun tomCategoryLabel(category: TomCategory): String =
    when (category) {
        TomCategory.PHYSICAL_ACCESS_CONTROL -> gettext("Zutrittskontrolle")
        TomCategory.SYSTEM_ACCESS_CONTROL -> gettext("Zugangskontrolle")
        TomCategory.DATA_ACCESS_CONTROL -> gettext("Zugriffskontrolle")
        TomCategory.TRANSFER_CONTROL -> gettext("Weitergabekontrolle")
        TomCategory.INPUT_CONTROL -> gettext("Eingabekontrolle")
        TomCategory.ORDER_CONTROL -> gettext("Auftragskontrolle")
        TomCategory.AVAILABILITY_CONTROL -> gettext("Verfügbarkeitskontrolle")
        TomCategory.SEPARATION_CONTROL -> gettext("Trennungsgebot")
    }

fun tomCategoryColor(category: TomCategory): String =
    when (category) {
        TomCategory.PHYSICAL_ACCESS_CONTROL -> "dark"
        TomCategory.SYSTEM_ACCESS_CONTROL -> "primary"
        TomCategory.DATA_ACCESS_CONTROL -> "info"
        TomCategory.TRANSFER_CONTROL -> "secondary"
        TomCategory.INPUT_CONTROL -> "warning"
        TomCategory.ORDER_CONTROL -> "success"
        TomCategory.AVAILABILITY_CONTROL -> "danger"
        TomCategory.SEPARATION_CONTROL -> "dark"
    }

/** `statusBadge` grammar (filled) -- a DSFA/DPIA row's lifecycle status. */
fun dsfaStatusLabel(status: DsfaStatus): String =
    when (status) {
        DsfaStatus.DRAFT -> gettext("Entwurf")
        DsfaStatus.COMPLETED -> gettext("Abgeschlossen")
        DsfaStatus.OUTDATED_REVIEW_DUE -> gettext("Überholt -- Überprüfung fällig")
    }

fun dsfaStatusColor(status: DsfaStatus): String =
    when (status) {
        DsfaStatus.DRAFT -> "secondary"
        DsfaStatus.COMPLETED -> "success"
        DsfaStatus.OUTDATED_REVIEW_DUE -> "warning"
    }

/** `statusBadge` grammar (filled) -- a data-breach incident's lifecycle status. `REPORTED` gets
 * `danger` deliberately (D12: "freshly reported = urgent by default"); `CLOSED` gets `dark`, not
 * `success` (D12: "closing a breach file is not a win"). */
fun breachStatusLabel(status: BreachStatus): String =
    when (status) {
        BreachStatus.REPORTED -> gettext("Gemeldet")
        BreachStatus.UNDER_ASSESSMENT -> gettext("In Prüfung")
        BreachStatus.NOTIFIED_AUTHORITY -> gettext("Aufsichtsbehörde benachrichtigt")
        BreachStatus.NO_NOTIFICATION_REQUIRED -> gettext("Keine Meldung erforderlich")
        BreachStatus.CLOSED -> gettext("Abgeschlossen")
    }

fun breachStatusColor(status: BreachStatus): String =
    when (status) {
        BreachStatus.REPORTED -> "danger"
        BreachStatus.UNDER_ASSESSMENT -> "warning"
        BreachStatus.NOTIFIED_AUTHORITY -> "info"
        BreachStatus.NO_NOTIFICATION_REQUIRED -> "secondary"
        BreachStatus.CLOSED -> "dark"
    }

/** `statusBadge` grammar (filled) -- D7's exact color escalation for the Art. 33(1) 72h clock.
 * `SATISFIED` deliberately gets `secondary`, NOT `success` -- [BreachDeadlineStatus] KDoc:
 * "notification recorded", regardless of whether it landed before or after the deadline; coloring
 * it green would falsely claim "you made it in time". */
fun breachDeadlineStatusLabel(status: BreachDeadlineStatus): String =
    when (status) {
        BreachDeadlineStatus.WITHIN_WINDOW -> gettext("Frist läuft")
        BreachDeadlineStatus.DUE_SOON -> gettext("Frist bald fällig")
        BreachDeadlineStatus.OVERDUE -> gettext("Frist überschritten")
        BreachDeadlineStatus.SATISFIED -> gettext("Meldung erfolgt")
    }

fun breachDeadlineStatusColor(status: BreachDeadlineStatus): String =
    when (status) {
        BreachDeadlineStatus.WITHIN_WINDOW -> "success"
        BreachDeadlineStatus.DUE_SOON -> "warning"
        BreachDeadlineStatus.OVERDUE -> "danger"
        BreachDeadlineStatus.SATISFIED -> "secondary"
    }

/** `statusBadge` grammar (filled) -- [DpiaRiskBand] is a read-time-only display band ([DpiaRiskBand]
 * KDoc: "Not an Art. 35 DSGVO necessity determination"), but still a value the user watches change
 * as `riskLikelihood`/`riskSeverity` inputs change, hence `statusBadge` not `typeBadge`. `CRITICAL`
 * gets `dark` (D12: "heat-scale convention: dark reads as beyond red"). */
fun dpiaRiskBandLabel(band: DpiaRiskBand): String =
    when (band) {
        DpiaRiskBand.LOW -> gettext("Niedrig")
        DpiaRiskBand.MEDIUM -> gettext("Mittel")
        DpiaRiskBand.HIGH -> gettext("Hoch")
        DpiaRiskBand.CRITICAL -> gettext("Kritisch")
    }

fun dpiaRiskBandColor(band: DpiaRiskBand): String =
    when (band) {
        DpiaRiskBand.LOW -> "success"
        DpiaRiskBand.MEDIUM -> "warning"
        DpiaRiskBand.HIGH -> "danger"
        DpiaRiskBand.CRITICAL -> "dark"
    }

/** `statusBadge` grammar (filled) -- the raw human-entered likelihood/severity inputs that feed
 * [DpiaRiskBand] (DSFA tab) and the human-entered breach severity (Breach tab), same 3-step subset
 * of [dpiaRiskBandColor]'s scale for visual consistency between the two risk inputs and their
 * derived band (D12). */
fun riskLevelLabel(level: RiskLevel): String =
    when (level) {
        RiskLevel.LOW -> gettext("Niedrig")
        RiskLevel.MEDIUM -> gettext("Mittel")
        RiskLevel.HIGH -> gettext("Hoch")
    }

fun riskLevelColor(level: RiskLevel): String =
    when (level) {
        RiskLevel.LOW -> "success"
        RiskLevel.MEDIUM -> "warning"
        RiskLevel.HIGH -> "danger"
    }

// ------------------------------------------------------------------------------------------------
// Screen 4 of 5 -- DsgvoRightsScreen.kt (member-facing export/erasure rights + ADMIN decide/execute
// workflow). Design decision D12's consolidated table is the binding source for every color below.
// ------------------------------------------------------------------------------------------------

/** `statusBadge` grammar (filled) -- an erasure request's lifecycle status, the thing both the
 * requesting member and the deciding ADMIN watch progress through REQUESTED -> APPROVED/REJECTED ->
 * COMPLETED. D12: `APPROVED` deliberately gets `warning` ("alarming -- one click from irreversible"),
 * `REJECTED` gets `dark` ("neutral terminal, no harm done"), `COMPLETED` gets `danger`, never
 * `success` -- data loss is not a "success" color even though the workflow completed as designed. */
fun erasureStatusLabel(status: ErasureStatus): String =
    when (status) {
        ErasureStatus.REQUESTED -> gettext("Beantragt")
        ErasureStatus.APPROVED -> gettext("Genehmigt")
        ErasureStatus.REJECTED -> gettext("Abgelehnt")
        ErasureStatus.COMPLETED -> gettext("Ausgeführt")
    }

fun erasureStatusColor(status: ErasureStatus): String =
    when (status) {
        ErasureStatus.REQUESTED -> "secondary"
        ErasureStatus.APPROVED -> "warning"
        ErasureStatus.REJECTED -> "dark"
        ErasureStatus.COMPLETED -> "danger"
    }

/** `typeBadge` grammar (outline) -- which of the two erasure modes a request carries, a fixed
 * classification chosen once at request time, not a value that itself progresses. */
fun erasureModeLabel(mode: ErasureMode): String =
    when (mode) {
        ErasureMode.ANONYMIZE -> gettext("Anonymisierung")
        ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED -> gettext("Anonymisierung + Hartlöschung eigener Nachrichten")
    }

fun erasureModeColor(mode: ErasureMode): String =
    when (mode) {
        ErasureMode.ANONYMIZE -> "info"
        ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED -> "danger"
    }

/** D5: the plain-language consequence line for each [ErasureMode] -- shown to the requesting member
 * at request time, repeated read-only to the deciding ADMIN, and shown a third and final time in
 * `executeErasureConfirmDialog` at the point of irrevocable commitment (D4/D5's "never just the raw
 * enum name" rule). */
fun erasureModeDescription(mode: ErasureMode): String =
    when (mode) {
        ErasureMode.ANONYMIZE ->
            gettext(
                "Ihr Mitgliedsdatensatz bleibt als anonymer Platzhalter bestehen (z. B. damit Beitragshistorie " +
                    "und Buchungen konsistent bleiben). Persönliche Felder werden geleert oder anonymisiert.",
            )
        ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED ->
            gettext(
                "Zusätzlich zur Anonymisierung werden von Ihnen selbst versendete Nachrichten hart gelöscht, " +
                    "soweit dem keine gesetzliche Aufbewahrungspflicht entgegensteht. Von Ihnen empfangene " +
                    "Nachrichten anderer Mitglieder bleiben unberührt.",
            )
    }

/** `statusBadge` grammar (filled) -- which kind of DSGVO-rights event a
 * `DsgvoAuditLogEntryDto` row records. Colors deliberately mirror [erasureStatusColor]'s own scale
 * (`ERASURE_REQUESTED`=secondary, `ERASURE_APPROVED`=warning, `ERASURE_REJECTED`=dark,
 * `ERASURE_EXECUTED`=danger) so the audit trail and the live request status read as the same visual
 * language for the same underlying transition; `EXPORT` is unrelated to the erasure state machine
 * and gets its own `info` hue. */
fun dsgvoAuditActionLabel(action: DsgvoAuditAction): String =
    when (action) {
        DsgvoAuditAction.EXPORT -> gettext("Auskunft exportiert")
        DsgvoAuditAction.ERASURE_REQUESTED -> gettext("Löschung beantragt")
        DsgvoAuditAction.ERASURE_APPROVED -> gettext("Löschung genehmigt")
        DsgvoAuditAction.ERASURE_REJECTED -> gettext("Löschung abgelehnt")
        DsgvoAuditAction.ERASURE_EXECUTED -> gettext("Löschung ausgeführt")
    }

fun dsgvoAuditActionColor(action: DsgvoAuditAction): String =
    when (action) {
        DsgvoAuditAction.EXPORT -> "info"
        DsgvoAuditAction.ERASURE_REQUESTED -> "secondary"
        DsgvoAuditAction.ERASURE_APPROVED -> "warning"
        DsgvoAuditAction.ERASURE_REJECTED -> "dark"
        DsgvoAuditAction.ERASURE_EXECUTED -> "danger"
    }

// ------------------------------------------------------------------------------------------------
// Screen 5 of 5 -- BoardMembershipScreen.kt (Vorstandsregister + Transparenzregister-Bericht +
// Erinnerungen). This is D12's originally-listed twelfth and final enum -- see this file's header
// comment ("this file's remaining enum from D12's table (BoardChangeType) belongs to screen 5").
// ------------------------------------------------------------------------------------------------

/** `statusBadge` grammar (filled) -- which half of a Vorstandsaenderung a
 * [network.lapis.cloud.shared.domain.TransparenzregisterReminderDto] row records, the thing a
 * BOARD/ADMIN reviewer watches accumulate over time. D12: `LEFT` deliberately gets `secondary`, not
 * a negative-flavored hue -- "leaving isn't inherently negative -- term expiry is routine". */
fun boardChangeTypeLabel(type: BoardChangeType): String =
    when (type) {
        BoardChangeType.JOINED -> gettext("Eingetreten")
        BoardChangeType.LEFT -> gettext("Ausgeschieden")
    }

fun boardChangeTypeColor(type: BoardChangeType): String =
    when (type) {
        BoardChangeType.JOINED -> "success"
        BoardChangeType.LEFT -> "secondary"
    }
