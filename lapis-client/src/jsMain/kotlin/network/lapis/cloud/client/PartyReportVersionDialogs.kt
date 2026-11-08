package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.text.Text
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.PartgDeadlineDto
import network.lapis.cloud.shared.domain.PartgDeadlineRules
import network.lapis.cloud.shared.domain.PartgDeclarationTemplate
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgUnitDto
import network.lapis.cloud.shared.domain.PartgVersionDto
import network.lapis.cloud.shared.domain.PartgVersionRules

// V1.9.291 (R6) -- the dialogs of the tab "Versionen und Freigabe". They are Bootstrap modals: the focus is trapped inside and returned to the
// button that opened them when they close. The server stays the authority for every rule (comment, state, checksum, typed number).

/** "Version festhalten": scope and an optional short comment; says what the export of the kept version will carry. */
internal fun openKeepVersionDialog(
    context: PartyReportContext,
    units: List<PartgUnitDto>,
    onKept: (PartgVersionDto) -> Unit,
) {
    val modal = Modal(caption = tr("Version festhalten"))
    modal.p(
        tr(
            "Der aktuelle Arbeitsstand wird als nummerierte Version gespeichert. " +
                "Eine festgehaltene Version lässt sich nicht mehr ändern. " +
                "Sie enthält die Ausweise B und C mit Namen und Anschriften Dritter.",
        ),
    ) { addCssClasses("small") }
    val form = modal.lapisForm()
    val scopeField =
        form.selectField(
            label = tr("Umfang"),
            options = listOf(PartgReportScope.ALL_KEY to tr("Gesamtpartei")) + units.map { it.unitKey to unitLabel(it) },
            value = PartgReportScope.ALL_KEY,
            required = true,
        )
    val commentField =
        form.textField(
            label = tr("Kommentar (freiwillig)"),
            hint =
                tr(
                    "Höchstens 500 Zeichen, eine Zeile. Bitte keine Namen von Personen eintragen; der Kommentar steht in der Versionsliste.",
                ),
            rule = { text ->
                if (PartgVersionRules
                        .checkComment(
                            text,
                        ).ok
                ) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(partgCommentInvalidText())
                }
            },
        )
    modal.div(
        tr(
            "Auf jeder Seite des Exports steht: Version, Tag des Festhaltens, Prüfsumme und der Hinweis, dass der Entwurf nicht eingereicht ist.",
        ),
    ) { addCssClasses("small text-muted mt-2") }
    form.finish()

    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val keep = newActionButton(ActionIcon.KEEP_VERSION, tr("Festhalten"), ButtonStyle.PRIMARY)
    keep.onClick {
        form.submit(keep) {
            val kept =
                guarded {
                    partyReportRpc().freezeVersion(
                        year = context.year,
                        scopeKey = scopeField.value,
                        comment = commentField.value.trim().takeIf { it.isNotEmpty() },
                    )
                }
            if (kept == null) {
                form.showFormError(saveFailedText())
                return@submit
            }
            modal.hide()
            onKept(kept)
        }
    }
    modal.addButton(keep)
    modal.show()
}

/**
 * "Versichern": the fixed text of the template (never editable), the checksum, and a required tick. The button stays inactive until the box is ticked.
 * The text says it is an internal note and no signature.
 */
internal fun openDeclareDialog(
    version: PartgVersionDto,
    onDone: (PartgVersionDto) -> Unit,
) {
    val modal = Modal(caption = gettext("Version %1 versichern", version.versionNumber.toString()))
    modal.div(tr("Entwurf einer Versicherung. Rechtlich nicht geprüft, keine qualifizierte elektronische Signatur.")) {
        addCssClasses("alert alert-warning small")
        setAttribute("role", "note")
    }
    modal.div(
        PartgDeclarationTemplate
            .text(
                code = PartgDeclarationTemplate.CODE,
                versionNumber = version.versionNumber,
                year = version.year,
            ).orEmpty(),
    ) {
        addCssClasses("border rounded p-2 mb-2")
        setAttribute("data-partg-declaration-text", "1")
    }
    modal.div(gettext("Prüfsumme: %1", version.contentHash)) { addCssClasses("small text-muted text-break") }
    val box = CheckBox(value = false, label = tr("Ich habe den Inhalt dieser Version geprüft und versichere ihn."))
    box.id = "partg-declare-check"
    modal.add(box)
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val declare = newActionButton(ActionIcon.DECLARE, tr("Versichern"), ButtonStyle.PRIMARY)
    declare.id = "partg-declare-confirm"
    declare.disabled = true
    declare.setAttribute("aria-disabled", "true")
    box.subscribe { checked ->
        val on = checked == true
        declare.disabled = !on
        declare.setAttribute("aria-disabled", (!on).toString())
    }
    declare.onClick {
        if (!box.value) return@onClick
        declare.disabled = true
        AppScope.launch {
            val result = guarded { partyReportRpc().declareVersion(version.id, version.contentHash, reviewed = true) }
            if (result == null) {
                declare.disabled = !box.value
            } else {
                modal.hide()
                onDone(result)
            }
        }
    }
    modal.addButton(declare)
    modal.show()
}

