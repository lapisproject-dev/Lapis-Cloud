package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.select.Select
import io.kvision.form.text.Text
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.PartgReportScope
import network.lapis.cloud.shared.domain.PartgReviewerInvitationDto
import network.lapis.cloud.shared.domain.PartgReviewerInvitationStatus
import network.lapis.cloud.shared.domain.PartgReviewerRules
import network.lapis.cloud.shared.domain.PartgVersionDto

/**
 * V1.9.310 (R6b) -- the board's section "Lesezugang für die Prüfung" under the kept versions: who may read which versions, until when, what they did,
 * and the buttons to invite and to revoke. BOARD and ADMIN only (the server refuses everybody else). The link of an invitation is shown ONCE, in
 * the dialog that creates it, and is gone from the page when the dialog closes; the list never carries a token.
 */
internal fun SimplePanel.renderReviewerAccess(
    context: PartyReportContext,
    versions: List<PartgVersionDto>,
) {
    div(tr("Lesezugang für die Prüfung"), className = "fw-bold mt-4") { setAttribute("data-partg-reviewer", "1") }
    div(
        tr(
            "Ein Prüfer ohne Konto kann ausgewählte festgehaltene Versionen der Gesamtpartei lesen, herunterladen und vergleichen. " +
                "Sie geben den Link selbst weiter; das System sendet dem Prüfer nur einen Code per E-Mail. " +
                "Der Zugang endet mit dem Ablauf der Einladung oder wenn Sie ihn widerrufen.",
        ),
        className = "small text-muted",
    )
    val wholeParty = versions.filter { it.scopeKey == PartgReportScope.ALL_KEY }.sortedBy { it.versionNumber }
    val host = simplePanel()

    fun reload() {
        AppScope.launch {
            val list = guarded { partyReportRpc().listReviewerInvitations(context.year) }
            host.removeAll()
            if (list != null) host.renderInvitations(list = list, versions = wholeParty, context = context, reload = ::reload)
        }
    }
    actionGroup {
        actionButton(ActionIcon.ADD, tr("Prüfer einladen"), ButtonStyle.OUTLINEPRIMARY) {
            id = "partg-reviewer-invite"
            if (wholeParty.isEmpty()) {
                disabled = true
                setAttribute("aria-disabled", "true")
            }
            onClick { openReviewerInviteDialog(context = context, versions = wholeParty, prefill = null, onClosed = ::reload) }
        }
    }
    if (wholeParty.isEmpty()) {
        div(tr("Zum Einladen wird mindestens eine festgehaltene Version der Gesamtpartei gebraucht."), className = "small text-muted")
    }
    reload()
}

