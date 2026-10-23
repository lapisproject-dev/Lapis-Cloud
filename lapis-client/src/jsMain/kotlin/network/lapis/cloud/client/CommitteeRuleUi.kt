package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.form.check.RadioGroup
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeModeChangeBlock
import network.lapis.cloud.shared.domain.CommitteeModeChangeImpactDto
import network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.DynamicListExplanationDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.RuleExplanationItem
import network.lapis.cloud.shared.domain.RuleExplanationKind
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.domain.rules.RuleOp
import network.lapis.cloud.shared.rpc.IContributionService
import network.lapis.cloud.shared.rpc.IGovernanceService

// V1.9.112 "regelbasierte Gremien" -- the client side of rule-based committees: the "Mitglieder" choice (manual / by rule) with the shared rule builder,
// the confirmation of a mode switch (with the number of seats that end and the reason a switch is refused), the card of a rule committee and the
// sentence that explains its rule. Design-team decisions: the choice is a fieldset with a legend (a radio group, never colour alone), the rule is
// shown as a sentence, the count of members is a polite live region, a rule committee says plainly what it does NOT have (no offices, no roster, only
// BOARD/ADMIN lead its meetings) and that a vote freezes the circle at its start; the confirm button of a refused switch is disabled and the reason
// stands next to it as text.

/** Types for which the choice is never offered (the General Assembly is "everybody", the executive board needs named offices). */
internal fun committeeTypeAllowsRule(type: CommitteeType): Boolean =
    type != CommitteeType.GENERAL_ASSEMBLY && type != CommitteeType.EXECUTIVE_BOARD

private val EMPTY_RULE = MemberRule(op = RuleOp.ALL, conditions = emptyList())

/** The stored rule of [committee] as the builder needs it (an unreadable one starts empty -- the server still decides). */
internal fun committeeRuleOrEmpty(committee: CommitteeDto): MemberRule =
    committee.ruleJson?.let {
        try {
            MemberRuleCodec.parse(text = it, context = RuleContext.COMMITTEE)
        } catch (_: InvalidMemberRuleException) {
            null
        }
    } ?: EMPTY_RULE

/** The rule as ONE sentence for the board: "Aktive Mitglieder, auf die zutrifft: ..." -- committee and tier names come from the lists in hand. */
internal fun committeeRuleSentence(
    rule: MemberRule,
    committees: List<CommitteeDto>,
    tiers: List<MembershipTierDto>,
): String {
    val committeeNames = committees.associate { c -> c.id to c.name }
    val tierNames = tiers.associate { t -> t.id to t.name }
    val items =
        rule.conditions.mapNotNull { condition ->
            when (condition) {
                is RuleCondition.Role ->
                    RuleExplanationItem(kind = RuleExplanationKind.ROLE, labels = condition.values.map { it.name }.sorted())
                is RuleCondition.JoinedWithinDays ->
                    RuleExplanationItem(
                        kind = RuleExplanationKind.JOINED_WITHIN_DAYS,
                        days = condition.days,
                    )
                is RuleCondition.Committee ->
                    RuleExplanationItem(
                        kind = RuleExplanationKind.COMMITTEE,
                        labels = condition.committeeIds.mapNotNull { committeeNames[it] }.sorted(),
                    )
                is RuleCondition.Tier ->
                    RuleExplanationItem(kind = RuleExplanationKind.TIER, labels = condition.tierIds.mapNotNull { tierNames[it] }.sorted())
                is RuleCondition.Status -> null
            }
        }
    return explainRule(DynamicListExplanationDto(op = rule.op, items = items), forMember = false)
}

/** The badge of a rule committee: the icon is decoration (`aria-hidden`), the word carries the meaning. */
internal fun Container.ruleCommitteeBadge() {
    span(className = "badge rounded-pill border border-info text-info") {
        icon(ActionIcon.FILTER.css) { setAttribute("aria-hidden", "true") }
        span(" ")
        span(gettext("Regel"))
    }
}

