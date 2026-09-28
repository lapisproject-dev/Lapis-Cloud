package network.lapis.cloud.server.events.series

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventSeriesEditScope
import network.lapis.cloud.shared.rpc.BadRequestException
import java.time.ZoneId

/**
 * Welle V1.4.37 "Wiederkehrende Veranstaltungen, Folgewelle (Rest)" -- the "Server-Scope-Engine"
 * piece [EventSeriesLimits] already forward-references: turns an [EventSeriesEditScope] choice into
 * a concrete plan of which materialized occurrences an edit affects, and -- for [EventSeriesEditScope
 * .FOLLOWING] on a non-first occurrence -- the two RRULE strings the series-split leaves behind.
 *
 * Pure fachlogik, same posture [RecurrenceRuleBuilder]/`EventPolicy` already establish: no DB
 * access, no transaction, unit-testable in isolation (`EventSeriesScopeEngineTest`). The actual
 * writes this plan drives (updating `event.series_detached`, inserting a second `event_series` row,
 * re-pointing `event.series_id` for the moved-forward occurrences, materializing/deleting `event`
 * rows via `EventSeriesMaterializer`) are still a later wave -- this object only ever computes
 * *what* should happen, never performs it, so it can be reviewed and tested independently of that
 * write-path wiring (same "Schritt" discipline `39-events.kuml.kts`'s own file header already
 * documents for this feature: Datenmodell, then Scope-Engine, then write-path, then client/iCal/i18n).
 */
internal object EventSeriesScopeEngine {
    /**
     * The two RRULE strings a [EventSeriesEditScope.FOLLOWING] split on a non-first occurrence
     * produces. [originalSeriesRrule] replaces the ORIGINAL series' `event_series.rrule` (truncated
     * so it stops just before [newSeriesDtstart]); [newSeriesRrule] becomes the NEW series' own
     * `rrule`, anchored at [newSeriesDtstart] (which becomes that new row's `event_series.dtstart`,
     * and -- unchanged by this object, decided by the caller's edit -- the first materialized
     * occurrence's `series_original_start`/`starts_at`).
     */
    data class SplitPlan(
        val originalSeriesRrule: String,
        val newSeriesDtstart: LocalDateTime,
        val newSeriesRrule: String,
    )

    /**
     * [affectedOriginalStarts] are the `event.series_original_start` values ([RecurrenceExpander]'s
     * own occurrence timestamps -- wall-clock in the series' [ZoneId], matching how that column is
     * stored) an edit under [affectedOriginalStarts]'s originating scope should apply to. [split] is
     * non-null only for [EventSeriesEditScope.FOLLOWING] on an occurrence that is not the series'
     * very first one.
     */
    data class ScopePlan(
        val affectedOriginalStarts: Set<LocalDateTime>,
        val split: SplitPlan?,
    )

    private val COUNT_PATTERN = Regex(";COUNT=\\d+")
    private val UNTIL_PATTERN = Regex(";UNTIL=[^;]+")

    /**
     * Expands [rrule]/[dtstart] (see [RecurrenceExpander.expand] for the DoS bounds this inherits)
     * and builds the [ScopePlan] for editing the occurrence at [targetOriginalStart] under [scope].
     * Throws [BadRequestException] if [targetOriginalStart] is not actually one of that expansion's
     * occurrences -- e.g. a stale client re-submitting a scope choice against an occurrence an
     * EXDATE (or a prior [EventSeriesEditScope.THIS] detach) already removed from the live set;
     * [exdates] should be passed as whatever `event_series_exdate`/already-detached original-start
     * values the caller already excludes elsewhere, so this rejects the same stale input the
     * materializer itself would reject.
     */
    fun plan(
        scope: EventSeriesEditScope,
        rrule: String,
        dtstart: LocalDateTime,
        zone: ZoneId,
        targetOriginalStart: LocalDateTime,
        exdates: Set<LocalDateTime> = emptySet(),
    ): ScopePlan {
        val occurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = zone, exdates = exdates)
        val targetIndex = occurrences.indexOf(targetOriginalStart)
        if (targetIndex < 0) {
            throw BadRequestException(
                "Der gewählte Termin gehört nicht (mehr) zu dieser Wiederholungsregel.",
            )
        }