private fun SimplePanel.renderInvitations(
    list: List<PartgReviewerInvitationDto>,
    versions: List<PartgVersionDto>,
    context: PartyReportContext,
    reload: () -> Unit,
) {
    if (list.isEmpty()) {
        div(tr("Noch keine Einladung."), className = "small text-muted") { setAttribute("data-partg-reviewer-empty", "1") }
        return
    }
    list.forEach { invitation ->
        div(className = "card mb-2") {
            setAttribute("data-partg-reviewer-invitation", invitation.id)
            div(className = "card-body") {
                div(className = "d-flex flex-wrap align-items-center gap-2") {
                    if (invitation.email == null) {
                        span(tr("E-Mail-Adresse gelöscht"), className = "fw-semibold text-muted")
                    } else {
                        untrustedSpanLine(invitation.email.orEmpty())
                    }
                    span(
                        reviewerStatusWord(invitation.status),
                        className = "badge rounded-pill text-bg-${reviewerStatusColor(invitation.status)}",
                    ) {
                        setAttribute("data-partg-reviewer-status", invitation.status.name)
                    }
                }
                div(reviewerVersionsText(invitation.versionNumbers), className = "small")
                div(gettext("Gültig bis %1", formatSystemDateTime(invitation.expiresAt)), className = "small text-muted")
                div(
                    invitation.lastUsedAt?.let { gettext("Zuletzt genutzt: %1", formatSystemDateTime(it)) } ?: tr("Noch nicht genutzt"),
                    className = "small text-muted",
                )
                actionGroup {
                    if (invitation.status == PartgReviewerInvitationStatus.ACTIVE ||
                        invitation.status == PartgReviewerInvitationStatus.LOCKED
                    ) {
                        actionButton(ActionIcon.REVOKE, tr("Widerrufen"), ButtonStyle.OUTLINEDANGER, small = true) {
                            onClick {
                                confirmDialog(
                                    title = tr("Zugang widerrufen?"),
                                    message = tr("Der Zugang endet sofort, auch laufende Sitzungen."),
                                    confirmLabel = tr("Widerrufen"),
                                    focusCancel = true,
                                    onConfirm = {
                                        AppScope.launch {
                                            if (guarded { partyReportRpc().revokeReviewerInvitation(invitation.id) } != null) reload()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    actionButton(ActionIcon.ADD, tr("Neue Einladung aus dieser"), ButtonStyle.OUTLINESECONDARY, small = true) {
                        onClick {
                            openReviewerInviteDialog(
                                context = context,
                                versions = versions,
                                prefill = ReviewerPrefill(email = invitation.email, versionIds = invitation.versionIds.toSet()),
                                onClosed = reload,
                            )
                        }
                    }
                }
                val details = Tag(TAG.DETAILS, className = "small mt-1")
                details.add(Tag(TAG.SUMMARY, content = gettext("Ereignisse (%1)", invitation.events.size.toString())))
                if (invitation.events.isEmpty()) {
                    details.add(Tag(TAG.DIV, content = tr("Keine Ereignisse."), className = "text-muted"))
                } else {
                    invitation.events.forEach { event ->
                        details.add(Tag(TAG.DIV, content = formatSystemDateTime(event.occurredAt) + " – " + reviewerEventText(event)))
                    }
                }
                add(details)
            }
        }
    }
}

private fun reviewerStatusColor(status: PartgReviewerInvitationStatus): String =
    when (status) {
        PartgReviewerInvitationStatus.ACTIVE -> "success"
        PartgReviewerInvitationStatus.EXPIRED -> "secondary"
        PartgReviewerInvitationStatus.REVOKED -> "secondary"
        PartgReviewerInvitationStatus.LOCKED -> "danger"
    }

/** The address of an invitation is data of a third party: shown as text, never as markup. */
private fun SimplePanel.untrustedSpanLine(text: String) {
    untrustedDiv(text, className = "fw-semibold")
}

/** What "Neue Einladung aus dieser" fills in: the address and the versions of the old invitation (the board still ticks and confirms). */
internal class ReviewerPrefill(
    val email: String?,
    val versionIds: Set<String>,
)

/**
 * "Prüfer einladen": the address, the versions (all of the whole party, all ticked), the validity (7, 14, 30, 90 days, default 14) -- no free text.
 * After "Einladung erstellen" the SAME dialog shows the link once, with "Kopieren" and the warning ABOVE the close button. Closing the dialog
 * (button, X or ESC) removes the link from the page.
 */
internal fun openReviewerInviteDialog(
    context: PartyReportContext,
    versions: List<PartgVersionDto>,
    prefill: ReviewerPrefill?,
    onClosed: () -> Unit,
) {
    val modal = Modal(caption = tr("Prüfer einladen"))
    val form = modal.simplePanel()
    val result = modal.simplePanel()
    form.p(
        tr(
            "Der Prüfer bekommt Lesezugang zu den gewählten Versionen. Das System sendet keine E-Mail mit dem Link, " +
                "sondern später nur einen Code, den der Prüfer anfordert.",
        ),
    ) { addCssClasses("small") }
    val email = Text(type = InputType.EMAIL, label = tr("E-Mail-Adresse des Prüfers"))
    email.id = "partg-reviewer-email"
    email.input.setAttribute("autocomplete", "off")
    email.maxlength = PartgReviewerRules.MAX_EMAIL_LENGTH
    email.value = prefill?.email
    form.add(email)
    form.div(gettext("Berichtsjahr: %1", context.year.toString()), className = "small fw-semibold mt-2")
    form.div(tr("Versionen"), className = "fw-semibold mt-2")
    val boxes =
        versions.map { version ->
            val label = gettext("Version %1, festgehalten am %2", version.versionNumber.toString(), formatSystemDateTime(version.frozenAt))
            val box = CheckBox(value = prefill?.versionIds?.let { version.id in it } ?: true, label = label)
            box.id = "partg-reviewer-version-${version.versionNumber}"
            form.add(box)
            version to box
        }
    val validity =
        Select(
            options = PartgReviewerRules.VALIDITY_DAYS.map { it.toString() to reviewerValidityLabel(it) },
            value = PartgReviewerRules.DEFAULT_VALIDITY_DAYS.toString(),
            label = tr("Gültigkeit"),
        )
    validity.id = "partg-reviewer-validity"
    form.add(validity)
    val problem = form.div { addCssClasses("alert alert-danger small mt-2") }
    problem.visible = false
    problem.setAttribute("role", "alert")

    val linkField = Text(label = tr("Link für den Prüfer"))
    linkField.id = "partg-reviewer-link"
    linkField.readonly = true
    var closed = false

    fun clearAndClose() {
        if (closed) return
        closed = true
        linkField.value = null
        result.removeAll()
        modal.hide()
        onClosed()
    }
    // the X and ESC close the dialog without our button: the link must leave the page then too
    modal.addAfterInsertHook { vnode ->
        (vnode.elm as? org.w3c.dom.HTMLElement)?.addEventListener("hidden.bs.modal", { if (!closed) clearAndClose() })
    }

    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { clearAndClose() } }
    val create = newActionButton(ActionIcon.ADD, tr("Einladung erstellen"), ButtonStyle.PRIMARY)
    create.id = "partg-reviewer-create"
    modal.addButton(cancel)
    modal.addButton(create)

    fun chosenIds() = boxes.filter { (_, box) -> box.value }.map { (version, _) -> version.id }

    // without a chosen version the button is inactive and a message says why (the box change is reported at once on subscription)
    fun refresh() {
        val none = chosenIds().isEmpty()
        create.disabled = none
        create.setAttribute("aria-disabled", none.toString())
        if (none) {
            problem.content = reviewerProblemText(ReviewerInviteProblem.NO_VERSION)
            problem.visible = true
        } else if (problem.content == reviewerProblemText(ReviewerInviteProblem.NO_VERSION)) {
            problem.visible = false
        }
    }
    boxes.forEach { (_, box) -> box.subscribe { refresh() } }
    refresh()
    create.onClick {
        val chosen = chosenIds()
        val problems = reviewerInviteProblems(email = email.value.orEmpty(), selectedVersions = chosen.size)
        if (problems.isNotEmpty()) {
            val message = problems.joinToString(" ") { reviewerProblemText(it) }
            problem.content = message
            problem.visible = true
            return@onClick
        }
        problem.visible = false
        create.disabled = true
        AppScope.launch {
            val created =
                guarded {
                    partyReportRpc().createReviewerInvitation(
                        year = context.year,
                        versionIds = chosen,
                        validityDays = validity.value?.toIntOrNull() ?: PartgReviewerRules.DEFAULT_VALIDITY_DAYS,
                        email = email.value.orEmpty(),
                    )
                }
            if (created == null) {
                create.disabled = false
                return@launch
            }
            form.visible = false
            create.visible = false
            cancel.visible = false
            linkField.value = created.link
            result.add(linkField)
            result.add(
                newActionButton(ActionIcon.COPY, tr("Link kopieren"), ButtonStyle.OUTLINEPRIMARY, small = true).apply {
                    id = "partg-reviewer-copy"
                    onClick { copyLink(created.link, linkField) }
                },
            )
            result.div(
                tr("Der Link wird nur jetzt angezeigt. Bitte geben Sie ihn dem Prüfer selbst weiter."),
                className = "alert alert-warning small mt-2",
            ) { setAttribute("role", "note") }
            modal.addButton(newActionButton(ActionIcon.CLOSE, tr("Schließen"), ButtonStyle.PRIMARY).apply { onClick { clearAndClose() } })
        }
    }
    modal.show()
}

private fun copyLink(
    link: String,
    field: Text,
) {
    val clipboard: dynamic = window.navigator.asDynamic().clipboard
    if (clipboard != null && clipboard != undefined) {
        clipboard.writeText(link)
        notifySuccess(tr("Link kopiert."))
    } else {
        field.focus()
        notifyError(tr("Bitte markieren Sie den Link und kopieren Sie ihn selbst."))
    }
}
