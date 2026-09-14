package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftInput
import network.lapis.cloud.shared.domain.EventVolunteerShiftRosterDto
import network.lapis.cloud.shared.domain.EventVolunteerSignupDto

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- Schicht-Definitionen (BOARD/ADMIN)
 * + Selbstbedienungs-Zusagen (jedes authentifizierte Mitglied), deliberately its OWN RPC interface
 * rather than a set of methods on [IEventService]: welche Helferschichten zu einem Event existieren
 * ist orthogonal zur event/registration Fachlogik [IEventService] bereits besitzt -- dieselbe
 * Begründung, die [IEventRoomService]/[ICateringService] für Raumverwaltung/Catering bereits
 * etablieren.
 *
 * **KEINE neue "Helfer"-Rolle** -- siehe [network.lapis.cloud.shared.domain.EventVolunteerShiftInput]
 * KDoc für die volle Scope-Begründung. [listShifts]/[signUpSelf]/[cancelOwnSignup] sind für JEDES
 * authentifizierte Mitglied erreichbar (Selbstbedienung, mirrors [IEventService.registerSelf]/
 * [IEventService.cancelOwnRegistration]); [createShift]/[updateShift]/[cancelShift]/
 * [getShiftRoster] sind BOARD/ADMIN (Schicht-Management, back-office data, same tier as
 * [IEventRoomService]/[ICateringService]).
 */
@RpcService
interface IEventVolunteerService {
    /** Role: jedes authentifizierte Mitglied. `confirmedCount`/`full`/`ownSignupStatus` werden serverseitig berechnet. */
    suspend fun listShifts(eventId: String): List<EventVolunteerShiftDto>

    /** Role: BOARD/ADMIN. Rejects a blank [EventVolunteerShiftInput.description], a non-positive
     * [EventVolunteerShiftInput.neededCount], `endsAt <= startsAt`, or an unknown
     * [EventVolunteerShiftInput.eventId] (`NotFoundException`). */
    suspend fun createShift(input: EventVolunteerShiftInput): EventVolunteerShiftDto

    /** Role: BOARD/ADMIN. Same validation as [createShift]. */
    suspend fun updateShift(
        id: String,
        input: EventVolunteerShiftInput,
    ): EventVolunteerShiftDto

    /** Role: BOARD/ADMIN. Soft-Cancel, nie ein DELETE -- bestehende Zusagen bleiben als historische Datensätze erhalten. */
    suspend fun cancelShift(id: String): EventVolunteerShiftDto

    /** Role: BOARD/ADMIN. Liefert die Schicht plus alle Zusagen (CONFIRMED und CANCELLED). */
    suspend fun getShiftRoster(id: String): EventVolunteerShiftRosterDto

    /** Role: jedes authentifizierte Mitglied. Kapazitätsgeprüft (`ConflictException`, wenn die
     * Schicht voll oder storniert ist). Eine Re-Anmeldung nach vorheriger Abmeldung reaktiviert die
     * bestehende Zusage-Zeile, statt eine zweite anzulegen. */
    suspend fun signUpSelf(shiftId: String): EventVolunteerSignupDto

    /** Role: jedes authentifizierte Mitglied. Bricht ausschließlich die EIGENE aktive Zusage ab
     * (`NotFoundException`, wenn keine eigene aktive Zusage für diese Schicht existiert). */
    suspend fun cancelOwnSignup(shiftId: String): EventVolunteerSignupDto
}
