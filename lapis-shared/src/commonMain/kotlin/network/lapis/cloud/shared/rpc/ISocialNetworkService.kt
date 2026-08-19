package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.SocialCommentInput
import network.lapis.cloud.shared.domain.SocialPostDto
import network.lapis.cloud.shared.domain.SocialPostErasureDto
import network.lapis.cloud.shared.domain.SocialPostErasureInput
import network.lapis.cloud.shared.domain.SocialPostErasureStatus
import network.lapis.cloud.shared.domain.SocialPostInput
import network.lapis.cloud.shared.domain.SocialPostRemovalNoticeDto
import network.lapis.cloud.shared.domain.SocialPostReportDto
import network.lapis.cloud.shared.domain.SocialPostReportInput
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import network.lapis.cloud.shared.domain.SocialThreadDto
import network.lapis.cloud.shared.domain.SocialTimelinePageDto
import network.lapis.cloud.shared.domain.SocialTimelineQuery

/**
 * Soziales Netzwerk, Welle V1.1.1 "Fundament & Post-Kern" + Welle V1.1.2 "Kommentarbaum, Boosts,
 * rekursive Gesamtgewichtung" + Welle V1.1.3 "Öffentlicher SEO-Lesepfad" + Welle V1.1.4
 * "LTR_ELIGIBLE/FRIEND-Erweiterung" + Welle V1.1.5 "Moderation, DSA-Melde-Mechanismus,
 * DSGVO-Content-Hard-Delete" -- see `32-social-network.kuml.kts` file header for the full fachlich
 * model this domain implements.
 *
 * **Welle V1.1.5 adds NINE methods**, not eight: the eight vordeklarierten Namen
 * (`removePostForLegalReason`/`reportPost`/`listReports`/`decideReport`/`requestContentErasure`/
 * `listContentErasures`/`decideContentErasure`/`executeContentErasure`) plus [getRemovalNotice] --
 * a NINTH method, added additively as the resolution of Entscheidungspunkt E-B (der öffentliche
 * Entfernungshinweis geht über den öffentlichen HTTP-Pfad hinaus und braucht ein Äquivalent für
 * nicht-öffentliche Sichtbarkeitsstufen, siehe [getRemovalNotice] KDoc).
 *
 * **Welle V1.1.3**: this interface covers ONLY the authenticated Kilua-RPC surface. The
 * unauthenticated public HTML read path (`GET /s`, `GET /s/{id}`, `GET /sitemap.xml`,
 * `GET /robots.txt`, and, since Welle V1.1.5, `GET`/`POST /s/{id}/report`) does NOT run through
 * this interface at all -- it is a dedicated Ktor route family
 * (`network.lapis.cloud.server.routes.SocialPublicRoutes`) that shares the underlying
 * load/aggregate pipeline (`network.lapis.cloud.server.rpc.SocialReadPipeline`) but has no RPC
 * service class and no wire contract here. A JS consumer wanting the public view fetches the
 * rendered HTML/XML directly, not via this interface.
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

    // ── Welle V1.1.5 -- Moderation (DSA Art. 16/6) ─────────────────────────────────────────────

    /**
     * Rolle: BOARD oder ADMIN (Entscheidungspunkt E-D -- jeder für sich allein, kein
     * Vier-Augen-Zwang, DSA Art. 6 verlangt unverzügliches Handeln). [reason] ist PFLICHT (nicht
     * leer, ≤ 2000 Zeichen) UND ist ab dieser Welle **öffentlicher Text** (Entscheidungspunkt E-B):
     * für einen `PUBLIC`-Post wird er jedem anonymen Besucher auf der 451-Hinweisseite unter
     * `GET /s/{id}` angezeigt; für einen nicht-öffentlichen Post über [getRemovalNotice]. Enthält
     * deshalb NIEMALS interne Vorgangsdaten -- diese gehören in `SocialPostReportDto.decisionNote`
     * bzw. `SocialPostErasureDto.decisionNote`, beide bleiben strikt intern.
     *
     * Bewusst NICHT [network.lapis.cloud.server.rpc.SocialVisibility.isReadable]-gegattert (anders
     * als jeder andere Schreibpfad dieser Domäne) -- ein Moderator muss auch einen
     * `MEMBERS_ONLY`-Post entfernen können, den er selbst (bei abweichendem Status) nicht lesen
     * dürfte. Ein bereits `REMOVED_LEGAL`-Post wirft `ConflictException` ("bereits entfernt"),
     * NICHT `NotFoundException` -- der Aufrufer ist bereits BOARD/ADMIN und darf den Zustand
     * kennen, hier gibt es kein Existenz-Orakel zu schützen. `HIDDEN_BY_AUTHOR` ist ein zulässiger
     * Ausgangszustand (Verstecken ≠ Entfernen). Fasst `content` NIE an (orthogonal zum
     * Content-Tombstoning, siehe [SocialPostDto.contentErasedAt] KDoc) und schreibt KEINEN
     * Cascade-`UPDATE` auf Kind-Posts -- die Unterdrückung des Teilbaums entsteht wie bei
     * [hideOwnPost] ausschließlich zur Lesezeit über `SocialPostWeight.suppressedIds`. Keine
     * LTR-Rückerstattung. Schließt alle offenen [reportPost]-Meldungen auf diesen Post automatisch
     * auf `ACTION_TAKEN`.
     */
    suspend fun removePostForLegalReason(
        postId: String,
        reason: String,
    ): SocialPostDto

    /**
     * Rolle: jeder authentifizierte Aufrufer (auch FRIEND/GUEST) -- eine Meldung kostet kein LTR
     * und ist keine Autoritätsausübung. Rückgabe bewusst `Unit`, die Antwort ist **identisch**, ob
     * der Post existiert, für den Aufrufer lesbar ist, für ihn nicht lesbar ist, oder gar nicht
     * existiert (Enumeration-Härtung -- ohne das wäre dies ein Existenz-Orakel für
     * `MEMBERS_ONLY`-Posts). Der Autor darf seinen eigenen Post nicht melden -- ebenfalls stiller
     * No-Op, kein `ConflictException` (sonst wieder ein Signal-Unterschied). Der öffentliche Pfad
     * (`GET`/`POST /s/{id}/report`) teilt sich dieselbe Kernlogik über
     * `network.lapis.cloud.server.rpc.SocialReportSubmission`.
     */
    suspend fun reportPost(input: SocialPostReportInput)

    /**
     * Rolle: BOARD oder ADMIN. Sortiert `reportedAt DESC` (Tiebreaker `id DESC`), Seitengröße
     * gedeckelt bei 200 Zeilen. `status = null` == alle Status.
     *
     * **Security-Audit-Fund MAJOR-2 (Runde 1, 2026-08-19): echte Keyset-Pagination**, analog
     * [network.lapis.cloud.shared.rpc.IAuditLogService.listAuditLog]'s
     * `beforeSequenceNumber`-Cursor. Vorher war die Warteschlange hart bei 200 Zeilen gedeckelt,
     * OHNE jede Seitennavigation -- über den seit dieser Welle öffentlichen, unauthentifizierten
     * Melde-Weg (`POST /s/{id}/report`) erreichbar konnte sie so über die Grenze hinaus anwachsen;
     * ältere offene Meldungen wären dauerhaft über keine UI mehr erreichbar gewesen, was der DSA-
     * Art.-16-Pflicht widerspricht, eingehende Meldungen tatsächlich zu prüfen. Anders als
     * [IAuditLogService.listAuditLog] gibt es hier keine monoton wachsende `sequenceNumber`-Spalte
     * -- der Cursor ist deshalb ein KOMPOSIT aus `beforeReportedAt`/`beforeId` (Zeitstempel plus
     * Tiebreaker, weil `reportedAt` bei zwei schnell aufeinanderfolgenden Meldungen theoretisch
     * kollidieren kann, `id` dagegen als `UUID.random()` selbst keine Sortierreihenfolge trägt und
     * daher NICHT als alleiniger Cursor taugt). Beide `null` == erste Seite; nur eines von beiden
     * gesetzt wird wie "kein Cursor" behandelt (kein Fehler, einfach keine Filterung). Aufrufer
     * bilden den nächsten Cursor aus der zuletzt gesehenen Zeile dieser Antwort
     * (`reportedAt`/`id` des LETZTEN Elements).
     */
    suspend fun listReports(
        status: SocialPostReportStatus?,
        beforeReportedAt: LocalDateTime? = null,
        beforeId: String? = null,
    ): List<SocialPostReportDto>

    /**
     * Rolle: BOARD oder ADMIN. Zulässige Zielwerte: `UNDER_REVIEW`/`ACTION_TAKEN`/`DISMISSED` --
     * `OPEN` als Ziel wirft `ConflictException` (kein Zurückdrehen). Ausgangszustand muss `OPEN`
     * oder `UNDER_REVIEW` sein. Entfernt den gemeldeten Post NICHT selbst -- das ist der separate
     * [removePostForLegalReason]-Aufruf.
     */
    suspend fun decideReport(
        reportId: String,
        decision: SocialPostReportStatus,
        note: String?,
    ): SocialPostReportDto

    // ── Welle V1.1.5 -- DSGVO-Content-Hard-Delete (post-bezogener Art.-17-Antrag) ──────────────

    /**
     * Rolle: self-or-ADMIN (Muster `IDsgvoService.requestErasure`) -- kein Rollen-Gate im engeren
     * Sinn, jeder authentifizierte Aufrufer (auch FRIEND/GUEST) darf einen Löschantrag stellen.
     * **Nicht auf eigene Beiträge beschränkt** -- ein Post kann personenbezogene Daten des
     * Antragstellers enthalten, ohne dass dieser ihn selbst verfasst hat (z. B. namentliche
     * Erwähnung in einem fremden Beitrag). Für einen Nicht-ADMIN gilt daher dieselbe
     * Lesbarkeits-Schranke wie beim Lesen selbst (`SocialVisibility.readableByCondition` in der
     * `SocialNetworkService`-Implementierung, identische Enumeration-Härtung wie [reportPost]: ein
     * unlesbarer und ein nicht existierender Post liefern ununterscheidbar `NotFoundException`).
     * Ein ADMIN darf zusätzlich im Namen einer externen betroffenen Person (per
     * [SocialPostErasureInput.subjectMemberId] bzw. `requesterContact`) beantragen und ist dabei
     * NICHT an die Lesbarkeit des Posts für sich selbst gebunden (reine Existenzprüfung) -- der
     * Weg, auf dem eine per E-Mail/Post eingegangene Löschforderung eines Dritten OHNE eigenes
     * Lapis-Cloud-Konto ins System kommt (im Unterschied zum bestehenden, mitglieds-bezogenen
     * `IDsgvoService.requestErasure`-Pfad, dessen `subjectMemberId` strukturell NOT NULL ist).
     */
    suspend fun requestContentErasure(input: SocialPostErasureInput): SocialPostErasureDto

    /**
     * Rolle: ADMIN (Entscheidungspunkt E-E -- eine Art.-17-Abwägung ist eine Datenschutz-, keine
     * Moderationsentscheidung, dieselbe Schwelle wie `IDsgvoService.listErasureRequests`).
     *
     * **Security-Audit-Fund MAJOR-2 (Runde 1, 2026-08-19)**: dieselbe Keyset-Pagination wie
     * [listReports] -- siehe dessen KDoc für die volle Begründung. Sortiert `requestedAt DESC`
     * (Tiebreaker `id DESC`), Cursor-Komposit `beforeRequestedAt`/`beforeId`.
     */
    suspend fun listContentErasures(
        status: SocialPostErasureStatus?,
        beforeRequestedAt: LocalDateTime? = null,
        beforeId: String? = null,
    ): List<SocialPostErasureDto>

    /** Rolle: ADMIN. `REQUESTED --approve--> APPROVED` bzw. `REQUESTED --reject--> REJECTED`. */
    suspend fun decideContentErasure(
        erasureId: String,
        approve: Boolean,
        note: String?,
    ): SocialPostErasureDto

    /**
     * Rolle: ADMIN. Nur auf einem `APPROVED`-Antrag ausführbar (`ConflictException` sonst,
     * identisch zu `IDsgvoService.executeErasure`). Überschreibt `content` IN PLACE mit dem
     * anlassabhängigen Tombstone-Marker (`network.lapis.cloud.server.rpc.SocialContentTombstone
     * .ON_POST_REQUEST`), setzt [SocialPostDto.contentErasedAt]. Fasst `state` NIE an --
     * orthogonal zu [removePostForLegalReason]. Idempotent: ein zweiter Aufruf auf einen bereits
     * getombstoneten Post überschreibt den Marker nicht erneut ("erster Schreiber gewinnt"),
     * schlägt aber auch nicht fehl.
     */
    suspend fun executeContentErasure(erasureId: String): SocialPostErasureDto

    // ── Welle V1.1.5 -- öffentlicher Entfernungshinweis für nicht-öffentliche Beiträge (E-B) ──

    /**
     * Welle V1.1.5 (E-B). Begründung einer rechtlichen Entfernung für einen Aufrufer, dessen
     * Status die Sichtbarkeitsstufe des entfernten Beitrags zulässt -- das Äquivalent der
     * öffentlichen 451-Hinweisseite (`GET /s/{id}`) für `MEMBERS_ONLY`/`MEMBERS_AND_EXTERNAL`-Posts,
     * die nie eine öffentliche URL hatten. **Kein Rollen-Gate** -- jeder authentifizierte
     * Aufrufer, auch FRIEND/GUEST, im Rahmen seiner Sichtbarkeitsstufe. Wirft `NotFoundException`
     * für ALLES andere (unbekannte UUID, Beitrag außerhalb der Stufe des Aufrufers, Beitrag nicht
     * `REMOVED_LEGAL`) -- ununterscheidbar von einer unbekannten UUID, kein Existenz-Orakel.
     *
     * Bewusst eine EIGENE Methode statt einer Aufweichung von [getPost]: [getPost] bleibt für
     * `REMOVED_LEGAL` unverändert streng, sein Rückgabetyp ist [SocialPostDto], und ein
     * "Post-DTO mit leerem Inhalt" wäre genau die Art Halbwahrheit, die später jemand als echten
     * Post rendert. Der Client ruft diese Methode als expliziten Fallback, NACHDEM
     * [getPost]/[getThread] `NotFoundException` geliefert haben.
     */
    suspend fun getRemovalNotice(postId: String): SocialPostRemovalNoticeDto
}
