package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ExternalMemberIdRules
import network.lapis.cloud.shared.domain.ExternalMemberIdSettingsDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.IMemberService

/** The RPC surface of the external-id card -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface ExternalMemberIdRpc {
    suspend fun settings(): ExternalMemberIdSettingsDto

    suspend fun set(
        memberId: String,
        externalId: String,
    ): MemberAdminRowDto

    suspend fun clear(memberId: String): MemberAdminRowDto
}

internal fun liveExternalMemberIdRpc(): ExternalMemberIdRpc =
    object : ExternalMemberIdRpc {
        override suspend fun settings() = rpcService<IMemberService>().getExternalMemberIdSettings()

        override suspend fun set(
            memberId: String,
            externalId: String,
        ) = rpcService<IMemberService>().setExternalMemberId(memberId, externalId)

        override suspend fun clear(memberId: String) = rpcService<IMemberService>().clearExternalMemberId(memberId)
    }

/** V156 -- BOARD/ADMIN only, never the caller's own row (four-eyes), never anonymised; an escalated row only by an ADMIN. */
internal fun canEditExternalMemberIdOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean {
    if (callerRole != AccountRole.BOARD && callerRole != AccountRole.ADMIN) return false
    if (row.anonymized || row.id == callerMemberId) return false
    if (row.role != null && row.role in ESCALATED_ROLES && callerRole != AccountRole.ADMIN) return false
    return row.status in MemberStatusSets.ORGANIZATION_MEMBER || row.externalMemberId != null
}

/** The refusal shown under the field for an invalid entry; `null` when the entry is fine. Same rules as the server. */
internal fun externalMemberIdFieldProblem(raw: String): String? =
    if (ExternalMemberIdRules.normalizeId(raw) == null) {
        gettext("Die ID darf nur Buchstaben, Ziffern und . _ : / - enthalten (höchstens 64 Zeichen).")
    } else {
        null
    }

/** The question before removing an id; with the voting requirement on it also says what the removal costs. */
internal fun externalMemberIdRemovalQuestion(requiredForVoting: Boolean): String =
    if (requiredForVoting) {
        tr("ID wirklich entfernen? Das Mitglied verliert damit sein Stimmrecht.")
    } else {
        tr("ID wirklich entfernen?")
    }

/** Wording for the refusal when the id is already held by another member of the same source. */
internal fun externalMemberIdTakenText(): String = gettext("Diese ID ist bereits einem anderen Mitglied zugeordnet.")

/**
 * V156 -- the "Externe Mitglieds-ID" card of the editor dialog. Invisible while the instance has no source configured. The source is shown as a
 * fixed label, never editable; one single-line field, "Speichern" and "Entfernen" ("Entfernen" only while the member holds an id). Client-side rule problems
 * stand under the field; refusals of the server (duplicate id, bad request, conflict) arrive as a toast through the guarded call. The server decides everything again.
 */
internal class MemberExternalIdCard(
    private val parent: SimplePanel,
    private val row: MemberAdminRowDto,
    private val settings: ExternalMemberIdSettingsDto,
    private val rpc: ExternalMemberIdRpc,
    private val legendGroup: LegendGroup,
    private val onChanged: () -> Unit,
    private val confirm: (message: String, onConfirm: () -> Unit) -> Unit = { message, onConfirm ->
        confirmDialog(
            title = tr("Externe Mitglieds-ID entfernen"),
            message = message,
            confirmLabel = tr("Entfernen"),
            onConfirm = onConfirm,
        )
    },
    private val toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    internal var field: LapisField? = null
        private set

    internal lateinit var form: LapisForm
        private set

    fun render() {
        val source = settings.source ?: return
        parent.div { addCssClass("mt-3") }
        parent.h2(tr("Externe Mitglieds-ID")) { addCssClass("h6") }
        form = parent.lapisForm(legendGroup)
        form.panel.div(gettext("Quelle: %1", row.externalMemberIdSource ?: source)) { addCssClasses("text-muted small") }
        val input =
            form.textField(
                label = tr("Externe Mitglieds-ID"),
                value = row.externalMemberId,
                rule = { raw -> externalMemberIdFieldProblem(raw)?.let { FieldCheck.Invalid(it) } ?: FieldCheck.Ok },
            )
        field = input
        val save = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
        val remove = newActionButton(ActionIcon.REMOVE, tr("Entfernen"), ButtonStyle.WARNING)
        form.buttons(primary = save, destructive = if (row.externalMemberId != null) remove else null)
        save.onClick {
            form.submit(save) {
                try {
                    val result = memberAdminGuarded { rpc.set(row.id, input.value.trim()) }
                    if (result != null) {
                        toastSuccess(tr("Externe Mitglieds-ID gespeichert."))
                        onChanged()
                    }
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
        remove.onClick {
            confirm(externalMemberIdRemovalQuestion(settings.requiredForVoting)) {
                AppScope.launch {
                    val result = memberAdminGuarded { rpc.clear(row.id) }
                    if (result != null) {
                        toastSuccess(tr("Externe Mitglieds-ID entfernt."))
                        onChanged()
                    }
                }
            }
        }
    }
}

/**
 * Mounts the card in the editor dialog of [row]. Loads the instance settings first; renders nothing without a configured source or for a caller who
 * may not edit. A failed settings load leaves the card out (the rest of the dialog is unaffected).
 */
internal fun renderMemberExternalIdSection(
    modal: Modal,
    row: MemberAdminRowDto,
    callerRole: AccountRole?,
    callerMemberId: String?,
    legendGroup: LegendGroup,
    onChanged: () -> Unit,
    rpc: ExternalMemberIdRpc = liveExternalMemberIdRpc(),
) {
    if (!canEditExternalMemberIdOf(callerRole, callerMemberId, row)) return
    val host = SimplePanel()
    modal.add(host)
    AppScope.launch {
        val settings = runCatching { rpc.settings() }.getOrNull() ?: return@launch
        if (settings.source == null) return@launch
        MemberExternalIdCard(
            parent = host,
            row = row,
            settings = settings,
            rpc = rpc,
            legendGroup = legendGroup,
            onChanged = {
                modal.hide()
                onChanged()
            },
        ).render()
    }
}

/** The text of the read-only line in "Meine Daten": the member's own id and its source. */
internal fun ownExternalMemberIdLine(
    source: String,
    id: String,
): String = gettext("Externe Mitglieds-ID (%1): %2", source, id)

/** "Meine Daten": the member's OWN external id, read-only. Renders nothing without a configured source or without a stored id, and not for a guest. */
internal fun renderOwnExternalMemberIdSection(
    container: SimplePanel,
    rpc: ExternalMemberIdRpc = liveExternalMemberIdRpc(),
) {
    val session = AppState.session ?: return
    if (session.isGuest) return
    val host = SimplePanel()
    container.add(host)
    AppScope.launch {
        val settings = runCatching { rpc.settings() }.getOrNull() ?: return@launch
        val id = settings.ownExternalMemberId ?: return@launch
        if (settings.source == null) return@launch
        host.div(ownExternalMemberIdLine(source = settings.ownExternalMemberIdSource ?: settings.source.orEmpty(), id = id)) {
            addCssClasses("border rounded p-3")
        }
    }
}
