package network.lapis.cloud.server.activism

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.containsLikePattern
import network.lapis.cloud.server.db.generated.ActivismActionTable
import network.lapis.cloud.server.db.generated.ActivismContributionTable
import network.lapis.cloud.server.db.generated.ActivismMaterialDocumentTable
import network.lapis.cloud.server.db.generated.ActivismNoticeTextTable
import network.lapis.cloud.server.db.generated.ActivismReportTable
import network.lapis.cloud.server.db.generated.ActivismResponsibleTable
import network.lapis.cloud.server.db.generated.ActivismSignalTable
import network.lapis.cloud.server.db.generated.ActivismTaskTable
import network.lapis.cloud.server.db.generated.ActivismTextBlockTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.ActivismContributionStatus
import network.lapis.cloud.shared.domain.ActivismKind
import network.lapis.cloud.shared.domain.ActivismReportStatus
import network.lapis.cloud.shared.domain.ActivismStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** Sums of one action: [reported] = REPORTED + CONFIRMED (a rejected contribution does not count), [confirmed] = CONFIRMED only. */
internal data class ActivismProgress(
    val reported: Int,
    val confirmed: Int,
)

/** The changed columns of an action row; every field is applied as is. */
internal data class ActivismActionValues(
    val title: String,
    val description: String?,
    val goalText: String?,
    val location: String?,
    val startsAt: LocalDateTime?,
    val endsAt: LocalDateTime?,
    val kind: ActivismKind,
    val formAppointment: Boolean,
    val formProgress: Boolean,
    val formSignal: Boolean,
    val goalQuantity: Int?,
    val unit: String?,
    val confirmContributions: Boolean,
    val ownNotice: String?,
)

/**
 * V1.9.246 -- THE ONE reader and writer of every `activism_*` table (except `activism_signal`, which is [ActivismSignalStore]) and of the two
 * `organization_settings` columns of the module. Exceptions: `ActivismPersonalData` (DSGVO, `coveredTables`) and `OrganizationSchemaCatalog`
 * (`ActivismTripwireTest` pins the list). Every list has a deterministic `ORDER BY ..., id` (PostgreSQL promises no order otherwise).
 *
 * **Lock order of every write**: `activism_action` row `FOR UPDATE` -> its child rows -> the settings row (only the settings setters) -> the
 * audit chain row LAST. No member row is ever locked. Must run inside a `transaction {}`.
 */
internal object ActivismStore {
    /** Row cap of the unpaginated lists (actions, contributions) -- the same order of magnitude as the report list. */
    const val LIST_LIMIT = 500

    // ── Action ──────────────────────────────────────────────────────────────────────────────

    fun getAction(
        id: Uuid,
        lock: Boolean = false,
    ): ResultRow? {
        val query = ActivismActionTable.selectAll().where { ActivismActionTable.id eq id }
        return (if (lock) query.forUpdate() else query).singleOrNull()
    }

