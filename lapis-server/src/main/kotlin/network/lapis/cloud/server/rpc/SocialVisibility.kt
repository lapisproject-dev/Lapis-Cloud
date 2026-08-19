package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.SocialPostTable
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SocialPostState
import network.lapis.cloud.shared.domain.SocialPostVisibility
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import kotlin.uuid.Uuid

/**
 * The SINGLE place a "may this caller read this [network.lapis.cloud.server.db.generated
 * .SocialPostTable] row" question is answered -- shared by the authenticated RPC read path
 * ([SocialNetworkService]) AND, since Welle V1.1.3, the unauthenticated public HTTP read path
 * (`network.lapis.cloud.server.routes.SocialPublicRoutes`). Deliberately extracted into its own
 * file already in Welle V1.1.1, rather than left inline in [SocialNetworkService] -- Welle V1.1.3
 * needed [publicReadableCondition] verbatim, and Testgruppe T4 (`SocialPublicRoutesTest`) compares
 * the public route's rendered ID set against this same condition on a shared fixture; having only
 * ONE function to call from both places is the whole point (see the implementation plan's X2/X14
 * findings for why this matters more than usual: this domain is the first in this codebase with an
 * unauthenticated read path at all).
 */
object SocialVisibility {
    /** Ohne Login: ausschließlich [SocialPostVisibility.PUBLIC] + [SocialPostState.VISIBLE]. */
    fun publicReadableCondition(): Op<Boolean> =
        (SocialPostTable.visibility eq SocialPostVisibility.PUBLIC) and
            (SocialPostTable.state eq SocialPostState.VISIBLE)

    /**
     * Addendum V1.1.5 § 1.2, Extraktion 1: der reine Sichtbarkeitsstufen-Teil von
     * [readableByCondition], OHNE dessen `state`-Klausel. **Reine Umstellung** -- [readableByCondition]
     * liefert danach byte-gleiche Semantik, verifiziert von einem eigenen Extraktions-Guard-Test
     * (`SocialNetworkServiceTest`/`SocialVisibilityTest`, "Test 49" im Addendum). Existiert, damit
     * [publicRemovalNoticeCondition]/[removalNoticeReadableCondition] (E-B) dieselbe Stufenregel
     * nicht ein zweites Mal kodieren müssen (§ 9 Stolperfalle 6: keine zweite Wahrheitsquelle für
     * dieselbe Regel).
     */
    fun visibilityTierCondition(status: MemberStatus): Op<Boolean> =
        when {
            status in MemberStatusSets.ORGANIZATION_MEMBER ->
                SocialPostTable.visibility inList
                    listOf(
                        SocialPostVisibility.PUBLIC,
                        SocialPostVisibility.MEMBERS_ONLY,
                        SocialPostVisibility.MEMBERS_AND_EXTERNAL,
                    )
            status in MemberStatusSets.NON_MEMBER ->
                SocialPostTable.visibility inList listOf(SocialPostVisibility.PUBLIC, SocialPostVisibility.MEMBERS_AND_EXTERNAL)
            // APPLICATION/WITHDRAWN/REJECTED -- can log in (APPLICATION) or once could
            // (WITHDRAWN/REJECTED, see AuthRoutes' own LOGIN_BLOCKED gate for why the latter two
            // never actually reach here in practice), but are not yet/no longer either an
            // organization member or a recognized non-member guest -- treated identically to an
            // unauthenticated reader.
            else -> (SocialPostTable.visibility eq SocialPostVisibility.PUBLIC)
        }

    /**
     * Für einen authentifizierten Aufrufer mit gegebenem [status]. `state` wird NUR insofern
     * gefiltert, als [SocialPostState.REMOVED_LEGAL] hier IMMER ausgeschlossen wird (Review-Fund
     * S1, 2026-08-18): ein aus rechtlichen Gründen entfernter Post muss auch über einen direkten
     * Link gesperrt bleiben, sonst unterläuft der Link die Entfernung (DSA Art. 16/6). **Keine
     * BOARD/ADMIN-Sonderrolle** -- `REMOVED_LEGAL` ist über DIESE Funktion für JEDEN Aufrufer,
     * auch BOARD/ADMIN, identisch zu "existiert nicht"; die drei Ausnahmen, die Welle V1.1.5
     * tatsächlich braucht (BOARD-Moderationsansicht, Autor-Eigenansicht, Entfernungshinweis E-B),
     * laufen über eigene, benannte Funktionen daneben ([moderationReadableCondition],
     * `ownAuthorViewCondition`, [removalNoticeReadableCondition]/[publicRemovalNoticeCondition]) --
     * diese Funktion selbst bleibt bewusst UNVERÄNDERT (Addendum § 4, Plan § 5.5).
     *
     * `HIDDEN_BY_AUTHOR` wird dagegen weiterhin NICHT gefiltert -- dieser Post ist über direkten
     * ID-Zugriff ([SocialNetworkService.getPost]) weiterhin erreichbar (Konzept: "Direkter Zugriff
     * bleibt möglich"); Aufrufer, die NUR sichtbare Posts wollen (z. B. [SocialNetworkService
     * .listTimeline]), kombinieren dieses Ergebnis selbst mit einer eigenen, engeren
     * `state`-Bedingung (die `REMOVED_LEGAL`-Exklusion hier ist für sie redundant, aber harmlos).
     */
    fun readableByCondition(status: MemberStatus): Op<Boolean> =
        visibilityTierCondition(status = status) and (SocialPostTable.state neq SocialPostState.REMOVED_LEGAL)

