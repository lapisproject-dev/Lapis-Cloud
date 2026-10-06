package network.lapis.cloud.client.encounter

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.AppState
import network.lapis.cloud.client.DataSection
import network.lapis.cloud.client.FormRules
import network.lapis.cloud.client.FormSnapshot
import network.lapis.cloud.client.Routes
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.actionLink
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.collapseCancelButton
import network.lapis.cloud.client.collapsibleCreateForm
import network.lapis.cloud.client.confirmDialog
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.lapisForm
import network.lapis.cloud.client.lapisToolbar
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.notifySuccess
import network.lapis.cloud.client.pageHeader
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.snapshot
import network.lapis.cloud.client.statusBadge
import network.lapis.cloud.client.untrustedCardTitle
import network.lapis.cloud.client.untrustedP
import network.lapis.cloud.client.untrustedSpan
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.rpc.IEncounterSpaceService

/**
 * V1.9.62 Begegnungsraum (B2) -- the list of the standing rooms ("Begegnungsräume"). Everybody who may see a room sees its row: title,
 * whether the doors are open, how many people are present (a NUMBER, never names) and the names of the pulpit office holders. BOARD
 * and ADMIN additionally create, edit, assign the offices of and archive rooms.
 *
 * The role gating mirrors the server (`IEncounterSpaceService`: `createSpace`/`updateSpace`/`setSpaceRoles`/`archiveSpace` are
 * BOARD/ADMIN) -- `canManage` is a UX nicety on top of that authority, never the security boundary. All free text of a room (title,
 * description, notice, names) is rendered through the `untrusted*` helpers.
 */
fun renderEncounterSpaceScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 720.px
            marginTop = 24.px
        }
    val header = root.pageHeader(tr("Begegnungsräume"))
    val canManage = AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)
    // R36B: the create form is collapsed; its button sits in the title row and the form opens right under the header.
    val createHost = if (canManage) root.vPanel(spacing = 6) else null

    lateinit var section: DataSection
    section =
        root.dataSection<List<EncounterSpaceDto>>(
            emptyText = tr("Noch keine Begegnungsräume."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<IEncounterSpaceService>().listSpaces() } },
        ) { panel, spaces ->
            spaces.forEach { space -> renderEncounterSpaceRow(panel, space, canManage) { section.reload() } }
        }
    section.reload()

    if (createHost != null) {
        collapsibleCreateForm<Unit>(
            actionSlot = header.actionSlot,
            formHost = createHost,
            buttonLabel = tr("Neuer Begegnungsraum"),
            formId = "lapis-create-encounter-space",
        ) { _, close -> renderEncounterSpaceForm(root = this, existing = null, onSaved = { section.reload() }, collapse = close) }
    }
}

private fun renderEncounterSpaceRow(
    panel: SimplePanel,
    space: EncounterSpaceDto,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2 lapis-encounter-space-row") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.untrustedCardTitle(space.title)
    // Open or closed is text AND colour (never colour alone).
    headerRow.statusBadge(if (space.open) gettext("Geöffnet") else gettext("Geschlossen"), if (space.open) "success" else "secondary")
    if (space.description.isNotBlank()) row.untrustedP(space.description, className = "mb-0")
    val facts = row.div(className = "text-muted small d-flex flex-wrap gap-3")
    facts.div(gettext("%1 anwesend", space.presentCount))
    if (space.pulpitDisplayNames.isNotEmpty()) {
        facts.untrustedSpan(gettext("Kanzel: %1", space.pulpitDisplayNames.joinToString(", ")))
    }

    val actions = row.lapisToolbar()
    actions.actionLink(ActionIcon.ENTER, tr("Zum Raum"), "#${Routes.ENCOUNTER}/${space.id}")
    if (!canManage) return
    val editPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    editPanel.hide()
    var editOpen = false
    actions.actionButton(ActionIcon.EDIT, tr("Bearbeiten"), style = ButtonStyle.OUTLINEPRIMARY).onClick {
        editOpen = !editOpen
        editPanel.removeAll()
        if (editOpen) {
            renderEncounterSpaceForm(
                root = editPanel,
                existing = space,
                onSaved = {
                    editPanel.hide()
                    onChanged()
                },
            )
            editPanel.show()
        } else {
            editPanel.hide()
        }
    }
    val rolesPanel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    rolesPanel.hide()
    var rolesOpen = false
    actions.actionButton(ActionIcon.OFFICES, tr("Ämter"), style = ButtonStyle.OUTLINESECONDARY).onClick {
        rolesOpen = !rolesOpen
        rolesPanel.removeAll()
        if (rolesOpen) {
            rolesPanel.encounterSpaceRoleEditor(space) {
                rolesPanel.hide()
                rolesOpen = false
                onChanged()
            }
            rolesPanel.show()
        } else {
            rolesPanel.hide()
        }
    }
    val archiveButton = actions.actionButton(ActionIcon.ARCHIVE, tr("Archivieren"), style = ButtonStyle.OUTLINEDANGER)
    if (space.open) {
        // An open room cannot be archived: the doors have to be closed first (the server refuses it too).
        archiveButton.disabled = true
        row.div(tr("Erst Türen schließen, dann archivieren."), className = "text-muted small")
    }
    archiveButton.onClick {
        confirmDialog(
            title = tr("Den Raum archivieren?"),
            message = tr("Ein archivierter Raum erscheint nicht mehr in der Liste und kann nicht mehr geöffnet werden."),
            confirmLabel = tr("Archivieren"),
            confirmIcon = ActionIcon.ARCHIVE,
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IEncounterSpaceService>().archiveSpace(space.id) }
                if (result != null) {
                    notifySuccess(tr("Der Raum wurde archiviert."))
                    onChanged()
                }
            }
        }
    }
}

