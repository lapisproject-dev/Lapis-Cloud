package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.RegionalChapterRules
import network.lapis.cloud.shared.rpc.IRegionalChapterService

/**
 * V1.9.48 (R36B): the chapter creation form, built into the host of a collapsible create form ([collapsibleCreateForm]) behind the
 * page header button "Landesverband anlegen" (ADMIN only, see `NavVisibility.showsRegionalChapterStructure`). At the chapter limit the
 * button stays active and the opened form says so with the existing hint; its submit button is disabled.
 */
internal fun renderChapterCreationForm(
    root: SimplePanel,
    atLimit: Boolean,
    collapse: ((Boolean) -> Unit)? = null,
    onCreated: suspend () -> Unit,
): FormSnapshot {
    val form = root.lapisForm()
    val nameField =
        form.textField(
            label = tr("Name des Landesverbands"),
            required = true,
            rule = { chapterNameCheck(it) },
        )
    if (atLimit) {
        form.panel.p(gettext("Höchstens %1 Landesverbände möglich.", RegionalChapterRules.MAX_CHAPTERS)) {
            addCssClasses("text-muted small")
        }
    }
    val createButton = newActionButton(ActionIcon.ADD, tr("Landesverband anlegen"), ButtonStyle.PRIMARY)
    createButton.disabled = atLimit
    form.buttons(primary = createButton, cancel = collapse?.let { collapseCancelButton(it) })
    createButton.onClick {
        form.submit(createButton) {
            val name = RegionalChapterRules.normalizeName(nameField.value)
            val result =
                regionalChapterGuarded(onNameTaken = { nameField.showError(tr("Ein Landesverband mit diesem Namen existiert bereits.")) }) {
                    rpcService<IRegionalChapterService>().createChapter(name)
                }
            if (result != null) {
                notifySuccess(gettext("Landesverband \"%1\" wurde angelegt.", result.name))
                collapse?.invoke(true)
                onCreated()
            }
        }
    }
    return form.snapshot()
}
