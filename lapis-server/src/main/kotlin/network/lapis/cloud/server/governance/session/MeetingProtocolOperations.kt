package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.db.generated.ProtocolTaskStatusTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTaskTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.pdf.MeetingProtocolPdfGenerator
import network.lapis.cloud.server.pdf.PdfLimitExceeded
import network.lapis.cloud.server.pdf.addressLines
import network.lapis.cloud.server.rpc.toOrganizationSettingsDto
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingProtocolPreviewDto
import network.lapis.cloud.shared.domain.MeetingProtocolVersionsDto
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MyProtocolTaskDto
import network.lapis.cloud.shared.domain.PROTOCOL_CORRECTION_NOTE_MAX
import network.lapis.cloud.shared.domain.PROTOCOL_CORRECTION_NOTE_MIN
import network.lapis.cloud.shared.domain.ProtocolApprovalDto
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolConfirmOutcome
import network.lapis.cloud.shared.domain.ProtocolConfirmResultDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationMode
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolFinaliseOutcome
import network.lapis.cloud.shared.domain.ProtocolFinaliseResultDto
import network.lapis.cloud.shared.domain.ProtocolVersionDto
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** V1.9.240 -- process-wide limiters (top level: the RPC service is constructed anew for every call), keyed by member, checked after authentication. */
internal val PROTOCOL_FINAL_READ_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes)
internal val PROTOCOL_FINAL_WRITE_RATE_LIMITER = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes)

/** Where a Protokoll PDF is stored; the route resolves [storageKey] against the document storage root. */
internal class ProtocolPdfLocation(
    val storageKey: String,
    val versionNo: Int,
)

private const val RAW_NOTE_FACTOR = 4
private const val TASK_LIST_LIMIT = 200
private val SHA256_SHAPE = Regex("^[0-9a-f]{64}$")

/**
 * V1.9.240 "Protokoll fertigstellen" (D2) -- the operations behind `IMeetingProtocolService`.
 *
 * **State machine of a version:** AWAITING_CONFIRMATION -> CONFIRMED (every required role confirmed) or -> SUPERSEDED (a correction was started
 * while it waited). A CONFIRMED version stays CONFIRMED forever; a correction is a NEW version (the newest one is the valid one).
 * **Who:** finalising and confirming need the role LEAD (chair or recording office) or MINUTES (minute taker); a person holding both roles
 * confirms both at once. The roles come from [MeetingSessionAccess.rolesOf].
 * **Lock order, everywhere:** `meeting` row, then `protocol_draft` row, then version rows; the audit entry (chain lock) is the LAST step, and the
 * approval-item insertion (which locks ANOTHER Sitzung) happens before it. **External effects never run inside a transaction:** the PDF is archived
 * AFTER the commit ([archivePdf]); only the cheap page-limit probe runs inside the finalising transaction (pure computation, bounded to 20 s).
 * **Privacy:** audit snapshots hold ids, enums and numbers (the content hash included); no log line carries a text, a name or a hash.
 */
