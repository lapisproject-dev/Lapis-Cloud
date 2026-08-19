package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SocialPostBoostTable
import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.economy.WeightDecayClock
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SocialThreadDto
import network.lapis.cloud.shared.domain.SocialTimelinePageDto
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Review-Fund S1 (2026-08-18): defensiver DB-seitiger Deckel für [SocialReadPipeline.timelinePage]s
 * Kandidaten-Menge -- UNABHÄNGIG von der Seitengröße (`limit`/`offset`), weil die eigentliche
 * Sortierung nach Gewicht in Kotlin passiert (Gewicht ist nirgends eine SQL-Spalte, siehe
 * [SocialPostWeight] KDoc), nicht per SQL `ORDER BY` -- ein `.limit(offset + limit)` VOR der
 * Sortierung würde beliebige statt die tatsächlich gewichtsstärksten Zeilen liefern und die
 * Rangfolge stillschweigend verfälschen. Umgezogen aus `SocialNetworkService.kt` in Welle V1.1.3
 * (Extraktion nach [SocialReadPipeline]) -- Wert unverändert, nur der Ort. Siehe
 * [SocialReadPipeline.SocialReadCaps.AUTHENTICATED] für den authentifizierten Wert und
 * [SocialReadPipeline.SocialReadCaps.PUBLIC] für den seit dieser Welle existierenden, deutlich
 * strengeren Wert des öffentlichen, kontenlosen Lesepfads.
 */
private const val MAX_TIMELINE_WORKING_SET_ROWS = 2_000

/**
 * Der geteilte Lesepfad des sozialen Netzwerks -- verschoben (nicht kopiert) aus
 * [SocialNetworkService] in Welle V1.1.3, weil der neue unauthentifizierte HTTP-Lesepfad
 * (`network.lapis.cloud.server.routes.SocialPublicRoutes`) exakt dieselbe Lade- und
 * Aggregationspipeline braucht. Eine zweite Implementierung wäre das größte Risiko dieser Welle:
 * zwei Pipelines, die auseinanderdriften, wobei die unbeobachtetere von beiden die ohne Login
 * erreichbare wäre.
 *
 * **Vertrag**: jede Funktion hier läuft in der bereits offenen Exposed-Transaktion des Aufrufers --
 * keine Funktion hier öffnet selbst eine. Ausschließlich typisierte Exposed-Builder, niemals
 * Roh-SQL.
 *
 * **Deckel sind Parameter, keine Konstanten** ([SocialReadCaps]) -- der öffentliche Pfad ist der
 * einzige ohne Login, ohne LTR-Einsatz und ohne mitglieds-gebundene Zurechenbarkeit und muss
 * deshalb der STRENGSTE Konsument dieser Pipeline sein, nicht ein gleichberechtigter.
 *
 * **Beweis, dass die Extraktion verhaltensneutral war**: `SocialNetworkServiceTest` blieb
 * inhaltlich unverändert grün (Welle V1.1.3 Testgruppe T19) -- er deckt jeden Review-/
 * Security-Fund der beiden Vorwellen ab; wäre die Extraktion kein reiner Move gewesen, hätte er
 * angepasst werden müssen.
 */
