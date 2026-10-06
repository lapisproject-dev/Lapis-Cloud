package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.61 "Begegnungsraum" (B1, server side) -- the DTOs of [network.lapis.cloud.shared.rpc.IEncounterSpaceService]. An encounter
 * space ("Begegnungsraum") is a standing room (first theme: a church service) in which a few office holders speak and a congregation
 * listens. Architecture, access matrix and the Art. 9 GDPR decisions: `docs/architecture/encounter-space.adoc`.
 *
 * **Privacy by construction**: no DTO here carries a presence time, a home server or a history of who attended. Only [EncounterSpaceDto
 * .presentCount] (a number) and [EncounterPresentDto] (names of the people CURRENTLY present, visible only to people who are present
 * themselves) leave the server.
 */
@Serializable
enum class EncounterTheme { CHURCH, }

/** What happens in the room. Only [SERVICE] (a service with a pulpit and a listening congregation) exists in B1. */
@Serializable
enum class EncounterSpaceMode { SERVICE, }

/** Who may enter. [MEMBERS_AND_GUESTS] additionally admits GUEST/FRIEND callers (after their explicit Art. 9 consent). */
@Serializable
enum class EncounterGuestPolicy { MEMBERS_ONLY, MEMBERS_AND_GUESTS }

/**
 * An OFFICE in one space (persisted in `encounter_space_role`). [PULPIT] may speak (publish audio/video), [STEWARD] ("Ordner") may speak
 * and moderate. The congregation holds no persisted role.
 */
@Serializable
enum class EncounterSpaceRole { PULPIT, STEWARD }

/** The role of a person inside a running session: an office or [CONGREGATION]. */
@Serializable
enum class EncounterPresenceRole { PULPIT, STEWARD, CONGREGATION }

/** The two reactions a congregation member may send (B2 data-channel topic, no server path in B1). */
@Serializable
enum class EncounterReaction { HAND, AMEN }

/** LiveKit data-channel topic of [EncounterReaction] messages (B2, informational only: the server has no path that reads it). */
const val ENCOUNTER_REACTION_TOPIC = "lapis-encounter-reaction"

/**
 * Create/update input. [maxParticipants] `null` = the conference default; the service clamps it to the instance maximum.
 * [closedNotice] is free text shown while the room is closed ("Gottesdienst beginnt um 10:15 Uhr"), at most 200 characters.
 */
@Serializable
data class EncounterSpaceInput(
    val title: String,
    val description: String = "",
    val theme: EncounterTheme = EncounterTheme.CHURCH,
    val guestPolicy: EncounterGuestPolicy = EncounterGuestPolicy.MEMBERS_ONLY,
    val closedNotice: String? = null,
    val maxParticipants: Int? = null,
)

/**
 * One space as the CALLER sees it. [presentCount] is only a number -- no names. [pulpitDisplayNames] names the office holders of the
 * PULPIT role (not the congregation). [myRole] is the caller's persisted office, `null` for everybody else (also for BOARD/ADMIN
 * without an office). [canModerate] is true for an office holder with status ACTIVE and for BOARD/ADMIN.
 */
@Serializable
data class EncounterSpaceDto(
    val id: String,
    val title: String,
    val description: String,
    val theme: EncounterTheme,
    val mode: EncounterSpaceMode,
    val guestPolicy: EncounterGuestPolicy,
    val closedNotice: String?,
    val open: Boolean,
    val openedAt: LocalDateTime?,
    val presentCount: Int,
    val maxParticipants: Int,
    val pulpitDisplayNames: List<String>,
    val myRole: EncounterSpaceRole?,
    val canModerate: Boolean,
    val archived: Boolean,
)

/** One office assignment of [network.lapis.cloud.shared.rpc.IEncounterSpaceService.setSpaceRoles] (replace-all, at most 20). */
@Serializable
data class EncounterSpaceRoleAssignmentInput(
    val memberId: String,
    val role: EncounterSpaceRole,
)

@Serializable
data class EncounterSpaceRoleDto(
    val memberId: String,
    val displayName: String,
    val role: EncounterSpaceRole,
)

/** The Art. 9 consent text a NON-member must acknowledge before entering. Submit [version]/[sha256] unmodified in [EncounterConsentInput]. */
@Serializable
data class EncounterConsentDisclaimerDto(
    val version: String,
    val headline: String,
    val keyPoints: List<String>,
    val text: String,
    val sha256: String,
)

@Serializable
data class EncounterConsentInput(
    val consentVersion: String,
    val consentSha256: String,
)

/** Pre-entry probe: the space, whether the caller must still consent, and the text to show. [disclaimer] is `null` when no consent is required. */
@Serializable
data class EncounterEntryInfoDto(
    val space: EncounterSpaceDto,
    val consentRequired: Boolean,
    val disclaimer: EncounterConsentDisclaimerDto?,
)

/**
 * The result of entering: the LiveKit join data plus the caller's [presenceRole] and the grant the token carries. [canPublish] is `true`
 * only for office holders; [canPublishData] is `false` for a person a steward silenced.
 */
@Serializable
data class EncounterEntryDto(
    val join: ConferenceJoinTokenDto,
    val presenceRole: EncounterPresenceRole,
    val canPublish: Boolean,
    val canPublishData: Boolean,
)

/** One person currently present. No time, no home server -- see the file KDoc. */
@Serializable
data class EncounterPresentDto(
    val memberId: String,
    val displayName: String,
    val role: EncounterPresenceRole,
    val isGuest: Boolean,
)