    /** Newest first, at most [limit] rows ([LIST_LIMIT] by default): an unbounded list is a memory sink on a long-lived organisation. */
    fun listActions(
        statuses: Collection<ActivismStatus>,
        limit: Int = LIST_LIMIT,
    ): List<ResultRow> =
        ActivismActionTable
            .selectAll()
            .where { ActivismActionTable.status inList statuses }
            .orderBy(ActivismActionTable.createdAt to SortOrder.DESC, ActivismActionTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()

    fun insertAction(
        id: Uuid,
        values: ActivismActionValues,
        createdBy: Uuid,
        now: LocalDateTime,
    ) {
        ActivismActionTable.insert {
            it[ActivismActionTable.id] = id
            it[title] = values.title
            it[description] = values.description
            it[goalText] = values.goalText
            it[location] = values.location
            it[startsAt] = values.startsAt
            it[endsAt] = values.endsAt
            it[kind] = values.kind
            it[formAppointment] = values.formAppointment
            it[formProgress] = values.formProgress
            it[formSignal] = values.formSignal
            it[goalQuantity] = values.goalQuantity
            it[unit] = values.unit
            it[confirmContributions] = values.confirmContributions
            it[ownNotice] = values.ownNotice
            it[status] = ActivismStatus.IDEA
            it[ActivismActionTable.createdBy] = createdBy
            it[createdAt] = now
            it[updatedAt] = now
            it[revision] = 0
        }
    }

    fun updateActionValues(
        id: Uuid,
        values: ActivismActionValues,
        now: LocalDateTime,
    ) {
        ActivismActionTable.update({ ActivismActionTable.id eq id }) {
            it[title] = values.title
            it[description] = values.description
            it[goalText] = values.goalText
            it[location] = values.location
            it[startsAt] = values.startsAt
            it[endsAt] = values.endsAt
            it[kind] = values.kind
            it[formAppointment] = values.formAppointment
            it[formProgress] = values.formProgress
            it[formSignal] = values.formSignal
            it[goalQuantity] = values.goalQuantity
            it[unit] = values.unit
            it[confirmContributions] = values.confirmContributions
            it[ownNotice] = values.ownNotice
            it[updatedAt] = now
            it[revision] = ActivismActionTable.revision + 1
        }
    }

    fun setStatus(
        id: Uuid,
        status: ActivismStatus,
        now: LocalDateTime,
        approvedBy: Uuid? = null,
        reviewRejectReason: String? = null,
        withdrawnReason: String? = null,
    ) {
        ActivismActionTable.update({ ActivismActionTable.id eq id }) {
            it[ActivismActionTable.status] = status
            if (approvedBy != null) it[ActivismActionTable.approvedBy] = approvedBy
            it[ActivismActionTable.reviewRejectReason] = reviewRejectReason
            it[ActivismActionTable.withdrawnReason] = withdrawnReason
            it[updatedAt] = now
            it[revision] = ActivismActionTable.revision + 1
        }
    }

    fun setLinks(
        id: Uuid,
        costCenterId: Uuid?,
        eventId: Uuid?,
        now: LocalDateTime,
    ) {
        ActivismActionTable.update({ ActivismActionTable.id eq id }) {
            it[ActivismActionTable.costCenterId] = costCenterId
            it[ActivismActionTable.eventId] = eventId
            it[updatedAt] = now
            it[revision] = ActivismActionTable.revision + 1
        }
    }

    fun setResult(
        id: Uuid,
        participants: Int?,
        quantity: Int?,
        text: String?,
        now: LocalDateTime,
    ) {
        ActivismActionTable.update({ ActivismActionTable.id eq id }) {
            it[resultParticipants] = participants
            it[resultQuantity] = quantity
            it[resultText] = text
            it[resultRecordedAt] = now
            it[updatedAt] = now
            it[revision] = ActivismActionTable.revision + 1
        }
    }

    // ── Responsible ─────────────────────────────────────────────────────────────────────────

    fun responsibleMemberIds(actionId: Uuid): List<Uuid> =
        ActivismResponsibleTable
            .selectAll()
            .where { ActivismResponsibleTable.actionId eq actionId }
            .orderBy(ActivismResponsibleTable.memberId to SortOrder.ASC)
            .map { it[ActivismResponsibleTable.memberId] }

    fun isResponsible(
        actionId: Uuid,
        memberId: Uuid,
    ): Boolean =
        ActivismResponsibleTable
            .selectAll()
            .where { (ActivismResponsibleTable.actionId eq actionId) and (ActivismResponsibleTable.memberId eq memberId) }
            .any()

    /** The ids of the actions [memberId] is responsible for, among [actionIds]. */
    fun responsibleActionIds(
        memberId: Uuid,
        actionIds: Collection<Uuid>,
    ): Set<Uuid> {
        if (actionIds.isEmpty()) return emptySet()
        return ActivismResponsibleTable
            .selectAll()
            .where { (ActivismResponsibleTable.memberId eq memberId) and (ActivismResponsibleTable.actionId inList actionIds) }
            .map { it[ActivismResponsibleTable.actionId] }
            .toSet()
    }

    fun isResponsibleForAny(memberId: Uuid): Boolean =
        ActivismResponsibleTable
            .selectAll()
            .where { ActivismResponsibleTable.memberId eq memberId }
            .limit(1)
            .any()

    fun replaceResponsibles(
        actionId: Uuid,
        memberIds: Set<Uuid>,
    ) {
        val current = responsibleMemberIds(actionId).toSet()
        val removed = current - memberIds
        if (removed.isNotEmpty()) {
            ActivismResponsibleTable.deleteWhere {
                (ActivismResponsibleTable.actionId eq actionId) and
                    (ActivismResponsibleTable.memberId inList removed)
            }
        }
        for (memberId in (memberIds - current).sortedBy { it.toString() }) {
            ActivismResponsibleTable.insert {
                it[id] = Uuid.random()
                it[ActivismResponsibleTable.actionId] = actionId
                it[ActivismResponsibleTable.memberId] = memberId
            }
        }
    }

    // ── Tasks ───────────────────────────────────────────────────────────────────────────────

    fun listTasks(actionId: Uuid): List<ResultRow> =
        ActivismTaskTable
            .selectAll()
            .where { ActivismTaskTable.actionId eq actionId }
            .orderBy(ActivismTaskTable.position to SortOrder.ASC, ActivismTaskTable.id to SortOrder.ASC)
            .toList()

    fun getTask(id: Uuid): ResultRow? = ActivismTaskTable.selectAll().where { ActivismTaskTable.id eq id }.singleOrNull()

    fun insertTask(
        actionId: Uuid,
        text: String,
        assignee: Uuid?,
        dueOn: LocalDate?,
    ): Uuid {
        val position = (listTasks(actionId).maxOfOrNull { it[ActivismTaskTable.position] } ?: 0) + 1
        val id = Uuid.random()
        ActivismTaskTable.insert {
            it[ActivismTaskTable.id] = id
            it[ActivismTaskTable.actionId] = actionId
            it[ActivismTaskTable.position] = position
            it[ActivismTaskTable.text] = text
            it[assigneeMemberId] = assignee
            it[ActivismTaskTable.dueOn] = dueOn
        }
        return id
    }

    fun updateTask(
        id: Uuid,
        text: String,
        assignee: Uuid?,
        dueOn: LocalDate?,
    ) {
        ActivismTaskTable.update({ ActivismTaskTable.id eq id }) {
            it[ActivismTaskTable.text] = text
            it[assigneeMemberId] = assignee
            it[ActivismTaskTable.dueOn] = dueOn
        }
    }

    fun setTaskDone(
        id: Uuid,
        done: Boolean,
        by: Uuid,
        now: LocalDateTime,
    ) {
        ActivismTaskTable.update({ ActivismTaskTable.id eq id }) {
            it[ActivismTaskTable.done] = done
            it[doneAt] = if (done) now else null
            it[doneBy] = if (done) by else null
        }
    }

    fun deleteTask(id: Uuid) {
        ActivismTaskTable.deleteWhere { ActivismTaskTable.id eq id }
    }

    /** Re-numbers the checklist so that [taskId] ends up at [newPosition] (1-based, clamped). Two phases because of the unique (action, position). */
    fun moveTask(
        actionId: Uuid,
        taskId: Uuid,
        newPosition: Int,
    ) {
        val rows = listTasks(actionId)
        val ids = rows.map { it[ActivismTaskTable.id] }.toMutableList()
        if (!ids.remove(taskId)) return
        // The temporary range must lie above every position in use, otherwise it collides with a not-yet-moved task.
        val tempBase = maxOf(TEMP_POSITION_BASE, (rows.maxOfOrNull { it[ActivismTaskTable.position] } ?: 0) + 1)
        ids.add((newPosition - 1).coerceIn(0, ids.size), taskId)
        ids.forEachIndexed { index, id ->
            ActivismTaskTable.update({ ActivismTaskTable.id eq id }) { it[position] = tempBase + index }
        }
        ids.forEachIndexed { index, id ->
            ActivismTaskTable.update({ ActivismTaskTable.id eq id }) { it[position] = index + 1 }
        }
    }

    // ── Text blocks ─────────────────────────────────────────────────────────────────────────

    fun listTextBlocks(actionId: Uuid): List<ResultRow> =
        ActivismTextBlockTable
            .selectAll()
            .where { ActivismTextBlockTable.actionId eq actionId }
            .orderBy(ActivismTextBlockTable.position to SortOrder.ASC, ActivismTextBlockTable.id to SortOrder.ASC)
            .toList()

    fun getTextBlock(id: Uuid): ResultRow? = ActivismTextBlockTable.selectAll().where { ActivismTextBlockTable.id eq id }.singleOrNull()

    fun insertTextBlock(
        actionId: Uuid,
        title: String,
        body: String,
    ): Uuid {
        val position = (listTextBlocks(actionId).maxOfOrNull { it[ActivismTextBlockTable.position] } ?: 0) + 1
        val id = Uuid.random()
        ActivismTextBlockTable.insert {
            it[ActivismTextBlockTable.id] = id
            it[ActivismTextBlockTable.actionId] = actionId
            it[ActivismTextBlockTable.position] = position
            it[ActivismTextBlockTable.title] = title
            it[ActivismTextBlockTable.body] = body
        }
        return id
    }

    fun updateTextBlock(
        id: Uuid,
        title: String,
        body: String,
    ) {
        ActivismTextBlockTable.update({ ActivismTextBlockTable.id eq id }) {
            it[ActivismTextBlockTable.title] = title
            it[ActivismTextBlockTable.body] = body
        }
    }

    fun deleteTextBlock(id: Uuid) {
        ActivismTextBlockTable.deleteWhere { ActivismTextBlockTable.id eq id }
    }

    // ── Material (links to existing documents) ──────────────────────────────────────────────

    fun listMaterial(actionId: Uuid): List<ResultRow> =
        ActivismMaterialDocumentTable
            .selectAll()
            .where { ActivismMaterialDocumentTable.actionId eq actionId }
            .orderBy(ActivismMaterialDocumentTable.position to SortOrder.ASC, ActivismMaterialDocumentTable.id to SortOrder.ASC)
            .toList()

    fun linkMaterial(
        actionId: Uuid,
        documentId: Uuid,
    ) {
        val rows = listMaterial(actionId)
        if (rows.any { it[ActivismMaterialDocumentTable.documentId] == documentId }) return
        ActivismMaterialDocumentTable.insert {
            it[id] = Uuid.random()
            it[ActivismMaterialDocumentTable.actionId] = actionId
            it[ActivismMaterialDocumentTable.documentId] = documentId
            it[position] = (rows.maxOfOrNull { row -> row[ActivismMaterialDocumentTable.position] } ?: 0) + 1
        }
    }

    fun unlinkMaterial(
        actionId: Uuid,
        documentId: Uuid,
    ) {
        ActivismMaterialDocumentTable.deleteWhere {
            (ActivismMaterialDocumentTable.actionId eq actionId) and (ActivismMaterialDocumentTable.documentId eq documentId)
        }
    }

    // ── Contributions ───────────────────────────────────────────────────────────────────────

    fun getContribution(id: Uuid): ResultRow? =
        ActivismContributionTable.selectAll().where { ActivismContributionTable.id eq id }.singleOrNull()

    /** Newest first, at most [limit] rows ([LIST_LIMIT] by default). */
    fun listContributions(
        actionId: Uuid,
        limit: Int = LIST_LIMIT,
    ): List<ResultRow> =
        ActivismContributionTable
            .selectAll()
            .where { ActivismContributionTable.actionId eq actionId }
            .orderBy(ActivismContributionTable.reportedAt to SortOrder.DESC, ActivismContributionTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()

    /** The caller's OWN contributions on [actionId] (a plain member needs nothing else), newest first, at most [limit]. */
    fun listOwnContributions(
        actionId: Uuid,
        memberId: Uuid,
        limit: Int = LIST_LIMIT,
    ): List<ResultRow> =
        ActivismContributionTable
            .selectAll()
            .where { (ActivismContributionTable.actionId eq actionId) and (ActivismContributionTable.memberId eq memberId) }
            .orderBy(ActivismContributionTable.reportedAt to SortOrder.DESC, ActivismContributionTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()

    /** Sum of the quantities per area (NULL area = no area) of the not rejected contributions of [actionId], computed in SQL. */
    fun areaSums(actionId: Uuid): List<Pair<String?, Int>> {
        val sum = ActivismContributionTable.quantity.sum()
        return ActivismContributionTable
            .select(ActivismContributionTable.area, sum)
            .where {
                (ActivismContributionTable.actionId eq actionId) and
                    (ActivismContributionTable.status neq ActivismContributionStatus.REJECTED)
            }.groupBy(ActivismContributionTable.area)
            .map { it[ActivismContributionTable.area] to (it[sum] ?: 0) }
    }

    /** Contributions of [memberId] on [actionId] reported after [since] (ANY status: a rejected one still used the allowance). */
    fun countContributionsSince(
        actionId: Uuid,
        memberId: Uuid,
        since: LocalDateTime,
    ): Int =
        ActivismContributionTable
            .selectAll()
            .where {
                (ActivismContributionTable.actionId eq actionId) and
                    (ActivismContributionTable.memberId eq memberId) and
                    (ActivismContributionTable.reportedAt greater since)
            }.count()
            .toInt()

    fun insertContribution(
        actionId: Uuid,
        memberId: Uuid,
        quantity: Int,
        area: String?,
        note: String?,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        ActivismContributionTable.insert {
            it[ActivismContributionTable.id] = id
            it[ActivismContributionTable.actionId] = actionId
            it[ActivismContributionTable.memberId] = memberId
            it[ActivismContributionTable.quantity] = quantity
            it[ActivismContributionTable.area] = area
            it[ActivismContributionTable.note] = note
            it[status] = ActivismContributionStatus.REPORTED
            it[reportedAt] = now
            it[updatedAt] = now
        }
        return id
    }

    /**
     * The capped insert: locks the action row (a no-op when the caller already holds it), counts the contributions of [memberId] since [since] and
     * inserts only below [cap]. Returns the new id or null at the cap. Because the count runs under the action lock, parallel reports cannot slip past.
     */
    fun insertContributionCapped(
        actionId: Uuid,
        memberId: Uuid,
        quantity: Int,
        area: String?,
        note: String?,
        now: LocalDateTime,
        since: LocalDateTime,
        cap: Int,
    ): Uuid? {
        getAction(id = actionId, lock = true) ?: return null
        if (countContributionsSince(actionId = actionId, memberId = memberId, since = since) >= cap) return null
        return insertContribution(actionId = actionId, memberId = memberId, quantity = quantity, area = area, note = note, now = now)
    }

    fun updateContribution(
        id: Uuid,
        quantity: Int,
        area: String?,
        note: String?,
        now: LocalDateTime,
    ) {
        ActivismContributionTable.update({ ActivismContributionTable.id eq id }) {
            it[ActivismContributionTable.quantity] = quantity
            it[ActivismContributionTable.area] = area
            it[ActivismContributionTable.note] = note
            it[updatedAt] = now
        }
    }

    fun deleteContribution(id: Uuid) {
        ActivismContributionTable.deleteWhere { ActivismContributionTable.id eq id }
    }

    fun decideContribution(
        id: Uuid,
        status: ActivismContributionStatus,
        reason: String?,
        by: Uuid,
        now: LocalDateTime,
    ) {
        ActivismContributionTable.update({ ActivismContributionTable.id eq id }) {
            it[ActivismContributionTable.status] = status
            it[rejectReason] = reason
            it[decidedBy] = by
            it[decidedAt] = now
            it[updatedAt] = now
        }
    }

    /** Sums per action for [actionIds]; an action without contributions maps to zeros. */
    fun progressOf(actionIds: Collection<Uuid>): Map<Uuid, ActivismProgress> {
        if (actionIds.isEmpty()) return emptyMap()
        val sum = ActivismContributionTable.quantity.sum()
        val reported = HashMap<Uuid, Int>()
        val confirmed = HashMap<Uuid, Int>()
        ActivismContributionTable
            .select(ActivismContributionTable.actionId, ActivismContributionTable.status, sum)
            .where {
                (ActivismContributionTable.actionId inList actionIds) and
                    (ActivismContributionTable.status neq ActivismContributionStatus.REJECTED)
            }.groupBy(ActivismContributionTable.actionId, ActivismContributionTable.status)
            .forEach {
                val action = it[ActivismContributionTable.actionId]
                val value = it[sum] ?: 0
                reported.merge(action, value, Int::plus)
                if (it[ActivismContributionTable.status] == ActivismContributionStatus.CONFIRMED) confirmed.merge(action, value, Int::plus)
            }
        return actionIds.associateWith { ActivismProgress(reported = reported[it] ?: 0, confirmed = confirmed[it] ?: 0) }
    }

    // ── Reports ─────────────────────────────────────────────────────────────────────────────

    fun getReport(id: Uuid): ResultRow? = ActivismReportTable.selectAll().where { ActivismReportTable.id eq id }.singleOrNull()

    fun listOpenReports(limit: Int): List<ResultRow> =
        ActivismReportTable
            .selectAll()
            .where { ActivismReportTable.status eq ActivismReportStatus.OPEN }
            .orderBy(ActivismReportTable.createdAt to SortOrder.ASC, ActivismReportTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()

    fun countReportsSince(
        memberId: Uuid,
        since: LocalDateTime,
    ): Int =
        ActivismReportTable
            .selectAll()
            .where { (ActivismReportTable.reporterMemberId eq memberId) and (ActivismReportTable.createdAt greater since) }
            .count()
            .toInt()

    fun hasOpenReport(
        actionId: Uuid,
        memberId: Uuid,
    ): Boolean =
        ActivismReportTable
            .selectAll()
            .where {
                (ActivismReportTable.actionId eq actionId) and
                    (ActivismReportTable.reporterMemberId eq memberId) and
                    (ActivismReportTable.status eq ActivismReportStatus.OPEN)
            }.any()

    fun insertReport(
        actionId: Uuid,
        reporter: Uuid,
        reason: String,
        now: LocalDateTime,
    ) {
        ActivismReportTable.insert {
            it[id] = Uuid.random()
            it[ActivismReportTable.actionId] = actionId
            it[reporterMemberId] = reporter
            it[ActivismReportTable.reason] = reason
            it[status] = ActivismReportStatus.OPEN
            it[createdAt] = now
        }
    }

    /** The three outcomes of [insertReportLimited]. */
    enum class ReportOutcome { OPENED, ALREADY_OPEN, LIMIT_REACHED }

    /**
     * Note: the per-day cap counts across all actions of the reporter while only ONE action row is locked, so parallel reports on
     * different actions can exceed it slightly (a soft cap); the one-open-report-per-action rule is exact.
     * Locks the action row, then enforces "one OPEN report per member and action" and [maxPerDay] reports per member since [since], and inserts. */
    fun insertReportLimited(
        actionId: Uuid,
        reporter: Uuid,
        reason: String,
        now: LocalDateTime,
        since: LocalDateTime,
        maxPerDay: Int,
    ): ReportOutcome {
        getAction(id = actionId, lock = true)
        if (hasOpenReport(actionId = actionId, memberId = reporter)) return ReportOutcome.ALREADY_OPEN
        if (countReportsSince(memberId = reporter, since = since) >= maxPerDay) return ReportOutcome.LIMIT_REACHED
        insertReport(actionId = actionId, reporter = reporter, reason = reason, now = now)
        return ReportOutcome.OPENED
    }

    fun resolveReport(
        id: Uuid,
        by: Uuid,
        now: LocalDateTime,
    ) {
        ActivismReportTable.update({ ActivismReportTable.id eq id }) {
            it[status] = ActivismReportStatus.RESOLVED
            it[resolvedAt] = now
            it[resolvedBy] = by
        }
    }

    /** Resolves every OPEN report of [actionId] (a withdrawn action closes them); returns how many. */
    fun resolveOpenReportsOf(
        actionId: Uuid,
        by: Uuid,
        now: LocalDateTime,
    ): Int =
        ActivismReportTable.update({
            (ActivismReportTable.actionId eq actionId) and
                (ActivismReportTable.status eq ActivismReportStatus.OPEN)
        }) {
            it[status] = ActivismReportStatus.RESOLVED
            it[resolvedAt] = now
            it[resolvedBy] = by
        }

    // ── Notice texts and settings ───────────────────────────────────────────────────────────

    fun noticeCountry(): String = settingsRow()?.get(OrganizationSettingsTable.activismNoticeCountry) ?: DEFAULT_COUNTRY

    fun reviewRequired(): Boolean = settingsRow()?.get(OrganizationSettingsTable.activismReviewRequired) ?: false

    private fun settingsRow(): ResultRow? =
        OrganizationSettingsTable.selectAll().where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }.singleOrNull()

    fun lockSettings() {
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .toList()
    }

    fun setReviewRequired(enabled: Boolean) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[activismReviewRequired] =
                enabled
        }
    }

    fun setNoticeCountry(code: String) {
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[activismNoticeCountry] = code }
    }

