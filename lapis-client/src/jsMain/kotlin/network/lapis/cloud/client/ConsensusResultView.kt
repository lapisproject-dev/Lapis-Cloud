package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusRules
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.28 -- the result of an evaluated consensus: a ranking (lowest resistance first), per option the mean, the group conflict in words and
 * a collapsible rating distribution. The numbers are exactly what `evaluate` computed -- the screen reads them through
 * `getSystemicConsensusResult`, which shares the calculation with `evaluate`.
 *
 * Anonymity: for an anonymous consensus no list of single ratings is requested at all (only the aggregate per option is shown). Only an OPEN
 * consensus lists who rated what, and only that branch calls `listResistanceBallots` (`ConsensusSecrecyTripwireTest`).
 *
 * V1.9.42: an anonymous consensus with fewer than the minimum participation arrives with `figuresWithheld` and no `optionResults` at all.
 * Then only the winner, the group-wide verdict and the options in their list order are shown -- no ranking, no figure. The branch is
 * decided by `figuresWithheld` alone, never by an empty list.
 */

internal fun renderConsensusResult(
    panel: SimplePanel,
    data: ConsensusDetailData,
) {
    val c = data.consensus
    if (c.status != SystemicConsensusStatus.EVALUATED) return
    panel.h2(tr("Ergebnis")) { addCssClass("h5") }
    val result = data.result
    if (result == null) {
        panel.p(tr("Das Ergebnis konnte nicht geladen werden."))
        return
    }
    renderVerdict(panel, c, result)
    val numbers = consensusOptionNumbers(c.options)
    if (result.figuresWithheld) {
        consensusOrderedOptions(c.options).forEachIndexed { index, option ->
            renderWithheldRow(
                panel,
                option,
                number = numbers[option.id],
                winner = option.id == result.winnerOptionId,
                index = index,
            )
        }
    } else {
        // The server decides a tie in the mean with its tiebreak rule: the winner always leads its tied options.
        val ranked =
            result.optionResults.sortedWith(
                compareBy<SystemicConsensusOptionResultDto> { it.meanResistance }.thenBy { it.optionId != result.winnerOptionId },
            )
        ranked.forEachIndexed { index, option ->
            renderRankRow(
                panel,
                c,
                option,
                rank = index + 1,
                number = numbers[option.optionId],
                winner = option.optionId == result.winnerOptionId,
            )
        }
    }
    if (!c.secret) {
        panel.h2(tr("Namentliche Bewertungen")) { addCssClass("h5") }
        panel
            .dataSection<List<SystemicConsensusBallotDto>>(
                emptyText = gettext("Es wurden keine Bewertungen abgegeben."),
                isEmpty = { it.isEmpty() },
                load = { guarded { rpcService<ISystemicConsensusService>().listResistanceBallots(c.id) } },
                render = { host, ballots -> renderNamedRatings(host, c, ballots) },
            ).reload()
    }
}

private fun renderVerdict(
    panel: SimplePanel,
    c: SystemicConsensusDto,
    result: SystemicConsensusResultDto,
) {
    val winnerOption = c.options.firstOrNull { it.id == result.winnerOptionId }
    when {
        result.noRatings -> panel.p(tr("Es wurde keine Bewertung abgegeben.")) { addCssClasses("alert alert-warning mb-0") }
        winnerOption == null ->
            panel.p(tr("Gleichstand ohne Entscheidung. Bitte erneut diskutieren.")) { addCssClasses("alert alert-warning mb-0") }
        else -> {
            panel.p(gettext("Geringster Widerstand: %1", consensusOptionText(winnerOption))) { addCssClasses("fw-bold mb-0 text-break") }
        }
    }
    renderWithheldNotice(panel, result, winnerOption)
    if (winnerOption != null) {
        when (result.tiebreakApplied) {
            SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE ->
                panel.p(tr("Gleichstand beim kumulierten Widerstand, entschieden durch den geringsten Höchstwert.")) {
                    addCssClasses("text-muted small mb-0")
                }
            SystemicConsensusTiebreakRule.LOWEST_STD_DEV ->
                panel.p(tr("Gleichstand beim kumulierten Widerstand, entschieden durch die geringste Streuung.")) {
                    addCssClasses("text-muted small mb-0")
                }
            else -> Unit
        }
    }
    if (c.bindingness == SystemicConsensusBindingness.BINDING) {
        panel.p(tr("Als Beschluss protokolliert.")) { addCssClasses("text-muted mb-0") }
        when {
            winnerOption == null -> panel.p(tr("Ohne Entscheidung: Der Antrag wurde zurückgestellt.")) { addCssClasses("text-muted mb-0") }
            winnerOption.isStatusQuoOption ->
                panel.p(tr("Die Passivlösung hat gewonnen: Der Antrag gilt als abgelehnt.")) { addCssClasses("alert alert-warning mb-0") }
        }
    }
}

/** V1.9.42: the privacy sentence and the group-wide verdict, directly under the winner line (anonymous result below the minimum only). */
private fun renderWithheldNotice(
    panel: SimplePanel,
    result: SystemicConsensusResultDto,
    winnerOption: SystemicConsensusOptionDto?,
) {
    if (!result.figuresWithheld || result.noRatings) return
    panel.p(
        gettext("Aus Datenschutzgründen werden Zahlen bei anonymem Konsensieren erst ab %1 Bewertungen gezeigt.", result.minimumResponses),
    ) { addCssClasses("text-muted mb-0") }
    if (winnerOption != null) {
        panel.p(
            gettext(
                "Gruppenkonflikt: %1",
                groupConflictWord(consensusViable = result.consensusViable, groupConflictWarning = result.groupConflictWarning),
            ),
        ) { addCssClasses("mb-0") }
    }
}

