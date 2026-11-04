package network.lapis.cloud.server.events

import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.rpc.EventCaptureSettingsStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.EventOnlineKind
import kotlin.uuid.Uuid

/**
 * V1.9.280 -- the ONE place that decides the release (`event.capture_allowed`) of a NEWLY CREATED event.
 *
 * The default is "released" -- but only where a release can mean anything: the online kind is a Lapis conference AND the instance switch is on with the
 * current operator notice. The default is decided in code, never in the database: `event.capture_allowed` keeps `DEFAULT FALSE` (V110), so a path that does not
 * go through here (importer, staging seeds, any future insert) never releases an event by accident. Existing events are never touched.
 *
 * All functions run inside the caller's `transaction {}`.
 */
internal object EventCaptureDefaults {
    /**
     * [explicit] is [network.lapis.cloud.shared.domain.EventInput.captureAllowed]: `false` always wins; `true` or `null` ("no choice") become `true`
     * only for a Lapis conference while the instance switch is on, otherwise `false` (an explicit `true` while the switch is off is silently `false`).
     */
    fun resolve(
        explicit: Boolean?,
        onlineKind: EventOnlineKind,
    ): Boolean {
        if (explicit == false) return false
        if (onlineKind != EventOnlineKind.LAPIS_CONFERENCE) return false
        return EventCaptureSettingsStore.load().startAllowed()
    }

    /** Audit of a creation that ended up released: counters and flags only -- no name, no title. [explicit] says whether the creator asked for it. */
    fun auditCreated(
        eventId: Uuid,
        actor: CurrentMember,
        explicit: Boolean?,
    ) {
        AuditLogRecorder.record(
            actorMemberId = actor.memberId,
            actorRole = actor.role,
            entityType = AuditEntityType.EVENT_CAPTURE,
            entityId = eventId,
            action = AuditAction.CREATE,
            before = null,
            after = "{\"eventId\":\"$eventId\",\"captureAllowed\":true,\"source\":\"${if (explicit == true) "explicit" else "default"}\"}",
        )
    }
}
