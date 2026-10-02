package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canCreatePolls
import network.lapis.cloud.server.security.isActiveMemberNow
import network.lapis.cloud.server.security.isCommitteeLeaderAnywhere
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requirePollReader
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollOptionDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.domain.PollSnapshot
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IPollService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 "Umfragen auf LTR-Basis" (Server) -- NON-BINDING opinion polls (Stimmungsbilder),
 * see [IPollService] and `docs/architecture/polls.adoc`. A poll is not a resolution, is bound to no
 * motion/meeting, and moves NO LTR: the responder's free balance is only READ and snapshotted as the
 * response's weight (no ledger entry, no `lockForDebit`).
 *
 * **Anonymity**: `poll_participation` (WHO answered) and `poll_response` (WHAT was answered, with the
 * weight snapshot) share no key, carry random UUIDv4 ids and no time column; single responses never
 * leave the database layer -- results are computed from aggregates ([OptionTally]). Responses are
 * never audited. Residual risk, stated honestly: whoever can read the database or its backups sees
 * option and weight per response and could match weights against the public LTR ledger -- this is a
 * table separation, not cryptography, exactly like the secret elections.
 *
 * **Locking** (one order everywhere, so no deadlock): organization-settings row (create only, the
 * global mutex for the open-poll cap) -> poll row (cast/close/abort) -> audit chain row LAST
 * ([AuditLogRecorder.record] must be the last lock-taking statement of its transaction).
 *
 * **Errors**: authorization first, existence second (no existence oracle); messages never contain
 * member ids.
 */
class PollService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : IPollService {
    override suspend fun createPoll(input: PollCreateInput): PollDto {
        val current = resolveCurrentMember(call)
        return transaction {
            if (!current.canCreatePolls()) throw ForbiddenException()
            val now = nowLocalDateTime()
            val wallNow = wallNow()
            val validated = validateCreateInput(input = input, wallNow = wallNow)

            // Global mutex for the open-poll cap: without it two concurrent creates both read "19 open"
            // and both insert. Taken BEFORE the inserts and the audit row (lock order, see class KDoc).
            OrganizationSettingsTable.selectAll().forUpdate().single()
            val openCount = PollTable.selectAll().where { effectiveOpenPredicate(wallNow) }.count()
            if (openCount >= PollRules.MAX_OPEN_POLLS) {
                throw ConflictException("The maximum of ${PollRules.MAX_OPEN_POLLS} open polls is reached")
            }

            val ownOpenCount =
                PollTable
                    .selectAll()
                    .where { effectiveOpenPredicate(wallNow) and (PollTable.createdBy eq current.memberId) }
                    .count()
            if (ownOpenCount >= PollRules.MAX_OPEN_POLLS_PER_CREATOR) {
                throw ConflictException("You may have at most ${PollRules.MAX_OPEN_POLLS_PER_CREATOR} open polls")
            }
            val zone = ServerClock.zone
            val windowStart = now.toInstant(zone).minus(PollRules.CREATE_RATE_WINDOW_HOURS.hours).toLocalDateTime(zone)
            val recentCount =
                PollTable
                    .selectAll()
                    .where { (PollTable.createdBy eq current.memberId) and (PollTable.createdAt greaterEq windowStart) }
                    .count()
            if (recentCount >= PollRules.MAX_POLLS_CREATED_PER_WINDOW) {
                throw ConflictException("Too many polls created recently, please try again later")
            }

            val pollId = Uuid.random()
            PollTable.insert {
                it[PollTable.id] = pollId
                it[question] = validated.question
                it[description] = validated.description
                it[status] = PollStatus.OPEN
                it[createdBy] = current.memberId
                it[createdAt] = now
                it[closesAt] = validated.closesAt
            }
            validated.options.forEachIndexed { index, optionText ->
                PollOptionTable.insert {
                    it[PollOptionTable.id] = Uuid.random()
                    it[PollOptionTable.pollId] = pollId
                    it[position] = index
                    it[text] = optionText
                }
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.POLL,
                entityId = pollId,
                action = AuditAction.CREATE,
                after =
                    snapshotJson(
                        PollSnapshot(
                            question = validated.question,
                            options = validated.options,
                            status = PollStatus.OPEN.name,
                            closesAt = validated.closesAt,
                            closedAt = null,
                        ),
                    ),
            )
            loadPolls(ids = listOf(pollId), current = current, wallNow = wallNow).single()
        }
    }

