package network.lapis.cloud.client

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
        AuditAction.CREATE -> "Erstellt"
        AuditAction.UPDATE -> "Geändert"
        AuditAction.POST -> "Gebucht"
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
        AuditEntityType.JOURNAL_ENTRY -> "Buchung"
        AuditEntityType.PARTY_DONATION_VERDICT -> "Spendenprüfung"
        AuditEntityType.RESOLUTION -> "Beschluss"
        AuditEntityType.BOARD_MEMBERSHIP -> "Vorstandsmitgliedschaft"
    }

fun auditEntityTypeColor(entityType: AuditEntityType): String =
    when (entityType) {
        AuditEntityType.JOURNAL_ENTRY -> "primary"
        AuditEntityType.PARTY_DONATION_VERDICT -> "danger"
        AuditEntityType.RESOLUTION -> "dark"
        AuditEntityType.BOARD_MEMBERSHIP -> "secondary"
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
        BackupOperationType.EXPORT -> "Export"
        BackupOperationType.RESTORE -> "Wiederherstellung"
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
        BackupOperationStatus.SUCCEEDED -> "Erfolgreich"
        BackupOperationStatus.FAILED -> "Fehlgeschlagen"
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
        AvvStatus.NONE -> "Kein AVV"
        AvvStatus.DRAFT -> "Entwurf"
        AvvStatus.SIGNED -> "Unterzeichnet"
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
        TomCategory.PHYSICAL_ACCESS_CONTROL -> "Zutrittskontrolle"
        TomCategory.SYSTEM_ACCESS_CONTROL -> "Zugangskontrolle"
        TomCategory.DATA_ACCESS_CONTROL -> "Zugriffskontrolle"
        TomCategory.TRANSFER_CONTROL -> "Weitergabekontrolle"
        TomCategory.INPUT_CONTROL -> "Eingabekontrolle"
        TomCategory.ORDER_CONTROL -> "Auftragskontrolle"
        TomCategory.AVAILABILITY_CONTROL -> "Verfügbarkeitskontrolle"
        TomCategory.SEPARATION_CONTROL -> "Trennungsgebot"
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
        DsfaStatus.DRAFT -> "Entwurf"
        DsfaStatus.COMPLETED -> "Abgeschlossen"
        DsfaStatus.OUTDATED_REVIEW_DUE -> "Überholt -- Überprüfung fällig"
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
        BreachStatus.REPORTED -> "Gemeldet"
        BreachStatus.UNDER_ASSESSMENT -> "In Prüfung"
        BreachStatus.NOTIFIED_AUTHORITY -> "Aufsichtsbehörde benachrichtigt"
        BreachStatus.NO_NOTIFICATION_REQUIRED -> "Keine Meldung erforderlich"
        BreachStatus.CLOSED -> "Abgeschlossen"
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
        BreachDeadlineStatus.WITHIN_WINDOW -> "Frist läuft"
        BreachDeadlineStatus.DUE_SOON -> "Frist bald fällig"
        BreachDeadlineStatus.OVERDUE -> "Frist überschritten"
        BreachDeadlineStatus.SATISFIED -> "Meldung erfolgt"
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
        DpiaRiskBand.LOW -> "Niedrig"
        DpiaRiskBand.MEDIUM -> "Mittel"
        DpiaRiskBand.HIGH -> "Hoch"
        DpiaRiskBand.CRITICAL -> "Kritisch"
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
        RiskLevel.LOW -> "Niedrig"
        RiskLevel.MEDIUM -> "Mittel"
        RiskLevel.HIGH -> "Hoch"
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
        ErasureStatus.REQUESTED -> "Beantragt"
        ErasureStatus.APPROVED -> "Genehmigt"
        ErasureStatus.REJECTED -> "Abgelehnt"
        ErasureStatus.COMPLETED -> "Ausgeführt"
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
        ErasureMode.ANONYMIZE -> "Anonymisierung"
        ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED -> "Anonymisierung + Hartlöschung eigener Nachrichten"
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
            "Ihr Mitgliedsdatensatz bleibt als anonymer Platzhalter bestehen (z. B. damit Beitragshistorie " +
                "und Buchungen konsistent bleiben). Persönliche Felder werden geleert oder anonymisiert."
        ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED ->
            "Zusätzlich zur Anonymisierung werden von Ihnen selbst versendete Nachrichten hart gelöscht, " +
                "soweit dem keine gesetzliche Aufbewahrungspflicht entgegensteht. Von Ihnen empfangene " +
                "Nachrichten anderer Mitglieder bleiben unberührt."
    }

/** `statusBadge` grammar (filled) -- which kind of DSGVO-rights event a
 * `DsgvoAuditLogEntryDto` row records. Colors deliberately mirror [erasureStatusColor]'s own scale
 * (`ERASURE_REQUESTED`=secondary, `ERASURE_APPROVED`=warning, `ERASURE_REJECTED`=dark,
 * `ERASURE_EXECUTED`=danger) so the audit trail and the live request status read as the same visual
 * language for the same underlying transition; `EXPORT` is unrelated to the erasure state machine
 * and gets its own `info` hue. */
fun dsgvoAuditActionLabel(action: DsgvoAuditAction): String =
    when (action) {
        DsgvoAuditAction.EXPORT -> "Auskunft exportiert"
        DsgvoAuditAction.ERASURE_REQUESTED -> "Löschung beantragt"
        DsgvoAuditAction.ERASURE_APPROVED -> "Löschung genehmigt"
        DsgvoAuditAction.ERASURE_REJECTED -> "Löschung abgelehnt"
        DsgvoAuditAction.ERASURE_EXECUTED -> "Löschung ausgeführt"
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
        BoardChangeType.JOINED -> "Eingetreten"
        BoardChangeType.LEFT -> "Ausgeschieden"
    }

fun boardChangeTypeColor(type: BoardChangeType): String =
    when (type) {
        BoardChangeType.JOINED -> "success"
        BoardChangeType.LEFT -> "secondary"
    }
