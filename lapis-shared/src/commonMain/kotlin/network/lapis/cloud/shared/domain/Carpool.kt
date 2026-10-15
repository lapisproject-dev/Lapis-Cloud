package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- ein Mitglied bietet eine Fahrt an ([OFFER]) oder sucht eine
 * Mitfahrgelegenheit ([REQUEST]). Siehe `network.lapis.cloud.server.rpc.CarpoolService` KDoc für
 * den vollen Lebenszyklus (Feed-Sichtbarkeit endet mit dem Abfahrtsdatum, endgültige Löschung 7
 * Tage später über `CarpoolRetention`).
 */
@Serializable
enum class CarpoolPostingType { OFFER, REQUEST }

@Serializable
data class CarpoolPostingInput(
    val type: CarpoolPostingType,
    val fromPlace: String,
    val toPlace: String,
    val departureDate: LocalDate,
    val departureTime: LocalTime? = null,
    val seatsOffered: Int? = null,
    val notes: String? = null,
)

/**
 * Security (data protection): the name of a driver is shown to a guest or friend only once the person is part of the ride (CONFIRMED, or
 * the driver invited them). Everywhere before that this neutral label is sent instead -- an anonymous self-registered friend must not be
 * able to list party members together with their travel times.
 */
object CarpoolPrivacy {
    const val HIDDEN_NAME = "Mitglied"
}

/**
 * Deliberately carries NO `authorMemberId` field (Design-Team-Auflage, Larry Tesler): the client
 * must never be able to address `contactAuthor` with an id it read off a DTO -- the server alone
 * resolves the recipient from `postingId`, see [network.lapis.cloud.shared.rpc.ICarpoolService
 * .contactAuthor].
 */
@Serializable
data class CarpoolPostingDto(
    val id: String,
    val type: CarpoolPostingType,
    val fromPlace: String,
    val toPlace: String,
    val departureDate: LocalDate,
    val departureTime: LocalTime?,
    val seatsOffered: Int?,
    val notes: String?,
    val authorDisplayName: String,
    val isOwn: Boolean,
    val isPast: Boolean,
    val createdAt: LocalDateTime,
    /** V1.9.125: OFFER = `max(0, seatsOffered - confirmed)`, REQUEST = null. Default keeps the wire format backward compatible. */
    val seatsFree: Int? = null,
    /** V1.9.125: ONLY the caller's own participation on this posting, never a foreign one. */
    val myParticipation: CarpoolMyParticipationRef? = null,
    /** V1.9.125: meeting point and time -- only for the driver and for a CONFIRMED passenger, otherwise null. */
    val meetingNote: String? = null,
    /** V1.9.125: only on the caller's OWN offer -- participants waiting for the driver's decision (a number, no names). */
    val openRequests: Int = 0,
)

/** V1.9.125 -- who started a participation: the driver invited the person, or the person asked to ride along. */
@Serializable
enum class CarpoolParticipationInitiator { DRIVER, PASSENGER }

/** V1.9.125 -- OPEN waits for the other side; CONFIRMED holds a seat; DECLINED and WITHDRAWN are final (a new request revives the row). */
@Serializable
enum class CarpoolParticipationStatus { OPEN, CONFIRMED, DECLINED, WITHDRAWN }

/** V1.9.125 -- the caller's own participation on a posting, embedded in [CarpoolPostingDto]. */
@Serializable
data class CarpoolMyParticipationRef(
    val id: String,
    val status: CarpoolParticipationStatus,
    val initiator: CarpoolParticipationInitiator,
)

/** V1.9.125 -- one entry of "Meine Mitfahrten" ([network.lapis.cloud.shared.rpc.ICarpoolService.listMyParticipations]). */
@Serializable
data class CarpoolMyParticipationDto(
    val id: String,
    val postingId: String,
    val fromPlace: String,
    val toPlace: String,
    val departureDate: LocalDate,
    val departureTime: LocalTime?,
    val driverDisplayName: String,
    val status: CarpoolParticipationStatus,
    val initiator: CarpoolParticipationInitiator,
    /** True while the ball is in the caller's court (OPEN and the other side started it). */
    val awaitingMe: Boolean,
    /** Only while the participation is CONFIRMED. */
    val meetingNote: String?,
    val isPast: Boolean,
    val decidedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

/** V1.9.125 -- one participant of the caller's own offer ([network.lapis.cloud.shared.rpc.ICarpoolService.listParticipants]). */
@Serializable
data class CarpoolParticipantDto(
    val id: String,
    val displayName: String,
    /** Security: a self-registered FRIEND chooses the display name freely -- the driver must see what kind of account it is. */
    val memberStatus: MemberStatus? = null,
    val status: CarpoolParticipationStatus,
    val initiator: CarpoolParticipationInitiator,
    /** True while the driver has to decide (OPEN and the person asked). */
    val awaitingMe: Boolean,
    val decidedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

/**
 * V1.9.125 -- a search hit of [network.lapis.cloud.shared.rpc.ICarpoolService.findInvitee]. [personRef] is an opaque, short-lived token bound
 * to the searching driver -- NOT a member id.
 */
@Serializable
data class CarpoolInviteeCandidateDto(
    val personRef: String,
    val displayName: String,
    /** Security: lets the driver tell apart same-named accounts (member / guest / friend). */
    val memberStatus: MemberStatus? = null,
)

/** V1.9.125 -- numbers only (no names): how many confirmed passengers the caller's own offers had in a date range (`0` = none). */
@Serializable
data class CarpoolPassengerHintDto(
    val confirmedPassengers: Int,
    val multipleTrips: Boolean,
)

/** V1.9.139 -- the two parties of a ride-message history; derived from the participation, never a person reference. */
@Serializable
enum class CarpoolMessageSide { DRIVER, PASSENGER }

/** V1.9.139 -- one message of a ride history. [mine] is derived on the server; [senderLabel] is the display name of the other party. */
@Serializable
data class CarpoolMessageDto(
    val id: String,
    val mine: Boolean,
    val senderLabel: String,
    val body: String,
    val sentAt: LocalDateTime,
)

/** V1.9.139 -- one page of a history ([items] newest first, at most [CarpoolMessageLimits.PAGE_SIZE]). */
@Serializable
data class CarpoolMessagePageDto(
    val items: List<CarpoolMessageDto>,
    val hasMore: Boolean,
    val ended: Boolean,
    val endedByMe: Boolean,
    val writable: Boolean,
    /** A number only, for the "maximum reached" display. */
    val totalCount: Int,
)

/** V1.9.139 -- the same limits for client and server (`String.length`). */
object CarpoolMessageLimits {
    const val MAX_BODY_LENGTH = 1000
    const val MAX_MESSAGES = 200
    const val PAGE_SIZE = 50
    const val PER_MINUTE = 10
}