internal object SocialReadPipeline {
    /**
     * Größendeckel EINES Lesevorgangs -- siehe Klassen-KDoc, warum das Parameter und keine
     * Konstanten sind.
     */
    data class SocialReadCaps(
        val workingSetRows: Int,
        val descendantRows: Int,
        val boostRows: Int,
        val threadMaxNodes: Int,
    ) {
        companion object {
            /** Werte des authentifizierten RPC-Pfads -- unverändert gegenüber V1.1.2. */
            val AUTHENTICATED =
                SocialReadCaps(
                    workingSetRows = MAX_TIMELINE_WORKING_SET_ROWS,
                    descendantRows = SocialPostWeight.TIMELINE_MAX_DESCENDANT_ROWS,
                    boostRows = SocialPostWeight.TIMELINE_MAX_BOOST_ROWS,
                    threadMaxNodes = SocialPostWeight.THREAD_MAX_NODES,
                )

            /**
             * V1.1.3: der kontenlose, öffentliche Pfad -- deutlich strenger als [AUTHENTICATED],
             * weil ein Angreifer hier NICHTS für einen Aufruf bezahlt (kein LTR-Einsatz, keine
             * Mitgliedschaft, keine mitglieds-gebundene Zurechenbarkeit). Zusammen mit
             * `SocialPublicRoutes`' eigener fester Seitengröße (20, max. 25 Seiten) liegt der
             * Worst Case eines öffentlichen Timeline-Aufrufs bei ≈500+2000+2000+20 ≈ 4500 Zeilen
             * statt ≈12000 beim authentifizierten Pfad -- siehe `SocialPublicRoutes.kt` für die
             * volle Herleitung.
             *
             * **`threadMaxNodes` = 300, gesenkt von 1 000 (Security-Audit-Fund S-1, 2026-08-18)**:
             * dieser Wert allein hatte einen unbegrenzten Body für `GET /s/{rootId}` NICHT verhindert
             * -- ein Knoten kann bis zu 5 000 Zeichen tragen
             * (`SocialNetworkService.MAX_CONTENT_LENGTH`), und `SocialPublicHtml.renderThreadDescendant`
             * kürzt Nachfahren-Inhalt bewusst NIE (M4-Prinzip). Die tatsächlich wirksame Body-Grenze
             * ist seit diesem Fund `SocialPublicHtml.THREAD_DESCENDANTS_BYTE_BUDGET` (~1,5 MB,
             * innerhalb des Renderers durchgesetzt) -- dieser Wert hier bleibt eine ZWEITE,
             * UNABHÄNGIGE Verteidigungsschicht, die bereits VOR dem Byte-Budget die Datenbank-Query-
             * und Aggregations-Kosten (`loadSubtreeRows`/`SocialPostWeight.aggregateWeightsUnrounded`)
             * für einen einzelnen Thread deckelt, nicht nur die HTML-Ausgabe. 300 ist für jeden bisher
             * beobachteten legitimen Thread großzügig, senkt aber den Worst-Case-Rohdaten-Umfang
             * (Query-Ergebnis + Aggregation) auf knapp ein Drittel des vorherigen Werts.
             */
            val PUBLIC =
                SocialReadCaps(
                    workingSetRows = 500,
                    descendantRows = 2_000,
                    boostRows = 2_000,
                    threadMaxNodes = 300,
                )
        }
    }