        return when (scope) {
            EventSeriesEditScope.THIS ->
                ScopePlan(affectedOriginalStarts = setOf(targetOriginalStart), split = null)

            EventSeriesEditScope.ALL ->
                ScopePlan(affectedOriginalStarts = occurrences.toSet(), split = null)

            EventSeriesEditScope.FOLLOWING -> {
                val following = occurrences.drop(targetIndex).toSet()
                if (targetIndex == 0) {
                    // Nothing precedes the edited occurrence -- "following" IS "all", no split.
                    ScopePlan(affectedOriginalStarts = following, split = null)
                } else {
                    ScopePlan(
                        affectedOriginalStarts = following,
                        split =
                            buildSplitPlan(
                                rrule = rrule,
                                dtstart = dtstart,
                                zone = zone,
                                targetOriginalStart = targetOriginalStart,
                                exdates = exdates,
                            ),
                    )
                }
            }
        }
    }

    /**
     * The split index -- how many occurrences stay on the original series -- is derived from the
     * RAW (unfiltered by [exdates]) expansion of [rrule], never from an exdate-filtered occurrence
     * list. [RecurrenceExpander.expand]'s own KDoc is explicit about why: ical4j applies a rule's
     * `COUNT`/`UNTIL` bound against the raw occurrence sequence and only subtracts [exdates]
     * afterward ("checked BEFORE exdates are subtracted -- an EXDATE ... must not be able to 'buy
     * back' headroom against the limit"). A `COUNT=n` clause therefore always counts RAW
     * occurrences, so truncating/splitting it must use the raw index of [targetOriginalStart], not
     * its index in a list that has already had [exdates] (e.g. a prior [EventSeriesEditScope.THIS]
     * detach) removed -- using the filtered index would make the emitted `COUNT` off by however many
     * exdates precede [targetOriginalStart], silently dropping or fabricating occurrences on
     * whichever side of the split absorbs the discrepancy.
     *
     * Both resulting RRULE strings are cross-checked by actually re-expanding them (same defense-
     * in-depth posture [RecurrenceRuleBuilder.build] already establishes for its own output) -- a
     * truncated/shifted RRULE this function produces is guaranteed to actually materialize into the
     * occurrence count/start the split intends, not just "look right" as a string. A further check
     * reconstructs the exdate-filtered union of both split pieces and requires it to equal the
     * exdate-filtered occurrence set the original, unsplit rule would have produced -- catching any
     * lost or fabricated occurrence the plain size/first-element checks alone would miss.
     */
    private fun buildSplitPlan(
        rrule: String,
        dtstart: LocalDateTime,
        zone: ZoneId,
        targetOriginalStart: LocalDateTime,
        exdates: Set<LocalDateTime>,
    ): SplitPlan {
        val parsed = RecurrenceRuleBuilder.parseWhitelisted(rrule)

        val rawOccurrences = RecurrenceExpander.expand(rrule = rrule, dtstart = dtstart, zone = zone)
        val rawIndex = rawOccurrences.indexOf(targetOriginalStart)
        require(rawIndex >= 0) {
            "Target occurrence not found in the raw (unfiltered) expansion of the RRULE"
        }

        // The original series must ALWAYS stop after exactly [rawIndex] RAW occurrences, regardless
        // of whether it was originally COUNT- or UNTIL-bounded -- an UNTIL clause bounds an absolute
        // instant, not "how many occurrences", so leaving it unchanged would still let the original
        // series materialize occurrences on/after [targetOriginalStart] (those belong to the new,
        // split-off series now). Replacing whichever bound clause is present with a fresh COUNT is
        // therefore correct for both cases.
        val originalSeriesRrule =
            if (COUNT_PATTERN.containsMatchIn(rrule)) {
                COUNT_PATTERN.replace(rrule, ";COUNT=$rawIndex")
            } else {
                UNTIL_PATTERN.replace(rrule, ";COUNT=$rawIndex")
            }
        val originalRawCheck = RecurrenceExpander.expand(rrule = originalSeriesRrule, dtstart = dtstart, zone = zone)
        require(originalRawCheck.size == rawIndex) {
            "Splitting the original series' RRULE produced ${originalRawCheck.size} occurrences, " +
                "expected $rawIndex"
        }

        val parsedCount = parsed.count
        val newSeriesRrule =
            if (parsedCount != null) {
                val remaining = parsedCount - rawIndex
                COUNT_PATTERN.replace(rrule, ";COUNT=$remaining")
            } else {
                // UNTIL-bounded: same absolute cutoff, just re-anchored at the new DTSTART.
                rrule
            }
        val newRawCheck = RecurrenceExpander.expand(rrule = newSeriesRrule, dtstart = targetOriginalStart, zone = zone)
        require(newRawCheck.isNotEmpty() && newRawCheck.first() == targetOriginalStart) {
            "New series' RRULE does not start on the intended occurrence"
        }

        val originalKept = originalRawCheck.filterNot { it in exdates }
        val newMaterialized = newRawCheck.filterNot { it in exdates }
        val fullFiltered = rawOccurrences.filterNot { it in exdates }
        require(originalKept + newMaterialized == fullFiltered) {
            "Split plan does not reconstruct the original exdate-filtered occurrence set"
        }

        return SplitPlan(
            originalSeriesRrule = originalSeriesRrule,
            newSeriesDtstart = targetOriginalStart,
            newSeriesRrule = newSeriesRrule,
        )
    }
}
