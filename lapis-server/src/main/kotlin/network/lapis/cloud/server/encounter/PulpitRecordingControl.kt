package network.lapis.cloud.server.encounter

import kotlin.uuid.Uuid

/** Why a pulpit recording was stopped without a person pressing "stop". Written verbatim to the audit entry (`reason`). */
enum class PulpitStopReason {
    INSTANCE_DISABLED,
    ROOM_OPTION_OFF,
    SESSION_CLOSED,
    SOURCE_NOT_PULPIT,
    MAX_DURATION,
    FLOOR_GRANTED,
    RESTART_ORPHAN,
    DISK_LOW,
    CONSENT_WITHDRAWN,
}

/**
 * V1.9.143 -- the narrow face of [PulpitRecordingCoordinator] that the services which change the preconditions of a recording need
 * (organization settings: instance switch off; encounter space: room option off, room closed, office changed, floor granted). Passed as a
 * constructor parameter WITHOUT a default and taken from the module singleton: a default would give every request an engine that knows of no
 * running recording and silently stops nothing.
 *
 * Every function runs AFTER the caller's transaction committed, never throws, and is idempotent.
 */
interface PulpitRecordingControl {
    /** Stops every running pulpit recording of the instance. */
    suspend fun stopAll(reason: PulpitStopReason)

    /** Stops the recording of the session [sessionRoomId] (the session's conference room), if there is one. */
    suspend fun stopSession(
        sessionRoomId: Uuid,
        reason: PulpitStopReason,
    )

    /** Stops the recordings of every session of the space [spaceId]. */
    suspend fun stopSpace(
        spaceId: Uuid,
        reason: PulpitStopReason,
    )

    /** Re-checks the running recording of [spaceId] against the current offices (after `setSpaceRoles`); stops a source that is no pulpit any more. */
    suspend fun reconcileSpace(spaceId: Uuid)

    /** Whether the session [sessionRoomId] is being recorded right now (told to everybody who enters, so the banner is there from the first moment). */
    fun isRecordingActive(sessionRoomId: Uuid): Boolean = false
}
