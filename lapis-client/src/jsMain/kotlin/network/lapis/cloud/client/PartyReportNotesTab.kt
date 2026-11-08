package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.AmountParse
import network.lapis.cloud.shared.domain.PartgAnnexRules
import network.lapis.cloud.shared.domain.PartgAnnexViolation
import network.lapis.cloud.shared.domain.PartgHoldingDto
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgNoteDto
import network.lapis.cloud.shared.domain.PartgNotesDto
import network.lapis.cloud.shared.domain.PartgTriState
import network.lapis.cloud.shared.domain.parseGermanAmount

internal fun partgNoteBlockTitle(block: PartgNoteBlock): String =
    when (block) {
        PartgNoteBlock.GENERAL_ACCOUNTING -> tr("Allgemeine Angaben zur Rechnungslegung")
        PartgNoteBlock.RELIEF_28 -> tr("Erleichterungen nach § 28 PartG")
        PartgNoteBlock.HOLDINGS -> tr("Beteiligungen")
        PartgNoteBlock.MEDIA_PRODUCTS -> tr("Medienerzeugnisse")
        PartgNoteBlock.REAL_ESTATE_VALUATION -> tr("Bewertung von Grundstücken")
    }

/** What the block is about. Shown only as a placeholder: it is never stored and never reaches the report. */
internal fun partgNoteBlockPlaceholder(block: PartgNoteBlock): String =
    when (block) {
        PartgNoteBlock.GENERAL_ACCOUNTING -> tr("Zum Beispiel: Bilanzierungs- und Bewertungsmethoden")
        PartgNoteBlock.RELIEF_28 -> tr("Zum Beispiel: Begründung der Inanspruchnahme")
        PartgNoteBlock.HOLDINGS -> tr("Zum Beispiel: Erläuterung zu den Beteiligungen")
        PartgNoteBlock.MEDIA_PRODUCTS -> tr("Zum Beispiel: Angaben zu Druckschriften und Medien")
        PartgNoteBlock.REAL_ESTATE_VALUATION -> tr("Zum Beispiel: Wertansatz und Quelle der Bewertung")
    }

private fun triLabel(state: PartgTriState): String =
    when (state) {
        PartgTriState.YES -> tr("Ja")
        PartgTriState.NO -> tr("Nein")
        PartgTriState.UNSPECIFIED -> tr("Nicht angegeben")
    }

private class HoldingRow(
    val name: LapisField,
    val seat: LapisField,
    val share: LapisField,
    val capital: LapisField,
    val result: LapisField,
)

private fun signedCents(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val negative = trimmed.startsWith("-")
    val parsed = parseGermanAmount(trimmed.removePrefix("-")) as? AmountParse.Ok ?: return null
    return if (negative) -parsed.cents else parsed.cents
}

/** The share in hundredths of a percent: "25,5" -> 2550. Blank is not entered. */
private fun basisPoints(text: String): Int? = signedCents(text)?.takeIf { it in 0..10_000 }?.toInt()

/**
 * V1.9.269 "Rechenschaftsbericht R3" -- the tab "Erläuterungen": five blocks of text the organisation can switch into the report, plus the
 * holdings as rows. A placeholder only describes what belongs into a block; it is never saved. Texts are limited to 4,000 characters.
 */
internal fun SimplePanel.renderPartyReportNotes(context: PartyReportContext) {
    p(yearHint(context.year)) { addCssClasses("text-muted small mb-0") }
    lateinit var section: DataSection
    section =
        dataSection<PartgNotesDto>(
            isEmpty = { false },
            load = { guarded { partyReportRpc().getNotes(context.year) } },
            render = { body, notes -> body.renderNotesBody(context, notes) { section.reload() } },
        )
    section.reload()
}

