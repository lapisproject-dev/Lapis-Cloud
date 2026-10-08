package network.lapis.cloud.client.encounter

import io.kvision.form.check.CheckBox
import io.kvision.form.check.radioGroup
import io.kvision.html.ButtonStyle
import io.kvision.html.Span
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
import network.lapis.cloud.client.LapisField
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
import network.lapis.cloud.client.typeBadge
import network.lapis.cloud.client.untrustedCardTitle
import network.lapis.cloud.client.untrustedP
import network.lapis.cloud.client.untrustedSpan
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ENCOUNTER_TABLE_MAX_COUNT
import network.lapis.cloud.shared.domain.ENCOUNTER_TABLE_MAX_SEATS
import network.lapis.cloud.shared.domain.ENCOUNTER_TABLE_MIN_SEATS
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterTablesConfig
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
    // The kind of room is text, never colour alone.
    headerRow.typeBadge(termsFor(space.profile).profileName(), "info")
    // V1.9.76: a quiet sign that the office holders are told about newcomers (transparency, Art. 13) -- no badge, no colour, no number.
    notifyIndicatorText(space.notifyMode)?.let { name ->
        // An icon element like the chevron of the conference dock: no rich text, so nothing foreign can become markup.
        val bell = Span(className = "fas fa-bell text-muted")
        bell.setAttribute("role", "img")
        bell.setAttribute("aria-label", name)
        bell.setAttribute("title", name)
        headerRow.add(bell)
    }
    if (space.description.isNotBlank()) row.untrustedP(space.description, className = "mb-0")
    val facts = row.div(className = "text-muted small d-flex flex-wrap gap-3")
    facts.div(gettext("%1 anwesend", space.presentCount))
    if (space.pulpitDisplayNames.isNotEmpty()) {
        facts.untrustedSpan(termsFor(space.profile).speakersFact(space.pulpitDisplayNames.joinToString(", ")))
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
    // V1.9.67: the kind of room (no preselection when creating: the person decides, a wrong default would put the wrong vocabulary and the
    // wrong consent text in the room) and the reactions it offers. Both are frozen while a session is open (the server refuses a change too).
    val locked = existing?.open == true
    val profileRadio =
        form.panel.radioGroup(
            options = EncounterProfile.entries.map { it.name to termsFor(it).profileName() + ": " + termsFor(it).profileDescription() },
            value = existing?.profile?.name,
            label = tr("Raumart"),
        )
    profileRadio.setAttribute("role", "radiogroup")
    profileRadio.disabled = locked
    val profileField =
        form.register(
            control = profileRadio,
            label = tr("Raumart"),
            required = true,
            requiredMessage = tr("Bitte wählen Sie eine Raumart."),
        )
    val reactionChecks = LinkedHashMap<EncounterReactionOption, LapisField>()
    val currentReactions = existing?.reactions ?: emptyList()
    EncounterReactionOption.entries.forEach { option ->
        val always = option == EncounterReactionOption.ALWAYS_ON
        reactionChecks[option] =
            form.checkField(
                label = reactionLabel(option),
                value = always || option in currentReactions,
                hint = if (always) gettext("Immer verfügbar: Wortmeldung") else null,
                init = { box -> box.disabled = always || locked },
            )
    }
    if (locked) {
        form.panel.div(
            tr("Raumart und Reaktionen lassen sich nur bei geschlossenem Raum ändern."),
            className = "text-muted small",
        )
    }
    // V1.9.80 (stage 2b): tables with their own audio group -- the assembly profile only, frozen while a session is open (the server refuses a
    // change too). Choosing the church-service profile switches them off and greys them out.
    val tablesNow = existing?.tables ?: EncounterTablesConfig()
    val tablesAllowed = existing?.profile == EncounterProfile.ASSEMBLY
    val tablesCheck =
        form.checkField(
            label = tr("Tische mit eigener Audiogruppe"),
            value = tablesNow.enabled && tablesAllowed,
            hint = gettext("Nur in der Raumart Versammlung. Wer an einem Tisch sitzt, hört und spricht nur dort mit den anderen am Tisch."),
            init = { box -> box.disabled = locked || !tablesAllowed },
        )
    val tableCountField =
        form.textField(
            label = tr("Anzahl der Tische"),
            value = tablesNow.count.toString(),
            hint = gettext("Zwischen %1 und %2.", 1, ENCOUNTER_TABLE_MAX_COUNT),
            rule = { FormRules.intInRange(value = it, min = 1, max = ENCOUNTER_TABLE_MAX_COUNT) },
            init = { it.disabled = locked },
        )
    val tableSeatsField =
        form.textField(
            label = tr("Plätze je Tisch"),
            value = tablesNow.seats.toString(),
            hint = gettext("Zwischen %1 und %2.", ENCOUNTER_TABLE_MIN_SEATS, ENCOUNTER_TABLE_MAX_SEATS),
            rule = { FormRules.intInRange(value = it, min = ENCOUNTER_TABLE_MIN_SEATS, max = ENCOUNTER_TABLE_MAX_SEATS) },
            init = { it.disabled = locked },
        )
    if (locked) {
        form.panel.div(
            tr("Die Tische lassen sich nur bei geschlossenem Raum ändern."),
            className = "text-muted small",
        )
    }
    // KVision calls the observer at once with the current value: only a real change of the kind may reset the reactions, otherwise opening
    // a closed room with a non-default reaction set would silently replace it by the defaults.
    var lastProfile = existing?.profile?.name
    profileRadio.subscribe { chosen ->
        if (chosen == lastProfile) return@subscribe
        lastProfile = chosen
        // A change of the kind of room offers that kind's usual reactions again (the person may adjust them afterwards).
        val profile = EncounterProfile.entries.firstOrNull { it.name == chosen } ?: return@subscribe
        if (locked) return@subscribe
        val defaults = EncounterReactionOption.defaultsFor(profile)
        reactionChecks.forEach { (option, field) -> (field.control as? CheckBox)?.value = option in defaults }
    }
    profileRadio.subscribe { chosen ->
        val box = tablesCheck.control as? CheckBox ?: return@subscribe
        if (locked) return@subscribe
        val assembly = chosen == EncounterProfile.ASSEMBLY.name
        // Tables belong to the assembly profile: any other kind switches them off and greys them out.
        if (!assembly) box.value = false
        box.disabled = !assembly
    }
    // V1.9.76: the anonymous e-mail notice to the office holders. NOT frozen while the room is open: it only affects future entries.
    val notifyRadio =
        form.panel.radioGroup(
            options =
                EncounterNotifyMode.entries.map { it.name to notifyModeName(it) + notifyModeDescription(it) },
            value = (existing?.notifyMode ?: EncounterNotifyMode.NONE).name,
            label = tr("Benachrichtigung der Amtsträger per E-Mail"),
        )
    notifyRadio.setAttribute("role", "radiogroup")
    val notifyField =
        form.register(
            control = notifyRadio,
            label = tr("Benachrichtigung der Amtsträger per E-Mail"),
        )
    // The supporting text is linked to the group by hand: the form grammar only links hints to text-like controls, a radio group has no input.
    val notifyHintId = "lapis-encounter-notify-hint-${notifyHintCounter++}"
    form.panel.div(
        gettext(
            "Die Nachricht ist anonym: Raumname, Uhrzeit und Zahl der Anwesenden, keine Namen. " +
                "Sie geht nur an Amtsträger, die gerade nicht im Raum sind, und nur, wenn E-Mail-Versand eingerichtet ist.",
        ),
        className = "text-muted small",
    ) { id = notifyHintId }
    notifyRadio.setAttribute("aria-describedby", notifyHintId)
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
                    profile = EncounterProfile.entries.first { it.name == profileField.value },
                    reactions =
                        EncounterReactionOption.normalize(
                            reactionChecks
                                .filter { (option, field) ->
                                    option == EncounterReactionOption.ALWAYS_ON || field.value == "true"
                                }.keys,
                        ),
                    closedNotice = noticeField.value.trim().ifEmpty { null },
                    maxParticipants = maxField.value.trim().toIntOrNull(),
                    notifyMode = EncounterNotifyMode.entries.firstOrNull { it.name == notifyField.value } ?: EncounterNotifyMode.NONE,
                    tables =
                        EncounterTablesConfig(
                            enabled = tablesCheck.value == "true" && profileField.value == EncounterProfile.ASSEMBLY.name,
                            count = tableCountField.value.trim().toIntOrNull() ?: tablesNow.count,
                            seats = tableSeatsField.value.trim().toIntOrNull() ?: tablesNow.seats,
                        ),
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

/** Makes the id of the supporting text unique per form instance (the create form and several edit forms can be on the page at once). */
private var notifyHintCounter = 0

/** Name of a notify mode as shown in the form (`gettext`: the option labels are plain strings). */
internal fun notifyModeName(mode: EncounterNotifyMode): String =
    when (mode) {
        EncounterNotifyMode.NONE -> gettext("Keine Nachricht")
        EncounterNotifyMode.FIRST_GUEST -> gettext("Bei der ersten Person ohne Amt")
        EncounterNotifyMode.EVERY_GUEST -> gettext("Bei jeder Person ohne Amt")
    }

private fun notifyModeDescription(mode: EncounterNotifyMode): String =
    when (mode) {
        EncounterNotifyMode.NONE -> ""
        EncounterNotifyMode.FIRST_GUEST -> ": " + gettext("Höchstens eine Nachricht je Öffnung des Raums.")
        EncounterNotifyMode.EVERY_GUEST ->
            ": " + gettext("Höchstens eine Nachricht alle fünf Minuten, mehrere Eintritte zusammengefasst.")
    }

/** The accessible name of the bell in a room's row, `null` for NONE (no bell). Pure. */
internal fun notifyIndicatorText(mode: EncounterNotifyMode): String? =
    when (mode) {
        EncounterNotifyMode.NONE -> null
        EncounterNotifyMode.FIRST_GUEST -> gettext("Amtsträger erhalten eine anonyme E-Mail bei der ersten Person ohne Amt")
        EncounterNotifyMode.EVERY_GUEST -> gettext("Amtsträger erhalten eine anonyme E-Mail bei jeder Person ohne Amt")
    }

/** Label of an office in the vocabulary of the room's [profile] (the bare msgid "Ordner" already means "folder"). */
internal fun encounterSpaceRoleLabel(
    role: EncounterSpaceRole,
    profile: EncounterProfile,
): String = termsFor(profile).spaceRoleLabel(role)
