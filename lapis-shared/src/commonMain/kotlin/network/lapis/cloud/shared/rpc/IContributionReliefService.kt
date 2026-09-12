package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ContributionExemptionStateDto
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefRequestInput
import network.lapis.cloud.shared.domain.ContributionReliefStatus

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" (Stundung / Befreiung / Sozialermäßigung). See
 * `network.lapis.cloud.server.rpc.ContributionReliefService` KDoc for the full state machine,
 * README.adoc's "Contribution relief" section for the fachlich rationale (there is no dedicated
 * docs/architecture/contribution-relief.adoc -- this domain's rationale lives in the README), and
 * this interface's KDoc "Art. 9 DSGVO" note for the special-category-data handling of
 * [ContributionReliefRequestDto.reasonText].
 */
@RpcService
interface IContributionReliefService {
    /**
     * Selbstbedienung. MEMBER nur für sich selbst (IDOR-Gate); BOARD/ADMIN auch im Namen eines
     * fremden Mitglieds. Ein zweiter offener Antrag derselben Art scheitert mit
     * [ConflictException] (DB-seitig über `uq_crr_active_request` garantiert, nicht nur
     * anwendungsseitig geprüft).
     */
    suspend fun requestRelief(
        subjectMemberId: String,
        input: ContributionReliefRequestInput,
    ): ContributionReliefRequestDto

    /** Jeder authentifizierte Aufrufer, ausschließlich eigene Anträge (als Subjekt ODER Antragsteller). */
    suspend fun listMyReliefRequests(): List<ContributionReliefRequestDto>

    /**
     * Antragsteller ODER Subjekt, KEIN BOARD/ADMIN-Bypass. Nur aus REQUESTED -> WITHDRAWN. Der Weg,
     * einen bereits genehmigten (APPROVED), aber dauerhaft nicht ausführbaren Antrag zu beenden, ist
     * [decideReliefRequest] mit `approve=false` (BOARD/ADMIN, Vier-Augen-Prinzip) -> REJECTED, nicht
     * dieser hier -- ein Rückzug durch das Mitglied darf nicht vorspiegeln, dass es sich anders
     * überlegt hätte, wenn tatsächlich der Vorstand entschieden hat.
     */
    suspend fun withdrawReliefRequest(requestId: String): ContributionReliefRequestDto

    /**
     * Role: BOARD/ADMIN -- NICHT TREASURER. Spiegelt das Gate von
     * `IContributionService.markContributionWaived`. [reviewDueOnly] = `true` liefert nur Anträge,
     * deren `reviewDueOn` tatsächlich erreicht ist (`<= heute`), NICHT bloß gesetzt ist.
     *
     * Bounded to 200 matching rows per page (`requestedAt` ASC, `id` as tiebreaker) -- DoS guard,
     * same class of cap `IAuctionService.listAuctions`'s own KDoc documents. Deliberately
     * oldest-first rather than the more common newest-first: this is a decision/review queue, and
     * a page boundary must only ever defer the newest, still-fresh tail -- never hide the
     * longest-waiting requests a board queue exists to surface.
     *
     * **Echte Keyset-Pagination** (Review-Fix: eine erste Fassung hatte den 200er-Deckel OHNE jede
     * Seitennavigation, sodass jede über die Grenze hinaus wachsende Teilmenge -- z. B. alle
     * `EXECUTED`-Anträge, die nie wieder kleiner wird -- ihre neuesten Zeilen dauerhaft unerreichbar
     * gemacht hätte), analog [network.lapis.cloud.shared.rpc.ISocialNetworkService.listReports]'s
     * `beforeReportedAt`/`beforeId`-Komposit-Cursor, hier nur in `after`-statt-`before`-Richtung
     * passend zur ASC-Sortierung: [afterRequestedAt]/[afterId] zusammen filtern auf Zeilen ECHT NACH
     * der zuletzt gesehenen (`requestedAt` größer, oder gleich UND `id` größer -- derselbe
     * Tiebreaker-Grund wie bei `listReports`: `requestedAt` allein kann bei zwei Anträgen in
     * derselben Minute kollidieren, eine `UUID.random()`-`id` allein trägt keine Sortierreihenfolge).
     * Beide `null` == erste Seite; nur eines von beiden gesetzt wird wie "kein Cursor" behandelt
     * (kein Fehler, einfach keine Filterung). Aufrufer bilden den nächsten Cursor aus
     * `requestedAt`/`id` der LETZTEN Zeile dieser Antwort und wiederholen den Aufruf, bis eine
     * Antwort mit weniger als 200 Zeilen zurückkommt (letzte Seite erreicht).
     */
    suspend fun listReliefRequests(
        status: ContributionReliefStatus? = null,
        kind: ContributionReliefKind? = null,
        reviewDueOnly: Boolean = false,
        afterRequestedAt: LocalDateTime? = null,
        afterId: String? = null,
    ): List<ContributionReliefRequestDto>

    /**
     * Role: BOARD/ADMIN. Bei `approve=true` ist [note] PFLICHT und nur aus REQUESTED erlaubt.
     * Entscheidung UND Ausführung in derselben Transaktion (ein Klick) -- REQUESTED -> EXECUTED.
     * Scheitert die Ausführung am Zustands-Recheck unter Sperre, bleibt der Antrag APPROVED mit
     * gesetztem `executionError`, retryable via [retryReliefExecution]. Bei `approve=false` ist auch
     * APPROVED ein gültiger Ausgangszustand (nicht nur REQUESTED) -> REJECTED: der einzige Weg, einen
     * dauerhaft nicht ausführbaren APPROVED-Antrag (`executionError` bleibt bei jedem Retry gleich)
     * in einen Endzustand zu bringen und `active_request_key` freizugeben, sodass das Mitglied einen
     * neuen Antrag derselben Art stellen kann. Vier-Augen-Prinzip: `decidedBy == subjectMemberId` ->
     * [ForbiddenException], ohne Ausnahme, in beiden Fällen.
     */
    suspend fun decideReliefRequest(
        requestId: String,
        approve: Boolean,
        note: String? = null,
    ): ContributionReliefRequestDto

    /** Role: BOARD/ADMIN. Nur aus APPROVED. Identischer Ausführungspfad wie [decideReliefRequest]. */
    suspend fun retryReliefExecution(requestId: String): ContributionReliefRequestDto

    /** Self-or-BOARD/ADMIN/TREASURER, gleiches Gate wie `IContributionService.getMemberContributionSummary`. */
    suspend fun getExemptionState(memberId: String): ContributionExemptionStateDto
}
