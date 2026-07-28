package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.TrustAnchorSigningKeyTable
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyDto
import network.lapis.cloud.shared.domain.TrustAnchorSigningKeyStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Persistence helpers for `trust_anchor_signing_key` -- used by [network.lapis.cloud.server.rpc.TrustAnchorService]
 * (the ADMIN-facing rotate/revoke RPC surface), [TrustAnchorStatements] (which key currently signs
 * new statements + which keys are still published for verification), and
 * [TrustAnchorSigningKeyProvisioner] (the first, boot-time row). Every function here must be called
 * from within an open `transaction {}` block -- it never opens its own, same contract
 * [FederationRelationshipStore] establishes.
 *
 * **Concurrency**: [findActive]/[findByKid] take an optional `forUpdate` row lock -- a concurrent
 * rotate/revoke targeting the SAME row (or racing to become the new sole `ACTIVE` row) blocks until
 * the first transaction commits, then re-reads the now-decided state instead of racing. This is the
 * ONLY guard needed here (unlike [FederationRelationshipStore]'s additional CAS): every orchestrating
 * call site in [network.lapis.cloud.server.rpc.TrustAnchorService] holds its `forUpdate` lock for the
 * WHOLE decide-and-write window (no network call, no lock-release, in between) -- there is no
 * "network fan-out never holds a DB connection open" tension here the way there is for
 * `FederationService`'s outbound Activity delivery, since minting/retiring a local key is pure DB
 * work.
 */
object TrustAnchorSigningKeyStore {
    fun findActive(forUpdate: Boolean = false): ResultRow? {
        val query = TrustAnchorSigningKeyTable.selectAll().where { TrustAnchorSigningKeyTable.status eq TrustAnchorSigningKeyStatus.ACTIVE }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
    }

    fun findByKid(
        kid: String,
        forUpdate: Boolean = false,
    ): ResultRow? {
        val query = TrustAnchorSigningKeyTable.selectAll().where { TrustAnchorSigningKeyTable.kid eq kid }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
    }

    /** Every key this server has ever provisioned, newest first. */
    fun listAll(): List<ResultRow> =
        TrustAnchorSigningKeyTable
            .selectAll()
            .orderBy(TrustAnchorSigningKeyTable.createdAt, SortOrder.DESC)
            .toList()

    /** Every key that is still valid for VERIFICATION -- `ACTIVE` (signs new things) + `RETIRED` (grace period, no longer signs but still verifies) -- deliberately excludes `REVOKED`, see `26-trust-anchor.kuml.kts` file header "Why revocation needs more than expiry alone". */
    fun listPublishable(): List<ResultRow> =
        TrustAnchorSigningKeyTable
            .selectAll()
            .where {
                (TrustAnchorSigningKeyTable.status eq TrustAnchorSigningKeyStatus.ACTIVE) or
                    (TrustAnchorSigningKeyTable.status eq TrustAnchorSigningKeyStatus.RETIRED)
            }.toList()

    /** Unconditional status write to `RETIRED` -- the caller must already hold a `forUpdate` lock on this exact row (see class KDoc "Concurrency"). */
    fun retire(
        id: Uuid,
        now: LocalDateTime,
    ) {
        TrustAnchorSigningKeyTable.update({ TrustAnchorSigningKeyTable.id eq id }) {
            it[status] = TrustAnchorSigningKeyStatus.RETIRED
            it[retiredAt] = now
        }
    }

    /** Unconditional status write to `REVOKED` -- the caller must already hold a `forUpdate` lock on this exact row (see class KDoc "Concurrency"). */
    fun revokeRow(
        id: Uuid,
        now: LocalDateTime,
    ) {
        TrustAnchorSigningKeyTable.update({ TrustAnchorSigningKeyTable.id eq id }) {
            it[status] = TrustAnchorSigningKeyStatus.REVOKED
            it[revokedAt] = now
        }
    }

    fun ResultRow.toDto(): TrustAnchorSigningKeyDto =
        TrustAnchorSigningKeyDto(
            kid = this[TrustAnchorSigningKeyTable.kid],
            publicKeyPem = this[TrustAnchorSigningKeyTable.publicKeyPem],
            status = this[TrustAnchorSigningKeyTable.status],
            createdAt = this[TrustAnchorSigningKeyTable.createdAt],
            retiredAt = this[TrustAnchorSigningKeyTable.retiredAt],
            revokedAt = this[TrustAnchorSigningKeyTable.revokedAt],
        )
}