    fun noticeTexts(country: String): Map<ActivismKind, String> =
        ActivismNoticeTextTable
            .selectAll()
            .where { ActivismNoticeTextTable.countryCode eq country }
            .orderBy(ActivismNoticeTextTable.kind to SortOrder.ASC)
            .associate { it[ActivismNoticeTextTable.kind] to it[ActivismNoticeTextTable.body] }

    fun upsertNoticeText(
        country: String,
        kind: ActivismKind,
        body: String,
        by: Uuid,
        now: LocalDateTime,
    ) {
        val updated =
            ActivismNoticeTextTable.update(
                { (ActivismNoticeTextTable.countryCode eq country) and (ActivismNoticeTextTable.kind eq kind) },
            ) {
                it[ActivismNoticeTextTable.body] = body
                it[updatedAt] = now
                it[updatedBy] = by
            }
        if (updated == 0) {
            ActivismNoticeTextTable.insert {
                it[id] = Uuid.random()
                it[ActivismNoticeTextTable.countryCode] = country
                it[ActivismNoticeTextTable.kind] = kind
                it[ActivismNoticeTextTable.body] = body
                it[updatedAt] = now
                it[updatedBy] = by
            }
        }
    }

    fun deleteNoticeText(
        country: String,
        kind: ActivismKind,
    ) {
        ActivismNoticeTextTable.deleteWhere { (ActivismNoticeTextTable.countryCode eq country) and (ActivismNoticeTextTable.kind eq kind) }
    }

