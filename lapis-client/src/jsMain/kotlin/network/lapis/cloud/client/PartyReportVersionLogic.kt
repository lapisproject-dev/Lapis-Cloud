package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeadlineLevel
import network.lapis.cloud.shared.domain.PartgDiffCellDto
import network.lapis.cloud.shared.domain.PartgDiffMarker
import network.lapis.cloud.shared.domain.PartgDiffRowDto
import network.lapis.cloud.shared.domain.PartgDiffValueDto
import network.lapis.cloud.shared.domain.PartgDiffValueState
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgVersionDiffDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionRules
import network.lapis.cloud.shared.domain.PartgVersionStatus

/** How a status looks: a symbol AND a word (never colour alone, no red for a state that is no error). */
internal data class PartgStatusLook(
    val word: String,
    val color: String,
    val icon: String,
)

/** V1.9.291 (R6): bookmark = kept, shield = declared, closed lock = internally final; the draft has a pencil. Always with the word. */
internal fun partgVersionStatusLook(status: PartgVersionStatus): PartgStatusLook =
    when (status) {
        PartgVersionStatus.DRAFT -> PartgStatusLook(word = tr("Arbeitsstand"), color = "secondary", icon = "fas fa-pen")
        PartgVersionStatus.FROZEN -> PartgStatusLook(word = tr("Festgehalten"), color = "info", icon = "fas fa-bookmark")
        PartgVersionStatus.DECLARED -> PartgStatusLook(word = tr("Versichert"), color = "primary", icon = "fas fa-user-shield")
        PartgVersionStatus.FINAL -> PartgStatusLook(word = tr("Intern final"), color = "success", icon = "fas fa-lock")
    }

/** The deadline notice: a symbol and a word as well. `NONE` shows nothing loud. */
internal fun partgDeadlineLook(level: PartgDeadlineLevel): PartgStatusLook? =
    when (level) {
        PartgDeadlineLevel.NONE -> null
        PartgDeadlineLevel.INFO -> PartgStatusLook(word = tr("Frist in Sicht"), color = "info", icon = "fas fa-circle-info")
        PartgDeadlineLevel.WARNING -> PartgStatusLook(word = tr("Frist bald"), color = "warning", icon = "fas fa-triangle-exclamation")
        PartgDeadlineLevel.DANGER -> PartgStatusLook(word = tr("Frist überschritten"), color = "danger", icon = "fas fa-hourglass-end")
    }

/** `Gesamtpartei` or the name of the unit. */
internal fun partgVersionScopeText(
    scopeKey: String,
    units: List<PartgUnitDto>,
): String = if (scopeKey == PartgReportScope.ALL_KEY) tr("Gesamtpartei") else partgUnitLabelOf(units, scopeKey)

/** The sentence about the deadline: the date, whether it is only a proposal, and the days left or overdue. */
internal fun partgDeadlineText(d: PartgDeadlineDto): String {
    val date = partgGermanDate(d.deadline)
    val base = if (d.confirmed) gettext("Frist: %1", date) else gettext("Frist: %1 (Vorschlag, nicht bestätigt)", date)
    return when {
        d.daysUntil < 0 -> gettext("%1, seit %2 Tagen überschritten", base, (-d.daysUntil).toString())
        d.daysUntil == 0 -> gettext("%1, heute", base)
        d.daysUntil == 1 -> gettext("%1, noch ein Tag", base)
        else -> gettext("%1, noch %2 Tage", base, d.daysUntil.toString())
    }
}

/** The warning about the working state; `null` when there is nothing to say. */
internal fun partgDeviationText(deviation: PartgDraftDeviation): String? =
    when (deviation) {
        PartgDraftDeviation.SAME -> null
        PartgDraftDeviation.NOT_CHECKED -> null
        PartgDraftDeviation.DIFFERS ->
            tr(
                "Der Arbeitsstand weicht von der letzten festgehaltenen Version ab. Halten Sie ihn fest, wenn er in den Bericht eingehen soll.",
            )
        PartgDraftDeviation.UNKNOWN_FORMAT -> tr("Vergleich nicht möglich (älteres Format).")
    }

