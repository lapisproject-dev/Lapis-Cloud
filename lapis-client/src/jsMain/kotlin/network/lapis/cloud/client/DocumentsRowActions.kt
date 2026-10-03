package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentDto
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.restrictiveness
import network.lapis.cloud.shared.rpc.IDocumentService

// V1.9.49: the row actions of the documents screen (delete a document, change the visibility of a document or a folder) moved out of
// `DocumentsScreen.kt` unchanged -- they only write. "Sichtbarkeit ändern" now uses ActionIcon.ACCESS (was a string icon).

/** Delete action of a document row -- role gate (`canManage`) and confirmation dialog unchanged. */
internal fun Container.renderDocumentDeleteAction(
    document: DocumentDto,
    onDeleted: () -> Unit,
) {
    val deleteButton = tableActionButton(ActionIcon.DELETE, tr("Löschen"), ButtonStyle.OUTLINEDANGER)
    deleteButton.onClick {
        // Audit fix M9: the trigger is disabled while the delete runs, so a second click cannot open a second dialog for a second request.
        if (deleteButton.disabled) return@onClick
        confirmDialog(
            title = tr("Dokument löschen"),
            message =
                gettext(
                    "\"%1\" wirklich löschen? (Soft-Delete -- bisherige Versionen " +
                        "bleiben zu Prüfzwecken erhalten, das Dokument verschwindet aus der Ansicht.)",
                    document.title,
                ),
            confirmLabel = tr("Löschen"),
            confirmIcon = ActionIcon.DELETE,
        ) {
            runGuardedAction(deleteButton) {
                val result = guarded { rpcService<IDocumentService>().deleteDocument(document.id) }
                if (result != null) {
                    notifySuccess(tr("Gelöscht."))
                    onDeleted()
                }
            }
        }
    }
}

/**
 * Welle V1.9.1: "Sichtbarkeit ändern" action of a document row. The modal IS the confirmation --
 * no second `confirmDialog` on top (unlike [renderDocumentDeleteAction]'s soft-delete, changing a
 * level is reversible and the modal already requires an explicit "Speichern" click). Options are
 * [DocumentsAuthzUi.allowedLevels] filtered to the folder's own level and up, same UX-nicety
 * reasoning as [renderDocumentCreation] -- the server (`ConflictException`) remains the authority.
 */
internal fun Container.renderDocumentAccessLevelAction(
    document: DocumentDto,
    folderAccessLevel: DocumentAccessLevel,
    onChanged: () -> Unit,
) {
    val changeButton = tableActionButton(ActionIcon.ACCESS, tr("Sichtbarkeit ändern"))
    changeButton.onClick {
        val modal = Modal(caption = tr("Sichtbarkeit des Dokuments ändern"))
        val form = modal.lapisForm()
        val options =
            DocumentsAuthzUi
                .allowedLevels(AppState.session?.role)
                .filter { it.restrictiveness >= folderAccessLevel.restrictiveness }
                .map { it.name to documentAccessLevelLabel(it) }
        val levelField =
            form.selectField(label = tr("Sichtbarkeit"), options = options, value = document.accessLevel.name, required = true)
        form.finish()
        modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
        saveButton.onClick {
            form.submit(saveButton) {
                val newLevel = DocumentAccessLevel.valueOf(levelField.value)
                // The returned DocumentDto is deliberately unused -- `onChanged()` reloads the whole
                // list, so binding it would only invite someone to render a second, stale source of
                // truth next to the reloaded one (fix round, W8: it used to be an unused `val`).
                guarded { rpcService<IDocumentService>().setDocumentAccessLevel(document.id, newLevel) } ?: return@submit
                modal.hide()
                notifySuccess(tr("Sichtbarkeit geändert."))
                onChanged()
            }
        }
        modal.addButton(saveButton)
        modal.show()
    }
}

/**
 * Welle V1.9.1: "Sichtbarkeit ändern" action of a folder row. Unlike the document-level dialog
 * above, this one ALWAYS shows both cascade sentences (never conditionally) -- an admin choosing a
 * folder's level cannot yet know whether the choice tightens or loosens, and "an einschränkende
 * Wahl schränkt mit ein, eine erweiternde erweitert nichts" is the one fact that must land BEFORE
 * the click, not just in the success toast afterward. Since the fix round (B5) the tighten reaches
 * descendant FOLDERS as well as documents, which is why both sentences name both.
 *
 * The options are NOT filtered against a parent folder's level here (unlike the document dialog's
 * `>=` predicate): this screen only creates top-level folders, so the only way a sub-folder exists
 * at all is a nesting some other tool created, and in that case the server's `ConflictException`
 * ("A subfolder cannot be more visible than its parent folder") is the authority. `guarded` surfaces
 * it to the user as an error toast.
 */
internal fun Container.renderFolderAccessLevelAction(
    folder: DocumentFolderDto,
    onChanged: () -> Unit,
) {
    val changeButton = tableActionButton(ActionIcon.ACCESS, tr("Sichtbarkeit ändern"))
    changeButton.onClick {
        val modal = Modal(caption = tr("Sichtbarkeit des Ordners ändern"))
        modal.div(tr("Eine Einschränkung des Ordners schränkt alle enthaltenen Unterordner und Dokumente mit ein."))
        modal.div(tr("Eine Erweiterung des Ordners erweitert die enthaltenen Unterordner und Dokumente nicht."))
        val form = modal.lapisForm()
        val options = DocumentsAuthzUi.allowedLevels(AppState.session?.role).map { it.name to documentAccessLevelLabel(it) }
        val levelField =
            form.selectField(label = tr("Sichtbarkeit"), options = options, value = folder.accessLevel.name, required = true)
        form.finish()
        modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
        saveButton.onClick {
            form.submit(saveButton) {
                val newLevel = DocumentAccessLevel.valueOf(levelField.value)
                val result = guarded { rpcService<IDocumentService>().setFolderAccessLevel(folder.id, newLevel) } ?: return@submit
                modal.hide()
                if (result.tightenedFolders > 0) {
                    notifySuccess(
                        gettext(
                            "Sichtbarkeit geändert -- %1 Unterordner und %2 Dokumente wurden mit eingeschränkt.",
                            result.tightenedFolders,
                            result.tightenedDocuments,
                        ),
                    )
                } else if (result.tightenedDocuments > 0) {
                    notifySuccess(gettext("Sichtbarkeit geändert -- %1 Dokumente wurden mit eingeschränkt.", result.tightenedDocuments))
                } else {
                    notifySuccess(tr("Sichtbarkeit geändert."))
                }
                onChanged()
            }
        }
        modal.addButton(saveButton)
        modal.show()
    }
}
