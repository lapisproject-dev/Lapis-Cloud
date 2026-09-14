package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * Welle V1.4.3.4 "Raumverwaltung für Veranstaltungen" -- room master data, orthogonal to the
 * `event`/`event_registration` fachlogik `IEventService` already owns (see `IEventRoomService`
 * KDoc for why this is a separate RPC interface). See `49-event-room.kuml.kts` file header for the
 * full entity rationale.
 *
 * Deliberately out of scope for this wave: a single event can reference at most one room
 * (`Event.roomId`, 1:n from `EventRoom`'s perspective) -- multi-room-per-event (m:n) is NOT
 * modelled here.
 */
@Serializable
enum class EventRoomStatus { ACTIVE, INACTIVE }

/** Role: BOARD/ADMIN. `equipmentTags` is free-text, comma-joined server-side -- no dedicated search/filter this wave. */
@Serializable
data class EventRoomInput(
    val name: String,
    val capacity: Int? = null,
    val equipmentTags: List<String> = emptyList(),
)

@Serializable
data class EventRoomDto(
    val id: String,
    val name: String,
    val capacity: Int?,
    val equipmentTags: List<String>,
    val status: EventRoomStatus,
)
