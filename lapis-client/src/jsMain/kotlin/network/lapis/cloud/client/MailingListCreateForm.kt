package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.rpc.IMailingService

/**
 * V1.9.49 (R36B): inline mini-form -- name (required) + description (optional), built into the host of a collapsible create form
 * ([collapsibleCreateForm]) behind the title-row button "Neue Mailingliste" of the admin section. On success, both the self-service
 * panel above (subscriber counts changed) and the "Liste verwalten" selector below need a refresh; [onCreated] lets the caller
 * re-render the selector so the new list is immediately pickable without a full page reload. The old bold title line is gone: the
 * button carries the meaning.
 */
internal fun SimplePanel.renderCreateMailingListForm(
    refreshSelfService: () -> Unit,
    collapse: ((Boolean) -> Unit)? = null,
    onCreated: (newListId: String) -> Unit,
): FormSnapshot {
    val panel = vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    // Formular-Grammatik (V1.4.29, W4b): Name ist Pflicht, Beschreibung optional => Fall (a).
    val form = panel.lapisForm()
    val nameField = form.textField(label = tr("Name"), required = true)
    val descriptionField = form.textField(label = tr("Beschreibung"))
    val createButton = newActionButton(ActionIcon.ADD, tr("Anlegen"), ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapse?.let { collapseCancelButton(it) })

    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val description = descriptionField.value.trim().takeIf { it.isNotBlank() }
            val result = guarded { rpcService<IMailingService>().createMailingList(name, description) }
            if (result != null) {
                notifySuccess(gettext("Mailingliste \"%1\" wurde angelegt.", name))
                collapse?.invoke(true)
                refreshSelfService()
                onCreated(result.id)
            }
        }
    }
    return form.snapshot()
}
