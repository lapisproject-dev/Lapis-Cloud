package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.economy.WeightDecayClock
import network.lapis.cloud.shared.domain.SocialPostState
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum, Boosts,
 * rekursive Gesamtgewichtung" -- fachliche Konstanten und die Gewichtsberechnung. Siehe
 * `32-social-network.kuml.kts` file header und die Meritokratie-Konzeptnotiz ("Im sozialen Netz --
 * Gewichtung von Posts") für das volle fachliche Modell.
 *
 * **Live-rekursive Berechnung zur Lesezeit, ohne SQL-Rekursion.** Dank `root_id` (seit V4) ist ein
 * Teilbaum EIN `WHERE root_id inList (...)`-Zugriff; die eigentliche Aggregation
 * ([totalWeightsUnrounded]) ist eine reine, DB-freie Kotlin-Funktion über den bereits geladenen
 * Knotensatz -- kein `WITH RECURSIVE`, kein Ebenen-Abstieg, weil die Zerfallsmathematik niemals in
 * SQL wandern darf (`POWER()` ist Fließkomma, siehe [WeightDecayClock] KDoc).
 */
object SocialPostWeight {
    /** Untere Schranke aus dem Konzept -- gilt einheitlich für Post-Einsatz, Kommentar-Einsatz und Boost. DB-seitig gespiegelt in `chk_social_post_min_weight`/`chk_social_post_boost_min_amount`. */
    val MIN_WEIGHT_LTR: BigDecimal = BigDecimal("0.01")

    /**
     * Ab wie vielen Tagen ein Beitrag garantiert unter [MIN_WEIGHT_LTR] gefallen ist und damit für
     * das Ranking irrelevant wird -- aus `0.9^d * maxWeight < 0.01` hergeleitet (für ein
     * realistisches `maxWeight`, siehe Herleitung im Implementierungsplan). Aktiv seit Review-Fund
     * S1 (2026-08-18, siehe `SocialNetworkService.rankingHorizon`/`.listTimeline`) --
     * `listTimeline` filtert `WHERE published_at >= now - RANKING_HORIZON_DAYS` und deckelt so die
     * Kandidatenmenge, bevor überhaupt sortiert wird. Seit Welle V1.1.2 gilt derselbe Filter auch
     * für die Nachfahren-/Boost-Zeilen, die in die Gesamtgewichts-Aggregation einfließen (siehe
     * `SocialNetworkService.loadSubtreeRows`). Ausgenommen ist nur die Selbstansicht des Autors
     * (`includeHidden`-Query auf den eigenen `authorMemberId`, siehe [SocialNetworkService
     * .listTimeline] KDoc) -- dort ist kein Horizont-Filter aktiv, weil das keine Rangliste,
     * sondern eine vollständige eigene Übersicht ist.
     */
    const val RANKING_HORIZON_DAYS: Long = 400

    /** Maximale Knotenzahl, die ein Teilbaum-Zugriff je materialisiert (`getThread`). DoS-Guard. */
    const val THREAD_MAX_NODES: Int = 5_000

    /** DB-seitig gespiegelt in `chk_social_post_depth`. Service-seitig seit Welle V1.1.2 tatsächlich geprüft (`createComment`). */
    const val MAX_DEPTH: Int = 64

    /**
     * NEU V1.1.2. Obergrenze für die Zahl der Nachfahren-Zeilen, die EIN `listTimeline`-Aufruf über
     * ALLE Kandidaten-Wurzeln hinweg materialisiert -- Pendant zu `MAX_TIMELINE_WORKING_SET_ROWS`
     * auf der Wurzel-Ebene. Der [RANKING_HORIZON_DAYS]-Filter greift auch hier (ein Nachfahre älter
     * als der Horizont trägt < [MIN_WEIGHT_LTR] bei), dieser Deckel ist der davon UNABHÄNGIGE
     * Speicher-Backstop. Beim (praktisch unerreichbaren) Überlauf wird nach `published_at DESC`
     * abgeschnitten -- die jüngsten Nachfahren tragen das meiste Gewicht, der Fehler ist damit nach
     * unten beschränkt und least-surprise, kein Korrektheitsanspruch.
     *
     * **DoS-Härtung, Security-Audit-Fund S-A2 (2026-08-18), NICHT nur Performance-Tuning.** Vorher
     * `20_000` -- Faktor ≈1.700 gegenüber dem, was eine reguläre Seite (`MAX_TIMELINE_LIMIT` = 100,
     * typisch 12 Posts) tatsächlich anzeigt. Gewählter Wert `5_000`: identisch zu [THREAD_MAX_NODES]
     * (derselbe strukturelle Deckel, den `getThread` für EINEN Baum ohnehin schon durchsetzt --
     * `listTimeline` darf über alle Kandidaten-Wurzeln einer Seite hinweg nicht großzügiger sein als
     * ein einzelner Thread-Abruf), und immer noch ~400x der typischen Seitengröße, also weit über
     * jedem plausiblen legitimen Lastfall.
     *
     * **Welle V1.1.3-Fortschreibung**: dieser Wert ist [network.lapis.cloud.server.rpc
     * .SocialReadPipeline.SocialReadCaps.AUTHENTICATED]s `descendantRows` -- der seit dieser Welle
     * existierende öffentliche, kontenlose Lesepfad (`network.lapis.cloud.server.routes
     * .SocialPublicRoutes`, `network.lapis.cloud.server.rpc.SocialVisibility
     * .publicReadableCondition`) benutzt NICHT diese Konstante, sondern die eigenen, noch engeren
     * Werte aus `SocialReadCaps.PUBLIC` (2 000 statt 5 000) -- dieselbe Aggregations-Pipeline ist
     * dort ohne jedes Konto und ohne LTR-Einsatz erreichbar, der Deckel dort ist also strenger,
     * nicht identisch. Dieser hier bleibt UNVERÄNDERT für den authentifizierten Pfad.
     */
    const val TIMELINE_MAX_DESCENDANT_ROWS: Int = 5_000

    /**
     * Analog zu [TIMELINE_MAX_DESCENDANT_ROWS] für Boost-Zeilen, abgeschnitten nach `boosted_at
     * DESC`. **DoS-Härtung, Security-Audit-Fund S-A2 (2026-08-18)** -- aus demselben Grund wie dort
     * von `20_000` auf `5_000` gesenkt: ein Boost ist strukturell an einen Post gebunden (jede Zeile
     * hier korrespondiert zu einer Zeile in [TIMELINE_MAX_DESCENDANT_ROWS]s Kandidatenmenge), also
     * ist derselbe Deckel-Wert die naheliegende, konsistente Wahl -- kein eigenständig hergeleiteter
     * zweiter Wert nötig. **Welle V1.1.3-Fortschreibung**: wie [TIMELINE_MAX_DESCENDANT_ROWS] ist
     * dies `SocialReadCaps.AUTHENTICATED.boostRows` -- der öffentliche Pfad benutzt
     * `SocialReadCaps.PUBLIC.boostRows` (2 000) statt dieser Konstante.
     */
    const val TIMELINE_MAX_BOOST_ROWS: Int = 5_000

    /** E6: Doppelklick-Fenster für `boostPost` -- identischer (member, post, amount) innerhalb dieser Spanne => `ConflictException`. */
    val BOOST_DUPLICATE_WINDOW = 5.seconds

    /** V1.1.1-Signatur -- UNVERÄNDERT (Aufrufstellen + Tests hängen daran). Eigengewicht ohne Boosts. */
    fun ownWeightUnrounded(
        initialWeightLtr: BigDecimal,
        publishedAt: LocalDateTime,
        now: LocalDateTime,
    ): BigDecimal = WeightDecayClock.decayedUnrounded(amountLtr = initialWeightLtr, since = publishedAt, now = now)

    /**
     * NEU V1.1.2. Eigengewicht INKLUSIVE Boosts: zerfallener Einsatz + Summe der je ab ihrem
     * EIGENEN [BoostContribution.boostedAt] zerfallenen Boosts (S4 -- ein Boost zerfällt ab seinem
     * eigenen Zeitpunkt, NICHT ab `publishedAt` des geboosteten Posts). UNRUNDIERT -- jeder einzelne
     * Summand wird unrundiert addiert, [WeightDecayClock.round2] kommt genau einmal ganz am Ende
     * (siehe dessen KDoc "40-Jahre-Stabilitätsanforderung"). Überladung, keine Ersetzung von
     * [ownWeightUnrounded]'s Drei-Parameter-Form oben.
     */
    fun ownWeightUnrounded(
        initialWeightLtr: BigDecimal,
        publishedAt: LocalDateTime,
        boosts: List<BoostContribution>,
        now: LocalDateTime,
    ): BigDecimal {
        val ownStake = WeightDecayClock.decayedUnrounded(amountLtr = initialWeightLtr, since = publishedAt, now = now)
        return boosts.fold(ownStake) { acc, boost ->
            acc + WeightDecayClock.decayedUnrounded(amountLtr = boost.amountLtr, since = boost.boostedAt, now = now)
        }
    }

    /** One boost's contribution to its post's eigengewicht -- see [ownWeightUnrounded] overload above. */
    data class BoostContribution(
        val amountLtr: BigDecimal,
        val boostedAt: LocalDateTime,
    )

    /**
     * A `social_post` row's aggregation-relevant projection -- exactly the columns
     * [totalWeightsUnrounded]/[descendantCounts]/[suppressedIds] need, never the full row (`content`
     * up to 5000 chars is never needed for aggregation, same S-3 lesson as the timeline's own
     * ranking projection).
     */
    data class WeightNode(
        val id: Uuid,
        val parentId: Uuid?,
        /** Aus der `social_post.depth`-Spalte -- trägt die Bottom-up-Reihenfolge, siehe [totalWeightsUnrounded]. */
        val depth: Int,
        val initialWeightLtr: BigDecimal,
        val publishedAt: LocalDateTime,
    )

    /** Direkte + transitive Nachfahrenzahl je Knoten -- siehe [descendantCounts]. */
    data class DescendantCounts(
        val direct: Int,
        val total: Int,
    )

    /**
     * Bündel aus [ownById] (Eigengewicht je Knoten, inkl. eigener Boosts) und [totalById]
     * (Gesamtgewicht, siehe [aggregateWeightsUnrounded] KDoc) -- beide UNRUNDIERT, beide aus
     * demselben internen Fold, ohne dass ein Aufrufer, der beide Karten braucht, sie zweimal
     * berechnen muss.
     */
    data class AggregatedWeights(
        val ownById: Map<Uuid, BigDecimal>,
        val totalById: Map<Uuid, BigDecimal>,
    )

    /**
     * Gesamtgewicht jedes Knotens = Eigengewicht (inkl. eigener Boosts, [ownWeightUnrounded]) +
     * Summe der GESAMTgewichte aller Kinder -- UND, seit Security-Audit-Fund S-A2 (2026-08-18,
     * Welle V1.1.2), gleichzeitig auch das je Knoten bereits berechnete Eigengewicht selbst, als
     * [AggregatedWeights.ownById]. REINE FUNKTION über einen bereits geladenen Knotensatz -- kein
     * DB-Zugriff, keine SQL-Rekursion, damit sie ohne Datenbank unit-testbar ist (dieselbe Haltung
     * wie [WeightDecayClock]).
     *
     * **Bewusst KEINE Rekursion und kein expliziter Stack**: die Knoten werden nach
     * [WeightNode.depth] ABSTEIGEND durchlaufen und ihr laufender Wert auf den Elternknoten
     * aufaddiert. Weil jedes Kind per Konstruktion `depth = parent.depth + 1` hat, ist jeder Knoten
     * fertig aggregiert, bevor er selbst an der Reihe ist. Das ist O(n log n) (dominiert von der
     * Sortierung), stack-sicher bei jeder Tiefe UND strukturell TERMINATIONS-sicher gegen einen
     * (durch fehlerhafte Daten theoretisch möglichen) `parent_id`-Zyklus, der einen naiven
     * rekursiven Abstieg in eine Endlosschleife schicken würde: die Schleife hier besucht jeden
     * Knoten GENAU EINMAL (ein `forEach` über eine endliche Liste, kein Wiedereinstieg), ein Zyklus
     * kann also nie eine Endlosschleife oder einen Stack Overflow auslösen -- er kann höchstens
     * (weil ein Zyklus per Definition gleiche `depth`-Werte bräuchte, was bei echten, per
     * `createComment` geschriebenen Zeilen strukturell ausgeschlossen ist, siehe `depth = parent
     * .depth + 1`) zu einer je nach Listenreihenfolge unvollständigen/einseitigen Gewichtsweitergabe
     * zwischen den zyklischen Knoten führen -- ein Datenintegritätsproblem, kein
     * Terminierungsproblem. In einem korrekten Datenbestand (kein Zyklus möglich) ist das
     * irrelevant.
     *
     * Ein Knoten, dessen [WeightNode.parentId] NICHT im übergebenen Satz liegt (Waise, oder
     * Elternteil außerhalb des geladenen Fensters), trägt nur zu sich selbst bei -- das ist der
     * Normalfall für die Wurzel und der definierte Ausgang für einen abgeschnittenen Teilbaum.
     *
     * UNRUNDIERT. Der Aufrufer ruft [WeightDecayClock.round2] genau einmal, unmittelbar vor der
     * Anzeige.
     */
    fun aggregateWeightsUnrounded(
        nodes: List<WeightNode>,
        boostsByPostId: Map<Uuid, List<BoostContribution>>,
        now: LocalDateTime,
    ): AggregatedWeights {
        val byId = nodes.associateBy { it.id }
        val ownById =
            nodes.associate { node ->
                node.id to
                    ownWeightUnrounded(
                        initialWeightLtr = node.initialWeightLtr,
                        publishedAt = node.publishedAt,
                        boosts = boostsByPostId[node.id].orEmpty(),
                        now = now,
                    )
            }
        val totalById = ownById.toMutableMap()
        // Descending depth: every child is fully aggregated before its parent is visited.
        nodes.sortedByDescending { it.depth }.forEach { node ->
            val parentId = node.parentId
            if (parentId != null && byId.containsKey(parentId)) {
                totalById[parentId] = totalById.getValue(parentId) + totalById.getValue(node.id)
            }
        }
        return AggregatedWeights(ownById = ownById, totalById = totalById)
    }

    /**
     * Convenience-Wrapper um [aggregateWeightsUnrounded], der nur [AggregatedWeights.totalById]
     * zurückgibt -- UNVERÄNDERTE Signatur/Semantik gegenüber dem V1.1.1/V1.1.2-Review-Rundenstand
     * (Aufrufstellen + [SocialPostWeightTest] hängen an genau dieser Signatur). Ein Aufrufer, der
     * BEIDE Karten braucht (siehe `SocialNetworkService.ownWeightByIdOf`s Ablösung durch
     * [aggregateWeightsUnrounded] selbst, Security-Audit-Fund S-A2), ruft [aggregateWeightsUnrounded]
     * direkt auf statt Eigengewicht anschließend ein zweites Mal separat zu berechnen.
     */
    fun totalWeightsUnrounded(
        nodes: List<WeightNode>,
        boostsByPostId: Map<Uuid, List<BoostContribution>>,
        now: LocalDateTime,
    ): Map<Uuid, BigDecimal> = aggregateWeightsUnrounded(nodes = nodes, boostsByPostId = boostsByPostId, now = now).totalById

    /** Direkte + transitive Nachfahrenzahl je Knoten, über denselben Depth-DESC-Fold wie [totalWeightsUnrounded]. */
    fun descendantCounts(nodes: List<WeightNode>): Map<Uuid, DescendantCounts> {
        val byId = nodes.associateBy { it.id }
        val childrenByParent = nodes.mapNotNull { it.parentId }.groupingBy { it }.eachCount()
        val totalById = nodes.associate { it.id to 0 }.toMutableMap()
        nodes.sortedByDescending { it.depth }.forEach { node ->
            val parentId = node.parentId
            if (parentId != null && byId.containsKey(parentId)) {
                // Every one of node's own descendants, plus node itself, counts toward parentId's total.
                totalById[parentId] = totalById.getValue(parentId) + totalById.getValue(node.id) + 1
            }
        }
        return nodes.associate { node ->
            node.id to DescendantCounts(direct = childrenByParent[node.id] ?: 0, total = totalById.getValue(node.id))
        }
    }

    /**
     * Ids, die aus JEDER Liste-/Baumdarstellung auszublenden sind, weil sie selbst oder ein Vorfahre
     * nicht `VISIBLE` ist. Top-down über `depth ASC`. Ihr GEWICHT bleibt in [totalWeightsUnrounded]
     * enthalten (E3) -- diese Funktion trennt Sichtbarkeit sauber von Ökonomie.
     */
    fun suppressedIds(
        nodes: List<WeightNode>,
        stateById: Map<Uuid, SocialPostState>,
    ): Set<Uuid> {
        val byId = nodes.associateBy { it.id }
        val suppressed = mutableSetOf<Uuid>()
        nodes.sortedBy { it.depth }.forEach { node ->
            val parentId = node.parentId
            val ownStateHidden = stateById[node.id] != SocialPostState.VISIBLE
            val parentSuppressed = parentId != null && byId.containsKey(parentId) && parentId in suppressed
            if (ownStateHidden || parentSuppressed) {
                suppressed += node.id
            }
        }
        return suppressed
    }
}
