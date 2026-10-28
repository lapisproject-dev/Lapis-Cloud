package network.lapis.cloud.server.rpc
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import network.lapis.cloud.server.conference.ConferenceConnectedMembers
import network.lapis.cloud.server.conference.DisabledConferenceConnectedMembers
import network.lapis.cloud.server.conference.SecretBallotStreamGuard
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.containsLikePattern
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.events.EventOnlineServices
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.governance.meetingevent.MeetingEventLinkOperations
import network.lapis.cloud.server.governance.meetingevent.MeetingEventLinkStore
import network.lapis.cloud.server.governance.session.AttendanceWriter
import network.lapis.cloud.server.governance.session.MeetingSessionAudit
import network.lapis.cloud.server.governance.session.MeetingSessionMotionOperations
import network.lapis.cloud.server.governance.session.MeetingSessionOperations
import network.lapis.cloud.server.governance.session.ProtocolApprovalItems
import network.lapis.cloud.server.routes.PUBLIC_API_MOTION_STATUSES
import network.lapis.cloud.server.rules.RuleReferences
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.server.security.canSubmitMotion
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.tender.isTenderAwardMotion
import network.lapis.cloud.server.tender.tenderConflictedMemberIdsForMotion
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.webhook.WebhookEventPublisher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgendaItemDto
import network.lapis.cloud.shared.domain.AgendaItemInput
import network.lapis.cloud.shared.domain.AttendanceDto
import network.lapis.cloud.shared.domain.AttendanceInput
import network.lapis.cloud.shared.domain.CommitteeAutoEndEntryDto
import network.lapis.cloud.shared.domain.CommitteeAutoEndNoticeDto
import network.lapis.cloud.shared.domain.CommitteeCandidateSearch
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeElectionSeatChangeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeModeChangeImpactDto
import network.lapis.cloud.shared.domain.CommitteeOpenSeatDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeRulePreviewDto
import network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason
import network.lapis.cloud.shared.domain.CommitteeSeatChangeKind
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceMeetingStateAnswer
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.LinkedEventRef
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.MeetingDetailDto
import network.lapis.cloud.shared.domain.MeetingDto
import network.lapis.cloud.shared.domain.MeetingInput
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberPickDto
import network.lapis.cloud.shared.domain.MemberPickPageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.MotionInput
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionReviewDecision
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SessionAttendanceInput
import network.lapis.cloud.shared.domain.VacantMandatoryRoleDto
import network.lapis.cloud.shared.domain.VoteBallotDto
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteOptionDto
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.domain.WebhookEventType
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.SearchRateLimitedException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** V1.9.102 -- look-back window (days) and row cap of [GovernanceService.getCommitteeAutoEndNotice]. */
private const val AUTO_END_NOTICE_DAYS = 30
private const val AUTO_END_NOTICE_LIMIT = 50

/**
 * V1.9.101 -- process-wide limiter of the committee-candidate search (60 requests/minute per acting member).
 * It MUST be a top-level `val`: `GovernanceService` is constructed anew for every RPC call, so a limiter held
 * per service instance would never accumulate anything.
 */
private val COMMITTEE_CANDIDATE_SEARCH_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)

/**
 * V1.9.112 -- process-wide limiters (top-level for the same reason as above): the rule preview (board only, 60/minute and member) and
 * the eligible count of a derived committee (any member, 120/minute and member -- one call per committee card). Both are checked AFTER
 * the permission check, so an unauthorised caller never burns budget.
 */
private val COMMITTEE_RULE_PREVIEW_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
private val COMMITTEE_ELIGIBLE_COUNT_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 120, window = 1.minutes)

/** V1.9.227 -- the five Sitzung <-> Veranstaltung link operations share one per-actor budget. */
private val MEETING_EVENT_LINK_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

/** Meritokratische Voteen (V0.2.3): server-side floor, never trusted from a client. */
private val MIN_STAKE_LTR: BigDecimal = BigDecimal("0.01")
private val ZERO_LTR: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * DoS caps on [network.lapis.cloud.shared.domain.VoteOpenInput.optionLabels] — well above
 * any realistic Sachentscheidung's option count/label length, but bounded so a careless or
 * malicious caller cannot make `openVote` insert an unbounded number of
 * [network.lapis.cloud.server.db.generated.VoteOptionTable] rows or exceed that table's
 * `label VARCHAR(200)` column with a confusing DB-level error instead of a clean 409.
 */
private const val MAX_ABSTIMMUNG_OPTIONS = 50
private const val MAX_OPTION_LABEL_LENGTH = 200

/**
 * Committee and meeting management (V0.2.1). Reads (`listCommittees`/`getMeetingDetail`/
 * `listResolutions`/etc.) only require a resolvable [network.lapis.cloud.server.security
 * .CurrentMember] (any authenticated member) — see [IGovernanceService] KDoc for why this is a
 * deliberate simplification versus [network.lapis.cloud.shared.domain.DocumentAccessLevel]'s
 * tiered model. Writes that manage a specific Committee's meetings/agenda/attendance/resolutions
 * require that Committee's leadership role or global BOARD/ADMIN, checked via
 * [network.lapis.cloud.server.security.canRecordForMeeting] (see `GovernanceAuthorization.kt`).
 *
 * Member display names for the multiple-FK-to-member tables ([MeetingTable] has three: called
 * by/chair/minute-taker; [AttendanceTable] has two: attendee/proxy) are resolved via
 * [memberDisplayName], a small follow-up lookup per id, rather than aliased multi-joins — kept
 * simple and correct rather than optimized, consistent with this codebase's "simple-transaction"
 * style (see [ContributionService]/[MailingService]). Single-member-FK joins ([CommitteeTable] via
 * [MeetingTable]/[CommitteeMembershipTable] via `member`) still use a plain `innerJoin`.
 *
 * Meritokratische Voteen (V0.2.3): [ltrBalanceProvider] defaults to
 * [LedgerBackedLtrBalanceProvider] (V0.6.1 swapped in the real ledger-backed implementation
 * here — the only call-site change the seam was designed for, see [LtrBalanceProvider] KDoc) so
 * `Application.module`'s single-arg `GovernanceService(call)` construction is unaffected. See
 * [LtrBalanceProvider] KDoc for the read-only boundary this service relies on.
 */
