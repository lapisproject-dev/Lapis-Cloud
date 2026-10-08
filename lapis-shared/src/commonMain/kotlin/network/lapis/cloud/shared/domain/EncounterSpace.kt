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
// V1.9.67: EncounterTheme is frozen at CHURCH (old cached clients still decode it); read [EncounterProfile] instead.

/**
 * V1.9.67: the kind of gathering a room hosts. It selects the vocabulary (pulpit/steward/congregation vs. podium/moderation/participants),
 * the floor-plan scene, the default reactions and the Art. 9 consent text. Describes the ROOM, never a person. Stored in
 * `encounter_space.profile` (V76).
 */
@Serializable
enum class EncounterProfile { CHURCH_SERVICE, ASSEMBLY }

/**
 * The reactions a room can be configured with (V1.9.67). Canonical order = declaration order. [HAND] is always on and
 * [HAND_LOWERED] (the wire counterpart of [HAND]) is never selectable. There is deliberately no thumbs-up: in an assembly it reads as
 * a vote.
 */
@Serializable
enum class EncounterReactionOption {
    HAND,
    AMEN,
    APPLAUSE,
    HEART,
    ;

    companion object {
        val ALWAYS_ON: EncounterReactionOption = HAND

        fun defaultsFor(profile: EncounterProfile): List<EncounterReactionOption> =
            when (profile) {
                EncounterProfile.CHURCH_SERVICE -> listOf(HAND, AMEN)
                EncounterProfile.ASSEMBLY -> listOf(HAND, APPLAUSE)
            }

        /** Dedupe + canonical order + [HAND] forced. Pure; shared by the server validation and the client form. */
        fun normalize(input: Collection<EncounterReactionOption>): List<EncounterReactionOption> =
            (input + HAND).toSet().sortedBy { it.ordinal }
    }
}

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

/**
 * The reactions a congregation member may send (B2 data-channel topic, no server path). [HAND] is a STATE ("my hand is up"): the
 * sender renews it every 30 s while it stays up and the receivers let it lapse after 90 s; [HAND_LOWERED] ends it at once. [AMEN] is an
 * EVENT (a short symbol at the sender's seat), never counted; so are [APPLAUSE] and [HEART] (V1.9.67, appended
 * only: old clients decode an unknown name to `null` and drop it).
 */
@Serializable
enum class EncounterReaction { HAND, HAND_LOWERED, AMEN, APPLAUSE, HEART }

/** Maps a wire reaction to its configuration option ([EncounterReaction.HAND_LOWERED] belongs to [EncounterReactionOption.HAND]). */
fun EncounterReaction.option(): EncounterReactionOption =
    when (this) {
        EncounterReaction.HAND, EncounterReaction.HAND_LOWERED -> EncounterReactionOption.HAND
        EncounterReaction.AMEN -> EncounterReactionOption.AMEN
        EncounterReaction.APPLAUSE -> EncounterReactionOption.APPLAUSE
        EncounterReaction.HEART -> EncounterReactionOption.HEART
    }

/** LiveKit data-channel topic of [EncounterReaction] messages (B2, informational only: the server has no path that reads it). */
const val ENCOUNTER_REACTION_TOPIC = "lapis-encounter-reaction"

/**
 * V1.9.76 -- anonymous e-mail notice to the room's office holders when a person WITHOUT an office enters. [NONE] = off (default),
 * [FIRST_GUEST] = at most one notice per opening of the room, [EVERY_GUEST] = at most one notice per five minutes (entries summarised).
 * The notice never names the person (Art. 9 GDPR); see `docs/architecture/encounter-space.adoc`.
 */
enum class EncounterNotifyMode { NONE, FIRST_GUEST, EVERY_GUEST }

/** Largest accepted reaction payload in bytes; `{"r":"HAND_LOWERED"}` is 20 bytes. Anything longer is dropped unread by the receiver. */
const val ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES = 32

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
    /** Create: `null` = [EncounterProfile.CHURCH_SERVICE]. Update: `null` = unchanged (an old cached admin client sends no profile). */
    val profile: EncounterProfile? = null,
    /** Create: `null` = [EncounterReactionOption.defaultsFor] the profile. Update: `null` = unchanged. HAND is forced, order is canonical. */
    val reactions: List<EncounterReactionOption>? = null,
    /** Create: `null` = [EncounterNotifyMode.NONE]. Update: `null` = unchanged (an old cached admin client sends no mode). */
    val notifyMode: EncounterNotifyMode? = null,
    /** V1.9.80: Create: `null` = tables off. Update: `null` = unchanged (an old cached admin client sends none). Describes the ROOM. */
    val tables: EncounterTablesConfig? = null,
)

