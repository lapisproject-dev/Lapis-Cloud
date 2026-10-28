package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.ProtocolTaskStatusTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTaskTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.shared.domain.ProtocolConfirmationMode
import network.lapis.cloud.shared.domain.ProtocolConfirmationRole
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** One task of a CONFIRMED version together with where it comes from. */
internal class StoredProtocolTask(
    val taskKey: Uuid,
    val meetingId: Uuid,
    val versionNo: Int,
    val description: String,
    val dueDate: LocalDate?,
    val assigneeMemberId: Uuid,
)

/**
 * V1.9.240 -- the ONLY place that writes `protocol_version`, `protocol_version_confirmation`, `protocol_version_task` and `protocol_task_status`
 * (the DSGVO contributor may delete `protocol_task_status` rows, nothing else). **Append-only**: a version row is inserted once; afterwards only
 * `status` and `pdf_document_version_id` ever change, each through a CONDITIONAL update below (`WHERE status = ...` / `WHERE ... IS NULL`).
 * There is no delete and no upsert on the version tables. `ProtocolVersionStoreTripwireTest` pins that no other file touches these tables.
 * All functions must run inside a transaction.
 */
internal object ProtocolVersionStore {
    fun insertVersion(
        meetingId: Uuid,
        versionNo: Int,
        contentJson: String,
        contentSha256: String,
        correctionNote: String?,
        finalisedBy: Uuid,
        finalisedAt: LocalDateTime,
        mode: ProtocolConfirmationMode,
        minutesRequired: Boolean,
    ): Uuid? {
        val id = Uuid.random()
        return try {
            withSavepoint(name = "protocol_version_insert") {
                ProtocolVersionTable.insert {
                    it[ProtocolVersionTable.id] = id
                    it[ProtocolVersionTable.meetingId] = meetingId
                    it[ProtocolVersionTable.versionNo] = versionNo
                    it[ProtocolVersionTable.contentJson] = contentJson
                    it[ProtocolVersionTable.contentSha256] = contentSha256
                    it[ProtocolVersionTable.correctionNote] = correctionNote
                    it[ProtocolVersionTable.finalisedBy] = finalisedBy
                    it[ProtocolVersionTable.finalisedAt] = finalisedAt
                    it[ProtocolVersionTable.confirmationMode] = mode
                    it[ProtocolVersionTable.minutesRequired] = minutesRequired
                    it[ProtocolVersionTable.status] = ProtocolVersionStatus.AWAITING_CONFIRMATION
                    it[ProtocolVersionTable.pdfDocumentVersionId] = null
                }
            }
            id
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) null else throw e
        }
    }

    /** `false` when the role was already confirmed (a concurrent call won). */
    fun insertConfirmation(
        versionId: Uuid,
        memberId: Uuid,
        role: ProtocolConfirmationRole,
        at: LocalDateTime,
    ): Boolean =
        try {
            withSavepoint(name = "protocol_confirmation_insert") {
                ProtocolVersionConfirmationTable.insert {
                    it[ProtocolVersionConfirmationTable.id] = Uuid.random()
                    it[ProtocolVersionConfirmationTable.versionId] = versionId
                    it[ProtocolVersionConfirmationTable.memberId] = memberId
                    it[ProtocolVersionConfirmationTable.role] = role
                    it[ProtocolVersionConfirmationTable.confirmedAt] = at
                }
            }
            true
        } catch (e: ExposedSQLException) {
            if (e.isUniqueViolation()) false else throw e
        }

    fun insertVersionTask(
        versionId: Uuid,
        taskKey: Uuid,
        assigneeMemberId: Uuid,
        dueDate: LocalDate?,
        description: String,
    ) {
        ProtocolVersionTaskTable.insert {
            it[ProtocolVersionTaskTable.id] = Uuid.random()
            it[ProtocolVersionTaskTable.versionId] = versionId
            it[ProtocolVersionTaskTable.taskKey] = taskKey
            it[ProtocolVersionTaskTable.assigneeMemberId] = assigneeMemberId
            it[ProtocolVersionTaskTable.dueDate] = dueDate
            it[ProtocolVersionTaskTable.description] = description
        }
    }

    fun markConfirmed(versionId: Uuid): Boolean =
        ProtocolVersionTable.update({
            (ProtocolVersionTable.id eq versionId) and (ProtocolVersionTable.status eq ProtocolVersionStatus.AWAITING_CONFIRMATION)
        }) { it[status] = ProtocolVersionStatus.CONFIRMED } > 0

    fun markSuperseded(versionId: Uuid): Boolean =
        ProtocolVersionTable.update({
            (ProtocolVersionTable.id eq versionId) and (ProtocolVersionTable.status eq ProtocolVersionStatus.AWAITING_CONFIRMATION)
        }) { it[status] = ProtocolVersionStatus.SUPERSEDED } > 0

    /** Attaches the archived PDF once: only to a CONFIRMED version that has none yet. */
    fun attachPdf(
        versionId: Uuid,
        documentVersionId: Uuid,
    ): Boolean =
        ProtocolVersionTable.update({
            (ProtocolVersionTable.id eq versionId) and
                (ProtocolVersionTable.status eq ProtocolVersionStatus.CONFIRMED) and
                ProtocolVersionTable.pdfDocumentVersionId.isNull()
        }) { it[pdfDocumentVersionId] = documentVersionId } > 0

    fun versionRow(
        versionId: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow? {
        val query = ProtocolVersionTable.selectAll().where { ProtocolVersionTable.id eq versionId }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
    }

    fun versionsOf(meetingId: Uuid): List<ResultRow> =
        ProtocolVersionTable
            .selectAll()
            .where { ProtocolVersionTable.meetingId eq meetingId }
            .orderBy(ProtocolVersionTable.versionNo to SortOrder.DESC)
            .toList()

    fun latestVersion(meetingId: Uuid): ResultRow? =
        ProtocolVersionTable
            .selectAll()
            .where { ProtocolVersionTable.meetingId eq meetingId }
            .orderBy(ProtocolVersionTable.versionNo to SortOrder.DESC)
            .limit(1)
            .singleOrNull()

    fun latestConfirmed(meetingId: Uuid): ResultRow? =
        ProtocolVersionTable
            .selectAll()
            .where { (ProtocolVersionTable.meetingId eq meetingId) and (ProtocolVersionTable.status eq ProtocolVersionStatus.CONFIRMED) }
            .orderBy(ProtocolVersionTable.versionNo to SortOrder.DESC)
            .limit(1)
            .singleOrNull()

    fun confirmations(versionId: Uuid): List<ResultRow> =
        ProtocolVersionConfirmationTable
            .selectAll()
            .where { ProtocolVersionConfirmationTable.versionId eq versionId }
            .orderBy(ProtocolVersionConfirmationTable.role to SortOrder.ASC, ProtocolVersionConfirmationTable.id to SortOrder.ASC)
            .toList()

    fun taskStatus(taskKey: Uuid): ResultRow? =
        ProtocolTaskStatusTable
            .selectAll()
            .where {
                ProtocolTaskStatusTable.taskKey eq taskKey
            }.singleOrNull()

    /** Sets or clears the done-state of a task (a row per task, kept outside the immutable versions). */
    fun setTaskDone(
        taskKey: Uuid,
        meetingId: Uuid,
        assigneeMemberId: Uuid,
        doneAt: LocalDateTime?,
        now: LocalDateTime,
    ) {
        val updated =
            ProtocolTaskStatusTable.update({ ProtocolTaskStatusTable.taskKey eq taskKey }) {
                it[ProtocolTaskStatusTable.assigneeMemberId] = assigneeMemberId
                it[ProtocolTaskStatusTable.doneAt] = doneAt
                it[ProtocolTaskStatusTable.updatedAt] = now
            }
        if (updated > 0) return
        try {
            withSavepoint(name = "protocol_task_status_insert") {
                ProtocolTaskStatusTable.insert {
                    it[ProtocolTaskStatusTable.taskKey] = taskKey
                    it[ProtocolTaskStatusTable.meetingId] = meetingId
                    it[ProtocolTaskStatusTable.assigneeMemberId] = assigneeMemberId
                    it[ProtocolTaskStatusTable.doneAt] = doneAt
                    it[ProtocolTaskStatusTable.updatedAt] = now
                }
            }
        } catch (e: ExposedSQLException) {
            // A concurrent first tick: the other row wins, ours is the later write on the same key.
            if (!e.isUniqueViolation()) throw e
            ProtocolTaskStatusTable.update({ ProtocolTaskStatusTable.taskKey eq taskKey }) {
                it[ProtocolTaskStatusTable.doneAt] = doneAt
                it[ProtocolTaskStatusTable.updatedAt] = now
            }
        }
    }

    /** Max version number of a Sitzung (0 = none). */
    fun maxVersionNo(meetingId: Uuid): Int {
        val maxColumn = ProtocolVersionTable.versionNo.max()
        return ProtocolVersionTable
            .select(maxColumn)
            .where { ProtocolVersionTable.meetingId eq meetingId }
            .singleOrNull()
            ?.get(maxColumn) ?: 0
    }

    /**
     * The tasks of [memberId] that stand in the NEWEST CONFIRMED version of their Protokoll (an older version's task that a correction dropped
     * does not count), with their done-state. At most [limit], ordered by due date (undated last) then task key. Indexed by assignee.
     */
    fun tasksOf(
        memberId: Uuid,
        limit: Int,
    ): List<Pair<StoredProtocolTask, ResultRow?>> {
        val raw =
            (ProtocolVersionTaskTable innerJoin ProtocolVersionTable)
                .selectAll()
                .where {
                    (ProtocolVersionTaskTable.assigneeMemberId eq memberId) and
                        (ProtocolVersionTable.status eq ProtocolVersionStatus.CONFIRMED)
                }.limit(RAW_TASK_CAP)
                .toList()
        if (raw.isEmpty()) return emptyList()
        val meetingIds = raw.map { it[ProtocolVersionTable.meetingId] }.distinct()
        val maxColumn = ProtocolVersionTable.versionNo.max()
        val newest =
            meetingIds
                .chunked(ID_CHUNK)
                .flatMap { chunk ->
                    ProtocolVersionTable
                        .select(ProtocolVersionTable.meetingId, maxColumn)
                        .where {
                            (ProtocolVersionTable.meetingId inList chunk) and
                                (ProtocolVersionTable.status eq ProtocolVersionStatus.CONFIRMED)
                        }.groupBy(ProtocolVersionTable.meetingId)
                        .map { it[ProtocolVersionTable.meetingId] to it[maxColumn]!! }
                }.toMap()
        val current =
            raw
                .filter { newest[it[ProtocolVersionTable.meetingId]] == it[ProtocolVersionTable.versionNo] }
                .map {
                    StoredProtocolTask(
                        taskKey = it[ProtocolVersionTaskTable.taskKey],
                        meetingId = it[ProtocolVersionTable.meetingId],
                        versionNo = it[ProtocolVersionTable.versionNo],
                        description = it[ProtocolVersionTaskTable.description],
                        dueDate = it[ProtocolVersionTaskTable.dueDate],
                        assigneeMemberId = it[ProtocolVersionTaskTable.assigneeMemberId],
                    )
                }.sortedWith(
                    compareBy<StoredProtocolTask>({ it.dueDate == null }, { it.dueDate?.toString().orEmpty() }, { it.taskKey.toString() }),
                ).take(limit)
        if (current.isEmpty()) return emptyList()
        val statuses =
            current
                .map { it.taskKey }
                .chunked(ID_CHUNK)
                .flatMap { chunk -> ProtocolTaskStatusTable.selectAll().where { ProtocolTaskStatusTable.taskKey inList chunk }.toList() }
                .associateBy { it[ProtocolTaskStatusTable.taskKey] }
        return current.map { it to statuses[it.taskKey] }
    }

    /** The committee name and Sitzung date of [meetingId] for the task list. */
    fun meetingLabel(meetingId: Uuid): Pair<String, LocalDate>? {
        val meeting = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.singleOrNull() ?: return null
        val committee =
            CommitteeTable.selectAll().where { CommitteeTable.id eq meeting[MeetingTable.committeeId] }.singleOrNull() ?: return null
        return committee[CommitteeTable.name] to meeting[MeetingTable.scheduledAt].date
    }

    /** `true` when [documentId] is the Protokoll document of a Sitzung -- its versions are written ONLY by the archiver, never uploaded. */
    fun isProtocolDocument(documentId: Uuid): Boolean =
        MeetingTable
            .select(MeetingTable.id)
            .where { MeetingTable.protocolDocumentId eq documentId }
            .limit(1)
            .count() > 0

    private const val RAW_TASK_CAP = 1_000
    private const val ID_CHUNK = 500
}
