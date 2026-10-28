package network.lapis.cloud.server.messaging

import network.lapis.cloud.server.db.containsLikePattern
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.memberfinder.MemberFinderBlockStore
import network.lapis.cloud.shared.domain.DirectMessageRecipientDto
import network.lapis.cloud.shared.domain.DirectMessageRules
import network.lapis.cloud.shared.domain.MemberStatusSets
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * V1.9.237 -- the name search of "Neue Nachricht". Must run inside a `transaction {}`.
 *
 * **Only `display_name` is searched** (never first name, last name or email), case-insensitively, as a "contains" match with LIKE metacharacters
 * escaped ([containsLikePattern]) -- without that, `%%%` would pass the minimum length and list the whole membership. Fewer than
 * [DirectMessageRules.RECIPIENT_QUERY_MIN] or more than [DirectMessageRules.RECIPIENT_QUERY_MAX] characters give an empty list, never an error and
 * never an echo of the input. Hidden (`dm_discoverable = FALSE`), blocked (DM or finder, either direction), non-member, anonymized and the caller
 * himself are excluded; a hidden member is indistinguishable from a name that does not exist. The term is never logged.
 */
internal object DirectMessageRecipientSearch {
    fun search(
        callerId: Uuid,
        rawQuery: String,
    ): List<DirectMessageRecipientDto> {
        val term = rawQuery.trim()
        if (term.length < DirectMessageRules.RECIPIENT_QUERY_MIN || term.length > DirectMessageRules.RECIPIENT_QUERY_MAX) return emptyList()
        val excluded = DirectMessageBlockStore.blockedEitherWay(callerId) + MemberFinderBlockStore.blockedEitherWay(callerId) + callerId
        val pattern = containsLikePattern(term.lowercase())
        val hits =
            MemberTable
                .select(MemberTable.id, MemberTable.displayName)
                .where {
                    (MemberTable.displayName.lowerCase() like pattern) and
                        (MemberTable.status inList MemberStatusSets.ORGANIZATION_MEMBER) and
                        MemberTable.anonymizedAt.isNull() and
                        (MemberTable.dmDiscoverable eq true) and
                        (MemberTable.id notInList excluded)
                }.orderBy(MemberTable.displayName to SortOrder.ASC, MemberTable.id to SortOrder.ASC)
                .limit(DirectMessageRules.MAX_RECIPIENT_SEARCH_RESULTS)
                .map { it[MemberTable.id] to it[MemberTable.displayName] }
        if (hits.isEmpty()) return emptyList()
        val ids = hits.map { it.first }
        val talked = HashSet<Uuid>()
        DirectMessageTable
            .select(DirectMessageTable.recipientId)
            .where { (DirectMessageTable.senderId eq callerId) and (DirectMessageTable.recipientId inList ids) }
            .forEach { talked += it[DirectMessageTable.recipientId] }
        DirectMessageTable
            .select(DirectMessageTable.senderId)
            .where { (DirectMessageTable.recipientId eq callerId) and (DirectMessageTable.senderId inList ids) }
            .forEach { talked += it[DirectMessageTable.senderId] }
        return hits.map { (id, name) ->
            DirectMessageRecipientDto(memberId = id.toString(), displayName = name, hasConversation = id in talked)
        }
    }
}
