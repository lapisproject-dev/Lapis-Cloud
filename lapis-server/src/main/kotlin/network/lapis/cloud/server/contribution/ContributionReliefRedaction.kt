package network.lapis.cloud.server.contribution

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.generated.ContributionReliefRequestTable
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.ContributionReliefStatusSets
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Welle V1.4.10 "Beitragsvergünstigungen" -- Art. 9 DSGVO minimization: [ContributionReliefRequestTable
 * .reasonText] may carry special-category (health) data (see `ContributionReliefService` KDoc
 * "Art. 9 DSGVO") and is therefore not kept forever the way `decisionNote`/`kind`/`status`/every
 * date and amount field is (Art. 5(2) DSGVO Rechenschaftspflicht + §147 AO). [redactDueReasonTexts]
 * nulls it once its request has been in a terminal state for [REDACTION_AFTER_MONTHS] -- rein
 * datengetrieben, keine Zeitgeber-Abhängigkeit, direkt aus Tests aufrufbar (siehe
 * `ContributionReliefRedactionPoller`, das diese Funktion lediglich periodisch aufruft).
 */
object ContributionReliefRedaction {
    const val REDACTION_AFTER_MONTHS = 12

    /**
     * DoS-Deckel (Security-Prüfliste "Pagination-Caps"), gleiche Posture wie
     * `PublicRankingConsentPersonalData.MAX_EXPORTED_EVENTS`: bounds how many `reason_text IS NOT
     * NULL` candidates a single poll tick materializes/updates, instead of an unbounded query whose
     * memory footprint and open-transaction duration would scale linearly with the table's size. Any
     * candidate beyond the cap is simply picked up by the next daily tick
     * ([network.lapis.cloud.server.contribution.ContributionReliefRedactionPoller]) -- a redaction
     * deadline measured in months tolerates a run or two of delay for the excess tail.
     */
    private const val MAX_CANDIDATES_PER_RUN = 5_000

    /** @return the number of rows whose `reason_text` this call actually nulled. */
    fun redactDueReasonTexts(now: LocalDateTime): Int =
        transaction {
            val candidates =
                ContributionReliefRequestTable
                    .selectAll()
                    .where {
                        // Review fix: an EARLIER version of this query had neither predicate below,
                        // just `reasonText.isNotNull()` -- a still-active REQUESTED/APPROVED row
                        // (which `redactionAnchor` permanently returns `null` for, see its KDoc)
                        // would occupy a candidate slot on EVERY tick forever without ever being
                        // redacted. Once an instance accumulates >= MAX_CANDIDATES_PER_RUN such
                        // never-redactable rows, they alone fill the cap and a genuinely due row
                        // further down the (previously undefined) scan order would never be reached
                        // -- the whole redaction mechanism silently stalls. Restricting the SQL
                        // itself to TERMINAL statuses closes that gap completely: every TERMINAL row
                        // has a guaranteed non-null anchor (REJECTED/WITHDRAWN -> `decidedAt` with a
                        // `requestedAt` fallback; EXECUTED -> a kind-specific date with an
                        // `executedAt` fallback for the two cases -- unbounded EXEMPTION,
                        // reviewDueOn-less REDUCTION -- that would otherwise have none; see
                        // `redactionAnchor`'s KDoc and the `check` inside it, which fails loudly
                        // instead of silently if a future status/kind combination were ever added
                        // without also giving it an anchor). So no TERMINAL row can stay a permanent
                        // candidate -- every one of them is redacted, at the latest, exactly
                        // REDACTION_AFTER_MONTHS after its anchor date. The deterministic `orderBy`
                        // below still matters for a DIFFERENT reason: it guarantees PROGRESS within
                        // one bounded run even while candidates are merely NOT YET due -- those are
                        // skipped in Kotlin below and revisited on a later tick, oldest-`requestedAt`
                        // -first rather than in an arbitrary order.
                        ContributionReliefRequestTable.reasonText.isNotNull() and
                            (ContributionReliefRequestTable.status inList ContributionReliefStatusSets.TERMINAL)
                    }.orderBy(ContributionReliefRequestTable.requestedAt)
                    .limit(MAX_CANDIDATES_PER_RUN)
                    .toList()
            var redacted = 0
            candidates.forEach { row ->
                val anchor = redactionAnchor(row) ?: return@forEach
                if (anchor.plus(REDACTION_AFTER_MONTHS, DateTimeUnit.MONTH) > now.date) return@forEach
                redacted +=
                    ContributionReliefRequestTable.update({
                        (ContributionReliefRequestTable.id eq row[ContributionReliefRequestTable.id]) and
                            ContributionReliefRequestTable.reasonText.isNotNull()
                    }) {
                        it[reasonText] = null
                        it[reasonRedactedAt] = now
                    }
            }
            redacted
        }

