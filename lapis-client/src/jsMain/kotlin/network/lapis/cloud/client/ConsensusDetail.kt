package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.28 -- the detail view of one consensus: header, the actions the viewer may take in the current phase, the options, the result and
 * (anonymous consensus) the receipt check. Every successful write reloads the whole detail (one consistent snapshot) -- the screen
 * never patches pieces of itself.
 */

/** The visible stations of a consensus, left to right. */
private enum class ConsensusStep { COLLECTING, RATING, CLOSED, EVALUATED }

private fun consensusStepLabel(step: ConsensusStep): String =
    when (step) {
        ConsensusStep.COLLECTING -> gettext("Sammeln")
        ConsensusStep.RATING -> gettext("Bewerten")
        ConsensusStep.CLOSED -> gettext("Auswerten")
        ConsensusStep.EVALUATED -> gettext("Ergebnis")
    }

/** The last station reached; an aborted consensus keeps the station it was aborted at, read from its timestamps. */
private fun reachedConsensusStep(c: SystemicConsensusDto): ConsensusStep =
    when (c.status) {
        SystemicConsensusStatus.COLLECTION -> ConsensusStep.COLLECTING
        SystemicConsensusStatus.RATING -> ConsensusStep.RATING
        SystemicConsensusStatus.CLOSED -> ConsensusStep.CLOSED
        SystemicConsensusStatus.EVALUATED -> ConsensusStep.EVALUATED
        SystemicConsensusStatus.ABORTED ->
            when {
                c.ratingClosedAt != null -> ConsensusStep.CLOSED
                c.ratingOpenedAt != null -> ConsensusStep.RATING
                else -> ConsensusStep.COLLECTING
            }
    }

/** The phase bar, built like the elections' (same stylesheet): an ordered list, the current station carries `aria-current="step"`. */
private fun Container.consensusPhaseBar(c: SystemicConsensusDto): Tag {
    val steps = ConsensusStep.entries
    val reached = steps.indexOf(reachedConsensusStep(c))
    val list = tag(TAG.OL, className = "lapis-election-phase")
    list.setAttribute("aria-label", gettext("Ablauf des Konsensierens"))
    steps.forEachIndexed { index, step ->
        list.tag(TAG.LI, content = consensusStepLabel(step), className = "lapis-election-phase__item") {
            if (index < reached) addCssClass("lapis-election-phase__item--done")
            if (index == reached) {
                addCssClass("lapis-election-phase__item--current")
                setAttribute("aria-current", "step")
            }
        }
    }
    return list
}

/**
 * Loads and renders the detail of [consensusId] into [panel]; a successful write reloads the whole detail and then calls [onChanged].
 */
internal fun renderConsensusDetail(
    panel: SimplePanel,
    consensusId: String,
    ctx: ConsensusUiContext,
    onChanged: () -> Unit = {},
) {
    panel.removeAll()
    panel.p(tr("Wird geladen …"))
    AppScope.launch {
        val data = loadConsensusDetail(consensusId)
        panel.removeAll()
        if (data == null) {
            panel.p(tr("Das Konsensieren konnte nicht geladen werden."))
            return@launch
        }
        val reload: () -> Unit = {
            onChanged()
            renderConsensusDetail(panel, consensusId, ctx, onChanged)
        }
        renderConsensusHeader(panel, data)
        renderConsensusActions(panel, data, reload)
        renderConsensusOptions(panel, data, ctx.currentMemberId, reload)
        renderConsensusResult(panel, data)
        renderConsensusReceiptCheck(panel, data.consensus)
    }
}

