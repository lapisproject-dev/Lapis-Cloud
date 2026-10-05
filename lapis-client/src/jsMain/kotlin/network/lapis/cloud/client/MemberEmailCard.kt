package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Autocomplete
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.OwnEmailChangeResultDto
import network.lapis.cloud.shared.domain.OwnPendingEmailChangeDto
import network.lapis.cloud.shared.rpc.IMemberEmailChangeService
import network.lapis.cloud.shared.rpc.IMemberService

/** The RPC surface of [MemberEmailCard] -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface MemberEmailRpc {
    suspend fun getCurrentMember(): MemberDto

    suspend fun capability(): EmailChangeCapabilityDto

    suspend fun ownPending(): OwnPendingEmailChangeDto?

    suspend fun changeOwn(
        currentPassword: String,
        newEmail: String,
        newEmailRepeat: String,
    ): OwnEmailChangeResultDto

    suspend fun accept(
        changeId: String,
        currentPassword: String,
    ): MemberDto

    suspend fun decline(changeId: String)
}

internal fun liveMemberEmailRpc(): MemberEmailRpc =
    object : MemberEmailRpc {
        override suspend fun getCurrentMember() = rpcService<IMemberService>().getCurrentMember()

        override suspend fun capability() = rpcService<IMemberEmailChangeService>().getEmailChangeCapability()

        override suspend fun ownPending() = rpcService<IMemberEmailChangeService>().getOwnPendingEmailChange().pending

        override suspend fun changeOwn(
            currentPassword: String,
            newEmail: String,
            newEmailRepeat: String,
        ) = rpcService<IMemberEmailChangeService>().changeOwnEmail(currentPassword, newEmail, newEmailRepeat)

        override suspend fun accept(
            changeId: String,
            currentPassword: String,
        ) = rpcService<IMemberEmailChangeService>().acceptOwnPendingEmailChange(changeId, currentPassword)

        override suspend fun decline(changeId: String) {
            rpcService<IMemberEmailChangeService>().declineOwnPendingEmailChange(changeId)
        }
    }

/** What the card renders -- everything is loaded by ONE bundled call, so there is one loading and one error state. */
internal data class MemberEmailCardState(
    val currentEmail: String,
    val capability: EmailChangeCapabilityDto,
    val pending: OwnPendingEmailChangeDto?,
)

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the member's own login address on "Meine Daten":
 *  - the current address (own data, shown in plain text),
 *  - an open change, if any: a PROPOSAL the member accepts with the password (or declines), or an administrator-initiated change that
 *    takes effect after a warning period and that the member can only decline,
 *  - the form "E-Mail-Adresse ändern" (current password + new address twice), only when the server says the member MAY change the
 *    address themselves (no identity-provider-managed login, a password exists).
 *
 * The password is a form value only: never a toast, a log, storage or the URL. The address never reaches a toast either. After every
 * write -- successful or not -- the host section reloads, so the card shows the server state, never a hand-patched one. Server text is
 * never shown (Kilua RPC does not transmit it); every sentence is fixed here. Failures are reported by the shared guard
 * ([memberAdminGuarded]) as fixed toasts.
 */
