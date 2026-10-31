package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.activism.ActivismAccessPolicy
import network.lapis.cloud.server.activism.ActivismActionValues
import network.lapis.cloud.server.activism.ActivismRateLimiter
import network.lapis.cloud.server.activism.ActivismSignalStore
import network.lapis.cloud.server.activism.ActivismStore
import network.lapis.cloud.server.activism.ActivismText
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismMaterialDocumentTable
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.server.db.generated.ActivismTextBlockTable
import network.lapis.cloud.server.db.generated.CostCenterTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.EventVolunteerShiftTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.documents.FolderAccessLevels
import network.lapis.cloud.server.events.EventStore
import network.lapis.cloud.server.events.EventVolunteerStore
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.server.messaging.DirectMessageBlockStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ActivismActionDetailDto
import network.lapis.cloud.shared.domain.ActivismActionInput
import network.lapis.cloud.shared.domain.ActivismActionSummaryDto
import network.lapis.cloud.shared.domain.ActivismAreaSumDto
import network.lapis.cloud.shared.domain.ActivismAuditKind
import network.lapis.cloud.shared.domain.ActivismAuditSnapshot
import network.lapis.cloud.shared.domain.ActivismCandidateDto
import network.lapis.cloud.shared.domain.ActivismContributionDto
import network.lapis.cloud.shared.domain.ActivismContributionInput
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismContributionsViewDto
import network.lapis.cloud.shared.domain.ActivismForm
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismLimits
import network.lapis.cloud.shared.domain.ActivismListFilter
import network.lapis.cloud.shared.domain.ActivismMaterialDto
import network.lapis.cloud.shared.domain.ActivismNoticeSettingsDto
import network.lapis.cloud.shared.domain.ActivismReportDto
import network.lapis.cloud.shared.domain.ActivismResponsibleDto
import network.lapis.cloud.shared.domain.ActivismResultDto
import network.lapis.cloud.shared.domain.ActivismResultInput
import network.lapis.cloud.shared.domain.ActivismShiftSummaryDto
import network.lapis.cloud.shared.domain.ActivismSignalStateDto
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.ActivismTaskDto
import network.lapis.cloud.shared.domain.ActivismTaskInput
import network.lapis.cloud.shared.domain.ActivismTextBlockDto
import network.lapis.cloud.shared.domain.ActivismTransitions
import network.lapis.cloud.shared.domain.ActivismViewerRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.ActivismInvalidTransitionException
import network.lapis.cloud.shared.rpc.ActivismLimitReachedException
import network.lapis.cloud.shared.rpc.ActivismNotRunningException
import network.lapis.cloud.shared.rpc.ActivismReportOpenException
import network.lapis.cloud.shared.rpc.ActivismResponsibleLimitException
import network.lapis.cloud.shared.rpc.ActivismRevisionConflictException
import network.lapis.cloud.shared.rpc.ActivismSelfApprovalException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IActivismService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * V1.9.246 "Aktionen A1" -- see [IActivismService] and `docs/features/activism.adoc`.
 *
 * * Every method first needs a participant ([ActivismAccessPolicy.isParticipant]); per action the rights come from [ActivismAccessPolicy] ONLY.
 * * An unknown action and an invisible one answer with the SAME `NotFoundException("Not found")` -- also for [reportAction] and [setSignal].
 * * All reads and writes of the tables go through `ActivismStore` / `ActivismSignalStore`.
 * * Write order inside a transaction: action row locked first, children, settings row, the audit entry LAST. The rate limiter runs BEFORE the
 *   transaction (never inside it). Audit snapshots hold ids, enums and numbers only, never free text; signals and the creation of a report are
 *   NOT audited.
 * * Logging: ids and numbers only.
 */