    override suspend fun closePoll(pollId: String): PollDto =
        finish(rawPollId = pollId, target = PollStatus.CLOSED, action = AuditAction.UPDATE)

    override suspend fun abortPoll(pollId: String): PollDto =
        finish(rawPollId = pollId, target = PollStatus.ABORTED, action = AuditAction.VOID)

    private fun finish(
        rawPollId: String,
        target: PollStatus,
        action: AuditAction,
    ): PollDto {
        val current = resolveCurrentMember(call)
        return transaction {
            current.requirePollReader()
            val pollId = rawPollId.toPollIdOrNotFound()
            val row = lockPollRow(pollId)
            if (!canManage(current = current, createdBy = row[PollTable.createdBy])) throw ForbiddenException()
            val now = nowLocalDateTime()
            val wallNow = wallNow()
            if (row.effectivePollStatus(wallNow) != PollStatus.OPEN) throw ConflictException("Poll is not open")
            val before = snapshotOf(row = row, effective = PollStatus.OPEN, closedAt = null)

            PollTable.update({ PollTable.id eq pollId }) {
                it[status] = target
                it[closedAt] = now
                it[closedBy] = current.memberId
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.POLL,
                entityId = pollId,
                action = action,
                before = snapshotJson(before),
                after = snapshotJson(before.copy(status = target.name, closedAt = now)),
            )
            loadPolls(ids = listOf(pollId), current = current, wallNow = wallNow).single()
        }
    }

    override suspend fun getPoll(pollId: String): PollDto {
        val current = resolveCurrentMember(call)
        return transaction {
            current.requirePollReader()
            val id = pollId.toPollIdOrNotFound()
            loadPolls(ids = listOf(id), current = current, wallNow = wallNow()).singleOrNull()
                ?: throw NotFoundException("Poll not found")
        }
    }