/** Why a switch is refused, as a finished sentence. */
internal fun committeeModeBlockText(block: CommitteeModeChangeBlock): String =
    when (block) {
        CommitteeModeChangeBlock.MEETING_IN_PROGRESS ->
            gettext("Eine Sitzung dieses Gremiums hat begonnen und ist noch nicht abgeschlossen.")
        CommitteeModeChangeBlock.OPEN_DECISION ->
            gettext("Eine Abstimmung, Wahl oder ein Konsensieren dieses Gremiums ist noch nicht abgeschlossen.")
        CommitteeModeChangeBlock.ELECTION_TARGETS_COMMITTEE -> gettext("Eine noch nicht abgeschlossene Wahl besetzt dieses Gremium.")
        CommitteeModeChangeBlock.REFERENCED_BY_RULE -> gettext("Eine andere Regel (Gremium oder Mailingliste) verweist auf dieses Gremium.")
        CommitteeModeChangeBlock.TYPE_NOT_ALLOWED -> gettext("Für die Mitgliederversammlung und den Vorstand ist keine Regel möglich.")
    }

/** Label of the reason a seat ended automatically: the member status, or the switch to rule-based membership. */
internal fun committeeSeatEndReasonLabel(reason: CommitteeSeatAutoEndReason): String =
    when (reason) {
        CommitteeSeatAutoEndReason.RULE_MODE -> gettext("Umstellung auf Regel")
        else -> memberStatusLabel(MemberStatus.valueOf(reason.name))
    }

/**
 * The confirmation before a switch: how many open seats end (for the switch INTO rule mode), that the committee is empty afterwards (switching
 * back), or -- if [impact] says it is refused -- the reason as text with the confirm button disabled. Nothing is written before the person confirms.
 */
