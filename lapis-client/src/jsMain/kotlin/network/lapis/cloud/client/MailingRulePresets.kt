package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DynamicListExplanationDto
import network.lapis.cloud.shared.domain.RuleExplanationItem
import network.lapis.cloud.shared.domain.RuleExplanationKind
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleOp

// V1.9.111 "Dynamische Mailinglisten" -- the starting points of the rule builder and the one sentence that explains a rule. Both are PURE
// functions returning FINISHED strings (gettext, never tr: a tr() marker is only resolved at the start of a widget's text, so one that is
// joined into a longer string would show up raw). Committee and tier NAMES inside an explanation are free text of the board: they are
// sanitised by gettext's own argument handling, and the finished sentence is sanitised again by the caller before it becomes widget content.

/** The four ready-made starting points offered above the rule builder. "Beitrag offen" is deliberately NOT among them (no such condition). */
internal enum class MailingRulePreset { ACTIVE_MEMBERS, BOARD_ROLES, COMMITTEE, NEW_MEMBERS_30 }

internal object MailingRulePresets {
    /** Stable order of the chips. */
    val ALL: List<MailingRulePreset> = MailingRulePreset.entries

    fun label(preset: MailingRulePreset): String =
        when (preset) {
            MailingRulePreset.ACTIVE_MEMBERS -> gettext("Alle aktiven Mitglieder")
            MailingRulePreset.BOARD_ROLES -> gettext("Vorstand")
            MailingRulePreset.COMMITTEE -> gettext("Ein Gremium")
            MailingRulePreset.NEW_MEMBERS_30 -> gettext("Neue Mitglieder (30 Tage)")
        }

    /**
     * The rule a preset stands for, or `null` if it cannot be offered (the committee preset needs [firstCommitteeId]; without any
     * committee the chip is hidden). "Vorstand" means the software roles Vorstand and Schatzmeister, not the statutory body.
     */
    fun rule(
        preset: MailingRulePreset,
        firstCommitteeId: String?,
    ): MemberRule? =
        when (preset) {
            MailingRulePreset.ACTIVE_MEMBERS -> MemberRule(op = RuleOp.ALL, conditions = emptyList())
            MailingRulePreset.BOARD_ROLES ->
                MemberRule(op = RuleOp.ANY, conditions = listOf(RuleCondition.Role(setOf(AccountRole.BOARD, AccountRole.TREASURER))))
            MailingRulePreset.COMMITTEE ->
                firstCommitteeId?.let { MemberRule(op = RuleOp.ALL, conditions = listOf(RuleCondition.Committee(setOf(it)))) }
            MailingRulePreset.NEW_MEMBERS_30 ->
                MemberRule(op = RuleOp.ALL, conditions = listOf(RuleCondition.JoinedWithinDays(30)))
        }
}

/** One condition as a neutral noun phrase (no verb: it must work in every language behind "...zutrifft:" without agreement). */
private fun explanationFragment(item: RuleExplanationItem): String =
    when (item.kind) {
        RuleExplanationKind.ROLE -> gettext("Rolle %1", joinedNames(item.labels.map { name -> roleNameOf(name) }))
        RuleExplanationKind.JOINED_WITHIN_DAYS -> gettext("Beitritt in den letzten %1 Tagen", item.days ?: 0)
        RuleExplanationKind.COMMITTEE -> gettext("Mitglied im Gremium %1", joinedNames(item.labels))
        RuleExplanationKind.TIER -> gettext("Mitgliedschaftsstufe %1", joinedNames(item.labels))
    }

/** The member-facing sentence for ONE condition ("Sie erhalten diese Liste, weil Sie Mitglied im Gremium X sind."). */
private fun memberSentenceFor(item: RuleExplanationItem): String =
    when (item.kind) {
        RuleExplanationKind.ROLE ->
            gettext("Sie erhalten diese Liste, weil Sie die Rolle %1 haben.", joinedNames(item.labels.map { name -> roleNameOf(name) }))
        RuleExplanationKind.JOINED_WITHIN_DAYS ->
            gettext("Sie erhalten diese Liste, weil Sie in den letzten %1 Tagen beigetreten sind.", item.days ?: 0)
        RuleExplanationKind.COMMITTEE ->
            gettext("Sie erhalten diese Liste, weil Sie Mitglied im Gremium %1 sind.", joinedNames(item.labels))
        RuleExplanationKind.TIER ->
            gettext("Sie erhalten diese Liste, weil Sie die Mitgliedschaftsstufe %1 haben.", joinedNames(item.labels))
    }

private fun roleNameOf(enumName: String): String =
    AccountRole.entries.firstOrNull { it.name == enumName }?.let { accountRoleLabel(it) } ?: enumName

private fun joinedNames(names: List<String>): String = names.joinToString(" ${gettext("oder")} ")

/**
 * The sentence for [dto]. [forMember] = the explanation a member sees for a list that reaches them (the server already reduced the items to
 * the conditions THEY meet): one condition gets its own natural sentence ("Sie erhalten diese Liste, weil Sie Mitglied im Gremium X sind."),
 * several get the generic one. Otherwise the board's description of the whole rule ("Aktive Mitglieder, auf die zutrifft: A und B."). An
 * empty rule is "Alle aktiven Mitglieder.".
 */
internal fun explainRule(
    dto: DynamicListExplanationDto,
    forMember: Boolean,
): String {
    if (dto.items.isEmpty()) {
        return if (forMember) {
            gettext(
                "Sie erhalten diese Liste, weil Sie ein aktives Mitglied sind.",
            )
        } else {
            gettext("Alle aktiven Mitglieder.")
        }
    }
    if (forMember && dto.items.size == 1) return memberSentenceFor(dto.items.single())
    val joiner = if (forMember || dto.op == RuleOp.ALL) gettext("und") else gettext("oder")
    val phrase = dto.items.joinToString(" $joiner ") { explanationFragment(it) }
    return if (forMember) {
        gettext("Sie erhalten diese Liste, weil Folgendes auf Sie zutrifft: %1.", phrase)
    } else {
        gettext("Aktive Mitglieder, auf die zutrifft: %1.", phrase)
    }
}
