package network.lapis.cloud.server.lupabb

import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusTransitions

internal object LupaBbStatusRules {
    /**
     * V1.9.299 -- the only transition the sync allows beyond [MemberStatusTransitions]: legacy FRIEND records created from "Förderer"
     * before V1.9.299. Sync-only; the manual admin path never sees it.
     * Only applies when the member already carries the record's LupaBB person number: an identity-unverified self-registered FRIEND that was
     * merely matched by e-mail + name or external reference must never be upgraded.
     */
    val SYNC_ONLY_TRANSITIONS: Set<Pair<MemberStatus, MemberStatus>> = setOf(MemberStatus.FRIEND to MemberStatus.DONOR)

    fun syncAllows(
        from: MemberStatus,
        to: MemberStatus,
        memberPersonNumber: Long? = null,
        recordPersonNumber: Long? = null,
    ): Boolean =
        to in MemberStatusTransitions.allowedTargets(from) ||
            ((from to to) in SYNC_ONLY_TRANSITIONS && memberPersonNumber != null && memberPersonNumber == recordPersonNumber)
}
