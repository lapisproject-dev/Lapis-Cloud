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
import network.lapis.cloud.shared.domain.SystemicConsensusOptionResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
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
 */

/** Resistance at or above this value is a strong objection that the mean can hide. */
private const val STRONG_OBJECTION = 9

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
    // The server decides a tie in the mean with its tiebreak rule: the winner always leads its tied options.
    val ranked =
        result.optionResults.sortedWith(
            compareBy<SystemicConsensusOptionResultDto> { it.meanResistance }.thenBy { it.optionId != result.winnerOptionId },
        )
    ranked.forEachIndexed { index, option ->
        renderRankRow(panel, c, option, rank = index + 1, winner = option.optionId == result.winnerOptionId)
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

private fun renderRankRow(
    panel: Container,
    c: SystemicConsensusDto,
    option: SystemicConsensusOptionResultDto,
    rank: Int,
    winner: Boolean,
) {
    val dto = c.options.firstOrNull { it.id == option.optionId }
    val box = panel.vPanel(spacing = 4) { addCssClasses(if (winner) "lapis-sk-rank lapis-sk-rank--winner" else "lapis-sk-rank") }
    val head = box.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    head.div(rank.toString()) { addCssClasses("fw-bold lapis-num") }
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
    if (option.maxResistance >= STRONG_OBJECTION) {
        box.div(
            gettext("Höchster Einzelwert: %1. Das ist ein starker Einwand.", option.maxResistance),
        ) { addCssClasses("small text-danger") }
    }
    renderDistribution(box, option, c.scaleMax)
}

/** The rating histogram of one option, collapsed by default. [SystemicConsensusOptionResultDto.distribution] only holds values that were cast; the gaps are filled with 0. */
private fun renderDistribution(
    box: Container,
    option: SystemicConsensusOptionResultDto,
    scaleMax: Int,
) {
    val toggle = Button(tr("Verteilung anzeigen"), style = ButtonStyle.OUTLINESECONDARY)
    toggle.setAttribute("aria-expanded", "false")
    box.add(toggle)
    val total = option.distribution.values.sum()
    val content = box.vPanel(spacing = 2) { hide() }
    content.div(if (total == 1) gettext("1 Bewertung") else gettext("%1 Bewertungen", total)) { addCssClasses("text-muted small") }
    val grid = content.div(className = "lapis-sk-hist")
    (0..scaleMax).forEach { value ->
        val column = grid.div { addCssClasses("d-flex flex-column") }
        column.span(value.toString()) { addCssClasses("text-muted") }
        column.span((option.distribution[value] ?: 0).toString()) { addCssClasses("fw-bold") }
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
    val options = c.options.filterNot { it.isStatusQuoOption }.sortedBy { it.position } + c.options.filter { it.isStatusQuoOption }
    host.dataTable(
        columns =
            listOf(textColumn<SystemicConsensusBallotDto>(title = tr("Name"), primary = true) { it.memberDisplayName.orEmpty() }) +
                options.map { option ->
                    textColumn<SystemicConsensusBallotDto>(title = consensusOptionText(option), numeric = true) {
                        it.resistances[option.id]?.toString().orEmpty()
                    }
                },
        rows = ballots.sortedBy { it.memberDisplayName.orEmpty() },
    )
}