    /**
     * Fälligkeitsanker je Zustand -- `null` bedeutet "nie redigieren" (nur noch für einen aktiven
     * Antrag, siehe `BLOCKS_NEW_REQUEST`-Zweig unten): REQUESTED/APPROVED (noch aktiv);
     * REJECTED/WITHDRAWN -> `decided_at` (beide Übergänge setzen es, siehe
     * `ContributionReliefService.withdrawReliefRequest`/`.decideReliefRequest`), Fallback
     * `requested_at`; EXECUTED+DEFERRAL -> `deferral_new_due_date`; EXECUTED+EXEMPTION ->
     * `exemption_until`, bei `null` (unbefristet) Fallback `executed_at` (Review fix: eine
     * unbefristete Befreiung endet nie von selbst -- siehe `ContributionReliefExecution` KDoc "keine
     * Beendigung", CHANGELOG "bewusste Grenze" -- ein permanentes `null` hier würde den Art.-9-
     * Freitext auf unabsehbare Zeit speichern UND, da diese Zeilen `reason_text IS NOT NULL` +
     * TERMINAL bleiben, dauerhaft Kandidaten-Plätze in [redactDueReasonTexts] belegen, ohne je
     * Fortschritt zu machen); EXECUTED+REDUCTION -> `review_due_on`, bei `null` `executed_at`.
     */
    private fun redactionAnchor(row: ResultRow): LocalDate? {
        val status = row[ContributionReliefRequestTable.status]
        // Still an active request (ContributionReliefStatusSets.BLOCKS_NEW_REQUEST -- REQUESTED/
        // APPROVED) -- never redact. Using the shared set here, instead of repeating the two
        // literals, keeps this call site automatically correct if that set ever changes; the
        // `check` below then makes any THIRD, newly-added status that is neither active nor
        // terminal fail loudly here rather than silently falling through a `when` branch.
        if (status in ContributionReliefStatusSets.BLOCKS_NEW_REQUEST) return null
        check(status in ContributionReliefStatusSets.TERMINAL) {
            "ContributionReliefStatus.$status is neither BLOCKS_NEW_REQUEST nor TERMINAL -- " +
                "redactionAnchor was not updated for a new status literal"
        }
        return when (status) {
            ContributionReliefStatus.REJECTED, ContributionReliefStatus.WITHDRAWN ->
                row[ContributionReliefRequestTable.decidedAt]?.date ?: row[ContributionReliefRequestTable.requestedAt].date
            ContributionReliefStatus.EXECUTED ->
                when (row[ContributionReliefRequestTable.kind]) {
                    ContributionReliefKind.DEFERRAL -> row[ContributionReliefRequestTable.deferralNewDueDate]
                    ContributionReliefKind.EXEMPTION ->
                        row[ContributionReliefRequestTable.exemptionUntil] ?: row[ContributionReliefRequestTable.executedAt]?.date
                    ContributionReliefKind.REDUCTION ->
                        row[ContributionReliefRequestTable.reviewDueOn] ?: row[ContributionReliefRequestTable.executedAt]?.date
                }
            ContributionReliefStatus.REQUESTED, ContributionReliefStatus.APPROVED ->
                error("unreachable -- guarded by the BLOCKS_NEW_REQUEST check above")
        }
    }
}
