package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import network.lapis.cloud.shared.domain.SocialCommentInput
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialThreadDto
import network.lapis.cloud.shared.domain.SocialTimelinePageDto
import network.lapis.cloud.shared.domain.SocialTimelineQuery

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum, Boosts,
 * rekursive Gesamtgewichtung" -- see `32-social-network.kuml.kts` file header for the full
 * fachlich model. Deliberately INCOMPLETE: `removePostForLegalReason`/`reportPost`/`listReports`/
 * `decideReport`/`requestContentErasure`/`listContentErasures`/`decideContentErasure`/
 * `executeContentErasure` (Welle V1.1.5) are NOT declared here as TODO stubs -- later waves extend
 * this interface when their own tables/service methods land, exactly like every other domain
 * interface in this codebase grows additively.
 */
@RpcService
interface ISocialNetworkService {
    /**
     * Rolle: `ORGANIZATION_MEMBER` (Welle 1, via `requireActiveMembership`; ab Welle V1.1.4
     * `LTR_ELIGIBLE`), für sich selbst. Bindet [SocialPostInput.initialWeightLtr] als LTR-Debit
     * ([network.lapis.cloud.shared.domain.LtrLedgerEntryType.SOCIAL_POST_STAKE], referenceType
     * [network.lapis.cloud.shared.domain.LtrLedgerReferenceType.SOCIAL_POST]). Wirft
     * `ConflictException`, wenn der Einsatz < 0,01 LTR ist, mehr als 2 Nachkommastellen hat, oder
     * das freie Guthaben übersteigt. Der Post ist nach Rückkehr unveränderlich -- es existiert
     * keine `updatePost`-Methode.
     */
    suspend fun createPost(input: SocialPostInput): SocialPostDto

    /**
     * Jeder authentifizierte Aufrufer; Ergebnis ist nach seiner eigenen Sichtbarkeitsstufe
     * gefiltert ([network.lapis.cloud.server.rpc.SocialVisibility] serverseitig, für JS-Consumer
     * nicht direkt sichtbar), sortiert nach GESAMTgewicht ([SocialPostDto.totalCurrentWeightLtr]
     * absteigend -- Eigengewicht PLUS rekursive Summe der Gesamtgewichte aller Nachfahren, seit
     * Welle V1.1.2, siehe [network.lapis.cloud.server.rpc.SocialPostWeight.totalWeightsUnrounded]
     * KDoc; korrigiert Review Runde 1, 2026-08-18 -- diese KDoc behauptete bis dahin fälschlich noch
     * "zerfallenes Eigengewicht", das Kernfeature dieser Welle), Tiebreaker `publishedAt` dann `id`.
     * Ergänzt (NEU-2, Review Runde 2, 2026-08-18): Beiträge, deren `publishedAt` mehr als
     * [network.lapis.cloud.server.rpc.SocialPostWeight.RANKING_HORIZON_DAYS] (aktuell 400) Tage
     * zurückliegt, fallen grundsätzlich aus dem Ergebnis -- ein alter Post kann also aus der
     * Timeline verschwinden, ohne dass sich sein `state` ändert. Ausnahme: die eigene
     * `includeHidden`-Übersicht des Autors (`authorMemberId` = aufrufendes Mitglied) ist von diesem
     * Cutoff ausgenommen und zeigt auch ältere eigene Beiträge weiterhin vollständig.
     */
    suspend fun listTimeline(query: SocialTimelineQuery): SocialTimelinePageDto

    /**
     * Jeder authentifizierte Aufrufer. Sichtbarkeitsprüfung wie [listTimeline]; direkter
     * ID-Zugriff funktioniert auch für einen [network.lapis.cloud.shared.domain.SocialPostState
     * .HIDDEN_BY_AUTHOR]-Post (Konzept: "Direkter Zugriff bleibt möglich"). Ein
     * [network.lapis.cloud.shared.domain.SocialPostState.REMOVED_LEGAL]-Post wird dagegen wie
     * nicht-existent behandelt (`NotFoundException`, auch für BOARD/ADMIN -- diese Welle hat noch
     * keinen Sonderzugriff dafür, siehe [network.lapis.cloud.server.rpc.SocialVisibility] KDoc) --
     * ein aus rechtlichen Gründen entfernter Beitrag darf nicht über einen direkten Link weiter
     * erreichbar sein. Kein Codepfad dieser Welle schreibt `REMOVED_LEGAL` (das kommt erst mit
     * Welle V1.1.5), die Sperre gilt aber bereits jetzt, bevor die Primitive wiederverwendet wird.
     */
    suspend fun getPost(id: String): SocialPostDto