    /**
     * Gewichts-sortierte, paginierte Seite über die durch [condition] beschriebene Wurzel-Menge.
     * [condition] MUSS alle Sichtbarkeits-, State-, Eltern- und Autorfilter bereits enthalten --
     * diese Funktion fügt keinen davon hinzu (der authentifizierte Pfad baut sie aus
     * [SocialVisibility.readableByCondition], der öffentliche Pfad aus
     * [SocialVisibility.publicReadableCondition]). [horizon] ist der Ranking-Horizont für den
     * NACHFAHREN-/Boost-Load (`null`, wenn keiner gelten soll, z. B. `selfHiddenView`) -- ein
     * bereits im übergebenen [condition] enthaltener Horizont-Filter auf der Wurzel-Ebene ist davon
     * unabhängig und bleibt Sache des Aufrufers. [viewerStatus] `null` == unauthentifizierter
     * Besucher, siehe [toDtos].
     */
    fun timelinePage(
        condition: Op<Boolean>,
        horizon: LocalDateTime?,
        limit: Int,
        offset: Int,
        now: LocalDateTime,
        viewerStatus: MemberStatus?,
        caps: SocialReadCaps,
        ltrBalanceProvider: LtrBalanceProvider,
    ): SocialTimelinePageDto {
        // Security-Audit-Fund S-3 (2026-08-18): ranking only ever needs id/publishedAt/rootId here
        // -- loading every OTHER column (content up to 5000 chars, visibility, state, ...) for up
        // to caps.workingSetRows rows just to discard all but <=limit of them afterwards was pure
        // waste. Project only the ranking-relevant columns here; the full row is loaded further
        // below, ONLY for the ids that actually survive paging.
        val rankingRows =
            SocialPostTable
                .select(
                    SocialPostTable.id,
                    SocialPostTable.publishedAt,
                    SocialPostTable.rootId,
                    SocialPostTable.initialWeightLtr,
                ).where { condition }
                .orderBy(SocialPostTable.publishedAt, SortOrder.DESC)
                .limit(caps.workingSetRows)
                .toList()

        val rootIds = rankingRows.map { it[SocialPostTable.rootId] }.distinct()
        val subtreeRows = loadSubtreeRows(rootIds = rootIds, horizon = horizon, maxRows = caps.descendantRows)
        val weightNodes = subtreeRows.map { it.toWeightNode() }
        val subtreeIds = subtreeRows.map { it[SocialPostTable.id] }
        val boosts = loadBoosts(postIds = subtreeIds, maxRows = caps.boostRows)
        val aggregated = SocialPostWeight.aggregateWeightsUnrounded(nodes = weightNodes, boostsByPostId = boosts, now = now)
        val totalWeightById = aggregated.totalById

        val ranked =
            rankingRows.sortedWith(
                compareByDescending<ResultRow> { row ->
                    totalWeightById[row[SocialPostTable.id]] ?: SocialPostWeight.ownWeightUnrounded(
                        initialWeightLtr = row[SocialPostTable.initialWeightLtr],
                        publishedAt = row[SocialPostTable.publishedAt],
                        now = now,
                    )
                }.thenByDescending { it[SocialPostTable.publishedAt] }
                    .thenBy { it[SocialPostTable.id].toString() },
            )
        // S-3: resolve the final page's ids from the lightweight ranking above FIRST, then load
        // full rows for ONLY those ids -- never for the whole (up to caps.workingSetRows-row)
        // candidate set.
        val pageIds = ranked.drop(offset).take(limit).map { it[SocialPostTable.id] }
        val fullRowById =
            if (pageIds.isEmpty()) {
                emptyMap()
            } else {
                // N-1: re-apply `condition`, not just `id inList pageIds` -- defense in depth,
                // deliberately NOT "optimized away".
                SocialPostTable
                    .selectAll()
                    .where { (SocialPostTable.id inList pageIds) and condition }
                    .associateBy { it[SocialPostTable.id] }
            }
        // `inList` gives no row-order guarantee of its own -- rebuild the page in ranked order.
        val page = pageIds.mapNotNull { fullRowById[it] }

        val stateById = subtreeRows.associate { it[SocialPostTable.id] to it[SocialPostTable.state] }
        val suppressed = SocialPostWeight.suppressedIds(nodes = weightNodes, stateById = stateById)
        val countsById = SocialPostWeight.descendantCounts(weightNodes.filter { it.id !in suppressed })
        val boostCountById = boosts.mapValues { it.value.size }
        val ownWeightById = aggregated.ownById

        return SocialTimelinePageDto(
            posts =
                toDtos(
                    rows = page,
                    now = now,
                    viewerStatus = viewerStatus,
                    totalWeightById = totalWeightById,
                    countsById = countsById,
                    boostCountById = boostCountById,
                    ownWeightById = ownWeightById,
                    ltrBalanceProvider = ltrBalanceProvider,
                ),
            totalRankedCount = ranked.size,
            rankingHorizonFrom = ranked.lastOrNull()?.get(SocialPostTable.publishedAt) ?: now,
        )
    }

