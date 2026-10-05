package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PeerActionDecisionsDto
import network.lapis.cloud.shared.domain.PrivilegedActionKind
import network.lapis.cloud.shared.domain.PrivilegedActionOverviewDto
import network.lapis.cloud.shared.domain.PrivilegedActionRequestDto
import network.lapis.cloud.shared.domain.PrivilegedActionStatus
import network.lapis.cloud.shared.domain.PrivilegedPasswordResultDto
import network.lapis.cloud.shared.rpc.IPrivilegedActionService

/*
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the card "Ausstehende Freigaben" on top of the member administration (ADMIN only, and only
 * while there is something in it): what waits for the caller's approval, and what the caller requested themselves.
 *
 * Placement rules (guideline R36/R36B/R56-R58): the card sits in the page body, NOT in `PageHeader.actionSlot`; it is no create form,
 * so no "Neu ..." title; verbs use the standard icons (Freigabe erteilen = APPROVE, Ablehnen = REJECT, Zurückziehen = UNDO, the request
 * button = SEND), "Passwort jetzt erzeugen" is a domain verb without a standard picture and keeps its text only.
 *
 * Every name and every reason shown here is member-supplied free text: it goes through [sanitizeUntrustedI18nText]. The generated
 * password is shown ONCE, in the same receipt as the direct path ([renderTemporaryPasswordReceipt]) inside a dialog that is NOT closed
 * automatically (a fade-out would take the only visible copy away before anybody could note it).
 */

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the RPC surface of the four-eyes screens, as an interface so DOM tests can drive the card, the
 * request dialogs and the password dialog without a server. Exceptions propagate (the callers wrap them in [memberAdminGuarded]).
 *
 * Lives in this file on purpose: the live implementation below reads (`getPeerActionDecisions`, `listPrivilegedActions`), and the card
 * of this file loads through a `dataSection` -- the R34 ratchet (`ClientDataStateTripwireTest`) is per file and must not grow.
 */
internal interface PrivilegedActionRpc {
    suspend fun decisions(memberId: String): PeerActionDecisionsDto

    suspend fun requestTemporaryPassword(
        memberId: String,
        reason: String,
    ): PrivilegedActionRequestDto

    suspend fun requestDemotion(
        memberId: String,
        newRole: AccountRole,
        reason: String,
    ): PrivilegedActionRequestDto

    suspend fun requestSuspension(
        memberId: String,
        newStatus: MemberStatus,
        reason: String,
    ): PrivilegedActionRequestDto

    suspend fun approve(requestId: String): PrivilegedActionRequestDto

    suspend fun reject(requestId: String): PrivilegedActionRequestDto

    suspend fun withdraw(requestId: String): PrivilegedActionRequestDto

    suspend fun executeTemporaryPassword(requestId: String): PrivilegedPasswordResultDto

    suspend fun overview(): PrivilegedActionOverviewDto
}

internal fun livePrivilegedActionRpc(): PrivilegedActionRpc =
    object : PrivilegedActionRpc {
        override suspend fun decisions(memberId: String) = rpcService<IPrivilegedActionService>().getPeerActionDecisions(memberId)

        override suspend fun requestTemporaryPassword(
            memberId: String,
            reason: String,
        ) = rpcService<IPrivilegedActionService>().requestTemporaryPassword(memberId, reason)

        override suspend fun requestDemotion(
            memberId: String,
            newRole: AccountRole,
            reason: String,
        ) = rpcService<IPrivilegedActionService>().requestDemotion(memberId, newRole, reason)

        override suspend fun requestSuspension(
            memberId: String,
            newStatus: MemberStatus,
            reason: String,
        ) = rpcService<IPrivilegedActionService>().requestSuspension(memberId, newStatus, reason)

        override suspend fun approve(requestId: String) = rpcService<IPrivilegedActionService>().approve(requestId)

        override suspend fun reject(requestId: String) = rpcService<IPrivilegedActionService>().reject(requestId)

        override suspend fun withdraw(requestId: String) = rpcService<IPrivilegedActionService>().withdraw(requestId)

        override suspend fun executeTemporaryPassword(requestId: String) =
            rpcService<IPrivilegedActionService>().executeTemporaryPassword(requestId)

        override suspend fun overview() = rpcService<IPrivilegedActionService>().listPrivilegedActions()
    }