internal class MemberEmailCard(
    private val parent: SimplePanel,
    private val rpc: MemberEmailRpc,
    private val onChanged: () -> Unit,
    private val toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    fun render(state: MemberEmailCardState) {
        val root = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
        root.h2(tr("E-Mail-Adresse")) { addCssClass("h5") }
        root.div(
            tr(
                "Ihre E-Mail-Adresse ist zugleich Ihre Anmeldeadresse. Eine Änderung wird erst wirksam, wenn Sie sie mit " +
                    "Ihrem Passwort bestätigen -- oder, wenn eine andere Person sie veranlasst hat, nach einer Warnfrist, " +
                    "in der Sie ablehnen können.",
            ),
        ) { addCssClasses("text-muted small") }
        root.div {
            span(tr("Aktuelle Adresse:")) { addCssClass("me-1") }
            untrustedSpan(state.currentEmail) { addCssClass("fw-bold") }
        }

        state.pending?.let { renderPending(root, it) }

        if (state.capability.ownChangeAvailable) {
            renderChangeForm(root)
        } else {
            root.div(
                if (AppState.session?.keycloakMode == true) {
                    tr("Ihre Adresse verwalten Sie über die Anmeldung Ihrer Organisation.")
                } else {
                    tr("Für Ihr Konto können Sie die Adresse hier nicht selbst ändern. Wenden Sie sich bitte an den Vorstand.")
                },
            ) { addCssClasses("text-muted small") }
        }
    }

    private fun renderPending(
        root: SimplePanel,
        pending: OwnPendingEmailChangeDto,
    ) {
        val box = root.vPanel(spacing = 6) { addCssClasses("alert alert-secondary mb-0") }
        if (pending.requiresPassword) {
            box.div {
                span(tr("Eine neue Anmeldeadresse wurde für Sie vorgeschlagen:")) { addCssClass("me-1") }
                untrustedSpan(pending.newEmail) { addCssClass("fw-bold") }
            }
            box.div(
                tr("Übernehmen Sie sie mit Ihrem Passwort -- oder lehnen Sie sie ab, wenn Sie das nicht erwartet haben."),
            ) { addCssClasses("small") }
            val form = box.lapisForm()
            val password =
                form.passwordField(
                    label = tr("Aktuelles Passwort"),
                    required = true,
                    autocomplete = Autocomplete.CURRENT_PASSWORD,
                )
            val accept = newActionButton(ActionIcon.APPROVE, tr("Übernehmen"), ButtonStyle.PRIMARY)
            val decline = newActionButton(ActionIcon.REJECT, tr("Ablehnen"), ButtonStyle.OUTLINESECONDARY)
            form.buttons(primary = accept, cancel = decline)
            accept.onClick {
                form.submit(accept) {
                    val ok = write { rpc.accept(pending.changeId, password.value) }
                    if (ok) toastSuccess(tr("Ihre E-Mail-Adresse wurde geändert."))
                    onChanged()
                }
            }
            decline.onClick {
                form.runBusy(decline) {
                    val ok = write { rpc.decline(pending.changeId) }
                    if (ok) toastSuccess(tr("Der Vorschlag wurde abgelehnt."))
                    onChanged()
                }
            }
        } else {
            val effective = pending.effectiveAt
            box.div {
                span(tr("Eine administrative Person hat eine neue Anmeldeadresse veranlasst:")) { addCssClass("me-1") }
                untrustedSpan(pending.newEmail) { addCssClass("fw-bold") }
            }
            box.div(
                if (effective != null) {
                    gettext(
                        "Sie wird frühestens am %1 wirksam -- sofern die neue Adresse bestätigt wurde und Sie nicht ablehnen.",
                        formatSystemDateTime(effective),
                    )
                } else {
                    gettext("Sie wird wirksam, sobald die neue Adresse bestätigt wurde und Sie nicht ablehnen.")
                },
            ) { addCssClass("small") }
            val decline = newActionButton(ActionIcon.REJECT, tr("Ablehnen"), ButtonStyle.OUTLINESECONDARY)
            box.add(decline)
            decline.onClick {
                runGuardedAction(decline) {
                    val ok = write { rpc.decline(pending.changeId) }
                    if (ok) toastSuccess(tr("Die Änderung wurde abgelehnt."))
                    onChanged()
                }
            }
        }
    }

    private fun renderChangeForm(root: SimplePanel) {
        root.h2(tr("E-Mail-Adresse ändern")) { addCssClass("h6") }
        val form = root.lapisForm()
        val password =
            form.passwordField(
                label = tr("Aktuelles Passwort"),
                required = true,
                autocomplete = Autocomplete.CURRENT_PASSWORD,
            )
        val newEmail =
            form.textField(
                label = tr("Neue E-Mail-Adresse"),
                type = InputType.EMAIL,
                required = true,
                autocomplete = Autocomplete.EMAIL,
                rule = { FormRules.email(value = it) },
            )
        val repeat =
            form.textField(
                label = tr("Neue E-Mail-Adresse wiederholen"),
                type = InputType.EMAIL,
                required = true,
                rule = { FieldCheck.Ok },
            )
        form.crossFieldRule(field = repeat) {
            if (newEmail.value.trim().lowercase() == repeat.value.trim().lowercase()) {
                FieldCheck.Ok
            } else {
                FieldCheck.Invalid(gettext("Die beiden Adressen stimmen nicht überein."))
            }
        }
        val save = newActionButton(ActionIcon.SAVE, tr("Adresse ändern"), ButtonStyle.PRIMARY)
        form.buttons(primary = save)
        save.onClick {
            form.submit(save) {
                val ok = write { rpc.changeOwn(password.value, newEmail.value.trim(), repeat.value.trim()) }
                if (ok) toastSuccess(tr("Ihre E-Mail-Adresse wurde geändert."))
                onChanged()
            }
        }
    }

    /** One write through the shared guard; `true` iff it completed. Never shows server text. */
    private suspend fun write(block: suspend () -> Unit): Boolean = memberAdminGuarded { block() } != null
}

/**
 * Mounts the card on "Meine Daten". Not shown to a guest session (a guest has no member record of their own, and the address of a
 * federated guest is synthetic).
 */
internal fun renderMemberEmailSection(
    container: SimplePanel,
    rpc: MemberEmailRpc = liveMemberEmailRpc(),
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    val session = AppState.session ?: return
    if (session.isGuest) return
    lateinit var section: DataSection
    section =
        container.dataSection<MemberEmailCardState>(
            isEmpty = { false },
            load = {
                guarded {
                    MemberEmailCardState(
                        currentEmail = rpc.getCurrentMember().email,
                        capability = rpc.capability(),
                        pending = rpc.ownPending(),
                    )
                }
            },
            render = { panel, state ->
                MemberEmailCard(panel, rpc, onChanged = { section.reload() }, toastSuccess = toastSuccess).render(state)
            },
        )
    section.reload()
}
