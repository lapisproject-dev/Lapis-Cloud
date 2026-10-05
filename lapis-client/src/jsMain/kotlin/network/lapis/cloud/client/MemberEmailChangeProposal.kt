package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EmailChangeCapabilityDto
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangePendingDto
import network.lapis.cloud.shared.domain.MailDeliveryState
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerDenyReason
import network.lapis.cloud.shared.rpc.IMemberEmailChangeService

/** The RPC surface of the proposal section -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface MemberEmailProposalRpc {
    suspend fun capability(): EmailChangeCapabilityDto

    suspend fun pending(memberId: String): EmailChangePendingDto?

    suspend fun propose(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
    ): EmailChangePendingDto

    suspend fun override(
        memberId: String,
        newEmail: String,
        newEmailRepeat: String,
        reason: String,
    ): EmailChangePendingDto

    suspend fun withdraw(changeId: String)
}

internal fun liveMemberEmailProposalRpc(): MemberEmailProposalRpc =
    object : MemberEmailProposalRpc {
        override suspend fun capability() = rpcService<IMemberEmailChangeService>().getEmailChangeCapability()

        override suspend fun pending(memberId: String) =
            rpcService<IMemberEmailChangeService>().getPendingEmailChangeForMember(memberId).pending

        override suspend fun propose(
            memberId: String,
            newEmail: String,
            newEmailRepeat: String,
        ) = rpcService<IMemberEmailChangeService>().proposeEmailChange(memberId, newEmail, newEmailRepeat)

        override suspend fun override(
            memberId: String,
            newEmail: String,
            newEmailRepeat: String,
            reason: String,
        ) = rpcService<IMemberEmailChangeService>().requestEmailChangeOverride(memberId, newEmail, newEmailRepeat, reason)

        override suspend fun withdraw(changeId: String) {
            rpcService<IMemberEmailChangeService>().withdrawEmailChange(changeId)
        }
    }

/**
 * Welle V1.9.56 -- whether the roster editor offers "Änderung vorschlagen" for [row]. Mirrors `EmailChangeService` (the server stays the
 * only authority, this only avoids offering a refused action): BOARD or ADMIN, never for the caller's own row (the owner changes their
 * own address in "Meine Daten"), never for an anonymized, GUEST or DECEASED member, and a BOARD caller never against a BOARD/TREASURER/
 * ADMIN account.
 */
fun canProposeEmailChangeOf(
    callerRole: AccountRole?,
    callerMemberId: String?,
    row: MemberAdminRowDto,
): Boolean {
    if (row.anonymized) return false
    if (row.id == callerMemberId) return false
    if (row.status == MemberStatus.GUEST || row.status == MemberStatus.DECEASED) return false
    if (row.role != null &&
        row.role in setOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN) &&
        callerRole != AccountRole.ADMIN
    ) {
        return false
    }
    return callerRole == AccountRole.BOARD || callerRole == AccountRole.ADMIN
}

private class ProposalState(
    val capability: EmailChangeCapabilityDto,
    val pending: EmailChangePendingDto?,
)

private const val OVERRIDE_REASON_MIN = 10
private const val OVERRIDE_REASON_MAX = 500

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the address section of the roster editor. The address is the member's login identity,
 * so the board/administrator never edits it directly: they PROPOSE a new one. The member takes it over with their password -- or, where
 * no password exists, it takes effect once the new address is confirmed AND a 72 hour warning period passed. An ADMIN can additionally
 * start the emergency path ("Notfall ohne Annahme durch das Mitglied", with a reason). All of it needs outbound mail: without it the
 * button is disabled with the reason, never silently missing.
 *
 * An open change is shown as a quiet status strip (a neutral badge, not a warning colour: it is a normal state) with the MASKED new
 * address and the times, and a "Zurückziehen" button when the caller may withdraw it. Server text is never shown, the full address
 * the caller typed is never echoed back, and no value reaches a toast.
 */
internal fun renderEmailChangeProposalSection(
    modal: Container,
    row: MemberAdminRowDto,
    callerRole: AccountRole?,
    legendGroup: LegendGroup,
    rpc: MemberEmailProposalRpc = liveMemberEmailProposalRpc(),
    onChanged: () -> Unit,
) {
    modal.h2(tr("E-Mail-Adresse ändern")) { addCssClass("h6") }
    modal.div(
        tr(
            "Die E-Mail-Adresse ist die Anmeldeadresse des Mitglieds und kann nicht direkt geändert werden. " +
                "Sie schlagen eine neue Adresse vor: Das Mitglied übernimmt sie mit seinem Passwort -- oder, wo es kein " +
                "Passwort gibt, wird sie nach Bestätigung der neuen Adresse und einer Warnfrist von 72 Stunden wirksam.",
        ),
    ) { addCssClasses("text-muted small") }
    lateinit var section: DataSection
    section =
        modal.dataSection<ProposalState>(
            isEmpty = { false },
            load = { guarded { ProposalState(rpc.capability(), rpc.pending(row.id)) } },
            render = { panel, state ->
                val pending = state.pending
                if (pending != null) {
                    renderPendingStrip(panel, pending, rpc) { section.reload() }
                } else {
                    renderProposalForm(panel, row, callerRole, state.capability, legendGroup, rpc) {
                        section.reload()
                        onChanged()
                    }
                }
            },
        )
    section.reload()
}

