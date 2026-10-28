package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.toResolutionDtos
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.PROTOCOL_BLOCK_MAX_LENGTH
import network.lapis.cloud.shared.domain.PROTOCOL_MAX_TASKS
import network.lapis.cloud.shared.domain.PROTOCOL_TASK_MAX_LENGTH
import network.lapis.cloud.shared.domain.ProtocolAgendaItemDto
import network.lapis.cloud.shared.domain.ProtocolAttendanceDto
import network.lapis.cloud.shared.domain.ProtocolBlockSaveResultDto
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolHeaderDto
import network.lapis.cloud.shared.domain.ProtocolMemberRefDto
import network.lapis.cloud.shared.domain.ProtocolPersonDto
import network.lapis.cloud.shared.domain.ProtocolQuorumDto
import network.lapis.cloud.shared.domain.ProtocolRepresentationDto
import network.lapis.cloud.shared.domain.ProtocolResolutionDto
import network.lapis.cloud.shared.domain.ProtocolTaskDto
import network.lapis.cloud.shared.domain.ProtocolTaskInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.239 -- process-wide limiters (top level: the RPC service is constructed anew for every call). Checked AFTER authentication, before the transaction. */
internal val PROTOCOL_DRAFT_READ_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
internal val PROTOCOL_DRAFT_WRITE_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

/** The most a client may send before sanitising (a generous multiple of the limit; protects the sanitiser from a huge string). */
private const val RAW_INPUT_FACTOR = 4
private const val ID_CHUNK = 500

/**
 * V1.9.239 "Protokollentwurf in der Sitzung" (D1) -- the four operations behind `IProtocolDraftService`. The RPC service only delegates here.
 *
 * **Access.** Through the Sitzung ([MeetingSessionAccess.requireMeeting]), not through the conference room: the minute taker often finishes
 * after the room ended. Whatever the caller must not see (unknown or malformed id, no role in the Sitzung, a guest) is the SAME
 * [NotFoundException], raised before anything else is read. Every role of the Sitzung may read; writing needs LEAD or MINUTES.
 *
 * **State.** A draft exists once somebody wrote ([ProtocolDraftPhase.DRAFT]); reading NEVER creates, writes or locks anything. Writing needs an
 * opened Sitzung that is not cancelled and a draft that is not [ProtocolDraftPhase.LOCKED]. This is a deliberate exception to "CLOSED is
 * read-only" of the conference room: after the Sitzung was held the Protokoll may still be written until a later wave (D2) locks it.
 *
 * **Derived at read.** Header, attendance, quorum and resolutions are not copied into the draft; they are derived from the stored rows when
 * the draft is read. That is acceptable only because there is no finalisation yet: the snapshot of D2 is taken exactly at the transition
 * DRAFT -> LOCKED. Attendance comes from the attendance table only (confirmed entries), never from the raw "connected" suggestions.
 *
 * **Concurrency.** Writes lock the Sitzung row `FOR UPDATE` first; a text carries a version and a stale save is an ordinary answer (not an
 * exception). The audit entry is the LAST lock-taking step. **Privacy.** Audit snapshots hold ids and a version only; no log line carries a
 * text or a name; no ballot or voter is ever read.
 */