    // ── Candidates ──────────────────────────────────────────────────────────────────────────

    /** Active participants whose display name contains [needle] (wildcards already removed), at most [limit]. */
    fun searchParticipants(
        needle: String,
        limit: Int,
        onlyDiscoverable: Boolean = false,
        excluded: Collection<Uuid> = emptyList(),
    ): List<ResultRow> =
        MemberTable
            .selectAll()
            .where {
                (MemberTable.status inList MemberStatusSets.ACTIVISM_PARTICIPANT) and
                    (MemberTable.anonymizedAt.isNull()) and
                    (MemberTable.displayName.lowerCase() like containsLikePattern(needle.lowercase())) and
                    (if (onlyDiscoverable) MemberTable.dmDiscoverable eq true else Op.TRUE) and
                    (if (excluded.isEmpty()) Op.TRUE else MemberTable.id notInList excluded)
            }.orderBy(MemberTable.displayName to SortOrder.ASC, MemberTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()

    fun participantRows(memberIds: Collection<Uuid>): List<ResultRow> {
        if (memberIds.isEmpty()) return emptyList()
        return MemberTable
            .selectAll()
            .where {
                (MemberTable.id inList memberIds) and (MemberTable.status inList MemberStatusSets.ACTIVISM_PARTICIPANT) and
                    MemberTable.anonymizedAt.isNull()
            }.orderBy(MemberTable.id to SortOrder.ASC)
            .toList()
    }

    fun memberNames(memberIds: Collection<Uuid>): Map<Uuid, String> {
        if (memberIds.isEmpty()) return emptyMap()
        return MemberTable
            .selectAll()
            .where { MemberTable.id inList memberIds }
            .associate { it[MemberTable.id] to it[MemberTable.displayName] }
    }

    const val DEFAULT_COUNTRY = "DE"
    private const val TEMP_POSITION_BASE = 1000
}

/**
 * V1.9.246 -- the ONLY code that touches `activism_signal`. A signal is a bare "I would take part": no time, no text, no photo. There is NO
 * function that returns the members behind the signals of an action -- only the count ([countOf]) and the own state ([hasSignal]); the one
 * other read is the DSGVO export ([signalsOfMember]). The count only includes members who are CURRENTLY participants (an exit lowers it at once).
 */
internal object ActivismSignalStore {
    fun hasSignal(
        actionId: Uuid,
        memberId: Uuid,
    ): Boolean =
        ActivismSignalTable
            .selectAll()
            .where { (ActivismSignalTable.actionId eq actionId) and (ActivismSignalTable.memberId eq memberId) }
            .any()