private fun renderConsensusHeader(
    panel: SimplePanel,
    data: ConsensusDetailData,
) {
    val c = data.consensus
    val p = data.participation
    val headerRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.untrustedHeading(c.title, level = 2, className = "h5 flex-grow-1")
    headerRow.statusBadge(consensusStatusLabel(c.status), consensusStatusColor(c.status))
    headerRow.typeBadge(
        consensusBindingnessLabel(c.bindingness),
        if (c.bindingness ==
            SystemicConsensusBindingness.BINDING
        ) {
            "danger"
        } else {
            "secondary"
        },
    )
    headerRow.typeBadge(consensusSecrecyLabel(c.secret), if (c.secret) "primary" else "warning")

    panel.div(
        tr(
            "Jede Option wird bewertet: 0 heißt kein Widerstand, 10 heißt nicht tragbar. Die Option mit dem geringsten Widerstand liegt vorn.",
        ),
    ) { addCssClasses("alert alert-light border mb-0") }
    panel.consensusPhaseBar(c)
    panel.div(consensusRoundLabel(c)) { addCssClasses("text-muted small") }

    panel.div(
        if (c.bindingness == SystemicConsensusBindingness.BINDING) {
            tr("Beschluss: Das Ergebnis wird als Beschluss protokolliert und entscheidet den Antrag.")
        } else {
            tr("Sondierung: Das Ergebnis ist eine Orientierung und entscheidet den Antrag nicht.")
        },
    ) { addCssClasses("text-muted small") }
    panel.div(
        if (c.secret) {
            tr("Anonyme Bewertung: Es wird gespeichert, dass Sie bewertet haben, aber nicht, wie.")
        } else {
            tr("Offene Bewertung: Ihre Bewertung wird mit Ihrem Namen gespeichert und ist für alle Mitglieder sichtbar.")
        },
    ) { addCssClasses("text-muted small") }
    panel.div(
        gettext("Eröffnet von %1 am %2", c.openedByDisplayName, formatSystemDateTime(c.openedAt)),
    ) { addCssClasses("text-muted small") }

    val eligibleCount = p.eligibleCount
    if (eligibleCount != null) {
        panel.div(gettext("%1 von %2 haben bewertet", p.ballotCount, eligibleCount)) { addCssClasses("fw-bold") }
    }
    data.motion?.let { motion ->
        panel.h2(tr("Antragstext")) { addCssClass("h6") }
        panel.untrustedP(motion.effectiveText, className = "border rounded p-2 mb-0 text-break")
    }
    panel.button(tr("Zum Antrag"), style = ButtonStyle.OUTLINESECONDARY).onClick { navigateTo("/motions/${c.motionId}") }
}

/** The in-place explanation for a viewer who cannot (or no longer can) take part. */
private fun consensusParticipationNote(data: ConsensusDetailData): String? {
    val c = data.consensus
    val p = data.participation
    val isMember = AppState.session?.status in MemberStatusSets.ORGANIZATION_MEMBER
    return when {
        c.status != SystemicConsensusStatus.COLLECTION && c.status != SystemicConsensusStatus.RATING -> null
        !isMember -> gettext("Nur Mitglieder dieses Servers können bewerten.")
        c.status == SystemicConsensusStatus.RATING && p.hasRated -> gettext("Ihre Bewertung ist bereits eingegangen.")
        c.status == SystemicConsensusStatus.RATING && p.eligible == false -> gettext("Sie sind für diese Runde nicht stimmberechtigt.")
        else -> null
    }
}

/** A button for [gate]: hidden, enabled, or disabled with its reason as visible text (not only a tooltip). */
private fun Container.gatedButton(
    label: String,
    style: ButtonStyle,
    gate: Gate,
    onClick: (Button) -> Unit,
) {
    if (gate is Gate.Hidden) return
    val box = vPanel(spacing = 2)
    val button = box.button(label, style = style)
    if (gate is Gate.Disabled) {
        button.disabled = true
        val reason = gate.reason
        box.div(reason) { addCssClasses("text-muted small") }
    } else {
        button.onClick { onClick(button) }
    }
}

private fun optionCountMessage(count: Int): String =
    if (count == 1) {
        gettext("1 Option wird zur Bewertung freigegeben. Danach kann keine Option mehr ergänzt werden.")
    } else {
        gettext("%1 Optionen werden zur Bewertung freigegeben. Danach kann keine Option mehr ergänzt werden.", count)
    }

