package network.lapis.cloud.server.ai.optin

import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AiMemberOptInTable
import network.lapis.cloud.shared.domain.AiFeature
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Per-member, per-feature consent. **No row means "not opted in"** unless the operator flipped
 * `LAPIS_AI_MEMBER_OPT_IN_DEFAULT` (default `false`). The opt-in is the member's own decision:
 * only [set] with the caller's own member id is ever reachable from the RPC layer.
 */
internal object AiMemberOptInStore {
    fun isEnabled(
        memberId: Uuid,
        feature: AiFeature,
        default: Boolean,
    ): Boolean =
        transaction {
            AiMemberOptInTable
                .selectAll()
                .where { (AiMemberOptInTable.memberId eq memberId) and (AiMemberOptInTable.feature eq feature) }
                .singleOrNull()
                ?.get(AiMemberOptInTable.enabled)
        } ?: default

    /** Upsert; idempotent. A concurrent first write is resolved through the unique index. */
    fun set(
        memberId: Uuid,
        feature: AiFeature,
        enabled: Boolean,
    ) {
        val now = DbClock.nowLocalDateTime()

        fun update(): Int =
            transaction {
                AiMemberOptInTable.update({ (AiMemberOptInTable.memberId eq memberId) and (AiMemberOptInTable.feature eq feature) }) {
                    it[AiMemberOptInTable.enabled] = enabled
                    it[updatedAt] = now
                }
            }
        if (update() > 0) return
        try {
            transaction {
                AiMemberOptInTable.insert {
                    it[id] = Uuid.random()
                    it[AiMemberOptInTable.memberId] = memberId
                    it[AiMemberOptInTable.feature] = feature
                    it[AiMemberOptInTable.enabled] = enabled
                    it[updatedAt] = now
                }
            }
        } catch (_: ExposedSQLException) {
            // Lost the race against a concurrent first write for the same (member, feature): update instead.
            update()
        }
    }
}
