package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.form.text.Text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberTitleRules
import network.lapis.cloud.shared.domain.MemberTitleViolation
import network.lapis.cloud.shared.rpc.IMemberService

private const val TITLE_DATALIST_ID = "lapis-member-title-suggestions"

/** V151 -- the inline check of the title field, built from the shared [MemberTitleRules] (same rule as the server). */
internal fun memberTitleRule(raw: String): FieldCheck =
    when (MemberTitleRules.violation(MemberTitleRules.normalize(raw))) {
        MemberTitleViolation.TOO_LONG -> FieldCheck.Invalid(gettext("Der Titel darf höchstens 50 Zeichen lang sein."))
        MemberTitleViolation.INVALID_CHARACTERS -> FieldCheck.Invalid(gettext("Der Titel enthält unzulässige Zeichen."))
        null -> FieldCheck.Ok
    }

/**
 * Limits the field to [MemberTitleRules.MAX] and attaches the (shared, created once) suggestion list. The field stays free text:
 * the list only proposes.
 */
internal fun initMemberTitleControl(control: Text) {
    control.maxlength = MemberTitleRules.MAX
    control.setAttribute("list", TITLE_DATALIST_ID)
    // Created eagerly and once (no widget hook): the list lives on document.body and is found by id.
    if (document.getElementById(TITLE_DATALIST_ID) == null) {
        val list = document.createElement("datalist")
        list.id = TITLE_DATALIST_ID
        MemberTitleRules.SUGGESTIONS.forEach { suggestion ->
            val option = document.createElement("option")
            option.setAttribute("value", suggestion)
            list.appendChild(option)
        }
        document.body?.appendChild(list)
    }
}

/** The RPC surface of the self-service card -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface MemberTitleRpc {
    suspend fun getCurrentMember(): MemberDto

    suspend fun updateTitle(
        memberId: String,
        title: String?,
    ): MemberDto
}

internal fun liveMemberTitleRpc(): MemberTitleRpc =
    object : MemberTitleRpc {
        override suspend fun getCurrentMember() = rpcService<IMemberService>().getCurrentMember()

        override suspend fun updateTitle(
            memberId: String,
            title: String?,
        ) = rpcService<IMemberService>().updateMemberTitle(memberId, title)
    }

/**
 * V151 -- the member's own title on "Meine Daten": one field, one save button (enabled only while the value changed AND is valid).
 * The memberId always comes from the loaded [MemberDto.id]; the host section is reloaded after every save. The value never reaches a
 * toast, log or URL.
 */
internal class MemberTitleCard(
    private val parent: SimplePanel,
    private val rpc: MemberTitleRpc,
    private val onChanged: () -> Unit,
    private val toastError: (String) -> Unit = { notifyError(it) },
    private val toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    internal lateinit var saveButton: Button
        private set

    fun render(dto: MemberDto) {
        val root = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
        root.h2(tr("Titel")) { addCssClass("h5") }
        root.div(tr("z. B. Dr. oder Dipl.-Ing., erscheint in Briefen vor dem Namen")) { addCssClasses("text-muted small") }
        val form = root.lapisForm()
        val field =
            form.textField(
                label = tr("Titel"),
                value = dto.title.orEmpty(),
                rule = ::memberTitleRule,
                init = ::initMemberTitleControl,
            )
        val initial = MemberTitleRules.normalize(dto.title)

        fun canSave() = MemberTitleRules.normalize(field.value) != initial && field.isValid()
        val button = newActionButton(ActionIcon.SAVE, tr("Titel speichern"), ButtonStyle.PRIMARY)
        button.disabled = true
        saveButton = button
        form.buttons(primary = button)
        field.subscribe { button.disabled = !canSave() }
        button.onClick {
            if (!canSave()) return@onClick
            val value = MemberTitleRules.normalize(field.value)
            form.runBusy(button, restoreDisabled = { !canSave() }) {
                val ok =
                    runOrConflict(
                        conflictMessage =
                            gettext(
                                "Der Titel wurde nicht gespeichert. Bitte prüfen Sie die Eingabe; die Ansicht wurde neu geladen.",
                            ),
                        toast = toastError,
                        block = { rpc.updateTitle(dto.id, value) },
                    )
                if (ok) toastSuccess(gettext("Gespeichert."))
                onChanged()
            }
        }
    }
}

/** Mounts the card on "Meine Daten" (not for a guest session -- a guest has no member record). */
internal fun renderMemberTitleSection(
    container: SimplePanel,
    rpc: MemberTitleRpc = liveMemberTitleRpc(),
    toastError: (String) -> Unit = { notifyError(it) },
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    val session = AppState.session ?: return
    if (session.isGuest) return
    lateinit var section: DataSection
    section =
        container.dataSection<MemberDto>(
            isEmpty = { false },
            load = { guarded { rpc.getCurrentMember() } },
            render = { panel, dto ->
                MemberTitleCard(
                    panel,
                    rpc,
                    onChanged = { section.reload() },
                    toastError = toastError,
                    toastSuccess = toastSuccess,
                ).render(dto)
            },
        )
    section.reload()
}