private fun renderConsensusActions(
    panel: SimplePanel,
    data: ConsensusDetailData,
    reload: () -> Unit,
) {
    val c = data.consensus
    val p = data.participation
    val freezeGate = canFreeze(c, p)
    val canClose = canCloseRating(c, p)
    val canEvaluateNow = canEvaluate(c, p)
    val reopenOffer = canReopen(c, p, data.result)
    val canBooth = canEnterConsensusBooth(c, p)
    val canAbortNow = canAbortConsensus(c, p)
    val note = consensusParticipationNote(data)
    val nothing =
        freezeGate is Gate.Hidden &&
            !canClose &&
            !canEvaluateNow &&
            reopenOffer == ReopenOffer.Hidden &&
            !canBooth &&
            !canAbortNow &&
            note == null
    if (nothing) return

    panel.h2(tr("Aktionen")) { addCssClass("h5") }
    if (note != null) panel.p(note) { addCssClasses("alert alert-info mb-0") }
    if (canEvaluateNow && c.bindingness == SystemicConsensusBindingness.BINDING) {
        panel.p(tr("Das Ergebnis wird als Beschluss protokolliert und kann danach nicht mehr geändert werden.")) {
            addCssClasses("alert alert-warning mb-0")
        }
    }
    // Full width on a phone, natural button width from 576 px up.
    val row = panel.vPanel(spacing = 8) { addCssClass("align-items-sm-start") }

    if (canBooth) {
        row.button(tr("Zur Bewertung"), style = ButtonStyle.PRIMARY).onClick {
            // Leaving the booth, with or without a rating, reloads the detail: the participation state is what the next screen shows.
            renderConsensusBooth(panel, c) { reload() }
        }
    }
    row.gatedButton(tr("Optionen einfrieren"), ButtonStyle.PRIMARY, freezeGate) { button ->
        confirmDialog(
            title = tr("Optionen einfrieren"),
            message = optionCountMessage(c.options.size),
            confirmLabel = tr("Einfrieren"),
            confirmStyle = ButtonStyle.PRIMARY,
            focusCancel = true,
        ) {
            runGuardedAction(button) {
                val result =
                    consensusGuarded(
                        gettext("Die Optionen konnten nicht eingefroren werden. Bitte Ansicht aktualisieren."),
                        onConflict = reload,
                    ) {
                        rpcService<ISystemicConsensusService>().freezeOptions(c.id)
                    }
                if (result != null) {
                    notifySuccess(tr("Optionen eingefroren. Die Bewertung läuft."))
                    reload()
                }
            }
        }
    }
    if (canClose) {
        val closeButton = row.actionButton(ActionIcon.CLOSE, tr("Bewertung schließen"), style = ButtonStyle.WARNING)
        closeButton.onClick {
            confirmDialog(
                title = tr("Bewertung schließen"),
                message = tr("Nach dem Schließen kann niemand mehr bewerten. Danach folgt die Auswertung."),
                confirmLabel = tr("Bewertung schließen"),
                confirmStyle = ButtonStyle.PRIMARY,
            ) {
                runGuardedAction(closeButton) {
                    val result =
                        consensusGuarded(
                            gettext("Die Bewertung konnte nicht geschlossen werden. Bitte Ansicht aktualisieren."),
                            onConflict = reload,
                        ) {
                            rpcService<ISystemicConsensusService>().closeRating(c.id)
                        }
                    if (result != null) {
                        notifySuccess(tr("Bewertung geschlossen."))
                        reload()
                    }
                }
            }
        }
    }
    if (canEvaluateNow) renderEvaluateAction(row, c, reload)
    if (reopenOffer != ReopenOffer.Hidden) renderReopenAction(row, c, reopenOffer, reload)
    if (canAbortNow) renderConsensusAbortAction(row, c, reload)
}

