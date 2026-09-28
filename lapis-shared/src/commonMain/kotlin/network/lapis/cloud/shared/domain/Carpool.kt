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
)