/** The sentence of an action, `gettext` only (it is an argument of other `gettext` calls, never a `tr` marker). */
internal fun privilegedActionLabel(request: PrivilegedActionRequestDto): String =
    when (request.action) {
        PrivilegedActionKind.TEMP_PASSWORD -> gettext("Temporäres Passwort")
        PrivilegedActionKind.DEMOTE ->
            gettext("Rolle entziehen (neue Rolle: %1)", request.requestedRole?.let { accountRoleLabel(it) }.orEmpty())
        PrivilegedActionKind.SUSPEND ->
            gettext("Zugang sperren (neuer Status: %1)", request.requestedStatus?.let { memberStatusLabel(it) }.orEmpty())
    }

internal fun privilegedStatusLabel(status: PrivilegedActionStatus): String =
    when (status) {
        PrivilegedActionStatus.PENDING -> gettext("wartet auf Freigabe")
        PrivilegedActionStatus.APPROVED_WAITING -> gettext("freigegeben, wartet auf die Erzeugung des Passworts")
        PrivilegedActionStatus.EXECUTED -> gettext("ausgeführt")
        PrivilegedActionStatus.REJECTED -> gettext("abgelehnt")
        PrivilegedActionStatus.WITHDRAWN -> gettext("zurückgezogen")
        PrivilegedActionStatus.VETOED -> gettext("vom betroffenen Konto widersprochen")
        PrivilegedActionStatus.EXPIRED -> gettext("abgelaufen")
        PrivilegedActionStatus.INVALIDATED -> gettext("nicht mehr gültig (die Voraussetzungen haben sich geändert)")
    }

/**
 * The card. [rpc] and [confirm] are injectable for DOM tests. Returns a function that reloads the card -- the roster calls it after a
 * request was made, so the new entry appears at once.
 */
