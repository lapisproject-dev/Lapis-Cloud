package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.TrustedExternalAnchorTable
import network.lapis.cloud.shared.domain.TrustedExternalAnchorDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Persistence helpers for `trusted_external_anchor` -- the set of external Trust Anchor entity URIs
 * THIS server has chosen to trust for the one-hop resolution signal (see [TrustAnchorResolver]).
 * Same shape/concurrency posture as [TrustAnchorPoolStore] -- see that class's own KDoc.
 */
object TrustedAnchorStore {
    fun findByUri(anchorEntityUri: String): ResultRow? =
        TrustedExternalAnchorTable.selectAll().where { TrustedExternalAnchorTable.anchorEntityUri eq anchorEntityUri }.singleOrNull()

    fun listAll(): List<ResultRow> =
        TrustedExternalAnchorTable.selectAll().orderBy(TrustedExternalAnchorTable.addedAt, SortOrder.ASC).toList()

    fun insert(
        anchorEntityUri: String,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        TrustedExternalAnchorTable.insert {
            it[TrustedExternalAnchorTable.id] = id
            it[TrustedExternalAnchorTable.anchorEntityUri] = anchorEntityUri
            it[addedAt] = now
        }
        return id
    }

    /** `true` iff a row for [anchorEntityUri] existed and was deleted. */
    fun remove(anchorEntityUri: String): Boolean =
        TrustedExternalAnchorTable.deleteWhere { TrustedExternalAnchorTable.anchorEntityUri eq anchorEntityUri } > 0

    fun ResultRow.toDto(): TrustedExternalAnchorDto =
        TrustedExternalAnchorDto(
            id = this[TrustedExternalAnchorTable.id].toString(),
            anchorEntityUri = this[TrustedExternalAnchorTable.anchorEntityUri],
            addedAt = this[TrustedExternalAnchorTable.addedAt],
        )
}