    /**
     * Rolle: der Autor selbst, für seinen eigenen Post. Setzt
     * `state = HIDDEN_BY_AUTHOR`, irreversibel (keine `unhideOwnPost`-Methode existiert). Keine
     * LTR-Rückerstattung. Der Post bleibt über [getPost] weiterhin direkt erreichbar, verschwindet
     * aber aus [listTimeline]. **K2 (Welle V1.1.2)**: diese Methode schreibt ausschließlich ihre
     * EIGENE Zeile -- es gibt KEIN Cascade-`UPDATE` auf Kind-Posts. Die Kaskade auf den Teilbaum
     * entsteht ausschließlich zur LESEZEIT über
     * [network.lapis.cloud.server.rpc.SocialPostWeight.suppressedIds] (siehe [getThread]); das
     * GEWICHT des Teilbaums bleibt dabei vollständig erhalten (E3). Korrigiert (NEU-4, Review
     * Runde 2, 2026-08-18): ein bereits
     * [network.lapis.cloud.shared.domain.SocialPostState.REMOVED_LEGAL]-Post wirft hier ebenfalls
     * `NotFoundException`, nicht `ConflictException` -- konsistent mit [getPost]'s "existiert
     * nicht"-Behandlung desselben Zustands, auch für den eigenen Autor.
     */
    suspend fun hideOwnPost(postId: String): SocialPostDto

    /**
     * Rolle wie [createPost]. [SocialCommentInput.parentId] muss auf einen erreichbaren Post mit
     * `state == VISIBLE` zeigen (Eltern-Zeile wird FOR UPDATE gelesen, siehe Service-KDoc).
     * `visibility` wird vom WURZEL-Post übernommen und ist bewusst NICHT Teil von
     * [SocialCommentInput] (S5) -- ein öffentlicher Kommentar unter einem internen Post würde schon
     * durch seine bloße Existenz den internen Kontext leaken. `depth = parent.depth + 1`, gedeckelt
     * bei [network.lapis.cloud.server.rpc.SocialPostWeight.MAX_DEPTH] (64, DB-seitig gespiegelt);
     * `rootId` wird vom Elternknoten übernommen. Bindet [SocialCommentInput.initialWeightLtr] als
     * eigenen [network.lapis.cloud.shared.domain.LtrLedgerEntryType.SOCIAL_POST_STAKE]-Debit -- ein
     * Kommentar ist ein vollwertiger Post. Erhöht das Gesamtgewicht JEDES Vorfahren -- rein
     * rechnerisch, es wird nach oben nichts geschrieben.
     */
    suspend fun createComment(input: SocialCommentInput): SocialPostDto

    /**
     * Jeder authentifizierte Aufrufer. Vollständiger Teilbaum ab [rootId] (dem Wurzel-Post, nicht
     * einem beliebigen Knoten -- eine Nicht-Wurzel-ID wirft `NotFoundException`, der Client löst
     * über `getPost(id).rootId` auf), flach in Präorder mit gefülltem [SocialPostDto.depth].
     * Sichtbarkeit: [network.lapis.cloud.server.rpc.SocialVisibility.readableByCondition] auf dem
     * Wurzel-Post PLUS eine Defense-in-Depth-Prüfung jedes einzelnen Knotens. Knoten, deren
     * Vorfahrenkette einen nicht-`VISIBLE`-Knoten enthält, werden ausgelassen -- ihr GEWICHT zählt
     * aber weiter (E3). Eine nicht-`VISIBLE` Wurzel wirft `NotFoundException` (kein
     * Existenz-Orakel über ein leeres Ergebnis mit gefülltem `totalNodeCount`). Maximal
     * [network.lapis.cloud.server.rpc.SocialPostWeight.THREAD_MAX_NODES] Knoten; darüber liefert
     * [SocialThreadDto.truncated] `true` statt zu werfen.
     */
    suspend fun getThread(rootId: String): SocialThreadDto

    /**
     * Rolle wie [createPost]. Monetäres Like:
     * [network.lapis.cloud.shared.domain.LtrLedgerEntryType.SOCIAL_POST_BOOST]-Debit, mindestens
     * [network.lapis.cloud.server.rpc.SocialPostWeight.MIN_WEIGHT_LTR], höchstens zwei
     * Nachkommastellen. Mehrfache Boosts desselben Aufrufers auf denselben Post sind erlaubt (S3)
     * -- gegen den Doppelklick schützt ein 5-Sekunden-Duplikatfenster (E6), nicht ein
     * Unique-Constraint. Ein Boost zerfällt ab SEINEM eigenen Zeitstempel (S4), nicht ab dem des
     * Posts. Der Post muss `state == VISIBLE` sein. Gibt den geboosteten Post mit frisch
     * berechnetem Gesamtgewicht zurück.
     */
    suspend fun boostPost(
        postId: String,
        amountLtr: Decimal,
    ): SocialPostDto
}
