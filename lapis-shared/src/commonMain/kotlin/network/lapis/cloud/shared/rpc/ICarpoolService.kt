package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
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
}