/** V1.9.42: one option of a withheld result -- number, text, the winner mark and the rationale, but no rank, mean, bar or distribution. */
private fun renderWithheldRow(
    panel: Container,
    option: SystemicConsensusOptionDto,
    number: String?,
    winner: Boolean,
    index: Int,
) {
    val box = panel.vPanel(spacing = 4) { addCssClasses(if (winner) "lapis-sk-rank lapis-sk-rank--winner" else "lapis-sk-rank") }
    val head = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    if (number != null) head.consensusNumberPlaque(number)
    if (number != null) head.consensusNumberSrPrefix(number)
    head.div(consensusOptionText(option)) { addCssClasses("flex-grow-1 fw-bold text-break") }
    if (winner) head.statusBadge(tr("Geringster Widerstand"), "success")
    renderOptionRationale(box, option, RationaleMode.Collapsed, "sk-res-why", index + 1)
}

private fun renderRankRow(
    panel: Container,
    c: SystemicConsensusDto,
    option: SystemicConsensusOptionResultDto,
    rank: Int,
    number: String?,
    winner: Boolean,
) {
    val dto = c.options.firstOrNull { it.id == option.optionId }
    val box = panel.vPanel(spacing = 4) { addCssClasses(if (winner) "lapis-sk-rank lapis-sk-rank--winner" else "lapis-sk-rank") }
    val head = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    // V1.9.39: the plaque shows WHICH option this is (the numbers of the options list); the rank is the order of the rows (read out, not shown).
    head.span(gettext("Platz %1", rank), className = "visually-hidden")
    if (number != null) head.consensusNumberPlaque(number)
    if (number != null) head.consensusNumberSrPrefix(number)
    head.div(dto?.let { consensusOptionText(it) }.orEmpty()) { addCssClasses("flex-grow-1 fw-bold text-break") }
    if (winner) head.statusBadge(tr("Geringster Widerstand"), "success")
    head.div(formatResistance(mean = option.meanResistance, scaleMax = c.scaleMax)) { addCssClasses("fw-bold lapis-num") }

    val bar = box.div(className = "lapis-election-bar")
    bar.setAttribute("aria-hidden", "true")
    bar.div(className = "lapis-election-bar__fill") { width = (option.meanResistance * 100 / c.scaleMax).coerceIn(0.0, 100.0).perc }

    val index = option.consensusIndex
    box.div(
        gettext(
            "Gruppenkonflikt: %1 (%2)",
            groupConflictWord(index, c.groupConflictViableThreshold.toDouble(), c.groupConflictWarnThreshold.toDouble()),
            formatDecimal(value = index, places = 2),
        ),
    ) { addCssClasses("small") }
    box.div(
        gettext("Höchstwert %1 vergeben: %2-mal", c.scaleMax, option.distribution[c.scaleMax] ?: 0),
    ) { addCssClasses("small text-muted") }
    if (option.maxResistance >= SystemicConsensusRules.strongObjectionThreshold(c.scaleMax)) {
        box.div(
            gettext("Höchster Einzelwert: %1. Das ist ein starker Einwand.", option.maxResistance),
        ) { addCssClasses("small text-danger") }
    }
    box.renderResistanceDistribution(option.distribution, c.scaleMax)
    if (dto != null) renderOptionRationale(box, dto, RationaleMode.Collapsed, "sk-res-why", rank)
}

/**
 * The rating histogram of one option, collapsed by default (also used by the consensus polls, V1.9.41). [distribution] may only hold
 * values that were cast; the gaps are filled with 0.
 */
internal fun Container.renderResistanceDistribution(
    distribution: Map<Int, Int>,
    scaleMax: Int,
) {
    val box = this
    val toggle = Button(tr("Verteilung anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    toggle.setAttribute("aria-expanded", "false")
    box.add(toggle)
    val total = distribution.values.sum()
    val content = box.vPanel(spacing = 2) { hide() }
    content.div(if (total == 1) gettext("1 Bewertung") else gettext("%1 Bewertungen", total)) { addCssClasses("text-muted small") }
    val grid = content.div(className = "lapis-sk-hist")
    (0..scaleMax).forEach { value ->
        val column = grid.div { addCssClasses("d-flex flex-column") }
        column.span(value.toString()) { addCssClasses("text-muted") }
        column.span((distribution[value] ?: 0).toString()) { addCssClasses("fw-bold") }
    }
    var open = false
    toggle.onClick {
        open = !open
        toggle.setAttribute("aria-expanded", open.toString())
        toggle.text = if (open) tr("Verteilung ausblenden") else tr("Verteilung anzeigen")
        if (open) content.show() else content.hide()
    }
}

private fun renderNamedRatings(
    host: SimplePanel,
    c: SystemicConsensusDto,
    ballots: List<SystemicConsensusBallotDto>,
) {
    host.p(tr("Bei einem offenen Konsensieren sind die Bewertungen mit Namen sichtbar.")) { addCssClasses("text-muted small") }
    val options = consensusOrderedOptions(c.options)
    val numbers = consensusOptionNumbers(c.options)
    host.dataTable(
        columns =
            listOf(textColumn<SystemicConsensusBallotDto>(title = tr("Name"), primary = true) { it.memberDisplayName.orEmpty() }) +
                options.map { option ->
                    val columnTitle =
                        if (option.isStatusQuoOption) {
                            consensusOptionText(option)
                        } else {
                            gettext("Option %1: %2", numbers.getValue(option.id), consensusOptionText(option))
                        }
                    textColumn<SystemicConsensusBallotDto>(title = columnTitle, numeric = true) {
                        it.resistances[option.id]?.toString().orEmpty()
                    }
                },
        rows = ballots.sortedBy { it.memberDisplayName.orEmpty() },
    )
}
