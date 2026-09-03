package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.dsgvo.DataSubject
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.dsgvo.TableErasureOutcome
import network.lapis.cloud.server.dsgvo.nowUtc
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureRequestDto
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.ExportManifestDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PublicRankingConsentDisclaimerDto
import network.lapis.cloud.shared.domain.PublicRankingConsentStateDto
import network.lapis.cloud.shared.domain.PublicRankingKind
import network.lapis.cloud.shared.domain.TableErasureOutcomeDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IDsgvoService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val outcomeListSerializer = ListSerializer(TableErasureOutcomeDto.serializer())

/**
 * See [IDsgvoService] KDoc for the overall design (registry-driven export/erasure, HTTP route
 * for the full export bundle). Every method here resolves [CurrentMember] exactly once via
 * [resolveCurrentMember] and checks subject-or-ADMIN / ADMIN-only exactly like the rest of the
 * `rpc` package (see [network.lapis.cloud.server.security.RequestContext] KDoc) — no bespoke
 * authorization logic.
 */
class DsgvoService(
    private val call: ApplicationCall,
    /**
     * V1.3.0 -- throttles [grantPublicRankingConsent], MEMBER-keyed (`"member:${current.memberId}"`,
     * same [requireWithinRate] idiom `SepaService.mandateWriteRateLimiter`/
     * `DunningService.issueRateLimiter` already establish for a comparable low-frequency
     * authenticated self-service write) -- never IP-keyed like the ACCOUNT-LESS public read path's
     * own limiters (`SocialPublicRoutes`/`PublicTransparencyRoutes`). Constructor default exists for
     * tests only, same "production always passes it explicitly via `Application.module`'s
     * `registerService`" contract every other rate-limited service constructor in this codebase
     * follows.
     */
    private val consentRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
    /**
     * Security-Fix (Review): a SEPARATE limiter instance for [revokePublicRankingConsent] --
     * deliberately never [consentRateLimiter] itself. Art. 7(3) DSGVO requires that withdrawing
     * consent be no harder than giving it; sharing one budget between grant and revoke meant a
     * member who exhausted [consentRateLimiter] via repeated GRANT calls (e.g. toggling the
     * `/dsgvo-rights` switch) could then have their very next REVOKE rejected with
     * `ConflictException` -- their name staying publicly visible for up to the window length even
     * though the revoke itself was the very first one they attempted. More generous than
     * [consentRateLimiter] on purpose.
     *
     * **Correction (Review, Runde 2)**: [PublicRankingConsentStore.revoke] is idempotent ONLY when
     * there is no current GRANTED row -- that case is a true no-op write. Whenever a current
     * GRANTED row DOES exist (the normal "member actually revokes" case), [revoke] writes a row
     * exactly like [PublicRankingConsentStore.grant] does. A member alternating grant/revoke
     * therefore appends roughly TWO rows per cycle, bounded only by
     * `min(`[consentRateLimiter]`, `[consentRevokeRateLimiter]`)` combined -- i.e. this limiter
     * does share fully in grant's unbounded-row-growth concern for `public_ranking_consent_event`,
     * it is not exempt from it. The export-side bound that actually protects against this now
     * lives in `PublicRankingConsentPersonalData.export`'s `MAX_EXPORTED_EVENTS` cap -- see that
     * KDoc.
     */
    private val consentRevokeRateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
) : IDsgvoService {
    override suspend fun exportManifest(memberId: String): ExportManifestDto {
        val current = resolveCurrentMember(call)
        val subjectId = memberId.toDsgvoUuid()
        requireSelfOrAdmin(current = current, subjectId = subjectId)
        return transaction {
            val sectionCounts =
                PersonalDataRegistry.contributors
                    .filter { DsgvoSubjectKind.MEMBER in it.handledSubjects }
                    .associate { contributor ->
                        contributor.sectionKey to contributor.export(DataSubject.Member(subjectId)).elementCount()
                    }
            writeAuditLog(
                actor = current,
                action = DsgvoAuditAction.EXPORT,
                subjectMemberId = subjectId,
                requestId = null,
                outcome = emptyList(),
                legalBasis = "Art. 15/20 DSGVO",
            )
            ExportManifestDto(
                subjectMemberId = subjectId.toString(),
                generatedAt = nowUtc(),
                sectionCounts = sectionCounts,
            )
        }
    }

    override suspend fun requestErasure(
        subjectMemberId: String,
        reason: String,
        mode: ErasureMode,
    ): ErasureRequestDto {
        val current = resolveCurrentMember(call)
        val subjectId = subjectMemberId.toDsgvoUuid()
        requireSelfOrAdmin(current = current, subjectId = subjectId)
        return transaction {
            val id = Uuid.random()
            ErasureRequestTable.insert {
                it[ErasureRequestTable.id] = id
                it[ErasureRequestTable.subjectMemberId] = subjectId
                it[requestedAt] = nowUtc()
                it[requestedBy] = current.memberId
                it[ErasureRequestTable.reason] = reason
                it[ErasureRequestTable.mode] = mode
                it[status] = ErasureStatus.REQUESTED
                it[legalHold] = false
            }
            writeAuditLog(
                actor = current,
                action = DsgvoAuditAction.ERASURE_REQUESTED,
                subjectMemberId = subjectId,
                requestId = id,
                outcome = emptyList(),
                legalBasis = "Art. 17 DSGVO",
            )
            loadErasureRequest(id)
        }
    }

    override suspend fun listErasureRequests(status: ErasureStatus?): List<ErasureRequestDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val baseQuery = erasureRequestJoin().selectAll()
            val rows = if (status != null) baseQuery.where { ErasureRequestTable.status eq status } else baseQuery
            rows.map { it.toErasureRequestDto() }
        }
    }

    override suspend fun decideErasure(
        requestId: String,
        approve: Boolean,
        note: String?,
    ): ErasureRequestDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = requestId.toDsgvoUuid()
        return transaction {
            val row =
                ErasureRequestTable.selectAll().where { ErasureRequestTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("ErasureRequest $requestId not found")
            if (row[ErasureRequestTable.status] != ErasureStatus.REQUESTED) {
                throw ConflictException("ErasureRequest $requestId is not in REQUESTED state")
            }
            val newStatus = if (approve) ErasureStatus.APPROVED else ErasureStatus.REJECTED
            ErasureRequestTable.update({ ErasureRequestTable.id eq id }) {
                it[status] = newStatus
                it[decidedBy] = current.memberId
                it[decidedAt] = nowUtc()
                it[decisionNote] = note
            }
            val action = if (approve) DsgvoAuditAction.ERASURE_APPROVED else DsgvoAuditAction.ERASURE_REJECTED
            writeAuditLog(
                actor = current,
                action = action,
                subjectMemberId = row[ErasureRequestTable.subjectMemberId],
                requestId = id,
                outcome = emptyList(),
                legalBasis = "Art. 17 DSGVO",
            )
            loadErasureRequest(id)
        }
    }

    override suspend fun executeErasure(requestId: String): ErasureRequestDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val id = requestId.toDsgvoUuid()
        return transaction {
            val row =
                ErasureRequestTable.selectAll().where { ErasureRequestTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("ErasureRequest $requestId not found")
            if (row[ErasureRequestTable.status] != ErasureStatus.APPROVED) {
                throw ConflictException("ErasureRequest $requestId is not APPROVED")
            }
            val subjectId = row[ErasureRequestTable.subjectMemberId]
            val mode = row[ErasureRequestTable.mode]
            val outcomeDtos =
                PersonalDataRegistry.contributors
                    .filter { DsgvoSubjectKind.MEMBER in it.handledSubjects }
                    .flatMap { it.erase(subject = DataSubject.Member(subjectId), mode = mode) }
                    .map { it.toDto() }
            ErasureRequestTable.update({ ErasureRequestTable.id eq id }) {
                it[status] = ErasureStatus.COMPLETED
                it[executedAt] = nowUtc()
                it[outcomeSummary] = Json.encodeToString(outcomeListSerializer, outcomeDtos)
            }
            writeAuditLog(
                actor = current,
                action = DsgvoAuditAction.ERASURE_EXECUTED,
                subjectMemberId = subjectId,
                requestId = id,
                outcome = outcomeDtos,
                legalBasis = "Art. 17 DSGVO",
            )
            loadErasureRequest(id)
        }
    }

    override suspend fun listAuditLog(
        subjectMemberId: String?,
        subjectKind: DsgvoSubjectKind?,
    ): List<DsgvoAuditLogEntryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            // Condition built up-front, not a `.where {}.andWhere {}` chain -- same idiom
            // FederationService.listFederationRelationships establishes, this codebase does not use
            // Exposed's separate `andWhere` extension anywhere.
            val subjectIdCondition = subjectMemberId?.let { DsgvoAuditLogTable.subjectMemberId eq it.toDsgvoUuid() }
            val subjectKindCondition = subjectKind?.let { DsgvoAuditLogTable.subjectKind eq it }
            val condition =
                when {
                    subjectIdCondition != null && subjectKindCondition != null -> subjectIdCondition and subjectKindCondition
                    subjectIdCondition != null -> subjectIdCondition
                    subjectKindCondition != null -> subjectKindCondition
                    else -> null
                }
            val query = if (condition != null) DsgvoAuditLogTable.selectAll().where { condition } else DsgvoAuditLogTable.selectAll()
            query.map { it.toAuditLogEntryDto() }
        }
    }

    // ============================================================================================
    // V1.3.0 "Öffentliche Transparenz-Startseite" -- opt-in consent for the two public leaderboards.
    // See IDsgvoService KDoc on each method for the contract.
    // ============================================================================================

    override suspend fun getPublicRankingConsents(): List<PublicRankingConsentStateDto> {
        val current = resolveCurrentMember(call)
        return transaction { PublicRankingConsentStore.currentState(memberId = current.memberId) }
    }

    override suspend fun getPublicRankingConsentDisclaimer(kind: PublicRankingKind): PublicRankingConsentDisclaimerDto {
        // No auth gate -- reading the disclosure text itself is not a personal-data operation, and
        // the member must be able to see it BEFORE deciding whether to consent. requireAuth on the
        // route (`/dsgvo-rights`) already gates the whole screen this is called from.
        resolveCurrentMember(call)
        val disclaimer = PublicRankingConsentDisclaimer.of(kind)
        return PublicRankingConsentDisclaimerDto(
            kind = disclaimer.kind,
            version = disclaimer.version,
            headline = disclaimer.headline,
            keyPoints = disclaimer.keyPoints,
            text = disclaimer.text,
            sha256 = disclaimer.sha256,
        )
    }

    override suspend fun grantPublicRankingConsent(
        kind: PublicRankingKind,
        version: String,
        sha256: String,
    ): PublicRankingConsentStateDto {
        val current = resolveCurrentMember(call)
        requireOrganizationMember(current)
        requireWithinConsentRate(current)
        if (!PublicRankingConsentDisclaimer.of(kind).matches(version = version, sha256 = sha256)) {
            throw BadRequestException("Einwilligungstext stimmt nicht mit der aktuellen Fassung überein -- bitte neu laden.")
        }
        return transaction {
            lockMemberRow(current.memberId)
            PublicRankingConsentStore.grant(memberId = current.memberId, kind = kind, version = version, sha256 = sha256, now = nowUtc())
            PublicRankingConsentStore.currentState(memberId = current.memberId).single { it.kind == kind }
        }
    }

    override suspend fun revokePublicRankingConsent(kind: PublicRankingKind): PublicRankingConsentStateDto {
        val current = resolveCurrentMember(call)
        requireOrganizationMember(current)
        requireWithinConsentRevokeRate(current)
        return transaction {
            lockMemberRow(current.memberId)
            PublicRankingConsentStore.revoke(memberId = current.memberId, kind = kind, now = nowUtc())
            PublicRankingConsentStore.currentState(memberId = current.memberId).single { it.kind == kind }
        }
    }

    /**
     * A GUEST has no LTR account here (it lives on their federated home server); a FRIEND's
     * `display_name` is unverified. Neither belongs in a public leaderboard of THIS organization --
     * `MemberStatusSets.ORGANIZATION_MEMBER` is (today) exactly `{ACTIVE}`, so this also excludes
     * WITHDRAWN/REJECTED/DONOR/DECEASED without needing a second, hand-maintained status list.
     */
    private fun requireOrganizationMember(current: CurrentMember) {
        if (current.status !in MemberStatusSets.ORGANIZATION_MEMBER) {
            throw ForbiddenException("Nur aktive Mitglieder koennen in eine oeffentliche Rangliste einwilligen")
        }
    }

    private fun requireWithinConsentRate(current: CurrentMember) {
        if (!consentRateLimiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    /** Uses [consentRevokeRateLimiter] -- a budget separate from [requireWithinConsentRate]'s, see that field's KDoc. */
    private fun requireWithinConsentRevokeRate(current: CurrentMember) {
        if (!consentRevokeRateLimiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte spaeter erneut versuchen.")
        }
    }

    /**
     * `SELECT ... FOR UPDATE` on [MemberTable] -- same [network.lapis.cloud.server.economy
     * .LtrBalanceProvider.lockForDebit] idiom, reused here as the per-member mutex
     * [PublicRankingConsentStore]'s own KDoc ("Concurrency") requires its caller to hold BEFORE
     * calling [PublicRankingConsentStore.grant]/[PublicRankingConsentStore.revoke].
     */
    private fun lockMemberRow(memberId: Uuid) {
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .forUpdate()
            .singleOrNull()
            ?: error("Member $memberId not found while locking for a public-ranking-consent write")
    }

    private fun requireSelfOrAdmin(
        current: CurrentMember,
        subjectId: Uuid,
    ) {
        if (current.memberId != subjectId && current.role != AccountRole.ADMIN) {
            throw ForbiddenException("Nur das betroffene Mitglied selbst oder ADMIN duerfen diese DSGVO-Operation ausfuehren")
        }
    }

    private fun writeAuditLog(
        actor: CurrentMember,
        action: DsgvoAuditAction,
        subjectMemberId: Uuid,
        requestId: Uuid?,
        outcome: List<TableErasureOutcomeDto>,
        legalBasis: String?,
        // Every call site in THIS class describes an actual member -- CrmService/CrmRoutes write
        // their own CRM_CONTACT-kind rows directly (see those classes' own KDoc), this default
        // exists purely so DsgvoService's five pre-existing call sites stay unchanged.
        subjectKind: DsgvoSubjectKind = DsgvoSubjectKind.MEMBER,
    ) {
        DsgvoAuditLogTable.insert {
            it[id] = Uuid.random()
            it[occurredAt] = nowUtc()
            it[actorMemberId] = actor.memberId
            it[actorRole] = actor.role
            it[DsgvoAuditLogTable.action] = action
            it[DsgvoAuditLogTable.subjectMemberId] = subjectMemberId
            it[DsgvoAuditLogTable.requestId] = requestId
            it[outcomeSummary] = if (outcome.isEmpty()) null else Json.encodeToString(outcomeListSerializer, outcome)
            it[DsgvoAuditLogTable.legalBasis] = legalBasis
            it[DsgvoAuditLogTable.subjectKind] = subjectKind
        }
    }

    private fun loadErasureRequest(id: Uuid): ErasureRequestDto =
        erasureRequestJoin()
            .selectAll()
            .where { ErasureRequestTable.id eq id }
            .single()
            .toErasureRequestDto()

    /**
     * Explicit join, not `ErasureRequestTable innerJoin MemberTable`: [ErasureRequestTable] has
     * three separate FKs to [MemberTable] (`subject_member_id`/`requested_by`/`decided_by`), so
     * Exposed's implicit FK-based join resolution can't tell which path to use and throws
     * `IllegalStateException: ... multiple primary key <-> foreign key references` — same
     * disambiguation issue `ContributionService.contributionJoin` documents. `subjectMemberId` is
     * the one [toErasureRequestDto] actually needs (`subjectDisplayName`).
     */
    private fun erasureRequestJoin() =
        ErasureRequestTable.join(MemberTable, JoinType.INNER, ErasureRequestTable.subjectMemberId, MemberTable.id)
}

private fun String.toDsgvoUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

private fun TableErasureOutcome.toDto() =
    TableErasureOutcomeDto(
        table = table,
        rowsAnonymized = rowsAnonymized,
        rowsDeleted = rowsDeleted,
        rowsRetained = rowsRetained,
        retentionReason = retentionReason,
    )

private fun JsonElement.elementCount(): Int =
    when (this) {
        is JsonArray -> size
        is JsonObject -> if (isEmpty()) 0 else 1
        else -> 1
    }

private fun ResultRow.toErasureRequestDto(): ErasureRequestDto =
    ErasureRequestDto(
        id = this[ErasureRequestTable.id].toString(),
        subjectMemberId = this[ErasureRequestTable.subjectMemberId].toString(),
        subjectDisplayName = this[MemberTable.displayName],
        requestedAt = this[ErasureRequestTable.requestedAt],
        requestedBy = this[ErasureRequestTable.requestedBy].toString(),
        reason = this[ErasureRequestTable.reason],
        mode = this[ErasureRequestTable.mode],
        status = this[ErasureRequestTable.status],
        decidedBy = this[ErasureRequestTable.decidedBy]?.toString(),
        decidedAt = this[ErasureRequestTable.decidedAt],
        decisionNote = this[ErasureRequestTable.decisionNote],
        executedAt = this[ErasureRequestTable.executedAt],
        legalHold = this[ErasureRequestTable.legalHold],
        outcome = this[ErasureRequestTable.outcomeSummary]?.let { Json.decodeFromString(outcomeListSerializer, it) } ?: emptyList(),
    )

private fun ResultRow.toAuditLogEntryDto(): DsgvoAuditLogEntryDto =
    DsgvoAuditLogEntryDto(
        id = this[DsgvoAuditLogTable.id].toString(),
        occurredAt = this[DsgvoAuditLogTable.occurredAt],
        actorMemberId = this[DsgvoAuditLogTable.actorMemberId]?.toString(),
        actorRole = this[DsgvoAuditLogTable.actorRole],
        action = this[DsgvoAuditLogTable.action],
        subjectMemberId = this[DsgvoAuditLogTable.subjectMemberId].toString(),
        requestId = this[DsgvoAuditLogTable.requestId]?.toString(),
        outcome = this[DsgvoAuditLogTable.outcomeSummary]?.let { Json.decodeFromString(outcomeListSerializer, it) } ?: emptyList(),
        legalBasis = this[DsgvoAuditLogTable.legalBasis],
        subjectKind = this[DsgvoAuditLogTable.subjectKind],
    )