internal class MeetingProtocolDraftOperations(
    private val readLimiter: FederationInboxRateLimiter = PROTOCOL_DRAFT_READ_RATE_LIMITER,
    private val writeLimiter: FederationInboxRateLimiter = PROTOCOL_DRAFT_WRITE_RATE_LIMITER,
) {
    // ---- read (never writes) ----

    fun get(
        current: CurrentMember,
        meetingId: String,
    ): MeetingProtocolDraftDto {
        val id = parseId(meetingId)
        requireWithinRate(limiter = readLimiter, current = current, meetingId = id)
        return transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = false)
            buildDraft(ctx)
        }
    }

    // ---- writes ----

    fun saveBlock(
        current: CurrentMember,
        meetingId: String,
        agendaItemId: String,
        expectedVersion: Long,
        body: String,
    ): ProtocolBlockSaveResultDto {
        val id = parseId(meetingId)
        val itemId = parseId(agendaItemId)
        requireWithinRate(limiter = writeLimiter, current = current, meetingId = id)
        if (expectedVersion < 0) throw BadRequestException("invalid version")
        if (body.length > PROTOCOL_BLOCK_MAX_LENGTH * RAW_INPUT_FACTOR) throw BadRequestException("text too long")
        return transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = true)
            requireWriter(ctx)
            val existingDraft = draftRow(id)
            requireWritable(meeting = ctx.meetingRow, draft = existingDraft)
            requireItemOfMeeting(itemId = itemId, meetingId = id)
            val text = sanitizeProtocolText(body)
            if (protocolCharCount(text) > PROTOCOL_BLOCK_MAX_LENGTH) throw BadRequestException("text too long")
            val existingBlock =
                existingDraft?.let { blockRow(draftId = it[ProtocolDraftTable.id], agendaItemId = itemId) }
            if (existingBlock == null) {
                saveFirstBlock(
                    ctx = ctx,
                    current = current,
                    draft = existingDraft,
                    itemId = itemId,
                    expectedVersion = expectedVersion,
                    text = text,
                )
            } else {
                updateBlock(
                    current = current,
                    ctx = ctx,
                    draft = existingDraft,
                    block = existingBlock,
                    expectedVersion = expectedVersion,
                    text = text,
                )
            }
        }
    }

    fun addTask(
        current: CurrentMember,
        meetingId: String,
        input: ProtocolTaskInput,
    ): ProtocolTaskDto {
        val id = parseId(meetingId)
        val itemId = parseId(input.agendaItemId)
        val assignee = parseId(input.assigneeMemberId)
        requireWithinRate(limiter = writeLimiter, current = current, meetingId = id)
        if (input.description.length > PROTOCOL_TASK_MAX_LENGTH * RAW_INPUT_FACTOR) throw BadRequestException("description too long")
        return transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = true)
            requireWriter(ctx)
            val existingDraft = draftRow(id)
            requireWritable(meeting = ctx.meetingRow, draft = existingDraft)
            requireItemOfMeeting(itemId = itemId, meetingId = id)
            val description = sanitizeProtocolText(input.description).replace(WHITESPACE_RUN, " ").trim()
            if (description.isEmpty()) throw BadRequestException("description is empty")
            if (protocolCharCount(description) > PROTOCOL_TASK_MAX_LENGTH) throw BadRequestException("description too long")
            if (assignableMemberIds(ctx).none { it == assignee }) throw BadRequestException("choose a person of the committee")
            val due = input.dueDate
            if (due != null && due < ctx.meetingRow[MeetingTable.scheduledAt].date) throw BadRequestException("due date before the meeting")
            if (existingDraft != null) {
                val count =
                    ProtocolDraftTaskTable
                        .selectAll()
                        .where {
                            ProtocolDraftTaskTable.draftId eq
                                existingDraft[ProtocolDraftTable.id]
                        }.count()
                if (count >= PROTOCOL_MAX_TASKS) throw ConflictException("too many tasks")
            }
            val now = DbClock.nowLocalDateTime()
            val (draftId, created) = ensureDraft(ctx = ctx, existing = existingDraft, now = now)
            val taskId = Uuid.random()
            ProtocolDraftTaskTable.insert {
                it[ProtocolDraftTaskTable.id] = taskId
                it[ProtocolDraftTaskTable.draftId] = draftId
                it[ProtocolDraftTaskTable.agendaItemId] = itemId
                it[ProtocolDraftTaskTable.assigneeMemberId] = assignee
                it[ProtocolDraftTaskTable.description] = description
                it[ProtocolDraftTaskTable.dueDate] = due
                it[ProtocolDraftTaskTable.createdAt] = now
            }
            touchDraft(draftId = draftId, now = now)
            if (created) recordCreated(current = current, ctx = ctx, draftId = draftId)
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.PROTOCOL_TASK_ADDED,
                        meetingId = ctx.meetingId.toString(),
                        protocolDraftId = draftId.toString(),
                        agendaItemId = itemId.toString(),
                        protocolTaskId = taskId.toString(),
                    ),
            )
            ProtocolTaskDto(
                taskId = taskId.toString(),
                agendaItemId = itemId.toString(),
                assigneeName = displayNames(setOf(assignee))[assignee].orEmpty(),
                description = description,
                dueDate = due,
                createdAt = now,
            )
        }
    }

    fun removeTask(
        current: CurrentMember,
        meetingId: String,
        taskId: String,
    ) {
        val id = parseId(meetingId)
        val task = parseId(taskId)
        requireWithinRate(limiter = writeLimiter, current = current, meetingId = id)
        transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = true)
            requireWriter(ctx)
            val draft = draftRow(id)
            requireWritable(meeting = ctx.meetingRow, draft = draft)
            // A task of another Sitzung's draft is as unknown as a random id.
            val row =
                draft?.let {
                    ProtocolDraftTaskTable
                        .selectAll()
                        .where { (ProtocolDraftTaskTable.id eq task) and (ProtocolDraftTaskTable.draftId eq it[ProtocolDraftTable.id]) }
                        .singleOrNull()
                } ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            val draftId = draft[ProtocolDraftTable.id]
            ProtocolDraftTaskTable.deleteWhere { ProtocolDraftTaskTable.id eq task }
            touchDraft(draftId = draftId, now = DbClock.nowLocalDateTime())
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.PROTOCOL_TASK_REMOVED,
                        meetingId = ctx.meetingId.toString(),
                        protocolDraftId = draftId.toString(),
                        agendaItemId = row[ProtocolDraftTaskTable.agendaItemId].toString(),
                        protocolTaskId = task.toString(),
                    ),
            )
        }
    }

    // ---- block write paths ----

    private fun saveFirstBlock(
        ctx: MeetingContext,
        current: CurrentMember,
        draft: ResultRow?,
        itemId: Uuid,
        expectedVersion: Long,
        text: String,
    ): ProtocolBlockSaveResultDto {
        // No text saved yet: only "I saw nothing" (version 0) is current. Empty text on an empty item changes nothing -> no draft is created.
        if (expectedVersion != 0L) return ProtocolBlockSaveResultDto(saved = false, version = 0, currentBody = "", currentUpdatedAt = null)
        if (text.isEmpty()) return ProtocolBlockSaveResultDto(saved = true, version = 0, currentBody = null, currentUpdatedAt = null)
        val now = DbClock.nowLocalDateTime()
        val (draftId, created) = ensureDraft(ctx = ctx, existing = draft, now = now)
        val inserted =
            try {
                withSavepoint(name = "protocol_block_insert") {
                    ProtocolDraftBlockTable.insert {
                        it[ProtocolDraftBlockTable.id] = Uuid.random()
                        it[ProtocolDraftBlockTable.draftId] = draftId
                        it[ProtocolDraftBlockTable.agendaItemId] = itemId
                        it[ProtocolDraftBlockTable.body] = text
                        it[ProtocolDraftBlockTable.version] = 1L
                        it[ProtocolDraftBlockTable.updatedAt] = now
                    }
                }
                true
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) false else throw e
            }
        if (!inserted) return conflictOf(blockRow(draftId = draftId, agendaItemId = itemId))
        touchDraft(draftId = draftId, now = now)
        if (created) recordCreated(current = current, ctx = ctx, draftId = draftId)
        recordBlockUpdated(current = current, ctx = ctx, draftId = draftId, itemId = itemId, version = 1L)
        return ProtocolBlockSaveResultDto(saved = true, version = 1L, currentBody = null, currentUpdatedAt = null)
    }

    private fun updateBlock(
        current: CurrentMember,
        ctx: MeetingContext,
        draft: ResultRow,
        block: ResultRow,
        expectedVersion: Long,
        text: String,
    ): ProtocolBlockSaveResultDto {
        val version = block[ProtocolDraftBlockTable.version]
        if (version != expectedVersion) return conflictOf(block)
        // Unchanged text: no update, no new version, no audit entry.
        if (block[ProtocolDraftBlockTable.body] ==
            text
        ) {
            return ProtocolBlockSaveResultDto(saved = true, version = version, currentBody = null, currentUpdatedAt = null)
        }
        val now = DbClock.nowLocalDateTime()
        val blockId = block[ProtocolDraftBlockTable.id]
        val changed =
            ProtocolDraftBlockTable.update({
                (ProtocolDraftBlockTable.id eq blockId) and
                    (ProtocolDraftBlockTable.version eq expectedVersion)
            }) {
                it[ProtocolDraftBlockTable.body] = text
                it[ProtocolDraftBlockTable.version] = expectedVersion + 1
                it[ProtocolDraftBlockTable.updatedAt] = now
            }
        if (changed ==
            0
        ) {
            return conflictOf(
                blockRow(draftId = draft[ProtocolDraftTable.id], agendaItemId = block[ProtocolDraftBlockTable.agendaItemId]),
            )
        }
        val draftId = draft[ProtocolDraftTable.id]
        touchDraft(draftId = draftId, now = now)
        recordBlockUpdated(
            current = current,
            ctx = ctx,
            draftId = draftId,
            itemId = block[ProtocolDraftBlockTable.agendaItemId],
            version =
                expectedVersion + 1,
        )
        return ProtocolBlockSaveResultDto(saved = true, version = expectedVersion + 1, currentBody = null, currentUpdatedAt = null)
    }

    private fun conflictOf(block: ResultRow?): ProtocolBlockSaveResultDto =
        ProtocolBlockSaveResultDto(
            saved = false,
            version = block?.get(ProtocolDraftBlockTable.version) ?: 0L,
            currentBody = block?.get(ProtocolDraftBlockTable.body).orEmpty(),
            currentUpdatedAt = block?.get(ProtocolDraftBlockTable.updatedAt),
        )

    private fun recordBlockUpdated(
        current: CurrentMember,
        ctx: MeetingContext,
        draftId: Uuid,
        itemId: Uuid,
        version: Long,
    ) {
        MeetingSessionAudit.record(
            actor = current,
            snapshot =
                MeetingSessionAuditSnapshot(
                    kind = MeetingSessionAuditKind.PROTOCOL_BLOCK_UPDATED,
                    meetingId = ctx.meetingId.toString(),
                    protocolDraftId = draftId.toString(),
                    agendaItemId = itemId.toString(),
                    blockVersion = version,
                ),
        )
    }

    private fun recordCreated(
        current: CurrentMember,
        ctx: MeetingContext,
        draftId: Uuid,
    ) {
        MeetingSessionAudit.record(
            actor = current,
            snapshot =
                MeetingSessionAuditSnapshot(
                    kind = MeetingSessionAuditKind.PROTOCOL_DRAFT_CREATED,
                    meetingId = ctx.meetingId.toString(),
                    protocolDraftId = draftId.toString(),
                ),
        )
    }

    // ---- draft row helpers ----

    internal fun draftRow(meetingId: Uuid): ResultRow? =
        ProtocolDraftTable.selectAll().where { ProtocolDraftTable.meetingId eq meetingId }.singleOrNull()

    private fun blockRow(
        draftId: Uuid,
        agendaItemId: Uuid,
    ): ResultRow? =
        ProtocolDraftBlockTable
            .selectAll()
            .where { (ProtocolDraftBlockTable.draftId eq draftId) and (ProtocolDraftBlockTable.agendaItemId eq agendaItemId) }
            .singleOrNull()

    /**
     * The draft of the Sitzung, created on the first write. Returns the id and whether THIS call created it. A concurrent creator (the
     * Sitzung row lock normally prevents it) is tolerated: a unique violation re-reads the row.
     */
    internal fun ensureDraft(
        ctx: MeetingContext,
        existing: ResultRow?,
        now: LocalDateTime,
    ): Pair<Uuid, Boolean> {
        if (existing != null) return existing[ProtocolDraftTable.id] to false
        val newId = Uuid.random()
        val created =
            try {
                withSavepoint(name = "protocol_draft_insert") {
                    ProtocolDraftTable.insert {
                        it[ProtocolDraftTable.id] = newId
                        it[ProtocolDraftTable.meetingId] = ctx.meetingId
                        it[ProtocolDraftTable.phase] = ProtocolDraftPhase.DRAFT
                        it[ProtocolDraftTable.createdAt] = now
                        it[ProtocolDraftTable.updatedAt] = now
                    }
                }
                true
            } catch (e: ExposedSQLException) {
                if (e.isUniqueViolation()) false else throw e
            }
        if (created) return newId to true
        val row = draftRow(ctx.meetingId) ?: throw ConflictException("draft not available")
        return row[ProtocolDraftTable.id] to false
    }

    internal fun touchDraft(
        draftId: Uuid,
        now: LocalDateTime,
    ) {
        ProtocolDraftTable.update({ ProtocolDraftTable.id eq draftId }) { it[updatedAt] = now }
    }

    // ---- guards ----

    private fun parseId(raw: String): Uuid =
        runCatching { Uuid.parse(raw) }.getOrElse { throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE) }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        current: CurrentMember,
        meetingId: Uuid,
    ) {
        // Keyed by actor only: a per-meeting key would give every random meetingId a fresh bucket (DoS, map eviction).
        if (!limiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }

    private fun requireWriter(ctx: MeetingContext) {
        if (ctx.role != MeetingSessionRole.LEAD && ctx.role != MeetingSessionRole.MINUTES) throw ForbiddenException()
    }

    private fun requireWritable(
        meeting: ResultRow,
        draft: ResultRow?,
    ) {
        if (!isWritable(meeting = meeting, draft = draft)) throw ConflictException("Das Protokoll kann gerade nicht bearbeitet werden.")
    }

    /** Opened, not cancelled, and not locked. (HELD stays writable until a later wave locks the draft.) */
    private fun isWritable(
        meeting: ResultRow,
        draft: ResultRow?,
    ): Boolean =
        meeting[MeetingTable.openedAt] != null &&
            meeting[MeetingTable.status] != MeetingStatus.CANCELLED &&
            (draft == null || draft[ProtocolDraftTable.phase] == ProtocolDraftPhase.DRAFT)

    private fun requireItemOfMeeting(
        itemId: Uuid,
        meetingId: Uuid,
    ) {
        AgendaItemTable
            .selectAll()
            .where { (AgendaItemTable.id eq itemId) and (AgendaItemTable.meetingId eq meetingId) }
            .singleOrNull() ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
    }

    // ---- who can be given a task ----

    /** The people a task can be given to: the roster of the committee, for the general assembly the people recorded as present or represented. */
    private fun assignableRoster(ctx: MeetingContext): List<RosterPerson> {
        val rule = CommitteeAttendanceRules.forType(ctx.committeeRow[CommitteeTable.type])
        val meetingId = ctx.meetingId
        val people =
            if (ctx.committeeRow[CommitteeTable.type] == CommitteeType.GENERAL_ASSEMBLY) {
                val ids =
                    AttendanceTable
                        .selectAll()
                        .where {
                            (AttendanceTable.meetingId eq meetingId) and
                                (AttendanceTable.status inList listOf(AttendanceStatus.PRESENT, AttendanceStatus.REPRESENTED))
                        }.map { it[AttendanceTable.memberId] }
                        .toSet()
                val names = displayNames(ids)
                ids.map { RosterPerson(memberId = it, displayName = names[it].orEmpty()) }
            } else {
                rule.roster(
                    meetingId = meetingId,
                    committeeRow = ctx.committeeRow,
                    scheduledDate = ctx.meetingRow[MeetingTable.scheduledAt].date,
                )
            }
        return people.sortedWith(compareBy({ it.displayName.lowercase() }, { it.displayName }, { it.memberId.toString() }))
    }

    private fun assignableMemberIds(ctx: MeetingContext): List<Uuid> = assignableRoster(ctx).map { it.memberId }

    // ---- derive the draft ----

    /**
     * V1.9.240 -- the read model. While the draft is open it is derived from the live rows; once it is LOCKED (finalised) it is the SNAPSHOT of the
     * newest version, so nothing "wanders" after finalising (attendance or a resolution changed later does not change what was confirmed).
     */
    fun buildDraft(ctx: MeetingContext): MeetingProtocolDraftDto {
        val draft = draftRow(ctx.meetingId)
        val latest = ProtocolVersionStore.latestVersion(ctx.meetingId)
        val base =
            if (draft != null && draft[ProtocolDraftTable.phase] == ProtocolDraftPhase.LOCKED && latest != null) {
                ProtocolSnapshots.toDraftDto(
                    snapshot = ProtocolSnapshots.parse(latest[ProtocolVersionTable.contentJson]),
                    phase = ProtocolDraftPhase.LOCKED,
                    role = ctx.role,
                    roles = ctx.roles,
                    finalisedAt = latest[ProtocolVersionTable.finalisedAt],
                    serverNow = DbClock.nowLocalDateTime(),
                )
            } else {
                buildLiveDraft(ctx)
            }
        return base.copy(
            latestVersionNo = latest?.get(ProtocolVersionTable.versionNo),
            latestStatus = latest?.get(ProtocolVersionTable.status),
            correctionOfVersionNo = draft?.get(ProtocolDraftTable.correctionOfVersionNo),
            roles = ctx.roles,
        )
    }

    /** The stable key + assignee of every task of the draft, by draft task id (a task without a key gets its own id as the key). */
    fun draftTaskKeys(meetingId: Uuid): Map<String, DraftTaskKey> {
        val draft = draftRow(meetingId) ?: return emptyMap()
        return ProtocolDraftTaskTable
            .selectAll()
            .where { ProtocolDraftTaskTable.draftId eq draft[ProtocolDraftTable.id] }
            .associate {
                it[ProtocolDraftTaskTable.id].toString() to
                    DraftTaskKey(
                        taskKey = (it[ProtocolDraftTaskTable.taskKey] ?: it[ProtocolDraftTaskTable.id]).toString(),
                        assigneeMemberId = it[ProtocolDraftTaskTable.assigneeMemberId].toString(),
                    )
            }
    }

    /** The draft derived from the live rows (never the snapshot). Used by [buildDraft] and by finalising. */
    fun buildLiveDraft(ctx: MeetingContext): MeetingProtocolDraftDto {
        val meeting = ctx.meetingRow
        val meetingId = ctx.meetingId
        val draft = draftRow(meetingId)
        val writable = isWritable(meeting = meeting, draft = draft)
        val editable = writable && (ctx.role == MeetingSessionRole.LEAD || ctx.role == MeetingSessionRole.MINUTES)
        val attendanceRows = AttendanceTable.selectAll().where { AttendanceTable.meetingId eq meetingId }.toList()
        val itemRows =
            AgendaItemTable
                .selectAll()
                .where { AgendaItemTable.meetingId eq meetingId }
                .orderBy(AgendaItemTable.position to SortOrder.ASC, AgendaItemTable.id to SortOrder.ASC)
                .toList()
        val blocks =
            draft
                ?.let { d ->
                    ProtocolDraftBlockTable.selectAll().where { ProtocolDraftBlockTable.draftId eq d[ProtocolDraftTable.id] }.toList()
                }.orEmpty()
                .associateBy { it[ProtocolDraftBlockTable.agendaItemId] }
        val taskRows =
            draft
                ?.let { d ->
                    ProtocolDraftTaskTable
                        .selectAll()
                        .where { ProtocolDraftTaskTable.draftId eq d[ProtocolDraftTable.id] }
                        .orderBy(ProtocolDraftTaskTable.createdAt to SortOrder.ASC, ProtocolDraftTaskTable.id to SortOrder.ASC)
                        .toList()
                }.orEmpty()
        val resolutionRows =
            ResolutionTable
                .selectAll()
                .where { ResolutionTable.meetingId eq meetingId }
                .orderBy(
                    ResolutionTable.decidedAt to SortOrder.ASC,
                    ResolutionTable.number to SortOrder.ASC,
                    ResolutionTable.id to SortOrder.ASC,
                ).toList()
        val secretElections = secretElectionIds(resolutionRows.mapNotNull { it[ResolutionTable.electionId] })
        val nameIds =
            buildSet {
                attendanceRows.forEach {
                    add(it[AttendanceTable.memberId])
                    it[AttendanceTable.representedByMemberId]?.let(::add)
                }
                taskRows.forEach { add(it[ProtocolDraftTaskTable.assigneeMemberId]) }
                meeting[MeetingTable.chairMemberId]?.let(::add)
                meeting[MeetingTable.minuteTakerMemberId]?.let(::add)
            }
        val names = displayNames(nameIds)

        // The figures come ONLY through toResolutionDtos(): it masks a secret vote below the minimum participation.
        val disclosed = resolutionRows.toResolutionDtos().associateBy { it.id }

        fun resolutionDto(row: ResultRow): ProtocolResolutionDto {
            val figures = disclosed.getValue(row[ResolutionTable.id].toString())
            return ProtocolResolutionDto(
                resolutionId = row[ResolutionTable.id].toString(),
                number = row[ResolutionTable.number],
                title = row[ResolutionTable.title],
                text = row[ResolutionTable.text],
                yes = figures.votesYes,
                no = figures.votesNo,
                abstain = figures.votesAbstain,
                figuresWithheld = figures.figuresWithheld,
                status = row[ResolutionTable.status],
                quorumMet = row[ResolutionTable.quorumMet],
                decidedAt = row[ResolutionTable.decidedAt],
                secretBallot = row[ResolutionTable.electionId]?.let { it in secretElections } ?: false,
            )
        }

        val itemIds = itemRows.map { it[AgendaItemTable.id] }.toSet()
        val items =
            itemRows.map { item ->
                val itemId = item[AgendaItemTable.id]
                val block = blocks[itemId]
                ProtocolAgendaItemDto(
                    agendaItemId = itemId.toString(),
                    position = item[AgendaItemTable.position],
                    title = item[AgendaItemTable.title],
                    body = block?.get(ProtocolDraftBlockTable.body).orEmpty(),
                    version = block?.get(ProtocolDraftBlockTable.version) ?: 0L,
                    updatedAt = block?.get(ProtocolDraftBlockTable.updatedAt),
                    resolutions = resolutionRows.filter { it[ResolutionTable.agendaItemId] == itemId }.map(::resolutionDto),
                    approval = ProtocolApprovalItems.dto(item = item, editable = editable),
                    tasks =
                        taskRows.filter { it[ProtocolDraftTaskTable.agendaItemId] == itemId }.map {
                            ProtocolTaskDto(
                                taskId = it[ProtocolDraftTaskTable.id].toString(),
                                agendaItemId = itemId.toString(),
                                assigneeName = names[it[ProtocolDraftTaskTable.assigneeMemberId]].orEmpty(),
                                description = it[ProtocolDraftTaskTable.description],
                                dueDate = it[ProtocolDraftTaskTable.dueDate],
                                createdAt = it[ProtocolDraftTaskTable.createdAt],
                            )
                        },
                )
            }
        val unassigned =
            resolutionRows.filter { row -> row[ResolutionTable.agendaItemId].let { it == null || it !in itemIds } }.map(::resolutionDto)
        return MeetingProtocolDraftDto(
            meetingId = meetingId.toString(),
            phase = draft?.get(ProtocolDraftTable.phase) ?: ProtocolDraftPhase.DRAFT,
            editable = editable,
            role = ctx.role,
            header =
                ProtocolHeaderDto(
                    committeeName = ctx.committeeRow[CommitteeTable.name],
                    committeeType = ctx.committeeRow[CommitteeTable.type],
                    title = meeting[MeetingTable.title],
                    scheduledAt = meeting[MeetingTable.scheduledAt],
                    format = meeting[MeetingTable.format],
                    location = meeting[MeetingTable.location],
                    openedAt = meeting[MeetingTable.openedAt],
                    endedAt = null,
                    chairName = meeting[MeetingTable.chairMemberId]?.let { names[it] },
                    minuteTakerName = meeting[MeetingTable.minuteTakerMemberId]?.let { names[it] },
                ),
            attendance = attendanceOf(rows = attendanceRows, names = names),
            quorum =
                meeting[MeetingTable.quorumEstablishedAt]?.let { at ->
                    ProtocolQuorumDto(
                        establishedAt = at,
                        present = meeting[MeetingTable.quorumPresentCount] ?: 0,
                        required = meeting[MeetingTable.quorumRequiredCount] ?: 0,
                        eligible = meeting[MeetingTable.quorumEligibleCount] ?: 0,
                        overridden = meeting[MeetingTable.quorumOverridden],
                    )
                },
            items = items,
            unassignedResolutions = unassigned,
            assignableMembers =
                if (editable) {
                    assignableRoster(ctx).map { ProtocolMemberRefDto(memberId = it.memberId.toString(), displayName = it.displayName) }
                } else {
                    emptyList()
                },
            taskCount = taskRows.size,
            serverNow = DbClock.nowLocalDateTime(),
        )
    }

    private fun attendanceOf(
        rows: List<ResultRow>,
        names: Map<Uuid, String>,
    ): ProtocolAttendanceDto {
        fun people(status: AttendanceStatus) =
            rows
                .filter { it[AttendanceTable.status] == status }
                .map { ProtocolPersonDto(displayName = names[it[AttendanceTable.memberId]].orEmpty()) }
                .sortedWith(compareBy({ it.displayName.lowercase() }, { it.displayName }))
        return ProtocolAttendanceDto(
            present = people(AttendanceStatus.PRESENT),
            excused = people(AttendanceStatus.EXCUSED),
            absent = people(AttendanceStatus.UNEXCUSED),
            represented =
                rows
                    .filter { it[AttendanceTable.status] == AttendanceStatus.REPRESENTED }
                    .map {
                        ProtocolRepresentationDto(
                            representedName = names[it[AttendanceTable.memberId]].orEmpty(),
                            representativeName = it[AttendanceTable.representedByMemberId]?.let { id -> names[id] },
                        )
                    }.sortedWith(
                        compareBy({ it.representedName.lowercase() }, { it.representedName }, { it.representativeName.orEmpty() }),
                    ),
            guests = people(AttendanceStatus.GUEST),
        )
    }

    /** Which of the elections are secret -- the ONLY thing read from the election side (never a ballot, never a voter). */
    private fun secretElectionIds(ids: List<Uuid>): Set<Uuid> {
        if (ids.isEmpty()) return emptySet()
        return ids
            .distinct()
            .chunked(ID_CHUNK)
            .flatMap { chunk ->
                ElectionTable
                    .selectAll()
                    .where { ElectionTable.id inList chunk }
                    .filter { it[ElectionTable.secret] }
                    .map { it[ElectionTable.id] }
            }.toSet()
    }

    private fun displayNames(ids: Set<Uuid>): Map<Uuid, String> {
        if (ids.isEmpty()) return emptyMap()
        return ids
            .chunked(ID_CHUNK)
            .flatMap { chunk ->
                MemberTable.selectAll().where { MemberTable.id inList chunk }.map { it[MemberTable.id] to it[MemberTable.displayName] }
            }.toMap()
    }

    private companion object {
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
