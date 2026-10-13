package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CarpoolInviteeCandidateDto
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolParticipantDto
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPassengerHintDto
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.DirectMessageDto

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- ORGANIZATION_MEMBER-exklusiv (jede Methode gattet über
 * `requireActiveMembership`, kein eigenes `NavVisibility`-Prädikat nötig, siehe
 * `network.lapis.cloud.server.rpc.CarpoolService` KDoc "1a"). Kontaktaufnahme läuft über eine ganz
 * normale Direktnachricht ([contactAuthor] löst den Empfänger serverseitig auf und ruft
 * `network.lapis.cloud.server.rpc.insertDirectMessage`) -- es gibt kein eigenes
 * Carpool-Nachrichtensystem.
 */
@RpcService
interface ICarpoolService {
    /** Nur zukünftige Einträge (departure_date >= heute), max. 200, sortiert Datum/Zeit/Erstellung. */
    suspend fun listPostings(type: CarpoolPostingType? = null): List<CarpoolPostingDto>

    /** Auch abgelaufene, noch nicht endgültig gelöschte eigene Einträge (isPast = true). */
    suspend fun listMyPostings(): List<CarpoolPostingDto>

    suspend fun createPosting(input: CarpoolPostingInput): CarpoolPostingDto

    suspend fun updatePosting(
        id: String,
        input: CarpoolPostingInput,
    ): CarpoolPostingDto

    suspend fun deletePosting(id: String)

    /** Löst den Autor serverseitig auf; wirft bei abgelaufenem/unbekanntem/eigenem Posting. */
    suspend fun contactAuthor(
        postingId: String,
        message: String,
    ): DirectMessageDto

    // ---- V1.9.125 "Mitfahrten": appended at the END -- Kilua RPC numbers its routes by declaration order, never reorder. ----

    /** Passenger allowlist (ACTIVE/GUEST/FRIEND): asks to ride along on an OFFER. Idempotent while OPEN/CONFIRMED. */
    suspend fun requestParticipation(postingId: String): CarpoolMyParticipationDto

    /**
     * Driver (ACTIVE): exact-name search among people who may ride along. Both parts at least 3 characters, at most 3 hits, no prefix match.
     * Always the same answer shape: an empty list for "nobody", "too short" and "rate limited" alike (no enumeration oracle).
     */
    suspend fun findInvitee(
        firstName: String,
        lastName: String,
    ): List<CarpoolInviteeCandidateDto>

    /** Driver (ACTIVE), author of the offer: invites the person behind a [findInvitee] token. */
    suspend fun inviteParticipant(
        postingId: String,
        personRef: String,
    ): CarpoolParticipantDto

    /** Passenger allowlist: the OTHER side of the initiator confirms. Answers the new status. */
    suspend fun confirmParticipation(participationId: String): CarpoolParticipationStatus

    /** Passenger allowlist: the OTHER side of the initiator declines. */
    suspend fun declineParticipation(participationId: String): CarpoolParticipationStatus

    /** Passenger allowlist: the person or the driver withdraws (from OPEN or CONFIRMED). */
    suspend fun withdrawParticipation(participationId: String): CarpoolParticipationStatus

    /** Passenger allowlist: "Meine Mitfahrten", waiting items first. Declined/withdrawn entries disappear 7 days after the decision. */
    suspend fun listMyParticipations(): List<CarpoolMyParticipationDto>

    /** Driver (ACTIVE), author of the offer only. */
    suspend fun listParticipants(postingId: String): List<CarpoolParticipantDto>

    /** Driver (ACTIVE), author of an OFFER only: meeting point and time, at most 200 characters; blank clears it. */
    suspend fun setMeetingNote(
        postingId: String,
        note: String?,
    ): CarpoolPostingDto

    /**
     * ACTIVE: numbers only -- the confirmed passengers on the caller's OWN offers in [from]..[to] (at most 62 days); `confirmedPassengers == 0`
     * when there are none. (Not nullable: the Kilua RPC code generator cannot express a nullable object return type.)
     */
    suspend fun getPassengerHint(
        from: LocalDate,
        to: LocalDate,
    ): CarpoolPassengerHintDto
}