    /**
     * Vollständiger, sichtbarkeits-gefilterter Teilbaum ab [rootUuid]. Gibt `null` zurück, wenn die
     * Wurzel unter [condition] nicht erreichbar, keine echte Wurzel (`rootId != id`, K4) oder nicht
     * `VISIBLE` ist -- der Aufrufer übersetzt das in seine eigene Fehlerform (`NotFoundException`
     * im RPC-Pfad, 404-HTML-Seite im öffentlichen Pfad). **Niemals ein leeres Ergebnis mit
     * gefülltem `totalNodeCount`** -- das wäre ein Existenz-Orakel (K3).
     *
     * **Bekannter, akzeptierter Kompromiss -- Security-Audit-Fund S-4 (2026-08-18)**: [truncated]
     * unten wird aus der ROHEN Zeilenzahl (`subtreeRows.size`, VOR jeder Sichtbarkeits-/State-
     * Filterung) gegen [SocialReadCaps.threadMaxNodes] berechnet, nicht aus der Zahl der tatsächlich
     * SICHTBAREN/angezeigten Nachfahren. Das kann theoretisch ein 1-Bit-Existenz-Signal an genau der
     * `threadMaxNodes`-Schwelle erzeugen: eine `PUBLIC`-Wurzel mit mehr als `threadMaxNodes`
     * durchweg versteckten/entfernten Kommentaren zeigt den `truncated`-Hinweis an, obwohl `0`
     * tatsächlich angezeigte Nachfahren existieren -- ein Leser kann daraus lernen "hier wurde
     * moderiert", was dem sonst im Modul verfolgten X5-Prinzip (keine Zähler, die Rückschlüsse auf
     * nicht-sichtbare Inhalte erlauben) widerspricht. NICHT behoben, bewusst: eine korrekte Lösung
     * müsste wissen, ob JENSEITS der geladenen `threadMaxNodes + 1` Zeilen noch weitere SICHTBARE
     * Knoten existieren -- das lässt sich mit der aktuellen Query-Form nicht ohne zusätzliche
     * Datenbank-Rundreisen (oder eine Sichtbarkeits-Filterung direkt in [loadSubtreeRows]) beantworten,
     * UND [loadSubtreeRows] darf nicht-sichtbare Knoten nicht einfach vorab ausschließen, weil deren
     * Gewicht weiterhin ins `totalCurrentWeightLtr` der Wurzel einfließt (siehe `PublicPostView` KDoc
     * "GESAMTGEWICHT zählt weiterhin alle Nachfahren"). Praktischer Wert des Lecks ist gering (nur an
     * einer festen 300-Knoten-Schwelle, nur "irgendwo wurde moderiert", kein Rückschluss auf WELCHER
     * Kommentar oder WELCHE Moderationsaktion) -- als bewusst akzeptierter Kompromiss dokumentiert
     * statt stillschweigend belassen.
     *
     * [nodeReadable] ist der X4-Defense-in-Depth-Haken: der RPC-Pfad übergibt
     * `{ v, s -> SocialVisibility.isReadable(visibility = v, state = s, status = current.status) }`,
     * der öffentliche Pfad `{ v, s -> SocialVisibility.isPublicReadable(visibility = v, state = s) }`.
     * Damit bleibt die Prüfung "jeder einzelne Knoten, nicht nur die Wurzel" in EINER Codestelle,
     * für beide Pfade.
     */
    fun thread(
        rootUuid: Uuid,
        condition: Op<Boolean>,
        nodeReadable: (SocialPostVisibility, SocialPostState) -> Boolean,
        now: LocalDateTime,
        viewerStatus: MemberStatus?,
        caps: SocialReadCaps,
        ltrBalanceProvider: LtrBalanceProvider,
    ): SocialThreadDto? {
        val rootRow =
            SocialPostTable
                .selectAll()
                .where { (SocialPostTable.id eq rootUuid) and condition }
                .singleOrNull() ?: return null
        // K4: only a genuine root id is accepted.
        if (rootRow[SocialPostTable.rootId] != rootUuid) return null
        // K3: no existence oracle -- a hidden/removed root is "not found", not an empty-but-counted thread.
        if (rootRow[SocialPostTable.state] != SocialPostState.VISIBLE) return null

        val subtreeRows =
            loadSubtreeRows(
                rootIds = listOf(rootUuid),
                horizon = null,
                maxRows = caps.threadMaxNodes + 1,
                rootFirst = true,
            )
        val totalNodeCount = subtreeRows.size
        val truncated = totalNodeCount > caps.threadMaxNodes
        val limitedRows = if (truncated) subtreeRows.take(caps.threadMaxNodes) else subtreeRows

        val weightNodes = limitedRows.map { it.toWeightNode() }
        val nodeIds = limitedRows.map { it[SocialPostTable.id] }
        val boosts = loadBoosts(postIds = nodeIds, maxRows = caps.boostRows)
        val aggregated = SocialPostWeight.aggregateWeightsUnrounded(nodes = weightNodes, boostsByPostId = boosts, now = now)
        val totalWeightById = aggregated.totalById
        val stateById = limitedRows.associate { it[SocialPostTable.id] to it[SocialPostTable.state] }
        val suppressed = SocialPostWeight.suppressedIds(nodes = weightNodes, stateById = stateById)
        val countsById = SocialPostWeight.descendantCounts(weightNodes.filter { it.id !in suppressed })
        val boostCountById = boosts.mapValues { it.value.size }
        val ownWeightById = aggregated.ownById

        // X4 Defense-in-Depth: exclude a node whose OWN visibility/state, checked individually (not
        // inherited from the root), is not readable for this caller -- on top of state-based
        // suppression above.
        val displayableIds =
            limitedRows
                .filter { row ->
                    val id = row[SocialPostTable.id]
                    id !in suppressed && nodeReadable(row[SocialPostTable.visibility], row[SocialPostTable.state])
                }.map { it[SocialPostTable.id] }
        // Fund #11: re-apply [condition] here too, not just `id inList displayableIds` -- defense
        // in depth, deliberately NOT "optimized away".
        val fullRowById =
            if (displayableIds.isEmpty()) {
                emptyMap()
            } else {
                SocialPostTable
                    .selectAll()
                    .where { (SocialPostTable.id inList displayableIds) and condition }
                    .associateBy { it[SocialPostTable.id] }
            }
        val preorder = buildPreorder(rows = displayableIds.mapNotNull { fullRowById[it] }, totalWeightById = totalWeightById)

        return SocialThreadDto(
            nodes =
                toDtos(
                    rows = preorder,
                    now = now,
                    viewerStatus = viewerStatus,
                    totalWeightById = totalWeightById,
                    countsById = countsById,
                    boostCountById = boostCountById,
                    ownWeightById = ownWeightById,
                    ltrBalanceProvider = ltrBalanceProvider,
                ),
            truncated = truncated,
            totalNodeCount = totalNodeCount,
        )
    }

