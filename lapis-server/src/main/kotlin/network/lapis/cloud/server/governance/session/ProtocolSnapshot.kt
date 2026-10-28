package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.domain.PROTOCOL_HASH_PREFIX
import network.lapis.cloud.shared.domain.ProtocolAgendaItemDto
import network.lapis.cloud.shared.domain.ProtocolApprovalDto
import network.lapis.cloud.shared.domain.ProtocolApprovalResult
import network.lapis.cloud.shared.domain.ProtocolAttendanceDto
import network.lapis.cloud.shared.domain.ProtocolDraftPhase
import network.lapis.cloud.shared.domain.ProtocolHeaderDto
import network.lapis.cloud.shared.domain.ProtocolQuorumDto
import network.lapis.cloud.shared.domain.ProtocolResolutionDto
import network.lapis.cloud.shared.domain.ProtocolTaskDto
import java.security.MessageDigest
import java.text.Normalizer

/**
 * V1.9.240 -- the canonical content of a finalised Protokoll (schema 1). The field ORDER is the declaration order and is part of the hash
 * format: never reorder, never add a field in between -- a format change gets a new `schema` and a new hash prefix.
 *
 * Deliberately NOT in here: the done-state of a task, confirmations, `serverNow`, `finalisedAt`, any member id (a task names its assignee by
 * NAME only), and no ballot or voter. Resolution figures come from the draft DTO, i.e. only through `toResolutionDtos()` (k-anonymity).
 */
@Serializable
internal data class ProtocolSnapshotV1(
    val schema: Int = 1,
    val meetingId: String,
    val versionNo: Int,
    val correctionOfVersionNo: Int?,
    val correctionNote: String?,
    val minutesRequired: Boolean,
    val header: ProtocolHeaderDto,
    val attendance: ProtocolAttendanceDto,
    val quorum: ProtocolQuorumDto?,
    val items: List<SnapshotItem>,
    val unassignedResolutions: List<ProtocolResolutionDto>,
)

@Serializable
internal data class SnapshotItem(
    val agendaItemId: String,
    val position: Int,
    val title: String,
    val body: String,
    val resolutions: List<ProtocolResolutionDto>,
    val tasks: List<SnapshotTask>,
    val approval: SnapshotApproval?,
)

@Serializable
internal data class SnapshotTask(
    val taskKey: String,
    val assigneeName: String,
    val dueDate: LocalDate?,
    val description: String,
)

@Serializable
internal data class SnapshotApproval(
    val sourceMeetingId: String,
    val sourceMeetingDate: LocalDate,
    val versionNo: Int?,
    val result: ProtocolApprovalResult?,
)

/** A task of the draft with what the DTO does not carry: the stable key and the assignee's id (never part of the hash). */
internal class DraftTaskKey(
    val taskKey: String,
    val assigneeMemberId: String,
)

internal object ProtocolSnapshots {
    private val canonicalJson =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    /** The snapshot of [draft] as the NEXT version. [taskKeys]: draft task id -> stable key + assignee. */
    fun build(
        draft: MeetingProtocolDraftDto,
        versionNo: Int,
        correctionOfVersionNo: Int?,
        correctionNote: String?,
        minutesRequired: Boolean,
        taskKeys: Map<String, DraftTaskKey>,
    ): ProtocolSnapshotV1 =
        ProtocolSnapshotV1(
            meetingId = draft.meetingId,
            versionNo = versionNo,
            correctionOfVersionNo = correctionOfVersionNo,
            correctionNote = correctionNote,
            minutesRequired = minutesRequired,
            header = draft.header,
            attendance = draft.attendance,
            quorum = draft.quorum,
            items =
                draft.items.map { item ->
                    SnapshotItem(
                        agendaItemId = item.agendaItemId,
                        position = item.position,
                        title = item.title,
                        body = item.body,
                        resolutions = item.resolutions,
                        tasks =
                            item.tasks.map { task ->
                                SnapshotTask(
                                    taskKey = taskKeys.getValue(task.taskId).taskKey,
                                    assigneeName = task.assigneeName,
                                    dueDate = task.dueDate,
                                    description = task.description,
                                )
                            },
                        approval =
                            item.approval?.let {
                                SnapshotApproval(
                                    sourceMeetingId = it.sourceMeetingId,
                                    sourceMeetingDate = it.sourceMeetingDate,
                                    versionNo = it.versionNo,
                                    result = it.result,
                                )
                            },
                    )
                },
            unassignedResolutions = draft.unassignedResolutions,
        )

    /** The canonical JSON text: fixed field order, no whitespace, every string NFC-normalised. */
    fun canonicalJson(snapshot: ProtocolSnapshotV1): String =
        normalise(canonicalJson.encodeToJsonElement(ProtocolSnapshotV1.serializer(), snapshot)).toString()

    /** `sha256(PROTOCOL_HASH_PREFIX + json)` over UTF-8, lower-case hex. A fresh [MessageDigest] per call (thread safety). */
    fun sha256(json: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest((PROTOCOL_HASH_PREFIX + json).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun parse(json: String): ProtocolSnapshotV1 = canonicalJson.decodeFromString(ProtocolSnapshotV1.serializer(), json)

    /** The read model of a LOCKED draft: what was finalised, not what the live rows say now. */
    fun toDraftDto(
        snapshot: ProtocolSnapshotV1,
        phase: ProtocolDraftPhase,
        role: MeetingSessionRole,
        roles: Set<MeetingSessionRole>,
        finalisedAt: LocalDateTime,
        serverNow: LocalDateTime,
    ): MeetingProtocolDraftDto =
        MeetingProtocolDraftDto(
            meetingId = snapshot.meetingId,
            phase = phase,
            editable = false,
            role = role,
            header = snapshot.header,
            attendance = snapshot.attendance,
            quorum = snapshot.quorum,
            items =
                snapshot.items.map { item ->
                    ProtocolAgendaItemDto(
                        agendaItemId = item.agendaItemId,
                        position = item.position,
                        title = item.title,
                        body = item.body,
                        version = 0L,
                        updatedAt = null,
                        resolutions = item.resolutions,
                        approval =
                            item.approval?.let {
                                ProtocolApprovalDto(
                                    sourceMeetingId = it.sourceMeetingId,
                                    sourceMeetingDate = it.sourceMeetingDate,
                                    versionNo = it.versionNo,
                                    result = it.result,
                                    recordedAt = null,
                                    editable = false,
                                )
                            },
                        tasks =
                            item.tasks.map {
                                ProtocolTaskDto(
                                    taskId = it.taskKey,
                                    agendaItemId = item.agendaItemId,
                                    assigneeName = it.assigneeName,
                                    description = it.description,
                                    dueDate = it.dueDate,
                                    createdAt = finalisedAt,
                                )
                            },
                    )
                },
            unassignedResolutions = snapshot.unassignedResolutions,
            taskCount = snapshot.items.sumOf { it.tasks.size },
            serverNow = serverNow,
            roles = roles,
        )

    private fun normalise(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.entries.associate { (key, value) -> key to normalise(value) })
            is JsonArray -> JsonArray(element.map(::normalise))
            is JsonNull -> element
            is JsonPrimitive -> if (element.isString) JsonPrimitive(Normalizer.normalize(element.content, Normalizer.Form.NFC)) else element
        }
}
