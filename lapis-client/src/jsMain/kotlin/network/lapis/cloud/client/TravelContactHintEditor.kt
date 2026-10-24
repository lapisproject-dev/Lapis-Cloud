package network.lapis.cloud.client

import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.TravelContactHintFormat
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/*
 * V1.9.199: the editor of the contact hint ("Mehr erfahren") on the travel-expense settings page. Since V1.9.202 the editor itself is the generic
 * [markdownTextEditor] (also used by the tender form); this file only carries the travel-specific parts: the label, the limit, the legacy hint,
 * the preview RPC (`previewTravelContactHint`) and the format the save has to send.
 */

/** Handle on the editor: the field (for the value) and the format the save has to send. */
internal class TravelContactHintEditor(
    val field: LapisField,
    private val handle: MarkdownTextEditorHandle,
    private val loadedFormat: TravelContactHintFormat,
) {
    /**
     * MARKDOWN as soon as the administration changed the text (the new text is written as Markdown), otherwise the stored format: saving the
     * other fields of the page must NOT silently turn a legacy plain text into Markdown.
     */
    fun formatToSend(): TravelContactHintFormat = if (handle.markdownToSend()) TravelContactHintFormat.MARKDOWN else loadedFormat
}

internal fun LapisForm.travelContactHintEditor(
    current: String,
    format: TravelContactHintFormat,
): TravelContactHintEditor {
    val handle =
        markdownTextEditor(
            label = tr("Hinweistext (Mehr erfahren)"),
            current = current,
            isMarkdown = format == TravelContactHintFormat.MARKDOWN,
            maxLength = TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH,
            legacyHint =
                tr(
                    "Der bisherige Text ist einfacher Text. Sobald Sie ihn ändern, wird er als Markdown gespeichert: Absätze trennt eine " +
                        "Leerzeile, Aufzählungen beginnen mit \"- \".",
                ),
            preview = { rpcService<ITravelExpenseService>().previewTravelContactHint(it) },
        )
    return TravelContactHintEditor(field = handle.field, handle = handle, loadedFormat = format)
}
