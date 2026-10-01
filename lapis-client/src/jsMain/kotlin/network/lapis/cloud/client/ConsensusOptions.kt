package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.28 -- the options of a consensus. In COLLECTION every eligible member can add one and remove their own; from RATING on the same
 * list is read-only. The status quo option is always last and cannot be removed.
 */

/** `systemic_consensus_option.label` is VARCHAR(200); the server refuses anything else, the client says so before sending. */
private const val MAX_OPTION_TEXT = 200

internal fun renderConsensusOptions(
    panel: SimplePanel,
    data: ConsensusDetailData,
    me: String,
    reload: () -> Unit,
) {
    val c = data.consensus
    val p = data.participation
    panel.h2(tr("Optionen")) { addCssClass("h5") }
    if (c.status == SystemicConsensusStatus.COLLECTION) {
        panel.p(
            tr("Machen Sie Vorschläge: Jede Option, die hier steht, wird später von allen bewertet."),
        ) { addCssClasses("text-muted small") }
    }
    if (c.tooManyOptionsWarning) {
        panel.p(tr("Es gibt sehr viele Optionen. Bitte prüfen Sie, ob sich ähnliche Vorschläge zusammenfassen lassen.")) {
            addCssClasses("alert alert-warning mb-0")
        }
    }
    // The status quo option stands at position 0 on the server, but is shown LAST: the real proposals come first.
    val ordered = c.options.filterNot { it.isStatusQuoOption }.sortedBy { it.position } + c.options.filter { it.isStatusQuoOption }
    val list = panel.tag(TAG.OL, className = "list-group list-group-numbered")
    ordered.forEach { option ->
        list.tag(TAG.LI, className = "list-group-item d-flex flex-wrap align-items-center gap-2") {
            renderOptionRow(this, option, canRemoveOption(c, option, me, p), reload)
        }
    }
    if (canAddOption(c, p)) renderAddOptionForm(panel, c.id, reload)
}

private fun renderOptionRow(
    row: Container,
    option: SystemicConsensusOptionDto,
    removable: Boolean,
    reload: () -> Unit,
) {
    val text = row.vPanel(spacing = 0) { addCssClasses("flex-grow-1") }
    text.div(consensusOptionText(option)) { addCssClasses("fw-bold text-break") }
    if (!option.isStatusQuoOption) {
        text.div(gettext("Vorgeschlagen von %1", option.createdByDisplayName)) { addCssClasses("text-muted small") }
    }
    if (option.isStatusQuoOption) row.typeBadge(tr("Immer dabei"), "secondary")
    if (removable) {
        val remove = row.button(tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
        remove.onClick {
            confirmDialog(
                title = tr("Option entfernen"),
                message = gettext("Die Option \"%1\" wird entfernt.", consensusOptionText(option)),
                confirmLabel = tr("Entfernen"),
            ) {
                runGuardedAction(remove) {
                    val result =
                        consensusGuarded(
                            gettext("Die Option konnte nicht entfernt werden. Bitte Ansicht aktualisieren."),
                            onConflict = reload,
                        ) {
                            rpcService<ISystemicConsensusService>().removeOption(option.id)
                        }
                    if (result != null) {
                        notifyInfo(tr("Option entfernt."))
                        reload()
                    }
                }
            }
        }
    }
}

private fun renderAddOptionForm(
    panel: SimplePanel,
    consensusId: String,
    reload: () -> Unit,
) {
    val holder = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    holder.p(tr("Option hinzufügen")) { addCssClass("fw-bold") }
    val form = holder.lapisForm()
    val textField =
        form.textField(
            label = tr("Neue Option"),
            required = true,
            hint = tr("Was soll bewertet werden? Höchstens 200 Zeichen."),
            rule = { raw ->
                if (raw.trim().length in 1..MAX_OPTION_TEXT) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(gettext("Bitte geben Sie 1 bis %1 Zeichen ein.", MAX_OPTION_TEXT))
                }
            },
        )
    val counter =
        form.panel.div(gettext("%1 von %2 Zeichen", 0, MAX_OPTION_TEXT)) {
            addCssClasses("text-muted small")
            setAttribute("aria-live", "polite")
        }
    textField.subscribe { raw -> counter.content = gettext("%1 von %2 Zeichen", raw.length, MAX_OPTION_TEXT) }
    val add = Button(tr("Option hinzufügen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = add)
    add.onClick {
        form.submit(add) {
            val added =
                consensusGuarded(gettext("Die Option konnte nicht hinzugefügt werden. Bitte Ansicht aktualisieren."), onConflict = reload) {
                    rpcService<ISystemicConsensusService>().addOption(
                        consensusId,
                        SystemicConsensusOptionInput(label = textField.value.trim()),
                    )
                }
            if (added != null) {
                notifySuccess(tr("Option hinzugefügt."))
                reload()
            }
        }
    }
}
