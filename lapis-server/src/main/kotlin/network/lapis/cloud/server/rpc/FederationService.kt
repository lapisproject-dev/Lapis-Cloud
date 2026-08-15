package network.lapis.cloud.server.rpc
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.server.federation.ACTIVITY_JSON_CONTENT_TYPE
import network.lapis.cloud.server.federation.Activity
import network.lapis.cloud.server.federation.FEDERATION_ACTOR_KEY_ID
import network.lapis.cloud.server.federation.FEDERATION_JSON
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationRelationshipStore
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.federation.activityIdObject
import network.lapis.cloud.server.federation.actorUriObject
import network.lapis.cloud.server.federation.federationHttpClient
import network.lapis.cloud.server.federation.fetchActorDocument
import network.lapis.cloud.server.federation.requireSafeFederationUrl
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.FederationActorDto
import network.lapis.cloud.shared.domain.FederationEventType
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.FederationRelationshipDto
import network.lapis.cloud.shared.domain.FederationRelationshipEventDto
import network.lapis.cloud.shared.domain.FederationRelationshipStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IFederationService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V0.8.1 Federation-Grundgerüst RPC surface -- see [IFederationService] KDoc and
 * `24-federation.kuml.kts` file header for the full fachlich model. Every method is ADMIN-only.
 *
 * **Outbound Activity delivery is best-effort and happens OUTSIDE any DB `transaction {}`** --
 * mirrors [PriceOracleService]'s own "network fan-out never holds a DB connection open" ordering
 * discipline. A remote server being unreachable never prevents the local relationship row from
 * reflecting this server's own intent (see each method's KDoc in [IFederationService]).
 */
