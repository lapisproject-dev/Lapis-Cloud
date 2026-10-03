package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.rpc.IMemberService

/** The RPC surface of the board dialog: the shared writes plus the audited read. An interface so DOM tests can drive it. */
internal interface MemberAddressAdminRpc : MemberAddressWriteRpc {
    suspend fun loadForAdministration(memberId: String): MemberAddressDataDto
}

internal fun liveMemberAddressAdminRpc(): MemberAddressAdminRpc =
    object : MemberAddressAdminRpc {
        override suspend fun loadForAdministration(memberId: String) =
            rpcService<IMemberService>().getMemberAddressForAdministration(memberId)

        override suspend fun updateAddress(
            memberId: String,
            street: String?,
            postalCode: String?,
            city: String?,
            country: String?,
        ) = rpcService<IMemberService>().updateMemberAddress(memberId, street, postalCode, city, country)

        override suspend fun updateBeneficialOwnerData(
            memberId: String,
            dateOfBirth: LocalDate?,
            nationality: String?,
        ) = rpcService<IMemberService>().updateMemberBeneficialOwnerData(memberId, dateOfBirth, nationality)
    }

/**
 * Injectable confirmation for a deliberate but reversible step (primary style, focus on "Abbrechen"): the board dialog's save
 * and the refund marking. The tests drive it without a modal.
 */
internal fun interface AdminActionConfirm {
    fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    )
}

internal val liveAdminActionConfirm =
    AdminActionConfirm { title, message, confirmLabel, onConfirm ->
        confirmDialog(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            confirmStyle = ButtonStyle.PRIMARY,
            focusCancel = true,
            onConfirm = onConfirm,
        )
    }

/**
 * V1.9.35 -- the roster action "Anschrift und GwG-Angaben bearbeiten". Self-gated: renders NOTHING unless the caller is BOARD/ADMIN
 * (strictly -- not "may see the roster": a chapter officer is not `isPrivileged` and the server refuses) and the member is not
 * anonymized (the server answers NotFound for those; no offer for an action it rejects).
 */
internal fun renderMemberAddressAdminAction(
    actionsCell: Container,
    row: MemberAdminRowDto,
    rpc: MemberAddressAdminRpc = liveMemberAddressAdminRpc(),
) {
    if (!AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) return
    if (row.anonymized) return
    val button = actionsCell.tableActionButton("fas fa-address-card", tr("Anschrift und GwG-Angaben bearbeiten"))
    button.onClick { openMemberAddressAdminDialog(row, rpc) }
}

/**
 * V1.9.35 -- two steps, so a mis-click leaves no trace: step 1 only explains that the read is logged under the caller's name and
 * performs NO server call; "Angaben anzeigen" removes step 1 at once (a double click lands on nothing) and starts exactly one
 * audited read. Step 2 is the shared [MemberAddressCard] (variant ADMINISTRATION) -- every save asks first and is rebuilt from the
 * DTO the server returned (no second read, no second audit entry); a refused write reloads once. Closing the dialog disposes
 * it, so the personal data does not stay in the DOM. Values never reach a toast, a log, storage or the URL.
 */
internal fun openMemberAddressAdminDialog(
    row: MemberAdminRowDto,
    rpc: MemberAddressAdminRpc,
    today: () -> LocalDate = ::clientToday,
    confirm: AdminActionConfirm = liveAdminActionConfirm,
    toastError: (String) -> Unit = { notifyError(it) },
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    val modal = Modal(caption = sanitizeUntrustedI18nText(gettext("Anschrift und GwG-Angaben von %1", row.displayName)))
    val content = modal.vPanel(spacing = 10)
    val closeButton =
        newActionButton(ActionIcon.CLOSE, tr("Schließen"), ButtonStyle.SECONDARY).apply {
            onClick { modal.hide() }
        }
    modal.addButton(closeButton)

    lateinit var section: DataSection

    fun renderCard(
        panel: SimplePanel,
        data: MemberAddressFormData,
    ) {
        MemberAddressCard(
            parent = panel,
            rpc = rpc,
            today = today,
            onChanged = { section.reload() },
            toastError = toastError,
            toastSuccess = toastSuccess,
            onSaved = { saved: MemberDto? ->
                if (saved != null) {
                    panel.removeAll()
                    renderCard(panel, saved.toFormData())
                } else {
                    section.reload()
                }
            },
            variant = MemberAddressCardVariant.ADMINISTRATION,
            confirmSave = { proceed ->
                confirm.show(
                    gettext("Angaben speichern"),
                    gettext(
                        "Änderungen an den Angaben von %1 speichern? Die Änderung wird im Prüfprotokoll vermerkt.",
                        sanitizeUntrustedI18nText(row.displayName),
                    ),
                    gettext("Angaben speichern"),
                    proceed,
                )
            },
        ).render(data)
    }

    val notice = content.vPanel(spacing = 8)
    notice.div(
        tr("Diese Angaben unterliegen dem Geldwäschegesetz. Ihr Abruf wird mit Ihrem Namen im Prüfprotokoll vermerkt."),
    )
    val showButton = Button(tr("Angaben anzeigen"), style = ButtonStyle.PRIMARY)
    notice.add(showButton)
    showButton.onClick {
        if (showButton.disabled) return@onClick
        showButton.disabled = true
        content.removeAll()
        section =
            content.dataSection<MemberAddressDataDto>(
                isEmpty = { false },
                load = { guarded { rpc.loadForAdministration(row.id) } },
                render = { panel, data -> renderCard(panel, data.toFormData()) },
            )
        section.reload()
    }
    modal.show()
}