    /**
     * Einzelner Post inkl. Teilbaum-Aggregation für [SocialPostDto.totalCurrentWeightLtr] und die
     * Zähler. `null` == nicht erreichbar (kein Orakel: ein nicht gefundener Post und ein
     * existierender, aber unter [condition] nicht sichtbarer Post liefern identisch `null`).
     */
    fun post(
        postUuid: Uuid,
        condition: Op<Boolean>,
        now: LocalDateTime,
        viewerStatus: MemberStatus?,
        caps: SocialReadCaps,
        ltrBalanceProvider: LtrBalanceProvider,
    ): SocialPostDto? {
        val row =
            SocialPostTable
                .selectAll()
                .where { (SocialPostTable.id eq postUuid) and condition }
                .singleOrNull() ?: return null
        return dtoWithSubtreeAggregation(
            row = row,
            now = now,
            viewerStatus = viewerStatus,
            caps = caps,
            ltrBalanceProvider = ltrBalanceProvider,
        )
    }

    /**
     * Lädt den vollständigen Wald unter [rootIds] in EINER Query (`root_id inList rootIds`) -- die
     * Wurzeln selbst eingeschlossen, weil `root_id` für eine Wurzel auf sie selbst zeigt
     * (V4-Invariante). KEIN ebenenweiser Abstieg, KEIN `WITH RECURSIVE`: `root_id` macht den
     * Teilbaum zu einem flachen Prädikat, und die eigentliche Rekursion findet in
     * [SocialPostWeight.aggregateWeightsUnrounded] statt -- in Kotlin/`BigDecimal`, weil die
     * Zerfallsmathematik niemals in SQL wandern darf.
     *
     * Schlanke Projektion -- NIEMALS `selectAll()`: `content` ist bis zu 5000 Zeichen und wird für
     * die Aggregation nie gebraucht. [horizon] ist `null` für [thread]/[post] (dort will man den
     * vollständigen Thread) und ggf. gesetzt für [timelinePage].
     *
     * [rootFirst]: Sortierung `depth ASC, publishedAt ASC` statt `publishedAt DESC` -- die Wurzel
     * hat `depth = 0`, ist also IMMER die allererste Zeile, und weil jedes Kind per Konstruktion
     * `depth = parent.depth + 1` hat, kann ein `.limit()`/`.take()`-Schnitt auf dieser Sortierung
     * nur Knoten verlieren, deren ELTERNTEIL bereits früher in derselben Sortierung enthalten war --
     * kein erreichbares-aber-elternloses Kind entsteht. [timelinePage]s eigener Teilbaum-Load
     * behält bewusst das ALTE `publishedAt DESC`-Verhalten (die jüngsten Nachfahren tragen das
     * meiste Gewicht) -- dort sind die Kandidaten-Wurzeln bereits über die Ranking-Projektion
     * fixiert, eine dort verlorene Wurzel ist also nicht dasselbe Fehlerbild wie bei [thread]/[post].
     */
    private fun loadSubtreeRows(
        rootIds: List<Uuid>,
        horizon: LocalDateTime?,
        maxRows: Int,
        rootFirst: Boolean = false,
    ): List<ResultRow> {
        if (rootIds.isEmpty()) return emptyList()
        var condition: Op<Boolean> = SocialPostTable.rootId inList rootIds
        if (horizon != null) {
            condition = condition and (SocialPostTable.publishedAt greaterEq horizon)
        }
        val query =
            SocialPostTable
                .select(
                    SocialPostTable.id,
                    SocialPostTable.parentId,
                    SocialPostTable.rootId,
                    SocialPostTable.depth,
                    SocialPostTable.initialWeightLtr,
                    SocialPostTable.publishedAt,
                    SocialPostTable.state,
                    SocialPostTable.visibility,
                ).where { condition }
        val sorted =
            if (rootFirst) {
                query.orderBy(SocialPostTable.depth to SortOrder.ASC, SocialPostTable.publishedAt to SortOrder.ASC)
            } else {
                query.orderBy(SocialPostTable.publishedAt, SortOrder.DESC)
            }
        return sorted.limit(maxRows).toList()
    }