class FederationService(
    private val call: ApplicationCall,
) : IFederationService {
    override suspend fun getLocalActor(): FederationActorDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            val row = loadLocalActorKeyRow()
            FederationActorDto(
                actorUri = row[FederationActorKeyTable.actorUri],
                publicKeyPem = row[FederationActorKeyTable.publicKeyPem],
                createdAt = row[FederationActorKeyTable.createdAt],
            )
        }
    }

    override suspend fun listFederationRelationships(status: FederationRelationshipStatus?): List<FederationRelationshipDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            // Condition built up-front, not a `.where {}.andWhere {}` chain -- same "one Op<Boolean>
            // handed to a single .where {}" idiom PoliticianService.loadWeightHistory establishes,
            // this codebase does not use Exposed's separate `andWhere` extension anywhere else.
            val query =
                if (status != null) {
                    FederationRelationshipTable.selectAll().where { FederationRelationshipTable.status eq status }
                } else {
                    FederationRelationshipTable.selectAll()
                }
            with(FederationRelationshipStore) { query.map { it.toRelationshipDto() } }
        }
    }

    override suspend fun getFederationRelationship(id: String): FederationRelationshipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = id.toRelationshipUuidOrThrow()
        return transaction {
            val row = FederationRelationshipStore.findById(id = uuid) ?: throw NotFoundException("Federation relationship $id not found")
            with(FederationRelationshipStore) { row.toRelationshipDto() }
        }
    }

    override suspend fun listFederationEvents(relationshipId: String): List<FederationRelationshipEventDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = relationshipId.toRelationshipUuidOrThrow()
        return transaction {
            FederationRelationshipStore.findById(id = uuid) ?: throw NotFoundException("Federation relationship $relationshipId not found")
            with(FederationRelationshipStore) { FederationRelationshipStore.listEvents(uuid).map { it.toEventDto() } }
        }
    }

    override suspend fun initiateFollow(remoteActorUri: String): FederationRelationshipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)

        runCatching { requireSafeFederationUrl(remoteActorUri) }
            .onFailure { throw BadRequestException(it.message ?: "Invalid remoteActorUri: $remoteActorUri") }

        val existing = transaction { FederationRelationshipStore.findByRemoteActorUri(remoteActorUri = remoteActorUri) }
        if (existing != null) {
            val existingStatus = existing[FederationRelationshipTable.status]
            if (existingStatus == FederationRelationshipStatus.PENDING || existingStatus == FederationRelationshipStatus.ACTIVE) {
                throw ConflictException("A non-terminal relationship to $remoteActorUri already exists (status=$existingStatus)")
            }
        }

        val remoteActor =
            fetchActorDocument(remoteActorUri)
                ?: throw BadRequestException("Could not fetch or parse an ActivityPub actor document at $remoteActorUri")

        val actorKeyRow = transaction { loadLocalActorKeyRow() }
        val activityId = newActivityId()
        val activity =
            Activity(
                id = activityId,
                type = "Follow",
                actor = FederationConfig.actorUri,
                activityObject = actorUriObject(remoteActorUri),
            )
        val activityJson = FEDERATION_JSON.encodeToString(Activity.serializer(), activity)

        deliverActivity(inboxUri = remoteActor.inbox, activityJson = activityJson, actorKeyRow = actorKeyRow)

        val now = nowLocalDateTime()
        return transaction {
            // Authoritative check (the pre-check above is only a fast-path avoiding a wasted
            // network fetch) -- see FederationRelationshipStore.upsertByRemoteActorUri KDoc for
            // why this is an upsert, not a plain insert (remote_actor_uri's UNIQUE constraint).
            val id =
                FederationRelationshipStore.upsertByRemoteActorUri(
                    direction = FederationRelationshipDirection.OUTBOUND,
                    remoteActorUri = remoteActorUri,
                    remoteInboxUri = remoteActor.inbox,
                    remotePublicKeyPem = remoteActor.publicKey.publicKeyPem,
                    initiatedActivityId = activityId,
                    now = now,
                ) ?: throw ConflictException("A non-terminal relationship to $remoteActorUri already exists")
            FederationRelationshipStore.recordEvent(
                relationshipId = id,
                eventType = FederationEventType.FOLLOW_SENT,
                activityId = activityId,
                activityJson = activityJson,
                now = now,
            )
            with(FederationRelationshipStore) { FederationRelationshipStore.findById(id = id)!!.toRelationshipDto() }
        }
    }

    override suspend fun acceptInboundFollow(relationshipId: String): FederationRelationshipDto =
        respondToInboundFollow(
            relationshipId = relationshipId,
            activityType = "Accept",
            newStatus = FederationRelationshipStatus.ACTIVE,
            eventType = FederationEventType.ACCEPT_SENT,
        )

    override suspend fun rejectInboundFollow(relationshipId: String): FederationRelationshipDto =
        respondToInboundFollow(
            relationshipId = relationshipId,
            activityType = "Reject",
            newStatus = FederationRelationshipStatus.REJECTED,
            eventType = FederationEventType.REJECT_SENT,
        )

    override suspend fun undoRelationship(relationshipId: String): FederationRelationshipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = relationshipId.toRelationshipUuidOrThrow()

        val row =
            transaction {
                // forUpdate=true -- see FederationRelationshipStore KDoc "Concurrency" point 1.
                val r =
                    FederationRelationshipStore.findById(id = uuid, forUpdate = true)
                        ?: throw NotFoundException("Federation relationship $relationshipId not found")
                if (r[FederationRelationshipTable.status] != FederationRelationshipStatus.ACTIVE) {
                    throw ConflictException(
                        "Relationship $relationshipId is not ACTIVE (status=${r[FederationRelationshipTable.status]}) -- cannot undo",
                    )
                }
                r
            }
        val actorKeyRow = transaction { loadLocalActorKeyRow() }

        val activityId = newActivityId()
        val activity =
            Activity(
                id = activityId,
                type = "Undo",
                actor = FederationConfig.actorUri,
                activityObject = activityIdObject(row[FederationRelationshipTable.initiatedActivityId]),
            )
        val activityJson = FEDERATION_JSON.encodeToString(Activity.serializer(), activity)

        // Best-effort -- "we no longer trust them" must not depend on their reachability, see
        // IFederationService.undoRelationship KDoc. Deliberately OUTSIDE any transaction/lock (this
        // codebase's own "network fan-out never holds a DB connection open" discipline) -- the
        // forUpdate lock taken above is already released by the time this runs, so a concurrent
        // decision on the SAME relationship can land during this network round-trip. The
        // updateStatusIfCurrently CAS below is what actually catches that.
        deliverActivity(inboxUri = row[FederationRelationshipTable.remoteInboxUri], activityJson = activityJson, actorKeyRow = actorKeyRow)

        val now = nowLocalDateTime()
        return transaction {
            val applied =
                FederationRelationshipStore.updateStatusIfCurrently(
                    id = uuid,
                    expectedStatus = FederationRelationshipStatus.ACTIVE,
                    newStatus = FederationRelationshipStatus.UNDONE,
                    now = now,
                )
            if (!applied) {
                throw ConflictException("Relationship $relationshipId was concurrently decided while delivering -- retry")
            }
            FederationRelationshipStore.recordEvent(
                relationshipId = uuid,
                eventType = FederationEventType.UNDO_SENT,
                activityId = activityId,
                activityJson = activityJson,
                now = now,
            )
            with(FederationRelationshipStore) { FederationRelationshipStore.findById(id = uuid)!!.toRelationshipDto() }
        }
    }

    private suspend fun respondToInboundFollow(
        relationshipId: String,
        activityType: String,
        newStatus: FederationRelationshipStatus,
        eventType: FederationEventType,
    ): FederationRelationshipDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val uuid = relationshipId.toRelationshipUuidOrThrow()

        val row =
            transaction {
                // forUpdate=true -- see FederationRelationshipStore KDoc "Concurrency" point 1.
                val r =
                    FederationRelationshipStore.findById(id = uuid, forUpdate = true)
                        ?: throw NotFoundException("Federation relationship $relationshipId not found")
                if (r[FederationRelationshipTable.direction] != FederationRelationshipDirection.INBOUND ||
                    r[FederationRelationshipTable.status] != FederationRelationshipStatus.PENDING
                ) {
                    throw ConflictException(
                        "Relationship $relationshipId is not a pending inbound Follow " +
                            "(direction=${r[FederationRelationshipTable.direction]}, status=${r[FederationRelationshipTable.status]})",
                    )
                }
                r
            }
        val actorKeyRow = transaction { loadLocalActorKeyRow() }

        val activityId = newActivityId()
        val activity =
            Activity(
                id = activityId,
                type = activityType,
                actor = FederationConfig.actorUri,
                activityObject = activityIdObject(row[FederationRelationshipTable.initiatedActivityId]),
            )
        val activityJson = FEDERATION_JSON.encodeToString(Activity.serializer(), activity)

        // Best-effort, deliberately outside any transaction/lock -- see undoRelationship's own
        // comment on this same "network fan-out never holds a DB connection open" discipline. The
        // updateStatusIfCurrently CAS below is what actually catches a concurrent decision landing
        // during this network round-trip (e.g. an inbound Undo, or the SAME relationship being
        // accepted/rejected twice concurrently).
        deliverActivity(inboxUri = row[FederationRelationshipTable.remoteInboxUri], activityJson = activityJson, actorKeyRow = actorKeyRow)

        val now = nowLocalDateTime()
        return transaction {
            val applied =
                FederationRelationshipStore.updateStatusIfCurrently(
                    id = uuid,
                    expectedStatus = FederationRelationshipStatus.PENDING,
                    newStatus = newStatus,
                    now = now,
                )
            if (!applied) {
                throw ConflictException("Relationship $relationshipId was concurrently decided while delivering -- retry")
            }
            FederationRelationshipStore.recordEvent(
                relationshipId = uuid,
                eventType = eventType,
                activityId = activityId,
                activityJson = activityJson,
                now = now,
            )
            with(FederationRelationshipStore) { FederationRelationshipStore.findById(id = uuid)!!.toRelationshipDto() }
        }
    }

    private fun loadLocalActorKeyRow(): ResultRow =
        FederationActorKeyTable
            .selectAll()
            .where { FederationActorKeyTable.id eq FEDERATION_ACTOR_KEY_ID }
            .singleOrNull()
            ?: throw NotFoundException("FederationActorKey row $FEDERATION_ACTOR_KEY_ID not found -- provisioning missing?")

    private fun newActivityId(): String = "${FederationConfig.actorUri}/activities/${Uuid.random()}"

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toRelationshipUuidOrThrow(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    /** Best-effort signed delivery -- every failure (SSRF rejection, unreachable host, non-2xx, network error) is logged and swallowed, never thrown, per every call site's own KDoc ("delivery failure does not prevent..."). */
    private suspend fun deliverActivity(
        inboxUri: String,
        activityJson: String,
        actorKeyRow: ResultRow,
    ) {
        runCatching {
            val target = requireSafeFederationUrl(inboxUri)
            val bodyBytes = activityJson.toByteArray(Charsets.UTF_8)
            val url = Url(inboxUri)
            val signed =
                HttpSignatures.sign(
                    method = "POST",
                    path = url.encodedPath,
                    host = url.host,
                    body = bodyBytes,
                    keyId = "${actorKeyRow[FederationActorKeyTable.actorUri]}#main-key",
                    privateKeyPem = actorKeyRow[FederationActorKeyTable.privateKeyPem],
                )
            federationHttpClient(target).use { client ->
                client.post(inboxUri) {
                    header(HttpHeaders.Date, signed.dateHeader)
                    header("Digest", signed.digestHeader)
                    header("Signature", signed.signatureHeader)
                    contentType(ContentType.parse(ACTIVITY_JSON_CONTENT_TYPE))
                    setBody(bodyBytes)
                }
            }
        }.onFailure { e ->
            logger.warn { "Federation delivery to $inboxUri failed (best-effort, relationship state unaffected): ${e.message}" }
        }
    }
}