/**
 * V1.9.80 (stage 2b): table configuration of a room. Describes the ROOM, never a person. Tables exist only in the
 * [EncounterProfile.ASSEMBLY] profile: [enabled] with the church profile is rejected by the server (and by a CHECK constraint, V79).
 */
@Serializable
data class EncounterTablesConfig(
    val enabled: Boolean = false,
    val count: Int = 4,
    val seats: Int = 6,
)

/** V1.9.80: at most this many tables per room. */
const val ENCOUNTER_TABLE_MAX_COUNT = 12

/** V1.9.80: fewest seats per table. */
const val ENCOUNTER_TABLE_MIN_SEATS = 2

/** V1.9.80: most seats per table. */
const val ENCOUNTER_TABLE_MAX_SEATS = 8

/** V1.9.80: a quieted table stays quiet for this long unless a moderator lifts it earlier. */
const val ENCOUNTER_TABLE_QUIET_MINUTES = 5

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
    val profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
    val reactions: List<EncounterReactionOption> = listOf(EncounterReactionOption.HAND, EncounterReactionOption.AMEN),
    val notifyMode: EncounterNotifyMode = EncounterNotifyMode.NONE,
    /** V1.9.80: effective table configuration (`enabled` only ever true in the assembly profile). Last + default = wire compatible. */
    val tables: EncounterTablesConfig = EncounterTablesConfig(),
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
    /** V1.9.79: the seat this CONGREGATION person chose (0-based), `null` = not seated. Last + default = wire compatible both ways. */
    val seat: Int? = null,
    /** V1.9.80: the table (0-based) this CONGREGATION person sits at, `null` = in the plenum. Visible to everybody present. */
    val table: Int? = null,
    /** V1.9.80: the seat at that table (0-based). */
    val tableSeat: Int? = null,
)

/**
 * V1.9.80: the answer of `tableToken`: [token] `null` = the caller sits at no table (any more). A wrapper because the RPC generator cannot
 * express a nullable return type.
 */
@Serializable
data class EncounterTableTokenAnswer(
    val token: EncounterTableTokenDto? = null,
)

/** V1.9.80: one table as listed -- occupied seat numbers only, never a person. */
@Serializable
data class EncounterTableDto(
    val table: Int,
    val seats: Int,
    val occupiedSeats: List<Int>,
    val quieted: Boolean,
)

/**
 * V1.9.80: the join data of the caller's own table. [join] carries a short-lived (30 s) LiveKit token for the table's own room;
 * [canPublish] is `false` while the table is quieted. Never contains another person.
 */
@Serializable
data class EncounterTableTokenDto(
    val join: ConferenceJoinTokenDto,
    val canPublish: Boolean,
    val table: Int,
    val tableSeat: Int,
)

/** V1.9.79: seats per row -- two blocks of three with the aisle between position 3 and 4. */
const val ENCOUNTER_SEATS_PER_ROW = 6

/** V1.9.79: hard upper bound of seats per session (40 rows). */
const val ENCOUNTER_SEAT_MAX = 240

/** V1.9.79: data-channel topic of the content-free "seats changed, reload the list" nudge. The receiver decodes nothing. */
const val ENCOUNTER_SEAT_NUDGE_TOPIC = "lapis-encounter-seat"

/** V1.9.79: largest accepted seat nudge payload in bytes (`{"s":1}` is 7). Anything longer is dropped unread. */
const val ENCOUNTER_SEAT_NUDGE_MAX_PAYLOAD_BYTES = 8

/** V1.9.79: seats a session offers for [congregationCount] people: whole rows, at least 24, one spare row, capped at [ENCOUNTER_SEAT_MAX]. */
fun encounterSeatCapacity(congregationCount: Int): Int =
    minOf(ENCOUNTER_SEAT_MAX, maxOf(24, ((congregationCount.coerceAtLeast(0) + 5) / 6 + 1) * 6))

/** V1.9.79: grid size the client renders: the capacity, but never cutting off an occupied seat (capacity may shrink when people leave). */
fun encounterSeatGridSize(
    congregationCount: Int,
    occupied: Collection<Int>,
): Int = minOf(ENCOUNTER_SEAT_MAX, maxOf(encounterSeatCapacity(congregationCount), (occupied.maxOrNull() ?: -1) + 1))

/** V1.9.79: 1-based row of [seat]. */
fun encounterSeatRow(seat: Int): Int = seat / ENCOUNTER_SEATS_PER_ROW + 1

/** V1.9.79: 1-based position within the row of [seat]. */
fun encounterSeatPosition(seat: Int): Int = seat % ENCOUNTER_SEATS_PER_ROW + 1