private fun SimplePanel.renderNotesBody(
    context: PartyReportContext,
    notes: PartgNotesDto,
    reload: () -> Unit,
) {
    div(
        tr(
            "Schalten Sie die Bausteine ein, die in den Bericht aufgenommen werden sollen. " +
                "Ein ausgeschalteter Baustein erscheint nicht im Bericht, auch wenn Text darin steht.",
        ),
        className = "small text-muted",
    )
    val form = lapisForm()
    val blockFields = mutableMapOf<PartgNoteBlock, Triple<LapisField, LapisField, Pair<LapisField?, LapisField?>>>()
    val holdingRows = mutableListOf<HoldingRow>()
    lateinit var holdingsHost: Div
    notes.notes.forEach { note ->
        form.panel.div(partgNoteBlockTitle(note.block), className = "fw-semibold mt-2")
        val include = form.checkField(label = tr("In den Bericht aufnehmen"), value = note.included)
        val text =
            form.textAreaField(
                label = gettext("Text: %1", partgNoteBlockTitle(note.block)),
                rows = 4,
                value = note.body,
                hint = gettext("Höchstens %1 Zeichen.", PartgAnnexRules.MAX_NOTE_LENGTH),
                rule = { value ->
                    if (value.length >
                        PartgAnnexRules.MAX_NOTE_LENGTH
                    ) {
                        FieldCheck.Invalid(partgAnnexViolationText(PartgAnnexViolation.FIELD_TOO_LONG))
                    } else {
                        FieldCheck.Ok
                    }
                },
                init = { it.placeholder = resolvedAttributeText(partgNoteBlockPlaceholder(note.block)) },
            )
        var abs1: LapisField? = null
        var abs3: LapisField? = null
        if (note.block == PartgNoteBlock.RELIEF_28) {
            val options = PartgTriState.entries.map { it.name to triLabel(it) }
            abs1 =
                form.selectField(
                    label = tr("§ 28 Absatz 1"),
                    options = options,
                    value = (note.reliefAbs1 ?: PartgTriState.UNSPECIFIED).name,
                )
            abs3 =
                form.selectField(
                    label = tr("§ 28 Absatz 3"),
                    options = options,
                    value = (note.reliefAbs3 ?: PartgTriState.UNSPECIFIED).name,
                )
        }
        blockFields[note.block] = Triple(include, text, abs1 to abs3)
        if (note.block == PartgNoteBlock.HOLDINGS) {
            holdingsHost = form.panel.div()
        }
    }

    fun addHoldingRow(holding: PartgHoldingDto?) {
        val host = holdingsHost.div(className = "border rounded p-2 mb-2") { setAttribute("data-partg-holding", "1") }
        val row =
            HoldingRow(
                name =
                    form.textField(label = tr("Unternehmen"), value = holding?.name, host = host, rule = { text ->
                        holdingNameRule(text)
                    }),
                seat = form.textField(label = tr("Sitz"), value = holding?.seat, host = host),
                share =
                    form.textField(
                        label = tr("Anteil in %"),
                        value = holding?.shareBasisPoints?.let { partgInputText(it.toLong()) },
                        host = host,
                        rule = { text ->
                            if (text.isBlank() ||
                                basisPoints(text) != null
                            ) {
                                FieldCheck.Ok
                            } else {
                                FieldCheck.Invalid(gettext("Bitte einen Anteil zwischen 0 und 100 eingeben."))
                            }
                        },
                    ),
                capital =
                    form.textField(
                        label = tr("Kapital (€)"),
                        value = holding?.capitalCents?.let(::partgInputText),
                        host = host,
                        rule = { text ->
                            if (text.isBlank() ||
                                signedCents(text)?.let { it >= 0 } == true
                            ) {
                                FieldCheck.Ok
                            } else {
                                FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1.234,56 eingeben."))
                            }
                        },
                    ),
                result =
                    form.textField(
                        label = tr("Ergebnis (€)"),
                        value = holding?.resultCents?.let { if (it < 0) "-" + partgInputText(-it) else partgInputText(it) },
                        host = host,
                        rule = { text ->
                            if (text.isBlank() ||
                                signedCents(text) != null
                            ) {
                                FieldCheck.Ok
                            } else {
                                FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1.234,56 eingeben."))
                            }
                        },
                    ),
            )
        holdingRows += row
        host.actionButton(ActionIcon.REMOVE, tr("Entfernen"), small = true) {
            onClick {
                holdingRows.remove(row)
                listOf(row.name, row.seat, row.share, row.capital, row.result).forEach { form.unregister(it) }
                holdingsHost.remove(host)
            }
        }
    }
    notes.holdings.forEach { addHoldingRow(it) }
    val addHolding = newActionButton(ActionIcon.ADD, tr("Beteiligung hinzufügen"))
    addHolding.onClick {
        if (holdingRows.size >= PartgAnnexRules.MAX_HOLDINGS) {
            notifyError(tr("Es sind nicht mehr Einträge möglich."))
        } else {
            addHoldingRow(null)
        }
    }
    holdingsHost.add(addHolding)

    fun currentNotes(): List<PartgNoteDto> =
        PartgNoteBlock.entries.map { block ->
            val (include, text, relief) = blockFields.getValue(block)
            PartgNoteDto(
                block = block,
                included = include.value == "true",
                body = text.value.ifBlank { null },
                reliefAbs1 = relief.first?.let { PartgTriState.valueOf(it.value) },
                reliefAbs3 = relief.second?.let { PartgTriState.valueOf(it.value) },
            )
        }

    fun currentHoldings(): List<PartgHoldingDto> =
        holdingRows.map { r ->
            PartgHoldingDto(
                name = r.name.value,
                seat = r.seat.value.ifBlank { null },
                shareBasisPoints = basisPoints(r.share.value),
                capitalCents = signedCents(r.capital.value),
                resultCents = signedCents(r.result.value),
            )
        }

    val initial = form.fields.map { it.value }
    context.hasUnsavedChanges = { form.fields.map { it.value } != initial }
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val discard = newActionButton(ActionIcon.UNDO, tr("Verwerfen"))
    form.buttons(primary = save, cancel = discard)
    discard.onClick { reload() }
    save.onClick {
        form.submit(save) {
            val toSave = currentNotes()
            val holdings = currentHoldings()
            PartgAnnexRules.validateNotes(toSave, holdings)?.let {
                form.showFormError(partgAnnexViolationText(it))
                return@submit
            }
            val result = guarded { partyReportRpc().saveNotes(notes.year, notes.revision, toSave, holdings) }
            if (result != null) {
                context.hasUnsavedChanges = { false }
                notifySuccess(tr("Gespeichert."))
                PartyReportSaveSignal.saved()
                reload()
            } else {
                form.showFormError(
                    gettext(
                        "Das Speichern ist nicht gelungen, oder die Daten wurden inzwischen von jemand anderem geändert. " +
                            "Mit „Neu laden“ sehen Sie den aktuellen Stand; Ihre Eingaben gehen dabei verloren.",
                    ),
                )
            }
        }
    }
    actionGroup { actionButton(ActionIcon.REFRESH, tr("Neu laden")) { onClick { reload() } } }
}

private fun holdingNameRule(text: String): FieldCheck {
    val trimmed = text.trim()
    return when {
        trimmed.isEmpty() -> FieldCheck.Invalid(gettext("Bitte einen Namen angeben."))
        trimmed.length > PartgAnnexRules.NAME_MAX -> FieldCheck.Invalid(gettext("Der Text ist zu lang."))
        PartgAnnexRules.containsControl(trimmed) -> FieldCheck.Invalid(gettext("Bitte keine Steuerzeichen verwenden."))
        else -> FieldCheck.Ok
    }
}
