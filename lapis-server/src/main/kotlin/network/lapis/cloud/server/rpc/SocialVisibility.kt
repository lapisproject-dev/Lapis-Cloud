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
     * Für einen authentifizierten Aufrufer mit gegebenem [status]. `state` wird NUR insofern
     * gefiltert, als [SocialPostState.REMOVED_LEGAL] hier IMMER ausgeschlossen wird (Review-Fund
     * S1, 2026-08-18): ein aus rechtlichen Gründen entfernter Post muss auch über einen direkten
     * Link gesperrt bleiben, sonst unterläuft der Link die Entfernung (DSA Art. 16/6). Diese Welle
     * (V1.1.1) schreibt `REMOVED_LEGAL` nirgends (kein Codepfad existiert vor Welle V1.1.5) --
     * dieser Ausschluss wird trotzdem JETZT gesetzt, bevor irgendein anderer Aufrufer diese
     * gemeinsame Bedingung wiederverwendet, siehe Implementierungsplan § 7.2 und diese Klasse KDoc.
     * **Keine BOARD/ADMIN-Sonderrolle in dieser Welle** -- bis Welle V1.1.5 einen echten
     * Moderations-/Entfernungspfad einführt, ist `REMOVED_LEGAL` für JEDEN Aufrufer, auch BOARD/
     * ADMIN, identisch zu "existiert nicht" (einfachste, sichere Umsetzung; siehe Implementierungsplan).
     *
     * `HIDDEN_BY_AUTHOR` wird dagegen weiterhin NICHT gefiltert -- dieser Post ist über direkten
     * ID-Zugriff ([SocialNetworkService.getPost]) weiterhin erreichbar (Konzept: "Direkter Zugriff
     * bleibt möglich"); Aufrufer, die NUR sichtbare Posts wollen (z. B. [SocialNetworkService
     * .listTimeline]), kombinieren dieses Ergebnis selbst mit einer eigenen, engeren
     * `state`-Bedingung (die `REMOVED_LEGAL`-Exklusion hier ist für sie redundant, aber harmlos).
     */
    fun readableByCondition(status: MemberStatus): Op<Boolean> {
        val visibilityCondition =
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
        return visibilityCondition and (SocialPostTable.state neq SocialPostState.REMOVED_LEGAL)
    }

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
        val allowed =
            when {
                status in MemberStatusSets.ORGANIZATION_MEMBER ->
                    setOf(SocialPostVisibility.PUBLIC, SocialPostVisibility.MEMBERS_ONLY, SocialPostVisibility.MEMBERS_AND_EXTERNAL)
                status in MemberStatusSets.NON_MEMBER -> setOf(SocialPostVisibility.PUBLIC, SocialPostVisibility.MEMBERS_AND_EXTERNAL)
                else -> setOf(SocialPostVisibility.PUBLIC)
            }
        return visibility in allowed
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
}
