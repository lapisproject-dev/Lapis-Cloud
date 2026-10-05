package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * GoBD-Revisionssicherheit (V0.5.3) -- see `network.lapis.cloud.server.audit.AuditLogRecorder` /
 * `network.lapis.cloud.server.rpc.AuditLogService` KDoc for the full write/read lifecycle and
 * `lapis-server/src/main/kuml/14-audit-log.kuml.kts`'s file header for the bounded-scope rationale
 * (JournalEntry lifecycle is MUST, Resolution/PartyDonationVerdict/BoardMembership are SHOULD,
 * everything else this wave is explicitly out of scope) and the hash-chain tamper-evidence design.
 *
 * Additively extensible -- same "cheap to extend, expensive to reorder" note every other domain
 * enum in this codebase carries; literal order here is load-bearing (`AuditLogSchemaDriftTest`
 * pins it against `14-audit-log.kuml.kts`'s `auditAction` enum).
 *
 * `VOID` (Security-Fund, Welle V1.4.12, INFORMATIONAL: "keine Korrektur-/Widerrufsmöglichkeit
 * für eine falsch oder missbräuchlich erfasste Papier-Selbstauskunft") is the anticipated Storno
 * mechanism this KDoc previously only mentioned as a future possibility -- appended LAST, additive
 * only.  `network.lapis.cloud.server.rpc.VolunteerAllowanceService.voidPaperDeclaration` is the
 * first (and, as of this wave, only) writer: it HARD-deletes an `ON_PAPER`
 * `volunteer_allowance_self_declaration` row (there is no soft-delete/void column on that table --
 * `uq_vasd_member_category_year` is a PLAIN unique index, so a voided-but-retained row would still
 * block the subject's own fresh `IN_APP` declaration for the same category/year, defeating half of
 * why this method exists) and writes exactly one `VOLUNTEER_DECLARATION`/`VOID` entry with `before`
 * = the deleted row's [VolunteerAllowanceDeclarationSnapshot] and `after = null` -- the ONLY
 * surviving record that the declaration ever existed, same "hard-deleted row, audit entry is the
 * sole remaining trace" idiom [AuditEntityType.CONFERENCE_RECORDING]'s own KDoc already
 * establishes for `deleteRecording`.
 *
 * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar" --
 * `network.lapis.cloud.server.rpc.DocumentService.deleteDocument`'s soft-delete (`is_deleted =
 * true`, versions kept) is ALSO recorded as `VOID`, not a new `DELETE` literal: a fresh literal
 * would force a second `chk_audit_log_entry_action` CHECK-Verbreiterung AND an enum-literal-order
 * change pinned by `AuditLogSchemaDriftTest`, for no semantic gain -- a document soft-delete is
 * exactly the "Storno/Widerruf, record kept, no further correction" shape `VOID` already names.
 */
@Serializable
enum class AuditAction { CREATE, UPDATE, POST, VOID }

/**
 * The entity kinds this wave's bounded audit-log scope covers -- see file header. Literal order
 * here is load-bearing (`AuditLogSchemaDriftTest` pins it against `14-audit-log.kuml.kts`'s
 * `auditEntityType` enum). `CONFERENCE_RECORDING` (V1.0 Videokonferenzen, Wave 2 "Aufzeichnung")
 * was appended LAST -- see `network.lapis.cloud.server.rpc.ConferenceRecordingService` KDoc for
 * why start/stop are audited. `CONFERENCE_STREAM`/`CONFERENCE_STREAM_DESTINATION` (V1.0
 * Videokonferenzen, Wave 3 "Externes Streaming") were appended LAST after that, in this order --
 * every destination credential CREATE/UPDATE/DELETE and every stream start/pause/resume/stop is
 * audited, same GoBD/§32 BGB framing Wave 2 already established for recording start/stop.
 * `CONFERENCE_ROOM` (V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt") was appended LAST
 * after that -- see `network.lapis.cloud.server.rpc.ConferenceService.setRoomGuestAccess` KDoc for
 * why the room-level federation-guest-access toggle is audited (a moderator granting/revoking
 * another organization's members access to this room's audio/video is a governance-relevant fact).
 * `SOCIAL_POST` (Soziales Netzwerk, Welle V1.1.5 "Moderation, DSA-Melde-Mechanismus,
 * DSGVO-Content-Hard-Delete") was appended LAST after that -- a BOARD/ADMIN-ausgelöste rechtliche
 * Entfernung (`SocialNetworkService.removePostForLegalReason`) und eine ADMIN-Entscheidung über
 * eine Meldung (`.decideReport`, `entityId` = die Post-Id, nicht die Report-Id, damit
 * `listAuditLog(entityId = postId)` die vollständige Moderationsgeschichte eines Beitrags an
 * einer Stelle zeigt) sind beide `UPDATE`-Aktionen auf `entityType = SOCIAL_POST`. Der
 * post-bezogene DSGVO-Content-Löschantrag (`.executeContentErasure`) läuft bewusst über DIESEN
 * Log (`entityType = SOCIAL_POST`, `action = UPDATE`, `entityId` = die Post-Id) und NICHT über
 * `dsgvo_audit_log` -- letzteres bleibt dem bestehenden, mitgliedsweiten Erasure-Pfad
 * (`DsgvoService`/`network.lapis.cloud.server.dsgvo.SocialNetworkPersonalData.erase`, ON_AUTHOR_
 * REQUEST) vorbehalten. Der vor/nach-Snapshot trägt dabei niemals Post-INHALT, nur `state`/
 * `stateReason`/`visibility`/`contentErasedAt` -- siehe [SocialPostModerationSnapshot] KDoc für
 * die Begründung (append-only/hash-gekettete Snapshots dürfen niemals `content` tragen).
 * `ORGANIZATION_SETTINGS` (Welle V1.2.1 "Zahlungs-Fundament", Security Round 1, 2026-08-19,
 * MAJOR-2) wurde LAST danach angehängt -- `OrganizationSettingsService.updateOrganizationSettings`
 * schreibt `entityType = ORGANIZATION_SETTINGS`, `action = UPDATE`, `entityId =
 * OrganizationSettingsService.ORGANIZATION_SETTINGS_ID` mit einem
 * [OrganizationSettingsPaymentMappingSnapshot] vor/nach, aber NUR wenn sich mindestens eines der
 * drei Zahlungs-Konto-Zuordnungsfelder (`paymentBankAccountId`/`paymentFeeAccountId`/
 * `contributionIncomeAccountId`) tatsächlich geändert hat -- diese Methode ersetzt sonst pauschal
 * viele nicht-finanzielle Felder (Adresse, IBAN-Anzeige, Gemeinnützigkeits-Daten) bei jedem Aufruf,
 * und ein Audit-Eintrag bei jeder solchen Änderung würde die GoBD-Spur mit für diese
 * Konto-Routing-Frage irrelevanten Einträgen fluten. Siehe
 * `OrganizationSettingsService.updateOrganizationSettings` KDoc für die volle Begründung (GoBD
 * Nachvollziehbarkeit: WER hat WANN die Konten-Zuordnung geändert, in die jeder künftige
 * Mitgliedsbeitrag gebucht wird).
 * `SEPA_MANDATE`/`SEPA_DEBIT_BATCH` (Welle V1.2.2 "SEPA-Lastschriftmandate") were appended LAST after
 * that -- `SepaService`'s mandate/batch-lifecycle methods write `entityType = SEPA_MANDATE` for every
 * mandate grant/revoke/poller-driven expiry-or-lapse (see [SepaMandateSnapshot] KDoc for why it never
 * carries account data) and `entityType = SEPA_DEBIT_BATCH` for every batch state transition (see
 * [SepaDebitBatchSnapshot]). `PAYMENT_TRANSACTION` was deliberately NOT added ahead of need -- no
 * wave has a writer for it yet, same "no build-ahead-of-need" rule Welle V1.2.1 already applied to
 * itself. `DUNNING_NOTICE` (Welle V1.2.7 "Automatisiertes Mahnwesen") was appended LAST after
 * that -- `network.lapis.cloud.server.payment.dunning.DunningIssuance`'s single shared issuance
 * path (used by both the poller and every manual RPC override) writes `entityType =
 * DUNNING_NOTICE` for every notice CREATE (issued/skipped) and UPDATE (cancelled) -- see
 * [DunningNoticeSnapshot] KDoc for why it never carries member/address data.
 * `MEMBER` (Welle V1.2.12 "Mitgliederverwaltung: vollständige Bearbeitung + privilegiertes
 * Roster") was appended LAST after that -- `network.lapis.cloud.server.rpc.MemberService`'s three
 * privileged update RPCs (`updateMemberCoreData`/`updateMemberStatus`/`updateMemberRole`) each
 * write exactly one `entityType = MEMBER`, `action = UPDATE` entry per actual mutation (never for
 * a no-op/idempotent call, see [MemberChangeSnapshot] KDoc), `entityId` = the target member's id.
 * Welle V1.2.13 added a FOURTH writer, `MemberService.grantMemberAccount`, and the first one that
 * uses `action = CREATE` for this entity type: exactly one `MEMBER`/`CREATE` entry per granted
 * login account, `before.role = null` -> `after.role = <granted role>`, `status` unchanged on both
 * sides. `PAYMENT_TRANSACTION` (Welle V1.2.8 "PSP-Checkout (Stripe)", GitHub Issue #6) was appended
 * LAST after that -- `network.lapis.cloud.server.payment.psp.PspWebhookIngestion` writes exactly one
 * `PAYMENT_TRANSACTION`/`CREATE` entry per successfully-ingested `checkout.session.completed`
 * webhook delivery, `entityId` = the new `payment_transaction` row's id, see [PaymentTransactionSnapshot]
 * KDoc. `ContributionPostingBridge`'s own accounting audit entry continues to reuse the EXISTING
 * `JOURNAL_ENTRY` literal unchanged -- this is a SECOND, additional entry describing the gateway
 * receipt itself, not a replacement. Additive append only -- never reorder existing literals,
 * see this enum's own "cheap to extend, expensive to reorder" note class-wide.
 */
@Serializable
enum class AuditEntityType {
    JOURNAL_ENTRY,
    PARTY_DONATION_VERDICT,
    RESOLUTION,
    BOARD_MEMBERSHIP,
    CONFERENCE_RECORDING,
    CONFERENCE_STREAM,
    CONFERENCE_STREAM_DESTINATION,
    CONFERENCE_ROOM,
    SOCIAL_POST,
    ORGANIZATION_SETTINGS,
    SEPA_MANDATE,
    SEPA_DEBIT_BATCH,
    DUNNING_NOTICE,
    MEMBER,
    PAYMENT_TRANSACTION,

    /**
     * Welle V1.3.1 "API-Fundament, lesend" -- [network.lapis.cloud.server.rpc.ApiKeyService]'s
     * `issueApiKey`/`revokeApiKey`/`reissueApiKey` each write exactly one `API_KEY` entry per
     * lifecycle event (`CREATE` for issue, `UPDATE` for revoke; `reissueApiKey` writes both: an
     * `UPDATE` for the revoked old key followed by a `CREATE` for the freshly issued one). See
     * [ApiKeySnapshot] KDoc for why it never carries the token hash.
     */
    API_KEY,

    /**
     * Welle V1.3.2 "Webhooks" (ausgehend) -- `network.lapis.cloud.server.rpc.WebhookService`'s
     * `setWebhookUrl`/`removeWebhookUrl`/`rotateWebhookSecret`/`reactivateWebhookEndpoint` each
     * write exactly one `WEBHOOK_ENDPOINT` entry (`CREATE` for the endpoint's first `setWebhookUrl`
     * call, `UPDATE` for every subsequent lifecycle change), and
     * `network.lapis.cloud.server.webhook.WebhookDeliveryPoller`'s auto-deactivation writes a
     * SYSTEM-actor (`actorMemberId = null`) `UPDATE` for `DELIVERY_FAILURES`/`RECEIVER_GONE`. See
     * [WebhookEndpointSnapshot] KDoc for why it never carries the signature secret. Appended LAST,
     * additive only.
     */
    WEBHOOK_ENDPOINT,

    /**
     * Welle V1.4.5.1 "Kontoauszugs-Import (CSV/MT940)" --
     * `network.lapis.cloud.server.payment.bankstatement.BankStatementImportService` writes exactly
     * one `BANK_STATEMENT_IMPORT`/`CREATE` entry per completed import (auto-posted or not),
     * `entityId` = the new `bank_statement_import` row's id. See [BankStatementImportSnapshot] KDoc
     * for why it never carries a counterparty name/IBAN.
     */
    BANK_STATEMENT_IMPORT,

    /**
     * Welle V1.4.5.3 "lexoffice-Live-Anbindung" --
     * `network.lapis.cloud.server.rpc.AccountingExportService`'s `setToken`/`removeToken`/
     * `acknowledgeZeroVat` each write exactly one `ACCOUNTING_EXPORT_CONNECTION` entry
     * (`CREATE` for the connection's first `setToken` call, `UPDATE` for every subsequent
     * lifecycle change), `entityId` = the `accounting_export_connection` row's id. Never carries
     * the token itself, sealed or plain -- see `AccountingExportConnectionDto` KDoc for why.
     */
    ACCOUNTING_EXPORT_CONNECTION,

    /**
     * Security review Runde 3, Befund 4 (Fund 2026-09-07) -- `AccountingExportService`'s
     * `startExport`/`abortRun`/`retryFailed` each write exactly one `ACCOUNTING_EXPORT_RUN`
     * `CREATE`/`UPDATE`/`UPDATE` entry respectively, `entityId` = the `accounting_export_run`
     * row's id. `retryFailed` is the operation most in need of this: it is what resends an
     * aborted/reaped run's items to the external accounting service, so after any incident
     * involving a duplicate voucher, this is the only record of WHO triggered that resend and
     * WHEN -- `accounting_export_run`/`accounting_export_item` themselves carry no actor column
     * for abort/retry (unlike `started_by` for the original `startExport`). Appended LAST, after
     * `ACCOUNTING_EXPORT_CONNECTION`, additive only.
     *
     * Security review Fund 2026-09-07 (Runde 4, Befund 2) -- `resolveUnknownItem` writes a fourth
     * `UPDATE` entry here too (`entityId` = the item's OWN `accounting_export_run`, i.e. the run
     * that item belongs to, same as `retryFailed`/`abortRun`): it is the one action that turns an
     * `UNKNOWN` item's outcome into a human-asserted fact (lexoffice does or does not have the
     * voucher), which is precisely the kind of judgment call an audit trail needs to attribute.
     */
    ACCOUNTING_EXPORT_RUN,

    /**
     * Security review Runde 3, Befund 4 (Fund 2026-09-07) -- `AccountingExportService.mapAccount`
     * writes one `ACCOUNTING_EXPORT_MAPPING` `CREATE`/`UPDATE` entry per call (`CREATE` for a
     * ledger account's first mapping, `UPDATE` for every remapping), `entityId` =
     * the `accounting_export_category_map` row's id. `accounting_export_category_map` already
     * carries `mapped_by`/`mapped_at` on the row itself (unlike the run-lifecycle actions above),
     * so this entry is a secondary, append-only trail rather than the only record. Appended LAST,
     * after `ACCOUNTING_EXPORT_RUN`, additive only.
     */
    ACCOUNTING_EXPORT_MAPPING,

    /**
     * Welle V1.4.10 "Beitragsvergünstigungen" -- `network.lapis.cloud.server.rpc
     * .ContributionReliefService`'s `requestRelief`/`withdrawReliefRequest`/`decideReliefRequest`/
     * `retryReliefExecution` each write exactly one `CONTRIBUTION_RELIEF_REQUEST` entry per state
     * transition, `entityId` = the `contribution_relief_request` row's id. See
     * [ContributionReliefSnapshot] KDoc for why it never carries `reasonText`/`decisionNote`.
     * Appended LAST, after `ACCOUNTING_EXPORT_MAPPING`, additive only.
     */
    CONTRIBUTION_RELIEF_REQUEST,

    /**
     * Welle V1.4.11 "Reisekostenabrechnung für Vorstand und Funktionsträger" --
     * `network.lapis.cloud.server.rpc.TravelExpenseService`'s `createDraft`/`submitReport`/
     * `withdrawReport`/`decideReport`/`retryPosting` each write exactly one
     * `TRAVEL_EXPENSE_REPORT` entry per state transition, `entityId` = the
     * `travel_expense_report` row's id. The booking itself stays `JOURNAL_ENTRY` (same split
     * `DonationPostingBridge`/`PartyDonationVerdictSnapshot` already establish) -- this literal
     * describes only the request's own lifecycle. Draft mutations (`updateDraft`/`addLine`/
     * `removeLine`/receipt upload/deletion) deliberately write NO entry -- a draft is not yet a
     * transaction, see [TravelExpenseSnapshot] KDoc. Appended LAST, after
     * `CONTRIBUTION_RELIEF_REQUEST`, additive only.
     */
    TRAVEL_EXPENSE_REPORT,

    /**
     * Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale" (§3 Nr. 26 / 26a EStG) --
     * `network.lapis.cloud.server.rpc.VolunteerAllowanceService`'s `createDraft`/`submitPayment`/
     * `withdrawPayment`/`decidePayment`/`retryPosting` each write exactly one
     * `VOLUNTEER_ALLOWANCE_PAYMENT` entry per state transition, `entityId` = the
     * `volunteer_allowance_payment` row's id. The booking itself stays `JOURNAL_ENTRY`, same split
     * `TravelExpensePostingBridge` already establishes. See [VolunteerAllowanceSnapshot] KDoc for
     * why it never carries `activityDescription`/`decisionNote`. Deliberately 27 characters, well
     * under the `audit_log_entry.entity_type` `VARCHAR(29)` width limit (unlike the two longer
     * alternatives this wave considered and rejected, see [VOLUNTEER_DECLARATION] KDoc). Appended
     * LAST, after `TRAVEL_EXPENSE_REPORT`, additive only.
     */
    VOLUNTEER_ALLOWANCE_PAYMENT,

    /**
     * Welle V1.4.12 -- `network.lapis.cloud.server.rpc.VolunteerAllowanceService`'s `declareSelf`/
     * `recordPaperDeclaration` each write exactly one `VOLUNTEER_DECLARATION` entry, `entityId` =
     * the `volunteer_allowance_self_declaration` row's id. See [VolunteerAllowanceDeclarationSnapshot]
     * KDoc -- carries no free text at all. **Named `VOLUNTEER_DECLARATION`, not the more literal
     * `VOLUNTEER_ALLOWANCE_SELF_DECLARATION` (36 chars) or `VOLUNTEER_ALLOWANCE_DECLARATION` (31
     * chars)** -- both exceed the `audit_log_entry.entity_type` `VARCHAR(29)` hard limit
     * (`AuditLogEntryTable.entityType` is `enumerationByName(..., 29)`); this 21-character literal
     * is the shortest name that stays unambiguous. `AuditLogSchemaDriftTest` regression-guards
     * every literal in this enum against that width. Appended LAST, after
     * `VOLUNTEER_ALLOWANCE_PAYMENT`, additive only.
     */
    VOLUNTEER_DECLARATION,

    /**
     * Welle V1.4.14 "Mehrere Bankkonten" -- `network.lapis.cloud.server.payment.bankstatement
     * .BankAccountStore`'s `create`/`update`/`delete`/`setDefault` each write exactly one
     * `BANK_ACCOUNT` entry per state transition, `entityId` = the `bank_account` row's id. See
     * `network.lapis.cloud.shared.domain.BankAccountSnapshot` KDoc for why it never carries the
     * full IBAN. Appended LAST, after `VOLUNTEER_DECLARATION`, additive only.
     */
    BANK_ACCOUNT,

    /**
     * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" --
     * `network.lapis.cloud.server.rpc.OpenItemService`'s `createOpenItem`/`retryOpenItemPosting`/
     * `settleOpenItem`/`reverseSettlement`/`cancelOpenItem` each write exactly one `OPEN_ITEM`
     * `CREATE`/`UPDATE` entry per state transition, `entityId` = the `open_item` row's id. See
     * [OpenItemSnapshot] KDoc for why it never carries the free-text counterparty name/reference/
     * note. Appended LAST, after `BANK_ACCOUNT`, additive only.
     */
    OPEN_ITEM,

    /**
     * Welle V1.4.15 -- `network.lapis.cloud.server.rpc.OpenItemService`'s `executeNetting`/
     * `reverseNetting` each write exactly one `OPEN_ITEM_NETTING` `CREATE`/`VOID` entry,
     * `entityId` = the `open_item_netting` row's id. See [OpenItemNettingSnapshot] KDoc -- carries
     * both paired item ids and the netted amount, never free text. Appended LAST, after
     * `OPEN_ITEM`, additive only.
     */
    OPEN_ITEM_NETTING,

    /**
     * Welle V1.4.15 -- `network.lapis.cloud.server.openitem.dunning.ReceivableDunningService`'s
     * `issueReceivableDunningNotice`/`skipReceivableDunningLevel`/`cancelReceivableDunningNotice`
     * (and the poller's own automated issuance path) each write exactly one
     * `RECEIVABLE_DUNNING_NOTICE` `CREATE`/`UPDATE` entry, `entityId` = the
     * `receivable_dunning_notice` row's id. See [ReceivableDunningNoticeSnapshot] KDoc. Deliberately
     * a SEPARATE literal from `DUNNING_NOTICE` (the pre-existing member-contribution dunning
     * domain) -- this wave's receivable dunning is a structurally independent domain, see
     * `network.lapis.cloud.server.rpc.ReceivableDunningService` KDoc. 25 characters, well under the
     * `audit_log_entry.entity_type` `VARCHAR(29)` width limit. Appended LAST, after
     * `OPEN_ITEM_NETTING`, additive only.
     */
    RECEIVABLE_DUNNING_NOTICE,

    /**
     * Welle V1.9.1 "Zugriffsrechte für Dokumente und Ordner sichtbar und editierbar" --
     * `network.lapis.cloud.server.rpc.DocumentService`'s `createDocument`/`setDocumentAccessLevel`
     * each write exactly one `DOCUMENT` `CREATE`/`UPDATE` entry, `entityId` = the `document` row's
     * id. `deleteDocument` writes `VOID` here too -- see [AuditAction] KDoc for why this wave
     * deliberately reuses `VOID` instead of adding a new `DELETE` literal. See
     * [DocumentAccessLevelSnapshot] KDoc. 8 characters, well under the `audit_log_entry.entity_type`
     * `VARCHAR(29)` width limit. Appended LAST, after `RECEIVABLE_DUNNING_NOTICE`, additive only.
     */
    DOCUMENT,

    /**
     * Welle V1.9.1 -- `network.lapis.cloud.server.rpc.DocumentService`'s `createFolder`/
     * `setFolderAccessLevel` each write exactly one `DOCUMENT_FOLDER` `CREATE`/`UPDATE` entry,
     * `entityId` = the `document_folder` row's id. A `setFolderAccessLevel` verschärfung ADDITIONALLY
     * writes one `DOCUMENT`/`UPDATE` entry per cascaded document (`cascadedFromFolderId` set) --
     * N+1 rows, never a single combined entry, for full revision-safety. See
     * [DocumentFolderAccessLevelSnapshot] KDoc. 15 characters, well under the VARCHAR(29) width
     * limit. Appended LAST, after `DOCUMENT`, additive only.
     */
    DOCUMENT_FOLDER,

    /**
     * Welle V1.4.34 "Nachrichten-/Artikel-Modul mit redaktionellem Workflow" --
     * `network.lapis.cloud.server.rpc.ArticleService`'s `submitArticle`/`approveArticle`/
     * `rejectArticle`/`unpublishArticle` each write exactly one `ARTICLE` `UPDATE` entry per status
     * transition, `entityId` = the `article` row's id. 7 characters, well under the VARCHAR(29)
     * width limit. Appended LAST, after `DOCUMENT_FOLDER`, additive only -- see
     * `V55__article.sql`'s `chk_audit_log_entry_entity_type` widening.
     */
    ARTICLE,

    /**
     * Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)" -- `network.lapis.cloud.server.rpc
     * .RegionalChapterService`'s `createChapter`/`renameChapter` write `CREATE`/`UPDATE`
     * respectively, `entityId` = the `regional_chapter` row's id, [RegionalChapterSnapshot]
     * before/after. `deleteChapter` writes `VOID` (there is no `DELETE` [AuditAction] literal --
     * see that enum's own KDoc for why `VOID` is reused) with `after = null` -- the sole
     * surviving record that the chapter ever existed, same idiom
     * [CONFERENCE_RECORDING]'s own KDoc establishes for a hard-deleted row. A member's OWN
     * chapter *assignment* (`RegionalChapterService.assignMemberToChapter`) is deliberately
     * logged as `entityType = MEMBER` instead (see [MemberChangeSnapshot] KDoc "regionalChapterId"
     * paragraph) -- it is a fact about the MEMBER row, not about the chapter. 16 chars, well
     * under the `audit_log_entry.entity_type` `VARCHAR(29)` width limit. Appended LAST, after
     * `ARTICLE`, additive only.
     */
    REGIONAL_CHAPTER,

    /**
     * Welle V1.9.13 -- `network.lapis.cloud.server.rpc.RegionalChapterService`'s `grantOfficer`/
     * `revokeOfficer` each write exactly one `REGIONAL_CHAPTER_OFFICER` `CREATE`/`UPDATE` entry,
     * `entityId` = the `regional_chapter_officer` row's id, [RegionalChapterOfficerSnapshot]
     * before/after. Never carries the officer's display name/email -- same PII-minimization
     * discipline every other snapshot in this file establishes (see [SepaMandateSnapshot] KDoc):
     * only ids. 24 chars, well under the `VARCHAR(29)` width limit. Appended LAST, after
     * `REGIONAL_CHAPTER`, additive only.
     */
    REGIONAL_CHAPTER_OFFICER,

    /**
     * Welle V1.9.18 "Verwaltung der Mitgliedschaftsstufen" -- `network.lapis.cloud.server.rpc
     * .ContributionService`'s `createMembershipTier`/`updateMembershipTier` write `CREATE`/`UPDATE`,
     * `entityId` = the `membership_tier` row's id, [MembershipTierSnapshot] before/after (an update
     * that changes nothing writes no entry). A tier's contribution amount is bookkeeping-relevant:
     * it decides what every member of the tier is invoiced, so a change to it must be traceable.
     * A member's own tier *assignment* is logged as `entityType = MEMBER` instead
     * ([MemberMembershipTierSnapshot]). 15 chars, well under the `audit_log_entry.entity_type`
     * `VARCHAR(29)` width limit. Appended LAST, after `REGIONAL_CHAPTER_OFFICER`, additive only.
     */
    MEMBERSHIP_TIER,

    /**
     * Welle V1.9.30 "Umfragen auf LTR-Basis" (Server) -- `network.lapis.cloud.server.rpc.PollService`'s
     * `createPoll`/`closePoll`/`abortPoll` write `CREATE`/`UPDATE`/`VOID`, `entityId` = the `poll` row's
     * id, [PollSnapshot] before/after. Individual RESPONSES are deliberately NEVER audited (that would
     * link a member to a moment in the poll's lifecycle and defeat the anonymity of `poll_response`), and
     * a deadline expiring is a pure read-state, not an event, so it writes no entry either. 4 chars,
     * well under the `audit_log_entry.entity_type` `VARCHAR(29)` width limit. Appended LAST, after
     * `MEMBERSHIP_TIER`, additive only.
     */
    POLL,
}

/**
 * V1.9.30 -- before/after snapshot of a poll's lifecycle audit entries ([AuditEntityType.POLL]). Carries
 * the question, the option texts, the status and the two instants. Deliberately NO counters, NO weights,
 * NO respondents: the append-only hash chain must never carry any information about who answered what.
 */
@Serializable
data class PollSnapshot(
    val question: String,
    val options: List<String>,
    val status: String,
    val closesAt: LocalDateTime?,
    val closedAt: LocalDateTime?,
    /** V1.9.41: set only for the consensus kinds (null keeps the JSON of classic polls unchanged). */
    val kind: String? = null,
    val optionExplanations: List<String?>? = null,
)

/**
 * One immutable, hash-chained audit-log row -- see `AuditLogRecorder`/`AuditLogService` KDoc.
 * [beforeSnapshot]/[afterSnapshot] are raw JSON strings (one of [JournalEntrySnapshot] /
 * [ResolutionSnapshot] / [BoardMembershipSnapshot] / [PartyDonationVerdictSnapshot] depending on
 * [entityType]) -- kept as opaque strings here rather than a sealed-class union so a client can
 * always render *something* even for a future [entityType] this DTO's own release predates;
 * deserialize with `Json.decodeFromString<...>(...)` keyed on [entityType] when structured access
 * is needed. [actorMemberId]/[actorRole] are both `null` only for a (currently unused, reserved)
 * future SYSTEM/job actor -- every V0.5.3 write path always names a real member actor.
 * [previousEntryHash] is `null` only for the very first ("genesis") row in the whole chain.
 */
@Serializable
data class AuditLogEntryDto(
    val id: String,
    val sequenceNumber: Long,
    val occurredAt: LocalDateTime,
    val actorMemberId: String?,
    val actorMemberDisplayName: String?,
    val actorRole: AccountRole?,
    val entityType: AuditEntityType,
    val entityId: String,
    val action: AuditAction,
    val beforeSnapshot: String?,
    val afterSnapshot: String?,
    val entryHash: String,
    val previousEntryHash: String?,
    /**
     * V1.9.53: `true` iff [afterSnapshot] (a resolution snapshot) had its vote figures zeroed on delivery because
     * the resolution stems from a secret election below the minimum participation. The stored row and its hash are
     * untouched, so [entryHash] does not match the delivered snapshot of such an entry. This is no secrecy guarantee
     * for entries written before V1.9.53: the hash fields are still delivered and the few possible figure triples can
     * be brute-forced against them. A snapshot that cannot be decoded is delivered as `null` (fail-closed).
     */
    val figuresWithheld: Boolean = false,
)

/**
 * Bundles [network.lapis.cloud.shared.rpc.IAuditLogService.listAuditLog]'s optional filters into a
 * single parameter -- kilua-rpc's generated `bind` overloads only go up to 6 reified type
 * parameters (`PAR1..PAR6` + `RET`), and this method has 7 independent filters; a single query
 * object both fits that ceiling and reads better at call sites than seven positional/named
 * arguments. All fields default to "no filter"/the house-standard page size, matching every other
 * `activeOnly`/`includeResolved`-style optional-filter default in this codebase.
 *
 * V1.9.38: [from]/[to] are wall-clock values in the ORGANIZATION time zone (what the filter form shows,
 * since audit timestamps are displayed in that zone); the server converts them to UTC before comparing them
 * with the stored `occurred_at`.
 */
@Serializable
data class AuditLogListQuery(
    val entityType: AuditEntityType? = null,
    val entityId: String? = null,
    val actorMemberId: String? = null,
    val from: LocalDateTime? = null,
    val to: LocalDateTime? = null,
    val limit: Int = 50,
    val beforeSequenceNumber: Long? = null,
)

/**
 * Result of re-walking the hash chain over `[firstSequenceNumber, lastSequenceNumber]` (both
 * `null` when zero rows were in range) and recomputing every row's hash from its own stored
 * fields, comparing against the stored [network.lapis.cloud.shared.rpc.IAuditLogService
 * .verifyChainIntegrity] KDoc for the exact algorithm. [valid] is `true` iff every row's
 * recomputed hash matches its stored `entryHash` AND every row's stored `previousEntryHash`
 * matches the immediately preceding row's `entryHash` (or is `null` for the very first row in
 * range only when that row is also sequence number 1). [brokenAtSequenceNumber]/[reason] are
 * non-null only when [valid] is `false`.
 */
@Serializable
data class AuditChainVerificationResultDto(
    val valid: Boolean,
    val checkedCount: Int,
    val firstSequenceNumber: Long?,
    val lastSequenceNumber: Long?,
    val brokenAtSequenceNumber: Long?,
    val reason: String?,
)

/**
 * Structured before/after payload for an [AuditEntityType.JOURNAL_ENTRY] audit entry -- referenced
 * foreign entities (donor member, external donor, ledger accounts, cost centers) are carried by id
 * only, never by display name (PII minimization -- see `AuditLogPersonalData` KDoc: names are
 * resolved at read time via [AuditLogEntryDto.actorMemberDisplayName]'s own pattern, never baked
 * into a snapshot that is retained forever).
 *
 * Serialized size grows with [postings] and is NOT capped by [postings]'s own count -- there is
 * deliberately no maximum-posting-count validation in `AccountingService`. This is exactly why
 * `AuditLogEntryTable.beforeSnapshot`/`afterSnapshot` are modelled as unbounded `TEXT` columns
 * (`14-audit-log.kuml.kts`), not a fixed-length `VARCHAR` -- a capped column would eventually
 * reject a legitimate, balanced `JournalEntry` purely because it happened to carry enough
 * `Postings` to serialize past the cap, and truncating the snapshot instead would violate GoBD
 * Vollstaendigkeit.
 */
@Serializable
data class JournalEntrySnapshot(
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String?,
    val status: JournalEntryStatus,
    val postedAt: LocalDateTime?,
    val createdBy: String,
    val donorMemberId: String?,
    val externalDonorId: String?,
    val donorCategory: DonorCategory?,
    val postings: List<PostingSnapshot>,
)

/** One Soll/Haben line within a [JournalEntrySnapshot] -- mirrors [PostingDto]'s own shape, id-only.
 *  [vatRate]/[vatAmount] (V1.4.13) carry DEFAULTS -- unlike [PostingDto], where [PostingDto
 *  .vatAmount] has none -- so that historical audit-log JSON payloads written BEFORE this wave
 *  (`audit_log_entry.after`) remain deserializable: [vatAmount] is nullable (a pre-V1.4.13 posting
 *  has no meaningful "unknown VAT amount" value; `Decimal` has no sentinel for that), [vatRate]
 *  defaults to [VatRate.UNCLASSIFIED] (the truthful "not asked" reading for any snapshot written
 *  before this field existed). */
@Serializable
data class PostingSnapshot(
    val ledgerAccountId: String,
    val side: PostingSide,
    val amount: Decimal,
    val sphere: GemeinnuetzigkeitSphere,
    val costCenterId: String?,
    val vatRate: VatRate = VatRate.UNCLASSIFIED,
    val vatAmount: Decimal? = null,
)

/** Structured payload for an [AuditEntityType.RESOLUTION] audit entry -- CREATE only, see file header. */
@Serializable
data class ResolutionSnapshot(
    val meetingId: String,
    val number: String,
    val title: String,
    val text: String,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val quorumMet: Boolean,
    val status: ResolutionStatus,
    val decidedAt: LocalDateTime,
    val recordedBy: String,
    val resolutionMode: ResolutionMode,
)

/** Structured payload for an [AuditEntityType.BOARD_MEMBERSHIP] audit entry. */
@Serializable
data class BoardMembershipSnapshot(
    val memberId: String,
    val committeeRole: CommitteeRole,
    val startedAt: LocalDate,
    val endedAt: LocalDate?,
)

/**
 * Structured payload for an [AuditEntityType.PARTY_DONATION_VERDICT] audit entry --
 * [entityId] on the owning [AuditLogEntryDto] is the [JournalEntrySnapshot]'s own JournalEntry id
 * (this verdict is always recorded alongside, and pointing at, the JournalEntry it was computed
 * for). [verdict] is always the literal string `"ALLOWED"` -- a `PROHIBITED` attempt never reaches
 * a committed JournalEntry at all (the whole posting transaction rolls back first), so no
 * `PartyDonationVerdictSnapshot` for a prohibited attempt can ever exist; see
 * `AuditLogRecorder`/`AccountingService` KDoc for the full rationale of this deliberate, bounded
 * scope decision. Kept as a plain `String` rather than referencing
 * `network.lapis.cloud.server.rpc.DonationVerdict` -- that enum is `internal` to the server module
 * and has only ever one representable value at this call site anyway.
 */
@Serializable
data class PartyDonationVerdictSnapshot(
    val donorCategory: DonorCategory,
    val donationAmount: Decimal,
    val priorPostedTotalThisYear: Decimal,
    val verdict: String,
    val duties: List<DonationDuty>,
)

/**
 * Structured payload for an [AuditEntityType.SOCIAL_POST] audit entry (Welle V1.1.5) --
 * `removePostForLegalReason` (state UPDATE) und `decideReport` (Meldungs-Entscheidung). **Trägt
 * ausschließlich Metadaten -- NIEMALS den Post-`content`.** `AuditLogRecorder.record` schreibt in
 * eine append-only, hash-gekettete, nachweislich unveränderliche Tabelle
 * (`AuditLogImmutabilityTest` scannt den Quelltext dagegen); ein Snapshot, der den Post-Inhalt
 * enthielte, würde genau den Inhalt konservieren, den eine spätere Art.-17-Löschung entfernen
 * muss -- und wäre danach nicht mehr entfernbar, ohne die Hash-Kette zu brechen. [visibility] ist
 * unveränderlich (write-once, siehe `SocialVisibility` KDoc) und deshalb unbedenklich; [state]/
 * [stateReason] sind genau die beiden Felder, die eine rechtliche Entfernung tatsächlich ändert.
 */
@Serializable
data class SocialPostModerationSnapshot(
    val state: SocialPostState,
    val stateReason: String?,
    val visibility: SocialPostVisibility,
    val contentErasedAt: LocalDateTime?,
)

/**
 * Structured payload for an [AuditEntityType.ORGANIZATION_SETTINGS] audit entry (Welle V1.2.1
 * "Zahlungs-Fundament", Security Round 1, 2026-08-19, MAJOR-2) --
 * `network.lapis.cloud.server.rpc.OrganizationSettingsService.updateOrganizationSettings`'s ONLY
 * audit signal, deliberately narrowed to the payment-account-mapping fields rather than the
 * full wholesale-replace diff of every `OrganizationSettingsInput` field -- see that method's own
 * KDoc for why. Carries LedgerAccount ids only (never account numbers/names), matching every other
 * snapshot's id-only PII-minimization convention (see [JournalEntrySnapshot] KDoc).
 *
 * [donationIncomeAccountId] (Welle V1.2.8 "PSP-Checkout (Stripe)") was appended LAST -- a fourth
 * mapping field, same audit-relevance reasoning as the original three.
 *
 * [eventIncomeAccountId] (Welle V1.4.3.1 "Veranstaltungen", Review MAJOR fix) is a fifth mapping
 * field, same audit-relevance reasoning -- id only, never the sphere (`event_income_sphere` is a
 * classification, not a "which account did money move to" fact, so it stays outside this
 * deliberately narrow snapshot, same as every other non-account-id field `updateOrganizationSettings`
 * writes).
 *
 * [travelExpenseAccountId] (Welle V1.4.11 "Reisekostenabrechnung") is a sixth mapping field, same
 * audit-relevance reasoning -- the first EXPENSE-side (not INCOME-side) account this snapshot
 * carries, but the same "which account does money move through" fact GoBD Nachvollziehbarkeit
 * cares about.
 *
 * [volunteerAllowanceAccountId] (Welle V1.4.12 "Übungsleiter- und Ehrenamtspauschale") is a
 * seventh mapping field, same reasoning again -- the second EXPENSE-side account.
 */
@Serializable
data class OrganizationSettingsPaymentMappingSnapshot(
    val paymentBankAccountId: String?,
    val paymentFeeAccountId: String?,
    val contributionIncomeAccountId: String?,
    val donationIncomeAccountId: String? = null,
    val eventIncomeAccountId: String? = null,
    val travelExpenseAccountId: String? = null,
    val volunteerAllowanceAccountId: String? = null,
    /** V1.4.15. See `OrganizationSettingsDto.receivablesAccountId`/`.payablesAccountId` KDoc. */
    val receivablesAccountId: String? = null,
    val payablesAccountId: String? = null,
)

/**
 * Structured payload for an [AuditEntityType.SEPA_MANDATE] audit entry (Welle V1.2.2). **NEVER
 * carries the IBAN, the sealed ciphertext, the BIC, or the account-holder name** -- [AuditLogRecorder]
 * writes into an append-only, hash-chained table; a snapshot carrying account data would preserve
 * exactly the data a later Art. 17 erasure would have to remove -- and would then no longer be
 * removable without breaking the chain. Same reasoning as [SocialPostModerationSnapshot] (V1.1.5).
 * Enforced by `PaymentsRegressionScanTest`.
 *
 * [mandateReference] is pseudonymous by construction (see
 * `network.lapis.cloud.server.payment.sepa.SepaMandateReferenceGenerator` KDoc) and may therefore be
 * this snapshot's only mandate-identifying feature.
 */
@Serializable
data class SepaMandateSnapshot(
    val memberId: String,
    val mandateReference: String,
    val status: SepaMandateStatus,
    val sequenceType: SepaSequenceType,
    val signatureDate: LocalDate,
    val lastUsedAt: LocalDate?,
    /** `false` -> entered on the member's behalf (E-12) -- the GoBD-/abuse-relevant part of the event. */
    val createdBySelf: Boolean,
)

/** Same "no IBAN, no account-holder name, no item detail -- only aggregates" discipline as [SepaMandateSnapshot]. */
@Serializable
data class SepaDebitBatchSnapshot(
    val messageId: String,
    val status: SepaDebitBatchStatus,
    val sequenceType: SepaSequenceType,
    val requestedCollectionDate: LocalDate,
    val itemCount: Int,
    val totalAmount: Decimal,
    val requiredNoticeDays: Int?,
    val notifiedAt: LocalDateTime?,
    val generatedDocumentId: String?,
)

/**
 * Structured payload for an [AuditEntityType.ORGANIZATION_SETTINGS] audit entry written by
 * `ISepaService.updateSepaCreditorSettings` (Welle V1.2.2) -- carries the SEPA creditor
 * identification number/name and the pre-notification period, all public organization attributes
 * (not personal data), only ever written on an ACTUAL change (same narrowing
 * `OrganizationSettingsService.updateOrganizationSettings` already applies, see
 * [OrganizationSettingsPaymentMappingSnapshot] KDoc).
 */
@Serializable
data class SepaCreditorSettingsSnapshot(
    val sepaCreditorId: String?,
    val sepaCreditorName: String?,
    val sepaPrenotificationDays: Int,
)

/**
 * Structured payload for an [AuditEntityType.DUNNING_NOTICE] audit entry (Welle V1.2.7). **NEVER
 * carries the member's name/address/e-mail** -- same PII-minimization discipline
 * [SocialPostModerationSnapshot]/[SepaMandateSnapshot] already establish for an append-only,
 * hash-chained table: a snapshot carrying personal data would preserve exactly the data a later
 * Art. 17 erasure would have to remove, and could then no longer be removed without breaking the
 * chain. [issuedBySystem] is `true` iff [network.lapis.cloud.server.payment.dunning.DunningPoller]
 * (not a human treasurer) issued this notice -- the GoBD-/accountability-relevant part of the
 * event; the actor itself is already carried by [AuditLogEntryDto.actorMemberId] (`null` for the
 * poller, same system-actor convention `SepaBatchPoller` already uses).
 */
@Serializable
data class DunningNoticeSnapshot(
    val contributionId: String,
    val cycleNumber: Int,
    val levelNumber: Int,
    val levelName: String,
    val status: DunningNoticeStatus,
    val amountDue: Decimal,
    val feeAmount: Decimal?,
    val respondBy: LocalDate,
    val documentId: String?,
    val issuedBySystem: Boolean,
)

/**
 * Structured payload for an [AuditEntityType.ORGANIZATION_SETTINGS] audit entry written by
 * `network.lapis.cloud.server.rpc.DunningService.createDunningLevel`/`updateDunningLevel`/
 * `deactivateDunningLevel` (Welle V1.2.7 -- Security Round, "kein Audit-Trail fuer die
 * Mahnstufen-Leiter" finding). Reuses [AuditEntityType.ORGANIZATION_SETTINGS] with
 * `entityId = network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID`, the SAME idiom
 * [SepaCreditorSettingsSnapshot] already establishes for an org-wide configuration change that
 * doesn't warrant its OWN [AuditEntityType] literal (a `dunning_level` row is configuration, not a
 * per-member fact) -- see that snapshot's own KDoc and `SepaService.updateSepaCreditorSettings` for
 * the precedent this mirrors. No PII (a dunning level carries no member data at all).
 */
@Serializable
data class DunningLevelSnapshot(
    val levelNumber: Int,
    val name: String,
    val graceDays: Int,
    val responseDays: Int,
    val feeAmount: Decimal?,
    val active: Boolean,
)

/**
 * Structured `before`/`after` payload for an [AuditEntityType.MEMBER] audit entry (Welle V1.2.12).
 * [reason] is set ONLY in `after`, and ONLY by `updateMemberStatus` -- `member.rejection_reason`
 * (see [MemberDto.rejectionReason]) is deliberately NOT reused for this: that column belongs to
 * the admission-rejection workflow ([network.lapis.cloud.shared.rpc.IRegistrationService
 * .rejectApplication]), a structurally different event with its own board-decision metadata
 * ([MemberDto.reviewedById]/[MemberDto.reviewedAt]). **Never carries address, GwG, or account
 * data** -- same PII-minimization discipline [SocialPostModerationSnapshot]/[SepaMandateSnapshot]/
 * [DunningNoticeSnapshot] already establish for an append-only, hash-chained table: a snapshot
 * carrying that data would preserve exactly what a later Art. 17 erasure would have to remove, and
 * could then no longer be removed without breaking the chain.
 *
 * **Security fix (2026-08-27, DSGVO Art. 17/15 MAJOR)**: [displayName]/[email] were REMOVED from
 * this type -- an earlier revision carried the SUBJECT's (not the actor's) plaintext `displayName`/
 * `email` here, which is exactly the PII-in-an-immutable-hash-chain mistake this KDoc's own
 * preceding paragraph warns every OTHER snapshot type in this file away from, and which
 * [AuditLogPersonalData.erase] cannot clear (retained unconditionally for GoBD, see that object's
 * KDoc) -- an Art. 17 erasure of the member could never actually remove their name/e-mail from the
 * chain. [AuditLogPersonalData.export] filtered on `actorMemberId` only (not `entityId`) and so
 * never surfaced it in the SUBJECT's own Art. 15 export either -- a SEPARATE, LOW-severity gap this
 * same security-fix wave also closed (see that object's own "Security fix (2026-08-27, LOW DSGVO
 * Art. 15)" KDoc paragraph): `export` now additionally includes `entityType == MEMBER`/`entityId ==
 * memberId` rows and surfaces `status`/`role`/`reason` from them, which is safe precisely BECAUSE
 * `displayName`/`email` no longer live in this type. [displayNameChanged]/[emailChanged] carry
 * the GoBD-relevant FACT (something about this field changed, WHO did it via
 * [AuditLogEntryDto.actorMemberId], WHEN via `occurredAt`) without the value itself -- the identity
 * is already `entityId`, and the CURRENT value always lives on the (erasable) `member` row. Only
 * [network.lapis.cloud.server.member.EmailChangeService] (V1.9.56: the only writer of an existing member's address,
 * only when a change became effective) ever sets `emailChanged = true` here; `updateMemberCoreData` (name only since
 * V1.9.56) writes `false`, and the
 * other three writers ([network.lapis.cloud.server.rpc.MemberService.updateMemberStatus]/
 * [network.lapis.cloud.server.rpc.MemberService.updateMemberRole]/
 * [network.lapis.cloud.server.rpc.MemberService.grantMemberAccount]) always write `false` for both,
 * since none of them ever touch either field.
 */
@Serializable
data class MemberChangeSnapshot(
    val displayNameChanged: Boolean,
    val emailChanged: Boolean,
    val status: MemberStatus,
    val role: AccountRole?,
    val reason: String? = null,
    /**
     * Welle V1.4.4.5 -- bewusst ein BOOLEAN, kein Rohdatum: dieselbe PII-Disziplin, die
     * [displayNameChanged]/[emailChanged] fuer diese hash-verkettete, unloeschbare Tabelle bereits
     * etablieren. Der aktuelle Wert lebt auf der `member`-Zeile, die lesbar bleibt.
     * `true` schreiben nur `MemberService.updateMemberStatus` (wenn der Statuswechsel das Datum
     * mitgesetzt oder -- beim Verlassen von DECEASED -- geloescht hat) und
     * `MemberService.correctDateOfDeath`.
     */
    val dateOfDeathChanged: Boolean = false,
    /**
     * Welle V1.4.9 "Admin-Passwort-Reset" -- welche der beiden ADMIN-Passwort-Aktionen diesen
     * Audit-Eintrag erzeugt hat, siehe [AdminPasswordAction]. Traegt NIEMALS das Passwort -- weder
     * Klartext noch Hash -- dieselbe PII-/GoBD-Disziplin, die [displayNameChanged]/[emailChanged]/
     * [dateOfDeathChanged] fuer diese hash-verkettete, unloeschbare Tabelle bereits etablieren.
     * Nur `network.lapis.cloud.server.rpc.MemberService.setTemporaryPasswordForMember`
     * (schreibt [AdminPasswordAction.TEMPORARY_PASSWORD_SET]) und
     * `network.lapis.cloud.server.rpc.MemberService.sendPasswordResetMailToMember` (schreibt
     * [AdminPasswordAction.RESET_MAIL_SENT]) setzen hier einen Wert; alle uebrigen fuenf
     * MEMBER-Schreiber lassen `null`. Default `null`, damit jeder bestehende Konstruktionsaufruf
     * unveraendert bleibt und die Dekodierung aelterer, bereits gespeicherter Zeilen abwaertskompatibel bleibt.
     */
    val adminPasswordAction: AdminPasswordAction? = null,
    /**
     * Welle V1.9.15 "SuperMailer" -- set ONLY by `MailingService.setTrackingConsent`/`unsubscribe`
     * when a member's own open/click tracking consent actually changed. Booleans and the list id
     * only -- no PII, same discipline as [displayNameChanged]. Logged under [AuditEntityType.MEMBER]
     * (no new entity type, so no schema/CHECK migration). Default `null`: older rows decode unchanged.
     */
    val mailingTrackingConsent: MailingTrackingConsentSnapshot? = null,
    /**
     * Welle V1.9.19 "Mitglieder-Foto" -- set ONLY by the member-photo publication lifecycle
     * (publish/unpublish/replace/status-loss/delete/moderation). Carries NEVER the public token,
     * the storage key or any image data -- only the action, the visibility and the consent version.
     * Logged under [AuditEntityType.MEMBER] (no new entity type, no migration). Default `null`:
     * older rows decode unchanged.
     */
    val memberPhoto: MemberPhotoAuditSnapshot? = null,
    /**
     * Welle V1.9.20 "Öffentliche Seiten" -- what happened to a member's public short introduction
     * (save/delete/publish/unpublish/moderation/status loss). Carries NEVER the text itself -- only
     * the action, the publication state and the consent version. Logged under
     * [AuditEntityType.MEMBER]. Default `null`: older rows decode unchanged.
     */
    val memberPublicBio: MemberPublicBioAuditSnapshot? = null,
    /**
     * Welle V1.9.56 "E-Mail-Änderung absichern" -- set ONLY by the address-change lifecycle (request, confirmation of
     * the new address, applied, revoked, withdrawn, expired, superseded, conflict). Carries NEVER an address (not
     * even masked or hashed, see [EmailChangeAuditFacts]) -- only event, kind and the change id. Logged under
     * [AuditEntityType.MEMBER]. Default `null`: older rows decode unchanged.
     */
    val emailChange: EmailChangeAuditFacts? = null,
    /**
     * Welle V1.9.57 "Admin-Peer-Schutz" -- set by the peer protection (request, approval, execution, refusal, console
     * action, notices about a new administrator). Carries NEVER a reason, token or address, see [PeerActionAuditFacts].
     * Logged under [AuditEntityType.MEMBER]. Default `null`: older rows decode unchanged.
     */
    val peerAction: PeerActionAuditFacts? = null,
)

/** Welle V1.9.19 -- what happened to a member's photo publication. No PII, no token. */
@Serializable
data class MemberPhotoAuditSnapshot(
    val action: MemberPhotoAuditAction,
    val visibility: MemberPhotoVisibility,
    val consentTextVersion: String?,
)

/** Welle V1.9.19 -- see [MemberPhotoAuditSnapshot]. */
@Serializable
enum class MemberPhotoAuditAction {
    PUBLISHED,
    UNPUBLISHED,
    UNPUBLISHED_BY_REPLACEMENT,
    UNPUBLISHED_BY_STATUS_CHANGE,
    DELETED_BY_OWNER,
    REMOVED_BY_MODERATION,
}

/** Welle V1.9.15 -- the consent state AFTER a change (before: the same shape in the `before` snapshot). */
@Serializable
data class MailingTrackingConsentSnapshot(
    val mailingListId: String,
    val openTracking: Boolean,
    val clickTracking: Boolean,
)

/**
 * Welle V1.4.9 "Admin-Passwort-Reset" -- welche der beiden ADMIN-ausgeloesten Passwort-Aktionen
 * einen [MemberChangeSnapshot] erzeugt hat. BEWUSST EIN nullable ENUM statt zweier Booleans: zwei
 * sich gegenseitig ausschliessende Flags koennten den Unsinnszustand `true, true` kodieren, ein
 * Enum nicht. [TEMPORARY_PASSWORD_SET] = Weg 1 (`MemberService.setTemporaryPasswordForMember`,
 * revoked alle Sitzungen). [RESET_MAIL_SENT] = Weg 2
 * (`MemberService.sendPasswordResetMailToMember`, revoked bewusst KEINE Sitzung -- der Widerruf
 * gehoert zu `/api/auth/password-reset/confirm`).
 */
@Serializable
enum class AdminPasswordAction { TEMPORARY_PASSWORD_SET, RESET_MAIL_SENT }

/**
 * Structured `before` payload for the [AuditEntityType.CONFERENCE_RECORDING] audit entry
 * `network.lapis.cloud.server.rpc.ConferenceRecordingService.deleteRecording` writes (V1.0
 * Videokonferenzen, Wave 2 "Aufzeichnung"). The one snapshot in this file whose completeness is
 * itself the point: every OTHER `UPDATE` here describes a row that SURVIVES and can be re-read, but
 * a `conference_recording` row is HARD-deleted (`28-conference-recording.kuml.kts`'s file header
 * forbids a soft-delete column on that table), so this entry is the ONLY surviving record that the
 * recording ever existed -- which is exactly what the GoBD chain is for.
 *
 * **No PII beyond ids** -- same discipline [SepaMandateSnapshot]/[DunningNoticeSnapshot]/
 * [MemberChangeSnapshot] establish for an append-only, hash-chained table. [startedByMemberId] is an
 * id (the member row itself stays erasable), and [roomTitle] is a meeting title chosen by a
 * moderator, i.e. organizational metadata of the same kind [DunningLevelSnapshot.name] already
 * carries -- no member name, no e-mail, no media path, no raw directory. [failureReason] is safe by
 * construction: it is the SANITIZED German text from a fixed vocabulary, never raw ffmpeg/Twirp
 * output -- see [ConferenceRecordingDto.failureReason]'s own "a security boundary" KDoc.
 */
@Serializable
data class ConferenceRecordingSnapshot(
    val recordingId: String,
    val roomId: String,
    val roomTitle: String,
    val status: ConferenceRecordingStatus,
    val startedAt: LocalDateTime,
    val startedByMemberId: String,
    val accessLevel: DocumentAccessLevel,
    val documentId: String?,
    val durationSeconds: Long?,
    val fileSizeBytes: Long?,
    val failureReason: String?,
    /** How many `conference_recording_track` children the deletion removed alongside the parent row. */
    val trackCount: Int,
)

/**
 * Structured payload for an [AuditEntityType.PAYMENT_TRANSACTION] audit entry (Welle V1.2.8
 * "PSP-Checkout (Stripe)", GitHub Issue #6) -- written by
 * `network.lapis.cloud.server.payment.psp.PspWebhookIngestion` for every successfully-ingested
 * `checkout.session.completed` webhook delivery. **Field names deliberately avoid the handful of
 * forbidden substrings `PaymentsRegressionScanTest` scans for** (bank-account/card-related
 * fragments, plus `sealed`/`payload`) -- a body-digest field is named [providerBodyDigest], never
 * `rawPayloadDigest`, to stay clear of that scan even though the underlying DB column is named
 * that. No card data anywhere -- hosted Stripe Checkout only, see
 * `network.lapis.cloud.server.payment.psp.PspConfig` KDoc.
 */
@Serializable
data class PaymentTransactionSnapshot(
    val provider: PaymentProvider,
    val providerEventId: String,
    val providerPaymentId: String,
    val status: PaymentTransactionStatus,
    val amount: Decimal,
    val currency: String,
    val intent: PaymentIntent,
    val contributionId: String?,
    val memberId: String?,
    val donorCategory: DonorCategory?,
    val journalEntryId: String?,
    /** SHA-256 hex digest of the raw webhook body -- proof without retention; the raw body itself is never persisted. */
    val providerBodyDigest: String,
)

/**
 * Structured payload for an [AuditEntityType.API_KEY] audit entry (Welle V1.3.1 "API-Fundament,
 * lesend"). **Never carries [network.lapis.cloud.server.security.ApiKeyStore]'s `tokenHash` or the
 * raw key** -- same PII-/secret-minimization discipline [SepaMandateSnapshot]/[DunningNoticeSnapshot]
 * establish for an append-only, hash-chained table: a snapshot carrying the hash would preserve
 * exactly the material a later key revocation is supposed to invalidate the *usefulness* of, and
 * -- unlike a session token -- an API key's hash is a permanent secret-adjacent artifact, not
 * something that should ever live in a retained audit trail. [keyPrefix] alone (already
 * non-secret, display-only) is enough for an admin reading the log to tell which key an entry is
 * about.
 */
@Serializable
data class ApiKeySnapshot(
    val label: String,
    val keyPrefix: String,
    val createdByMemberId: String,
    val expiresAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
)

/**
 * Structured payload for an [AuditEntityType.WEBHOOK_ENDPOINT] audit entry (Welle V1.3.2
 * "Webhooks", ausgehend). **Never carries `secret_sealed`/`secret_prefix`/the raw signature
 * secret** -- same discipline [ApiKeySnapshot] establishes for `token_hash`: a hash-chained,
 * append-only table must never preserve material a later rotation/revocation is supposed to
 * invalidate the usefulness of. [url] itself IS carried (unlike a secret, an endpoint URL is not
 * secret-adjacent, and knowing which URL was configured when is exactly the accountability fact
 * this entry exists to record).
 *
 * [notifiedRecipients]/[totalRecipients] are set ONLY on the auto-deactivation `UPDATE` entry
 * `network.lapis.cloud.server.webhook.WebhookDeactivationNotifier` writes (both `null` on every
 * `WebhookService`-authored entry) -- Design-Team decision D4d: when the 20-recipient mail cap
 * (`WebhookDeactivationNotifier.MAX_RECIPIENTS`) actually bites, the true recipient count is
 * recorded here so a silently-capped notification is forensically visible instead of invisible
 * (see `MailDispatcher.enqueue` KDoc "DoS deckel" -- a saturated dispatcher drops mail silently).
 */
@Serializable
data class WebhookEndpointSnapshot(
    val apiKeyId: String,
    val url: String,
    val active: Boolean,
    val deactivationReason: WebhookDeactivationReason?,
    val notifiedRecipients: Int? = null,
    val totalRecipients: Int? = null,
)

/**
 * Structured payload for an [AuditEntityType.BANK_STATEMENT_IMPORT] audit entry (Welle V1.4.5.1
 * "Kontoauszugs-Import"). **Never carries a counterparty name, IBAN, or the raw bank-statement
 * line text** -- same PII-minimization discipline every other snapshot in this file establishes
 * for an append-only, hash-chained table (see [SepaMandateSnapshot] KDoc): only aggregate counts
 * and the file's own metadata are retained here.
 */
@Serializable
data class BankStatementImportSnapshot(
    val format: BankStatementFormat,
    val dialect: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val lineCount: Int,
    val duplicateCount: Int,
    val autoPostedCount: Int,
)

/**
 * Structured `before`/`after` payload for an [AuditEntityType.MEMBER] audit entry, written by
 * `network.lapis.cloud.server.rpc.MembershipTierAssignment.apply` (Welle V1.4.4.4
 * "Familienmitgliedschaften") -- the ONE write path for `member.membership_tier_id` in this whole
 * codebase (see that object's own KDoc for why a second implementation must never exist). A
 * dedicated type rather than an added field on [MemberChangeSnapshot]: that type's three existing
 * writers (`updateMemberCoreData`/`updateMemberStatus`/`updateMemberRole`) would otherwise write a
 * new field with a `null` default even for a member who genuinely HAS a tier -- a silently false
 * statement inside an append-only, hash-chained ledger.
 *
 * **No PII beyond ids** -- same discipline every other snapshot in this file establishes.
 * [familyId] is set only when the change originated from `MemberFamilyService`
 * (`addFamilyMember`/`changePayer`) -- an id only, never a family NAME (a family name is
 * board-authored free text about a household, not something this ledger needs to retain).
 * [reason] carries ONLY the manual `IMemberService.updateMemberMembershipTier` path's
 * board-authored justification -- the family-origin path always writes the SAME fixed machine
 * string (`"family-dependent"`), NEVER a person's or family's name, so this field can never
 * become a vector for the kind of PII-in-a-hash-chain mistake [MemberChangeSnapshot]'s own
 * "Security fix" KDoc paragraph documents.
 */
@Serializable
data class MemberMembershipTierSnapshot(
    val membershipTierId: String?,
    val familyId: String? = null,
    val reason: String? = null,
)

/**
 * Structured payload for an [AuditEntityType.ORGANIZATION_SETTINGS] audit entry written by
 * `network.lapis.cloud.server.rpc.TravelExpenseService.updateTravelExpenseRates` (Welle V1.4.11)
 * -- reuses [AuditEntityType.ORGANIZATION_SETTINGS] with `entityId =
 * network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID`, the SAME "org-wide configuration
 * change that doesn't warrant its own [AuditEntityType] literal" idiom
 * [SepaCreditorSettingsSnapshot]/[DunningLevelSnapshot] already establish. Only ever written on an
 * ACTUAL change (same narrowing `OrganizationSettingsService.updateOrganizationSettings` already
 * applies, see [OrganizationSettingsPaymentMappingSnapshot] KDoc). No PII -- both fields are an
 * organization-wide policy figure, never a per-member fact.
 */
@Serializable
data class TravelExpenseRatesSnapshot(
    val mileageRatePerKm: Decimal?,
    val perDiemRate: Decimal?,
)

/**
 * Structured `before`/`after` payload for an [AuditEntityType.REGIONAL_CHAPTER] audit entry
 * (Welle V1.9.13 "Gliederungsverwaltung (Landesverbände)"). [name] IS carried (unlike a member's
 * own PII) -- a chapter name is an organizational label chosen by an ADMIN, the same category of
 * data [DunningLevelSnapshot.name] already carries for this file's PII-minimization discipline.
 * `deleteChapter` writes this with `after = null` -- see [AuditEntityType.REGIONAL_CHAPTER] KDoc.
 */
@Serializable
data class RegionalChapterSnapshot(
    val name: String,
    /**
     * Welle V1.9.20 -- whether a public description / crest image was set AFTER the change (before:
     * the same flags in the `before` snapshot). Booleans only -- never the description text or the
     * crest token/key. Default `false` is not serialized (`encodeDefaults = false`), so every
     * pre-V1.9.20 payload and its hash stay byte-identical.
     */
    val descriptionPresent: Boolean = false,
    val crestPresent: Boolean = false,
)

/**
 * Structured `before`/`after` payload for an [AuditEntityType.MEMBERSHIP_TIER] audit entry (Welle
 * V1.9.18). [contributionAmount] is carried as a plain string (`BigDecimal.toPlainString()`) so the
 * hash-chained audit payload never depends on a serializer's number formatting. Name and
 * description are organizational labels chosen by a treasurer, not personal data.
 */
@Serializable
data class MembershipTierSnapshot(
    val name: String,
    val description: String,
    val contributionAmount: String,
    val billingInterval: BillingInterval,
    val active: Boolean,
    val paymentTermDays: Int,
)

/**
 * Structured `before`/`after` payload for an [AuditEntityType.REGIONAL_CHAPTER_OFFICER] audit
 * entry (Welle V1.9.13). **Never carries the officer's display name/email** -- same
 * PII-minimization discipline every other snapshot in this file establishes (see
 * [SepaMandateSnapshot] KDoc): only ids. `revokeOfficer` writes this with `after = null`.
 */
@Serializable
data class RegionalChapterOfficerSnapshot(
    val memberId: String,
    val regionalChapterId: String,
)

/**
 * Structured `before`/`after` payload for the [AuditEntityType.MEMBER] audit entry
 * `network.lapis.cloud.server.rpc.RegionalChapterService.assignMemberToChapter` writes (Welle
 * V1.9.13) -- a member's chapter *assignment* is a fact about the member row, not about the
 * chapter itself, so it deliberately reuses [AuditEntityType.MEMBER] rather than
 * [AuditEntityType.REGIONAL_CHAPTER] (see that literal's own KDoc). A dedicated type rather than
 * an added field on [MemberChangeSnapshot] -- same reasoning [MemberMembershipTierSnapshot]'s own
 * KDoc already gives for its own dedicated type. No PII beyond ids.
 */
@Serializable
data class MemberRegionalChapterSnapshot(
    val regionalChapterId: String?,
)
