package network.lapis.cloud.client.encounter

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.SearchableSelect
import network.lapis.cloud.client.actionButton
import network.lapis.cloud.client.addCssClasses
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.lapisForm
import network.lapis.cloud.client.lapisToolbar
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.notifyError
import network.lapis.cloud.client.notifySuccess
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.untrustedOptions
import network.lapis.cloud.client.untrustedSpan
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleDto
import network.lapis.cloud.shared.domain.MemberSummaryDto
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.IMemberService

/** The server accepts at most this many office assignments per space (`setSpaceRoles` is replace-all). */
internal const val ENCOUNTER_MAX_ROLE_ASSIGNMENTS = 20

private class EncounterRoleEditorData(
    val roles: List<EncounterSpaceRoleDto>,
    val members: List<MemberSummaryDto>,
)

/**
 * V1.9.62 -- the office editor of one space ("Ämter"), BOARD/ADMIN only: who speaks (the pulpit or podium) and who is a steward
 * ("Ordner", speaks and moderates). The list is edited LOCALLY (add a person with a role, remove one) and saved as a whole with
 * `setSpaceRoles` (replace-all, at most [ENCOUNTER_MAX_ROLE_ASSIGNMENTS]); a person holds one office per space. The people come from the
 * same member source as every other person picker (`IMemberService.listMembers`, ACTIVE members only) through a [SearchableSelect].
 * A server refusal is shown by the shared `guarded` toast with a fixed text, never with the server's message.
 */
internal fun SimplePanel.encounterSpaceRoleEditor(
    space: EncounterSpaceDto,
    onSaved: () -> Unit,
) {
    val box = vPanel(spacing = 6)
    box.h2(tr("Ämter")) { addCssClass("h5") }
    box
        .dataSection<EncounterRoleEditorData>(
            isEmpty = { false },
            load = {
                guarded {
                    val roles = rpcService<IEncounterSpaceService>().listSpaceRoles(space.id)
                    val members = rpcService<IMemberService>().listMembers()
                    EncounterRoleEditorData(roles = roles, members = members)
                }
            },
        ) { panel, data -> renderRoleEditorBody(panel, space, data, onSaved) }
        .reload()
}

private fun renderRoleEditorBody(
    panel: SimplePanel,
    space: EncounterSpaceDto,
    data: EncounterRoleEditorData,
    onSaved: () -> Unit,
) {
    val entries = data.roles.map { EncounterSpaceRoleAssignmentInput(memberId = it.memberId, role = it.role) }.toMutableList()
    val names = data.members.associate { member -> member.id to member.displayName }.toMutableMap()
    data.roles.forEach { names[it.memberId] = it.displayName }

    val listPanel = panel.vPanel(spacing = 4)

    fun redraw() {
        listPanel.removeAll()
        if (entries.isEmpty()) {
            listPanel.div(tr("Noch keine Ämter vergeben."), className = "text-muted")
            return
        }
        entries.toList().forEach { entry ->
            val row = listPanel.hPanel(spacing = 8) { addCssClasses("border-bottom py-1 align-items-center") }
            row.untrustedSpan(names[entry.memberId] ?: entry.memberId, className = "flex-grow-1")
            row.div(encounterSpaceRoleLabel(entry.role, space.profile), className = "text-muted small")
            row.actionButton(ActionIcon.REMOVE, tr("Amt entziehen"), style = ButtonStyle.OUTLINESECONDARY, small = true).onClick {
                entries.remove(entry)
                redraw()
            }
        }
    }
    redraw()

    val addForm = panel.vPanel(spacing = 6).lapisForm()
    val personField =
        addForm.searchableSelectField(
            label = tr("Person"),
            options = emptyList(),
            required = true,
            requiredMessage = gettext("Bitte eine Person auswählen."),
        )
    (personField.control as SearchableSelect).options = untrustedOptions(data.members.map { it.id to it.displayName })
    val roleField =
        addForm.selectField(
            label = tr("Amt"),
            options = EncounterSpaceRole.entries.map { it.name to encounterSpaceRoleLabel(it, space.profile) },
            value = EncounterSpaceRole.PULPIT.name,
            required = true,
        )
    val addButton = newActionButton(ActionIcon.ADD, tr("Amt vergeben"), ButtonStyle.OUTLINEPRIMARY)
    addForm.buttons(primary = addButton)
    addButton.onClick {
        if (!addForm.validateAndReport()) return@onClick
        val memberId = personField.value
        when {
            entries.size >= ENCOUNTER_MAX_ROLE_ASSIGNMENTS ->
                notifyError(gettext("Höchstens %1 Ämter pro Raum.", ENCOUNTER_MAX_ROLE_ASSIGNMENTS))
            entries.any { it.memberId == memberId } -> notifyError(tr("Diese Person hat bereits ein Amt in diesem Raum."))
            else -> {
                entries += EncounterSpaceRoleAssignmentInput(memberId = memberId, role = EncounterSpaceRole.valueOf(roleField.value))
                personField.reset()
                redraw()
            }
        }
    }

    val bar = panel.lapisToolbar()
    bar.actionButton(ActionIcon.SAVE, tr("Ämter speichern"), style = ButtonStyle.PRIMARY).onClick {
        AppScope.launch {
            val saved = guarded { rpcService<IEncounterSpaceService>().setSpaceRoles(space.id, entries.toList()) }
            if (saved != null) {
                notifySuccess(tr("Die Ämter wurden gespeichert."))
                onSaved()
            }
        }
    }
}
