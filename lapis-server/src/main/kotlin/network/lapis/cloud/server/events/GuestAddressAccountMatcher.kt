package network.lapis.cloud.server.events

import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.FriendRegistrationConfig
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.259 -- decides whether an address typed into the public GUEST form belongs to an account that should register through its own
 * session instead. The caller never reveals the answer: both outcomes end in the same redirect.
 */
internal object GuestAddressAccountMatcher {
    /** The account that owns the address, and the address exactly as stored on that account (the notice goes there, not to a re-cased copy). */
    data class Match(
        val memberId: Uuid,
        val email: String,
    )

    /**
     * The account that owns [normalizedEmail] and may register itself, or `null`. ALWAYS runs exactly ONE query -- a miss, a hit, an
     * ineligible status and an anonymised row all cost the same database work, so the two branches of the public route stay comparable.
     *
     * - `innerJoin AccountTable`: only rows with a local account count -- somebody who can actually sign in.
     * - [MemberStatusSets.EVENT_SELF_REGISTRANT] only; anonymised rows (`anonymized_at` set) never match.
     * - A FRIEND counts only when the address is verified, or when the operator does not require verification
     *   ([FriendRegistrationConfig.requireEmailVerification]): otherwise anybody could park a stranger's address on a FRIEND account and
     *   lock the real owner out of the guest form (same gate as the carpool invitee search).
     */
    fun selfRegistrantOrNull(
        normalizedEmail: String,
        friendConfig: FriendRegistrationConfig,
    ): Match? =
        transaction {
            (MemberTable innerJoin AccountTable)
                .select(MemberTable.id, MemberTable.email, MemberTable.status, MemberTable.emailVerifiedAt)
                .where {
                    (MemberTable.email.lowerCase() eq normalizedEmail) and
                        (MemberTable.status inList MemberStatusSets.EVENT_SELF_REGISTRANT.toList()) and
                        MemberTable.anonymizedAt.isNull()
                }.firstNotNullOfOrNull { row ->
                    val eligible =
                        row[MemberTable.status] != MemberStatus.FRIEND ||
                            !friendConfig.requireEmailVerification ||
                            row[MemberTable.emailVerifiedAt] != null
                    if (eligible) Match(memberId = row[MemberTable.id], email = row[MemberTable.email]) else null
                }
        }
}