private fun renderPendingStrip(
    panel: SimplePanel,
    pending: EmailChangePendingDto,
    rpc: MemberEmailProposalRpc,
    onChanged: () -> Unit,
) {
    val strip = panel.vPanel(spacing = 6) { addCssClasses("alert alert-secondary mb-0") }
    val head = strip.hPanel(spacing = 8) { addCssClasses("flex-wrap align-items-center") }
    head.statusBadge(tr("Änderung ausstehend"), "secondary")
    head.span {
        untrustedSpan(pending.newEmailMasked) { addCssClass("fw-bold") }
    }
    val effective = pending.effectiveAt
    val timing =
        if (pending.kind == EmailChangeKind.PROPOSAL) {
            gettext(
                "Wartet auf die Annahme durch das Mitglied. Der Vorschlag läuft ab am %1.",
                formatSystemDateTime(pending.expiresAt),
            )
        } else {
            val takesEffect =
                if (effective != null) {
                    gettext("Wird frühestens am %1 wirksam.", formatSystemDateTime(effective))
                } else {
                    gettext("Wird nach der Warnfrist wirksam.")
                }
            if (pending.newEmailConfirmed) takesEffect else takesEffect + " " + gettext("Die neue Adresse ist noch nicht bestätigt.")
        }
    strip.div(timing) { addCssClass("small") }
    if (pending.withdrawable) {
        val withdraw = newActionButton(ActionIcon.UNDO, tr("Zurückziehen"), ButtonStyle.OUTLINESECONDARY)
        strip.add(withdraw)
        withdraw.onClick {
            runGuardedAction(withdraw) {
                val ok = memberAdminGuarded { rpc.withdraw(pending.changeId) } != null
                if (ok) notifySuccess(tr("Änderung zurückgezogen."))
                onChanged()
            }
        }
    }
}

private fun renderProposalForm(
    panel: SimplePanel,
    row: MemberAdminRowDto,
    callerRole: AccountRole?,
    capability: EmailChangeCapabilityDto,
    legendGroup: LegendGroup,
    rpc: MemberEmailProposalRpc,
    onChanged: () -> Unit,
) {
    val open = newActionButton(ActionIcon.EDIT, tr("Änderung vorschlagen"), ButtonStyle.OUTLINEPRIMARY)
    panel.add(open)
    if (capability.mailDelivery == MailDeliveryState.NOT_CONFIGURED) {
        open.disabled = true
        panel.div(
            tr(
                "Auf dieser Instanz ist kein Mailversand eingerichtet. Adressänderungen für andere Mitglieder sind daher nicht möglich.",
            ),
        ) { addCssClasses("text-muted small") }
        return
    }
    val host = panel.vPanel(spacing = 6)
    host.hide()
    val form = host.lapisForm(legendGroup)
    val newEmail =
        form.textField(
            label = tr("Neue E-Mail-Adresse"),
            type = InputType.EMAIL,
            required = true,
            rule = { FormRules.email(value = it) },
        )
    val repeat = form.textField(label = tr("Neue E-Mail-Adresse wiederholen"), type = InputType.EMAIL, required = true)
    form.crossFieldRule(field = repeat) {
        if (newEmail.value.trim().lowercase() == repeat.value.trim().lowercase()) {
            FieldCheck.Ok
        } else {
            FieldCheck.Invalid(gettext("Die beiden Adressen stimmen nicht überein."))
        }
    }
    val emergency =
        if (callerRole == AccountRole.ADMIN) {
            form.checkField(
                label = tr("Notfall ohne Annahme durch das Mitglied"),
                hint =
                    tr(
                        "Nur wenn das Mitglied keinen Zugang mehr hat. " +
                            "Die Änderung wird erst nach Bestätigung der neuen Adresse und 72 Stunden wirksam.",
                    ),
            )
        } else {
            null
        }
    val reason =
        if (emergency != null) {
            form.textAreaField(
                label = tr("Begründung"),
                rows = 2,
                hint = gettext("%1 bis %2 Zeichen.", OVERRIDE_REASON_MIN, OVERRIDE_REASON_MAX),
                rule = { text ->
                    if (emergency.value != "true" || text.trim().length in OVERRIDE_REASON_MIN..OVERRIDE_REASON_MAX) {
                        FieldCheck.Ok
                    } else {
                        FieldCheck.Invalid(gettext("%1 bis %2 Zeichen.", OVERRIDE_REASON_MIN, OVERRIDE_REASON_MAX))
                    }
                },
            )
        } else {
            null
        }

    // Welle V1.9.57 "Admin-Peer-Schutz": the emergency path never targets ANOTHER administrator (its only safeguard is the target's
    // objection right, and one person could take the account over). The switch stays visible, disabled, with the reason as text.
    val emergencyProtected = emergency != null && row.role == AccountRole.ADMIN
    if (emergency != null && emergencyProtected) {
        emergency.control.disabled = true
        form.panel.peerProtectionNotice(peerDenyText(PeerDenyReason.TARGET_IS_ADMIN, PeerAction.EMAIL_OVERRIDE))
    }

    fun emergencyOn(): Boolean = !emergencyProtected && emergency?.value == "true"

    fun refreshReason() = reason?.setVisible(emergencyOn())
    emergency?.subscribe { refreshReason() }
    refreshReason()

    val send = newActionButton(ActionIcon.SEND, tr("Vorschlag senden"), ButtonStyle.PRIMARY)
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY)
    form.buttons(primary = send, cancel = cancel)

    open.onClick {
        host.show()
        open.hide()
        newEmail.focus()
    }
    cancel.onClick {
        host.hide()
        open.show()
    }
    send.onClick {
        form.submit(send) {
            val ok =
                memberAdminGuarded {
                    if (emergencyOn()) {
                        rpc.override(row.id, newEmail.value.trim(), repeat.value.trim(), reason?.value?.trim().orEmpty())
                    } else {
                        rpc.propose(row.id, newEmail.value.trim(), repeat.value.trim())
                    }
                } != null
            if (ok) notifySuccess(tr("Vorschlag gesendet."))
            onChanged()
        }
    }
}
