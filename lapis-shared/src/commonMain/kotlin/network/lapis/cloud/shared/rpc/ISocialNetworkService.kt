package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialTimelinePageDto
import network.lapis.cloud.shared.domain.SocialTimelineQuery

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" -- see
 * `32-social-network.kuml.kts` file header for the full fachlich model. Deliberately
 * INCOMPLETE: `createComment`/`getThread`/`boostPost` (Welle V1.1.2), `removePostForLegalReason`/
 * `reportPost`/`listReports`/`decideReport`/`requestContentErasure`/`listContentErasures`/
 * `decideContentErasure`/`executeContentErasure` (Welle V1.1.5) are NOT declared here as TODO
 * stubs -- later waves extend this interface when their own tables/service methods land, exactly
 * like every other domain interface in this codebase grows additively.
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
     * nicht direkt sichtbar), sortiert nach zerfallenem Eigengewicht (absteigend), Tiebreaker
     * `publishedAt` dann `id`. Ergänzt (NEU-2, Review Runde 2, 2026-08-18): Beiträge, deren
     * `publishedAt` mehr als
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
     * aber aus [listTimeline]. Kaskade auf Kind-Posts ist Welle V1.1.2 (in Welle 1 existieren
     * keine Kind-Posts). Korrigiert (NEU-4, Review Runde 2, 2026-08-18): ein bereits
     * [network.lapis.cloud.shared.domain.SocialPostState.REMOVED_LEGAL]-Post wirft hier ebenfalls
     * `NotFoundException`, nicht `ConflictException` -- konsistent mit [getPost]'s "existiert
     * nicht"-Behandlung desselben Zustands, auch für den eigenen Autor.
     */
    suspend fun hideOwnPost(postId: String): SocialPostDto
}