    /**
     * NEU Welle V1.1.2 (X4, Implementierungsplan Stolperfalle 9): pure-Kotlin Zwilling von
     * [readableByCondition] für bereits IN-MEMORY geladene Zeilen (Exposed's `Op<Boolean>` kann nur
     * in einer `WHERE`-Klausel ausgewertet werden, nicht gegen einen schon geladenen [ResultRow]).
     * Gebraucht von [SocialNetworkService.getThread]/[SocialNetworkService.createComment]/
     * [SocialNetworkService.boostPost] als Defense-in-Depth-Prüfung EINZELNER Knoten eines bereits
     * geladenen Teilbaums, gegen eine (durch fehlerhafte Daten theoretisch mögliche) eingeschleuste
     * Zeile mit abweichender Sichtbarkeit -- die eigentliche Sichtbarkeits-VERERBUNG (S5) sorgt
     * dafür, dass in einem korrekten Datenbestand jeder Knoten ohnehin dieselbe Sichtbarkeitsstufe
     * wie sein Wurzel-Post trägt. **Muss inhaltlich synchron zu [readableByCondition] bleiben** --
     * beide Funktionen kodieren dieselbe Regel zweimal (einmal als SQL-`WHERE`, einmal als reine
     * Kotlin-Prädikatfunktion), weil Exposed keinen Weg bietet, eine `Op<Boolean>` gegen einen
     * bereits materialisierten Wert auszuwerten, ohne eine zweite (nutzlose) Datenbank-Rundreise.
     */
    fun isReadable(
        visibility: SocialPostVisibility,
        state: SocialPostState,
        status: MemberStatus,
    ): Boolean {
        if (state == SocialPostState.REMOVED_LEGAL) return false
        return visibility in allowedVisibilities(status = status)
    }

    /**
     * Addendum V1.1.5 § 1.2, Extraktion 2: das Kotlin-Pendant von [visibilityTierCondition], aus
     * [isReadable] herausgezogen -- reine Umstellung, [isReadable] bleibt byte-gleich. `private`,
     * weil bisher nur [isReadable]/[isRemovalNoticeReadable] es brauchen; bei Bedarf jederzeit
     * anhebbar.
     */
    private fun allowedVisibilities(status: MemberStatus): Set<SocialPostVisibility> =
        when {
            status in MemberStatusSets.ORGANIZATION_MEMBER ->
                setOf(SocialPostVisibility.PUBLIC, SocialPostVisibility.MEMBERS_ONLY, SocialPostVisibility.MEMBERS_AND_EXTERNAL)
            status in MemberStatusSets.NON_MEMBER -> setOf(SocialPostVisibility.PUBLIC, SocialPostVisibility.MEMBERS_AND_EXTERNAL)
            else -> setOf(SocialPostVisibility.PUBLIC)
        }

    /**
     * NEU Welle V1.1.3 (X4, Implementierungsplan Stolperfalle 1). Pure-Kotlin Zwilling von
     * [publicReadableCondition] für bereits IN-MEMORY geladene Zeilen -- exakt dasselbe Verhältnis
     * wie [isReadable] zu [readableByCondition]. Gebraucht von
     * `network.lapis.cloud.server.rpc.SocialReadPipeline.thread`'s `nodeReadable`-Callback im
     * öffentlichen Pfad als Defense-in-Depth-Prüfung EINZELNER Knoten eines bereits geladenen
     * Teilbaums, gegen eine (durch fehlerhafte Daten theoretisch mögliche) eingeschleuste Zeile mit
     * abweichender Sichtbarkeit unter einer öffentlichen Wurzel (Testgruppe T12). **Muss inhaltlich
     * synchron zu [publicReadableCondition] bleiben** -- beide Funktionen kodieren dieselbe Regel
     * zweimal (einmal als SQL-`WHERE`, einmal als reine Kotlin-Prädikatfunktion), aus demselben
     * Grund, den [isReadable]s KDoc für sein eigenes SQL/Kotlin-Paar nennt.
     */
    fun isPublicReadable(
        visibility: SocialPostVisibility,
        state: SocialPostState,
    ): Boolean = visibility == SocialPostVisibility.PUBLIC && state == SocialPostState.VISIBLE

