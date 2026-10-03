package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.restrictiveness
import network.lapis.cloud.shared.rpc.IDocumentService

// V1.9.49 (R36B): the folder and the document creation forms, built into the hosts of the collapsible create forms behind the title-row
// buttons "Neuer Ordner" / "Neues Dokument" of `DocumentsScreen.kt`. They only write (createFolder, createDocument).

/**
 * R24 (W4d): migrated to the form grammar. Welle V1.9.1, fix round (W1): a "Sichtbarkeit" select was
 * added, mirroring [renderDocumentCreation]'s own. Without it every new folder was PUBLIC_MEMBERS by
 * force -- and a folder NAME is frequently the sensitive part ("Kündigungen Q3"), visible to every
 * member from the moment of creation until someone remembered to change the level in a second step.
 * Options come from [DocumentsAuthzUi.allowedLevels], the same single source of truth the document
 * dialog and both "Sichtbarkeit ändern" modals use; the server's `canAccessDocumentAtLevel` check in
 * `createFolder` remains the real authority.
 *
 * No parent-folder-derived filtering here (unlike [renderDocumentCreation]'s `>=` predicate): this
 * screen only ever creates TOP-LEVEL folders (`parentFolderId = null`), so there is no parent level
 * to be at least as restrictive as.
 *
 * Folgepunkt zu V1.9.1: the preselected value is [DocumentsAuthzUi.defaultCreationLevel] -- the most
 * restrictive level [role] is allowed to pick -- instead of always PUBLIC_MEMBERS. Rationale is the
 * same as the KDoc above: a folder name is often already the sensitive part, so the safer default is
 * the tightest one the creating role can choose, not the loosest. After every successful creation the
 * select is reset back to this SAME role default (not left on whatever was last chosen) -- otherwise a
 * BOARD member who once picked PUBLIC_MEMBERS for a genuinely public folder would silently keep
 * proposing PUBLIC_MEMBERS for every folder created afterward in the same session.
 */
internal fun SimplePanel.renderFolderCreation(
    role: AccountRole?,
    collapse: (Boolean) -> Unit,
    onCreated: () -> Unit,
): FormSnapshot {
    val form = lapisForm()
    val nameField = form.textField(label = tr("Neuer Ordnername"), required = true)
    val accessLevelOptions = DocumentsAuthzUi.allowedLevels(role).map { it.name to documentAccessLevelLabel(it) }
    val defaultLevel = DocumentsAuthzUi.defaultCreationLevel(role)
    val accessField =
        form.selectField(
            label = tr("Sichtbarkeit"),
            options = accessLevelOptions,
            value = defaultLevel.name,
            required = true,
        )
    val createButton = newActionButton(ActionIcon.ADD, tr("Ordner anlegen"), ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapseCancelButton(collapse))
    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val accessLevel = DocumentAccessLevel.valueOf(accessField.value)
            val result = guarded { rpcService<IDocumentService>().createFolder(name, null, accessLevel) }
            if (result != null) {
                notifySuccess(gettext("Ordner \"%1\" angelegt.", name))
                // V1.9.49: the form closes (and is rebuilt from scratch on the next open), which is what resets the select to the
                // role default now -- the explicit `setValue` of the permanently visible form is no longer needed.
                collapse(true)
                onCreated()
            }
        }
    }
    return form.snapshot()
}

// R24/R24B (W4d): migrated to the form grammar -- Titel (required text) + Sichtbarkeit (required select).
internal fun SimplePanel.renderDocumentCreation(
    folder: DocumentFolderDto,
    role: AccountRole?,
    collapse: (Boolean) -> Unit,
    onCreated: () -> Unit,
): FormSnapshot {
    // Review finding fix (Welle "Treasurer Document Upload", Runde 4): only offer access levels
    // the current role is actually allowed to create at -- see [DocumentsAuthzUi.allowedLevels]
    // KDoc for the orphaned-document failure mode this prevents. Welle V1.9.1: additionally
    // restricted to levels at least as restrictive as the OPEN folder's own level -- a UX nicety on
    // top of `createDocument`'s real `ConflictException` authority.
    //
    // Fix round (B5): this filter is only CORRECT because a folder tighten now materializes into
    // every descendant folder's own level, so "own level == effective level" actually holds. The
    // earlier comment here argued the opposite way round and was logically inverted: the effective
    // level can only be EQUAL OR STRICTER than the own level, so filtering by the OWN level offers a
    // superset of what the server accepts, not a subset. Before the materialization that superset was
    // reachable in practice (tighten a parent, open a sub-folder that kept PUBLIC_MEMBERS) and every
    // such creation answered `ConflictException`. It is now only reachable for a sub-folder some
    // other tool created and never materialized -- and the server still rejects it, which is the
    // posture that is actually load-bearing.
    val accessLevelOptions =
        DocumentsAuthzUi
            .allowedLevels(role)
            .filter { it.restrictiveness >= folder.accessLevel.restrictiveness }
            // Welle V1.9.1: fixes a labeling bug -- this dropdown used to show the raw enum
            // constant ("PUBLIC_MEMBERS") instead of a translated label (see
            // ClientUntrustedWidgetTextTripwireTest's KNOWN_UNSANITIZED_OPTIONS_LABEL_MAPS ledger).
            .map { it.name to documentAccessLevelLabel(it) }
    val form = lapisForm()
    val titleField = form.textField(label = tr("Neuer Dokumenttitel"), required = true)
    val accessField =
        form.selectField(
            // Defaults to the folder's own level -- itself always the most permissive option offered
            // above (PUBLIC_MEMBERS, restrictiveness 0, is never filtered out by the `>=` predicate).
            label = tr("Sichtbarkeit"),
            options = accessLevelOptions,
            value = folder.accessLevel.name,
            required = true,
        )
    val createButton = newActionButton(ActionIcon.ADD, tr("Dokument anlegen (danach Datei hochladen)"), ButtonStyle.PRIMARY)
    form.buttons(primary = createButton, cancel = collapseCancelButton(collapse))
    createButton.onClick {
        form.submit(createButton) {
            val title = titleField.value.trim()
            val accessLevel = DocumentAccessLevel.valueOf(accessField.value)
            val result = guarded { rpcService<IDocumentService>().createDocument(folder.id, title, accessLevel) }
            if (result != null) {
                notifySuccess(gettext("Dokument \"%1\" angelegt -- jetzt eine Datei hochladen.", title))
                collapse(true)
                onCreated()
            }
        }
    }
    return form.snapshot()
}