/** Longest title/notice the server accepts (mirrors `EncounterSpaceService`; the server stays the authority). */
private const val SPACE_TITLE_MAX = 200
private const val SPACE_DESCRIPTION_MAX = 1000
private const val SPACE_NOTICE_MAX = 200
private const val SPACE_MIN_PARTICIPANTS = 2

/**
 * The create/edit form of a space. [existing] `null` creates (the call sits inside `collapsibleCreateForm`, R36B), a row's
 * "Bearbeiten" passes the room. Fields: title, description, who may enter, the notice shown while closed (at most 200 characters),
 * the maximum number of people (optional).
 */
internal fun renderEncounterSpaceForm(
    root: SimplePanel,
    existing: EncounterSpaceDto?,
    onSaved: () -> Unit,
    collapse: ((saved: Boolean) -> Unit)? = null,
): FormSnapshot {
    val form = root.vPanel(spacing = 6).lapisForm()
    val titleField =
        form.textField(
            label = tr("Titel"),
            value = existing?.title,
            required = true,
            rule = { FormRules.maxLength(value = it, max = SPACE_TITLE_MAX) },
        )
    val descriptionField =
        form.textAreaField(
            label = tr("Beschreibung"),
            rows = 3,
            value = existing?.description,
            rule = { FormRules.maxLength(value = it, max = SPACE_DESCRIPTION_MAX) },
        )
    val policyField =
        form.selectField(
            label = tr("Wer darf eintreten?"),
            options =
                listOf(
                    EncounterGuestPolicy.MEMBERS_ONLY.name to gettext("Nur Mitglieder"),
                    EncounterGuestPolicy.MEMBERS_AND_GUESTS.name to gettext("Mitglieder und Gäste"),
                ),
            value = (existing?.guestPolicy ?: EncounterGuestPolicy.MEMBERS_ONLY).name,
            required = true,
        )
    val noticeField =
        form.textField(
            label = tr("Hinweis, solange der Raum geschlossen ist"),
            value = existing?.closedNotice,
            hint = gettext("Höchstens %1 Zeichen.", SPACE_NOTICE_MAX),
            rule = { FormRules.maxLength(value = it, max = SPACE_NOTICE_MAX) },
        )
    val maxField =
        form.textField(
            label = tr("Höchstzahl der Anwesenden"),
            value = existing?.maxParticipants?.toString(),
            hint = gettext("Leer lassen für die Voreinstellung der Instanz."),
            rule = { FormRules.intAtLeast(value = it, min = SPACE_MIN_PARTICIPANTS) },
        )
    val saveButton =
        if (existing == null) {
            newActionButton(ActionIcon.ADD, tr("Begegnungsraum anlegen"), ButtonStyle.PRIMARY)
        } else {
            newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
        }
    form.buttons(primary = saveButton, cancel = collapse?.let { collapseCancelButton(it) })
    saveButton.onClick {
        form.submit(saveButton) {
            val input =
                EncounterSpaceInput(
                    title = titleField.value.trim(),
                    description = descriptionField.value.trim(),
                    guestPolicy = EncounterGuestPolicy.valueOf(policyField.value),
                    closedNotice = noticeField.value.trim().ifEmpty { null },
                    maxParticipants = maxField.value.trim().toIntOrNull(),
                )
            val saved =
                guarded {
                    val service = rpcService<IEncounterSpaceService>()
                    if (existing == null) service.createSpace(input) else service.updateSpace(existing.id, input)
                }
            if (saved != null) {
                notifySuccess(
                    if (existing ==
                        null
                    ) {
                        tr("Der Begegnungsraum wurde angelegt.")
                    } else {
                        tr("Der Begegnungsraum wurde gespeichert.")
                    },
                )
                onSaved()
                collapse?.invoke(true)
            }
        }
    }
    return form.snapshot()
}

/** Static label of an office (the bare msgid "Ordner" already means "folder", so the role carries its meaning in a sentence). */
internal fun encounterSpaceRoleLabel(role: EncounterSpaceRole): String =
    when (role) {
        EncounterSpaceRole.PULPIT -> gettext("Kanzel (spricht)")
        EncounterSpaceRole.STEWARD -> gettext("Ordner (spricht und moderiert)")
    }
