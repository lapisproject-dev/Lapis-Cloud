package network.lapis.cloud.server.federation

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.shared.domain.FederationEventType
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.FederationRelationshipDto
import network.lapis.cloud.shared.domain.FederationRelationshipEventDto
import network.lapis.cloud.shared.domain.FederationRelationshipStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Shared persistence helpers for `federation_relationship`/`federation_relationship_event` --
 * used by BOTH [network.lapis.cloud.server.rpc.FederationService] (the ADMIN-facing RPC surface
 * driving the OUTBOUND side of the Follow/Accept/Reject/Undo handshake) and
 * [network.lapis.cloud.server.routes.registerFederationRoutes]' inbox handler (the INBOUND side,
 * processing Activities received from remote servers) -- so both directions of the state machine
 * funnel through the exact same read/write/event-recording logic rather than risking drift between
 * two independent implementations. Every function here must be called from within an open
 * `transaction {}` block -- it never opens its own.
 *
 * **Concurrency (round-1 review fix): row-lock + compare-and-swap, same contract
 * [network.lapis.cloud.server.rpc.RegistrationService.approveApplication]/`rejectApplication` and
 * [network.lapis.cloud.server.rpc.PoliticianService.grantPoliticianStatus] already establish.**
 * Two servers (or two rapid requests) deciding the SAME relationship concurrently -- an ADMIN
 * clicking Accept while an inbound `Undo` races in, or two concurrent inbound `Follow` deliveries
 * for a never-before-seen remote actor -- is a real scenario (Fediverse redelivery is expected,
 * not a bug), so every status TRANSITION here is guarded two ways:
 *  1. [findByRemoteActorUri]/[findById] take an optional `forUpdate` row lock -- a concurrent
 *     transaction targeting the SAME row blocks until the first commits, then re-reads the
 *     now-decided status instead of racing.
 *  2. [updateStatusIfCurrently] is additionally a compare-and-swap (`status eq expectedStatus` in
 *     the `WHERE` clause) -- defense in depth against the same lost-update, and the ONLY correct
 *     guard across [network.lapis.cloud.server.rpc.FederationService]'s own outbound methods,
 *     which -- per this codebase's established "network fan-out never holds a DB connection open"
 *     discipline ([network.lapis.cloud.server.rpc.PriceOracleService]'s own ordering) -- release
 *     any `forUpdate` lock before the best-effort `deliverActivity` network call and only re-open a
 *     transaction afterward; the CAS is what actually catches a concurrent decision that landed
 *     during that network round-trip.
 *  3. [upsertByRemoteActorUri]'s fresh-INSERT branch cannot be protected by a row lock at all (no
 *     row exists yet to lock) -- two concurrent first-Follow attempts for the same never-before-seen
 *     remote actor can both observe no existing row and both reach the `INSERT`, racing on
 *     `remote_actor_uri`'s own `UNIQUE` constraint. Caught via the same "pre-check + backstop"
 *     idiom [PoliticianService.grantPoliticianStatus] KDoc "First-grant race" documents: catch
 *     [ExposedSQLException], re-read (this time WITH a row to lock), and defer to whatever the
 *     winner just committed.
 */
object FederationRelationshipStore {
    fun findByRemoteActorUri(
        remoteActorUri: String,
        forUpdate: Boolean = false,
    ): ResultRow? {
        val query = FederationRelationshipTable.selectAll().where { FederationRelationshipTable.remoteActorUri eq remoteActorUri }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
    }

    fun findById(
        id: Uuid,
        forUpdate: Boolean = false,
    ): ResultRow? {
        val query = FederationRelationshipTable.selectAll().where { FederationRelationshipTable.id eq id }
        return (if (forUpdate) query.forUpdate() else query).singleOrNull()
    }

