package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.TrustAnchorPoolMemberTable
import network.lapis.cloud.shared.domain.TrustAnchorPoolMemberDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Persistence helpers for `trust_anchor_pool_member` -- the set of home-server URIs THIS server
 * vouches for when acting as its own Trust Anchor (see `26-trust-anchor.kuml.kts` file header
 * "opt-in via non-empty pool"). Every function here must be called from within an open
 * `transaction {}` block, same contract [FederationRelationshipStore]/[TrustAnchorSigningKeyStore]
 * establish.
 *
 * **Concurrency**: `home_server_uri` carries a hard DB `UNIQUE` constraint -- a concurrent
 * double-add is caught by [network.lapis.cloud.server.rpc.TrustAnchorService.addPoolMember]'s own
 * pre-check-then-catch idiom (same "pre-check + backstop" pattern
 * [FederationRelationshipStore.upsertByRemoteActorUri] KDoc "Concurrency" point 3 documents), not
 * by a row lock here (there is no existing row to lock for a brand-new URI).
 */
object TrustAnchorPoolStore {
    fun findByUri(homeServerUri: String): ResultRow? =
        TrustAnchorPoolMemberTable.selectAll().where { TrustAnchorPoolMemberTable.homeServerUri eq homeServerUri }.singleOrNull()

    fun listAll(): List<ResultRow> =
        TrustAnchorPoolMemberTable.selectAll().orderBy(TrustAnchorPoolMemberTable.addedAt, SortOrder.ASC).toList()

    fun insert(
        homeServerUri: String,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        TrustAnchorPoolMemberTable.insert {
            it[TrustAnchorPoolMemberTable.id] = id
            it[TrustAnchorPoolMemberTable.homeServerUri] = homeServerUri
            it[addedAt] = now
        }
        return id
    }

    /** `true` iff a row for [homeServerUri] existed and was deleted. */
    fun remove(homeServerUri: String): Boolean =
        TrustAnchorPoolMemberTable.deleteWhere { TrustAnchorPoolMemberTable.homeServerUri eq homeServerUri } > 0

    fun ResultRow.toDto(): TrustAnchorPoolMemberDto =
        TrustAnchorPoolMemberDto(
            id = this[TrustAnchorPoolMemberTable.id].toString(),
            homeServerUri = this[TrustAnchorPoolMemberTable.homeServerUri],
            addedAt = this[TrustAnchorPoolMemberTable.addedAt],
        )
}
