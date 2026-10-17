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
 * V1.9.152 (stage 2c): the fixed room template (what the room LOOKS like). Describes the ROOM, never a person; identical for everybody present.
 * Stored in `encounter_space.room_layout` (V109). [NAVE] = church nave with pews, [FOYER] = foyer/cafe with round tables, [HALL] = hall with
 * rows of chairs (both profiles), [SMALL_GROUP] = small-group room with long tables. See [allowedFor] for the combination rule.
 */
@Serializable
enum class EncounterRoomLayout { NAVE, FOYER, HALL, SMALL_GROUP }

/** The combination rule (also a CHECK constraint, V109): NAVE only with the church profile, FOYER/SMALL_GROUP only with the assembly profile, HALL with both. */
fun EncounterRoomLayout.allowedFor(profile: EncounterProfile): Boolean =
    when (this) {
        EncounterRoomLayout.NAVE -> profile == EncounterProfile.CHURCH_SERVICE
        EncounterRoomLayout.FOYER, EncounterRoomLayout.SMALL_GROUP -> profile == EncounterProfile.ASSEMBLY
        EncounterRoomLayout.HALL -> true
    }

/** The template a room gets when none is chosen: the nave for a church service, the hall for an assembly. Pure; shared by server and client form. */
fun defaultRoomLayout(profile: EncounterProfile): EncounterRoomLayout =
    when (profile) {
        EncounterProfile.CHURCH_SERVICE -> EncounterRoomLayout.NAVE
        EncounterProfile.ASSEMBLY -> EncounterRoomLayout.HALL
    }

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

/** V1.9.95 -- sent ONLY by the server (LiveKit RoomService/SendData, no participant); clients accept it only without a participant. */
const val ENCOUNTER_BLESSING_TOPIC = "lapis-encounter-blessing"

/** Fixed payload {"b":1}: no identity, no time, no counter. */
const val ENCOUNTER_BLESSING_PAYLOAD = """{"b":1}"""

/** Upper bound the client accepts for a blessing packet (the fixed payload is 7 bytes). */
const val ENCOUNTER_BLESSING_MAX_PAYLOAD_BYTES = 16

/** At most one blessing per session room per this interval; a second one is swallowed silently. */
const val ENCOUNTER_BLESSING_MIN_INTERVAL_MS = 10_000L

/** V1.9.96 -- sent ONLY by the server (LiveKit RoomService/SendData, no participant); clients accept it only without a participant. */
const val ENCOUNTER_BELL_TOPIC = "lapis-encounter-bell"

/** Fixed payload {"v":1}: no identity, no time, no counter. */
const val ENCOUNTER_BELL_PAYLOAD = """{"v":1}"""

/** Upper bound the client accepts for a bell packet (the fixed payload is 7 bytes). */
const val ENCOUNTER_BELL_MAX_PAYLOAD_BYTES = 16

/** At most one bell per session room per this interval; a second one is swallowed silently. */
const val ENCOUNTER_BELL_MIN_INTERVAL_MS = 10_000L

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
    /**
     * V1.9.143: whether the PULPIT of this room may be recorded. Create: `null` = off. Update: `null` = unchanged (an old cached admin client
     * sends none). Switching it ON needs the instance switch and a CLOSED room; switching it OFF is always allowed.
     */
    val pulpitRecordingAllowed: Boolean? = null,
    /** V1.9.152: the room template. Create: `null` = [defaultRoomLayout] of the profile. Update: `null` = unchanged. Must fit the profile ([allowedFor]). */
    val roomLayout: EncounterRoomLayout? = null,
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
    /** V1.9.143: the room option "the pulpit may be recorded". Last + default = wire compatible. */
    val pulpitRecordingAllowed: Boolean = false,
    /** V1.9.143: only for office holders (and BOARD/ADMIN): a recording may be started here right now (switch, room option, infrastructure). */
    val pulpitRecordingStartable: Boolean = false,
    /** V1.9.152: the room template. Last + default = wire compatible (an old server sends none: the nave, as before). */
    val roomLayout: EncounterRoomLayout = EncounterRoomLayout.NAVE,
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
    /** V1.9.143: the notice to the room ("the pulpit is recorded, the congregation is not"); `null` when the room option is off. */
    val pulpitRecordingRoomNotice: String? = null,
    /** V1.9.148: `true` iff the caller already acknowledged an OLDER version of this room's entry notice (same kind): shows "the notice has changed". Last + default = wire compatible. */
    val consentChanged: Boolean = false,
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
    /** V1.9.143: `true` while this room's pulpit is being recorded at the moment of entry (the banner shows from the first moment). Last + default = wire compatible. */
    val pulpitRecordingActive: Boolean = false,
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

