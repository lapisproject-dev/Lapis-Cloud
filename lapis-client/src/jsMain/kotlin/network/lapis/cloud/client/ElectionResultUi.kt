package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.Widget
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOptionDto
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.rpc.IElectionService

/*
 * V1.9.22 -- the count progress, the result, the ballot list and the receipt check of an election's detail view.
 *
 * Secrecy rules this file keeps (and `ElectionSecrecyTripwireTest` guards):
 *  - V1.9.46: a secret election shows NO single ballots at all (only the count and an explanatory sentence); the client does
 *    not even call `listElectionBallots` for it ([electionBallotsListable]);
 *  - the receipt code typed into the check field is read once and the field is cleared; it is never stored or logged.
 */

internal fun renderTallyProgress(
    panel: SimplePanel,
    data: ElectionDetailData,
) {
    val e = data.election
    if (e.status != ElectionStatus.CLOSED) return
    val p = data.participation
    panel.h2(tr("Freigaben der Auszählung")) { addCssClass("h5") }
    panel.div(gettext("%1 von %2 erforderlichen Freigaben erteilt", p.tallyApprovalCount, p.tallyThreshold)) {
        addCssClasses("small")
    }
    val percent = if (p.tallyThreshold <= 0) 100 else (p.tallyApprovalCount * 100 / p.tallyThreshold).coerceAtMost(100)
    val bar = panel.div(className = "lapis-election-bar")
    bar.setAttribute("role", "progressbar")
    bar.setAttribute("aria-valuemin", "0")
    bar.setAttribute("aria-valuenow", p.tallyApprovalCount.toString())
    bar.setAttribute("aria-valuemax", p.tallyThreshold.toString())
    bar.setAttribute("aria-label", gettext("Freigaben der Auszählung"))
    bar.div(className = "lapis-election-bar__fill") { width = percent.perc }
    panel.p(tr("Wer freigegeben hat, wird nicht namentlich angezeigt.")) { addCssClasses("text-muted small mb-0") }
}

internal fun renderElectionResultSection(
    panel: SimplePanel,
    data: ElectionDetailData,
) {
    val e = data.election
    val result = data.result ?: return
    panel.h2(tr("Ergebnis")) { addCssClass("h5") }
    renderElectionResultCompact(panel, e, result, showHint = data.participation.ballotCount > 0)

    val eligibleCount = data.participation.eligibleCount
    if (eligibleCount != null) {
        panel.div(gettext("Beteiligung: %1 von %2 Wahlberechtigten", data.participation.ballotCount, eligibleCount)) {
            addCssClasses("text-muted small")
        }
    }
    if (e.resolutionId != null) {
        val link = panel.button(tr("Beschluss im Beschlussbuch"), style = ButtonStyle.OUTLINESECONDARY)
        link.onClick { navigateTo("/motions/${e.motionId}") }
    }
}

/**
 * V1.9.26 -- the rows of a result (one per option, winners marked) and the verdict line, without heading, participation or links: the
 * part of the result the detail view and the conference panel share. The room shows exactly this, so the two can never disagree.
 */
internal fun renderElectionResultCompact(
    container: Container,
    e: ElectionDto,
    result: ElectionResultDto,
    showHint: Boolean = true,
) {
    // V1.9.53: the first decision is the server's flag, never the shape of the figures.
    if (result.figuresWithheld) {
        renderElectionResultWithheld(
            container = container,
            e = e,
            winnerOptionIds = result.winnerOptionIds,
            tie = result.tie,
            majorityMet = result.majorityMet,
            minimumResponses = result.minimumResponses,
            showHint = showHint,
        )
        return
    }
    // Ordered by position, not by votes: the order is the same with and without figures and never tells a ranking.
    val options = e.options.sortedBy { it.position }
    val maxVotes = options.maxOfOrNull { result.perOptionVotes[it.id] ?: 0 }?.coerceAtLeast(1) ?: 1
    options.forEach { option ->
        renderResultRow(
            container,
            e,
            option,
            result.perOptionVotes[option.id] ?: 0,
            maxVotes,
            option.id in result.winnerOptionIds,
        )
    }
    renderElectionVerdict(container, e, result.tie, result.majorityMet)
}

