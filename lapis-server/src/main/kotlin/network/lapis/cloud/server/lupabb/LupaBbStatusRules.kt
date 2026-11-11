package network.lapis.cloud.server.lupabb

import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusTransitions

internal object LupaBbStatusRules {
    /**
     * V1.9.299 -- the transitions the sync allows beyond [MemberStatusTransitions]: legacy FRIEND records created from "Förderer"
     * before V1.9.299 (FRIEND to DONOR), and since the "Konflikte direkt lösen" wave the END of a friendship (withdrawal, death) that LupaBB
     * reports. Sync-only; the manual admin path never sees it.
     * The asymmetry is deliberate: the end of a relationship is taken over, the beginning of a membership never is (FRIEND to ACTIVE,
     * APPLICATION, GUEST and REJECTED stay forbidden).
     * Only applies when the member already carries the record's LupaBB person number: an identity-unverified self-registered FRIEND that was
     * merely matched by e-mail + name or external reference must never be upgraded.
     */
    val SYNC_ONLY_TRANSITIONS: Set<Pair<MemberStatus, MemberStatus>> =
        setOf(
            MemberStatus.FRIEND to MemberStatus.DONOR,
            MemberStatus.FRIEND to MemberStatus.WITHDRAWN,
            MemberStatus.FRIEND to MemberStatus.DECEASED,
        )

    fun syncAllows(
        from: MemberStatus,
        to: MemberStatus,
        memberPersonNumber: Long? = null,
        recordPersonNumber: Long? = null,
    ): Boolean =
        to in MemberStatusTransitions.allowedTargets(from) ||
            ((from to to) in SYNC_ONLY_TRANSITIONS && memberPersonNumber != null && memberPersonNumber == recordPersonNumber)
}
