package network.lapis.cloud.server.mail.newsletter

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MailingListOptOutTable
import network.lapis.cloud.server.mail.isValidMailboxAddress
import network.lapis.cloud.server.rules.LoadedMember
import network.lapis.cloud.server.rules.MemberFactsLoader
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.rules.Exclusion
import network.lapis.cloud.shared.domain.rules.MailingRecipientGuard
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.RecipientFacts
import network.lapis.cloud.shared.domain.rules.matches
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The outcome of resolving a rule: who is addressed, plus counts of the rule's hits that were left out. The two counts relate only to
 * members the rule selects that are ACTIVE, not anonymised and not deceased (the rest is never "excluded", it is simply not addressed).
 */
internal data class DynamicResolution(
    val recipientIds: List<Uuid>,
    val excludedOptedOut: Int,
    val excludedNoEmail: Int,
)

/**
 * Welle V1.9.111 -- THE ONE function that decides who a DYNAMIC list reaches: the live preview, the send estimate, the list visibility
 * and the delivery worker all call it, so the number shown to the board can never disagree with what is sent. Must run inside a
 * transaction (reads only). [listId] `null` (a preview of a not yet created list) counts no opt-outs; a [mandatory] list ignores them.
 * [today] is the organisation's calendar day.
 */
internal fun resolveDynamicRecipients(
    rule: MemberRule,
    listId: Uuid?,
    mandatory: Boolean,
    today: LocalDate,
): DynamicResolution {
    val optedOut: Set<Uuid> =
        if (listId == null || mandatory) {
            emptySet()
        } else {
            MailingListOptOutTable
                .selectAll()
                .where { MailingListOptOutTable.mailingListId eq listId }
                .map { it[MailingListOptOutTable.memberId] }
                .toSet()
        }
    return resolveAmong(
        members = MemberFactsLoader.loadAll(today = today, statuses = setOf(MemberStatus.ACTIVE)),
        rule = rule,
        optedOut = optedOut,
        mandatory = mandatory,
        today = today,
    )
}

/**
 * The pure core of [resolveDynamicRecipients] over already loaded [members] -- lets a caller that evaluates several lists load the
 * members once (`listMailingLists` for the board).
 */
internal fun resolveAmong(
    members: List<LoadedMember>,
    rule: MemberRule,
    optedOut: Set<Uuid>,
    mandatory: Boolean,
    today: LocalDate,
): DynamicResolution {
    val recipients = ArrayList<Uuid>()
    var excludedOptedOut = 0
    var excludedNoEmail = 0
    members.forEach { member ->
        if (!rule.matches(facts = member.facts, today = today)) return@forEach
        val facts =
            RecipientFacts(
                member = member.facts,
                anonymised = member.anonymised,
                deceased = member.deceased,
                hasValidEmail = isValidMailboxAddress(member.email),
                optedOut = member.id in optedOut,
            )
        when (MailingRecipientGuard.exclusionOf(f = facts, mandatory = mandatory)) {
            null -> recipients += member.id
            Exclusion.NO_EMAIL -> excludedNoEmail++
            Exclusion.OPTED_OUT -> excludedOptedOut++
            Exclusion.NOT_ACTIVE, Exclusion.ANONYMISED, Exclusion.DECEASED -> Unit
        }
    }
    return DynamicResolution(recipientIds = recipients, excludedOptedOut = excludedOptedOut, excludedNoEmail = excludedNoEmail)
}