/**
 * V1.9.53 -- the result of a secret election below the minimum participation: who won (labels, no counts), the verdict and
 * one sentence why no figures are shown. Takes no vote figure at all; [minimumResponses] is the rule's constant, not a count.
 */
private fun renderElectionResultWithheld(
    container: Container,
    e: ElectionDto,
    winnerOptionIds: List<String>,
    tie: Boolean,
    majorityMet: Boolean?,
    minimumResponses: Int,
    showHint: Boolean,
) {
    if (e.electionType != ElectionType.YES_NO) {
        e.options.sortedBy { it.position }.forEach { option ->
            val row = container.hPanel(spacing = 8) { addCssClasses("align-items-center") }
            row.div(displayOptionLabel(e, option.label)) { addCssClasses("flex-grow-1") }
            if (option.id in winnerOptionIds) row.statusBadge(tr("Gewählt"), "success")
        }
    }
    renderElectionVerdict(container, e, tie, majorityMet)
    if (showHint) {
        container.p(
            gettext(
                "Aus Gründen des Wahlgeheimnisses werden bei geheimen Wahlen Stimmenzahlen erst ab %1 Stimmzetteln gezeigt.",
                minimumResponses,
            ),
        ) { addCssClasses("text-muted small mb-0") }
    }
}

private fun renderElectionVerdict(
    container: Container,
    e: ElectionDto,
    tie: Boolean,
    majorityMet: Boolean?,
) {
    if (e.electionType == ElectionType.YES_NO) {
        val text =
            when {
                tie -> gettext("Gleichstand oder keine entscheidenden Stimmen: Der Antrag wurde zurückgestellt.")
                majorityMet == true -> gettext("Die erforderliche Mehrheit wurde erreicht.")
                else -> gettext("Die erforderliche Mehrheit wurde nicht erreicht.")
            }
        container.p(text) { addCssClasses("fw-bold mb-0") }
    } else if (tie) {
        container.p(
            tr(
                "Es wurde niemand gewählt: Gleichstand an der Sitzgrenze oder die erforderliche Mehrheit wurde verfehlt. " +
                    "Der Antrag wurde zurückgestellt.",
            ),
        ) { addCssClasses("alert alert-warning mb-0") }
    }
}

private fun renderResultRow(
    panel: Container,
    e: ElectionDto,
    option: ElectionOptionDto,
    votes: Int,
    maxVotes: Int,
    winner: Boolean,
) {
    val row = panel.vPanel(spacing = 2)
    val head = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    head.div(displayOptionLabel(e, option.label)) { addCssClasses("flex-grow-1") }
    if (winner) head.statusBadge(tr("Gewählt"), "success")
    head.div(votes.toString()) { addCssClasses("fw-bold lapis-num") }
    val bar = row.div(className = "lapis-election-bar")
    bar.setAttribute("aria-hidden", "true")
    bar.div(className = "lapis-election-bar__fill") { width = (votes * 100 / maxVotes).perc }
}

/** What a ballot table row may know. Only ever built for an open-ballot election: voter, selection and time. */
private class BallotRow(
    val name: String,
    val selection: String,
    val at: String,
)

/** V1.9.46: single ballots are listed only for an open-ballot election that is TALLIED -- never for a secret one. */
internal fun electionBallotsListable(e: ElectionDto): Boolean = !e.secret && e.status == ElectionStatus.TALLIED