    override suspend fun listPolls(
        status: PollStatus?,
        limit: Int,
        offset: Int,
    ): List<PollDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            current.requirePollReader()
            if (offset < 0 || offset > PollRules.MAX_LIST_OFFSET) throw BadRequestException("Invalid offset")
            val wallNow = wallNow()
            val filter: Op<Boolean> = if (status == null) Op.TRUE else effectiveStatusPredicate(status = status, wallNow = wallNow)
            val ids =
                PollTable
                    .selectAll()
                    .where { filter }
                    .orderBy(PollTable.createdAt to SortOrder.DESC, PollTable.id to SortOrder.ASC)
                    .limit(limit.coerceIn(1, PollRules.MAX_LIST_LIMIT))
                    .offset(offset.toLong())
                    .map { it[PollTable.id] }
            loadPolls(ids = ids, current = current, wallNow = wallNow)
        }
    }

    override suspend fun getPollResult(pollId: String): PollResultDto {
        val current = resolveCurrentMember(call)
        return transaction {
            current.requirePollReader()
            val id = pollId.toPollIdOrNotFound()
            val row = PollTable.selectAll().where { PollTable.id eq id }.singleOrNull() ?: throw NotFoundException("Poll not found")
            // ABORTED is NOT closed: an aborted poll's responses are never disclosed.
            if (row.effectivePollStatus(wallNow()) != PollStatus.CLOSED) throw ConflictException("Poll is not closed")

            val optionIds =
                PollOptionTable
                    .selectAll()
                    .where { PollOptionTable.pollId eq id }
                    .orderBy(PollOptionTable.position to SortOrder.ASC)
                    .map { it[PollOptionTable.id] }
            // Aggregates only: single responses (and their weights) never leave the database layer.
            val responseCount = PollResponseTable.id.count()
            val weightSum = PollResponseTable.weightLtr.sum()
            val perOption =
                PollResponseTable
                    .select(PollResponseTable.optionId, responseCount, weightSum)
                    .where { PollResponseTable.pollId eq id }
                    .groupBy(PollResponseTable.optionId)
                    .associate { it[PollResponseTable.optionId] to (it[responseCount].toInt() to (it[weightSum] ?: BigDecimal.ZERO)) }
            val weightedCount = PollResponseTable.id.count()
            val perOptionWeighted =
                PollResponseTable
                    .select(PollResponseTable.optionId, weightedCount)
                    .where { (PollResponseTable.pollId eq id) and (PollResponseTable.weightLtr greater BigDecimal.ZERO) }
                    .groupBy(PollResponseTable.optionId)
                    .associate { it[PollResponseTable.optionId] to it[weightedCount].toInt() }
            computePollResult(
                pollId = id,
                optionsInPositionOrder =
                    optionIds.map { optionId ->
                        OptionTally(
                            optionId = optionId,
                            responses = perOption[optionId]?.first ?: 0,
                            weightedResponses = perOptionWeighted[optionId] ?: 0,
                            weightSum = perOption[optionId]?.second ?: BigDecimal.ZERO,
                        )
                    },
            )
        }
    }

    override suspend fun canCreatePolls(): Boolean {
        val current = resolveCurrentMember(call)
        // Read-only and no audit entry. A federated guest is a GUEST row of MemberTable, so isActiveMemberNow() is false for it.
        return transaction { current.canCreatePolls() }
    }

    override suspend fun getPollParticipation(pollId: String): PollParticipationDto {
        val current = resolveCurrentMember(call)
        return transaction {
            current.requirePollReader()
            val id = pollId.toPollIdOrNotFound()
            val row = PollTable.selectAll().where { PollTable.id eq id }.singleOrNull() ?: throw NotFoundException("Poll not found")
            PollOwnParticipation
                .load(
                    pollRows = listOf(row),
                    memberId = current.memberId,
                    eligible = current.isActiveMemberNow(),
                    wallNow = wallNow(),
                ).getValue(id)
        }
    }

    override suspend fun listPollParticipations(pollIds: List<String>): List<PollParticipationDto> {
        val current = resolveCurrentMember(call)
        return transaction {
            current.requirePollReader()
            if (pollIds.size > PollRules.MAX_PARTICIPATION_BATCH) {
                throw ConflictException("At most ${PollRules.MAX_PARTICIPATION_BATCH} polls per request")
            }
            val ids = pollIds.map { it.toPollIdOrNotFound() }.distinct()
            if (ids.isEmpty()) return@transaction emptyList()
            val rows = PollTable.selectAll().where { PollTable.id inList ids }.toList()
            val byId =
                PollOwnParticipation.load(
                    pollRows = rows,
                    memberId = current.memberId,
                    eligible = current.isActiveMemberNow(),
                    wallNow = wallNow(),
                )
            // Unknown ids are left out; the request's order is kept.
            ids.mapNotNull { byId[it] }
        }
    }

    override suspend fun castPollResponse(input: PollResponseInput): PollParticipationDto {
        val current = resolveCurrentMember(call)
        return transaction {
            // FIRST, before anything about the poll is looked at: GUEST/FRIEND/federated guests and
            // non-active members get Forbidden regardless of whether the poll exists (no oracle).
            requireActiveMembership(memberId = current.memberId)
            val pollId = input.pollId.toPollIdOrNotFound()
            val optionId = input.optionId.toOptionIdOrBadRequest()
            // Poll row lock: cast and close/abort are mutually exclusive, so no response can land after
            // closed_at, and concurrent casts of one member serialise. `now` is taken AFTER the lock.
            val pollRow = lockPollRow(pollId)
            val now = nowLocalDateTime()
            if (pollRow.effectivePollStatus(wallNow()) != PollStatus.OPEN) throw ConflictException("Poll is not open")
            val optionBelongs =
                PollOptionTable
                    .selectAll()
                    .where { (PollOptionTable.id eq optionId) and (PollOptionTable.pollId eq pollId) }
                    .limit(1)
                    .any()
            if (!optionBelongs) throw BadRequestException("Invalid option")

            val alreadyResponded =
                PollParticipationTable
                    .selectAll()
                    .where { (PollParticipationTable.pollId eq pollId) and (PollParticipationTable.memberId eq current.memberId) }
                    .limit(1)
                    .any()
            if (alreadyResponded) throw ConflictException("Already responded")
            // NOTE: no ConflictException may be thrown inside the try -- it would be fine here (only
            // ExposedSQLException is caught) but keeping the try minimal makes that obvious.
            // Weight = the balance AS OF the poll's creation instant (not the live balance), clamped to >= 0.
            // LTR received by transfer after the poll opened therefore cannot be counted a second time by
            // the recipient. Read only: no ledger entry, no lockForDebit, no audit entry for the response.
            val weight =
                ltrBalanceProvider
                    .balanceAsOf(memberId = current.memberId, asOf = pollRow[PollTable.createdAt])
                    .max(BigDecimal.ZERO)
                    .setScale(2)
            try {
                PollParticipationTable.insert {
                    it[PollParticipationTable.id] = Uuid.random()
                    it[PollParticipationTable.pollId] = pollId
                    it[PollParticipationTable.memberId] = current.memberId
                }
                PollResponseTable.insert {
                    it[PollResponseTable.id] = Uuid.random()
                    it[PollResponseTable.pollId] = pollId
                    it[PollResponseTable.optionId] = optionId
                    it[weightLtr] = weight
                }
            } catch (e: ExposedSQLException) {
                // The UNIQUE(poll_id, member_id) index is the real backstop behind the pre-check above.
                // Only a unique violation (SQLState 23505) means "already responded"; anything else is a
                // real failure and must not be masked.
                if (e.sqlState != UNIQUE_VIOLATION_SQLSTATE) throw e
                throw ConflictException("Already responded")
            }
            PollParticipationDto(pollId = pollId.toString(), eligible = true, hasResponded = true, canRespond = false)
        }
    }

    // ------------------------------------------------------------------------------------------------

    private class ValidatedCreate(
        val question: String,
        val description: String?,
        val options: List<String>,
        val closesAt: LocalDateTime?,
    )

    private fun validateCreateInput(
        input: PollCreateInput,
        wallNow: LocalDateTime,
    ): ValidatedCreate {
        // Bound the work before any per-element processing.
        if (input.options.size > PollRules.MAX_OPTIONS) {
            throw BadRequestException("A poll has at most ${PollRules.MAX_OPTIONS} options")
        }
        val question = PollRules.normalizeText(input.question)
        if (question.isEmpty() || question.length > PollRules.MAX_QUESTION_LENGTH || question.any { it.isISOControl() }) {
            throw BadRequestException("The question must have 1..${PollRules.MAX_QUESTION_LENGTH} characters")
        }
        val rawDescription = input.description?.trim().orEmpty()
        if (rawDescription.length > PollRules.MAX_DESCRIPTION_LENGTH ||
            rawDescription.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        ) {
            throw BadRequestException("The description must have at most ${PollRules.MAX_DESCRIPTION_LENGTH} characters")
        }
        val options = input.options.map { PollRules.normalizeText(it) }
        if (options.size < PollRules.MIN_OPTIONS) {
            throw BadRequestException("A poll needs at least ${PollRules.MIN_OPTIONS} options")
        }
        options.forEach {
            if (it.isEmpty() || it.length > PollRules.MAX_OPTION_LENGTH || it.any { c -> c.isISOControl() }) {
                throw BadRequestException("Each option must have 1..${PollRules.MAX_OPTION_LENGTH} characters")
            }
        }
        if (options.map { PollRules.optionKey(it) }.toSet().size != options.size) {
            throw BadRequestException("Options must be distinct")
        }
        val closesAt = input.closesAt?.truncatedToDbPrecision()
        if (closesAt != null) {
            // closesAt is a wall-clock in the organization zone, so the window is measured on that wall-clock
            // too (the lead/max spans are added as instants in that zone, so a DST change inside the span is honoured).
            val zone = OrganizationTimeZone.current()
            val earliest = wallNow.toInstant(zone).plus(PollRules.MIN_DEADLINE_LEAD_MINUTES.minutes).toLocalDateTime(zone)
            val latest = wallNow.toInstant(zone).plus(PollRules.MAX_DEADLINE_DAYS.days).toLocalDateTime(zone)
            if (closesAt < earliest || closesAt > latest) {
                throw BadRequestException(
                    "The deadline must be between ${PollRules.MIN_DEADLINE_LEAD_MINUTES} minutes and " +
                        "${PollRules.MAX_DEADLINE_DAYS} days from now",
                )
            }
        }
        return ValidatedCreate(
            question = question,
            description = rawDescription.ifEmpty { null },
            options = options,
            closesAt = closesAt,
        )
    }

    /** Creator while still creator-capable, or privileged. Runs inside the open transaction. */
    private fun canManage(
        current: CurrentMember,
        createdBy: Uuid,
    ): Boolean = current.isPrivileged || (createdBy == current.memberId && current.canCreatePolls())

    private fun snapshotOf(
        row: ResultRow,
        effective: PollStatus,
        closedAt: LocalDateTime?,
    ): PollSnapshot =
        PollSnapshot(
            question = row[PollTable.question],
            options =
                PollOptionTable
                    .selectAll()
                    .where { PollOptionTable.pollId eq row[PollTable.id] }
                    .orderBy(PollOptionTable.position to SortOrder.ASC)
                    .map { it[PollOptionTable.text] },
            status = effective.name,
            closesAt = row[PollTable.closesAt],
            closedAt = closedAt,
        )

    private fun snapshotJson(snapshot: PollSnapshot): String = Json.encodeToString(PollSnapshot.serializer(), snapshot)

    /**
     * Builds the DTOs for [ids] (order kept, unknown ids dropped) with a constant number of queries:
     * one for the polls, one each for options, creator names and (closed polls only) response counts.
     */
    private fun loadPolls(
        ids: List<Uuid>,
        current: CurrentMember,
        wallNow: LocalDateTime,
    ): List<PollDto> {
        if (ids.isEmpty()) return emptyList()
        val rows = PollTable.selectAll().where { PollTable.id inList ids }.associateBy { it[PollTable.id] }
        val optionsByPoll =
            PollOptionTable
                .selectAll()
                .where { PollOptionTable.pollId inList ids }
                .orderBy(PollOptionTable.position to SortOrder.ASC)
                .groupBy({ it[PollOptionTable.pollId] }) {
                    PollOptionDto(
                        id = it[PollOptionTable.id].toString(),
                        position = it[PollOptionTable.position],
                        text = it[PollOptionTable.text],
                    )
                }
        val orgZone = OrganizationTimeZone.current()
        val creatorIds = rows.values.map { it[PollTable.createdBy] }.distinct()
        val names =
            MemberTable
                .selectAll()
                .where { MemberTable.id inList creatorIds }
                .associate { it[MemberTable.id] to it[MemberTable.displayName] }
        // The response count is disclosed only for effectively CLOSED polls (never while OPEN, never for
        // ABORTED) -- a live counter would let an observer tie a click to a participation.
        val closedIds = rows.values.filter { it.effectivePollStatus(wallNow) == PollStatus.CLOSED }.map { it[PollTable.id] }
        val counts: Map<Uuid, Int> =
            if (closedIds.isEmpty()) {
                emptyMap()
            } else {
                val countExpr = PollResponseTable.id.count()
                PollResponseTable
                    .select(PollResponseTable.pollId, countExpr)
                    .where { PollResponseTable.pollId inList closedIds }
                    .groupBy(PollResponseTable.pollId)
                    .associate { it[PollResponseTable.pollId] to it[countExpr].toInt() }
            }
        // Computed at most once per call, not once per row.
        val leaderAnywhere by lazy { current.isCommitteeLeaderAnywhere() }
        return ids.mapNotNull { id ->
            val row = rows[id] ?: return@mapNotNull null
            val status = row.effectivePollStatus(wallNow)
            val responseCount = if (status == PollStatus.CLOSED) counts[id] ?: 0 else null
            val creator = row[PollTable.createdBy]
            PollDto(
                id = id.toString(),
                question = row[PollTable.question],
                description = row[PollTable.description],
                options = optionsByPoll[id].orEmpty(),
                status = status,
                createdAt = row[PollTable.createdAt],
                createdByDisplayName = names[creator].orEmpty(),
                closesAt = row[PollTable.closesAt],
                closedAt = row.effectiveClosedAt(wallNow = wallNow, orgZone = orgZone),
                binding = false,
                resultAvailable = responseCount != null && responseCount >= PollRules.MIN_RESPONSES_FOR_RESULT,
                responseCount = responseCount,
                canManage = status == PollStatus.OPEN && (current.isPrivileged || (creator == current.memberId && leaderAnywhere)),
            )
        }
    }

    private fun String.toPollIdOrNotFound(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid Poll id") }

    private fun String.toOptionIdOrBadRequest(): Uuid =
        runCatching {
            Uuid.parse(this)
        }.getOrElse { throw BadRequestException("Invalid option") }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    /** Class-B "now" for comparisons against `closes_at`: the wall-clock in the organization zone. */
    private fun wallNow(): LocalDateTime = ServerClock.nowIn(OrganizationTimeZone.current())
}

/** SQLState of a unique-constraint violation (Postgres and H2). */
private const val UNIQUE_VIOLATION_SQLSTATE = "23505"
