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
import network.lapis.cloud.shared.domain.PublicTextNormalization
import network.lapis.cloud.shared.domain.SystemicConsensusOptionDto
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusRules
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.28 -- the options of a consensus. In COLLECTION every eligible member can add one and remove their own; from RATING on the same
 * list is read-only. The status quo option cannot be removed.
 *
 * V1.9.39 -- the status quo option (P) comes FIRST and carries the plaque "P"; the real options carry the numbers 1..n (rank by position,
 * see [consensusOptionNumbers]). A proposal may carry a rationale (member input, shown untrusted); its proposer or the managers edit it in
 * COLLECTION.
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
    val numbers = consensusOptionNumbers(c.options)
    val list = panel.tag(TAG.OL, className = "list-group list-unstyled")
    consensusOrderedOptions(c.options).forEachIndexed { index, option ->
        list.tag(TAG.LI, className = "list-group-item d-flex flex-wrap align-items-center gap-2") {
            renderOptionRow(
                row = this,
                option = option,
                number = numbers.getValue(option.id),
                index = index,
                removable = canRemoveOption(c, option, me, p),
                editable = canEditRationale(c, option, me, p),
                reload = reload,
            )
        }
    }
    if (c.status == SystemicConsensusStatus.COLLECTION) {
        panel.p(tr("Die Nummern stehen fest, sobald die Optionen festgeschrieben sind.")) { addCssClasses("text-muted small mb-0") }
    }
    if (canAddOption(c, p)) renderAddOptionForm(panel, c.id, reload)
}

