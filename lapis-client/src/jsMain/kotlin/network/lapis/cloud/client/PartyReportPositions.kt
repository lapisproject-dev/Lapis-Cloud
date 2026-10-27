package network.lapis.cloud.client

import io.kvision.core.onEvent
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.table.cell
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositionDto

private const val HINT_MAX = 500

private val POSITION_HEADERS =
    listOf(
        TableHeader(title = tr("Nr.")),
        TableHeader(title = tr("Position")),
        TableHeader(title = tr("Art")),
        TableHeader(title = tr("Gesetz")),
        TableHeader(title = tr("Hinweis der Organisation")),
        TableHeader(title = tr("Aktion")),
    )

/** The positions tab: the fixed catalogue of the report with the organisation's own hint per position (shown in the entry tab). */
internal fun SimplePanel.renderPartyReportPositions() {
    div(
        tr(
            "Die Positionen und ihre Reihenfolge sind gesetzlich vorgegeben (§ 24 PartG) und nicht änderbar. " +
                "Zu jeder Position können Sie einen eigenen Hinweis hinterlegen, der bei der Erfassung angezeigt wird.",
        ),
    ) { addCssClasses("text-muted small") }
    lateinit var section: DataSection
    section =
        dataSection<List<PartgPositionDto>>(
            isEmpty = { it.isEmpty() },
            load = { guarded { partyReportRpc().listPositions() } },
            render = { body, positions ->
                val table = body.standardTable(POSITION_HEADERS)
                positions.forEach { position ->
                    table.row {
                        textCell(position.numberLabel)
                        textCell(partgLabel(position.code))
                        textCell(if (position.kind == PartgKind.LEAF) tr("Eingabe") else tr("Summe"))
                        textCell(position.legalRef)
                        textCell(position.orgHint.orEmpty())
                        cell {
                            if (position.kind == PartgKind.LEAF) {
                                actionButton(ActionIcon.EDIT, tr("Hinweis ändern"), small = true) {
                                    onClick { openHintDialog(position) { section.reload() } }
                                }
                            }
                        }
                    }
                }
            },
        )
    section.reload()
}

private fun openHintDialog(
    position: PartgPositionDto,
    onSaved: () -> Unit,
) {
    val modal = Modal(caption = gettext("Hinweis zu \"%1\"", partgLabel(position.code)))
    val field = modal.textArea(value = position.orgHint.orEmpty(), rows = 5, label = tr("Hinweis (höchstens 500 Zeichen)"))
    val counter = modal.div(className = "small text-muted")

    fun paint() {
        val length = (field.value ?: "").length
        counter.content = gettext("%1 von %2 Zeichen", length, HINT_MAX)
    }
    paint()
    field.onEvent { input = { paint() } }
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
    val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    save.onClick {
        val text = field.value.orEmpty()
        if (text.length > HINT_MAX) {
            notifyError(tr("Der Hinweis ist zu lang."))
            return@onClick
        }
        save.disabled = true
        AppScope.launch {
            val result = guarded { partyReportRpc().updatePositionHint(position.code, text.ifBlank { null }) }
            if (result != null) {
                modal.hide()
                notifySuccess(tr("Hinweis gespeichert."))
                onSaved()
            } else {
                save.disabled = false
            }
        }
    }
    modal.addButton(cancel)
    modal.addButton(save)
    modal.show()
}