    /** Alle Boosts zu [postIds] in EINER Query -- Anti-N+1-Muster. */
    private fun loadBoosts(
        postIds: List<Uuid>,
        maxRows: Int,
    ): Map<Uuid, List<SocialPostWeight.BoostContribution>> {
        if (postIds.isEmpty()) return emptyMap()
        return SocialPostBoostTable
            .select(SocialPostBoostTable.postId, SocialPostBoostTable.amountLtr, SocialPostBoostTable.boostedAt)
            .where { SocialPostBoostTable.postId inList postIds }
            .orderBy(SocialPostBoostTable.boostedAt, SortOrder.DESC)
            .limit(maxRows)
            .toList()
            .groupBy { it[SocialPostBoostTable.postId] }
            .mapValues { (_, rows) ->
                rows.map {
                    SocialPostWeight.BoostContribution(
                        amountLtr = it[SocialPostBoostTable.amountLtr],
                        boostedAt = it[SocialPostBoostTable.boostedAt],
                    )
                }
            }
    }

    /**
     * K1: builds the flat preorder [rows] must be delivered in for [SocialThreadDto.nodes] --
     * root(s) first, then each node's children ordered by [totalWeightById] descending, tiebreak
     * `publishedAt` ascending, then `id`. Recursion depth is bounded by [SocialPostWeight.MAX_DEPTH]
     * (64, enforced at write time in `SocialNetworkService.createComment`), so this is never at
     * risk of a stack overflow regardless of a thread's breadth.
     */
    private fun buildPreorder(
        rows: List<ResultRow>,
        totalWeightById: Map<Uuid, BigDecimal>,
    ): List<ResultRow> {
        val byParent = rows.filter { it[SocialPostTable.parentId] != null }.groupBy { it[SocialPostTable.parentId] }
        val roots = rows.filter { it[SocialPostTable.parentId] == null }
        val siblingComparator =
            compareByDescending<ResultRow> { totalWeightById[it[SocialPostTable.id]] ?: BigDecimal.ZERO }
                .thenBy { it[SocialPostTable.publishedAt] }
                .thenBy { it[SocialPostTable.id].toString() }
        val result = mutableListOf<ResultRow>()

        fun visit(node: ResultRow) {
            result += node
            byParent[node[SocialPostTable.id]].orEmpty().sortedWith(siblingComparator).forEach { visit(it) }
        }
        roots.sortedWith(siblingComparator).forEach { visit(it) }
        return result
    }

    private fun ResultRow.toWeightNode(): SocialPostWeight.WeightNode =
        SocialPostWeight.WeightNode(
            id = this[SocialPostTable.id],
            parentId = this[SocialPostTable.parentId],
            depth = this[SocialPostTable.depth],
            initialWeightLtr = this[SocialPostTable.initialWeightLtr],
            publishedAt = this[SocialPostTable.publishedAt],
        )

