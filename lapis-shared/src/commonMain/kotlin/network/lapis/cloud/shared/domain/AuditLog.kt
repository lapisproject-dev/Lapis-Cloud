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
 * why start/stop are audited.
 */
@Serializable
enum class AuditEntityType { JOURNAL_ENTRY, PARTY_DONATION_VERDICT, RESOLUTION, BOARD_MEMBERSHIP, CONFERENCE_RECORDING }

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
