package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.PollDecisionOutcome
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRatingOptionResultDto
import network.lapis.cloud.shared.domain.PollRatingResultDto
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollRules

/*
 * V1.9.41 -- the result of a closed consensus poll (SK_DECISION / SK_PRIORITY). The server sends the options already sorted (and decides
 * ties); this screen only shows them, it never recomputes a rank. Per option: the mean resistance, the cumulative sum, how often the
 * top value was given, the group conflict in words, a strong objection (maximum >= 9) as a sentence, and the distribution behind a switch.
 *
 * A mood picture, never a resolution: no bars (a bar of "less is better" would read as a score), no success colour for the leader --
 * the leading option of a decision only carries the neutral "Geringster Widerstand" mark. Below the minimum number of answers only the
 * response count is shown (the same single threshold for every aggregate).
 */
internal fun renderPollRatingResult(
    host: SimplePanel,
    poll: PollDto,
    result: PollResultDto,
) {
    host.h2(tr("Ergebnis")) { addCssClass("h5") }
    host.p(
        if (result.responseCount == 1) {
            gettext("Unverbindliches Stimmungsbild · 1 Antwort")
        } else {
            gettext("Unverbindliches Stimmungsbild · %1 Antworten", result.responseCount)
        },
    ) { addCssClasses("text-muted mb-0") }
    val rating = result.ratingResult
    if (!result.ratingResultAvailable || rating == null) {
        host.p(pollHeadWithheldText()) { addCssClasses("text-muted") }
        return
    }
    val byId = poll.options.associateBy { it.id }
    val numbers = pollRatingOrderedOptions(poll).associate { (number, option) -> option.id to number }
    when (poll.kind) {
        PollKind.SK_DECISION -> renderVerdict(host, rating, poll)
        PollKind.SK_PRIORITY -> host.h2(tr("Rangliste nach Widerstand")) { addCssClass("h6") }
        PollKind.SINGLE_CHOICE -> Unit
    }
    // The order comes from the server; an id that belongs to no option of the poll is skipped, never rendered.
    rating.options.forEachIndexed { index, option ->
        val dto = byId[option.optionId] ?: return@forEachIndexed
        renderRatingRow(
            panel = host,
            poll = poll,
            option = option,
            optionText = pollRatingOptionText(dto),
            number = numbers[option.optionId],
            explanationRaw = if (dto.isPassive) null else dto.explanation,
            leading = poll.kind == PollKind.SK_DECISION && option.optionId == rating.winnerOptionId,
            index = index,
        )
    }
}

private fun renderVerdict(
    host: SimplePanel,
    rating: PollRatingResultDto,
    poll: PollDto,
) {
    when (rating.outcome) {
        PollDecisionOutcome.OPTION_WINS -> {
            val winner = poll.options.firstOrNull { it.id == rating.winnerOptionId }
            if (winner != null) {
                host.p(gettext("Geringster Widerstand: %1", pollRatingOptionText(winner))) { addCssClasses("fw-bold mb-0 text-break") }
            }
        }
        PollDecisionOutcome.NO_CHANGE_WINS -> {
            host.p(tr("Keine Änderung hat den geringsten Widerstand.")) { addCssClasses("fw-bold mb-0") }
            if (rating.tieAtLowest) {
                host.p(tr("Gleichstand beim kumulierten Widerstand: Bei Gleichstand bleibt es bei keiner Änderung.")) {
                    addCssClasses("text-muted small mb-0")
                }
            }
        }
        PollDecisionOutcome.NO_CLEAR_RESULT ->
            host.p(tr("Gleichstand, kein eindeutiges Ergebnis.")) { addCssClasses("alert alert-warning mb-0") }
        null -> Unit
    }
    if (rating.decidedByLowestMax) {
        host.p(tr("Gleichstand beim kumulierten Widerstand, entschieden durch den geringsten Höchstwert.")) {
            addCssClasses("text-muted small mb-0")
        }
    }
}

@Suppress("LongParameterList")
private fun renderRatingRow(
    panel: Container,
    poll: PollDto,
    option: PollRatingOptionResultDto,
    optionText: String,
    number: String?,
    explanationRaw: String?,
    leading: Boolean,
    index: Int,
) {
    val box = panel.vPanel(spacing = 4) { addCssClasses("lapis-sk-rank") }
    val head = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    if (poll.kind == PollKind.SK_PRIORITY) {
        head.span(gettext("%1.", option.rank), className = "fw-bold lapis-num")
        if (option.tied) head.span(tr("gleichauf"), className = "text-muted small")
    } else if (number != null) {
        head.consensusNumberPlaque(number)
        head.consensusNumberSrPrefix(number)
    }
    head.div(optionText) { addCssClasses("flex-grow-1 fw-bold text-break") }
    if (leading) head.statusBadge(tr("Geringster Widerstand"), "info")
    if (poll.kind == PollKind.SK_DECISION && option.strongObjection) head.statusBadge(tr("Starker Einwand"), "danger")

    val figures = box.hPanel(spacing = 8) { addCssClasses("align-items-baseline flex-wrap") }
    figures.span(gettext("Ø %1", formatDecimal(value = option.meanResistance, places = 1)), className = "fw-bold lapis-num")
    figures.span(gettext("Summe %1 · 10er: %2", option.cumulativeResistance, option.topValueCount), className = "text-muted small")

    box.div(
        gettext(
            "Gruppenkonflikt: %1 (%2)",
            groupConflictWord(option.consensusIndex),
            formatDecimal(value = option.consensusIndex, places = 2),
        ),
    ) { addCssClasses("small") }
    if (option.strongObjection) {
        box.div(gettext("Höchster Einzelwert: %1. Das ist ein starker Einwand.", option.maxResistance)) {
            addCssClasses("small text-danger")
        }
    }
    box.renderResistanceDistribution(option.distribution, PollRules.SK_SCALE_MAX)
    renderUntrustedExplanation(
        parent = box,
        raw = explanationRaw,
        mode = RationaleMode.Collapsed,
        toggleIdPrefix = "poll-res-why",
        index = index,
        closedLabel = tr("Erklärung"),
    )
}
