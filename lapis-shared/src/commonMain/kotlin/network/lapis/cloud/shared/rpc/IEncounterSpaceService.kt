package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleDto

/**
 * Welle V1.9.61 "Begegnungsraum" (B1, server side): standing rooms with a pulpit and a listening congregation (first theme: a church
 * service), built on the LiveKit conference stack but with a different authority model and strict data minimisation (Art. 9 GDPR:
 * attending a church service can reveal religious belief). Full design: `docs/architecture/encounter-space.adoc`.
 *
 * **Access matrix** (all re-derived from the database on every call, never cached; "office holder" = holds a PULPIT/STEWARD role in the
 * space AND currently has status ACTIVE; "BOARD/ADMIN" = global privileged role):
 * - [listSpaces]/[getSpace]/[getEntryInfo]: every conference-eligible caller (ACTIVE; GUEST/FRIEND only for spaces with
 *   `MEMBERS_AND_GUESTS`). An archived space is visible to BOARD/ADMIN only.
 * - [createSpace]/[updateSpace]/[archiveSpace]/[listSpaceRoles]/[setSpaceRoles]: BOARD/ADMIN only.
 * - [openSpace]/[closeSpace]: office holder or BOARD/ADMIN.
 * - [enterSpace]: conference-eligible callers while the space is open and the caller is not removed. Only office holders receive a token
 *   that may publish audio/video; BOARD/ADMIN without an office listen only (but may moderate).
 * - [leaveSpace]: every authenticated caller, for their own presence.
 * - [listPresent]: only a person who is currently present sees who is present -- BOARD/ADMIN who are not present see nothing.
 * - [removeFromSpace]/[silenceInSpace]: office holder or BOARD/ADMIN; a STEWARD may not act against a PULPIT/STEWARD/BOARD/ADMIN.
 *
 * Kilua transmits only the exception TYPE: [NotFoundException] unknown/invisible id, [ForbiddenException] not allowed,
 * [ConflictException] state conflict (closed, full, throttled, consent missing), [BadRequestException] invalid input.
 */
@RpcService
interface IEncounterSpaceService {
    /** Every space the caller may see, newest first (at most 200). Archived spaces only for BOARD/ADMIN. */
    suspend fun listSpaces(): List<EncounterSpaceDto>

    suspend fun getSpace(spaceId: String): EncounterSpaceDto

    /** Whether [enterSpace] needs a consent, and the text to show. */
    suspend fun getEntryInfo(spaceId: String): EncounterEntryInfoDto

    /**
     * `input.profile`/`input.reactions` `null` = church service with its default reactions. `input.notifyMode` `null` = NONE (V1.9.76:
     * BOARD/ADMIN only, like the whole configuration).
     */
    suspend fun createSpace(input: EncounterSpaceInput): EncounterSpaceDto

    /**
     * `input.profile`/`input.reactions` `null` = unchanged. A real change of either is a [ConflictException] while a session is open
     * (the room's vocabulary and reaction set cannot change under people who are present).
     */
    suspend fun updateSpace(
        spaceId: String,
        input: EncounterSpaceInput,
    ): EncounterSpaceDto

    /** Archives a CLOSED space ([ConflictException] while a session is open). Idempotent. */
    suspend fun archiveSpace(spaceId: String): EncounterSpaceDto

    suspend fun listSpaceRoles(spaceId: String): List<EncounterSpaceRoleDto>

    /** Replace-all, at most 20 assignments, no duplicate member, every member must be ACTIVE. */
    suspend fun setSpaceRoles(
        spaceId: String,
        assignments: List<EncounterSpaceRoleAssignmentInput>,
    ): List<EncounterSpaceRoleDto>

    /** Opens a session (a new LiveKit room). Idempotent: an already open session is returned unchanged. */
    suspend fun openSpace(spaceId: String): EncounterSpaceDto

    /** Closes the session, deletes the LiveKit room and every trace of who was present. Idempotent. */
    suspend fun closeSpace(spaceId: String): EncounterSpaceDto

    /**
     * Enters the open session. [consent] is required for a non-member who has not yet acknowledged the CURRENT text (see [getEntryInfo]);
     * for an ACTIVE member it is ignored and nothing is stored.
     */
    suspend fun enterSpace(
        spaceId: String,
        consent: EncounterConsentInput?,
    ): EncounterEntryDto

    /** Deletes the caller's presence row (no "left at" kept). Idempotent. */
    suspend fun leaveSpace(spaceId: String)

    /** The people currently present; only for someone who is present themselves ([ForbiddenException] otherwise). */
    suspend fun listPresent(spaceId: String): List<EncounterPresentDto>

    /** Disconnects a person and blocks re-entry for the rest of this session. Writes no audit entry (Art. 9). */
    suspend fun removeFromSpace(
        spaceId: String,
        memberId: String,
    )

    /** Takes the person's data channel away (reactions) until the session ends: disconnects them; they re-enter without `canPublishData`. */
    suspend fun silenceInSpace(
        spaceId: String,
        memberId: String,
    )
}