    /**
     * Welle V1.1.5 (E-B), öffentlicher Pfad. Die EINZIGE Bedingung, unter der der öffentliche Pfad
     * über die bloße Existenz eines nicht mehr auslieferbaren Beitrags Auskunft gibt. Bewusst NICHT
     * symmetrisch zu [publicReadableCondition]: dort `state eq VISIBLE`, hier `state eq
     * REMOVED_LEGAL` -- die beiden Bedingungen sind DISJUNKT, es gibt keine Zeile, die beide
     * erfüllt.
     *
     * `visibility eq PUBLIC` ist die Angel des Ganzen: ein `MEMBERS_ONLY`-Beitrag, der rechtlich
     * entfernt wird, fällt hier durch und bleibt auf dem öffentlichen Pfad ein 404 -- der
     * öffentliche Pfad darf niemals verraten, dass ein nicht-öffentlicher Beitrag jemals existiert
     * hat. Das ist unbedenklich, weil `visibility` write-once ist (§ 0 des Addendums, Fakt 1): eine
     * Zeile, die diese Bedingung heute erfüllt, war zu jedem Zeitpunkt ihres Lebens unter
     * `/s/{id}` mit vollem Inhalt abrufbar -- der Hinweis offenbart also strikt WENIGER, als
     * dieselbe URL vorher offenbart hat.
     *
     * `HIDDEN_BY_AUTHOR` ist hier bewusst NICHT enthalten: ein Autor, der seinen Beitrag versteckt,
     * trifft eine private Entscheidung, über die der öffentliche Pfad kein Wort verlieren darf.
     */
    fun publicRemovalNoticeCondition(): Op<Boolean> =
        (SocialPostTable.visibility eq SocialPostVisibility.PUBLIC) and
            (SocialPostTable.state eq SocialPostState.REMOVED_LEGAL)

    /**
     * Welle V1.1.5 (E-B), authentifizierter Pfad -- das Äquivalent von
     * [publicRemovalNoticeCondition] für [ISocialNetworkService.getRemovalNotice]: liefert genau
     * die `REMOVED_LEGAL`-Zeilen, deren Sichtbarkeitsstufe [status] zulässt (nicht nur `PUBLIC`).
     * Disjunkt zu [readableByCondition] aus demselben Grund wie [publicRemovalNoticeCondition] zu
     * [publicReadableCondition].
     */
    fun removalNoticeReadableCondition(status: MemberStatus): Op<Boolean> =
        visibilityTierCondition(status = status) and (SocialPostTable.state eq SocialPostState.REMOVED_LEGAL)

    /** Pure-Kotlin Zwilling von [removalNoticeReadableCondition] -- selbe Beziehung wie [isReadable] zu [readableByCondition]. */
    fun isRemovalNoticeReadable(
        visibility: SocialPostVisibility,
        state: SocialPostState,
        status: MemberStatus,
    ): Boolean = state == SocialPostState.REMOVED_LEGAL && visibility in allowedVisibilities(status = status)

    /**
     * NUR für den Moderations-Lesepfad ([SocialNetworkService.listReports]/
     * [SocialNetworkService.listContentErasures]). Umgeht bewusst JEDE Sichtbarkeits- und
     * State-Filterung -- ein Vorstand muss auch eine Meldung/einen Löschantrag zu einem bereits
     * `REMOVED_LEGAL`-Post oder einem `MEMBERS_ONLY`-Post im Kontext sehen können, unabhängig
     * davon, ob er selbst nach [isReadable] Zugriff hätte. Die Funktion existiert, damit dieser
     * Verzicht benannt und auffindbar ist statt implizit -- es entsteht dadurch KEIN BOARD-weiter
     * "alles sehen"-Endpunkt: `listReports`/`listContentErasures` liefern ausschließlich Posts, zu
     * denen bereits ein Report-/Erasure-Datensatz existiert; `getPost`/`getThread`/`listTimeline`
     * bleiben für BOARD/ADMIN unverändert streng.
     */
    fun moderationReadableCondition(): Op<Boolean> = Op.TRUE

    /**
     * DSA Art. 17 (E-C) -- die Eigenansicht des Autors in `SocialNetworkService.listTimeline`s
     * `selfHiddenView`-Zweig. Der Autor darf seinen EIGENEN Post immer sehen, unabhängig von seinem
     * heutigen Mitgliedsstatus -- dieselbe Begründung, aus der `hideOwnPost` bewusst gar kein
     * Membership-Gate hat. Bewusst OHNE Sichtbarkeitsstufen-Filter (der Autor darf jede seiner
     * eigenen Stufen sehen) -- der `state`-Filter (`VISIBLE`/`HIDDEN_BY_AUTHOR`/`REMOVED_LEGAL`
     * für die Eigenansicht) bleibt Sache des Aufrufers, siehe `listTimeline`.
     */
    fun ownAuthorViewCondition(authorMemberId: Uuid): Op<Boolean> = SocialPostTable.authorMemberId eq authorMemberId
}