    /**
     * Shared by [post] and `SocialNetworkService.loadPostAfterCommit`: loads [row]'s full subtree
     * (via its `root_id`), aggregates Gesamtgewicht/Zähler/Boosts, and returns the DTO for exactly
     * this one row.
     */
    private fun dtoWithSubtreeAggregation(
        row: ResultRow,
        now: LocalDateTime,
        viewerStatus: MemberStatus?,
        caps: SocialReadCaps,
        ltrBalanceProvider: LtrBalanceProvider,
    ): SocialPostDto {
        val rootId = row[SocialPostTable.rootId]
        val subtreeRows = loadSubtreeRows(rootIds = listOf(rootId), horizon = null, maxRows = caps.threadMaxNodes, rootFirst = true)
        val weightNodes = subtreeRows.map { it.toWeightNode() }
        val nodeIds = subtreeRows.map { it[SocialPostTable.id] }
        val boosts = loadBoosts(postIds = nodeIds, maxRows = caps.boostRows)
        val aggregated = SocialPostWeight.aggregateWeightsUnrounded(nodes = weightNodes, boostsByPostId = boosts, now = now)
        val totalWeightById = aggregated.totalById
        val stateById = subtreeRows.associate { it[SocialPostTable.id] to it[SocialPostTable.state] }
        val suppressed = SocialPostWeight.suppressedIds(nodes = weightNodes, stateById = stateById)
        val countsById = SocialPostWeight.descendantCounts(weightNodes.filter { it.id !in suppressed })
        val boostCountById = boosts.mapValues { it.value.size }
        val ownWeightById = aggregated.ownById
        return toDtos(
            rows = listOf(row),
            now = now,
            viewerStatus = viewerStatus,
            totalWeightById = totalWeightById,
            countsById = countsById,
            boostCountById = boostCountById,
            ownWeightById = ownWeightById,
            ltrBalanceProvider = ltrBalanceProvider,
        ).single()
    }

