package network.lapis.cloud.server.rules

import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.InvalidRuleReason
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleCondition
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 -- the ONE validator of what a stored member rule points at, shared by the dynamic mailing lists and the rule-based
 * committees (so the two can never drift apart).
 *
 * A rule that names a committee only ever means "has an active SEAT in it" (`MemberFactsLoader` reads the seat rows). A rule-based
 * committee has no seat rows and the General Assembly has none either, so a reference to either would silently address NOBODY -- exactly
 * the kind of typo this validator exists to catch. Hence, in BOTH contexts, a referenced committee must exist, be active, be MANUAL and not
 * be the General Assembly; and a committee rule must never reference the committee it belongs to ([selfCommitteeId]). Because rule
 * committees never reference rule committees, evaluation is acyclic and one level deep -- no transitive resolution exists.
 *
 * Must run inside an open `transaction {}`. The rejection text carries only fixed wording or the name of the [InvalidRuleReason] -- never
 * a part of the rule.
 */
internal object RuleReferences {
    /**
     * Locks every referenced committee row plus [selfCommitteeId] `FOR UPDATE` in the database's own `ORDER BY id` (one deterministic order for
     * everybody -- no deadlock between two concurrent rule changes), THEN validates. A concurrent switch of a referenced committee to rule mode
     * therefore either commits before this check (and is rejected here) or waits for this transaction.
     */
    fun lockAndValidate(
        rule: MemberRule,
        context: RuleContext,
        selfCommitteeId: Uuid?,
    ) = check(rule = rule, context = context, selfCommitteeId = selfCommitteeId, lock = true)

    /** Like [lockAndValidate] without any row lock -- for the read-only preview. */
    fun validate(
        rule: MemberRule,
        context: RuleContext,
        selfCommitteeId: Uuid?,
    ) = check(rule = rule, context = context, selfCommitteeId = selfCommitteeId, lock = false)

    private fun check(
        rule: MemberRule,
        context: RuleContext,
        selfCommitteeId: Uuid?,
        lock: Boolean,
    ) {
        val referenced =
            rule.conditions
                .filterIsInstance<RuleCondition.Committee>()
                .flatMap { c ->
                    c.committeeIds.map { Uuid.parse(it) }
                }.toSet()
        val lockIds = (referenced + listOfNotNull(selfCommitteeId)).toList()
        val rows =
            if (lockIds.isEmpty()) {
                emptyMap()
            } else {
                CommitteeTable
                    .selectAll()
                    .where { CommitteeTable.id inList lockIds }
                    .orderBy(CommitteeTable.id)
                    .let { query -> if (lock) query.forUpdate() else query }
                    .associateBy { it[CommitteeTable.id] }
            }
        referenced.forEach { id ->
            val row = rows[id]
            if (row == null ||
                !row[CommitteeTable.active]
            ) {
                throw BadRequestException("Ungültige Regel: unbekanntes oder inaktives Gremium.")
            }
            val notManual =
                id == selfCommitteeId ||
                    row[CommitteeTable.membershipMode] == CommitteeMembershipMode.RULE ||
                    row[CommitteeTable.type] == CommitteeType.GENERAL_ASSEMBLY
            if (notManual) throw BadRequestException(InvalidRuleReason.COMMITTEE_REFERENCE_NOT_MANUAL.name)
        }
        rule.conditions.filterIsInstance<RuleCondition.Tier>().forEach { condition ->
            val ids = condition.tierIds.map { Uuid.parse(it) }
            val found = MembershipTierTable.selectAll().where { MembershipTierTable.id inList ids }.count()
            if (found != ids.size.toLong()) throw BadRequestException("Ungültige Regel: unbekannte Mitgliedschaftsstufe.")
        }
    }

    /**
     * How many stored rules (rule-based committees and DYNAMIC mailing lists) reference [committeeId]. Every stored rule is PARSED and its
     * committee conditions inspected -- never matched by text -- so a changed JSON layout or an id in another field cannot cause a false hit
     * or a miss. A stored rule that does not parse can reference nothing in effect (evaluation fails closed) and is skipped.
     */
    fun countReferencesTo(committeeId: Uuid): Int {
        val wanted = committeeId.toString()

        fun references(
            text: String?,
            context: RuleContext,
        ): Boolean {
            if (text == null) return false
            val rule =
                try {
                    MemberRuleCodec.parse(text = text, context = context)
                } catch (e: InvalidMemberRuleException) {
                    return false
                }
            return rule.conditions.filterIsInstance<RuleCondition.Committee>().any { wanted in it.committeeIds }
        }
        val fromCommittees =
            CommitteeTable
                .selectAll()
                .where { CommitteeTable.membershipMode eq CommitteeMembershipMode.RULE }
                .count { references(text = it[CommitteeTable.ruleJson], context = RuleContext.COMMITTEE) }
        val fromMailingLists =
            MailingListTable
                .selectAll()
                // V1.9.234: DYNAMIC lists are never deleted (server rule + CHECK); the filter is defence in depth only.
                .where { (MailingListTable.kind eq MailingListKind.DYNAMIC) and MailingListTable.deletedAt.isNull() }
                .count { references(text = it[MailingListTable.ruleJson], context = RuleContext.MAILING) }
        return fromCommittees + fromMailingLists
    }
}
