package network.lapis.cloud.server.carpool

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- 1:1 `network.lapis.cloud.server.social.PostDraftRetention`s
 * Muster (DoS-Deckel, deterministische Reihenfolge). Ein Posting verschwindet aus dem Feed schon
 * ab `departure_date < heute` (`CarpoolService.listPostings`s Query-Bedingung) -- dieser Poller
 * löscht ZUSÄTZLICH erst [RETENTION_DAYS_AFTER_DEPARTURE] Tage später endgültig, damit ein
 * Mitglied eine knapp abgelaufene eigene Fahrt noch kurz duplizieren kann
 * (`CarpoolService.listMyPostings` zeigt sie bis dahin weiterhin, markiert als `isPast`).
 */
internal object CarpoolRetention {
    const val RETENTION_DAYS_AFTER_DEPARTURE = 7

    private const val MAX_DELETED_PER_RUN = 5_000

    /** @return Anzahl gelöschter Zeilen. */
    fun deleteDueRows(now: LocalDateTime): Int =
        transaction {
            val cutoff = now.date.minus(DatePeriod(days = RETENTION_DAYS_AFTER_DEPARTURE))
            val dueIds =
                CarpoolPostingTable
                    .select(CarpoolPostingTable.id)
                    .where { CarpoolPostingTable.departureDate less cutoff }
                    .limit(MAX_DELETED_PER_RUN)
                    .map { it[CarpoolPostingTable.id] }
            if (dueIds.isEmpty()) 0 else CarpoolPostingTable.deleteWhere { CarpoolPostingTable.id inList dueIds }
        }
}
