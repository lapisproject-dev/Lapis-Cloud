package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.economy.WeightDecayClock
import java.math.BigDecimal

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" -- fachliche Konstanten und die
 * Welle-1-Gewichtsberechnung (Eigengewicht ohne rekursive Kommentar-/Boost-Aggregation). Siehe
 * `32-social-network.kuml.kts` file header und die Meritokratie-Konzeptnotiz ("Im sozialen Netz --
 * Gewichtung von Posts") für das volle fachliche Modell.
 *
 * **Welle V1.1.2 erweitert dieses Objekt** um eine rekursive `totalWeights`-Funktion (Eigengewicht
 * + Summe aller Nachfahren-Gewichte, inklusive Boosts) -- diese Welle braucht nur das Eigengewicht,
 * weil weder Kommentare noch Boosts existieren (jeder Post hat `parentId = null`).
 */
object SocialPostWeight {
    /** Untere Schranke aus dem Konzept -- gilt einheitlich für Post-Einsatz, Kommentar-Einsatz (Welle V1.1.2) und Boost (Welle V1.1.2). DB-seitig gespiegelt in `chk_social_post_min_weight`. */
    val MIN_WEIGHT_LTR: BigDecimal = BigDecimal("0.01")

    /**
     * Ab wie vielen Tagen ein Beitrag garantiert unter [MIN_WEIGHT_LTR] gefallen ist und damit für
     * das Ranking irrelevant wird -- aus `0.9^d * maxWeight < 0.01` hergeleitet (für ein
     * realistisches `maxWeight`, siehe Herleitung im Implementierungsplan). Korrigiert (NEU-2,
     * Review Runde 2, 2026-08-18): seit Review-Fund S1 (2026-08-18, siehe
     * `SocialNetworkService.rankingHorizon`/`.listTimeline`) ist dieser Cutoff bereits AKTIV --
     * `listTimeline` filtert `WHERE published_at >= now - RANKING_HORIZON_DAYS` und deckelt so die
     * Kandidatenmenge, bevor überhaupt sortiert wird. Ausgenommen ist nur die Selbstansicht des
     * Autors (`includeHidden`-Query auf den eigenen `authorMemberId`, siehe [SocialNetworkService
     * .listTimeline] KDoc) -- dort ist kein Horizont-Filter aktiv, weil das keine Rangliste,
     * sondern eine vollständige eigene Übersicht ist.
     */
    const val RANKING_HORIZON_DAYS: Long = 400

    /** Maximale Knotenzahl, die ein Teilbaum-Zugriff je materialisiert (Welle V1.1.2, `getThread`). DoS-Guard, hier bereits als Konstante angelegt. */
    const val THREAD_MAX_NODES: Int = 5_000

    /** DB-seitig gespiegelt in `chk_social_post_depth`. Service-seitig erst ab Welle V1.1.2 tatsächlich geprüft (kein Post dieser Welle hat je `depth > 0`). */
    const val MAX_DEPTH: Int = 64

    /**
     * Eigengewicht = zerfallener [initialWeightLtr]. UNRUNDIERT (siehe [WeightDecayClock] KDoc
     * "40-Jahre-Stabilitätsanforderung") -- Aufrufer runden erst nach jeder weiteren Aggregation
     * (ab Welle V1.1.2) bzw. unmittelbar vor der Anzeige (diese Welle, da keine Aggregation
     * stattfindet).
     */
    fun ownWeightUnrounded(
        initialWeightLtr: BigDecimal,
        publishedAt: LocalDateTime,
        now: LocalDateTime,
    ): BigDecimal = WeightDecayClock.decayedUnrounded(amountLtr = initialWeightLtr, since = publishedAt, now = now)
}