internal fun renderBallotsSection(
    panel: SimplePanel,
    data: ElectionDetailData,
) {
    val e = data.election
    if (e.status != ElectionStatus.OPEN && e.status != ElectionStatus.CLOSED && e.status != ElectionStatus.TALLIED) return
    panel.h2(tr("Stimmzettel")) { addCssClass("h5") }
    val count = data.participation.ballotCount
    if (e.secret) {
        panel.p(
            when (count) {
                0 -> tr("Es wurden keine Stimmzettel abgegeben.")
                1 -> gettext("1 Stimmzettel abgegeben.")
                else -> gettext("%1 Stimmzettel abgegeben.", count)
            },
        )
        panel.p(
            tr(
                "Aus Gründen des Wahlgeheimnisses werden bei geheimen Wahlen keine einzelnen Stimmzettel angezeigt; " +
                    "Sie können mit Ihrer Quittung prüfen, dass Ihre Stimme gezählt wurde.",
            ),
        )
        return
    }
    if (e.status != ElectionStatus.TALLIED) {
        panel.p(if (count == 1) gettext("1 Stimmzettel abgegeben.") else gettext("%1 Stimmzettel abgegeben.", count))
        return
    }
    val rows =
        data.ballots.map { ballot ->
            val selection = ballot.selectedOptionLabels.joinToString(", ") { displayOptionLabel(e, it) }
            BallotRow(name = ballot.memberDisplayName.orEmpty(), selection = selection, at = formatSystemDateTime(ballot.castAt))
        }
    if (rows.isEmpty()) {
        panel.p(tr("Es wurden keine Stimmzettel abgegeben."))
        return
    }
    panel.dataTable(
        columns =
            listOf(
                textColumn<BallotRow>(title = tr("Name"), primary = true) { it.name },
                textColumn(title = tr("Auswahl")) { it.selection },
                textColumn(title = tr("Zeit")) { it.at },
            ),
        rows = rows,
    )
}

internal fun renderReceiptVerification(
    panel: SimplePanel,
    e: ElectionDto,
) {
    if (!e.secret) return
    if (e.status != ElectionStatus.OPEN && e.status != ElectionStatus.CLOSED && e.status != ElectionStatus.TALLIED) return
    panel.h2(tr("Quittung prüfen")) { addCssClass("h5") }
    panel.p(
        tr("Mit Ihrer Quittung können Sie prüfen, dass Ihre Stimme gezählt wurde, ohne dass jemand erfährt, wie Sie abgestimmt haben."),
    ) {
        addCssClasses("text-muted small")
    }
    val form = panel.lapisForm()
    val codeField =
        form.textField(
            label = tr("Quittungscode"),
            required = true,
            autocomplete = Autocomplete.OFF,
            init = { text ->
                (text.input as? Widget)?.setAttribute("spellcheck", "false")
                (text.input as? Widget)?.setAttribute("autocapitalize", "off")
            },
        )
    val check = Button(tr("Prüfen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = check)
    val outcome =
        panel.div("") {
            addCssClasses("fw-bold")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }
    check.onClick {
        form.submit(check) {
            // Read once, then the field is cleared below: the code is not kept anywhere else.
            val code = codeField.value.filterNot { it.isWhitespace() }
            val verification = guarded { rpcService<IElectionService>().verifyReceipt(e.id, code) }
            codeField.reset()
            if (verification == null) return@submit
            val label = verification.optionLabel
            val text =
                when {
                    !verification.found -> gettext("Zu diesem Code wurde keine Stimme gefunden.")
                    label == null -> gettext("Ihre Stimme ist gespeichert. Die Auswahl wird erst nach der Auszählung angezeigt.")
                    else -> gettext("Ihre Stimme ist gespeichert und lautet: %1", displayReceiptLabels(e, label))
                }
            untrustedContent(outcome, text)
        }
    }
}

/** The label(s) of a verified receipt: the server joins several selections with ", "; a yes/no label is one of the three enum names. */
private fun displayReceiptLabels(
    e: ElectionDto,
    raw: String,
): String =
    if (e.electionType == ElectionType.YES_NO) {
        raw.split(", ").joinToString(", ") { displayOptionLabel(e, it) }
    } else {
        sanitizeUntrustedI18nText(raw)
    }