    fun countOf(actionId: Uuid): Int =
        ActivismSignalTable
            .join(MemberTable, JoinType.INNER, ActivismSignalTable.memberId, MemberTable.id)
            .selectAll()
            .where { (ActivismSignalTable.actionId eq actionId) and (MemberTable.status inList MemberStatusSets.ACTIVISM_PARTICIPANT) }
            .count()
            .toInt()

    /** Idempotent; the insert runs under a savepoint so a unique violation cannot poison a PostgreSQL transaction. */
    fun set(
        actionId: Uuid,
        memberId: Uuid,
    ) {
        if (hasSignal(actionId = actionId, memberId = memberId)) return
        try {
            withSavepoint(name = "activism_signal_insert") {
                ActivismSignalTable.insert {
                    it[id] = Uuid.random()
                    it[ActivismSignalTable.actionId] = actionId
                    it[ActivismSignalTable.memberId] = memberId
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
        }
    }

    fun clear(
        actionId: Uuid,
        memberId: Uuid,
    ) {
        ActivismSignalTable.deleteWhere { (ActivismSignalTable.actionId eq actionId) and (ActivismSignalTable.memberId eq memberId) }
    }

    /** DSGVO export of ONE member's own signals: the action ids only. */
    fun signalsOfMember(memberId: Uuid): List<Uuid> =
        ActivismSignalTable
            .selectAll()
            .where { ActivismSignalTable.memberId eq memberId }
            .orderBy(ActivismSignalTable.actionId to SortOrder.ASC)
            .map { it[ActivismSignalTable.actionId] }

    /** DSGVO erasure of ONE member's own signals. */
    fun eraseMember(memberId: Uuid): Int = ActivismSignalTable.deleteWhere { ActivismSignalTable.memberId eq memberId }
}
