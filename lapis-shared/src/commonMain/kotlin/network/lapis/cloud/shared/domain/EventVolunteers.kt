package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- Schicht-Definitionen (BOARD/ADMIN)
 * + Selbstbedienungs-Zusagen (jedes authentifizierte Mitglied), verwaltet über eine eigene
 * `IEventVolunteerService` -- orthogonal zur `event`/`event_registration`-Fachlogik `IEventService`
 * bereits besitzt, dieselbe Begründung, die `IEventRoomService`/`ICateringService` bereits
 * etablieren (siehe `IEventVolunteerService` KDoc). See `51-event-volunteer.kuml.kts` file header
 * for the full entity rationale.
 *
 * **Bestätigte Scope-Entscheidung: KEINE neue "Helfer"-Rolle.** [AccountRole] bleibt
 * `{MEMBER, BOARD, TREASURER, ADMIN}` -- eine Schicht-Zusage ist Fachdaten (wer hat sich
 * eingetragen), keine Berechtigungsänderung. BOARD/ADMIN verwalten Schicht-Definitionen, JEDES
 * authentifizierte Mitglied kann sich selbst für eine Schicht anmelden/abmelden (Selbstbedienung
 * wie bei `IEventService.registerSelf`/`.cancelOwnRegistration`).
 *
 * **Zwei Tabellen, zwei Lifecycles.** `EventVolunteerShift` (die Schicht-Definition: Beschreibung,
 * Zeitraum, benötigte Anzahl) und `EventVolunteerSignup` (eine einzelne Mitglieds-Zusage zu genau
 * einer Schicht) sind bewusst getrennt: eine Schicht kann storniert werden, ohne dass ihre
 * historischen Zusagen verloren gehen, und eine einzelne Zusage kann zurückgezogen werden, ohne die
 * Schicht selbst anzufassen. Beide Lifecycles sind Soft-Cancel (kein DELETE) -- siehe
 * `EventVolunteerShiftStatus`/`EventVolunteerSignupStatus` KDoc.
 */
@Serializable
enum class EventVolunteerShiftStatus { ACTIVE, CANCELLED }

@Serializable
enum class EventVolunteerSignupStatus { CONFIRMED, CANCELLED }

/** Role: BOARD/ADMIN. [description] ist Pflicht-Freitext, [neededCount] muss > 0 sein, [endsAt] muss nach [startsAt] liegen. */
@Serializable
data class EventVolunteerShiftInput(
    val eventId: String,
    val description: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val neededCount: Int,
)

/**
 * [confirmedCount]/[full] sind serverseitig berechnete Felder (keine eigenen Spalten) --
 * [ownSignupStatus] ist die eigene Zusage des jeweils aufrufenden Mitglieds (`null`, wenn dieses
 * Mitglied nie zugesagt hat), ausschließlich in [network.lapis.cloud.shared.rpc.IEventVolunteerService.listShifts]
 * befüllt.
 */
@Serializable
data class EventVolunteerShiftDto(
    val id: String,
    val eventId: String,
    val description: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val neededCount: Int,
    val status: EventVolunteerShiftStatus,
    val confirmedCount: Int,
    val full: Boolean,
    val ownSignupStatus: EventVolunteerSignupStatus? = null,
)

@Serializable
data class EventVolunteerSignupDto(
    val id: String,
    val shiftId: String,
    val memberId: String,
    val memberDisplayName: String,
    val status: EventVolunteerSignupStatus,
    val signedUpAt: LocalDateTime,
    val cancelledAt: LocalDateTime? = null,
)

/** Role: BOARD/ADMIN -- returned by [network.lapis.cloud.shared.rpc.IEventVolunteerService.getShiftRoster]. */
@Serializable
data class EventVolunteerShiftRosterDto(
    val shift: EventVolunteerShiftDto,
    val signups: List<EventVolunteerSignupDto>,
)
