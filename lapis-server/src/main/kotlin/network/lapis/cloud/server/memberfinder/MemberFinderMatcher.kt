package network.lapis.cloud.server.memberfinder

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MEMBER_FINDER_MIN_REGION_SIZE
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderRules
import java.security.SecureRandom
import kotlin.uuid.Uuid

/** The source of the random choice; a seam so a test can pick a known counterpart. */
fun interface MemberFinderRandom {
    /** A value in `0 until bound`. */
    fun nextInt(bound: Int): Int
}

/** The production source: [SecureRandom]. */
object SecureMemberFinderRandom : MemberFinderRandom {
    private val secure = SecureRandom()

    override fun nextInt(bound: Int): Int = secure.nextInt(bound)
}

/**
 * V1.9.144 -- picks the ONE counterpart of a request. The choice is uniform among the eligible candidates of the sender's region; the sender never
 * influences it. Returns `null` when nobody qualifies -- the caller then stores a PHANTOM request, which looks the same to the sender (decision E6).
 * Must run inside a `transaction {}`.
 */
internal object MemberFinderMatcher {
    fun pick(
        sender: MemberFinderParticipant,
        purpose: MemberFinderPurpose,
        participants: List<MemberFinderParticipant>,
        now: LocalDateTime,
        random: MemberFinderRandom,
    ): Uuid? {
        val region = participants.filter { it.regionKey == sender.regionKey }
        // The REAL size of the region (the sender included), not the published band: below the threshold nobody is drawn.
        if (region.size < MEMBER_FINDER_MIN_REGION_SIZE) return null
        val excluded =
            MemberFinderBlockStore.blockedEitherWay(sender.memberId) +
                MemberFinderRequestStore.pairExclusions(memberId = sender.memberId, now = now)
        val candidates =
            region.filter {
                it.memberId != sender.memberId &&
                    it.memberId !in excluded &&
                    interestMatches(interest = it.interest, purpose = purpose)
            }
        if (candidates.isEmpty()) return null
        val inbox = MemberFinderRequestStore.openInboxCounts(toMemberIds = candidates.map { it.memberId }, now = now)
        val open = candidates.filter { (inbox[it.memberId] ?: 0) < MemberFinderRules.MAX_OPEN_INCOMING }.sortedBy { it.memberId }
        if (open.isEmpty()) return null
        return open[random.nextInt(open.size)].memberId
    }

    /** `BOTH` matches every purpose. */
    fun interestMatches(
        interest: MemberFinderInterest,
        purpose: MemberFinderPurpose,
    ): Boolean =
        interest == MemberFinderInterest.BOTH ||
            (interest == MemberFinderInterest.GROUP_FOUNDING && purpose == MemberFinderPurpose.GROUP_FOUNDING) ||
            (interest == MemberFinderInterest.MEETUP && purpose == MemberFinderPurpose.MEETUP)
}