private fun renderEvaluateAction(
    row: Container,
    c: SystemicConsensusDto,
    reload: () -> Unit,
) {
    val evaluate = row.button(tr("Auswerten"), style = ButtonStyle.SUCCESS)

    fun doEvaluate() {
        runGuardedAction(evaluate) {
            val result =
                consensusGuarded(gettext("Die Auswertung war nicht möglich. Bitte Ansicht aktualisieren."), onConflict = reload) {
                    rpcService<ISystemicConsensusService>().evaluate(c.id)
                }
            if (result != null) {
                notifySuccess(tr("Ausgewertet."))
                reload()
            }
        }
    }
    evaluate.onClick {
        if (c.bindingness == SystemicConsensusBindingness.BINDING) {
            // Irreversible: a binding consensus writes its resolution right here and has no revote.
            confirmDialog(
                title = tr("Auswerten"),
                message =
                    tr(
                        "Das Ergebnis wird als Beschluss protokolliert und entscheidet den Antrag. Das kann nicht rückgängig gemacht werden.",
                    ),
                confirmLabel = tr("Auswerten"),
                confirmStyle = ButtonStyle.PRIMARY,
                focusCancel = true,
            ) { doEvaluate() }
        } else {
            doEvaluate()
        }
    }
}

private fun renderReopenAction(
    row: Container,
    c: SystemicConsensusDto,
    offer: ReopenOffer,
    reload: () -> Unit,
) {
    val reopen =
        row.button(
            gettext("Diskutieren und erneut bewerten (Runde %1 von %2)", c.round + 1, c.maxRounds),
            style = if (offer == ReopenOffer.Primary) ButtonStyle.PRIMARY else ButtonStyle.OUTLINEPRIMARY,
        )
    reopen.onClick {
        confirmDialog(
            title = tr("Neue Runde starten"),
            message = tr("Eine neue Bewertungsrunde beginnt. Das Ergebnis der bisherigen Runde ist danach nicht mehr abrufbar."),
            confirmLabel = tr("Neue Runde starten"),
            confirmStyle = ButtonStyle.PRIMARY,
        ) {
            runGuardedAction(reopen) {
                val result =
                    consensusGuarded(
                        gettext("Die neue Runde konnte nicht gestartet werden. Bitte Ansicht aktualisieren."),
                        onConflict = reload,
                    ) {
                        rpcService<ISystemicConsensusService>().reopenRating(c.id)
                    }
                if (result != null) {
                    notifySuccess(tr("Neue Bewertungsrunde gestartet."))
                    reload()
                }
            }
        }
    }
}

private fun renderConsensusAbortAction(
    row: Container,
    c: SystemicConsensusDto,
    reload: () -> Unit,
) {
    val abort = row.actionButton(ActionIcon.CANCEL, tr("Konsensieren abbrechen"), style = ButtonStyle.OUTLINEDANGER)

    fun doAbort() {
        runGuardedAction(abort) {
            val result =
                consensusGuarded(
                    gettext("Das Konsensieren konnte nicht abgebrochen werden. Bitte Ansicht aktualisieren."),
                    onConflict = reload,
                ) {
                    rpcService<ISystemicConsensusService>().abortSystemicConsensus(c.id)
                }
            if (result != null) {
                notifyInfo(tr("Konsensieren abgebrochen."))
                reload()
            }
        }
    }
    abort.onClick {
        if (c.status == SystemicConsensusStatus.RATING || c.status == SystemicConsensusStatus.CLOSED) {
            confirmWithTypedConfirmationDialog(
                title = tr("Konsensieren abbrechen"),
                message = tr("Ein laufendes Konsensieren wird endgültig abgebrochen. Alle bereits abgegebenen Bewertungen verfallen."),
                expectedText = c.title,
                confirmLabel = tr("Konsensieren abbrechen"),
            ) { doAbort() }
        } else {
            confirmDialog(
                title = tr("Konsensieren abbrechen"),
                message = tr("Das Konsensieren wird abgebrochen. Für diesen Antrag kann danach ein neues eröffnet werden."),
                confirmLabel = tr("Konsensieren abbrechen"),
            ) { doAbort() }
        }
    }
}
