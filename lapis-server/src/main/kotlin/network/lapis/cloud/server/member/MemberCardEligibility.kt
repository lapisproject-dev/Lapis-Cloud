package network.lapis.cloud.server.member

import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the ONE place that answers "may this member hold a
 * membership card at all?", for both the issuing route and the public verification page.
 *
 * **Deliberately narrower than "has a login".** A `GUEST`/`FRIEND`/`DONOR` account is explicitly
 * NOT a member of this organization ([MemberStatusSets.NON_MEMBER] plus `DONOR`), an `APPLICATION`
 * is not one yet, and `WITHDRAWN`/`REJECTED`/`DECEASED` no longer are. A document that states
 * "Mitglied seit ..." next to the organization's name is a membership assertion -- issuing one to
 * a non-member would be a false statement, not merely a permissions slip. Only
 * [MemberStatusSets.ORGANIZATION_MEMBER] (today: `ACTIVE`) qualifies.
 *
 * **Why the verification page re-checks the SAME predicate instead of trusting the code.** A card
 * code is revoked explicitly ([MemberCardStore.revokeAndReissue]); a MEMBERSHIP ends through a
 * status change that has no reason to know a card exists ([network.lapis.cloud.server.rpc
 * .MemberService.updateMemberStatus], the § 38 BGB death workflow, a DSGVO erasure). Without this
 * second check, a member who resigned in March would still verify as "gültiges Mitglied" in
 * December, because nobody remembered to revoke their card row. Membership status is the source of
 * truth; the code row only says "this particular piece of plastic has not been reported lost".
 */
internal object MemberCardEligibility {
    fun isEligible(status: MemberStatus): Boolean = status in MemberStatusSets.ORGANIZATION_MEMBER

    /**
     * The status word printed on the card and shown on the verification page. Only
     * [isEligible] statuses can ever reach the card itself; the remaining entries exist because
     * [MemberCardStore.resolve] can legitimately return a no-longer-eligible member (see class
     * KDoc) and the page has to name that state rather than show a bare enum.
     */
    fun statusLabel(status: MemberStatus): String =
        when (status) {
            MemberStatus.ACTIVE -> "Mitglied"
            MemberStatus.APPLICATION -> "Aufnahmeantrag"
            MemberStatus.GUEST -> "Gast"
            MemberStatus.FRIEND -> "Freund"
            MemberStatus.DONOR -> "Spender"
            MemberStatus.WITHDRAWN -> "Ausgetreten"
            MemberStatus.REJECTED -> "Abgelehnt"
            MemberStatus.DECEASED -> "Verstorben"
        }
}
