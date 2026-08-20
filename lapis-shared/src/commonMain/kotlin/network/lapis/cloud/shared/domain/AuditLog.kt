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
 * Additively extensible (e.g. a future `VOID` alongside a Storno mechanism) -- same "cheap to
 * extend, expensive to reorder" note every other domain enum in this codebase carries; literal
 * order here is load-bearing (`AuditLogSchemaDriftTest` pins it against
 * `14-audit-log.kuml.kts`'s `auditAction` enum).
 */
@Serializable
enum class AuditAction { CREATE, UPDATE, POST }

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
 * [SepaDebitBatchSnapshot]). `DUNNING_NOTICE`/`PAYMENT_TRANSACTION` were deliberately NOT added ahead
 * of need -- this wave has no writer for either, same "no build-ahead-of-need" rule Welle V1.2.1
 * already applied to itself for these same two literals.
 * Additive append only -- never reorder existing literals,
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
}

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
)

/**
 * Bundles [network.lapis.cloud.shared.rpc.IAuditLogService.listAuditLog]'s optional filters into a
 * single parameter -- kilua-rpc's generated `bind` overloads only go up to 6 reified type
 * parameters (`PAR1..PAR6` + `RET`), and this method has 7 independent filters; a single query
 * object both fits that ceiling and reads better at call sites than seven positional/named
 * arguments. All fields default to "no filter"/the house-standard page size, matching every other
 * `activeOnly`/`includeResolved`-style optional-filter default in this codebase.
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

/** One Soll/Haben line within a [JournalEntrySnapshot] -- mirrors [PostingDto]'s own shape, id-only. */
@Serializable
data class PostingSnapshot(
    val ledgerAccountId: String,
    val side: PostingSide,
    val amount: Decimal,
    val sphere: GemeinnuetzigkeitSphere,
    val costCenterId: String?,
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
 * audit signal, deliberately narrowed to the three payment-account-mapping fields rather than the
 * full wholesale-replace diff of every `OrganizationSettingsInput` field -- see that method's own
 * KDoc for why. Carries LedgerAccount ids only (never account numbers/names), matching every other
 * snapshot's id-only PII-minimization convention (see [JournalEntrySnapshot] KDoc).
 */
@Serializable
data class OrganizationSettingsPaymentMappingSnapshot(
    val paymentBankAccountId: String?,
    val paymentFeeAccountId: String?,
    val contributionIncomeAccountId: String?,
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