/** What the footer of the export says about this version (the server writes the real line; this is the same shape for the card). */
internal fun partgFooterPreview(version: PartgVersionDto): String {
    val kept = partgGermanDate(toOrganizationZone(version.frozenAt).date)
    val status =
        if (version.status ==
            PartgVersionStatus.FINAL
        ) {
            tr("Intern final, nicht eingereicht")
        } else {
            tr("Entwurf als Arbeitshilfe, nicht eingereicht")
        }
    return gettext(
        "Version %1 · festgehalten %2 · Prüfsumme %3 · %4",
        version.versionNumber.toString(),
        kept,
        version.contentHash.take(12),
        status,
    )
}

/** The typed confirmation of the finalisation: the number of the version, as digits. */
internal fun partgTypedNumberMatches(
    typed: String,
    version: PartgVersionDto,
): Boolean =
    typed.trim().toIntOrNull()?.let { PartgVersionRules.matchesTypedNumber(typed = it, versionNumber = version.versionNumber) } == true

/** Plain word for a cell of the comparison; an unknown value is never 0. */
internal fun partgDiffValueText(value: PartgDiffValueDto): String =
    when (value.state) {
        PartgDiffValueState.VALUE -> moneyFromCents(checkNotNull(value.cents))
        PartgDiffValueState.PARTIAL -> gettext("%1 (unvollst.)", moneyFromCents(checkNotNull(value.cents)))
        PartgDiffValueState.NOT_ENTERED -> tr("n. e.")
        PartgDiffValueState.NOT_COMPUTABLE -> tr("n. b.")
        PartgDiffValueState.ABSENT -> "–"
    }

/** The signed difference, or "n. b." when one side is not a plain value. */
internal fun partgDiffDeltaText(cell: PartgDiffCellDto): String {
    val delta = cell.deltaCents ?: return tr("n. b.")
    return (if (delta > 0) "+" else "") + moneyFromCents(delta)
}

internal fun partgDiffMarkerWord(marker: PartgDiffMarker): String =
    when (marker) {
        PartgDiffMarker.SAME -> tr("unverändert")
        PartgDiffMarker.CHANGED -> tr("geändert")
        PartgDiffMarker.ADDED -> tr("neu")
        PartgDiffMarker.REMOVED -> tr("entfallen")
    }

/** Only the rows with a difference, or all of them. */
internal fun partgVisibleDiffRows(
    diff: PartgVersionDiffDto,
    showAll: Boolean,
): List<PartgDiffRowDto> = if (showAll) diff.rows else diff.rows.filter { it.marker != PartgDiffMarker.SAME }

/** `true` iff no row differs at all (the comparison then says so in words instead of showing an empty table). */
internal fun partgNoDifferences(diff: PartgVersionDiffDto): Boolean = diff.rows.none { it.marker != PartgDiffMarker.SAME }

/** The word of a position code in the comparison: the number and the name. */
internal fun partgDiffCodeLabel(code: String): String = partgLabel(code)

/** The label of a version in a select: number, scope and day. */
internal fun partgVersionOptionLabel(
    version: PartgVersionDto,
    units: List<PartgUnitDto>,
): String =
    gettext(
        "Version %1, %2, %3",
        version.versionNumber.toString(),
        partgVersionScopeText(version.scopeKey, units),
        partgGermanDate(toOrganizationZone(version.frozenAt).date),
    )

/** The resolved text of the refusal of a comment (a `FieldCheck.Invalid` message is resolved text, never a raw `tr` marker). */
internal fun partgCommentInvalidText(): String = resolvedAttributeText(tr("Höchstens 500 Zeichen, ohne Zeilenumbruch."))
