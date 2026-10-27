package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Row
import io.kvision.table.cell
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IGovernanceService
import network.lapis.cloud.shared.rpc.IMailingService

// V1.9.111 "Dynamische Mailinglisten" -- the rows and blocks of the communication screen that belong to automatic lists: the two badges
// ("Automatisch", "Pflichtliste" -- always WITH text, never colour alone, so they read in greyscale), the member's own row with the
// explanation of why the list reaches them and the opt-out switch, and the board's detail block with "Regel bearbeiten".

/** The badges of [list]: "Automatisch" for a rule-based list, "Pflichtliste" for a mandatory one. A manual list has none. */
internal fun Container.mailingListBadges(list: MailingListDto) {
    if (list.kind == MailingListKind.DYNAMIC) statusBadge(gettext("Automatisch"), "secondary")
    if (list.mandatory) statusBadge(gettext("Pflichtliste"), "warning")
}

/**
 * The cells of an automatic list in the subscription table (V1.9.231: a table row instead of a free-standing row): name, badges and WHY
 * the list reaches the caller; the status; and the opt-out as an icon action. No count and no tracking switches (an automatic list is never
 * tracked). A mandatory list only says that it cannot be left; a board member the rule does not include sees the description of the whole
 * rule and no action.
 */
internal fun renderDynamicSelfServiceCells(
    row: Row,
    list: MailingListDto,
    refresh: () -> Unit,
) {
    val reachesCaller = list.isSubscribedByCurrentMember || list.currentMemberOptedOutAt != null
    val nameCell = row.cell()
    val head = nameCell.hPanel(spacing = 8) { addCssClass("align-items-center") }
    head.div(sanitizeUntrustedI18nText(list.name)) { addCssClass("fw-bold") }
    head.mailingListBadges(list)
    list.ruleExplanation?.let { explanation ->
        nameCell.div(sanitizeUntrustedI18nText(explainRule(explanation, forMember = reachesCaller))) { addCssClasses("text-muted small") }
    }
    if (list.mandatory && reachesCaller) nameCell.div(tr("Pflichtliste: keine Abmeldung möglich")) { addCssClasses("text-muted small") }
    row.cell { if (list.isSubscribedByCurrentMember) statusBadge(tr("Abonniert"), "success") }
    val actionsCell = row.cell { addCssClasses("text-end text-nowrap") }
    val actions = actionsCell.tableActionGroup().apply { addCssClass("justify-content-end") }
    when {
        list.mandatory && reachesCaller -> Unit
        list.isSubscribedByCurrentMember -> {
            val optOut = actions.tableActionButton(ActionIcon.REVOKE, tr("Von der Liste abmelden"))
            optOut.onClick {
                runGuardedAction(optOut) {
                    val done = guarded { rpcService<IMailingService>().optOutOfDynamicList(list.id) }
                    if (done != null) refresh()
                }
            }
        }
        list.currentMemberOptedOutAt != null -> {
            val back = actions.tableActionButton(ActionIcon.UNDO, tr("Wieder anmelden"), ButtonStyle.OUTLINEPRIMARY)
            back.onClick {
                runGuardedAction(back) {
                    val done = guarded { rpcService<IMailingService>().revokeOptOut(list.id) }
                    if (done != null) refresh()
                }
            }
        }
    }
}

/**
 * The board's block in the detail of an automatic list (it replaces "Abonnenten" and "Mitglied hinzufügen": nobody is subscribed by
 * hand): badges, the description of the rule, the current count, and "Regel bearbeiten" -- a form with the same builder as the create form.
 * [onChanged] runs after a saved rule so the caller can refresh both list panels.
 */
internal fun renderDynamicListAdminBlock(
    detail: SimplePanel,
    list: MailingListDto,
    onChanged: () -> Unit,
) {
    val head = detail.hPanel(spacing = 8) { addCssClass("align-items-center") }
    head.mailingListBadges(list)
    list.ruleExplanation?.let { explanation ->
        detail.div(sanitizeUntrustedI18nText(explainRule(explanation, forMember = false))) { addCssClass("fw-bold") }
    }
    detail.div(tr("Die Empfänger werden beim Versand nach der Regel ermittelt.")) { addCssClasses("text-muted small") }
    detail.div(gettext("Aktuell erreicht die Regel: %1", list.subscriberCount)) { addCssClasses("text-muted small") }

    val editHost = detail.vPanel(spacing = 6)
    editHost.hide()
    val editButton = detail.actionButton(ActionIcon.EDIT, tr("Regel bearbeiten"), ButtonStyle.OUTLINEPRIMARY)
    editButton.onClick {
        if (editHost.visible) {
            editHost.hide()
        } else {
            editHost.removeAll()
            runGuardedAction(editButton) {
                renderEditRuleForm(editHost, list, onSaved = { onChanged() }, onCancel = { editHost.hide() })
                editHost.show()
            }
        }
    }
}

private suspend fun renderEditRuleForm(
    host: SimplePanel,
    list: MailingListDto,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val tiers: List<MembershipTierDto> = guarded { rpcService<IContributionService>().listMembershipTiers() }.orEmpty()
    val initial: MemberRule =
        list.ruleJson?.let {
            try {
                MemberRuleCodec.parse(it, RuleContext.MAILING)
            } catch (_: InvalidMemberRuleException) {
                null
            }
        } ?: MemberRule(op = RuleOp.ALL, conditions = emptyList())
    val stored =
        initial.conditions
            .filterIsInstance<RuleCondition.Committee>()
            .flatMap { it.committeeIds }
            .toSet()
    val committees: List<CommitteeDto> =
        committeesForRules(guarded { rpcService<IGovernanceService>().listCommittees(true) }.orEmpty(), keep = stored)
    val panel = host.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val form = panel.lapisForm()
    val builder =
        renderRuleBuilder(
            form = form,
            host = form.panel,
            initial = initial,
            committees = committees,
            tiers = tiers,
            wording = mailingRuleWording(),
            previewer = mailingPreviewer(listId = list.id, mandatory = { list.mandatory }),
        )
    form.crossFieldRule { builder.validationError()?.let { FieldCheck.Invalid(it) } ?: FieldCheck.Ok }
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Regel speichern"), ButtonStyle.PRIMARY)
    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.OUTLINESECONDARY)
    cancelButton.onClick {
        builder.dispose()
        onCancel()
    }
    form.buttons(primary = saveButton, cancel = cancelButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val saved =
                guarded { rpcService<IMailingService>().updateMailingListRule(list.id, MemberRuleCodec.encode(builder.rule())) }
            if (saved != null) {
                notifySuccess(tr("Regel wurde gespeichert."))
                builder.dispose()
                onSaved()
            }
        }
    }
}