/** "Final setzen": irreversible; the person types the version number. Says plainly that this is an internal mark and no filing. */
internal fun openFinalizeDialog(
    version: PartgVersionDto,
    onDone: (PartgVersionDto) -> Unit,
) {
    val modal = Modal(caption = gettext("Version %1 intern final setzen", version.versionNumber.toString()))
    modal.div(
        tr(
            "Das lässt sich nicht rückgängig machen. Die Version gilt danach intern als final. " +
                "Das ist ein interner Vermerk: Der Bericht ist damit nicht eingereicht.",
        ),
    ) {
        addCssClasses("alert alert-danger small")
        setAttribute("role", "alert")
    }
    val typed = Text(label = gettext("Tippen Sie zur Bestätigung die Nummer %1 ein", version.versionNumber.toString()))
    typed.id = "partg-finalize-number"
    typed.setAttribute("inputmode", "numeric")
    typed.setAttribute("autocomplete", "off")
    modal.add(typed)
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val finalize = newActionButton(ActionIcon.FINALIZE, tr("Final setzen"), ButtonStyle.DANGER)
    finalize.id = "partg-finalize-confirm"
    finalize.disabled = true
    finalize.setAttribute("aria-disabled", "true")

    fun refresh() {
        val on = partgTypedNumberMatches(typed = typed.value.orEmpty(), version = version)
        finalize.disabled = !on
        finalize.setAttribute("aria-disabled", (!on).toString())
    }
    typed.setEventListener<Text> { input = { refresh() } }
    typed.subscribe { refresh() }
    finalize.onClick {
        val number =
            typed.value
                .orEmpty()
                .trim()
                .toIntOrNull() ?: return@onClick
        if (!partgTypedNumberMatches(typed = typed.value.orEmpty(), version = version)) return@onClick
        finalize.disabled = true
        AppScope.launch {
            val result = guarded { partyReportRpc().finalizeVersion(version.id, number, version.contentHash) }
            if (result == null) {
                refresh()
            } else {
                modal.hide()
                onDone(result)
            }
        }
    }
    modal.addButton(finalize)
    modal.show()
}

/** Confirms or changes the deadline of the year; the date must lie in the range the server also checks. */
internal fun openDeadlineDialog(
    context: PartyReportContext,
    current: PartgDeadlineDto,
    onDone: (PartgDeadlineDto) -> Unit,
) {
    val modal = Modal(caption = tr("Frist bestätigen"))
    modal.p(
        tr(
            "Der Vorschlag beruht auf der Annahme, dass der Bericht bis zum 30. September des Folgejahres einzureichen ist. " +
                "Das ist nicht rechtlich geprüft. Tragen Sie hier die Frist ein, die für Sie gilt.",
        ),
    ) { addCssClasses("small") }
    val form = modal.lapisForm()
    val dateField =
        form.localDateField(
            label = tr("Frist"),
            value = current.deadline,
            required = true,
            rule = { date ->
                if (PartgDeadlineRules.valid(year = context.year, deadline = date)) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(
                        gettext("Die Frist muss zwischen %1 und %2 liegen.", (context.year + 1).toString(), (context.year + 3).toString()),
                    )
                }
            },
            min = LocalDate(context.year + 1, 1, 1),
            max = LocalDate(context.year + 3, 12, 31),
        )
    form.finish()
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    save.onClick {
        form.submit(save) {
            val date = dateField.localDateOrNull() ?: return@submit
            val saved = guarded { partyReportRpc().confirmDeadline(context.year, date.toString()) }
            if (saved == null) {
                form.showFormError(saveFailedText())
                return@submit
            }
            modal.hide()
            onDone(saved)
        }
    }
    modal.addButton(save)
    modal.show()
}