// ── Temporary floor grant (V1.9.138) ─────────────────────────────────────────

/** V1.9.138 -- sent ONLY by the server (LiveKit RoomService/SendData); clients accept it only without a participant. */
const val ENCOUNTER_FLOOR_TOPIC = "lapis-encounter-floor"

/** Largest floor packet in bytes (`{"s":"YOU","t":600}` is 19). Longer packets are dropped unread. */
const val ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES = 64

/** The floor is always granted for this long; there is no duration parameter. */
const val ENCOUNTER_FLOOR_DEFAULT_SECONDS = 120

/** Total duration (first grant plus every extension) never exceeds this. */
const val ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS = 600

/** The two extension steps an office holder may choose. */
const val ENCOUNTER_FLOOR_EXTEND_SHORT = 60
const val ENCOUNTER_FLOOR_EXTEND_LONG = 120

/** The only valid extension values. */
val ENCOUNTER_FLOOR_EXTEND_STEPS: Set<Int> = setOf(ENCOUNTER_FLOOR_EXTEND_SHORT, ENCOUNTER_FLOOR_EXTEND_LONG)

/** The client highlights the countdown in the last this many seconds. */
const val ENCOUNTER_FLOOR_FINAL_SECONDS = 10

/**
 * V1.9.138 -- the state of the floor as one caller may see it. [holderMemberId]/[holderDisplayName] only for PULPIT/STEWARD of the room and
 * the holder themselves; [totalSeconds]/[maxReached] only for the office and the holder; everybody else gets [active] and [remainingSeconds].
 */
@Serializable
data class EncounterFloorDto(
    val active: Boolean,
    val remainingSeconds: Int,
    val totalSeconds: Int = 0,
    val maxReached: Boolean = false,
    val holderMemberId: String? = null,
    val holderDisplayName: String? = null,
    val self: Boolean = false,
)

/** What a floor packet says. Carries no identity of anybody. */
sealed interface EncounterFloorSignal {
    /** Somebody has the floor; [remainingSeconds] left. */
    data class On(
        val remainingSeconds: Int,
    ) : EncounterFloorSignal

    /** The receiver has the floor. */
    data class You(
        val remainingSeconds: Int,
    ) : EncounterFloorSignal

    /** Nobody has the floor. */
    data object Off : EncounterFloorSignal
}

/** The wire payload of [signal]: `{"s":"ON","t":118}`, `{"s":"YOU","t":118}` or `{"s":"OFF"}`. */
fun encounterFloorPayload(signal: EncounterFloorSignal): String =
    when (signal) {
        is EncounterFloorSignal.On -> """{"s":"ON","t":${signal.remainingSeconds.coerceIn(0, ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS)}}"""
        is EncounterFloorSignal.You -> """{"s":"YOU","t":${signal.remainingSeconds.coerceIn(0, ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS)}}"""
        EncounterFloorSignal.Off -> """{"s":"OFF"}"""
    }

/**
 * Parses a floor packet. `null` if [fromParticipant] (a packet that carries a sending participant is a forgery), the length is outside
 * `1..ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES`, keys other than `s`/`t` occur, `s` is not ON/YOU/OFF, or `t` is outside `0..600` (or missing for
 * ON/YOU). Never throws, never reads an identity.
 */
fun encounterFloorPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): EncounterFloorSignal? {
    if (fromParticipant || payload.size !in 1..ENCOUNTER_FLOOR_MAX_PAYLOAD_BYTES) return null
    return try {
        val obj =
            kotlinx.serialization.json.Json
                .parseToJsonElement(payload.decodeToString()) as? kotlinx.serialization.json.JsonObject ?: return null
        if (!obj.keys.all { it == "s" || it == "t" }) return null
        val s = (obj["s"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val tPrimitive = obj["t"] as? kotlinx.serialization.json.JsonPrimitive
        val t = tPrimitive?.takeIf { !it.isString }?.content?.toIntOrNull()
        when (s) {
            "OFF" -> if (obj.containsKey("t")) null else EncounterFloorSignal.Off
            "ON" -> if (t != null && t in 0..ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS) EncounterFloorSignal.On(t) else null
            "YOU" -> if (t != null && t in 0..ENCOUNTER_FLOOR_MAX_TOTAL_SECONDS) EncounterFloorSignal.You(t) else null
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