    /**
     * Batched author lookup (display name + free LTR balance) -- ONE query for every distinct
     * author across [rows], not one query per row.
     *
     * [viewerStatus] `null` bedeutet **unauthentifizierter Besucher** (V1.1.3, öffentlicher HTTP-
     * Lesepfad). Bewusst ein eigener, sprechender Wert statt eines missbrauchten [MemberStatus]-
     * Literals (z. B. `REJECTED`) als Platzhalter: ein Sentinel wäre eine stille Falle, sobald jemand
     * die Semantik dieses Literals ändert.
     *
     * Security-Audit-Fund S-1 (2026-08-18), **erweitert in Welle V1.1.4 (Entscheidungspunkt E-A,
     * Option B, Nutzerentscheidung 2026-08-19)**: [SocialPostDto.authorFreeBalanceLtr] wird für
     * jeden [viewerStatus] befüllt, der non-null ist UND in [MemberStatusSets.LTR_ELIGIBLE] liegt
     * (also ACTIVE UND FRIEND) -- nicht mehr nur [MemberStatusSets.ORGANIZATION_MEMBER]. Ein
     * FRIEND-Betrachter sieht damit das Autorengewicht JEDES Autors in seiner Timeline, nicht nur
     * das eigene. Ein `null` [viewerStatus] (unauthentifizierter Besucher über den öffentlichen
     * Lesepfad, `SocialPublicRoutes.kt`) bleibt unverändert bei `null` für jede Zeile --
     * [ltrBalanceProvider]`.freeBalances` wird für ihn weiterhin komplett übersprungen.
     *
     * **Bewusst getroffene, vom Nutzer bestätigte Entscheidung -- kein übersehenes Risiko:** ein
     * FRIEND-Konto ist kostenlos, selbstregistriert und identitäts-ungeprüft (Cap
     * `LAPIS_FRIEND_MAX_ACCOUNTS`, Default 500). Bis zu 500 selbstregistrierte FRIEND-Konten können
     * ab dieser Welle über unbegrenzte Timeline-Reads (Lesepfad-Rate-Limit 60/min,
     * `readRateLimiter`) die LTR-Vermögensverteilung ALLER Mitglieder abgreifen (Scraping-Vektor).
     * Der Plan (`soziales_netzwerk_v1.1.4_plan.md` Teil 3, Option A) hatte stattdessen eine
     * Selbst-Ausnahme empfohlen (Grenze bleibt ORGANIZATION_MEMBER, FRIEND sieht nur sein EIGENES
     * Gewicht) -- der Nutzer hat sich explizit für die einfachere, weitere Option B entschieden. Die
     * nächste Security-Runde soll diesen Vektor **nicht** als neuen Fund behandeln, sondern als
     * bekanntes, akzeptiertes Restrisiko -- siehe [MemberStatusSets.LTR_ELIGIBLE]s eigenes KDoc,
     * Absatz "Akzeptiertes Restrisiko (Welle V1.1.4, Entscheidungspunkt E-C)", für die vollständige
     * Einordnung inkl. `LAPIS_FRIEND_MAX_ACCOUNTS` als einziger mengenmäßiger Bremse.
     *
     * Der unauthentifizierte, öffentliche Lesepfad (`SocialPublicRoutes.kt`, `viewerStatus = null`)
     * ist von dieser Erweiterung UNBERÜHRT -- er sieht weiterhin nie ein Autorengewicht.
     *
     * [totalWeightById]/[countsById]/[boostCountById]/[ownWeightById] are PRE-COMPUTED by the
     * caller, NEVER re-derived here per row. A missing entry in any of them defaults to
     * Eigengewicht/0, never an exception, never a silent `getValue` crash on the production path.
     */
    private fun toDtos(
        rows: List<ResultRow>,
        now: LocalDateTime,
        viewerStatus: MemberStatus?,
        totalWeightById: Map<Uuid, BigDecimal>,
        countsById: Map<Uuid, SocialPostWeight.DescendantCounts>,
        boostCountById: Map<Uuid, Int>,
        ownWeightById: Map<Uuid, BigDecimal>,
        ltrBalanceProvider: LtrBalanceProvider,
    ): List<SocialPostDto> {
        if (rows.isEmpty()) return emptyList()
        val authorIds = rows.map { it[SocialPostTable.authorMemberId] }.distinct()
        val displayNames =
            MemberTable
                .selectAll()
                .where { MemberTable.id inList authorIds }
                .associate { it[MemberTable.id] to it[MemberTable.displayName] }
        val freeBalances =
            if (viewerStatus != null && viewerStatus in MemberStatusSets.LTR_ELIGIBLE) {
                ltrBalanceProvider.freeBalances(authorIds)
            } else {
                emptyMap()
            }
        return rows.map { row ->
            val id = row[SocialPostTable.id]
            val authorId = row[SocialPostTable.authorMemberId]
            val ownWeight =
                ownWeightById[id] ?: SocialPostWeight.ownWeightUnrounded(
                    initialWeightLtr = row[SocialPostTable.initialWeightLtr],
                    publishedAt = row[SocialPostTable.publishedAt],
                    now = now,
                )
            val totalWeight = totalWeightById[id] ?: ownWeight
            val counts = countsById[id] ?: SocialPostWeight.DescendantCounts(direct = 0, total = 0)
            SocialPostDto(
                id = id.toString(),
                parentId = row[SocialPostTable.parentId]?.toString(),
                rootId = row[SocialPostTable.rootId].toString(),
                depth = row[SocialPostTable.depth],
                authorMemberId = authorId.toString(),
                authorDisplayName = displayNames[authorId] ?: "",
                authorFreeBalanceLtr = freeBalances[authorId],
                content = row[SocialPostTable.content],
                visibility = row[SocialPostTable.visibility],
                state = row[SocialPostTable.state],
                stateReason = row[SocialPostTable.stateReason],
                initialWeightLtr = row[SocialPostTable.initialWeightLtr],
                ownCurrentWeightLtr = WeightDecayClock.round2(ownWeight),
                totalCurrentWeightLtr = WeightDecayClock.round2(totalWeight),
                directCommentCount = counts.direct,
                totalDescendantCount = counts.total,
                boostCount = boostCountById[id] ?: 0,
                publishedAt = row[SocialPostTable.publishedAt],
            )
        }
    }
}