internal fun committeeModeChangeDialog(
    committeeName: String,
    targetMode: CommitteeMembershipMode,
    impact: CommitteeModeChangeImpactDto,
    onConfirm: () -> Unit,
) {
    val modal =
        Modal(
            caption =
                if (targetMode == CommitteeMembershipMode.RULE) tr("Auf Regel umstellen") else tr("Auf manuelle Besetzung umstellen"),
        )
    modal.div(sanitizeUntrustedI18nText(committeeName)) { addCssClass("fw-bold") }
    val blocked = impact.blocked
    if (blocked != null) {
        modal.div(committeeModeBlockText(blocked)) { addCssClasses("alert alert-warning mb-0 mt-2") }
    } else if (targetMode == CommitteeMembershipMode.RULE) {
        modal.div(tr("Eine Regel bestimmt dann die Mitglieder. Sitze gibt es nicht mehr.")) { addCssClass("mt-2") }
        modal.div(ngettext("%1 offener Sitz endet heute.", "%1 offene Sitze enden heute.", impact.openSeats, impact.openSeats)) {
            addCssClasses("fw-bold")
        }
    } else {
        modal.div(tr("Das Gremium ist danach leer.")) { addCssClass("mt-2") }
        modal.div(tr("Mitglieder müssen danach einzeln eingetragen werden.")) { addCssClasses("text-muted small") }
    }
    modal.addButton(newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    val confirm = Button(tr("Umstellen"), style = ButtonStyle.PRIMARY)
    confirm.disabled = blocked != null
    val once = ConfirmOnce()
    confirm.onClick {
        once.run(confirm) {
            modal.hide()
            onConfirm()
        }
    }
    modal.addButton(confirm)
    modal.show()
}

/**
 * The "Mitglieder" choice of the create form and the edit section: a fieldset with a legend and two radios ("Manuell" / "Nach Regel"), and the
 * shared rule builder (committee wording) below while "Nach Regel" is chosen. [setTypeAllowsRule] hides everything for the General Assembly and
 * the executive board and forces "Manuell". The builder is built when it is first needed (committees and tiers are loaded then) and torn
 * down when the choice goes back to "Manuell", so a half-filled rule can never block a manual committee.
 */
internal class MembershipModeSection(
    private val form: LapisForm,
    host: Container,
    private val selfId: String?,
    initialMode: CommitteeMembershipMode,
    private val initialRule: MemberRule,
    private val onModeChanged: () -> Unit = {},
) {
    private val fieldset = Tag(TAG.FIELDSET, className = "border-0 p-0 m-0")
    private val radio: RadioGroup
    private val ruleHost: SimplePanel
    private var builder: RuleBuilderHandle? = null
    private var allowed = true
    private var built = false

    init {
        fieldset.add(Tag(TAG.LEGEND, tr("Mitglieder"), className = "h6 mb-1"))
        radio =
            RadioGroup(
                options =
                    listOf(
                        CommitteeMembershipMode.MANUAL.name to gettext("Manuell"),
                        CommitteeMembershipMode.RULE.name to gettext("Nach Regel"),
                    ),
                value = initialMode.name,
            )
        fieldset.add(radio)
        fieldset.add(Tag(TAG.P, tr("Mitglieder ergeben sich aus der Regel."), className = "text-muted small mb-1"))
        ruleHost = SimplePanel()
        fieldset.add(ruleHost)
        host.add(fieldset)
        form.register(control = radio, label = tr("Mitglieder"), required = false)
        form.crossFieldRule { validationError()?.let { FieldCheck.Invalid(it) } ?: FieldCheck.Ok }
        radio.subscribe {
            sync()
            onModeChanged()
        }
        sync()
    }

    /** The chosen mode; always MANUAL while the type allows no rule. */
    val mode: CommitteeMembershipMode
        get() =
            if (allowed &&
                radio.value == CommitteeMembershipMode.RULE.name
            ) {
                CommitteeMembershipMode.RULE
            } else {
                CommitteeMembershipMode.MANUAL
            }

    /** The canonical rule JSON of the builder, or `null` for a manual committee. */
    fun ruleJson(): String? = if (mode == CommitteeMembershipMode.RULE) builder?.rule()?.let { MemberRuleCodec.encode(it) } else null

    /**
     * A finished sentence while the rule is incomplete, `null` otherwise (and always `null` for a manual committee). While the rule builder is
     * still being built (committees and tiers are loading) the rule is not available yet, so saving is refused with a "still loading" sentence.
     */
    fun validationError(): String? =
        if (mode == CommitteeMembershipMode.RULE) {
            builder?.validationError() ?: if (builder == null) tr("Die Regel wird noch geladen.") else null
        } else {
            null
        }

    /** Shows or hides the whole choice according to the committee type (hidden = MANUAL, the builder is torn down). */
    fun setTypeAllowsRule(value: Boolean) {
        allowed = value
        if (!value) radio.value = CommitteeMembershipMode.MANUAL.name
        if (value) fieldset.show() else fieldset.hide()
        sync()
    }

    fun dispose() {
        builder?.dispose()
        builder = null
    }

    private fun sync() {
        if (mode == CommitteeMembershipMode.RULE) {
            if (!built) build()
            ruleHost.show()
        } else {
            builder?.dispose()
            builder = null
            built = false
            ruleHost.removeAll()
            ruleHost.hide()
        }
    }

    private fun build() {
        built = true
        AppScope.launch {
            val committees = guarded { rpcService<IGovernanceService>().listCommittees(true) }.orEmpty()
            val tiers = guarded { rpcService<IContributionService>().listMembershipTiers() }.orEmpty()
            // the choice may have gone back to "Manuell" while the lists were loading
            if (mode != CommitteeMembershipMode.RULE || builder != null) return@launch
            ruleHost.removeAll()
            builder =
                renderRuleBuilder(
                    form = form,
                    host = ruleHost,
                    initial = initialRule,
                    committees = committeesForRules(committees, selfId = selfId),
                    tiers = tiers,
                    wording = committeeRuleWording(),
                    previewer = committeePreviewer(committeeId = selfId),
                )
        }
    }
}

/**
 * The card content of a rule committee (below the title row): the rule as a sentence and the live number of members for BOARD/ADMIN (the rule
 * text itself never reaches anybody else), the number for everybody, and what a rule committee does not have. The count sits in a polite live
 * region; a failed count simply leaves the sentence without a number.
 */
internal fun renderRuleCommitteeFacts(
    row: Container,
    committee: CommitteeDto,
    canManage: Boolean,
    committees: List<CommitteeDto>,
    tiers: List<MembershipTierDto>,
    onCount: (Int?) -> Unit = {},
) {
    if (canManage) {
        val rule = committeeRuleOrEmpty(committee)
        row.div(sanitizeUntrustedI18nText(committeeRuleSentence(rule = rule, committees = committees, tiers = tiers))) {
            addCssClasses("small")
        }
    }
    val count = row.div { addCssClasses("fw-bold lapis-tabular-nums") }
    count.setAttribute("role", "status")
    count.setAttribute("aria-live", "polite")
    AppScope.launch {
        val n = runCatching { rpcService<IGovernanceService>().countCommitteeEligible(committee.id) }.getOrNull()
        if (n != null) count.content = ngettext("%1 Mitglied gehört dazu", "%1 Mitglieder gehören dazu", n, n)
        onCount(n)
    }
    row.div(tr("Mitglieder ergeben sich aus der Regel.")) { addCssClasses("text-muted small") }
    row.div(tr("Bei einer Abstimmung wird der Kreis zum Start festgeschrieben.")) { addCssClasses("text-muted small") }
    row.div(tr("Regelgremien haben keine Ämter. Ämter wie Vorsitz werden in dieser Version nur in manuellen Gremien vergeben.")) {
        addCssClasses("text-muted small")
    }
    row.div(tr("Sitzungen und Abstimmungen dieses Gremiums leiten Vorstand oder Administration.")) { addCssClasses("text-muted small") }
}

/**
 * The edit section "Mitglieder bestimmen" under the edit form of a committee (BOARD/ADMIN, not for the General Assembly and the executive
 * board): the choice and, for a rule, the builder. Saving first asks what the switch would do ([IGovernanceService.getCommitteeModeChangeImpact]),
 * shows it in the confirmation dialog and only then writes ([IGovernanceService.setCommitteeMembershipRule]); a change of the rule alone
 * goes through the same two steps (it can be refused for the same reasons).
 */
internal fun renderCommitteeMembershipSection(
    panel: SimplePanel,
    committee: CommitteeDto,
    onSaved: () -> Unit,
) {
    if (!committeeTypeAllowsRule(committee.type)) return
    val box = panel.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-2") }
    box.p(tr("Mitglieder bestimmen")) { addCssClasses("fw-bold mb-0") }
    val form = box.lapisForm()
    val section =
        MembershipModeSection(
            form = form,
            host = form.panel,
            selfId = committee.id,
            initialMode = committee.membershipMode,
            initialRule = committeeRuleOrEmpty(committee),
        )
    val saveButton = newActionButton(ActionIcon.SAVE, tr("Besetzung speichern"), ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)
    saveButton.onClick {
        form.submit(saveButton) {
            val target = section.mode
            val ruleJson = section.ruleJson()
            if (target == committee.membershipMode && target == CommitteeMembershipMode.MANUAL) {
                notifyInfo(tr("Es gibt nichts zu speichern."))
                return@submit
            }
            val impact = guarded { rpcService<IGovernanceService>().getCommitteeModeChangeImpact(committee.id, target) } ?: return@submit
            committeeModeChangeDialog(committeeName = committee.name, targetMode = target, impact = impact) {
                AppScope.launch {
                    val saved = guarded { rpcService<IGovernanceService>().setCommitteeMembershipRule(committee.id, target, ruleJson) }
                    if (saved != null) {
                        notifySuccess(
                            when {
                                target == CommitteeMembershipMode.MANUAL ->
                                    gettext(
                                        "Das Gremium \"%1\" wird jetzt manuell besetzt.",
                                        committee.name,
                                    )
                                committee.membershipMode == CommitteeMembershipMode.RULE -> tr("Regel wurde gespeichert.")
                                else -> gettext("Das Gremium \"%1\" ist jetzt ein Regelgremium.", committee.name)
                            },
                        )
                        section.dispose()
                        onSaved()
                    }
                }
            }
        }
    }
}