internal class ActivismService(
    private val call: ApplicationCall,
    private val limiter: ActivismRateLimiter,
    private val clock: () -> LocalDateTime = { ServerClock.now() },
) : IActivismService {
    // ── Read ────────────────────────────────────────────────────────────────────────────────

    override suspend fun listActions(filter: ActivismListFilter): List<ActivismActionSummaryDto> {
        val viewer = participant()
        val statuses = filter.statuses.ifEmpty { listOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING) }
        return transaction {
            val rows = ActivismStore.listActions(statuses = statuses)
            val mine = ActivismStore.responsibleActionIds(memberId = viewer.memberId, actionIds = rows.map { it[ActivismActionTable.id] })
            val visible =
                rows.filter {
                    ActivismAccessPolicy.resolve(
                        viewer = viewer,
                        status = it[ActivismActionTable.status],
                        responsible =
                            it[ActivismActionTable.id] in mine,
                    ) !=
                        null &&
                        matches(row = it, form = filter.form)
                }
            val progress = ActivismStore.progressOf(visible.map { it[ActivismActionTable.id] })
            visible.map { it.toSummary(progress.getValue(it[ActivismActionTable.id])) }
        }
    }

    override suspend fun getAction(id: String): ActivismActionDetailDto {
        val viewer = participant()
        val actionId = parseId(id)
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = false)
            detail(row = row, viewer = viewer, role = role)
        }
    }

    // ── Create / edit / lifecycle ───────────────────────────────────────────────────────────

    override suspend fun createAction(input: ActivismActionInput): ActivismActionDetailDto {
        val viewer = participant()
        val values = validate(input)
        planningRate(viewer)
        val now = clock()
        return transaction {
            val id = Uuid.random()
            ActivismStore.insertAction(id = id, values = values, createdBy = viewer.memberId, now = now)
            // The creator becomes the responsible person: otherwise the idea would be invisible to its own author.
            ActivismStore.replaceResponsibles(actionId = id, memberIds = setOf(viewer.memberId))
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = id,
                action = AuditAction.CREATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.CREATED, status = ActivismStatus.IDEA),
                now = now,
            )
            val (row, role) = loadVisible(id = id, viewer = viewer, lock = false)
            detail(row = row, viewer = viewer, role = role)
        }
    }

    override suspend fun updateAction(
        id: String,
        input: ActivismActionInput,
        expectedRevision: Int,
    ): ActivismActionDetailDto {
        val viewer = participant()
        val actionId = parseId(id)
        val values = validate(input)
        planningRate(viewer)
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            if (row[ActivismActionTable.status] == ActivismStatus.WITHDRAWN) throw ActivismInvalidTransitionException()
            if (row[ActivismActionTable.revision] != expectedRevision) throw ActivismRevisionConflictException()
            val status = row[ActivismActionTable.status]
            if (status == ActivismStatus.PLANNED ||
                status == ActivismStatus.RUNNING ||
                status == ActivismStatus.COMPLETED ||
                status == ActivismStatus.AWAITING_REVIEW
            ) {
                if (!(values.formAppointment || values.formProgress || values.formSignal)) {
                    throw BadRequestException("An action needs at least one form")
                }
            }
            val changed = changedFields(row = row, v = values)
            ActivismStore.updateActionValues(id = actionId, values = values, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.UPDATED, changedFields = changed),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun setActionStatus(
        id: String,
        target: ActivismStatus,
    ): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            val from = row[ActivismActionTable.status]
            // Review, approval and withdrawal have their own methods; they carry their own checks (reason, creator rule, report cleanup).
            if (target !in DIRECT_TARGETS || from == ActivismStatus.AWAITING_REVIEW || from == ActivismStatus.WITHDRAWN) {
                throw ActivismInvalidTransitionException()
            }
            if (!ActivismTransitions.allowed(
                    from = from,
                    to = target,
                    role = role,
                    reviewRequired = ActivismStore.reviewRequired(),
                )
            ) {
                throw transitionRefusal(role)
            }
            requireAForm(row = row, target = target)
            ActivismStore.setStatus(id = actionId, status = target, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.STATUS_CHANGED, status = target),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun submitForReview(id: String): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            val from = row[ActivismActionTable.status]
            if (!ActivismTransitions.allowed(
                    from = from,
                    to = ActivismStatus.AWAITING_REVIEW,
                    role = role,
                    reviewRequired = ActivismStore.reviewRequired(),
                )
            ) {
                throw transitionRefusal(role)
            }
            ActivismStore.setStatus(id = actionId, status = ActivismStatus.AWAITING_REVIEW, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.SUBMITTED, status = ActivismStatus.AWAITING_REVIEW),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun approveAction(id: String): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            val from = row[ActivismActionTable.status]
            if (!ActivismTransitions.allowed(from = from, to = ActivismStatus.PLANNED, role = role, reviewRequired = true) ||
                from != ActivismStatus.AWAITING_REVIEW
            ) {
                throw transitionRefusal(role)
            }
            // The comparison runs against the LOCKED row.
            if (row[ActivismActionTable.createdBy] == viewer.memberId) throw ActivismSelfApprovalException()
            requireAForm(row = row, target = ActivismStatus.PLANNED)
            ActivismStore.setStatus(id = actionId, status = ActivismStatus.PLANNED, now = now, approvedBy = viewer.memberId)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.APPROVED, status = ActivismStatus.PLANNED),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun rejectReview(
        id: String,
        reason: String,
    ): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val cleaned = ActivismText.require(raw = reason, maxChars = ActivismLimits.REJECT_REASON_MAX)
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            val from = row[ActivismActionTable.status]
            if (from != ActivismStatus.AWAITING_REVIEW ||
                !ActivismTransitions.allowed(from = from, to = ActivismStatus.IDEA, role = role, reviewRequired = true)
            ) {
                throw transitionRefusal(role)
            }
            ActivismStore.setStatus(id = actionId, status = ActivismStatus.IDEA, now = now, reviewRejectReason = cleaned)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot =
                    ActivismAuditSnapshot(
                        kind = ActivismAuditKind.REJECTED,
                        status = ActivismStatus.IDEA,
                        charCount = cleaned.length,
                    ),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun setResponsibles(
        id: String,
        memberIds: List<String>,
    ): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val ids = memberIds.map { parseId(it) }.toSet()
        if (ids.size !in ActivismLimits.RESPONSIBLES_MIN..ActivismLimits.RESPONSIBLES_MAX) throw ActivismResponsibleLimitException()
        val now = clock()
        return transaction {
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireManager(role)
            if (ActivismStore.participantRows(ids).size != ids.size) throw BadRequestException("Unknown member")
            ActivismStore.replaceResponsibles(actionId = actionId, memberIds = ids)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.RESPONSIBLES_CHANGED, count = ids.size),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun setPlanningLinks(
        id: String,
        costCenterId: String?,
        eventId: String?,
    ): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val costCenter = costCenterId?.let { parseId(it) }
        val event = eventId?.let { parseId(it) }
        val now = clock()
        return transaction {
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireManager(role)
            if (costCenter != null) {
                val active = CostCenterTable.selectAll().where { CostCenterTable.id eq costCenter }.singleOrNull()
                if (active == null || !active[CostCenterTable.active]) throw BadRequestException("Unknown cost centre")
            }
            // A manager (BOARD / ADMIN) may see every event, drafts included -- the same gate as EventVolunteerService.listShifts.
            if (event != null && EventStore.getEventOrNull(event) == null) throw BadRequestException("Unknown event")
            ActivismStore.setLinks(id = actionId, costCenterId = costCenter, eventId = event, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.LINKS_CHANGED),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun withdrawAction(
        id: String,
        reason: String,
    ): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val cleaned =
            ActivismText.requireBetween(
                raw = reason,
                min = ActivismLimits.WITHDRAW_REASON_MIN,
                max = ActivismLimits.WITHDRAW_REASON_MAX,
            )
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireManager(role)
            if (row[ActivismActionTable.status] == ActivismStatus.WITHDRAWN) throw ActivismInvalidTransitionException()
            ActivismStore.setStatus(id = actionId, status = ActivismStatus.WITHDRAWN, now = now, withdrawnReason = cleaned)
            val closed = ActivismStore.resolveOpenReportsOf(actionId = actionId, by = viewer.memberId, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.VOID,
                snapshot =
                    ActivismAuditSnapshot(
                        kind = ActivismAuditKind.WITHDRAWN,
                        status = ActivismStatus.WITHDRAWN,
                        count = closed,
                        charCount = cleaned.length,
                    ),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun restoreAction(id: String): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireManager(role)
            if (row[ActivismActionTable.status] != ActivismStatus.WITHDRAWN) throw ActivismInvalidTransitionException()
            ActivismStore.setStatus(id = actionId, status = ActivismStatus.IDEA, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.RESTORED, status = ActivismStatus.IDEA),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    override suspend fun recordResult(
        id: String,
        input: ActivismResultInput,
    ): ActivismActionDetailDto {
        val viewer = participant()
        planningRate(viewer)
        val actionId = parseId(id)
        val text = ActivismText.cleanMultiline(raw = input.text, maxChars = ActivismLimits.RESULT_TEXT_MAX)
        val participants = input.participants?.also { requireRange(value = it, min = 0, max = ActivismLimits.RESULT_NUMBER_MAX) }
        val quantity = input.quantity?.also { requireRange(value = it, min = 0, max = ActivismLimits.RESULT_NUMBER_MAX) }
        val now = clock()
        return transaction {
            val (row, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            if (row[ActivismActionTable.status] != ActivismStatus.COMPLETED) throw ActivismNotRunningException()
            ActivismStore.setResult(id = actionId, participants = participants, quantity = quantity, text = text, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_ACTION,
                entityId = actionId,
                action = AuditAction.UPDATE,
                snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.RESULT_RECORDED, charCount = text?.length),
                now = now,
            )
            reload(id = actionId, viewer = viewer)
        }
    }

    // ── Checklist, text blocks, material ────────────────────────────────────────────────────

    override suspend fun addTask(
        actionId: String,
        input: ActivismTaskInput,
    ): ActivismTaskDto {
        val viewer = participant()
        val id = parseId(actionId)
        val text = ActivismText.require(raw = input.text, maxChars = ActivismLimits.TASK_TEXT_MAX)
        val assignee = input.assigneeMemberId?.let { parseId(it) }
        planningRate(viewer)
        return transaction {
            val (_, role) = loadVisible(id = id, viewer = viewer, lock = true)
            requireEdit(role)
            if (ActivismStore.listTasks(id).size >= ActivismLimits.TASKS_MAX) throw ActivismLimitReachedException()
            requireParticipant(assignee)
            val taskId = ActivismStore.insertTask(actionId = id, text = text, assignee = assignee, dueOn = input.dueOn)
            taskDto(row = ActivismStore.getTask(taskId)!!, viewer = viewer)
        }
    }

    override suspend fun updateTask(
        taskId: String,
        input: ActivismTaskInput,
    ): ActivismTaskDto {
        val viewer = participant()
        val task = parseId(taskId)
        val text = ActivismText.require(raw = input.text, maxChars = ActivismLimits.TASK_TEXT_MAX)
        val assignee = input.assigneeMemberId?.let { parseId(it) }
        planningRate(viewer)
        return transaction {
            val actionId = taskAction(task)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            ActivismStore.getTask(task) ?: throw notFound()
            requireParticipant(assignee)
            ActivismStore.updateTask(id = task, text = text, assignee = assignee, dueOn = input.dueOn)
            taskDto(row = ActivismStore.getTask(task)!!, viewer = viewer)
        }
    }

    override suspend fun setTaskDone(
        taskId: String,
        done: Boolean,
    ): ActivismTaskDto {
        val viewer = participant()
        val task = parseId(taskId)
        planningRate(viewer)
        val now = clock()
        return transaction {
            val actionId = taskAction(task)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            val row = ActivismStore.getTask(task) ?: throw notFound()
            // The person a task is assigned to may tick it off, without being a responsible person.
            if (!ActivismAccessPolicy.mayEdit(role) &&
                row[ActivismTaskTable.assigneeMemberId] != viewer.memberId
            ) {
                throw ForbiddenException()
            }
            ActivismStore.setTaskDone(id = task, done = done, by = viewer.memberId, now = now)
            taskDto(row = ActivismStore.getTask(task)!!, viewer = viewer)
        }
    }

    override suspend fun deleteTask(taskId: String) {
        val viewer = participant()
        val task = parseId(taskId)
        planningRate(viewer)
        transaction {
            val actionId = taskAction(task)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            ActivismStore.deleteTask(task)
        }
    }

    override suspend fun moveTask(
        taskId: String,
        newPosition: Int,
    ) {
        val viewer = participant()
        val task = parseId(taskId)
        planningRate(viewer)
        transaction {
            val actionId = taskAction(task)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            ActivismStore.moveTask(actionId = actionId, taskId = task, newPosition = newPosition)
        }
    }

    override suspend fun addTextBlock(
        actionId: String,
        title: String,
        body: String,
    ): ActivismTextBlockDto {
        val viewer = participant()
        val id = parseId(actionId)
        val cleanTitle = ActivismText.require(raw = title, maxChars = ActivismLimits.TEXT_BLOCK_TITLE_MAX)
        val cleanBody = ActivismText.requireMultiline(raw = body, maxChars = ActivismLimits.TEXT_BLOCK_BODY_MAX)
        planningRate(viewer)
        return transaction {
            val (_, role) = loadVisible(id = id, viewer = viewer, lock = true)
            requireEdit(role)
            if (ActivismStore.listTextBlocks(id).size >= ActivismLimits.TEXT_BLOCKS_MAX) throw ActivismLimitReachedException()
            val blockId = ActivismStore.insertTextBlock(actionId = id, title = cleanTitle, body = cleanBody)
            ActivismStore.getTextBlock(blockId)!!.toDto()
        }
    }

    override suspend fun updateTextBlock(
        blockId: String,
        title: String,
        body: String,
    ): ActivismTextBlockDto {
        val viewer = participant()
        val block = parseId(blockId)
        val cleanTitle = ActivismText.require(raw = title, maxChars = ActivismLimits.TEXT_BLOCK_TITLE_MAX)
        val cleanBody = ActivismText.requireMultiline(raw = body, maxChars = ActivismLimits.TEXT_BLOCK_BODY_MAX)
        planningRate(viewer)
        return transaction {
            val actionId = textBlockAction(block)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            ActivismStore.getTextBlock(block) ?: throw notFound()
            ActivismStore.updateTextBlock(id = block, title = cleanTitle, body = cleanBody)
            ActivismStore.getTextBlock(block)!!.toDto()
        }
    }

    override suspend fun deleteTextBlock(blockId: String) {
        val viewer = participant()
        val block = parseId(blockId)
        planningRate(viewer)
        transaction {
            val actionId = textBlockAction(block)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            ActivismStore.deleteTextBlock(block)
        }
    }

    override suspend fun linkMaterialDocument(
        actionId: String,
        documentId: String,
    ) {
        val viewer = participant()
        val id = parseId(actionId)
        val document = parseId(documentId)
        planningRate(viewer)
        transaction {
            val (_, role) = loadVisible(id = id, viewer = viewer, lock = true)
            requireEdit(role)
            // The linking person must be able to read the document themselves; an unreadable one looks exactly like an unknown one.
            val row = DocumentTable.selectAll().where { DocumentTable.id eq document }.singleOrNull()
            if (row == null || !documentReadable(viewer = viewer, doc = row, snapshot = FolderAccessLevels.snapshot())) throw notFound()
            val current = ActivismStore.listMaterial(id)
            if (current.none { it[ActivismMaterialDocumentTable.documentId] == document } && current.size >= ActivismLimits.MATERIAL_MAX) {
                throw ActivismLimitReachedException()
            }
            ActivismStore.linkMaterial(actionId = id, documentId = document)
        }
    }

    override suspend fun unlinkMaterialDocument(
        actionId: String,
        documentId: String,
    ) {
        val viewer = participant()
        val id = parseId(actionId)
        val document = parseId(documentId)
        planningRate(viewer)
        transaction {
            val (_, role) = loadVisible(id = id, viewer = viewer, lock = true)
            requireEdit(role)
            ActivismStore.unlinkMaterial(actionId = id, documentId = document)
        }
    }

    // ── Contributions ───────────────────────────────────────────────────────────────────────

    override suspend fun reportContribution(
        actionId: String,
        input: ActivismContributionInput,
    ): ActivismContributionDto {
        val viewer = participant()
        val id = parseId(actionId)
        val quantity =
            requireRange(
                value = input.quantity,
                min = ActivismLimits.CONTRIBUTION_QUANTITY_MIN,
                max = ActivismLimits.CONTRIBUTION_QUANTITY_MAX,
            )
        val area = ActivismText.clean(raw = input.area, maxChars = ActivismLimits.AREA_MAX)
        val note = ActivismText.cleanMultiline(raw = input.note, maxChars = ActivismLimits.CONTRIBUTION_NOTE_MAX)
        participationRate(viewer)
        val now = clock()
        return transaction {
            val (row, _) = loadVisible(id = id, viewer = viewer, lock = true)
            if (!ActivismTransitions.acceptsParticipation(row[ActivismActionTable.status])) throw ActivismNotRunningException()
            if (!row[ActivismActionTable.formProgress]) throw BadRequestException("This action has no progress form")
            // Permanent cap, counted under the action lock (a restart does not reset it).
            val contributionId =
                ActivismStore.insertContributionCapped(
                    actionId = id,
                    memberId = viewer.memberId,
                    quantity = quantity,
                    area = area,
                    note = note,
                    now = now,
                    since = now.minus(ActivismLimits.RATE_WINDOW),
                    cap = ActivismLimits.MAX_CONTRIBUTIONS_PER_MEMBER_ACTION_24H,
                ) ?: throw ActivismLimitReachedException()
            contributionDto(row = ActivismStore.getContribution(contributionId)!!, viewer = viewer, includeIdentity = false, now = now)
        }
    }

    override suspend fun updateOwnContribution(
        contributionId: String,
        input: ActivismContributionInput,
    ): ActivismContributionDto {
        val viewer = participant()
        val contribution = parseId(contributionId)
        val quantity =
            requireRange(
                value = input.quantity,
                min = ActivismLimits.CONTRIBUTION_QUANTITY_MIN,
                max = ActivismLimits.CONTRIBUTION_QUANTITY_MAX,
            )
        val area = ActivismText.clean(raw = input.area, maxChars = ActivismLimits.AREA_MAX)
        val note = ActivismText.cleanMultiline(raw = input.note, maxChars = ActivismLimits.CONTRIBUTION_NOTE_MAX)
        participationRate(viewer)
        val now = clock()
        return transaction {
            val actionId = contributionAction(contribution)
            loadVisible(id = actionId, viewer = viewer, lock = true)
            val row = ownEditableContribution(id = contribution, viewer = viewer, now = now)
            ActivismStore.updateContribution(id = contribution, quantity = quantity, area = area, note = note, now = now)
            contributionDto(
                row = ActivismStore.getContribution(row[ActivismContributionTable.id])!!,
                viewer = viewer,
                includeIdentity = false,
                now = now,
            )
        }
    }

    override suspend fun withdrawOwnContribution(contributionId: String) {
        val viewer = participant()
        val contribution = parseId(contributionId)
        participationRate(viewer)
        val now = clock()
        transaction {
            val actionId = contributionAction(contribution)
            loadVisible(id = actionId, viewer = viewer, lock = true)
            ownEditableContribution(id = contribution, viewer = viewer, now = now)
            ActivismStore.deleteContribution(contribution)
        }
    }

    override suspend fun decideContribution(
        contributionId: String,
        confirm: Boolean,
        reason: String?,
    ): ActivismContributionDto {
        val viewer = participant()
        planningRate(viewer)
        val contribution = parseId(contributionId)
        val cleaned = if (confirm) null else ActivismText.require(raw = reason, maxChars = ActivismLimits.REJECT_REASON_MAX)
        val now = clock()
        return transaction {
            val actionId = contributionAction(contribution)
            val (_, role) = loadVisible(id = actionId, viewer = viewer, lock = true)
            requireEdit(role)
            val row = ActivismStore.getContribution(contribution) ?: throw notFound()
            if (row[ActivismContributionTable.status] != ActivismContributionStatus.REPORTED) throw ConflictException("Already decided")
            val target = if (confirm) ActivismContributionStatus.CONFIRMED else ActivismContributionStatus.REJECTED
            ActivismStore.decideContribution(id = contribution, status = target, reason = cleaned, by = viewer.memberId, now = now)
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_CONTRIBUTION,
                entityId = contribution,
                action = AuditAction.UPDATE,
                snapshot =
                    ActivismAuditSnapshot(
                        kind = if (confirm) ActivismAuditKind.CONTRIBUTION_CONFIRMED else ActivismAuditKind.CONTRIBUTION_REJECTED,
                        count = row[ActivismContributionTable.quantity],
                        charCount = cleaned?.length,
                    ),
                now = now,
            )
            contributionDto(row = ActivismStore.getContribution(contribution)!!, viewer = viewer, includeIdentity = true, now = now)
        }
    }

    override suspend fun listContributions(actionId: String): ActivismContributionsViewDto {
        val viewer = participant()
        val id = parseId(actionId)
        val now = clock()
        return transaction {
            val (_, role) = loadVisible(id = id, viewer = viewer, lock = false)
            // Own rows and area sums come from SQL; only a RESPONSIBLE / MANAGER receives the (capped) full list.
            val own = ActivismStore.listOwnContributions(actionId = id, memberId = viewer.memberId)
            val rows = if (ActivismAccessPolicy.mayEdit(role)) ActivismStore.listContributions(actionId = id) else emptyList()
            val names = ActivismStore.memberNames(rows.mapNotNull { it[ActivismContributionTable.memberId] }.toSet())
            val sums =
                ActivismStore
                    .areaSums(id)
                    .map { (area, sum) -> ActivismAreaSumDto(area = area, sum = sum) }
                    .sortedWith(compareByDescending<ActivismAreaSumDto> { it.sum }.thenBy { it.area ?: "" })
            ActivismContributionsViewDto(
                own = own.map { contributionDto(row = it, viewer = viewer, includeIdentity = false, now = now) },
                areaSums = sums,
                all =
                    if (ActivismAccessPolicy.mayEdit(role)) {
                        rows.map { contributionDto(row = it, viewer = viewer, includeIdentity = true, now = now, names = names) }
                    } else {
                        null
                    },
            )
        }
    }

    // ── Signal ──────────────────────────────────────────────────────────────────────────────

    override suspend fun setSignal(
        actionId: String,
        on: Boolean,
    ): ActivismSignalStateDto {
        val viewer = participant()
        val id = parseId(actionId)
        participationRate(viewer)
        return transaction {
            val (row, _) = loadVisible(id = id, viewer = viewer, lock = true)
            if (on) {
                if (!ActivismTransitions.acceptsParticipation(row[ActivismActionTable.status])) throw ActivismNotRunningException()
                if (!row[ActivismActionTable.formSignal]) throw BadRequestException("This action has no signal form")
                ActivismSignalStore.set(actionId = id, memberId = viewer.memberId)
            } else {
                ActivismSignalStore.clear(actionId = id, memberId = viewer.memberId)
            }
            signalState(actionId = id, viewer = viewer)
        }
    }

    // ── Reports ─────────────────────────────────────────────────────────────────────────────

    override suspend fun reportAction(
        actionId: String,
        reason: String,
    ) {
        val viewer = participant()
        val id = parseId(actionId)
        val cleaned =
            ActivismText.requireBetween(
                raw = reason,
                min = ActivismLimits.REPORT_REASON_MIN,
                max = ActivismLimits.REPORT_REASON_MAX,
            )
        if (!limiter.allowReport(viewer.memberId)) throw ServiceBusyException()
        val now = clock()
        transaction {
            loadVisible(id = id, viewer = viewer, lock = true)
            // Deliberately NOT audited: the audit log cannot be deleted and must not carry who reported what.
            when (
                ActivismStore.insertReportLimited(
                    actionId = id,
                    reporter = viewer.memberId,
                    reason = cleaned,
                    now = now,
                    since = now.minus(ActivismLimits.RATE_WINDOW),
                    maxPerDay = ActivismLimits.MAX_REPORTS_PER_MEMBER_24H,
                )
            ) {
                ActivismStore.ReportOutcome.OPENED -> Unit
                ActivismStore.ReportOutcome.ALREADY_OPEN -> throw ActivismReportOpenException()
                ActivismStore.ReportOutcome.LIMIT_REACHED -> throw ActivismLimitReachedException()
            }
        }
    }

    override suspend fun listReports(): List<ActivismReportDto> {
        val viewer = participant()
        requireManagerRole(viewer)
        return transaction {
            val rows = ActivismStore.listOpenReports(REPORT_LIST_LIMIT)
            val titles =
                ActivismActionTable
                    .selectAll()
                    .where { ActivismActionTable.id inList rows.map { it[ActivismReportTable.actionId] } }
                    .associate { it[ActivismActionTable.id] to it[ActivismActionTable.title] }
            rows.map {
                ActivismReportDto(
                    id = it[ActivismReportTable.id].toString(),
                    actionId = it[ActivismReportTable.actionId].toString(),
                    actionTitle = titles[it[ActivismReportTable.actionId]] ?: "",
                    reason = it[ActivismReportTable.reason],
                    status = it[ActivismReportTable.status],
                    createdAt = it[ActivismReportTable.createdAt],
                )
            }
        }
    }

    override suspend fun resolveReport(reportId: String) {
        val viewer = participant()
        requireManagerRole(viewer)
        val report = parseId(reportId)
        val now = clock()
        transaction {
            val actionId = ActivismStore.getReport(report)?.get(ActivismReportTable.actionId) ?: throw notFound()
            ActivismStore.getAction(id = actionId, lock = true)
            val row = ActivismStore.getReport(report) ?: throw notFound()
            if (row[ActivismReportTable.status] == network.lapis.cloud.shared.domain.ActivismReportStatus.OPEN) {
                ActivismStore.resolveReport(id = report, by = viewer.memberId, now = now)
                audit(
                    actor = viewer,
                    type = AuditEntityType.ACTIVISM_REPORT,
                    entityId = report,
                    action = AuditAction.UPDATE,
                    snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.REPORT_RESOLVED),
                    now = now,
                )
            }
        }
    }

    // ── Operator settings ───────────────────────────────────────────────────────────────────

    override suspend fun getNoticeSettings(): ActivismNoticeSettingsDto {
        val viewer = participant()
        requireManagerRole(viewer)
        return transaction {
            val country = ActivismStore.noticeCountry()
            ActivismNoticeSettingsDto(
                country = country,
                reviewRequired = ActivismStore.reviewRequired(),
                texts = ActivismStore.noticeTexts(country),
            )
        }
    }

    override suspend fun setNoticeCountry(code: String) {
        val viewer = participant()
        requireManagerRole(viewer)
        val country = code.trim().uppercase()
        if (!COUNTRY_CODE.matches(country)) throw BadRequestException("Invalid country code")
        val now = clock()
        transaction {
            ActivismStore.lockSettings()
            if (ActivismStore.noticeCountry() != country) {
                ActivismStore.setNoticeCountry(country)
                audit(
                    actor = viewer,
                    type = AuditEntityType.ACTIVISM_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    snapshot = ActivismAuditSnapshot(kind = ActivismAuditKind.NOTICE_COUNTRY_CHANGED, country = country),
                    now = now,
                )
            }
        }
    }

    override suspend fun setNoticeText(
        kind: ActivismKind,
        body: String?,
    ) {
        val viewer = participant()
        requireManagerRole(viewer)
        val cleaned = ActivismText.cleanMultiline(raw = body, maxChars = ActivismLimits.NOTICE_TEXT_MAX)
        val now = clock()
        transaction {
            ActivismStore.lockSettings()
            val country = ActivismStore.noticeCountry()
            if (cleaned == null) {
                ActivismStore.deleteNoticeText(country = country, kind = kind)
            } else {
                ActivismStore.upsertNoticeText(country = country, kind = kind, body = cleaned, by = viewer.memberId, now = now)
            }
            audit(
                actor = viewer,
                type = AuditEntityType.ACTIVISM_SETTINGS,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.UPDATE,
                snapshot =
                    ActivismAuditSnapshot(
                        kind = if (cleaned == null) ActivismAuditKind.NOTICE_TEXT_RESET else ActivismAuditKind.NOTICE_TEXT_CHANGED,
                        changedFields = listOf(kind.name),
                        country = country,
                        charCount = cleaned?.length,
                        sha256 = cleaned?.let { sha256(it) },
                    ),
                now = now,
            )
        }
    }

    override suspend fun setReviewRequired(enabled: Boolean) {
        val viewer = participant()
        requireManagerRole(viewer)
        val now = clock()
        transaction {
            ActivismStore.lockSettings()
            if (ActivismStore.reviewRequired() != enabled) {
                ActivismStore.setReviewRequired(enabled)
                audit(
                    actor = viewer,
                    type = AuditEntityType.ACTIVISM_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    snapshot =
                        ActivismAuditSnapshot(
                            kind = if (enabled) ActivismAuditKind.REVIEW_REQUIRED_ON else ActivismAuditKind.REVIEW_REQUIRED_OFF,
                        ),
                    now = now,
                )
            }
        }
    }

    override suspend fun listActivismCandidates(query: String): List<ActivismCandidateDto> {
        val viewer = participant()
        val needle = query.trim() // LIKE metacharacters are escaped in the store (containsLikePattern)
        if (needle.length !in ActivismLimits.CANDIDATE_QUERY_MIN..ActivismLimits.CANDIDATE_QUERY_MAX) return emptyList()
        if (!limiter.allowSearch(viewer.memberId)) throw ServiceBusyException()
        return transaction {
            val manager = ActivismAccessPolicy.isManager(viewer)
            if (!manager && !ActivismStore.isResponsibleForAny(viewer.memberId)) throw ForbiddenException()
            // Every participant becomes responsible by creating an idea, so the gate alone is no barrier: a non-manager only finds members who
            // opted in to being found (dm_discoverable) and are not blocked either way -- the same rules as the direct-message recipient search.
            // A manager administers the module and keeps the full directory.
            val excluded =
                if (manager) {
                    emptySet()
                } else {
                    DirectMessageBlockStore.blockedEitherWay(viewer.memberId) + MemberFinderBlockStore.blockedEitherWay(viewer.memberId)
                }
            ActivismStore
                .searchParticipants(
                    needle = needle,
                    limit = ActivismLimits.CANDIDATES_MAX,
                    onlyDiscoverable = !manager,
                    excluded = excluded,
                ).map {
                    ActivismCandidateDto(memberId = it[MemberTable.id].toString(), name = it[MemberTable.displayName])
                }
        }
    }

    // ── Helpers: access ─────────────────────────────────────────────────────────────────────

    private fun participant(): CurrentMember {
        val viewer = resolveCurrentMember(call)
        if (!ActivismAccessPolicy.isParticipant(viewer)) throw ForbiddenException()
        return viewer
    }

    private fun notFound() = NotFoundException(NOT_FOUND)

    /** The row (locked when [lock]) and the viewer's role; an unknown and an invisible action are indistinguishable. */
    private fun loadVisible(
        id: Uuid,
        viewer: CurrentMember,
        lock: Boolean,
    ): Pair<ResultRow, ActivismViewerRole> {
        val row = ActivismStore.getAction(id = id, lock = lock) ?: throw notFound()
        val role =
            ActivismAccessPolicy.resolve(
                viewer = viewer,
                status = row[ActivismActionTable.status],
                responsible = ActivismStore.isResponsible(actionId = id, memberId = viewer.memberId),
            ) ?: throw notFound()
        return row to role
    }

    private fun requireEdit(role: ActivismViewerRole) {
        if (!ActivismAccessPolicy.mayEdit(role)) throw ForbiddenException()
    }

    private fun requireManager(role: ActivismViewerRole) {
        if (role != ActivismViewerRole.MANAGER) throw ForbiddenException()
    }

    private fun requireManagerRole(viewer: CurrentMember) {
        if (!ActivismAccessPolicy.isManager(viewer)) throw ForbiddenException()
    }

    private fun transitionRefusal(role: ActivismViewerRole): Exception =
        if (role == ActivismViewerRole.MEMBER) ForbiddenException() else ActivismInvalidTransitionException()

    private fun requireAForm(
        row: ResultRow,
        target: ActivismStatus,
    ) {
        if (target == ActivismStatus.IDEA || target == ActivismStatus.AWAITING_REVIEW) return
        val any = row[ActivismActionTable.formAppointment] || row[ActivismActionTable.formProgress] || row[ActivismActionTable.formSignal]
        if (!any) throw BadRequestException("An action needs at least one form")
    }

    private fun planningRate(viewer: CurrentMember) {
        if (!limiter.allowPlanning(viewer.memberId)) throw ServiceBusyException()
    }

    private fun participationRate(viewer: CurrentMember) {
        if (!limiter.allowParticipation(viewer.memberId)) throw ServiceBusyException()
    }

    private fun requireParticipant(memberId: Uuid?) {
        if (memberId != null && ActivismStore.participantRows(listOf(memberId)).isEmpty()) throw BadRequestException("Unknown member")
    }

    private fun taskAction(taskId: Uuid): Uuid = ActivismStore.getTask(taskId)?.get(ActivismTaskTable.actionId) ?: throw notFound()

    private fun textBlockAction(blockId: Uuid): Uuid =
        ActivismStore.getTextBlock(blockId)?.get(ActivismTextBlockTable.actionId) ?: throw notFound()

    private fun contributionAction(id: Uuid): Uuid =
        ActivismStore.getContribution(id)?.get(ActivismContributionTable.actionId) ?: throw notFound()

    /** The own, still REPORTED contribution inside the edit window, re-read under the action lock. */
    private fun ownEditableContribution(
        id: Uuid,
        viewer: CurrentMember,
        now: LocalDateTime,
    ): ResultRow {
        val row = ActivismStore.getContribution(id) ?: throw notFound()
        if (row[ActivismContributionTable.memberId] != viewer.memberId) throw notFound()
        val editable =
            row[ActivismContributionTable.status] == ActivismContributionStatus.REPORTED &&
                row[ActivismContributionTable.reportedAt] > now.minus(ActivismLimits.CONTRIBUTION_EDIT_WINDOW)
        if (!editable) throw ConflictException("Not editable any more")
        return row
    }

    // ── Helpers: mapping ────────────────────────────────────────────────────────────────────

    private fun reload(
        id: Uuid,
        viewer: CurrentMember,
    ): ActivismActionDetailDto {
        val (row, role) = loadVisible(id = id, viewer = viewer, lock = false)
        return detail(row = row, viewer = viewer, role = role)
    }

    private fun detail(
        row: ResultRow,
        viewer: CurrentMember,
        role: ActivismViewerRole,
    ): ActivismActionDetailDto {
        val id = row[ActivismActionTable.id]
        val kind = row[ActivismActionTable.kind]
        val progress = ActivismStore.progressOf(listOf(id)).getValue(id)
        val editor = ActivismAccessPolicy.mayEdit(role)
        val responsibles =
            if (editor) {
                val ids = ActivismStore.responsibleMemberIds(id)
                val names = ActivismStore.memberNames(ids)
                ids.map { ActivismResponsibleDto(memberId = it.toString(), name = names[it] ?: "") }.sortedBy { it.name }
            } else {
                null
            }
        val costCenterId = row[ActivismActionTable.costCenterId]
        val costCenterName =
            if (editor && costCenterId != null) {
                CostCenterTable
                    .selectAll()
                    .where { CostCenterTable.id eq costCenterId }
                    .singleOrNull()
                    ?.get(CostCenterTable.name)
            } else {
                null
            }
        val resultAt = row[ActivismActionTable.resultRecordedAt]
        return ActivismActionDetailDto(
            id = id.toString(),
            title = row[ActivismActionTable.title],
            description = row[ActivismActionTable.description],
            goalText = row[ActivismActionTable.goalText],
            location = row[ActivismActionTable.location],
            startsAt = row[ActivismActionTable.startsAt],
            endsAt = row[ActivismActionTable.endsAt],
            kind = kind,
            formAppointment = row[ActivismActionTable.formAppointment],
            formProgress = row[ActivismActionTable.formProgress],
            formSignal = row[ActivismActionTable.formSignal],
            goalQuantity = row[ActivismActionTable.goalQuantity],
            unit = row[ActivismActionTable.unit],
            confirmContributions = row[ActivismActionTable.confirmContributions],
            status = row[ActivismActionTable.status],
            viewerRole = role,
            progressReported = progress.reported,
            progressConfirmed = if (row[ActivismActionTable.confirmContributions]) progress.confirmed else null,
            signal = signalState(actionId = id, viewer = viewer),
            noticeText = ActivismStore.noticeTexts(ActivismStore.noticeCountry())[kind],
            ownNotice = row[ActivismActionTable.ownNotice],
            tasks = ActivismStore.listTasks(id).map { taskDto(row = it, viewer = viewer) },
            textBlocks = ActivismStore.listTextBlocks(id).map { it.toDto() },
            material = materialOf(actionId = id, viewer = viewer),
            eventShifts = row[ActivismActionTable.eventId]?.let { shiftsOf(eventId = it, viewer = viewer) },
            result =
                resultAt?.let {
                    ActivismResultDto(
                        participants = row[ActivismActionTable.resultParticipants],
                        quantity = row[ActivismActionTable.resultQuantity],
                        text = row[ActivismActionTable.resultText],
                        recordedAt = it,
                    )
                },
            responsibles = responsibles,
            withdrawnReason = if (editor) row[ActivismActionTable.withdrawnReason] else null,
            reviewRejectReason = if (editor) row[ActivismActionTable.reviewRejectReason] else null,
            costCenterId = if (editor) costCenterId?.toString() else null,
            costCenterName = costCenterName,
            eventId = if (editor) row[ActivismActionTable.eventId]?.toString() else null,
            revision = row[ActivismActionTable.revision],
            reviewRequired = ActivismStore.reviewRequired(),
        )
    }

    private fun signalState(
        actionId: Uuid,
        viewer: CurrentMember,
    ): ActivismSignalStateDto {
        val count = ActivismSignalStore.countOf(actionId)
        return ActivismSignalStateDto(
            countAtLeast5 = if (count >= ActivismLimits.SIGNAL_MIN_DISPLAY) count else null,
            mine = ActivismSignalStore.hasSignal(actionId = actionId, memberId = viewer.memberId),
        )
    }

    private fun materialOf(
        actionId: Uuid,
        viewer: CurrentMember,
    ): List<ActivismMaterialDto> {
        val links = ActivismStore.listMaterial(actionId)
        if (links.isEmpty()) return emptyList()
        val docs =
            DocumentTable
                .selectAll()
                .where { DocumentTable.id inList links.map { it[ActivismMaterialDocumentTable.documentId] } }
                .associateBy { it[DocumentTable.id] }
        val snapshot = FolderAccessLevels.snapshot()
        // Filtered by what THIS viewer may read: linking never makes a document visible that the viewer could not open anyway.
        return links.mapNotNull { link ->
            val doc = docs[link[ActivismMaterialDocumentTable.documentId]] ?: return@mapNotNull null
            if (!documentReadable(viewer = viewer, doc = doc, snapshot = snapshot)) return@mapNotNull null
            ActivismMaterialDto(
                documentId = doc[DocumentTable.id].toString(),
                position = link[ActivismMaterialDocumentTable.position],
                title = doc[DocumentTable.title],
            )
        }
    }

    private fun documentReadable(
        viewer: CurrentMember,
        doc: ResultRow,
        snapshot: FolderAccessLevels.Snapshot,
    ): Boolean =
        !doc[DocumentTable.isDeleted] &&
            viewer.canAccessDocumentAtLevel(doc[DocumentTable.accessLevel]) &&
            viewer.canAccessDocumentAtLevel(snapshot.effectiveLevel(doc[DocumentTable.folderId]))

    /** Time windows and numbers of the shifts, no names -- only when the viewer may see the event (published, or BOARD / ADMIN). */
    private fun shiftsOf(
        eventId: Uuid,
        viewer: CurrentMember,
    ): List<ActivismShiftSummaryDto>? {
        val event = EventStore.getEventOrNull(eventId) ?: return null
        if (!ActivismAccessPolicy.isManager(viewer) && event[EventTable.status] != EventStatus.PUBLISHED) return null
        return EventVolunteerStore.listShifts(eventId).map {
            ActivismShiftSummaryDto(
                startsAt = it[EventVolunteerShiftTable.startsAt],
                endsAt = it[EventVolunteerShiftTable.endsAt],
                needed = it[EventVolunteerShiftTable.neededCount],
                confirmed = EventVolunteerStore.countConfirmedSignups(it[EventVolunteerShiftTable.id]),
            )
        }
    }

    private fun taskDto(
        row: ResultRow,
        viewer: CurrentMember,
    ): ActivismTaskDto {
        val assignee = row[ActivismTaskTable.assigneeMemberId]
        return ActivismTaskDto(
            id = row[ActivismTaskTable.id].toString(),
            position = row[ActivismTaskTable.position],
            text = row[ActivismTaskTable.text],
            assigneeName = assignee?.let { ActivismStore.memberNames(listOf(it))[it] },
            assigneeIsMe = assignee == viewer.memberId,
            dueOn = row[ActivismTaskTable.dueOn],
            done = row[ActivismTaskTable.done],
        )
    }

    private fun ResultRow.toDto() =
        ActivismTextBlockDto(
            id = this[ActivismTextBlockTable.id].toString(),
            position = this[ActivismTextBlockTable.position],
            title = this[ActivismTextBlockTable.title],
            body = this[ActivismTextBlockTable.body],
        )

    private fun contributionDto(
        row: ResultRow,
        viewer: CurrentMember,
        includeIdentity: Boolean,
        now: LocalDateTime,
        names: Map<Uuid, String>? = null,
    ): ActivismContributionDto {
        val owner = row[ActivismContributionTable.memberId]
        val mine = owner == viewer.memberId
        val memberNames = names ?: if (includeIdentity && owner != null) ActivismStore.memberNames(listOf(owner)) else emptyMap()
        return ActivismContributionDto(
            id = row[ActivismContributionTable.id].toString(),
            memberId = if (includeIdentity) owner?.toString() else null,
            memberName = if (includeIdentity && owner != null) memberNames[owner] else null,
            quantity = row[ActivismContributionTable.quantity],
            area = row[ActivismContributionTable.area],
            note = row[ActivismContributionTable.note],
            status = row[ActivismContributionTable.status],
            rejectReason = row[ActivismContributionTable.rejectReason],
            reportedAt = row[ActivismContributionTable.reportedAt],
            editable =
                mine &&
                    row[ActivismContributionTable.status] == ActivismContributionStatus.REPORTED &&
                    row[ActivismContributionTable.reportedAt] > now.minus(ActivismLimits.CONTRIBUTION_EDIT_WINDOW),
        )
    }

    private fun ResultRow.toSummary(progress: network.lapis.cloud.server.activism.ActivismProgress): ActivismActionSummaryDto =
        ActivismActionSummaryDto(
            id = this[ActivismActionTable.id].toString(),
            title = this[ActivismActionTable.title],
            kind = this[ActivismActionTable.kind],
            formAppointment = this[ActivismActionTable.formAppointment],
            formProgress = this[ActivismActionTable.formProgress],
            formSignal = this[ActivismActionTable.formSignal],
            startsAt = this[ActivismActionTable.startsAt],
            endsAt = this[ActivismActionTable.endsAt],
            status = this[ActivismActionTable.status],
            unit = this[ActivismActionTable.unit],
            goalQuantity = this[ActivismActionTable.goalQuantity],
            progressReported = progress.reported,
            progressConfirmed = if (this[ActivismActionTable.confirmContributions]) progress.confirmed else null,
        )

    private fun matches(
        row: ResultRow,
        form: ActivismForm?,
    ): Boolean =
        when (form) {
            null -> true
            ActivismForm.APPOINTMENT -> row[ActivismActionTable.formAppointment]
            ActivismForm.PROGRESS -> row[ActivismActionTable.formProgress]
            ActivismForm.SIGNAL -> row[ActivismActionTable.formSignal]
        }

    private fun validate(input: ActivismActionInput): ActivismActionValues {
        val goal =
            input.goalQuantity?.also {
                requireRange(value = it, min = ActivismLimits.GOAL_QUANTITY_MIN, max = ActivismLimits.GOAL_QUANTITY_MAX)
            }
        val starts = input.startsAt
        val ends = input.endsAt
        if (starts != null && ends != null && ends < starts) throw BadRequestException("End before start")
        return ActivismActionValues(
            title = ActivismText.require(raw = input.title, maxChars = ActivismLimits.TITLE_MAX),
            description = ActivismText.cleanMultiline(raw = input.description, maxChars = ActivismLimits.DESCRIPTION_MAX),
            goalText = ActivismText.cleanMultiline(raw = input.goalText, maxChars = ActivismLimits.GOAL_TEXT_MAX),
            location = ActivismText.clean(raw = input.location, maxChars = ActivismLimits.LOCATION_MAX),
            startsAt = input.startsAt,
            endsAt = input.endsAt,
            kind = input.kind,
            formAppointment = input.formAppointment,
            formProgress = input.formProgress,
            formSignal = input.formSignal,
            goalQuantity = goal,
            unit = ActivismText.clean(raw = input.unit, maxChars = ActivismLimits.UNIT_MAX),
            confirmContributions = input.confirmContributions,
            ownNotice = ActivismText.cleanMultiline(raw = input.ownNotice, maxChars = ActivismLimits.OWN_NOTICE_MAX),
        )
    }

    private fun changedFields(
        row: ResultRow,
        v: ActivismActionValues,
    ): List<String> =
        buildList {
            if (row[ActivismActionTable.title] != v.title) add("title")
            if (row[ActivismActionTable.description] != v.description) add("description")
            if (row[ActivismActionTable.goalText] != v.goalText) add("goalText")
            if (row[ActivismActionTable.location] != v.location) add("location")
            if (row[ActivismActionTable.startsAt] != v.startsAt || row[ActivismActionTable.endsAt] != v.endsAt) add("times")
            if (row[ActivismActionTable.kind] != v.kind) add("kind")
            if (row[ActivismActionTable.formAppointment] != v.formAppointment ||
                row[ActivismActionTable.formProgress] != v.formProgress ||
                row[ActivismActionTable.formSignal] != v.formSignal
            ) {
                add("forms")
            }
            if (row[ActivismActionTable.goalQuantity] != v.goalQuantity || row[ActivismActionTable.unit] != v.unit) add("goal")
            if (row[ActivismActionTable.confirmContributions] != v.confirmContributions) add("confirmContributions")
            if (row[ActivismActionTable.ownNotice] != v.ownNotice) add("ownNotice")
        }

    private fun requireRange(
        value: Int,
        min: Int,
        max: Int,
    ): Int {
        if (value !in min..max) throw BadRequestException("Number out of range")
        return value
    }

    private fun parseId(raw: String): Uuid = runCatching { Uuid.parse(raw) }.getOrElse { throw notFound() }

    private fun audit(
        actor: CurrentMember,
        type: AuditEntityType,
        entityId: Uuid,
        action: AuditAction,
        snapshot: ActivismAuditSnapshot,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = type,
            entityId = entityId,
            action = action,
            after = Json.encodeToString(ActivismAuditSnapshot.serializer(), snapshot),
            occurredAt = now,
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun LocalDateTime.minus(duration: Duration): LocalDateTime =
        (ServerClock.systemToInstant(this) - duration).toLocalDateTime(ServerClock.zone)

    private companion object {
        const val NOT_FOUND = "Not found"
        const val REPORT_LIST_LIMIT = 200
        val COUNTRY_CODE = Regex("^[A-Z]{2}$")
        val DIRECT_TARGETS = setOf(ActivismStatus.PLANNED, ActivismStatus.RUNNING, ActivismStatus.COMPLETED)
    }
}