class GovernanceService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
    private val candidateSearchRateLimiter: FederationInboxRateLimiter = COMMITTEE_CANDIDATE_SEARCH_RATE_LIMITER,
    private val rulePreviewRateLimiter: FederationInboxRateLimiter = COMMITTEE_RULE_PREVIEW_RATE_LIMITER,
    private val eligibleCountRateLimiter: FederationInboxRateLimiter = COMMITTEE_ELIGIBLE_COUNT_RATE_LIMITER,
    // V1.9.227 -- only needed to know whether an announced online Sitzung may use a Lapis video conference; the default keeps older constructions working.
    eventOnline: EventOnlineServices = EventOnlineServices.DISABLED,
    meetingEventLinkRateLimiter: FederationInboxRateLimiter = MEETING_EVENT_LINK_RATE_LIMITER,
    // V1.9.236 -- who is connected to a room (LiveKit, cached); the process-wide instance is built in Application.kt. The default shows nobody connected.
    connectedMembers: ConferenceConnectedMembers = DisabledConferenceConnectedMembers,
    // V1.9.238 -- needed to open a SECRET vote of a Sitzung; null = secret votes are refused (fail closed), never silently allowed.
    secretBallotStreamGuard: SecretBallotStreamGuard? = null,
) : IGovernanceService {
    private val meetingEventLinks =
        MeetingEventLinkOperations(conferencesEnabled = { eventOnline.conferencesEnabled }, rateLimiter = meetingEventLinkRateLimiter)
    private val meetingSessions =
        MeetingSessionOperations(conferencesEnabled = { eventOnline.conferencesEnabled }, connectedMembers = connectedMembers)
    private val meetingSessionMotions = MeetingSessionMotionOperations(base = meetingSessions, streamGuard = secretBallotStreamGuard)

    override suspend fun listCommittees(activeOnly: Boolean): List<CommitteeDto> {
        val current = resolveCurrentMember(call)
        // V1.3.1 -- delegates to GovernanceReads (see that object's KDoc); byte-identical behavior,
        // limit/offset both stay at their "unbounded"/`0` defaults for this RPC call site.
        // V1.9.112: the rule text is only sent to BOARD/ADMIN.
        return transaction { GovernanceReads.listCommittees(activeOnly = activeOnly, revealRule = current.role in BOARD_ROLES) }
    }

    override suspend fun createCommittee(input: CommitteeInput): CommitteeDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        // V1.9.112: a rule-based committee is created with its rule (strict parse, canonical form stored); a manual one never carries a rule.
        val rule: MemberRule? =
            when (input.membershipMode) {
                CommitteeMembershipMode.MANUAL -> {
                    if (input.ruleJson != null) throw BadRequestException("Ein manuelles Gremium hat keine Regel.")
                    null
                }
                CommitteeMembershipMode.RULE -> {
                    CommitteeRuleOps.requireTypeAllowsRule(input.type)
                    CommitteeRuleOps.parseOrBadRequest(input.ruleJson ?: throw BadRequestException("Ein Regelgremium braucht eine Regel."))
                }
            }
        val now = nowLocalDateTime()
        return transaction {
            if (rule != null) RuleReferences.lockAndValidate(rule = rule, context = RuleContext.COMMITTEE, selfCommitteeId = null)
            val id = Uuid.random()
            CommitteeTable.insert {
                it[CommitteeTable.id] = id
                it[name] = input.name
                it[type] = input.type
                it[description] = input.description
                it[active] = input.active
                it[quorumPercent] = input.quorumPercent
                it[createdAt] = now
                it[membershipMode] = input.membershipMode
                it[ruleJson] = rule?.let { r -> MemberRuleCodec.encode(r) }
            }
            // Welle V1.3.2 "Webhooks" (ausgehend) -- Thin event, D8-konform: GET /api/v1/committees/{id}
            // liefert dieses Gremium unmittelbar danach aus (kein Sichtbarkeitsfilter, siehe
            // PublicApiRoutes' /committees/{id}-Handler).
            WebhookEventPublisher.publish(eventType = WebhookEventType.COMMITTEE_CREATED, entityId = id, occurredAt = now)
            // V1.9.112: only the rule-based case is audited (manual committees never were); the audit write is the last locking operation.
            if (rule != null) CommitteeRuleOps.auditCreatedRuleCommittee(current = current, committeeId = id, rule = rule, now = now)
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq id }
                .single()
                .toCommitteeDto(revealRule = true)
        }
    }

    /**
     * V1.9.112: the membership mode and rule are NOT changed here, whatever [CommitteeInput.membershipMode]/[CommitteeInput.ruleJson]
     * contain -- they change only through [setCommitteeMembershipRule], which carries the preconditions, the seat handling and the audit.
     */
    override suspend fun updateCommittee(
        id: String,
        input: CommitteeInput,
    ): CommitteeDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val committeeId = id.toCommitteeUuid()
        return transaction {
            val existing =
                CommitteeTable
                    .selectAll()
                    .where { CommitteeTable.id eq committeeId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Committee $id not found")
            // a rule-based committee can never become the General Assembly or the executive board (the database CHECK would refuse
            // it too, but as a raw SQL error)
            if (existing[CommitteeTable.membershipMode] == CommitteeMembershipMode.RULE &&
                input.type in setOf(CommitteeType.GENERAL_ASSEMBLY, CommitteeType.EXECUTIVE_BOARD)
            ) {
                throw ConflictException("Ein Regelgremium kann nicht zu diesem Gremientyp werden.")
            }
            CommitteeTable.update({ CommitteeTable.id eq committeeId }) {
                it[name] = input.name
                it[type] = input.type
                it[description] = input.description
                it[active] = input.active
                it[quorumPercent] = input.quorumPercent
            }
            WebhookEventPublisher.publish(
                eventType = WebhookEventType.COMMITTEE_UPDATED,
                entityId = committeeId,
                occurredAt = nowLocalDateTime(),
            )
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq committeeId }
                .single()
                .toCommitteeDto(revealRule = true)
        }
    }

    override suspend fun listCommitteeMembers(
        committeeId: String,
        activeOnly: Boolean,
    ): List<CommitteeMembershipDto> {
        val current = resolveCurrentMember(call)
        val gId = committeeId.toCommitteeUuid()
        val revealAutoEndReason = current.role in BOARD_ROLES
        return transaction {
            // V1.9.112: a rule-based committee has no seat rows and nobody is enumerated -- its members are whoever the rule selects.
            val mode = CommitteeTable.select(CommitteeTable.membershipMode).where { CommitteeTable.id eq gId }.singleOrNull()
            if (mode?.get(CommitteeTable.membershipMode) == CommitteeMembershipMode.RULE) return@transaction emptyList()
            val conditions = mutableListOf<Op<Boolean>>(CommitteeMembershipTable.committeeId eq gId)
            if (activeOnly) conditions += CommitteeMembershipTable.until.isNull()
            (CommitteeMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .map { it.toCommitteeMembershipDto(revealAutoEndReason = revealAutoEndReason) }
        }
    }

    /**
     * V1.9.102 -- see [IGovernanceService.getCommitteeAutoEndNotice]. All three lists are computed per call, nothing is stored. "Today" is
     * the organisation-time-zone date. Vacancy is evaluated for TOMORROW: `until` is inclusive, so a chair whose seat ended today still
     * counts today but already no longer from tomorrow on -- the board must see the gap on the day it arises.
     */
    override suspend fun getCommitteeAutoEndNotice(): CommitteeAutoEndNoticeDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        return transaction {
            val today = OrganizationTimeZone.dateOf(DbClock.nowLocalDateTime())
            val windowStart = today.minus(AUTO_END_NOTICE_DAYS, DateTimeUnit.DAY)
            val recent =
                (CommitteeMembershipTable innerJoin CommitteeTable)
                    .join(MemberTable, JoinType.INNER, CommitteeMembershipTable.memberId, MemberTable.id)
                    .selectAll()
                    .where {
                        CommitteeMembershipTable.autoEndReason.isNotNull() and
                            // V1.9.112: a seat ended by the switch to rule mode is a deliberate board decision, not a surprise to list
                            (CommitteeMembershipTable.autoEndReason neq CommitteeSeatAutoEndReason.RULE_MODE) and
                            (CommitteeMembershipTable.until greaterEq windowStart)
                    }.orderBy(CommitteeMembershipTable.until to SortOrder.DESC, CommitteeMembershipTable.id to SortOrder.ASC)
                    .limit(AUTO_END_NOTICE_LIMIT)
                    .map { row ->
                        CommitteeAutoEndEntryDto(
                            committeeId = row[CommitteeTable.id].toString(),
                            committeeName = row[CommitteeTable.name],
                            membershipId = row[CommitteeMembershipTable.id].toString(),
                            memberId = row[CommitteeMembershipTable.memberId].toString(),
                            memberDisplayName = row[MemberTable.displayName],
                            role = row[CommitteeMembershipTable.role],
                            since = row[CommitteeMembershipTable.since],
                            until = checkNotNull(row[CommitteeMembershipTable.until]),
                            reason = checkNotNull(row[CommitteeMembershipTable.autoEndReason]),
                        )
                    }
            val chaired = CommitteeSeatLifecycle.chairedExecutiveBoardIds(today.plus(1, DateTimeUnit.DAY))
            val vacant =
                CommitteeTable
                    .selectAll()
                    .where { (CommitteeTable.type eq CommitteeType.EXECUTIVE_BOARD) and (CommitteeTable.active eq true) }
                    .orderBy(CommitteeTable.name to SortOrder.ASC)
                    .filter { it[CommitteeTable.id] !in chaired }
                    .map {
                        VacantMandatoryRoleDto(
                            committeeId = it[CommitteeTable.id].toString(),
                            committeeName = it[CommitteeTable.name],
                            role = CommitteeRole.CHAIR,
                        )
                    }
            val openWithoutMembership =
                (CommitteeMembershipTable innerJoin CommitteeTable)
                    .join(MemberTable, JoinType.INNER, CommitteeMembershipTable.memberId, MemberTable.id)
                    .selectAll()
                    .where {
                        // a seat the hook has just ended (auto_end_reason set, until = today) is not "open legacy data"
                        CommitteeMembershipTable.autoEndReason.isNull() and
                            (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq today)) and
                            (MemberTable.status inList MemberStatusSets.COMMITTEE_SEAT_ENDING.toList())
                    }.orderBy(CommitteeMembershipTable.since to SortOrder.ASC, CommitteeMembershipTable.id to SortOrder.ASC)
                    .limit(AUTO_END_NOTICE_LIMIT)
                    .map { row ->
                        CommitteeOpenSeatDto(
                            committeeId = row[CommitteeTable.id].toString(),
                            committeeName = row[CommitteeTable.name],
                            membershipId = row[CommitteeMembershipTable.id].toString(),
                            memberId = row[CommitteeMembershipTable.memberId].toString(),
                            memberDisplayName = row[MemberTable.displayName],
                            role = row[CommitteeMembershipTable.role],
                            since = row[CommitteeMembershipTable.since],
                            until = row[CommitteeMembershipTable.until],
                            memberStatus = row[MemberTable.status],
                        )
                    }
            CommitteeAutoEndNoticeDto(
                recentAutoEnds = recent,
                vacantMandatoryRoles = vacant,
                openSeatsWithoutMembership = openWithoutMembership,
                electionSeatChanges = loadElectionSeatChanges(windowStart = windowStart),
            )
        }
    }

    override suspend fun countGeneralAssemblyEligible(committeeId: String): Int = countCommitteeEligible(committeeId)

    override suspend fun countCommitteeEligible(committeeId: String): Int {
        val current = resolveCurrentMember(call)
        val gId = committeeId.toCommitteeUuid()
        if (!eligibleCountRateLimiter.checkAndRecord("actor:${current.memberId}")) throw SearchRateLimitedException()
        return transaction {
            val row =
                CommitteeTable.selectAll().where { CommitteeTable.id eq gId }.singleOrNull()
                    ?: throw NotFoundException("Committee $gId not found")
            if (row[CommitteeTable.type] != CommitteeType.GENERAL_ASSEMBLY && !isRuleCommittee(row)) {
                throw BadRequestException("Committee $gId has no derived membership")
            }
            // Same evaluator as eligibleMemberIds/computeQuorum -- never a copy (see generalAssemblyCondition / eligibleIdsForRule KDoc).
            countEligible(committeeRow = row)
        }
    }

    override suspend fun setCommitteeMembershipRule(
        committeeId: String,
        mode: CommitteeMembershipMode,
        ruleJson: String?,
    ): CommitteeDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val gId = committeeId.toCommitteeUuid()
        val rule: MemberRule? =
            when (mode) {
                CommitteeMembershipMode.MANUAL -> {
                    if (ruleJson != null) throw BadRequestException("Ein manuelles Gremium hat keine Regel.")
                    null
                }
                CommitteeMembershipMode.RULE ->
                    CommitteeRuleOps.parseOrBadRequest(ruleJson ?: throw BadRequestException("Ein Regelgremium braucht eine Regel."))
            }
        return transaction { CommitteeRuleOps.setMembershipRule(current = current, committeeId = gId, mode = mode, rule = rule) }
    }

    override suspend fun getCommitteeModeChangeImpact(
        committeeId: String,
        targetMode: CommitteeMembershipMode,
    ): CommitteeModeChangeImpactDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val gId = committeeId.toCommitteeUuid()
        return transaction { CommitteeRuleOps.impact(committeeId = gId, targetMode = targetMode) }
    }

    override suspend fun previewCommitteeRule(
        committeeId: String?,
        ruleJson: String,
    ): CommitteeRulePreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        if (!rulePreviewRateLimiter.checkAndRecord("actor:${current.memberId}")) throw SearchRateLimitedException()
        val gId = committeeId?.toCommitteeUuid()
        val rule = CommitteeRuleOps.parseOrBadRequest(ruleJson)
        return transaction {
            RuleReferences.validate(rule = rule, context = RuleContext.COMMITTEE, selfCommitteeId = gId)
            // A number only -- never a name, never an id.
            CommitteeRulePreviewDto(eligible = eligibleIdsForRule(rule = rule, today = OrganizationTimeZone.today()).size)
        }
    }

    override suspend fun searchCommitteeCandidates(
        committeeId: String,
        query: String,
        page: Int,
    ): MemberPickPageDto {
        val current = resolveCurrentMember(call)
        val gId = committeeId.toCommitteeUuid()
        // Order matters: the right comes BEFORE any read of member data, the rate limit AFTER the right (an
        // unauthorized caller must not burn budget, and must not learn which committee ids exist).
        if (!current.canRecordForMeeting(gId)) throw ForbiddenException("Not authorized to search candidates for this committee")
        transaction {
            if (CommitteeTable.select(CommitteeTable.id).where { CommitteeTable.id eq gId }.empty()) {
                throw NotFoundException("Committee $gId not found")
            }
        }
        if (!candidateSearchRateLimiter.checkAndRecord("actor:${current.memberId}")) throw SearchRateLimitedException()
        val q = query.trim()
        if (q.length < CommitteeCandidateSearch.MIN_QUERY_LENGTH || q.length > CommitteeCandidateSearch.MAX_QUERY_LENGTH) {
            throw BadRequestException(
                "query must be ${CommitteeCandidateSearch.MIN_QUERY_LENGTH}..${CommitteeCandidateSearch.MAX_QUERY_LENGTH} characters",
            )
        }
        if (page < 0 || page > CommitteeCandidateSearch.MAX_PAGE) {
            throw BadRequestException("page must be 0..${CommitteeCandidateSearch.MAX_PAGE}")
        }
        // No logging of the search term or any name -- only id + display name leave this method.
        val rows =
            transaction {
                // the pattern's escaping is dialect-aware, so it is built INSIDE the transaction
                val pattern = containsLikePattern(q.lowercase())
                MemberTable
                    .select(MemberTable.id, MemberTable.displayName)
                    .where {
                        (MemberTable.status eq MemberStatus.ACTIVE) and
                            MemberTable.anonymizedAt.isNull() and
                            (MemberTable.displayName.lowerCase() like pattern)
                    }.orderBy(MemberTable.displayName to SortOrder.ASC, MemberTable.id to SortOrder.ASC)
                    .limit(CommitteeCandidateSearch.PAGE_SIZE + 1)
                    .offset((page * CommitteeCandidateSearch.PAGE_SIZE).toLong())
                    .map { MemberPickDto(id = it[MemberTable.id].toString(), displayName = it[MemberTable.displayName]) }
            }
        return MemberPickPageDto(
            items = rows.take(CommitteeCandidateSearch.PAGE_SIZE),
            hasMore = rows.size > CommitteeCandidateSearch.PAGE_SIZE,
        )
    }

    override suspend fun addCommitteeMember(
        committeeId: String,
        input: CommitteeMembershipInput,
    ): CommitteeMembershipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val gId = committeeId.toCommitteeUuid()
        val memberId = input.memberId.toMemberUuid()
        return transaction {
            // ANTRAG membership-gate audit follow-up (2026-07-30): closes the root cause the
            // V0.7.2 ANTRAG membership-gate audit (commit 5082d55) identified but deliberately
            // deferred -- this is the seating path itself, not a downstream vote-casting call
            // site. Committee membership feeds eligibleMemberIds (CommitteeEligibility.kt) for
            // every non-GENERAL_ASSEMBLY Committee's Quorum/Vote/Motion eligibility, and
            // canSubmitMotion's non-GENERAL_ASSEMBLY branch, and SystemicConsensusService
            // .addOption's eligibleMembersOf -- so a status-blind seat here silently grants
            // real governance standing (voting, motion submission, Systemic-Consensus
            // participation) to a member the org never actually admitted, or already let go.
            // Member-only (ACTIVE), not requirePoliticianRaterMembership -- Committee seating is
            // fundamentally a voting-eligibility mechanism and this project's own concept states
            // "Keine Stimmrechte für Gäste". Checked on the SEATED member (input.memberId), not
            // the caller -- the caller's BOARD/ADMIN role is already separately enforced above
            // via requireRole. forUpdate = true (round-3 audit fix, 2026-07-30): this check mints
            // a new CommitteeMembershipTable row further down in this SAME transaction -- without
            // the row lock, a concurrent leaveMembership() could commit between this read and that
            // INSERT, seating a member from a status read that was already stale. See
            // requireActiveMembership KDoc "forUpdate".
            requireActiveMembership(memberId = memberId, forUpdate = true)
            // V1.9.112: lock order here is member (above), then committee; the rule switch only ever locks committees (then seats), so the
            // two cannot deadlock. A rule-based committee takes no manual seat; checked on the LOCKED row so a switch in flight is seen.
            val committeeRow =
                CommitteeTable
                    .selectAll()
                    .where { CommitteeTable.id eq gId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Committee $committeeId not found")
            if (isRuleCommittee(committeeRow)) throw ConflictException("Regelgremien haben keine manuellen Sitze.")
            val activeExists =
                CommitteeMembershipTable
                    .selectAll()
                    .where {
                        (CommitteeMembershipTable.committeeId eq gId) and
                            (CommitteeMembershipTable.memberId eq memberId) and
                            (CommitteeMembershipTable.until.isNull())
                    }.count() > 0
            if (activeExists) {
                throw ConflictException(
                    "Member ${input.memberId} already has an active membership in Committee $committeeId",
                )
            }
            val id = Uuid.random()
            CommitteeMembershipTable.insert {
                it[CommitteeMembershipTable.id] = id
                it[CommitteeMembershipTable.committeeId] = gId
                it[CommitteeMembershipTable.memberId] = memberId
                it[role] = input.role
                it[since] = input.since
                it[until] = null
            }
            // V0.5.2 §20 GwG: seating into an EXECUTIVE_BOARD Committee via this governance/
            // co-option path is exactly the same real-world Vorstandsaenderung as an
            // ElectionService.tally winner seating -- keep the Transparenzregister
            // beneficial-owner roster/reminder mechanism in step, guarded on the Committee's
            // type (NOT isPoliticalParty, see BoardMembershipEvents KDoc).
            if (committeeRow[CommitteeTable.type] == CommitteeType.EXECUTIVE_BOARD) {
                val boardMembershipId =
                    BoardMembershipEvents.recordBoardJoin(
                        memberId = memberId,
                        role = input.role,
                        startedAt = input.since,
                        now = nowLocalDateTime(),
                    )
                // V0.5.3 GoBD audit log: called last, after recordBoardJoin's own writes and before
                // the final read-only select below -- see auditBoardMembershipCreate KDoc for the
                // full call-site rationale (this is one of the two non-BoardMembershipService paths
                // that widened its scope).
                auditBoardMembershipCreate(
                    boardMembershipId = boardMembershipId,
                    memberId = memberId,
                    committeeRole = input.role,
                    startedAt = input.since,
                    current = current,
                )
            }
            (CommitteeMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { CommitteeMembershipTable.id eq id }
                .single()
                .toCommitteeMembershipDto(revealAutoEndReason = true)
        }
    }

    override suspend fun endCommitteeMembership(
        membershipId: String,
        until: LocalDate,
    ): CommitteeMembershipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = membershipId.toMembershipUuid()
        return transaction {
            val membershipRow =
                CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.id eq id }.singleOrNull()
                    ?: throw NotFoundException("CommitteeMembership $membershipId not found")
            // V0.5.2 §20 GwG cascade (EXECUTIVE_BOARD -> BoardMembershipTable + audit log) lives in
            // the shared endCommitteeMembershipRow helper (V0.13.1 extraction) -- see its KDoc.
            // The AUTOMATIC end on a member status change (CommitteeSeatLifecycle, V1.9.102) shares the
            // EXECUTIVE_BOARD cascade with this manual path via cascadeExecutiveBoardEnd.
            endCommitteeMembershipRow(membershipRow = membershipRow, until = until, current = current)
            (CommitteeMembershipTable innerJoin MemberTable)
                .selectAll()
                .where { CommitteeMembershipTable.id eq id }
                .single()
                .toCommitteeMembershipDto(revealAutoEndReason = true)
        }
    }

    override suspend fun listMeetings(
        committeeId: String?,
        status: MeetingStatus?,
    ): List<MeetingDto> {
        val current = resolveCurrentMember(call)
        val gId = committeeId?.toCommitteeUuid()
        // V1.3.1 -- delegates to GovernanceReads, byte-identical behavior (resolveMemberNames stays
        // at its default `true` for this RPC call site).
        return transaction {
            withLinkedEvents(
                current = current,
                meetings = GovernanceReads.listMeetings(committeeId = gId, status = status),
            )
        }
    }

    override suspend fun getMeetingDetail(meetingId: String): MeetingDetailDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val meeting = withLinkedEvents(current = current, meetings = listOf(loadMeeting(sId))).single()
            MeetingDetailDto(
                meeting = meeting,
                agenda = loadAgenda(sId),
                attendance = loadAttendance(sId),
                resolutions = loadResolutions(sId),
                quorum =
                    computeQuorum(
                        meetingId = sId,
                        committeeId = meeting.committeeId.toCommitteeUuid(),
                        scheduledDate = meeting.scheduledAt.date,
                    ),
            )
        }
    }

    override suspend fun createMeeting(input: MeetingInput): MeetingDto {
        val current = resolveCurrentMember(call)
        val gId = input.committeeId.toCommitteeUuid()
        return transaction {
            CommitteeTable.selectAll().where { CommitteeTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Committee ${input.committeeId} not found")
            if (!current.canRecordForMeeting(gId)) throw ForbiddenException()
            val id = Uuid.random()
            val now = nowLocalDateTime()
            MeetingTable.insert {
                it[MeetingTable.id] = id
                it[MeetingTable.committeeId] = gId
                it[title] = input.title
                it[scheduledAt] = input.scheduledAt
                it[location] = input.location
                it[format] = input.format
                it[status] = MeetingStatus.PLANNED
                it[calledBy] = current.memberId
                it[calledAt] = now
                it[chairMemberId] = input.chairMemberId?.let(Uuid::parse)
                it[minuteTakerMemberId] = input.minuteTakerMemberId?.let(Uuid::parse)
                it[protocolDocumentId] = null
                it[createdAt] = now
            }
            WebhookEventPublisher.publish(eventType = WebhookEventType.MEETING_CREATED, entityId = id, occurredAt = now)
            // V1.9.240: when the committee has a confirmed Protokoll nobody approved yet, the new Sitzung gets the approval item. The audit
            // entries come LAST (the chain lock).
            ProtocolApprovalItems.onMeetingCreated(id).forEach { MeetingSessionAudit.record(actor = current, snapshot = it) }
            loadMeeting(id)
        }
    }

    override suspend fun updateMeetingStatus(
        meetingId: String,
        status: MeetingStatus,
    ): MeetingDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            // Welle V1.3.2 "Webhooks" (ausgehend), S3 -- this method previously never read the
            // meeting's pre-update status; a dedicated read is needed so `meeting.held` fires only
            // on a REAL transition into HELD, not on a redundant HELD -> HELD call.
            val previousStatus = MeetingTable.select(MeetingTable.status).where { MeetingTable.id eq sId }.single()[MeetingTable.status]
            MeetingTable.update({ MeetingTable.id eq sId }) { it[MeetingTable.status] = status }
            if (status == MeetingStatus.HELD && previousStatus != MeetingStatus.HELD) {
                WebhookEventPublisher.publish(eventType = WebhookEventType.MEETING_HELD, entityId = sId, occurredAt = nowLocalDateTime())
            }
            if (status == MeetingStatus.CANCELLED && previousStatus != MeetingStatus.CANCELLED) {
                // V1.9.240: an approval item on the cancelled Sitzung is released so the Protokoll is offered to the next one. This locks ANOTHER
                // Sitzung (FOR UPDATE), so it runs before every audit write -- an audit entry takes the global chain lock and must be the LAST lock.
                val approvalAudits = ProtocolApprovalItems.onMeetingCancelled(sId)
                // V1.9.227: a cancelled Sitzung no longer announces anything -- the link is dissolved in the same transaction (the event stays).
                MeetingEventLinkOperations.Lifecycle.onMeetingCancelled(actor = current, meetingId = sId)
                approvalAudits.forEach { MeetingSessionAudit.record(actor = current, snapshot = it) }
            }
            withLinkedEvents(current = current, meetings = listOf(loadMeeting(sId))).single()
        }
    }

    override suspend fun addAgendaItem(
        meetingId: String,
        input: AgendaItemInput,
    ): AgendaItemDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            insertAgendaItem(sId = sId, input = input)
        }
    }

    override suspend fun removeAgendaItem(id: String) {
        val current = resolveCurrentMember(call)
        val topId = id.toAgendaItemUuid()
        transaction {
            val row =
                AgendaItemTable.selectAll().where { AgendaItemTable.id eq topId }.singleOrNull()
                    ?: throw NotFoundException("AgendaItem $id not found")
            val committeeId = requireMeetingCommitteeId(row[AgendaItemTable.meetingId])
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            // V1.9.240: a finalised Protokoll must not lose an item (its snapshot names the items); an approval item that was opened or got its
            // result is a record. The Sitzung row is locked for the check, the same order every Protokoll operation uses.
            val meetingRow =
                MeetingTable
                    .selectAll()
                    .where { MeetingTable.id eq row[AgendaItemTable.meetingId] }
                    .forUpdate()
                    .single()
            if (ProtocolDraftTable
                    .selectAll()
                    .where {
                        (ProtocolDraftTable.meetingId eq row[AgendaItemTable.meetingId]) and
                            (ProtocolDraftTable.phase eq ProtocolDraftPhase.LOCKED)
                    }.count() > 0
            ) {
                throw ConflictException("Das Protokoll ist fertiggestellt; der Tagesordnungspunkt kann nicht mehr entfernt werden.")
            }
            if (ProtocolApprovalItems.isProtected(item = row, meeting = meetingRow)) {
                throw ConflictException("Zu diesem Punkt gibt es schon ein Ergebnis oder die Sitzung wurde eröffnet.")
            }
            // V1.9.236: meeting.current_agenda_item_id has no foreign key (it would close a cycle the backup cannot restore) -- clear it here.
            MeetingTable.update({ MeetingTable.currentAgendaItemId eq topId }) { it[currentAgendaItemId] = null }
            // V1.9.239: the Protokoll draft points at the item (foreign keys). Written text or a task keeps the item; an EMPTY block goes with it.
            if (ProtocolDraftTaskTable.selectAll().where { ProtocolDraftTaskTable.agendaItemId eq topId }.count() > 0 ||
                ProtocolDraftBlockTable
                    .selectAll()
                    .where { (ProtocolDraftBlockTable.agendaItemId eq topId) and (ProtocolDraftBlockTable.body neq "") }
                    .count() > 0
            ) {
                throw ConflictException("Zu diesem Punkt gibt es schon Protokolltext oder Aufgaben.")
            }
            // Only EMPTY blocks: a text saved between the check above and this delete (READ COMMITTED) is never deleted silently -- the
            // foreign key refuses the item delete instead and the whole transaction fails.
            ProtocolDraftBlockTable.deleteWhere { (ProtocolDraftBlockTable.agendaItemId eq topId) and (ProtocolDraftBlockTable.body eq "") }
            AgendaItemTable.deleteWhere { AgendaItemTable.id eq topId }
        }
    }

    override suspend fun recordAttendance(
        meetingId: String,
        input: AttendanceInput,
    ): AttendanceDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        val memberId = input.memberId.toMemberUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val id =
                AttendanceWriter.upsert(
                    meetingId = sId,
                    memberId = memberId,
                    status = input.status,
                    representedBy = input.representedByMemberId?.let(Uuid::parse),
                    note = input.note,
                    now = nowLocalDateTime(),
                )
            AttendanceTable
                .selectAll()
                .where { AttendanceTable.id eq id }
                .single()
                .toAttendanceDto()
        }
    }

    override suspend fun getAttendance(meetingId: String): List<AttendanceDto> {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction { loadAttendance(sId) }
    }

    override suspend fun checkQuorum(meetingId: String): QuorumResultDto {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val meeting = loadMeeting(sId)
            computeQuorum(meetingId = sId, committeeId = meeting.committeeId.toCommitteeUuid(), scheduledDate = meeting.scheduledAt.date)
        }
    }

    override suspend fun recordResolution(
        meetingId: String,
        input: ResolutionInput,
    ): ResolutionDto {
        val current = resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val committeeId = requireMeetingCommitteeId(sId)
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            // V1.9.23: a free-form resolution recorded against an agenda item must not sidestep an election
            // that is deciding the motion behind that item. The motions are locked (motion first, ascending id)
            // so the check cannot race an openElection.
            val agendaItemId = input.agendaItemId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            if (agendaItemId != null) {
                MotionDecisionLock.lockMotionsByAgendaItem(agendaItemId).forEach {
                    MotionDecisionLock.requireNoActiveElection(it[MotionTable.id])
                }
            }
            val meeting = loadMeeting(sId)
            val resolution =
                insertResolutionRow(
                    sId = sId,
                    committeeId = committeeId,
                    scheduledDate = meeting.scheduledAt.date,
                    input = input,
                    current = current,
                )
            // Welle V1.3.2 "Webhooks" (ausgehend) -- MUST run before auditResolutionCreate (S2:
            // AuditLogRecorder's own deadlock-avoidance contract requires its write to be the LAST
            // one in the transaction).
            WebhookEventPublisher.publish(
                eventType = WebhookEventType.RESOLUTION_ADOPTED,
                entityId = Uuid.parse(resolution.id),
                occurredAt = resolution.decidedAt,
            )
            // V0.5.3 GoBD audit log: CREATE only -- a Resolution is never mutated after recording
            // (no update path exists in this codebase), see 14-audit-log.kuml.kts file header.
            // This is the last database write of this transaction, satisfying AuditLogRecorder's
            // deadlock-avoidance contract -- see auditResolutionCreate KDoc.
            auditResolutionCreate(resolution = resolution, current = current)
            resolution
        }
    }

    override suspend fun listResolutions(
        committeeId: String?,
        meetingId: String?,
    ): List<ResolutionDto> {
        resolveCurrentMember(call)
        val gId = committeeId?.toCommitteeUuid()
        val sId = meetingId?.toMeetingUuid()
        // V1.3.1 -- delegates to GovernanceReads, byte-identical behavior.
        return transaction { GovernanceReads.listResolutions(committeeId = gId, meetingId = sId) }
    }

    override suspend fun generateProtocolDraft(meetingId: String): ProtocolDraftDto {
        resolveCurrentMember(call)
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val meeting = loadMeeting(sId)
            ProtocolDraftDto(
                meeting = meeting,
                attendance = loadAttendance(sId),
                agenda = loadAgenda(sId),
                resolutions = loadResolutions(sId),
                quorum =
                    computeQuorum(
                        meetingId = sId,
                        committeeId = meeting.committeeId.toCommitteeUuid(),
                        scheduledDate = meeting.scheduledAt.date,
                    ),
                generatedAt = nowLocalDateTime(),
            )
        }
    }

    override suspend fun submitMotion(input: MotionInput): MotionDto {
        val current = resolveCurrentMember(call)
        val gId = input.targetCommitteeId.toCommitteeUuid()
        val amendsId = input.amendsMotionId?.toMotionUuid()
        return transaction {
            CommitteeTable.selectAll().where { CommitteeTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Committee ${input.targetCommitteeId} not found")
            if (!current.canSubmitMotion(gId)) throw ForbiddenException()
            // Änderungsantrag (V0.2.6): validate the amendment's target before inserting. Reuses
            // plain canSubmitMotion above for the amendment itself -- any member entitled to
            // submit to this Committee may amend, not only the target's original submitter,
            // mirroring real floor-amendment practice.
            //
            // forUpdate(): locks the target row FOR UPDATE for the rest of this transaction --
            // same "every mutating method locks the row FOR UPDATE first" discipline as
            // CrowdfundingService.requireProjectRow/AuctionService/PoliticianService.
            // requireProfileRowByMember/RegistrationService.requireApplicationRow. Without this,
            // a concurrent resolveMotion/closeVote on the SAME target (which now also takes this
            // lock, see their own KDoc) could read the target as still non-terminal, pass its own
            // requireNoPendingAmendments check, and finalize the target in the gap before this
            // INSERT below commits -- leaving a freshly-submitted amendment permanently pending
            // against an already-terminal main motion, and a later resolveMotion/closeVote on
            // THAT amendment would then silently overwrite currentText on the already-finalized
            // target (its unconditional write below is not itself guarded by the target's
            // status). Locking here closes the gap: whichever transaction acquires the target
            // row's lock first now runs to completion before the other can even read it.
            if (amendsId != null) {
                val targetRow =
                    MotionTable
                        .selectAll()
                        .where { MotionTable.id eq amendsId }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("Motion ${input.amendsMotionId} not found")
                if (targetRow[MotionTable.amendsMotionId] != null) {
                    throw ConflictException(
                        "Motion ${input.amendsMotionId} is itself an amendment -- amendments of amendments are not supported",
                    )
                }
                if (targetRow[MotionTable.targetCommitteeId] != gId) {
                    throw ConflictException(
                        "Amendment's targetCommitteeId must match the amended Motion's own target Committee",
                    )
                }
                if (targetRow[MotionTable.status] !in NON_TERMINAL_MOTION_STATUSES) {
                    throw ConflictException("Motion ${input.amendsMotionId} is ${targetRow[MotionTable.status]}, not amendable")
                }
                // V1.9.54: while the target's election / vote / systemic consensus runs, its text must not change
                // under the voters, and the amendment would only block the tally. The target row is locked above and
                // every path opens under that same lock, so this check is serialized against them.
                MotionDecisionLock.requireNoRunningDecisionPath(amendsId)
                // V1.9.201: an amendment is a separate motion and would escape the bidder conflict of interest; the award motion's text is fixed.
                if (isTenderAwardMotion(amendsId)) {
                    throw ConflictException("The award motion of a tender cannot be amended")
                }
            }
            val id = Uuid.random()
            val now = nowLocalDateTime()
            MotionTable.insert {
                it[MotionTable.id] = id
                it[targetCommitteeId] = gId
                it[title] = input.title
                it[rationale] = input.rationale
                it[text] = input.text
                it[submitterMemberId] = current.memberId
                it[status] = MotionStatus.SUBMITTED
                it[submittedAt] = now
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[reviewNote] = null
                it[meetingId] = null
                it[agendaItemId] = null
                it[resolutionId] = null
                it[withdrawnAt] = null
                it[MotionTable.amendsMotionId] = amendsId
                it[MotionTable.currentText] = null
            }
            loadMotion(id)
        }
    }

    override suspend fun listMotions(
        targetCommitteeId: String?,
        status: MotionStatus?,
        amendsMotionId: String?,
    ): List<MotionDto> {
        resolveCurrentMember(call)
        val gId = targetCommitteeId?.toCommitteeUuid()
        val aId = amendsMotionId?.toMotionUuid()
        // V1.3.1 -- delegates to GovernanceReads, byte-identical behavior (allowedStatuses stays at
        // its default `null` -- no REST-only whitelist for this RPC call site).
        return transaction { GovernanceReads.listMotions(targetCommitteeId = gId, status = status, amendsMotionId = aId) }
    }

    override suspend fun getMotion(id: String): MotionDto {
        resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction { loadMotion(aId) }
    }

    override suspend fun withdrawMotion(id: String): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction {
            // V1.9.23: locked, and refused while an election runs on the motion -- withdrawing it would leave
            // the election unable to be tallied. The election has to be aborted first.
            val row = MotionDecisionLock.lockMotion(aId)
            val committeeId = row[MotionTable.targetCommitteeId]
            val submitterId = row[MotionTable.submitterMemberId]
            val status = row[MotionTable.status]
            val submitterWithdrawingOwnPending = current.memberId == submitterId && status == MotionStatus.SUBMITTED
            if (!submitterWithdrawingOwnPending && !current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (status == MotionStatus.WITHDRAWN) throw ConflictException("Motion $id already withdrawn")
            MotionDecisionLock.requireNoActiveElection(aId)
            val now = nowLocalDateTime()
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = MotionStatus.WITHDRAWN
                it[withdrawnAt] = now
            }
            // Änderungsantrag (V0.2.6): a withdrawn main Motion procedurally no longer exists to
            // be amended -- auto-cascade WITHDRAWN onto every still-pending amendment rather than
            // leaving them orphaned. Only for a MAIN motion (amendsMotionId == null); withdrawing
            // an amendment itself has no cascade of its own.
            if (row[MotionTable.amendsMotionId] == null) cascadeWithdrawPendingAmendments(motionId = aId, now = now)
            loadMotion(aId)
        }
    }

    override suspend fun reviewMotion(
        id: String,
        decision: MotionReviewDecision,
        note: String?,
    ): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction {
            val row =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val status = row[MotionTable.status]
            if (status != MotionStatus.SUBMITTED) {
                throw ConflictException("Motion $id is $status, expected SUBMITTED")
            }
            val newStatus =
                when (decision) {
                    MotionReviewDecision.ACCEPT -> MotionStatus.REVIEWED
                    MotionReviewDecision.REJECT -> MotionStatus.REJECTED_PRELIMINARY
                }
            val now = nowLocalDateTime()
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = newStatus
                it[reviewedBy] = current.memberId
                it[reviewedAt] = now
                it[reviewNote] = note
            }
            // Änderungsantrag (V0.2.6): a preliminarily-rejected main Motion is procedurally moot,
            // same reasoning as withdrawMotion's cascade -- but NOT for ACCEPT (REVIEWED), which
            // keeps the main Motion alive, so its amendments correctly stay pending too.
            if (newStatus == MotionStatus.REJECTED_PRELIMINARY && row[MotionTable.amendsMotionId] == null) {
                cascadeWithdrawPendingAmendments(motionId = aId, now = now)
            }
            loadMotion(aId)
        }
    }

    override suspend fun scheduleMotion(
        id: String,
        meetingId: String,
        position: Int,
    ): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        val sId = meetingId.toMeetingUuid()
        return transaction {
            val row =
                MotionTable.selectAll().where { MotionTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            val status = row[MotionTable.status]
            if (status != MotionStatus.REVIEWED && status != MotionStatus.POSTPONED) {
                throw ConflictException("Motion $id is $status, expected REVIEWED or POSTPONED")
            }
            val meetingRow =
                MeetingTable.selectAll().where { MeetingTable.id eq sId }.singleOrNull()
                    ?: throw NotFoundException("Meeting $meetingId not found")
            if (meetingRow[MeetingTable.committeeId] != committeeId) {
                throw ConflictException("Meeting $meetingId does not belong to Motion $id's target Committee")
            }
            if (meetingRow[MeetingTable.status] != MeetingStatus.PLANNED) {
                throw ConflictException("Meeting $meetingId is not PLANNED")
            }

            // Änderungsantrag (V0.2.6): an amendment must land on its target main Motion's EXACT
            // SAME Meeting AND AgendaItem -- voting on an amendment separately from its own
            // motion's meeting/agenda point makes no procedural sense. Enforced server-side, not
            // merely trusted from the caller. Reuses the target's existing AgendaItem row rather
            // than creating a second one; `position` is ignored for an amendment.
            val amendsId = row[MotionTable.amendsMotionId]
            val topId: Uuid =
                if (amendsId != null) {
                    val targetRow =
                        MotionTable.selectAll().where { MotionTable.id eq amendsId }.singleOrNull()
                            ?: throw NotFoundException("Target Motion $amendsId not found")
                    val targetMeetingId =
                        targetRow[MotionTable.meetingId]
                            ?: throw ConflictException(
                                "Target Motion $amendsId must be scheduled before its amendments can be scheduled",
                            )
                    if (targetMeetingId != sId) {
                        throw ConflictException(
                            "Amendment $id must be scheduled onto its target Motion's own Meeting ($targetMeetingId), not $meetingId",
                        )
                    }
                    targetRow[MotionTable.agendaItemId]
                        ?: throw ConflictException("Target Motion $amendsId has no AgendaItem yet")
                } else {
                    Uuid.parse(
                        insertAgendaItem(
                            sId = sId,
                            input =
                                AgendaItemInput(
                                    position = position,
                                    title = row[MotionTable.title],
                                    description = row[MotionTable.rationale],
                                    presenterMemberId = row[MotionTable.submitterMemberId].toString(),
                                ),
                        ).id,
                    )
                }
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = MotionStatus.SCHEDULED
                it[MotionTable.meetingId] = sId
                it[MotionTable.agendaItemId] = topId
            }
            // Welle V1.3.2 "Webhooks" (ausgehend) -- D8-konform: SCHEDULED ist Teil von
            // PUBLIC_API_MOTION_STATUSES, GET /api/v1/motions/{id} liefert diesen Antrag also
            // unmittelbar danach aus.
            check(MotionStatus.SCHEDULED in PUBLIC_API_MOTION_STATUSES)
            WebhookEventPublisher.publish(eventType = WebhookEventType.MOTION_SCHEDULED, entityId = aId, occurredAt = nowLocalDateTime())
            loadMotion(aId)
        }
    }

    override suspend fun resolveMotion(
        id: String,
        input: MotionResolutionInput,
    ): MotionDto {
        val current = resolveCurrentMember(call)
        val aId = id.toMotionUuid()
        return transaction {
            // forUpdate(): locks this Motion row (main or amendment) FOR UPDATE for the rest of
            // this transaction -- pairs with submitMotion's own forUpdate() lock on a target row
            // (see that KDoc for the exact race this closes) and with closeVote's equivalent lock
            // below. For a MAIN motion this also serializes against a second concurrent
            // resolveMotion/closeVote call finalizing the same motion twice.
            //
            // V1.9.54: adopting an AMENDMENT writes the target's `currentText`, so then the target is locked too.
            // `amends_motion_id` never changes, so it is read without a lock; both rows are then locked in
            // ascending id order (the order of `lockMotionsByAgendaItem`), so no lock cycle is possible (closeVote uses the same order).
            val amendsPeek =
                if (input.status == ResolutionStatus.ADOPTED) {
                    MotionTable
                        .select(MotionTable.amendsMotionId)
                        .where { MotionTable.id eq aId }
                        .singleOrNull()
                        ?.get(MotionTable.amendsMotionId)
                } else {
                    null
                }
            var targetRow: ResultRow? = null
            if (amendsPeek != null) {
                val ordered = listOf(aId, amendsPeek).sortedBy { it.toString() }
                ordered.forEach { lockedId ->
                    val locked = MotionDecisionLock.lockMotion(lockedId)
                    if (lockedId == amendsPeek) targetRow = locked
                }
            }
            val row =
                MotionTable
                    .selectAll()
                    .where { MotionTable.id eq aId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Motion $id not found")
            val committeeId = row[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (row[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("Motion $id is ${row[MotionTable.status]}, expected SCHEDULED")
            }
            // V1.9.54: the target of an adopted amendment must not be in the middle of its own decision path
            // (legacy state only: submitMotion and the opening paths keep this from arising any more).
            if (targetRow != null) MotionDecisionLock.requireNoRunningDecisionPath(checkNotNull(amendsPeek))
            // V1.9.23: an election is the decision path of this motion; the quorum path may not decide it too.
            // V1.9.54: the same holds for an OPEN meritocratic vote and a running systemic consensus.
            MotionDecisionLock.requireNoRunningDecisionPath(aId)
            // Änderungsantrag (V0.2.6) ordering guard: a MAIN motion may not resolve while any of
            // its amendments is still pending -- see requireNoPendingAmendments KDoc.
            if (row[MotionTable.amendsMotionId] == null) requireNoPendingAmendments(aId)
            val sId = row[MotionTable.meetingId] ?: throw ConflictException("Motion $id has no scheduled Meeting")
            val topId = row[MotionTable.agendaItemId]
            val meeting = loadMeeting(sId)
            // Änderungsantrag (V0.2.6): the (possibly-amended) main motion's CURRENT working text,
            // not the immutable original -- see MotionDto.effectiveText KDoc.
            val effectiveText = row[MotionTable.currentText] ?: row[MotionTable.text]
            val resolutionInput =
                ResolutionInput(
                    agendaItemId = topId?.toString(),
                    title = row[MotionTable.title],
                    text = effectiveText,
                    votesYes = input.votesYes,
                    votesNo = input.votesNo,
                    votesAbstain = input.votesAbstain,
                    status = input.status,
                )
            // V1.9.201: on the award motion of a tender its bidders are excluded from both sides of the quorum, and the entered figures must be
            // plausible against the remaining present members. The server cannot see WHO the protocol keeper counted (see docs/architecture/tenders.adoc).
            val tenderExcluded = tenderConflictedMemberIdsForMotion(aId)
            if (tenderExcluded.isNotEmpty()) {
                val quorumAfterExclusion =
                    computeQuorum(
                        meetingId = sId,
                        committeeId = committeeId,
                        scheduledDate = meeting.scheduledAt.date,
                        excludedMemberIds = tenderExcluded,
                    )
                if (input.votesYes + input.votesNo + input.votesAbstain > quorumAfterExclusion.presentCount) {
                    throw ConflictException("The votes exceed the number of present members who are not bidders of the tender")
                }
            }
            val resolution =
                insertResolutionRow(
                    sId = sId,
                    committeeId = committeeId,
                    scheduledDate = meeting.scheduledAt.date,
                    input = resolutionInput,
                    current = current,
                    excludedMemberIds = tenderExcluded,
                )
            val newMotionStatus =
                when (input.status) {
                    ResolutionStatus.ADOPTED -> MotionStatus.RESOLVED
                    ResolutionStatus.REJECTED -> MotionStatus.REJECTED
                    ResolutionStatus.POSTPONED -> MotionStatus.POSTPONED
                }
            MotionTable.update({ MotionTable.id eq aId }) {
                it[MotionTable.status] = newMotionStatus
                it[MotionTable.resolutionId] = Uuid.parse(resolution.id)
            }
            // Änderungsantrag (V0.2.6) adoption: an ADOPTED amendment copies its own text into its
            // target main motion's current working text -- full-text replacement,
            // "last-adopted-wins" for multiple sequential amendments (see MotionDto KDoc for the
            // disclosed simplification). Must run BEFORE auditResolutionCreate below
            // (AuditLogRecorder's deadlock-avoidance "last write" contract).
            val amendsId = row[MotionTable.amendsMotionId]
            if (amendsId != null && input.status == ResolutionStatus.ADOPTED) {
                MotionTable.update({ MotionTable.id eq amendsId }) {
                    it[MotionTable.currentText] = row[MotionTable.text]
                }
            }
            // V0.5.3 GoBD audit log: called last, after MotionTable.update, so this satisfies
            // AuditLogRecorder's deadlock-avoidance contract -- see auditResolutionCreate KDoc.
            auditResolutionCreate(resolution = resolution, current = current)
            loadMotion(aId)
        }
    }

    override suspend fun openVote(input: VoteOpenInput): VoteDto {
        val current = resolveCurrentMember(call)
        val aId = input.motionId.toMotionUuid()
        return transaction {
            // V1.9.23: locked first (see MotionDecisionLock), so the vote check and the exclusion against an
            // election / systemic consensus cannot race another openVote or openElection.
            val motionRow = MotionDecisionLock.lockMotion(aId)
            val committeeId = motionRow[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (motionRow[MotionTable.status] != MotionStatus.SCHEDULED) {
                throw ConflictException("Motion ${input.motionId} is ${motionRow[MotionTable.status]}, expected SCHEDULED")
            }
            val sId =
                motionRow[MotionTable.meetingId]
                    ?: throw ConflictException("Motion ${input.motionId} has no scheduled Meeting")
            MotionDecisionLock.requireNoActiveElection(aId)
            MotionDecisionLock.requireNoActiveSystemicConsensus(aId)
            // V1.9.54: a main motion with a pending amendment may not enter a decision path (serverside; the client
            // only hid the button before). Otherwise that amendment could be adopted while the vote runs and change
            // `currentText` under the voters. Together with submitMotion's refusal (no amendment during a running
            // path) a running path of a main motion therefore never has a pending amendment.
            if (motionRow[MotionTable.amendsMotionId] == null) requireNoPendingAmendments(aId)
            val hasActiveVote =
                VoteTable
                    .selectAll()
                    .where {
                        (VoteTable.motionId eq aId) and
                            (VoteTable.status inList listOf(VoteStatus.OPEN, VoteStatus.CLOSED))
                    }.count() > 0
            if (hasActiveVote) {
                throw ConflictException("Motion ${input.motionId} already has an open or resolved Vote")
            }
            val distinctLabels =
                input.optionLabels
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            if (distinctLabels.size < 2) {
                throw ConflictException("openVote requires at least 2 distinct non-blank option labels")
            }
            if (distinctLabels.size > MAX_ABSTIMMUNG_OPTIONS) {
                throw ConflictException("openVote accepts at most $MAX_ABSTIMMUNG_OPTIONS distinct option labels")
            }
            if (distinctLabels.any { it.length > MAX_OPTION_LABEL_LENGTH }) {
                throw ConflictException("Option labels must be at most $MAX_OPTION_LABEL_LENGTH characters")
            }
            val id = Uuid.random()
            val now = nowLocalDateTime()
            VoteTable.insert {
                it[VoteTable.id] = id
                it[VoteTable.motionId] = aId
                it[VoteTable.meetingId] = sId
                it[title] = motionRow[MotionTable.title]
                it[status] = VoteStatus.OPEN
                it[openedBy] = current.memberId
                it[openedAt] = now
                it[closedAt] = null
                it[winnerOptionId] = null
                it[secondPriceLtr] = null
                it[resolutionId] = null
                it[electorateFrozenAt] = now
            }
            distinctLabels.forEachIndexed { index, label ->
                VoteOptionTable.insert {
                    it[VoteOptionTable.id] = Uuid.random()
                    it[VoteOptionTable.voteId] = id
                    it[VoteOptionTable.label] = label
                    it[position] = index
                }
            }
            // V1.9.112: the electorate is frozen NOW, in the same transaction and under the motion lock taken at the top -- the circle of
            // voters (and the denominator of the quorum) can no longer change under a running vote. openVote takes no room lock, so there
            // is nothing to hold across this insert. See frozenOrLiveElectorate.
            freezeVoteElectorate(
                voteId = id,
                meetingId = sId,
                committeeId = committeeId,
                excludedMemberIds = tenderConflictedMemberIdsForMotion(aId),
            )
            loadVote(id)
        }
    }

    /**
     * Eligibility mirrors [computeQuorum]'s [eligibleMemberIds] set for the Vote's
     * underlying Meeting -- staking LTR into a basket is, per the concept document, a right
     * exercised by the same constituency that would otherwise cast a headcount vote on this
     * Motion, not an org-wide free-for-all (see the implementation plan's open decision point
     * (c) for the alternative considered and deferred).
     */
    override suspend fun castVoteBallot(input: VoteBallotInput): VoteBallotDto {
        val current = resolveCurrentMember(call)
        val abId = input.voteId.toVoteUuid()
        val optId = input.optionId.toVoteOptionUuid()
        return transaction {
            // ANTRAG membership-gate audit (2026-07-30): closes the gap disclosed since V0.7.2 --
            // an APPLICATION applicant must not be able to cast a governance vote (and stake LTR on it)
            // before board approval. Defense in depth: eligibleMemberIds below only re-checks live
            // ACTIVE status for a GENERAL_ASSEMBLY Committee -- for any other Committee type,
            // eligibility is Committee membership alone (CommitteeMembershipTable). As of the
            // addCommitteeMember root-cause fix (2026-07-30), addCommitteeMember re-validates the
            // seated member's status at seat-time, so a fresh non-ACTIVE row can no longer be
            // created there -- this check stays as an independent second layer (defense in depth
            // against any future seating path that bypasses addCommitteeMember, or a legacy row
            // predating that fix). Member-only (ACTIVE), not requirePoliticianRaterMembership -- this
            // project's own concept states "Keine Stimmrechte für Gäste": guests never get vote
            // weight, full stop.
            requireActiveMembership(memberId = current.memberId)
            // V1.9.54: the vote row is locked FOR UPDATE (child row only, never the motion) BEFORE the status check
            // and before lockForDebit (lock order: vote, then member). closeVote/abortVote lock motion -> vote, so a
            // bid either commits before they read the ballots (and is settled) or sees CLOSED/ABORTED and is refused;
            // before, a bid could land after closeVote committed: an unsettled ballot and a VOTE_STAKE debit that
            // nothing ever settles.
            val voteRow =
                VoteTable
                    .selectAll()
                    .where { VoteTable.id eq abId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Vote ${input.voteId} not found")
            if (voteRow[VoteTable.status] != VoteStatus.OPEN) {
                throw ConflictException(
                    "Vote ${input.voteId} is ${voteRow[VoteTable.status]}, expected OPEN",
                )
            }
            // V1.9.201: a bidder of a tender has no vote on the award motion of that tender (refused before anything is staked).
            if (current.memberId in tenderConflictedMemberIdsForMotion(voteRow[VoteTable.motionId])) throw ForbiddenException()
            VoteOptionTable
                .selectAll()
                .where { (VoteOptionTable.id eq optId) and (VoteOptionTable.voteId eq abId) }
                .singleOrNull()
                ?: throw NotFoundException("Option ${input.optionId} does not belong to Vote ${input.voteId}")

            // V1.9.112: a vote opened since V1.9.112 is decided against the electorate frozen when it was opened (point query on the
            // snapshot); a vote from before has no marker and keeps the live set. requireActiveMembership above stays the live lower
            // bound: somebody who left the association after the vote opened is still in the denominator but can no longer vote.
            val eligibleToVote =
                if (voteRow[VoteTable.electorateFrozenAt] != null) {
                    VoteEligibleVoterTable
                        .selectAll()
                        .where { (VoteEligibleVoterTable.voteId eq abId) and (VoteEligibleVoterTable.memberId eq current.memberId) }
                        .limit(1)
                        .count() > 0
                } else {
                    val meeting = loadMeeting(voteRow[VoteTable.meetingId])
                    val committeeRow =
                        CommitteeTable.selectAll().where { CommitteeTable.id eq meeting.committeeId.toCommitteeUuid() }.single()
                    current.memberId in eligibleMemberIds(committeeRow = committeeRow, scheduledDate = meeting.scheduledAt.date)
                }
            if (!eligibleToVote) throw ForbiddenException()

            val stake = input.stakeLtr
            if (stake.scale() > 2) throw ConflictException("stakeLtr must have at most 2 decimal places")
            val normalizedStake = stake.setScale(2, RoundingMode.UNNECESSARY)
            if (normalizedStake < MIN_STAKE_LTR) throw ConflictException("stakeLtr must be at least $MIN_STAKE_LTR")
            // Serializes this debit-causing read-then-write against every other LTR-debiting call
            // for this same member (e.g. a concurrent castVoteBallot on a DIFFERENT Vote, a
            // concurrent recast of THIS SAME ballot from a second request, or a concurrent
            // CrowdfundingService.submitProject) -- see LtrBalanceProvider.lockForDebit KDoc for
            // the TOCTOU this closes. Must happen before both the existing-ballot read and the
            // freeBalance read below, so a concurrent recast of this same ballot always observes
            // the previous cast's debit before this transaction decides the new one.
            ltrBalanceProvider.lockForDebit(current.memberId)

            val now = nowLocalDateTime()
            val existing =
                VoteBallotTable
                    .selectAll()
                    .where {
                        (VoteBallotTable.voteId eq abId) and (VoteBallotTable.memberId eq current.memberId)
                    }.singleOrNull()
            // V1.9.27: createOnly (conference room) refuses to turn into a recast. Before any ledger write.
            if (input.createOnly && existing != null) throw ConflictException("Ballot already exists")

            // A recast (existing != null) only needs to debit the INCREASE over its own previous
            // stake -- that previous stake's debit already sits in the ledger from the earlier
            // cast below, still binding that portion of the member's balance. A same-or-lower
            // recast is accepted without releasing the difference: no release path exists yet for
            // a bound Vote stake (recast-down, closeVote settling below the full stake, or
            // abortVote) -- same "bound, not released this wave" limitation
            // LtrLedgerEntryType.VOTE_STAKE's own KDoc documents, mirroring the already-accepted
            // PROJECT_STAKE_RELEASE gap CrowdfundingService.submitProject has.
            val previousStake = existing?.get(VoteBallotTable.stakeLtr) ?: ZERO_LTR
            val additionalDebit = normalizedStake - previousStake
            if (additionalDebit > ZERO_LTR) {
                val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
                if (additionalDebit > freeBalance) {
                    throw ConflictException(
                        "stakeLtr $normalizedStake exceeds free LTR balance $freeBalance " +
                            "(additional stake required: $additionalDebit)",
                    )
                }
            }

            val id =
                if (existing == null) {
                    val newId = Uuid.random()
                    VoteBallotTable.insert {
                        it[VoteBallotTable.id] = newId
                        it[VoteBallotTable.voteId] = abId
                        it[VoteBallotTable.optionId] = optId
                        it[VoteBallotTable.memberId] = current.memberId
                        it[stakeLtr] = normalizedStake
                        it[settledLtr] = null
                        it[castAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[VoteBallotTable.id]
                    VoteBallotTable.update({ VoteBallotTable.id eq existingId }) {
                        it[VoteBallotTable.optionId] = optId
                        it[stakeLtr] = normalizedStake
                        it[settledLtr] = null
                        it[castAt] = now
                    }
                    existingId
                }

            // The actual debiting ledger row LtrBalanceProvider.kt's KDoc has always documented
            // castVoteBallot as writing -- previously missing entirely (security fix), which left
            // freeBalance blind to open Vote stakes and allowed the same LTR to be staked on
            // unlimited concurrent Votes and spent again via CrowdfundingService.submitProject.
            // Only written when the bound amount actually grows (see additionalDebit above); a
            // pure option change at the same stake, or a recast-down, writes nothing.
            if (additionalDebit > ZERO_LTR) {
                LtrLedgerEntryTable.insert {
                    it[LtrLedgerEntryTable.id] = Uuid.random()
                    it[memberId] = current.memberId
                    it[entryType] = LtrLedgerEntryType.VOTE_STAKE
                    it[amountLtr] = additionalDebit.negate()
                    it[referenceType] = LtrLedgerReferenceType.VOTE
                    it[referenceId] = abId
                    it[note] = "LTR-Stake fuer Vote '${voteRow[VoteTable.title]}'"
                    it[createdBy] = null
                    it[createdAt] = now
                }
            }

            VoteBallotTable
                .selectAll()
                .where { VoteBallotTable.id eq id }
                .single()
                .toVoteBallotDto()
        }
    }

    /**
     * Runs the Vickrey settlement ([computeVickreySettlement]) and writes it into the same
     * resolution book [recordResolution]/[resolveMotion] use, tagged
     * [ResolutionMode.MERITOCRATIC] -- see [insertResolutionRow]. `votesYes`/`votesNo` are
     * populated informationally only for the default 2-option YES/NO shape (headcount of ballots
     * per label, not LTR-weighted); any other option count leaves them at `0/0/0` since the
     * weighted result lives in the Vote itself, not in headcount fields designed for the
     * Committee-Quorum path. The winning *basket's* label decides [ResolutionStatus]: a basket
     * labelled `"NO"` (case-insensitive) resolves [ResolutionStatus.REJECTED], any other winning
     * basket (including a >2-option Sachentscheidung's winning project) resolves
     * [ResolutionStatus.ADOPTED], and a tie (no winner) resolves [ResolutionStatus.POSTPONED] --
     * documented decision point (a) from the implementation plan.
     */
    override suspend fun closeVote(voteId: String): VoteDto {
        val current = resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction {
            // motion_id never changes, so it can be read before any lock.
            val motionId =
                VoteTable
                    .selectAll()
                    .where { VoteTable.id eq abId }
                    .singleOrNull()
                    ?.get(VoteTable.motionId)
                    ?: throw NotFoundException("Vote $voteId not found")
            // forUpdate(): same lock discipline as resolveMotion's own Motion-row read -- see its
            // KDoc for the submitMotion race this closes; closeVote is the second path capable of
            // finalizing a main motion, so it needs the identical lock.
            // V1.9.54: adopting an amendment writes the target's `currentText`, so the target is locked as well,
            // in the same ascending-id order as resolveMotion (amends_motion_id never changes, so the peek needs
            // no lock) -- otherwise closeVote (A -> T) and resolveMotion (T -> A) could deadlock on one amendment.
            val amendsPeek =
                MotionTable
                    .select(MotionTable.amendsMotionId)
                    .where { MotionTable.id eq motionId }
                    .singleOrNull()
                    ?.get(MotionTable.amendsMotionId)
            var motionRowLocked: ResultRow? = null
            if (amendsPeek != null) {
                listOf(motionId, amendsPeek).sortedBy { it.toString() }.forEach { lockedId ->
                    val locked = MotionDecisionLock.lockMotion(lockedId)
                    if (lockedId == motionId) motionRowLocked = locked
                }
            } else {
                motionRowLocked = MotionDecisionLock.lockMotion(motionId)
            }
            val motionRow = checkNotNull(motionRowLocked)
            // V1.9.23: the vote row is re-read FOR UPDATE only AFTER the motion lock. Before, it was read
            // before the lock and its stale status was "re-checked" below, so two concurrent closeVote calls
            // could both settle and both write a resolution.
            val voteRow =
                VoteTable
                    .selectAll()
                    .where { VoteTable.id eq abId }
                    .forUpdate()
                    .single()
            val committeeId = motionRow[MotionTable.targetCommitteeId]
            if (!current.canRecordForMeeting(committeeId)) throw ForbiddenException()
            if (voteRow[VoteTable.status] != VoteStatus.OPEN) {
                throw ConflictException("Vote $voteId is ${voteRow[VoteTable.status]}, expected OPEN")
            }
            // V1.9.23: an election on the same motion owns the decision; the motion must still be undecided.
            MotionDecisionLock.requireNoActiveElection(motionId)
            MotionDecisionLock.requireUndecided(motionRow)
            // Änderungsantrag (V0.2.6) ordering guard: same invariant resolveMotion enforces --
            // required for soundness here too, since closeVote is a second path capable of
            // finalizing a main motion that still has pending amendments.
            if (motionRow[MotionTable.amendsMotionId] == null) requireNoPendingAmendments(motionRow[MotionTable.id])

            val optionRows = VoteOptionTable.selectAll().where { VoteOptionTable.voteId eq abId }.toList()
            val optionIds = optionRows.map { it[VoteOptionTable.id] }
            val labelByOptionId = optionRows.associate { it[VoteOptionTable.id] to it[VoteOptionTable.label] }
            val ballotRows = VoteBallotTable.selectAll().where { VoteBallotTable.voteId eq abId }.toList()
            val ballots =
                ballotRows.map {
                    Ballot(
                        memberId = it[VoteBallotTable.memberId],
                        optionId = it[VoteBallotTable.optionId],
                        stake = it[VoteBallotTable.stakeLtr],
                    )
                }
            val settlement = computeVickreySettlement(ballots = ballots, optionIds = optionIds)
            val now = nowLocalDateTime()

            ballotRows.forEach { row ->
                val ballotId = row[VoteBallotTable.id]
                val memberId = row[VoteBallotTable.memberId]
                val settled = settlement.charges[memberId] ?: ZERO_LTR
                VoteBallotTable.update({ VoteBallotTable.id eq ballotId }) {
                    it[settledLtr] = settled
                }
            }

            val resolutionStatus =
                when (val winnerId = settlement.winnerOptionId) {
                    null -> ResolutionStatus.POSTPONED
                    else -> {
                        val winnerLabel = labelByOptionId.getValue(winnerId)
                        if (winnerLabel.equals("NO", ignoreCase = true)) ResolutionStatus.REJECTED else ResolutionStatus.ADOPTED
                    }
                }
            // V1.9.54: an adopted amendment rewrites the target's text, so the target must not be in the middle
            // of its own decision path (e.g. an ADVISORY consensus running on it) -- same guard as resolveMotion.
            if (resolutionStatus == ResolutionStatus.ADOPTED && amendsPeek != null) {
                MotionDecisionLock.requireNoRunningDecisionPath(amendsPeek)
            }
            val (votesYes, votesNo) =
                if (optionRows.size == 2) {
                    val yes = ballotRows.count { labelByOptionId[it[VoteBallotTable.optionId]].equals("YES", ignoreCase = true) }
                    val no = ballotRows.count { labelByOptionId[it[VoteBallotTable.optionId]].equals("NO", ignoreCase = true) }
                    yes to no
                } else {
                    0 to 0
                }

            val sId = voteRow[VoteTable.meetingId]
            val meeting = loadMeeting(sId)
            // Änderungsantrag (V0.2.6): current working text, not the immutable original -- same
            // as resolveMotion, see MotionDto.effectiveText KDoc.
            val effectiveText = motionRow[MotionTable.currentText] ?: motionRow[MotionTable.text]
            val resolutionInput =
                ResolutionInput(
                    agendaItemId = motionRow[MotionTable.agendaItemId]?.toString(),
                    title = motionRow[MotionTable.title],
                    text = effectiveText,
                    votesYes = votesYes,
                    votesNo = votesNo,
                    votesAbstain = 0,
                    status = resolutionStatus,
                )
            val resolution =
                insertResolutionRow(
                    sId = sId,
                    committeeId = committeeId,
                    scheduledDate = meeting.scheduledAt.date,
                    input = resolutionInput,
                    current = current,
                    resolutionMode = ResolutionMode.MERITOCRATIC,
                    voteId = abId,
                    // V1.9.201: the bidders of a tender never count on its award motion.
                    excludedMemberIds = tenderConflictedMemberIdsForMotion(motionRow[MotionTable.id]),
                )

            val newMotionStatus =
                when (resolutionStatus) {
                    ResolutionStatus.ADOPTED -> MotionStatus.RESOLVED
                    ResolutionStatus.REJECTED -> MotionStatus.REJECTED
                    ResolutionStatus.POSTPONED -> MotionStatus.POSTPONED
                }
            MotionTable.update({ MotionTable.id eq motionRow[MotionTable.id] }) {
                it[MotionTable.status] = newMotionStatus
                it[MotionTable.resolutionId] = Uuid.parse(resolution.id)
            }

            VoteTable.update({ VoteTable.id eq abId }) {
                it[status] = VoteStatus.CLOSED
                it[closedAt] = now
                it[winnerOptionId] = settlement.winnerOptionId
                it[secondPriceLtr] = settlement.secondPrice
                it[resolutionId] = Uuid.parse(resolution.id)
            }
            // Änderungsantrag (V0.2.6) adoption via the Vickrey path -- same mechanic as
            // resolveMotion, must run BEFORE auditResolutionCreate below (deadlock-avoidance
            // "last write" contract).
            val amendsId = motionRow[MotionTable.amendsMotionId]
            if (amendsId != null && resolutionStatus == ResolutionStatus.ADOPTED) {
                MotionTable.update({ MotionTable.id eq amendsId }) {
                    it[MotionTable.currentText] = motionRow[MotionTable.text]
                }
            }
            // V0.5.3 GoBD audit log: called last, after VoteTable.update, so this satisfies
            // AuditLogRecorder's deadlock-avoidance contract -- see auditResolutionCreate KDoc.
            auditResolutionCreate(resolution = resolution, current = current)
            loadVote(abId)
        }
    }

    override suspend fun abortVote(voteId: String): VoteDto {
        val current = resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction {
            val motionId =
                VoteTable
                    .selectAll()
                    .where { VoteTable.id eq abId }
                    .singleOrNull()
                    ?.get(VoteTable.motionId)
                    ?: throw NotFoundException("Vote $voteId not found")
            // V1.9.23: motion lock first, then the vote row FOR UPDATE -- abort cannot interleave with closeVote.
            val motionRow = MotionDecisionLock.lockMotion(motionId)
            val voteRow =
                VoteTable
                    .selectAll()
                    .where { VoteTable.id eq abId }
                    .forUpdate()
                    .single()
            if (!current.canRecordForMeeting(motionRow[MotionTable.targetCommitteeId])) throw ForbiddenException()
            if (voteRow[VoteTable.status] != VoteStatus.OPEN) {
                throw ConflictException("Vote $voteId is ${voteRow[VoteTable.status]}, expected OPEN")
            }
            VoteTable.update({ VoteTable.id eq abId }) {
                it[status] = VoteStatus.ABORTED
                it[closedAt] = nowLocalDateTime()
            }
            loadVote(abId)
        }
    }

    override suspend fun getVote(voteId: String): VoteDto {
        resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction { loadVote(abId) }
    }

    /**
     * Governance UI wave addition (see [IGovernanceService.listVotes] KDoc for the gap this
     * closes) -- mirrors [listMotions]'s optional-filter/no-role-required shape exactly.
     */
    override suspend fun listVotes(
        motionId: String?,
        status: VoteStatus?,
    ): List<VoteDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (motionId != null) conditions += (VoteTable.motionId eq motionId.toMotionUuid())
            if (status != null) conditions += (VoteTable.status eq status)
            val baseQuery = VoteTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toVoteDto() }
        }
    }

    override suspend fun listVoteBallots(voteId: String): List<VoteBallotDto> {
        resolveCurrentMember(call)
        val abId = voteId.toVoteUuid()
        return transaction {
            VoteTable.selectAll().where { VoteTable.id eq abId }.singleOrNull()
                ?: throw NotFoundException("Vote $voteId not found")
            VoteBallotTable
                .selectAll()
                .where { VoteBallotTable.voteId eq abId }
                .map { it.toVoteBallotDto() }
        }
    }

    /**
     * Änderungsantrag (V0.2.6): a withdrawn or preliminarily-rejected main Motion is procedurally
     * moot to amend -- auto-transitions every still-[NON_TERMINAL_MOTION_STATUSES] amendment of
     * [motionId] to [MotionStatus.WITHDRAWN], reusing the caller's own [now] timestamp. Called
     * from [withdrawMotion] and [reviewMotion] (REJECT branch only); [MotionStatus.POSTPONED]
     * deliberately never calls this -- see both call sites' own comments.
     */
    private fun cascadeWithdrawPendingAmendments(
        motionId: Uuid,
        now: LocalDateTime,
    ) {
        MotionTable.update({
            (MotionTable.amendsMotionId eq motionId) and (MotionTable.status inList NON_TERMINAL_MOTION_STATUSES.toList())
        }) {
            it[MotionTable.status] = MotionStatus.WITHDRAWN
            it[withdrawnAt] = now
        }
    }

    /** V1.9.112 -- writes the frozen electorate of a freshly opened vote (one row per eligible member, batched). Same transaction as the insert. */
    private fun freezeVoteElectorate(
        voteId: Uuid,
        meetingId: Uuid,
        committeeId: Uuid,
        excludedMemberIds: Set<Uuid> = emptySet(),
    ) {
        val meetingRow = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.single()
        // FOR UPDATE: the committee cannot be switched to rule mode between this read and the commit (the switch locks the same row; the
        // motion lock taken first by openVote is never taken by the switch, so there is no lock cycle).
        val committeeRow =
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.id eq committeeId }
                .forUpdate()
                .single()
        // V1.9.201: the bidders of a tender are not part of the frozen circle on its award motion.
        val eligible =
            eligibleMemberIds(committeeRow = committeeRow, scheduledDate = meetingRow[MeetingTable.scheduledAt].date) - excludedMemberIds
        if (eligible.isEmpty()) return
        VoteEligibleVoterTable.batchInsert(eligible.sortedBy { it.toString() }, shouldReturnGeneratedValues = false) { memberId ->
            this[VoteEligibleVoterTable.id] = Uuid.random()
            this[VoteEligibleVoterTable.voteId] = voteId
            this[VoteEligibleVoterTable.memberId] = memberId
        }
    }

    // ---- V1.9.227 "Sitzung in der Videokonferenz": Sitzung <-> Veranstaltung (see MeetingEventLinkOperations) ----

    override suspend fun announceMeetingAsEvent(meetingId: String): LinkedEventRef =
        meetingEventLinks.announce(current = resolveCurrentMember(call), meetingId = meetingId.toMeetingUuid())

    override suspend fun linkMeetingToEvent(
        meetingId: String,
        eventId: String,
    ): LinkedEventRef =
        meetingEventLinks.link(
            current = resolveCurrentMember(call),
            meetingId = meetingId.toMeetingUuid(),
            eventId = eventId.toMeetingUuid(),
        )

    override suspend fun unlinkMeetingEvent(
        meetingId: String,
        eventId: String,
    ) = meetingEventLinks.unlink(
        current = resolveCurrentMember(call),
        meetingId = meetingId.toMeetingUuid(),
        eventId = eventId.toMeetingUuid(),
    )

    override suspend fun alignEventToMeeting(
        meetingId: String,
        eventId: String,
    ): LinkedEventRef =
        meetingEventLinks.align(
            current = resolveCurrentMember(call),
            meetingId = meetingId.toMeetingUuid(),
            eventId = eventId.toMeetingUuid(),
        )

    override suspend fun listLinkableMeetings(eventId: String): List<MeetingDto> =
        meetingEventLinks.listLinkable(current = resolveCurrentMember(call), eventId = eventId.toMeetingUuid())

    // ---- V1.9.236 "Sitzungsmodus im Konferenzraum" (see MeetingSessionOperations) ----

    override suspend fun getConferenceMeetingState(roomId: String): ConferenceMeetingStateAnswer =
        ConferenceMeetingStateAnswer(
            state = meetingSessions.getState(current = resolveCurrentMember(call), roomId = runCatching { Uuid.parse(roomId) }.getOrNull()),
        )

    override suspend fun openConferenceMeeting(
        roomId: String,
        meetingId: String,
    ): ConferenceMeetingStateDto =
        meetingSessions.open(current = resolveCurrentMember(call), roomId = roomId.toMeetingUuid(), meetingId = meetingId.toMeetingUuid())

    override suspend fun setConferenceAttendance(
        roomId: String,
        meetingId: String,
        input: SessionAttendanceInput,
    ): ConferenceMeetingStateDto =
        meetingSessions.setAttendance(
            current = resolveCurrentMember(call),
            roomId = roomId.toMeetingUuid(),
            meetingId = meetingId.toMeetingUuid(),
            input = input,
        )

    override suspend fun adoptConnectedAttendance(
        roomId: String,
        meetingId: String,
    ): Int =
        meetingSessions.adoptConnected(
            current = resolveCurrentMember(call),
            roomId = roomId.toMeetingUuid(),
            meetingId = meetingId.toMeetingUuid(),
        )

    override suspend fun establishConferenceQuorum(
        roomId: String,
        meetingId: String,
        acknowledgeNotMet: Boolean,
    ): ConferenceMeetingStateDto =
        meetingSessions.establishQuorum(
            current = resolveCurrentMember(call),
            roomId = roomId.toMeetingUuid(),
            meetingId = meetingId.toMeetingUuid(),
            acknowledgeNotMet = acknowledgeNotMet,
        )

    override suspend fun moveConferenceAgenda(
        roomId: String,
        meetingId: String,
        fromAgendaItemId: String?,
        toAgendaItemId: String,
    ): ConferenceMeetingStateDto =
        meetingSessions.moveAgenda(
            current = resolveCurrentMember(call),
            roomId = roomId.toMeetingUuid(),
            meetingId = meetingId.toMeetingUuid(),
            fromAgendaItemId = fromAgendaItemId,
            toAgendaItemId = toAgendaItemId,
        )

    override suspend fun attachConferenceMotion(
        roomId: String,
        meetingId: String,
        motionId: String,
        expectedAgendaItemId: String,
    ): ConferenceMeetingStateDto =
        meetingSessionMotions.attach(
            current = resolveCurrentMember(call),
            roomId = roomId.toMeetingUuid(),
            meetingId = meetingId.toMeetingUuid(),
            motionId = motionId,
            expectedAgendaItemId = expectedAgendaItemId,
        )

    override suspend fun openConferenceMotionVote(
        roomId: String,
        meetingId: String,
        motionId: String,
        expectedAgendaItemId: String,
        secret: Boolean,
        electionBoardMemberIds: List<String>,
    ): ConferenceMeetingStateDto =
        meetingSessionMotions.openVote(
            current = resolveCurrentMember(call),
            roomId = roomId.toMeetingUuid(),
            meetingId = meetingId.toMeetingUuid(),
            motionId = motionId,
            expectedAgendaItemId = expectedAgendaItemId,
            secret = secret,
            boardMemberIds = electionBoardMemberIds,
        )

    /** Adds the Veranstaltung link to authenticated reads; the public API path (`GovernanceReads`) never does. Inside a transaction. */
    private fun withLinkedEvents(
        current: CurrentMember,
        meetings: List<MeetingDto>,
    ): List<MeetingDto> {
        val refs =
            MeetingEventLinkStore.linkedEventRefs(
                meetings = meetings,
                caller = current,
                wallNow = OrganizationTimeZone.wallNowOf(DbClock.nowLocalDateTime()),
            )
        if (refs.isEmpty()) return meetings
        return meetings.map { meeting -> refs[meeting.id]?.let { meeting.copy(linkedEvent = it) } ?: meeting }
    }

    private fun loadMeeting(id: Uuid): MeetingDto =
        (MeetingTable innerJoin CommitteeTable)
            .selectAll()
            .where { MeetingTable.id eq id }
            .singleOrNull()
            ?.toMeetingDto()
            ?: throw NotFoundException("Meeting $id not found")

    private fun requireMeetingCommitteeId(meetingId: Uuid): Uuid =
        MeetingTable
            .selectAll()
            .where { MeetingTable.id eq meetingId }
            .singleOrNull()
            ?.get(MeetingTable.committeeId)
            ?: throw NotFoundException("Meeting $meetingId not found")

    private fun loadAgenda(meetingId: Uuid): List<AgendaItemDto> =
        AgendaItemTable
            .selectAll()
            .where { AgendaItemTable.meetingId eq meetingId }
            .orderBy(AgendaItemTable.position, SortOrder.ASC)
            .map { it.toAgendaItemDto() }

    private fun loadAttendance(meetingId: Uuid): List<AttendanceDto> =
        AttendanceTable
            .selectAll()
            .where { AttendanceTable.meetingId eq meetingId }
            .map { it.toAttendanceDto() }

    private fun loadResolutions(meetingId: Uuid): List<ResolutionDto> =
        ResolutionTable
            .selectAll()
            .where { ResolutionTable.meetingId eq meetingId }
            .toList()
            .toResolutionDtos()

    private fun loadMotion(id: Uuid): MotionDto =
        (MotionTable innerJoin CommitteeTable)
            .selectAll()
            .where { MotionTable.id eq id }
            .singleOrNull()
            ?.toMotionDto()
            ?: throw NotFoundException("Motion $id not found")

    private fun loadVote(id: Uuid): VoteDto =
        VoteTable
            .selectAll()
            .where { VoteTable.id eq id }
            .singleOrNull()
            ?.toVoteDto()
            ?: throw NotFoundException("Vote $id not found")

    /**
     * Shared insert path for [addAgendaItem] and [scheduleMotion] (V0.2.2) — the latter
     * populates [AgendaItemInput] from the Motion (`title`/`rationale`/submitter) rather
     * than duplicating the position-collision check and insert logic. Must run inside an
     * already-open `transaction {}` (both call sites do).
     */
    private fun insertAgendaItem(
        sId: Uuid,
        input: AgendaItemInput,
    ): AgendaItemDto {
        val positionTaken =
            AgendaItemTable
                .selectAll()
                .where {
                    (AgendaItemTable.meetingId eq sId) and (AgendaItemTable.position eq input.position)
                }.count() > 0
        if (positionTaken) throw ConflictException("Position ${input.position} already used for Meeting $sId")
        val id = Uuid.random()
        AgendaItemTable.insert {
            it[AgendaItemTable.id] = id
            it[AgendaItemTable.meetingId] = sId
            it[position] = input.position
            it[title] = input.title
            it[description] = input.description
            it[presenterMemberId] = input.presenterMemberId?.let(Uuid::parse)
        }
        return AgendaItemTable
            .selectAll()
            .where { AgendaItemTable.id eq id }
            .single()
            .toAgendaItemDto()
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    /**
     * Follow-up-queries the options ([VoteOptionTable]) and their computed basket totals
     * (summed from [VoteBallotTable], never stored) for this Vote — same
     * "simple-transaction" style as [memberDisplayName], not an optimized single join.
     */
    private fun ResultRow.toVoteDto(): VoteDto {
        val voteId = this[VoteTable.id]
        val stakesByOption =
            VoteBallotTable
                .selectAll()
                .where { VoteBallotTable.voteId eq voteId }
                .groupBy({ it[VoteBallotTable.optionId] }, { it[VoteBallotTable.stakeLtr] })
                .mapValues { (_, stakes) -> stakes.fold(ZERO_LTR) { acc, stake -> acc + stake } }
        val options =
            VoteOptionTable
                .selectAll()
                .where { VoteOptionTable.voteId eq voteId }
                .orderBy(VoteOptionTable.position, SortOrder.ASC)
                .map { optRow ->
                    val optionId = optRow[VoteOptionTable.id]
                    VoteOptionDto(
                        id = optionId.toString(),
                        voteId = voteId.toString(),
                        label = optRow[VoteOptionTable.label],
                        position = optRow[VoteOptionTable.position],
                        basketTotalLtr = stakesByOption[optionId] ?: ZERO_LTR,
                    )
                }
        return VoteDto(
            id = voteId.toString(),
            motionId = this[VoteTable.motionId].toString(),
            meetingId = this[VoteTable.meetingId].toString(),
            title = this[VoteTable.title],
            status = this[VoteTable.status],
            options = options,
            winnerOptionId = this[VoteTable.winnerOptionId]?.toString(),
            secondPriceLtr = this[VoteTable.secondPriceLtr],
            openedById = this[VoteTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[VoteTable.openedBy]).orEmpty(),
            openedAt = this[VoteTable.openedAt],
            closedAt = this[VoteTable.closedAt],
            resolutionId = this[VoteTable.resolutionId]?.toString(),
        )
    }

    private fun ResultRow.toVoteBallotDto(): VoteBallotDto =
        VoteBallotDto(
            id = this[VoteBallotTable.id].toString(),
            voteId = this[VoteBallotTable.voteId].toString(),
            optionId = this[VoteBallotTable.optionId].toString(),
            memberId = this[VoteBallotTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[VoteBallotTable.memberId]).orEmpty(),
            stakeLtr = this[VoteBallotTable.stakeLtr],
            settledLtr = this[VoteBallotTable.settledLtr],
            castAt = this[VoteBallotTable.castAt],
        )

    private fun String.toCommitteeUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMemberUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMeetingUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMembershipUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toAgendaItemUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMotionUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toVoteUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toVoteOptionUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}

/**
 * V1.3.1 "API-Fundament, lesend" -- hoisted from a `private` member of [GovernanceService] to a
 * top-level, `internal` function so [GovernanceReads] can reuse it too, without duplicating the
 * lookup. Kotlin resolves same-package top-level declarations without an import, so every call
 * site inside [GovernanceService] itself (unchanged, still unqualified `memberDisplayName(...)`)
 * keeps compiling exactly as before this extraction -- purely a visibility/location change, zero
 * behavior change.
 */
internal fun memberDisplayName(memberId: Uuid?): String? =
    memberId?.let { id ->
        MemberTable
            .selectAll()
            .where { MemberTable.id eq id }
            .singleOrNull()
            ?.get(MemberTable.displayName)
    }

/** V1.3.1 -- hoisted to top-level, `internal`, see [memberDisplayName] KDoc for the reasoning. */
internal fun ResultRow.toCommitteeDto(revealRule: Boolean = false): CommitteeDto =
    CommitteeDto(
        id = this[CommitteeTable.id].toString(),
        name = this[CommitteeTable.name],
        type = this[CommitteeTable.type],
        description = this[CommitteeTable.description],
        active = this[CommitteeTable.active],
        quorumPercent = this[CommitteeTable.quorumPercent],
        createdAt = this[CommitteeTable.createdAt],
        membershipMode = this[CommitteeTable.membershipMode],
        // V1.9.112: the rule only ever reaches BOARD/ADMIN callers (the public REST API never calls with revealRule)
        ruleJson = if (revealRule) this[CommitteeTable.ruleJson] else null,
    )

/** V1.3.1 -- hoisted to top-level, `internal`, see [memberDisplayName] KDoc for the reasoning. */
internal fun ResultRow.toCommitteeMembershipDto(revealAutoEndReason: Boolean = false): CommitteeMembershipDto =
    CommitteeMembershipDto(
        id = this[CommitteeMembershipTable.id].toString(),
        committeeId = this[CommitteeMembershipTable.committeeId].toString(),
        memberId = this[CommitteeMembershipTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        role = this[CommitteeMembershipTable.role],
        since = this[CommitteeMembershipTable.since],
        until = this[CommitteeMembershipTable.until],
        // V1.9.102 -- the reason can be DECEASED/DONOR (personal data): only BOARD/ADMIN callers get it, everybody else sees null.
        autoEndReason = if (revealAutoEndReason) this[CommitteeMembershipTable.autoEndReason] else null,
    )

/**
 * V1.3.1 -- hoisted to top-level, `internal`, PLUS the S3 N+1-fix: [resolveMemberNames] (default
 * `true`, keeping every pre-existing call site -- `toMeetingDto()` without arguments -- byte-
 * identical) gates the three follow-up [memberDisplayName] lookups this mapper otherwise always
 * performs (called-by/chair/minute-taker, up to 3 extra queries PER ROW). [GovernanceReads] is the
 * only caller that ever passes `false` -- the REST `/api/v1/meetings`/`/api/v1/meetings/{id}`
 * surface never exposes these display-name fields (see `PublicApiMeetingDto`), so resolving them
 * for a page of meetings would be pure wasted work, not merely an unused field.
 */
internal fun ResultRow.toMeetingDto(resolveMemberNames: Boolean = true): MeetingDto =
    MeetingDto(
        id = this[MeetingTable.id].toString(),
        committeeId = this[MeetingTable.committeeId].toString(),
        committeeName = this[CommitteeTable.name],
        title = this[MeetingTable.title],
        scheduledAt = this[MeetingTable.scheduledAt],
        location = this[MeetingTable.location],
        format = this[MeetingTable.format],
        status = this[MeetingTable.status],
        calledById = this[MeetingTable.calledBy]?.toString(),
        calledByDisplayName = if (resolveMemberNames) memberDisplayName(this[MeetingTable.calledBy]) else null,
        calledAt = this[MeetingTable.calledAt],
        chairMemberId = this[MeetingTable.chairMemberId]?.toString(),
        chairDisplayName = if (resolveMemberNames) memberDisplayName(this[MeetingTable.chairMemberId]) else null,
        minuteTakerMemberId = this[MeetingTable.minuteTakerMemberId]?.toString(),
        minuteTakerDisplayName = if (resolveMemberNames) memberDisplayName(this[MeetingTable.minuteTakerMemberId]) else null,
        protocolDocumentId = this[MeetingTable.protocolDocumentId]?.toString(),
        createdAt = this[MeetingTable.createdAt],
    )

/** V1.3.1 -- hoisted to top-level, `internal`, see [memberDisplayName] KDoc for the reasoning. */
internal fun ResultRow.toAgendaItemDto(): AgendaItemDto =
    AgendaItemDto(
        id = this[AgendaItemTable.id].toString(),
        meetingId = this[AgendaItemTable.meetingId].toString(),
        position = this[AgendaItemTable.position],
        title = this[AgendaItemTable.title],
        description = this[AgendaItemTable.description],
        presenterMemberId = this[AgendaItemTable.presenterMemberId]?.toString(),
        presenterDisplayName = memberDisplayName(this[AgendaItemTable.presenterMemberId]),
        protocolApproval = ProtocolApprovalItems.dto(item = this, editable = false),
    )

/** V1.3.1 -- hoisted to top-level, `internal`, see [memberDisplayName] KDoc for the reasoning. */
internal fun ResultRow.toAttendanceDto(): AttendanceDto =
    AttendanceDto(
        id = this[AttendanceTable.id].toString(),
        meetingId = this[AttendanceTable.meetingId].toString(),
        memberId = this[AttendanceTable.memberId].toString(),
        memberDisplayName = memberDisplayName(this[AttendanceTable.memberId]).orEmpty(),
        status = this[AttendanceTable.status],
        representedByMemberId = this[AttendanceTable.representedByMemberId]?.toString(),
        representedByDisplayName = memberDisplayName(this[AttendanceTable.representedByMemberId]),
        note = this[AttendanceTable.note],
        recordedAt = this[AttendanceTable.recordedAt],
    )

/**
 * V1.3.1 -- hoisted to top-level, `internal`, no new parameter (unlike [toMeetingDto]): the REST
 * `/api/v1/motions` surface already drops `submitterDisplayName`/`reviewedByDisplayName` from its
 * own [network.lapis.cloud.server.routes.PublicApiMotionDto] shape, but `submitterDisplayName` is
 * NOT nullable on [MotionDto] itself (`.orEmpty()`), so skipping the lookup would change this
 * mapper's return type contract for every OTHER (RPC) caller too. See the implementation plan §5.1
 * for why the resulting N+1 here is deliberately left unfixed this wave (smaller blast radius than
 * [toMeetingDto]'s, and `/api/v1/motions` is typically filtered to one Committee).
 */
internal fun ResultRow.toMotionDto(): MotionDto =
    MotionDto(
        id = this[MotionTable.id].toString(),
        targetCommitteeId = this[MotionTable.targetCommitteeId].toString(),
        targetCommitteeName = this[CommitteeTable.name],
        targetCommitteeType = this[CommitteeTable.type],
        title = this[MotionTable.title],
        rationale = this[MotionTable.rationale],
        text = this[MotionTable.text],
        submitterMemberId = this[MotionTable.submitterMemberId].toString(),
        submitterDisplayName = memberDisplayName(this[MotionTable.submitterMemberId]).orEmpty(),
        status = this[MotionTable.status],
        submittedAt = this[MotionTable.submittedAt],
        reviewedById = this[MotionTable.reviewedBy]?.toString(),
        reviewedByDisplayName = memberDisplayName(this[MotionTable.reviewedBy]),
        reviewedAt = this[MotionTable.reviewedAt],
        reviewNote = this[MotionTable.reviewNote],
        meetingId = this[MotionTable.meetingId]?.toString(),
        agendaItemId = this[MotionTable.agendaItemId]?.toString(),
        resolutionId = this[MotionTable.resolutionId]?.toString(),
        amendsMotionId = this[MotionTable.amendsMotionId]?.toString(),
        currentText = this[MotionTable.currentText],
        effectiveText = this[MotionTable.currentText] ?: this[MotionTable.text],
    )

/**
 * Core of [GovernanceService.endCommitteeMembership] (the MANUAL path) -- ends [membershipRow] (an already-loaded
 * [CommitteeMembershipTable] row): sets `until`, resets `auto_end_reason` to null (a seat an administrator ends or re-ends by hand is no
 * longer an automatic end, and the actor is a human again) and, if the row's Committee is EXECUTIVE_BOARD, cascades onto
 * [BoardMembershipTable] + the GoBD audit log exactly as [GovernanceService.endCommitteeMembership] has always done -- see that method's
 * own KDoc for the §20 GwG Vorstandsaenderung reasoning it mirrors from [GovernanceService.addCommitteeMember].
 * Transaction-free by contract, same as [BoardMembershipEvents] -- must run inside the caller's own already-open `transaction {}`.
 *
 * The AUTOMATIC end on a member status change lives in [CommitteeSeatLifecycle] (V1.9.102); both share [cascadeExecutiveBoardEnd], so
 * there is exactly one copy of the EXECUTIVE_BOARD cascade.
 */
internal fun endCommitteeMembershipRow(
    membershipRow: ResultRow,
    until: LocalDate,
    current: CurrentMember,
) {
    val id = membershipRow[CommitteeMembershipTable.id]
    val updated =
        CommitteeMembershipTable.update({ CommitteeMembershipTable.id eq id }) {
            it[CommitteeMembershipTable.until] = until
            it[autoEndReason] = null
            // V1.9.103: a seat an administrator ends by hand is no longer "ended by an election" either (the CHECK allows only one cause).
            it[endedByElectionId] = null
        }
    if (updated == 0) throw NotFoundException("CommitteeMembership $id not found")
    cascadeExecutiveBoardEnd(
        committeeId = membershipRow[CommitteeMembershipTable.committeeId],
        memberId = membershipRow[CommitteeMembershipTable.memberId],
        until = until,
        now = DbClock.nowLocalDateTime(),
        actorMemberId = current.memberId,
        actorRole = current.role,
    )?.write?.invoke()
}

/**
 * The EXECUTIVE_BOARD cascade of ending a committee seat (§20 GwG Vorstandsaenderung): if [committeeId] is an EXECUTIVE_BOARD committee and
 * [memberId] has an open [BoardMembershipTable] row, ends it via [BoardMembershipEvents.recordBoardLeave] and returns the prepared GoBD
 * audit entry as a [PendingAudit] (null if there was nothing to cascade). The caller executes it -- the manual path immediately, the
 * automatic path ([CommitteeSeatLifecycle]) at the very end of its transaction (audit lock order, see [AuditLogRecorder] KDoc).
 * [actorMemberId]/[actorRole] are null for the system actor of the automatic path.
 */
internal fun cascadeExecutiveBoardEnd(
    committeeId: Uuid,
    memberId: Uuid,
    until: LocalDate,
    now: LocalDateTime,
    actorMemberId: Uuid?,
    actorRole: AccountRole?,
): PendingAudit? {
    val committeeType =
        CommitteeTable
            .selectAll()
            .where { CommitteeTable.id eq committeeId }
            .single()[CommitteeTable.type]
    if (committeeType != CommitteeType.EXECUTIVE_BOARD) return null
    val openBoardMembershipRow =
        BoardMembershipTable
            .selectAll()
            .where { (BoardMembershipTable.memberId eq memberId) and (BoardMembershipTable.endedAt.isNull()) }
            .singleOrNull() ?: return null
    val openBoardMembershipId = openBoardMembershipRow[BoardMembershipTable.id]
    BoardMembershipEvents.recordBoardLeave(boardMembershipId = openBoardMembershipId, endedAt = until, now = now)
    // V0.5.3 GoBD audit log: the caller invokes the returned write as the last locking operation -- see auditBoardMembershipEnd KDoc.
    return PendingAudit {
        auditBoardMembershipEnd(
            boardMembershipId = openBoardMembershipId,
            memberId = openBoardMembershipRow[BoardMembershipTable.memberId],
            committeeRole = openBoardMembershipRow[BoardMembershipTable.committeeRole],
            startedAt = openBoardMembershipRow[BoardMembershipTable.startedAt],
            endedAt = until,
            actorMemberId = actorMemberId,
            actorRole = actorRole,
        )
    }
}

/**
 * V1.9.103 -- seats an election tally started ([CommitteeSeatChangeKind.ELECTION_SEATED]) or ended ([CommitteeSeatChangeKind.ELECTION_ENDED]) in
 * the notice window, newest first, at most [AUTO_END_NOTICE_LIMIT] of each kind. A seat that merely CHANGED ROLE (the same person got a new
 * seat in the same Gremium from the same election) is no "ended by succession": it is not listed as ENDED.
 */
internal fun loadElectionSeatChanges(windowStart: LocalDate): List<CommitteeElectionSeatChangeDto> {
    fun ResultRow.toChange(
        kind: CommitteeSeatChangeKind,
        electionId: Uuid,
        effectiveOn: LocalDate,
    ) = CommitteeElectionSeatChangeDto(
        kind = kind,
        electionId = electionId.toString(),
        committeeId = this[CommitteeTable.id].toString(),
        committeeName = this[CommitteeTable.name],
        membershipId = this[CommitteeMembershipTable.id].toString(),
        memberId = this[CommitteeMembershipTable.memberId].toString(),
        memberDisplayName = this[MemberTable.displayName],
        role = this[CommitteeMembershipTable.role],
        effectiveOn = effectiveOn,
    )

    fun seats() =
        (CommitteeMembershipTable innerJoin CommitteeTable)
            .join(MemberTable, JoinType.INNER, CommitteeMembershipTable.memberId, MemberTable.id)
            .selectAll()
    val seated =
        seats()
            .where {
                CommitteeMembershipTable.startedByElectionId.isNotNull() and (CommitteeMembershipTable.since greaterEq windowStart)
            }.orderBy(CommitteeMembershipTable.since to SortOrder.DESC, CommitteeMembershipTable.id to SortOrder.ASC)
            .limit(AUTO_END_NOTICE_LIMIT)
            .map {
                it.toChange(
                    kind = CommitteeSeatChangeKind.ELECTION_SEATED,
                    electionId = checkNotNull(it[CommitteeMembershipTable.startedByElectionId]),
                    effectiveOn = it[CommitteeMembershipTable.since],
                )
            }
    val endedRows =
        seats()
            .where {
                CommitteeMembershipTable.endedByElectionId.isNotNull() and
                    (CommitteeMembershipTable.until greaterEq windowStart)
            }.orderBy(CommitteeMembershipTable.until to SortOrder.DESC, CommitteeMembershipTable.id to SortOrder.ASC)
            .limit(AUTO_END_NOTICE_LIMIT * 4)
            .toList()
    val roleChanged =
        if (endedRows.isEmpty()) {
            emptySet()
        } else {
            CommitteeMembershipTable
                .selectAll()
                .where {
                    CommitteeMembershipTable.startedByElectionId inList
                        endedRows.mapNotNull { it[CommitteeMembershipTable.endedByElectionId] }.toSet()
                }.map {
                    Triple(
                        it[CommitteeMembershipTable.startedByElectionId],
                        it[CommitteeMembershipTable.committeeId],
                        it[CommitteeMembershipTable.memberId],
                    )
                }.toSet()
        }
    val ended =
        endedRows
            .filter {
                Triple(
                    it[CommitteeMembershipTable.endedByElectionId],
                    it[CommitteeMembershipTable.committeeId],
                    it[CommitteeMembershipTable.memberId],
                ) !in roleChanged
            }.take(AUTO_END_NOTICE_LIMIT)
            .map {
                it.toChange(
                    kind = CommitteeSeatChangeKind.ELECTION_ENDED,
                    electionId = checkNotNull(it[CommitteeMembershipTable.endedByElectionId]),
                    effectiveOn = checkNotNull(it[CommitteeMembershipTable.until]),
                )
            }
    return seated + ended
}