    fun insert(
        direction: FederationRelationshipDirection,
        status: FederationRelationshipStatus,
        remoteActorUri: String,
        remoteInboxUri: String,
        remotePublicKeyPem: String?,
        initiatedActivityId: String,
        now: LocalDateTime,
    ): Uuid {
        val id = Uuid.random()
        FederationRelationshipTable.insert {
            it[FederationRelationshipTable.id] = id
            it[FederationRelationshipTable.direction] = direction
            it[FederationRelationshipTable.status] = status
            it[FederationRelationshipTable.remoteActorUri] = remoteActorUri
            it[FederationRelationshipTable.remoteInboxUri] = remoteInboxUri
            it[FederationRelationshipTable.remotePublicKeyPem] = remotePublicKeyPem
            it[FederationRelationshipTable.initiatedActivityId] = initiatedActivityId
            it[FederationRelationshipTable.createdAt] = now
            it[FederationRelationshipTable.updatedAt] = now
        }
        return id
    }

    /**
     * Insert-or-reuse for [remoteActorUri]. `federation_relationship.remote_actor_uri` carries a
     * hard DB `UNIQUE` constraint (one row per remote actor for the lifetime of this server) --
     * this is the ONE place that respects it while still honoring the "a terminal
     * (`REJECTED`/`UNDONE`) relationship does not block re-establishing federation" rule from
     * `24-federation.kuml.kts`/the V0.8.1 plan's state-machine section: if no row exists yet, a
     * fresh one is inserted; if a TERMINAL row already exists, that SAME row is UPDATED back to
     * `PENDING` (never a second `INSERT`, which would violate the unique constraint) -- its full
     * prior history remains reconstructable via the still-append-only
     * `federation_relationship_event` log regardless of how many times one row's `status` cycles
     * through terminal and back to `PENDING`.
     *
     * Returns the relationship id on success, or `null` if a NON-TERMINAL (`PENDING`/`ACTIVE`) row
     * already exists for [remoteActorUri] -- the caller decides what that means (a
     * [network.lapis.cloud.shared.rpc.ConflictException] for an RPC-initiated `initiateFollow`, or
     * a silent idempotent no-op for an inbound `Follow` received while already `PENDING`/`ACTIVE`).
     */
    fun upsertByRemoteActorUri(
        direction: FederationRelationshipDirection,
        remoteActorUri: String,
        remoteInboxUri: String,
        remotePublicKeyPem: String?,
        initiatedActivityId: String,
        now: LocalDateTime,
    ): Uuid? {
        // forUpdate=true: an EXISTING row is locked before its status is inspected, so a second,
        // concurrent upsert/transition on the SAME remote actor blocks until the first commits --
        // see class KDoc "Concurrency" point 1.
        val existing = findByRemoteActorUri(remoteActorUri = remoteActorUri, forUpdate = true)
        if (existing == null) {
            return try {
                insert(
                    direction = direction,
                    status = FederationRelationshipStatus.PENDING,
                    remoteActorUri = remoteActorUri,
                    remoteInboxUri = remoteInboxUri,
                    remotePublicKeyPem = remotePublicKeyPem,
                    initiatedActivityId = initiatedActivityId,
                    now = now,
                )
            } catch (e: ExposedSQLException) {
                // See class KDoc "Concurrency" point 3 -- the loser of a concurrent first-Follow
                // race retries the read (this time WITH a row to lock) and defers to the winner.
                val winner =
                    findByRemoteActorUri(remoteActorUri = remoteActorUri, forUpdate = true)
                        ?: throw e
                resolveAgainstExisting(
                    existing = winner,
                    direction = direction,
                    remoteInboxUri = remoteInboxUri,
                    remotePublicKeyPem = remotePublicKeyPem,
                    initiatedActivityId = initiatedActivityId,
                    now = now,
                )
            }
        }
        return resolveAgainstExisting(
            existing = existing,
            direction = direction,
            remoteInboxUri = remoteInboxUri,
            remotePublicKeyPem = remotePublicKeyPem,
            initiatedActivityId = initiatedActivityId,
            now = now,
        )
    }

    private fun resolveAgainstExisting(
        existing: ResultRow,
        direction: FederationRelationshipDirection,
        remoteInboxUri: String,
        remotePublicKeyPem: String?,
        initiatedActivityId: String,
        now: LocalDateTime,
    ): Uuid? {
        val status = existing[FederationRelationshipTable.status]
        if (status == FederationRelationshipStatus.PENDING || status == FederationRelationshipStatus.ACTIVE) return null

        val id = existing[FederationRelationshipTable.id]
        FederationRelationshipTable.update({ FederationRelationshipTable.id eq id }) {
            it[FederationRelationshipTable.direction] = direction
            it[FederationRelationshipTable.status] = FederationRelationshipStatus.PENDING
            it[FederationRelationshipTable.remoteInboxUri] = remoteInboxUri
            it[FederationRelationshipTable.remotePublicKeyPem] = remotePublicKeyPem
            it[FederationRelationshipTable.initiatedActivityId] = initiatedActivityId
            it[FederationRelationshipTable.updatedAt] = now
        }
        return id
    }

