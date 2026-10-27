package network.lapis.cloud.server.governance.meetingevent

import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MeetingEventField
import network.lapis.cloud.shared.domain.MeetingEventLinkAuditKind
import network.lapis.cloud.shared.domain.MeetingEventLinkSnapshot
import network.lapis.cloud.shared.domain.MeetingEventUnlinkReason
import kotlin.uuid.Uuid

/**
 * V1.9.227 -- the audit entries of the Sitzung <-> Veranstaltung link: one [AuditEntityType.MEETING_EVENT_LINK] entry per action, `entityId` =
 * the meeting id, snapshot = [MeetingEventLinkSnapshot] (ids and enums only, never a title or a text). Must be the LAST lock-taking step of the
 * caller's transaction ([AuditLogRecorder.record] locks the chain-state row).
 *
 * ANNOUNCED / LINKED -> CREATE, UNLINKED -> VOID, ALIGNED and OFFICER_* -> UPDATE (ALIGNED / OFFICER_UPDATED with a `before` snapshot of the unchanged link; OFFICER_* carry the officer's seat).
 */
internal object MeetingEventLinkAudit {
    fun record(
        actor: CurrentMember,
        kind: MeetingEventLinkAuditKind,
        meetingId: Uuid,
        eventId: Uuid,
        reason: MeetingEventUnlinkReason? = null,
        changedFields: List<MeetingEventField> = emptyList(),
        officerRole: CommitteeRole? = null,
    ) {
        val snapshot =
            MeetingEventLinkSnapshot(
                kind = kind,
                meetingId = meetingId.toString(),
                eventId = eventId.toString(),
                reason = reason,
                changedFields = changedFields,
                officerRole = officerRole,
            )
        val before =
            if (kind == MeetingEventLinkAuditKind.ALIGNED || kind == MeetingEventLinkAuditKind.OFFICER_UPDATED) {
                Json.encodeToString(snapshot.copy(changedFields = emptyList()))
            } else {
                null
            }
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.MEETING_EVENT_LINK,
            entityId = meetingId,
            action =
                when (kind) {
                    MeetingEventLinkAuditKind.ANNOUNCED, MeetingEventLinkAuditKind.LINKED -> AuditAction.CREATE
                    MeetingEventLinkAuditKind.UNLINKED -> AuditAction.VOID
                    MeetingEventLinkAuditKind.ALIGNED,
                    MeetingEventLinkAuditKind.OFFICER_UPDATED,
                    MeetingEventLinkAuditKind.OFFICER_PUBLISHED,
                    MeetingEventLinkAuditKind.OFFICER_CANCELLED,
                    -> AuditAction.UPDATE
                },
            before = before,
            after = Json.encodeToString(snapshot),
        )
    }
}