internal fun renderPrivilegedActionsCard(
    root: Container,
    rpc: PrivilegedActionRpc = livePrivilegedActionRpc(),
    confirm: AdminActionConfirm = liveAdminActionConfirm,
    onExecuted: () -> Unit = {},
    now: () -> kotlinx.datetime.LocalDateTime = ::organizationNow,
): () -> Unit {
    val host = root.vPanel(spacing = 8)
    host.setAttribute("data-privileged-actions-card", "true")
    host.hide()

    lateinit var section: DataSection

    fun reload() = section.reload()

    fun requestRow(
        panel: SimplePanel,
        request: PrivilegedActionRequestDto,
        awaitingMyApproval: Boolean,
    ) {
        val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
        row.div(
            sanitizeUntrustedI18nText(
                gettext("%1 für %2", privilegedActionLabel(request), request.targetDisplayName),
            ),
        ) { addCssClass("fw-bold") }
        row.div(
            sanitizeUntrustedI18nText(
                gettext("Beantragt von %1 am %2.", request.actorDisplayName, formatSystemDateTime(request.createdAt)),
            ),
        ) { addCssClasses("text-muted small") }
        row.div(sanitizeUntrustedI18nText(gettext("Begründung: %1", request.reason))) { addCssClasses("small") }
        if (request.status.isOpen) {
            row.div(gettext("Offen bis %1.", formatSystemDateTime(request.expiresAt))) { addCssClasses("text-muted small") }
        } else {
            row.div(gettext("Stand: %1.", privilegedStatusLabel(request.status))) { addCssClasses("text-muted small") }
        }
        val buttons = row.vPanel(spacing = 4)
        if (awaitingMyApproval) {
            val approve = buttons.actionButton(ActionIcon.APPROVE, tr("Freigabe erteilen"), style = ButtonStyle.SUCCESS)
            val reject = buttons.actionButton(ActionIcon.REJECT, tr("Ablehnen"), style = ButtonStyle.OUTLINEDANGER)
            approve.onClick {
                confirm.show(
                    gettext("Antrag freigeben"),
                    sanitizeUntrustedI18nText(
                        gettext(
                            "%1 für %2 jetzt freigeben? Die Freigabe wird im Prüfprotokoll vermerkt.",
                            privilegedActionLabel(request),
                            request.targetDisplayName,
                        ),
                    ),
                    gettext("Freigabe erteilen"),
                ) {
                    approve.disabled = true
                    reject.disabled = true
                    AppScope.launch {
                        val result = memberAdminGuarded { rpc.approve(request.id) }
                        if (result != null) {
                            if (result.status == PrivilegedActionStatus.INVALIDATED) {
                                notifyError(tr("Der Antrag ist nicht mehr gültig: die Voraussetzungen haben sich geändert."))
                            } else {
                                notifySuccess(tr("Freigegeben."))
                            }
                            onExecuted()
                        }
                        reload()
                    }
                }
            }
            reject.onClick {
                approve.disabled = true
                reject.disabled = true
                AppScope.launch {
                    val result = memberAdminGuarded { rpc.reject(request.id) }
                    if (result != null) notifySuccess(tr("Abgelehnt."))
                    reload()
                }
            }
        } else {
            if (request.status.isOpen) {
                val withdraw = buttons.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)
                withdraw.onClick {
                    withdraw.disabled = true
                    AppScope.launch {
                        val result = memberAdminGuarded { rpc.withdraw(request.id) }
                        if (result != null) notifySuccess(tr("Antrag zurückgezogen."))
                        reload()
                    }
                }
            }
            val notBefore = request.notBefore
            val executeUntil = request.executeUntil
            if (request.action == PrivilegedActionKind.TEMP_PASSWORD &&
                request.status == PrivilegedActionStatus.APPROVED_WAITING &&
                notBefore != null &&
                executeUntil != null
            ) {
                val nowOrg = now()
                if (nowOrg < toOrganizationZone(notBefore)) {
                    buttons.div(
                        gettext("Das Passwort kann ab %1 erzeugt werden (Widerspruchsfrist des Kontos).", formatSystemDateTime(notBefore)),
                    ) {
                        addCssClasses("text-muted small")
                    }
                } else {
                    buttons.div(gettext("Das Passwort kann bis %1 erzeugt werden.", formatSystemDateTime(executeUntil))) {
                        addCssClasses("text-muted small")
                    }
                    val generate = Button(tr("Passwort jetzt erzeugen"), style = ButtonStyle.WARNING)
                    buttons.add(generate)
                    generate.onClick {
                        generate.disabled = true
                        AppScope.launch {
                            val result = memberAdminGuarded { rpc.executeTemporaryPassword(request.id) }
                            if (result != null) {
                                openPasswordReceiptDialog(result.generatedPassword, result.revokedSessionCount, result.memberNotified)
                                onExecuted()
                            }
                            reload()
                        }
                    }
                }
            }
        }
    }

    fun renderCard(
        panel: SimplePanel,
        overview: PrivilegedActionOverviewDto,
    ) {
        val card = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
        card.h2(tr("Ausstehende Freigaben")) { addCssClass("h5") }
        if (overview.awaitingMyApproval.isNotEmpty()) {
            card.h3(tr("Wartet auf Ihre Freigabe")) { addCssClass("h6") }
            overview.awaitingMyApproval.forEach { requestRow(card, it, awaitingMyApproval = true) }
        }
        if (overview.requestedByMe.isNotEmpty()) {
            card.h3(tr("Von Ihnen beantragt")) { addCssClass("h6") }
            overview.requestedByMe.forEach { requestRow(card, it, awaitingMyApproval = false) }
        }
    }

    fun isEmptyOverview(overview: PrivilegedActionOverviewDto) = overview.awaitingMyApproval.isEmpty() && overview.requestedByMe.isEmpty()

    // The card exists only while there is something in it: the host stays hidden until the first answer and while the answer is empty; a
    // failed load shows the shared error state with "Erneut versuchen" (host visible).
    section =
        host.dataSection<PrivilegedActionOverviewDto>(
            emptyText = " ",
            isEmpty = { isEmptyOverview(it) },
            onSettled = { settled -> if (settled != null && isEmptyOverview(settled)) host.hide() else host.show() },
            load = { memberAdminGuarded { rpc.overview() } },
            render = { panel, overview -> renderCard(panel, overview) },
        )
    section.reload()
    return { section.reload() }
}

/** The one-time display of a generated password: a dialog that stays until it is closed. */
private fun openPasswordReceiptDialog(
    password: String,
    revokedSessionCount: Int,
    memberNotified: network.lapis.cloud.shared.domain.MailDeliveryState,
) {
    val modal = Modal(caption = tr("Temporäres Passwort"))
    val body = modal.div()
    renderTemporaryPasswordReceipt(
        body = body,
        generatedPassword = password,
        revokedSessionCount = revokedSessionCount,
        memberNotified = memberNotified,
    )
    modal.addButton(newActionButton(ActionIcon.CLOSE, tr("Schließen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.show()
}