    /** Unconditional status write -- used only where the caller already holds an exclusive `forUpdate` lock spanning the whole decide-and-write window with no intervening network call (e.g. [upsertByRemoteActorUri]'s own reuse-terminal-row branch, and test fixture setup). Any call site whose decide-and-write window crosses a network call (best-effort Activity delivery) MUST use [updateStatusIfCurrently] instead -- see class KDoc "Concurrency" point 2. */
    fun updateStatus(
        id: Uuid,
        status: FederationRelationshipStatus,
        now: LocalDateTime,
    ) {
        FederationRelationshipTable.update({ FederationRelationshipTable.id eq id }) {
            it[FederationRelationshipTable.status] = status
            it[FederationRelationshipTable.updatedAt] = now
        }
    }

    /**
     * Compare-and-swap status transition: mutates [id]'s row from [expectedStatus] to [newStatus]
     * ONLY if that is still its current status at write time -- returns `true` on success, `false`
     * (no mutation) if a concurrent transaction already moved it away from [expectedStatus]. See
     * class KDoc "Concurrency" point 2; mirrors
     * [network.lapis.cloud.server.rpc.RegistrationService.approveApplication]'s own
     * `status eq MemberStatus.APPLICATION` WHERE-clause CAS.
     */
    fun updateStatusIfCurrently(
        id: Uuid,
        expectedStatus: FederationRelationshipStatus,
        newStatus: FederationRelationshipStatus,
        now: LocalDateTime,
    ): Boolean {
        val updated =
            FederationRelationshipTable.update({
                (FederationRelationshipTable.id eq id) and (FederationRelationshipTable.status eq expectedStatus)
            }) {
                it[FederationRelationshipTable.status] = newStatus
                it[FederationRelationshipTable.updatedAt] = now
            }
        return updated > 0
    }

    fun recordEvent(
        relationshipId: Uuid,
        eventType: FederationEventType,
        activityId: String?,
        activityJson: String,
        now: LocalDateTime,
    ) {
        FederationRelationshipEventTable.insert {
            it[id] = Uuid.random()
            it[FederationRelationshipEventTable.relationshipId] = relationshipId
            it[FederationRelationshipEventTable.eventType] = eventType
            it[FederationRelationshipEventTable.activityId] = activityId
            it[FederationRelationshipEventTable.activityJson] = activityJson
            it[occurredAt] = now
        }
    }

    fun listEvents(relationshipId: Uuid): List<ResultRow> =
        FederationRelationshipEventTable
            .selectAll()
            .where { FederationRelationshipEventTable.relationshipId eq relationshipId }
            .orderBy(FederationRelationshipEventTable.occurredAt, SortOrder.DESC)
            .toList()

    fun ResultRow.toRelationshipDto(): FederationRelationshipDto =
        FederationRelationshipDto(
            id = this[FederationRelationshipTable.id].toString(),
            direction = this[FederationRelationshipTable.direction],
            status = this[FederationRelationshipTable.status],
            remoteActorUri = this[FederationRelationshipTable.remoteActorUri],
            remoteInboxUri = this[FederationRelationshipTable.remoteInboxUri],
            createdAt = this[FederationRelationshipTable.createdAt],
            updatedAt = this[FederationRelationshipTable.updatedAt],
        )

    fun ResultRow.toEventDto(): FederationRelationshipEventDto =
        FederationRelationshipEventDto(
            id = this[FederationRelationshipEventTable.id].toString(),
            relationshipId = this[FederationRelationshipEventTable.relationshipId].toString(),
            eventType = this[FederationRelationshipEventTable.eventType],
            activityId = this[FederationRelationshipEventTable.activityId],
            occurredAt = this[FederationRelationshipEventTable.occurredAt],
        )
}