internal class MeetingProtocolOperations(
    private val storageRoot: java.io.File? = null,
    private val readLimiter: FederationInboxRateLimiter = PROTOCOL_FINAL_READ_RATE_LIMITER,
    private val writeLimiter: FederationInboxRateLimiter = PROTOCOL_FINAL_WRITE_RATE_LIMITER,
    private val draftOps: MeetingProtocolDraftOperations = MeetingProtocolDraftOperations(),
) {
    // ---- reads ----

    fun preview(
        current: CurrentMember,
        meetingId: String,
    ): MeetingProtocolPreviewDto {
        val id = parseId(meetingId)
        requireWithinRate(limiter = readLimiter, current = current)
        return transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = false)
            val callerRoles = confirmationRolesOf(ctx)
            val draft = draftOps.draftRow(id)
            val live = draftOps.buildLiveDraft(ctx)
            // An empty draft can never be finalised; say so up front instead of letting the dialog open and fail on confirm.
            val blocked = blockedReason(ctx = ctx, draft = draft) ?: ProtocolFinaliseOutcome.EMPTY.takeIf { isEmptyDraft(live) }
            val nextNo = ProtocolVersionStore.maxVersionNo(id) + 1
            val minutesRequired = ctx.meetingRow[MeetingTable.minuteTakerMemberId] != null
            val hash =
                if (callerRoles.isEmpty()) {
                    ""
                } else {
                    val snapshot =
                        ProtocolSnapshots.build(
                            draft = live,
                            versionNo = nextNo,
                            correctionOfVersionNo = draft?.get(ProtocolDraftTable.correctionOfVersionNo),
                            correctionNote = draft?.get(ProtocolDraftTable.correctionNote),
                            minutesRequired = minutesRequired,
                            taskKeys = draftOps.draftTaskKeys(id),
                        )
                    ProtocolSnapshots.sha256(ProtocolSnapshots.canonicalJson(snapshot))
                }
            MeetingProtocolPreviewDto(
                meetingId = id.toString(),
                nextVersionNo = nextNo,
                contentSha256 = hash,
                itemsWithoutCourse = live.items.count { it.body.isBlank() },
                canFinalise = callerRoles.isNotEmpty() && blocked == null,
                blockedReason = blocked,
                callerRoles = callerRoles,
                minutesRequired = minutesRequired,
            )
        }
    }

    fun versions(
        current: CurrentMember,
        meetingId: String,
    ): MeetingProtocolVersionsDto {
        val id = parseId(meetingId)
        requireWithinRate(limiter = readLimiter, current = current)
        return transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = false)
            val callerRoles = confirmationRolesOf(ctx)
            val rows = ProtocolVersionStore.versionsOf(id)
            val dtos = rows.map { versionDto(it) }
            val newest = dtos.firstOrNull()
            val draft = draftOps.draftRow(id)
            MeetingProtocolVersionsDto(
                meetingId = id.toString(),
                callerRoles = callerRoles,
                canConfirm =
                    newest != null &&
                        newest.status == ProtocolVersionStatus.AWAITING_CONFIRMATION &&
                        callerRoles.any { it in newest.missingRoles },
                canStartCorrection =
                    callerRoles.isNotEmpty() &&
                        draft?.get(ProtocolDraftTable.phase) == ProtocolDraftPhase.LOCKED &&
                        newest != null &&
                        newest.status != ProtocolVersionStatus.SUPERSEDED,
                versions = dtos,
            )
        }
    }

    /**
     * Where the PDF of [versionId] lives (storage key + version number), for the download route. `null` for EVERYTHING the caller must not get: a
     * malformed or unknown id, a version of a Sitzung in which the caller has no role, a version without a PDF. Never throws for those cases.
     */
    fun pdfLocation(
        current: CurrentMember,
        versionId: String,
    ): ProtocolPdfLocation? {
        val vId = runCatching { Uuid.parse(versionId) }.getOrNull() ?: return null
        return transaction {
            val version = ProtocolVersionStore.versionRow(versionId = vId) ?: return@transaction null
            MeetingSessionAccess.resolveMeeting(current = current, meetingId = version[ProtocolVersionTable.meetingId], lock = false)
                ?: return@transaction null
            val documentVersionId = version[ProtocolVersionTable.pdfDocumentVersionId] ?: return@transaction null
            DocumentVersionTable
                .selectAll()
                .where { DocumentVersionTable.id eq documentVersionId }
                .singleOrNull()
                ?.let {
                    ProtocolPdfLocation(
                        storageKey = it[DocumentVersionTable.storageKey],
                        versionNo = version[ProtocolVersionTable.versionNo],
                    )
                }
        }
    }

    // ---- finalise ----

    /** True when nothing would go into a Protokoll: no item has a course, and there is no resolution anywhere. */
    private fun isEmptyDraft(live: MeetingProtocolDraftDto): Boolean =
        live.items.all { it.body.isBlank() } &&
            live.items.all { it.resolutions.isEmpty() } &&
            live.unassignedResolutions.isEmpty()

    fun finalise(
        current: CurrentMember,
        meetingId: String,
        expectedContentSha256: String,
    ): ProtocolFinaliseResultDto {
        val id = parseId(meetingId)
        requireWithinRate(limiter = writeLimiter, current = current)
        if (!SHA256_SHAPE.matches(expectedContentSha256)) throw BadRequestException("invalid hash")
        val outcome =
            transaction {
                val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = true)
                val roles = confirmationRolesOf(ctx)
                if (roles.isEmpty()) throw ForbiddenException()
                val draft = lockedDraft(id)
                blockedReason(ctx = ctx, draft = draft)?.let {
                    return@transaction FinaliseTx(outcome = it, versionId = null, currentSha256 = null, fully = null)
                }
                if (mode() != ProtocolConfirmationMode.APP_CONFIRMATION) throw ConflictException("confirmation mode not available")
                val live = draftOps.buildLiveDraft(ctx)
                if (isEmptyDraft(live)) {
                    return@transaction FinaliseTx(
                        outcome = ProtocolFinaliseOutcome.EMPTY,
                        versionId = null,
                        currentSha256 = null,
                        fully = null,
                    )
                }
                val versionNo = ProtocolVersionStore.maxVersionNo(id) + 1
                val minutesRequired = ctx.meetingRow[MeetingTable.minuteTakerMemberId] != null
                val correctionOf = draft?.get(ProtocolDraftTable.correctionOfVersionNo)
                val correctionNote = draft?.get(ProtocolDraftTable.correctionNote)
                val taskKeys = draftOps.draftTaskKeys(id)
                val snapshot =
                    ProtocolSnapshots.build(
                        draft = live,
                        versionNo = versionNo,
                        correctionOfVersionNo = correctionOf,
                        correctionNote = correctionNote,
                        minutesRequired = minutesRequired,
                        taskKeys = taskKeys,
                    )
                val json = ProtocolSnapshots.canonicalJson(snapshot)
                val sha = ProtocolSnapshots.sha256(json)
                if (sha !=
                    expectedContentSha256
                ) {
                    return@transaction FinaliseTx(
                        outcome = ProtocolFinaliseOutcome.STALE,
                        versionId = null,
                        currentSha256 = sha,
                        fully = null,
                    )
                }
                if (!pdfFits(
                        snapshot,
                    )
                ) {
                    return@transaction FinaliseTx(
                        outcome = ProtocolFinaliseOutcome.PDF_LIMIT,
                        versionId = null,
                        currentSha256 = null,
                        fully = null,
                    )
                }

                val now = DbClock.nowLocalDateTime()
                val versionId =
                    ProtocolVersionStore.insertVersion(
                        meetingId = id,
                        versionNo = versionNo,
                        contentJson = json,
                        contentSha256 = sha,
                        correctionNote = correctionNote,
                        finalisedBy = current.memberId,
                        finalisedAt = now,
                        mode = ProtocolConfirmationMode.APP_CONFIRMATION,
                        minutesRequired = minutesRequired,
                    )
                        ?: return@transaction FinaliseTx(
                            outcome = ProtocolFinaliseOutcome.ALREADY_FINALISED,
                            versionId = null,
                            currentSha256 = null,
                            fully = null,
                        )
                persistTasks(versionId = versionId, meetingId = id, taskKeys = taskKeys, snapshot = snapshot)
                val (draftId, _) = draftOps.ensureDraft(ctx = ctx, existing = draft, now = now)
                ProtocolDraftTable.update({ ProtocolDraftTable.id eq draftId }) {
                    it[phase] = ProtocolDraftPhase.LOCKED
                    it[correctionOfVersionNo] = null
                    it[ProtocolDraftTable.correctionNote] = null
                    it[updatedAt] = now
                }
                val audits = mutableListOf<MeetingSessionAuditSnapshot>()
                audits +=
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.PROTOCOL_FINALISED,
                        meetingId = id.toString(),
                        protocolVersionId = versionId.toString(),
                        protocolVersionNo = versionNo,
                        contentSha256 = sha,
                    )
                val required = requiredRoles(minutesRequired)
                val confirmedNow = roles.filter { it in required }
                confirmedNow.forEach { role ->
                    ProtocolVersionStore.insertConfirmation(versionId = versionId, memberId = current.memberId, role = role, at = now)
                    audits += confirmedAudit(meetingId = id, versionId = versionId, versionNo = versionNo, role = role)
                }
                var fully = false
                if (confirmedNow.toSet() == required) {
                    ProtocolVersionStore.markConfirmed(versionId)
                    fully = true
                    audits += ProtocolApprovalItems.onConfirmed(id)
                }
                audits.forEach { MeetingSessionAudit.record(actor = current, snapshot = it) }
                FinaliseTx(
                    outcome = if (fully) ProtocolFinaliseOutcome.CONFIRMED else ProtocolFinaliseOutcome.FINALISED,
                    versionId = versionId,
                    currentSha256 = null,
                    fully = fully,
                )
            }
        if (outcome.versionId != null && outcome.fully == true) {
            ProtocolPdfArchiver(storageRoot).archiveDetailed(versionId = outcome.versionId, actor = current)
        }
        return transaction {
            val version = outcome.versionId?.let { ProtocolVersionStore.versionRow(versionId = it) }?.let { versionDto(it) }
            ProtocolFinaliseResultDto(outcome = outcome.outcome, version = version, currentSha256 = outcome.currentSha256)
        }
    }

    // ---- confirm ----

    fun confirm(
        current: CurrentMember,
        meetingId: String,
        versionId: String,
    ): ProtocolConfirmResultDto {
        val id = parseId(meetingId)
        val vId = parseId(versionId)
        requireWithinRate(limiter = writeLimiter, current = current)
        var archiveNeeded = false
        val outcome =
            transaction {
                val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = true)
                // IDOR: a version of another Sitzung is as unknown as a random id.
                val version =
                    ProtocolVersionStore.versionRow(versionId = vId, forUpdate = true)?.takeIf { it[ProtocolVersionTable.meetingId] == id }
                        ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
                val roles = confirmationRolesOf(ctx)
                val status = version[ProtocolVersionTable.status]
                if (status != ProtocolVersionStatus.AWAITING_CONFIRMATION) {
                    if (status == ProtocolVersionStatus.CONFIRMED &&
                        version[ProtocolVersionTable.pdfDocumentVersionId] == null &&
                        roles.isNotEmpty() &&
                        // Only the NEWEST confirmed version may be archived late: an older one would become the newest document version.
                        ProtocolVersionStore.latestConfirmed(id)?.get(ProtocolVersionTable.id) == vId
                    ) {
                        archiveNeeded = true
                    }
                    return@transaction ProtocolConfirmOutcome.NOT_AWAITING
                }
                val required = requiredRoles(version[ProtocolVersionTable.minutesRequired])
                val confirmed = ProtocolVersionStore.confirmations(vId).map { it[ProtocolVersionConfirmationTable.role] }.toSet()
                val missing = required - confirmed
                val mine = roles.filter { it in missing }
                if (mine.isEmpty()) {
                    if (roles.any { it in required }) return@transaction ProtocolConfirmOutcome.ALREADY_CONFIRMED
                    throw ForbiddenException()
                }
                val now = DbClock.nowLocalDateTime()
                val versionNo = version[ProtocolVersionTable.versionNo]
                val audits = mutableListOf<MeetingSessionAuditSnapshot>()
                var inserted = 0
                mine.forEach { role ->
                    if (ProtocolVersionStore.insertConfirmation(versionId = vId, memberId = current.memberId, role = role, at = now)) {
                        inserted++
                        audits += confirmedAudit(meetingId = id, versionId = vId, versionNo = versionNo, role = role)
                    }
                }
                if (inserted == 0) return@transaction ProtocolConfirmOutcome.ALREADY_CONFIRMED
                var fully = false
                if ((missing - mine.toSet()).isEmpty()) {
                    fully = ProtocolVersionStore.markConfirmed(vId)
                    if (fully) audits += ProtocolApprovalItems.onConfirmed(id)
                }
                audits.forEach { MeetingSessionAudit.record(actor = current, snapshot = it) }
                archiveNeeded = fully
                if (fully) ProtocolConfirmOutcome.FULLY_CONFIRMED else ProtocolConfirmOutcome.CONFIRMED_ROLE
            }
        var result = outcome
        if (archiveNeeded) {
            val retry = outcome == ProtocolConfirmOutcome.NOT_AWAITING
            result =
                when (ProtocolPdfArchiver(storageRoot).archiveDetailed(versionId = vId, actor = current)) {
                    PdfArchiveResult.DONE -> if (retry) ProtocolConfirmOutcome.FULLY_CONFIRMED else outcome
                    PdfArchiveResult.NOTHING_TO_DO -> outcome
                    PdfArchiveResult.LIMIT -> ProtocolConfirmOutcome.PDF_LIMIT
                    PdfArchiveResult.FAILED -> ProtocolConfirmOutcome.PDF_PENDING
                }
        }
        return transaction {
            ProtocolConfirmResultDto(outcome = result, version = ProtocolVersionStore.versionRow(versionId = vId)?.let { versionDto(it) })
        }
    }

    // ---- correction ----

    fun startCorrection(
        current: CurrentMember,
        meetingId: String,
        note: String,
    ): MeetingProtocolDraftDto {
        val id = parseId(meetingId)
        requireWithinRate(limiter = writeLimiter, current = current)
        if (note.length > PROTOCOL_CORRECTION_NOTE_MAX * RAW_NOTE_FACTOR) throw BadRequestException("note too long")
        return transaction {
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = id, lock = true)
            if (confirmationRolesOf(ctx).isEmpty()) throw ForbiddenException()
            val cleaned = sanitizeProtocolText(note).trim()
            if (protocolCharCount(cleaned) < PROTOCOL_CORRECTION_NOTE_MIN || protocolCharCount(cleaned) > PROTOCOL_CORRECTION_NOTE_MAX) {
                throw BadRequestException("note length")
            }
            val draft = lockedDraft(id) ?: throw ConflictException("Es gibt noch keine fertiggestellte Fassung.")
            val latest = ProtocolVersionStore.latestVersion(id)
            if (draft[ProtocolDraftTable.phase] != ProtocolDraftPhase.LOCKED ||
                latest == null ||
                latest[ProtocolVersionTable.status] == ProtocolVersionStatus.SUPERSEDED
            ) {
                throw ConflictException("Das Protokoll kann gerade nicht korrigiert werden.")
            }
            val now = DbClock.nowLocalDateTime()
            val draftId = draft[ProtocolDraftTable.id]
            val snapshot = ProtocolSnapshots.parse(latest[ProtocolVersionTable.contentJson])
            val versionTasks =
                ProtocolVersionTaskTable
                    .selectAll()
                    .where { ProtocolVersionTaskTable.versionId eq latest[ProtocolVersionTable.id] }
                    .associateBy { it[ProtocolVersionTaskTable.taskKey].toString() }
            val itemIds =
                AgendaItemTable
                    .selectAll()
                    .where { AgendaItemTable.meetingId eq id }
                    .map { it[AgendaItemTable.id].toString() }
                    .toSet()

            // Replace the draft's texts and tasks by those of the newest version. Texts get version + 1 so a client that still has the old
            // version in hand sees a conflict instead of silently overwriting (the draft tables are the only ones that are ever deleted from).
            val existingBlocks =
                ProtocolDraftBlockTable.selectAll().where { ProtocolDraftBlockTable.draftId eq draftId }.associateBy {
                    it[ProtocolDraftBlockTable.agendaItemId].toString()
                }
            snapshot.items.filter { it.agendaItemId in itemIds }.forEach { item ->
                val existing = existingBlocks[item.agendaItemId]
                if (existing != null) {
                    ProtocolDraftBlockTable.update({ ProtocolDraftBlockTable.id eq existing[ProtocolDraftBlockTable.id] }) {
                        it[body] = item.body
                        it[version] = existing[ProtocolDraftBlockTable.version] + 1
                        it[updatedAt] = now
                    }
                } else if (item.body.isNotEmpty()) {
                    ProtocolDraftBlockTable.insert {
                        it[ProtocolDraftBlockTable.id] = Uuid.random()
                        it[ProtocolDraftBlockTable.draftId] = draftId
                        it[agendaItemId] = Uuid.parse(item.agendaItemId)
                        it[body] = item.body
                        it[version] = 1L
                        it[updatedAt] = now
                    }
                }
            }
            ProtocolDraftTaskTable.deleteWhere { ProtocolDraftTaskTable.draftId eq draftId }
            snapshot.items.filter { it.agendaItemId in itemIds }.forEach { item ->
                item.tasks.forEach { task ->
                    val stored = versionTasks[task.taskKey] ?: return@forEach
                    ProtocolDraftTaskTable.insert {
                        it[ProtocolDraftTaskTable.id] = Uuid.random()
                        it[ProtocolDraftTaskTable.draftId] = draftId
                        it[agendaItemId] = Uuid.parse(item.agendaItemId)
                        it[assigneeMemberId] = stored[ProtocolVersionTaskTable.assigneeMemberId]
                        it[description] = stored[ProtocolVersionTaskTable.description]
                        it[dueDate] = stored[ProtocolVersionTaskTable.dueDate]
                        it[createdAt] = now
                        it[taskKey] = Uuid.parse(task.taskKey)
                    }
                }
            }
            ProtocolDraftTable.update({ ProtocolDraftTable.id eq draftId }) {
                it[phase] = ProtocolDraftPhase.DRAFT
                it[correctionOfVersionNo] = latest[ProtocolVersionTable.versionNo]
                it[correctionNote] = cleaned
                it[updatedAt] = now
            }
            val audits = mutableListOf<MeetingSessionAuditSnapshot>()
            if (latest[ProtocolVersionTable.status] == ProtocolVersionStatus.AWAITING_CONFIRMATION &&
                ProtocolVersionStore.markSuperseded(latest[ProtocolVersionTable.id])
            ) {
                audits +=
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.PROTOCOL_VERSION_SUPERSEDED,
                        meetingId = id.toString(),
                        protocolVersionId = latest[ProtocolVersionTable.id].toString(),
                        protocolVersionNo = latest[ProtocolVersionTable.versionNo],
                    )
            }
            audits +=
                MeetingSessionAuditSnapshot(
                    kind = MeetingSessionAuditKind.PROTOCOL_CORRECTION_STARTED,
                    meetingId = id.toString(),
                    protocolVersionNo = latest[ProtocolVersionTable.versionNo],
                )
            audits.forEach { MeetingSessionAudit.record(actor = current, snapshot = it) }
            draftOps.buildDraft(ctx)
        }
    }

    // ---- approval item ----

    fun recordApproval(
        current: CurrentMember,
        agendaItemId: String,
        result: ProtocolApprovalResult,
    ): ProtocolApprovalDto {
        val itemId = parseId(agendaItemId)
        requireWithinRate(limiter = writeLimiter, current = current)
        return transaction {
            val itemMeeting =
                AgendaItemTable
                    .selectAll()
                    .where { AgendaItemTable.id eq itemId }
                    .singleOrNull()
                    ?.takeIf { it[AgendaItemTable.protocolApprovalSourceMeetingId] != null }
                    ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            val meetingId = itemMeeting[AgendaItemTable.meetingId]
            val ctx = MeetingSessionAccess.requireMeeting(current = current, meetingId = meetingId, lock = true)
            if (confirmationRolesOf(ctx).isEmpty()) throw ForbiddenException()
            val draft = draftOps.draftRow(meetingId)
            if (ctx.meetingRow[MeetingTable.openedAt] == null ||
                ctx.meetingRow[MeetingTable.status] == MeetingStatus.CANCELLED ||
                draft?.get(ProtocolDraftTable.phase) == ProtocolDraftPhase.LOCKED
            ) {
                throw ConflictException("Das Ergebnis kann gerade nicht erfasst werden.")
            }
            val item = AgendaItemTable.selectAll().where { AgendaItemTable.id eq itemId }.single()
            val sourceId = item[AgendaItemTable.protocolApprovalSourceMeetingId]!!
            val now = DbClock.nowLocalDateTime()
            val sourceVersion = ProtocolVersionStore.latestConfirmed(sourceId)?.get(ProtocolVersionTable.versionNo)
            AgendaItemTable.update({ AgendaItemTable.id eq itemId }) {
                it[protocolApprovalResult] = result
                it[protocolApprovalVersionNo] = sourceVersion
                it[approvalRecordedBy] = current.memberId
                it[approvalRecordedAt] = now
            }
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.PROTOCOL_APPROVAL_RECORDED,
                        meetingId = meetingId.toString(),
                        agendaItemId = itemId.toString(),
                        sourceMeetingId = sourceId.toString(),
                        protocolVersionNo = sourceVersion,
                        approvalResult = result.name,
                    ),
            )
            ProtocolApprovalItems.dto(
                item = AgendaItemTable.selectAll().where { AgendaItemTable.id eq itemId }.single(),
                editable = true,
            )!!
        }
    }

    // ---- tasks ----

    fun myTasks(current: CurrentMember): List<MyProtocolTaskDto> {
        requireWithinRate(limiter = readLimiter, current = current)
        return transaction { loadTasks(current.memberId) }
    }

    fun setTaskDone(
        current: CurrentMember,
        taskKey: String,
        done: Boolean,
    ): MyProtocolTaskDto {
        val key = parseId(taskKey)
        requireWithinRate(limiter = writeLimiter, current = current)
        return transaction {
            // Only a task of the newest CONFIRMED version AND assigned to the caller; everything else is the same not-found.
            val task =
                loadTasks(current.memberId).firstOrNull { it.taskKey == key.toString() }
                    ?: throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE)
            val now = DbClock.nowLocalDateTime()
            ProtocolVersionStore.setTaskDone(
                taskKey = key,
                meetingId = Uuid.parse(task.meetingId),
                assigneeMemberId = current.memberId,
                doneAt = if (done) now else null,
                now = now,
            )
            MeetingSessionAudit.record(
                actor = current,
                snapshot =
                    MeetingSessionAuditSnapshot(
                        kind = MeetingSessionAuditKind.PROTOCOL_TASK_STATUS_CHANGED,
                        meetingId = task.meetingId,
                        protocolTaskKey = key.toString(),
                        taskDone = done,
                    ),
            )
            loadTasks(current.memberId).first { it.taskKey == key.toString() }
        }
    }

    private fun loadTasks(memberId: Uuid): List<MyProtocolTaskDto> {
        val today = ServerClock.todayIn(OrganizationTimeZone.current())
        return ProtocolVersionStore.tasksOf(memberId = memberId, limit = TASK_LIST_LIMIT).mapNotNull { (task, status) ->
            val (committee, date) = ProtocolVersionStore.meetingLabel(task.meetingId) ?: return@mapNotNull null
            val doneAt = status?.get(ProtocolTaskStatusTable.doneAt)
            MyProtocolTaskDto(
                taskKey = task.taskKey.toString(),
                meetingId = task.meetingId.toString(),
                meetingDate = date,
                committeeName = committee,
                versionNo = task.versionNo,
                description = task.description,
                dueDate = task.dueDate,
                done = doneAt != null,
                doneAt = doneAt,
                overdue = doneAt == null && task.dueDate != null && task.dueDate < today,
            )
        }
    }

    // The PDF is archived by [ProtocolPdfArchiver] AFTER the confirming transaction committed (file I/O never holds a database transaction). It is
    // idempotent; a failure leaves the version CONFIRMED without a PDF and LEAD/MINUTES can retry by confirming again.

    // ---- helpers ----

    private class FinaliseTx(
        val outcome: ProtocolFinaliseOutcome,
        val versionId: Uuid?,
        val currentSha256: String?,
        val fully: Boolean?,
    )

    private fun lockedDraft(meetingId: Uuid): ResultRow? =
        ProtocolDraftTable
            .selectAll()
            .where { ProtocolDraftTable.meetingId eq meetingId }
            .forUpdate()
            .singleOrNull()

    private fun mode(): ProtocolConfirmationMode =
        OrganizationSettingsTable
            .select(OrganizationSettingsTable.protocolConfirmationMode)
            .singleOrNull()
            ?.get(OrganizationSettingsTable.protocolConfirmationMode) ?: ProtocolConfirmationMode.APP_CONFIRMATION

    /** The page-limit probe: renders the Protokoll WITHOUT the confirmation page and one page short, so the real PDF later fits. */
    private fun pdfFits(snapshot: ProtocolSnapshotV1): Boolean {
        val org =
            OrganizationSettingsTable
                .selectAll()
                .limit(1)
                .singleOrNull()
                ?.toOrganizationSettingsDto()
        return try {
            MeetingProtocolPdfGenerator.generate(
                snapshot = snapshot,
                contentSha256 = "",
                confirmations = emptyList(),
                organizationName = org?.name.orEmpty(),
                organizationAddressLines = org?.addressLines().orEmpty(),
                zone = OrganizationTimeZone.current(),
                includeConfirmationPage = false,
            )
            true
        } catch (_: PdfLimitExceeded) {
            false
        }
    }

    private fun persistTasks(
        versionId: Uuid,
        meetingId: Uuid,
        taskKeys: Map<String, DraftTaskKey>,
        snapshot: ProtocolSnapshotV1,
    ) {
        val draft = draftOps.draftRow(meetingId) ?: return
        val rows = ProtocolDraftTaskTable.selectAll().where { ProtocolDraftTaskTable.draftId eq draft[ProtocolDraftTable.id] }.toList()
        val inSnapshot =
            snapshot.items
                .flatMap { it.tasks }
                .map { it.taskKey }
                .toSet()
        rows.forEach { row ->
            val key = taskKeys.getValue(row[ProtocolDraftTaskTable.id].toString()).taskKey
            if (row[ProtocolDraftTaskTable.taskKey] == null) {
                ProtocolDraftTaskTable.update({ ProtocolDraftTaskTable.id eq row[ProtocolDraftTaskTable.id] }) {
                    it[taskKey] =
                        Uuid.parse(key)
                }
            }
            if (key in inSnapshot) {
                ProtocolVersionStore.insertVersionTask(
                    versionId = versionId,
                    taskKey = Uuid.parse(key),
                    assigneeMemberId = row[ProtocolDraftTaskTable.assigneeMemberId],
                    dueDate = row[ProtocolDraftTaskTable.dueDate],
                    description = row[ProtocolDraftTaskTable.description],
                )
            }
        }
    }

    /** Why finalising is not possible right now (for the caller's view), or `null`. Order matters: it is the order the checks run in. */
    private fun blockedReason(
        ctx: MeetingContext,
        draft: ResultRow?,
    ): ProtocolFinaliseOutcome? {
        val meeting = ctx.meetingRow
        if (meeting[MeetingTable.openedAt] == null || meeting[MeetingTable.status] == MeetingStatus.CANCELLED) {
            return ProtocolFinaliseOutcome.NOT_OPENED
        }
        val latest = ProtocolVersionStore.latestVersion(ctx.meetingId)
        val locked = draft?.get(ProtocolDraftTable.phase) == ProtocolDraftPhase.LOCKED
        if (locked || latest?.get(ProtocolVersionTable.status) == ProtocolVersionStatus.AWAITING_CONFIRMATION) {
            return ProtocolFinaliseOutcome.ALREADY_FINALISED
        }
        return null
    }

    private fun confirmationRolesOf(ctx: MeetingContext): Set<ProtocolConfirmationRole> =
        buildSet {
            if (MeetingSessionRole.LEAD in ctx.roles) add(ProtocolConfirmationRole.LEAD)
            if (MeetingSessionRole.MINUTES in ctx.roles) add(ProtocolConfirmationRole.MINUTES)
        }

    private fun requiredRoles(minutesRequired: Boolean): Set<ProtocolConfirmationRole> =
        if (minutesRequired) {
            setOf(
                ProtocolConfirmationRole.LEAD,
                ProtocolConfirmationRole.MINUTES,
            )
        } else {
            setOf(ProtocolConfirmationRole.LEAD)
        }

    private fun confirmedAudit(
        meetingId: Uuid,
        versionId: Uuid,
        versionNo: Int,
        role: ProtocolConfirmationRole,
    ) = MeetingSessionAuditSnapshot(
        kind = MeetingSessionAuditKind.PROTOCOL_CONFIRMED,
        meetingId = meetingId.toString(),
        protocolVersionId = versionId.toString(),
        protocolVersionNo = versionNo,
        confirmationRole = role.name,
    )

    /** One version as the DTO: confirmations with the confirmers' names, the roles still missing, whether the PDF exists. */
    fun versionDto(row: ResultRow): ProtocolVersionDto {
        val confirmations = ProtocolVersionStore.confirmations(row[ProtocolVersionTable.id])
        val names =
            MemberTable
                .selectAll()
                .where {
                    MemberTable.id inList
                        confirmations.map { it[ProtocolVersionConfirmationTable.memberId] }
                }.associate { it[MemberTable.id] to it[MemberTable.displayName] }
        val confirmationTable = ProtocolVersionConfirmationTable
        val confirmedRoles = confirmations.map { it[confirmationTable.role] }.toSet()
        val required = requiredRoles(row[ProtocolVersionTable.minutesRequired])
        val status = row[ProtocolVersionTable.status]
        val hasPdf = row[ProtocolVersionTable.pdfDocumentVersionId] != null
        return ProtocolVersionDto(
            versionId = row[ProtocolVersionTable.id].toString(),
            versionNo = row[ProtocolVersionTable.versionNo],
            status = status,
            contentSha256 = row[ProtocolVersionTable.contentSha256],
            correctionNote = row[ProtocolVersionTable.correctionNote],
            finalisedAt = row[ProtocolVersionTable.finalisedAt],
            confirmationMode = row[ProtocolVersionTable.confirmationMode],
            minutesRequired = row[ProtocolVersionTable.minutesRequired],
            confirmations =
                confirmations.map {
                    ProtocolConfirmationDto(
                        role = it[confirmationTable.role],
                        confirmerName = names[it[confirmationTable.memberId]].orEmpty(),
                        confirmedAt = it[confirmationTable.confirmedAt],
                    )
                },
            missingRoles = if (status == ProtocolVersionStatus.AWAITING_CONFIRMATION) required - confirmedRoles else emptySet(),
            pdfAvailable = hasPdf,
            pdfPending = status == ProtocolVersionStatus.CONFIRMED && !hasPdf,
        )
    }

    private fun parseId(raw: String): Uuid =
        runCatching { Uuid.parse(raw) }.getOrElse { throw NotFoundException(MeetingSessionAccess.NOT_FOUND_MESSAGE) }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        current: CurrentMember,
    ) {
        // Keyed by actor only: a per-meeting key would give every random id a fresh bucket.
        if (!limiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
    }
}