private fun renderOptionRow(
    row: Container,
    option: SystemicConsensusOptionDto,
    number: String,
    index: Int,
    removable: Boolean,
    editable: Boolean,
    reload: () -> Unit,
) {
    val text = row.vPanel(spacing = 0) { addCssClasses("flex-grow-1") }
    val head = text.div { addCssClasses("d-flex align-items-baseline gap-2") }
    head.consensusNumberPlaque(number)
    head.consensusNumberSrPrefix(number)
    head.div(consensusOptionText(option)) { addCssClasses("fw-bold text-break") }
    if (option.isStatusQuoOption) {
        text.div(tr("Immer dabei")) { addCssClasses("text-muted small") }
    } else {
        text.div(gettext("Vorgeschlagen von %1", option.createdByDisplayName)) { addCssClasses("text-muted small") }
    }
    renderOptionRationale(text, option, RationaleMode.Full, "sk-opt-why", index)
    val editorHost = text.vPanel(spacing = 2)
    if (editable) {
        val edit =
            row.button(
                if (option.rationale ==
                    null
                ) {
                    tr("Begründung hinzufügen")
                } else {
                    tr("Begründung bearbeiten")
                },
                style = ButtonStyle.OUTLINESECONDARY,
            )
        edit.onClick {
            if (editorHost.getChildren().isEmpty()) renderRationaleEditor(editorHost, option, reload) else editorHost.removeAll()
        }
    }
    if (removable) {
        val remove = row.actionButton(ActionIcon.REMOVE, tr("Entfernen"), style = ButtonStyle.OUTLINEDANGER)
        remove.onClick {
            confirmDialog(
                title = tr("Option entfernen"),
                message = gettext("Die Option \"%1\" wird entfernt.", consensusOptionText(option)),
                confirmLabel = tr("Entfernen"),
                confirmIcon = ActionIcon.REMOVE,
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

/** The one rule of the rationale field -- the same normalisation the server applies (`SystemicConsensusRules`); an empty text is valid (= none). */
private fun rationaleCheck(raw: String): FieldCheck =
    when (SystemicConsensusRules.normalizeRationale(raw)) {
        is PublicTextNormalization.Ok, PublicTextNormalization.Empty -> FieldCheck.Ok
        PublicTextNormalization.TooLong ->
            FieldCheck.Invalid(gettext("Bitte geben Sie höchstens %1 Zeichen ein.", SystemicConsensusRules.MAX_RATIONALE_LENGTH))
        PublicTextNormalization.TooManyLineBreaks -> FieldCheck.Invalid(gettext("Die Begründung hat zu viele Zeilenumbrüche."))
        PublicTextNormalization.ControlChars -> FieldCheck.Invalid(gettext("Die Begründung enthält unzulässige Steuerzeichen."))
    }

/** Inline editor of a proposal's rationale: save, or remove (behind a confirmation). The prefilled text is the SANITIZED one (a forged marker would otherwise be saved back). */
private fun renderRationaleEditor(
    host: Container,
    option: SystemicConsensusOptionDto,
    reload: () -> Unit,
) {
    val form = host.lapisForm()
    val field =
        form.textAreaField(
            label = tr("Begründung"),
            rows = 4,
            value = sanitizeUntrustedI18nText(option.rationale.orEmpty()),
            hint = tr("Warum schlagen Sie das vor? Höchstens 1000 Zeichen."),
            rule = ::rationaleCheck,
            init = { it.setAttribute("maxlength", SystemicConsensusRules.MAX_RATIONALE_LENGTH.toString()) },
        )
    val counter =
        form.panel.div(gettext("%1 von %2 Zeichen", field.value.length, SystemicConsensusRules.MAX_RATIONALE_LENGTH)) {
            addCssClasses("text-muted small")
            setAttribute("aria-live", "polite")
        }
    field.subscribe { raw -> counter.content = gettext("%1 von %2 Zeichen", raw.length, SystemicConsensusRules.MAX_RATIONALE_LENGTH) }
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val remove = if (option.rationale != null) Button(tr("Begründung entfernen"), style = ButtonStyle.OUTLINEDANGER) else null
    form.buttons(primary = save, destructive = remove)
    val failure = gettext("Die Begründung konnte nicht gespeichert werden. Die Ansicht wurde neu geladen.")
    save.onClick {
        form.submit(save) {
            val saved =
                consensusGuarded(failure, onConflict = reload) {
                    rpcService<ISystemicConsensusService>().setOptionRationale(option.id, field.value.trim().ifEmpty { null })
                }
            if (saved != null) {
                notifySuccess(tr("Begründung gespeichert."))
                reload()
            }
        }
    }
    remove?.onClick {
        confirmDialog(
            title = tr("Begründung entfernen"),
            message = tr("Die Begründung wird gelöscht."),
            confirmLabel = tr("Begründung entfernen"),
        ) {
            runGuardedAction(remove) {
                val cleared =
                    consensusGuarded(failure, onConflict = reload) {
                        rpcService<ISystemicConsensusService>().setOptionRationale(option.id, null)
                    }
                if (cleared != null) {
                    notifySuccess(tr("Begründung entfernt."))
                    reload()
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
    val whyField =
        form.textAreaField(
            label = tr("Begründung (optional)"),
            rows = 2,
            hint = tr("Warum schlagen Sie das vor? Höchstens 1000 Zeichen."),
            rule = ::rationaleCheck,
            init = { it.setAttribute("maxlength", SystemicConsensusRules.MAX_RATIONALE_LENGTH.toString()) },
        )
    val add = newActionButton(ActionIcon.ADD, tr("Option hinzufügen"), ButtonStyle.PRIMARY)
    form.buttons(primary = add)
    add.onClick {
        form.submit(add) {
            val added =
                consensusGuarded(gettext("Die Option konnte nicht hinzugefügt werden. Bitte Ansicht aktualisieren."), onConflict = reload) {
                    rpcService<ISystemicConsensusService>().addOption(
                        consensusId,
                        SystemicConsensusOptionInput(label = textField.value.trim(), rationale = whyField.value.trim().ifEmpty { null }),
                    )
                }
            if (added != null) {
                notifySuccess(tr("Option hinzugefügt."))
                reload()
            }
        }
    }
}
