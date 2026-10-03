package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.utils.perc
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollOptionDto
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.isConsensus

/*
 * V1.9.31 -- the result of a closed poll: two blocks side by side (by heads, by LTR weight), each option once, in the order of the poll.
 *
 * Deliberately NOT sorted by rank and with no winner marker: a poll is a mood picture, never a resolution, and a highlighted "first place"
 * would turn it into one. The weighted block shows whole percents only -- the server sends no absolute LTR sums and none can be derived.
 * A block the server withholds says so in a sentence instead of showing partial numbers (a unanimous result of a few answers would reveal
 * every single answer).
 */
internal fun renderPollResult(
    host: SimplePanel,
    poll: PollDto,
    result: PollResultDto?,
) {
    if (result != null && result.kind.isConsensus) {
        renderPollRatingResult(host, poll, result)
        return
    }
    host.h2(tr("Ergebnis")) { addCssClass("h5") }
    if (result == null) {
        host.p(tr("Das Ergebnis konnte nicht geladen werden."))
        return
    }
    host.p(
        if (result.responseCount == 1) {
            gettext("Unverbindliches Stimmungsbild · 1 Antwort")
        } else {
            gettext("Unverbindliches Stimmungsbild · %1 Antworten", result.responseCount)
        },
    ) { addCssClasses("text-muted mb-0") }
    val grid = host.div(className = "lapis-poll-results")
    renderHeadBlock(grid, poll, result)
    renderWeightedBlock(grid, poll, result)
}

private fun renderHeadBlock(
    grid: Container,
    poll: PollDto,
    result: PollResultDto,
) {
    val block = grid.tag(TAG.SECTION, className = "lapis-poll-block")
    val titleId = "poll-head-title"
    val title = block.h2(tr("Nach Köpfen")) { addCssClass("h6") }
    title.id = titleId
    block.setAttribute("aria-labelledby", titleId)
    if (!result.headResultAvailable) {
        block.p(pollHeadWithheldText()) { addCssClasses("text-muted") }
        return
    }
    // Options in the order of the poll, with a missing entry counted as 0 -- never sorted by count.
    val counts = poll.options.map { option -> result.headResult.firstOrNull { it.optionId == option.id }?.count ?: 0 }
    val percents = headSharePercents(counts)
    poll.options.forEachIndexed { index, option ->
        val answers = if (counts[index] == 1) gettext("1 Antwort") else gettext("%1 Antworten", counts[index])
        renderBar(block, option, percents[index], gettext("%1 % · %2", percents[index], answers), weighted = false)
    }
}

private fun renderWeightedBlock(
    grid: Container,
    poll: PollDto,
    result: PollResultDto,
) {
    val block = grid.tag(TAG.SECTION, className = "lapis-poll-block")
    val titleId = "poll-weighted-title"
    val title = block.h2(tr("Nach LTR-Gewicht")) { addCssClass("h6") }
    title.id = titleId
    block.setAttribute("aria-labelledby", titleId)
    block.p(
        tr(
            "Jede Antwort zählt mit dem LTR-Stand, den die Person beim Antworten hatte – wer mehr beigetragen hat, zählt mehr. " +
                "Deshalb kann dieses Bild vom Ergebnis nach Köpfen abweichen.",
        ),
    ) { addCssClasses("text-muted small") }
    if (!result.weightedResultAvailable) {
        block.p(pollWithheldText(result.weightedWithheldReason)) { addCssClasses("text-muted") }
        return
    }
    poll.options.forEach { option ->
        // A share that the server did not list counts as 0; ids that belong to no option of the poll are skipped (never rendered).
        val share = result.weightedResult.firstOrNull { it.optionId == option.id }?.sharePercent ?: 0
        renderBar(block, option, share, gettext("%1 %", share), weighted = true)
    }
}

private fun renderBar(
    block: Container,
    option: PollOptionDto,
    percent: Int,
    text: String,
    weighted: Boolean,
) {
    val row = block.div(className = "lapis-poll-row")
    row.untrustedDiv(option.text, className = "text-break")
    val bar = row.div(className = "lapis-poll-bar")
    bar.setAttribute("aria-hidden", "true")
    bar.div(className = if (weighted) "lapis-poll-bar__fill lapis-poll-bar__fill--weighted" else "lapis-poll-bar__fill") {
        width = percent.coerceIn(0, 100).perc
    }
    row.span(text) { addCssClasses("lapis-num small") }
}
