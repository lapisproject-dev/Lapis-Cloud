package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.form.check.radioGroup
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.MotionDto
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.28 -- "Konsensieren eröffnen", the entry into the consensus screens, offered in the resolution section of a scheduled motion next to
 * the other ways to decide it, and the gate that hides those other ways while a consensus owns the motion (the server refuses an election
 * or a vote then anyway; the screen offers less than the server allows, never more).
 */

private const val SECRET_VALUE = "SECRET"
private const val OPEN_VALUE = "OPEN"

/**
 * While a consensus is running (collecting, rating or closed and awaiting its evaluation), or a binding one exists, it owns the motion: say so and offer the way to it,
 * and return `true` so the caller does not offer the other ways. A finished advisory consensus is only an opinion poll: it gets a link to
 * its result and returns `false`, the motion stays decidable.
 */
internal fun renderConsensusDecisionGate(
    panel: SimplePanel,
    consensuses: List<SystemicConsensusDto>,
): Boolean {
    val owning =
        consensuses.firstOrNull {
            it.status != SystemicConsensusStatus.ABORTED &&
                (
                    it.status == SystemicConsensusStatus.COLLECTION ||
                        it.status == SystemicConsensusStatus.RATING ||
                        it.status == SystemicConsensusStatus.CLOSED ||
                        it.bindingness == SystemicConsensusBindingness.BINDING
                )
        }
    if (owning != null) {
        panel.p(tr("Zu diesem Antrag läuft ein Konsensieren.")) { addCssClasses("alert alert-info mb-0") }
        panel.button(tr("Zum Konsensieren"), style = ButtonStyle.PRIMARY).onClick { navigateTo("/consensus/${owning.id}") }
        return true
    }
    val poll = consensuses.firstOrNull { it.status != SystemicConsensusStatus.ABORTED }
    if (poll != null) {
        panel.actionButton(ActionIcon.VIEW, tr("Ergebnis der Sondierung ansehen"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            navigateTo("/consensus/${poll.id}")
        }
    }
    return false
}

internal fun renderOpenConsensusForm(
    panel: SimplePanel,
    motion: MotionDto,
    onConflict: () -> Unit = {},
    onOpened: (SystemicConsensusDto) -> Unit,
) {
    val holder = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    holder.p(tr("Konsensieren eröffnen")) { addCssClass("fw-bold") }
    holder.p(
        tr(
            "Beim Konsensieren bewertet jedes stimmberechtigte Mitglied jede Option mit einem Widerstand. " +
                "Die Option mit dem geringsten Widerstand liegt vorn.",
        ),
    ) { addCssClasses("text-muted small mb-0") }
    val form = holder.lapisForm()

    val secrecyRadio =
        form.panel.radioGroup(
            options = listOf(SECRET_VALUE to consensusSecrecyLabel(true), OPEN_VALUE to consensusSecrecyLabel(false)),
            value = SECRET_VALUE,
            label = tr("Art der Bewertung"),
        )
    form.register(control = secrecyRadio, label = tr("Art der Bewertung"), required = true)
    val secrecyHint = form.panel.div("") { addCssClasses("text-muted small") }

    val bindingRadio =
        form.panel.radioGroup(
            options =
                listOf(
                    SystemicConsensusBindingness.ADVISORY.name to consensusBindingnessLabel(SystemicConsensusBindingness.ADVISORY),
                    SystemicConsensusBindingness.BINDING.name to consensusBindingnessLabel(SystemicConsensusBindingness.BINDING),
                ),
            value = SystemicConsensusBindingness.ADVISORY.name,
            label = tr("Verbindlichkeit"),
        )
    form.register(control = bindingRadio, label = tr("Verbindlichkeit"), required = true)
    val advisoryHint =
        form.panel.div(tr("Sondierung: Das Ergebnis ist eine Orientierung und entscheidet den Antrag nicht.")) {
            addCssClasses("text-muted small")
        }
    val bindingBox = form.panel.vPanel(spacing = 4)
    bindingBox.div(tr("Beschluss: Das Ergebnis wird als Beschluss protokolliert und entscheidet den Antrag.")) {
        addCssClasses("alert alert-warning mb-0")
    }
    bindingBox.div(tr("Gewinnt die Passivlösung, gilt der Antrag als abgelehnt.")) { addCssClasses("text-muted small") }
    bindingBox.div(
        tr("Bei einem Beschluss gibt es keine Wiederabstimmung. Für eine Diskussionsrunde zuerst als Sondierung konsensieren."),
    ) {
        addCssClasses("text-muted small")
    }
    val understood = form.checkField(label = tr("Verstanden"), host = bindingBox)

    fun isBinding(): Boolean = bindingRadio.value == SystemicConsensusBindingness.BINDING.name

    fun isSecret(): Boolean = secrecyRadio.value != OPEN_VALUE

    fun update() {
        secrecyHint.content =
            if (isSecret()) {
                gettext("Anonym: Es wird gespeichert, dass jemand bewertet hat, aber nicht, wie.")
            } else {
                gettext("Offen: Bewertungen werden mit Namen gespeichert und sind für alle Mitglieder sichtbar.")
            }
        if (isBinding()) {
            bindingBox.show()
            advisoryHint.hide()
        } else {
            bindingBox.hide()
            advisoryHint.show()
        }
    }
    secrecyRadio.subscribe { update() }
    bindingRadio.subscribe { update() }
    form.crossFieldRule(focusOn = understood.control as? Widget) {
        if (isBinding() && understood.value != "true") {
            FieldCheck.Invalid(gettext("Bitte bestätigen Sie, dass Sie das verstanden haben."))
        } else {
            FieldCheck.Ok
        }
    }
    update()

    val openButton = Button(tr("Konsensieren eröffnen"), style = ButtonStyle.OUTLINEPRIMARY)
    form.buttons(primary = openButton)
    openButton.onClick {
        form.submit(openButton) {
            val input =
                SystemicConsensusOpenInput(
                    motionId = motion.id,
                    secret = isSecret(),
                    bindingness = if (isBinding()) SystemicConsensusBindingness.BINDING else SystemicConsensusBindingness.ADVISORY,
                )
            val opened =
                consensusGuarded(
                    conflictMessage = gettext("Der Antrag wurde inzwischen anders bearbeitet. Die Ansicht wurde aktualisiert."),
                    onConflict = onConflict,
                ) {
                    rpcService<ISystemicConsensusService>().openSystemicConsensus(input)
                }
            if (opened != null) {
                notifySuccess(tr("Konsensieren eröffnet."))
                onOpened(opened)
            }
        }
    }
}
