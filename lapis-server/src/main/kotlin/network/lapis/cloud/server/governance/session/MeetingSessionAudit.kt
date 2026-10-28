package network.lapis.cloud.server.governance.session

import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import kotlin.uuid.Uuid

/**
 * V1.9.236 -- the audit entries of a Sitzung run in its conference room: one [AuditEntityType.MEETING_SESSION] entry per opening, per quorum
 * establishment, per agenda move and per adoption of the connected participants. `entityId` = the meeting id; the snapshot is a
 * [MeetingSessionAuditSnapshot] (ids, enums, counts -- NEVER a name; single attendance changes are not audited at all, a member id is personal data).
 * Must be the LAST lock-taking step of the caller's transaction ([AuditLogRecorder.record] locks the chain-state row).
 */
internal object MeetingSessionAudit {
    fun record(
        actor: CurrentMember,
        snapshot: MeetingSessionAuditSnapshot,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.MEETING_SESSION,
            entityId = Uuid.parse(snapshot.meetingId),
            action =
                when (snapshot.kind) {
                    MeetingSessionAuditKind.OPENED,
                    MeetingSessionAuditKind.QUORUM_ESTABLISHED,
                    MeetingSessionAuditKind.MOTION_VOTE_OPENED,
                    MeetingSessionAuditKind.PROTOCOL_DRAFT_CREATED,
                    MeetingSessionAuditKind.PROTOCOL_TASK_ADDED,
                    MeetingSessionAuditKind.PROTOCOL_FINALISED,
                    MeetingSessionAuditKind.PROTOCOL_CONFIRMED,
                    MeetingSessionAuditKind.PROTOCOL_PDF_ARCHIVED,
                    MeetingSessionAuditKind.PROTOCOL_CORRECTION_STARTED,
                    MeetingSessionAuditKind.PROTOCOL_APPROVAL_ITEM_INSERTED,
                    -> AuditAction.CREATE
                    MeetingSessionAuditKind.AGENDA_MOVED,
                    MeetingSessionAuditKind.ATTENDANCE_ADOPTED,
                    MeetingSessionAuditKind.MOTION_ATTACHED,
                    MeetingSessionAuditKind.PROTOCOL_BLOCK_UPDATED,
                    MeetingSessionAuditKind.PROTOCOL_VERSION_SUPERSEDED,
                    MeetingSessionAuditKind.PROTOCOL_APPROVAL_RECORDED,
                    MeetingSessionAuditKind.PROTOCOL_TASK_STATUS_CHANGED,
                    -> AuditAction.UPDATE
                    // A task has no "delete" in the audit vocabulary; removing it is a VOID (as for the other removable records).
                    MeetingSessionAuditKind.PROTOCOL_TASK_REMOVED -> AuditAction.VOID
                },
            after = Json.encodeToString(snapshot),
        )
    }
}
