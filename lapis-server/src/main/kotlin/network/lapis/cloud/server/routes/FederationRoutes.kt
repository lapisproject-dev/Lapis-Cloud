package network.lapis.cloud.server.routes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.db.generated.FederationInboxDeliveryLogTable
import network.lapis.cloud.server.db.generated.FederationRelationshipEventTable
import network.lapis.cloud.server.db.generated.FederationRelationshipTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.ACTIVITY_JSON_CONTENT_TYPE
import network.lapis.cloud.server.federation.ACTIVITY_PUB_CONTEXT
import network.lapis.cloud.server.federation.Activity
import network.lapis.cloud.server.federation.ActorDocument
import network.lapis.cloud.server.federation.FEDERATION_ACTOR_KEY_ID
import network.lapis.cloud.server.federation.FEDERATION_JSON
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationRelationshipStore
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.federation.PublicKeyBlock
import network.lapis.cloud.server.federation.asObjectIdOrNull
import network.lapis.cloud.server.federation.fetchActorDocument
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.FederationEventType
import network.lapis.cloud.shared.domain.FederationRelationshipDirection
import network.lapis.cloud.shared.domain.FederationRelationshipStatus
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Hard cap on a raw `POST /federation/inbox` body -- DoS guard, enforced BEFORE any JSON parsing (see class KDoc "Ordering"). Generous for a small Activity + `lapis:` extension block, far below the 25 MiB document-upload cap. */
private const val MAX_INBOX_BODY_BYTES = 64 * 1024

/** DoS guard against attacker-crafted deep JSON nesting -- checked via a linear, non-recursive bracket scan on the raw body TEXT before any (recursive) JSON parser ever touches it, see [exceedsMaxJsonNestingDepth]. */
private const val MAX_JSON_NESTING_DEPTH = 20

private const val MAX_OUTBOX_ITEMS = 50

private val OUTBOX_EVENT_TYPES =
    listOf(
        FederationEventType.FOLLOW_SENT,
        FederationEventType.ACCEPT_SENT,
        FederationEventType.REJECT_SENT,
        FederationEventType.UNDO_SENT,
    )

private val RECOGNIZED_ACTIVITY_TYPES = setOf("Follow", "Accept", "Reject", "Undo")

/**
 * V0.8.1 Federation-Grundgerüst public HTTP surface -- `GET /federation/actor` (this server's
 * ActivityPub Actor document), `GET /federation/outbox` (a minimal, capped `OrderedCollection` of
 * outbound Activities), `POST /federation/inbox` (signed Activity delivery from untrusted,
 * self-declared remote servers). Dedicated Ktor routes, NOT Kilua RPC -- same "spec-mandated
 * paths/content-types, pre-auth (HTTP-Signature-verified, not session-cookie), payload shape fixed
 * by an external spec" reasoning [registerAuthRoutes]/[registerBackupRoutes] already establish for
 * their own non-RPC surfaces. See [network.lapis.cloud.shared.rpc.IFederationService] KDoc for the
 * full protocol rationale.
 *
 * **Ordering in the inbox handler is load-bearing** (task requirement 5): rate-limit check (pure
 * flood guard, before any body read) -> `Content-Length` pre-check -> bounded streaming body read
 * (hard cap BEFORE any byte is interpreted) -> header presence/structure checks -> HTTP Signature
 * verification (cheap: string/byte comparisons and one RSA verify, no JSON parsing yet) -> replay
 * check -> ONLY THEN a linear-scan JSON-nesting-depth check on the raw text -> typed JSON decode.
 * Every branch that rejects (as well as the success path) writes exactly one
 * [FederationInboxDeliveryLogTable] row -- a forensic log of every request this endpoint ever
 * receives, verified or not.
 */
fun Route.registerFederationRoutes(
    inboxRateLimiter: FederationInboxRateLimiter,
    replayGuard: FederationReplayGuard,
) {
    get("/federation/actor") {
        val document =
            transaction {
                val actorRow =
                    FederationActorKeyTable
                        .selectAll()
                        .where { FederationActorKeyTable.id eq FEDERATION_ACTOR_KEY_ID }
                        .singleOrNull() ?: return@transaction null
                val organizationName =
                    OrganizationSettingsTable
                        .selectAll()
                        .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                        .singleOrNull()
                        ?.get(OrganizationSettingsTable.name) ?: "Lapis Cloud"
                val actorUri = actorRow[FederationActorKeyTable.actorUri]
                ActorDocument(
                    id = actorUri,
                    name = organizationName,
                    inbox = FederationConfig.inboxUri,
                    outbox = FederationConfig.outboxUri,
                    publicKey =
                        PublicKeyBlock(
                            id = "$actorUri#main-key",
                            owner = actorUri,
                            publicKeyPem = actorRow[FederationActorKeyTable.publicKeyPem],
                        ),
                )
            }
        if (document == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, "Federation actor not yet provisioned")
            return@get
        }
        call.respondText(
            FEDERATION_JSON.encodeToString(ActorDocument.serializer(), document),
            contentType = ContentType.parse(ACTIVITY_JSON_CONTENT_TYPE),
        )
    }

    get("/federation/outbox") {
        val activityJsonStrings =
            transaction {
                FederationRelationshipEventTable
                    .selectAll()
                    .where { FederationRelationshipEventTable.eventType inList OUTBOX_EVENT_TYPES }
                    .orderBy(FederationRelationshipEventTable.occurredAt, SortOrder.DESC)
                    .limit(MAX_OUTBOX_ITEMS)
                    .map { it[FederationRelationshipEventTable.activityJson] }
            }
        val items: List<JsonElement> =
            activityJsonStrings.mapNotNull { runCatching { FEDERATION_JSON.parseToJsonElement(it) }.getOrNull() }
        val collection =
            buildJsonObject {
                put("@context", ACTIVITY_PUB_CONTEXT)
                put("id", FederationConfig.outboxUri)
                put("type", "OrderedCollection")
                put("totalItems", items.size)
                put("orderedItems", JsonArray(items))
            }
        // JsonObject.toString() renders valid compact JSON directly -- no serializer needed for a
        // plain JsonElement tree.
        call.respondText(collection.toString(), contentType = ContentType.parse(ACTIVITY_JSON_CONTENT_TYPE))
    }

    post("/federation/inbox") {
        val remoteHost = call.request.origin.remoteHost

        // 1. Pure flood guard -- BEFORE any body read.
        if (!inboxRateLimiter.checkAndRecord(remoteHost)) {
            call.respond(HttpStatusCode.TooManyRequests, "Too many requests")
            return@post
        }

        // 2. Content-Length pre-check -- reject an announced-oversized body before reading a byte.
        val declaredContentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredContentLength != null && declaredContentLength > MAX_INBOX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max inbox payload size is $MAX_INBOX_BODY_BYTES bytes")
            return@post
        }

        // 3. Bounded streaming read -- hard cap enforced regardless of a missing/lying Content-Length.
        val bodyBytes = readCappedInboxBody(call)
        if (bodyBytes == null) {
            call.respond(HttpStatusCode.PayloadTooLarge, "Max inbox payload size is $MAX_INBOX_BODY_BYTES bytes")
            return@post
        }

        val signatureHeader = call.request.headers["Signature"]
        val dateHeader = call.request.headers[HttpHeaders.Date]
        val digestHeader = call.request.headers["Digest"]
        // call.request.host() (normalized, no port for the default) rather than a raw Host header
        // lookup -- matches what a sender's own "host" pseudo-header value names in the common
        // no-reverse-proxy case, and is robust against header-casing/multiplicity quirks.
        val requestHost = call.request.host()

        suspend fun logDelivery(
            signatureVerified: Boolean,
            rejectReason: String?,
            activityType: String?,
            activityId: String?,
        ) {
            transaction {
                FederationInboxDeliveryLogTable.insert {
                    it[id] = Uuid.random()
                    it[receivedAt] = nowLocalDateTime()
                    it[FederationInboxDeliveryLogTable.remoteHost] = remoteHost
                    it[keyId] = HttpSignatures.extractKeyId(signatureHeader)
                    it[FederationInboxDeliveryLogTable.signatureVerified] = signatureVerified
                    it[FederationInboxDeliveryLogTable.rejectReason] = rejectReason
                    it[FederationInboxDeliveryLogTable.activityType] = activityType
                    it[FederationInboxDeliveryLogTable.activityId] = activityId
                    it[bodySha256] = sha256Hex(bodyBytes)
                    it[bodyByteSize] = bodyBytes.size
                }
            }
        }

        // 4. Header presence/structure.
        if (signatureHeader == null || dateHeader == null || digestHeader == null) {
            logDelivery(false, "MISSING_HEADER", null, null)
            call.respond(HttpStatusCode.Unauthorized, "Missing Signature/Date/Digest header")
            return@post
        }

        val keyId = HttpSignatures.extractKeyId(signatureHeader)
        if (keyId == null) {
            logDelivery(false, "MALFORMED", null, null)
            call.respond(HttpStatusCode.Unauthorized, "Malformed Signature header")
            return@post
        }

        val senderKeyInfo = resolveSenderKeyInfo(keyId)
        if (senderKeyInfo == null) {
            logDelivery(false, "UNKNOWN_KEY", null, null)
            call.respond(HttpStatusCode.Unauthorized, "Could not resolve a public key for keyId '$keyId'")
            return@post
        }

        // 5. HTTP Signature verification -- cheap (string/byte comparisons + one RSA verify), no
        // JSON parsing yet.
        val verification =
            HttpSignatures.verify(
                method = "POST",
                path = call.request.path(),
                host = requestHost,
                body = bodyBytes,
                signatureHeader = signatureHeader,
                dateHeader = dateHeader,
                digestHeader = digestHeader,
                publicKeyPem = senderKeyInfo.publicKeyPem,
            )
        if (verification is HttpSignatures.VerificationResult.Invalid) {
            logDelivery(false, verification.reason, null, null)
            call.respond(HttpStatusCode.Unauthorized, "Signature verification failed: ${verification.reason}")
            return@post
        }

        // 6. Replay guard.
        if (!replayGuard.checkAndRecord(signatureHeader)) {
            logDelivery(false, "REPLAY", null, null)
            call.respond(HttpStatusCode.Unauthorized, "Replayed signature")
            return@post
        }

        // 7. ONLY NOW: a linear, non-recursive nesting-depth scan on the raw text, before any
        // (recursive) JSON parser ever touches attacker-supplied bytes.
        val bodyText = bodyBytes.toString(Charsets.UTF_8)
        if (exceedsMaxJsonNestingDepth(text = bodyText, maxDepth = MAX_JSON_NESTING_DEPTH)) {
            logDelivery(true, "JSON_TOO_DEEP", null, null)
            call.respond(HttpStatusCode.BadRequest, "JSON nesting too deep")
            return@post
        }

        val activity = runCatching { FEDERATION_JSON.decodeFromString(Activity.serializer(), bodyText) }.getOrNull()
        if (activity == null) {
            logDelivery(true, "MALFORMED_ACTIVITY", null, null)
            call.respond(HttpStatusCode.BadRequest, "Malformed Activity JSON")
            return@post
        }

        // 7.5. Actor/keyId binding (round-1 review fix, CRITICAL). The HTTP Signature only proves
        // "this request was signed by whoever controls the key at `keyId`" -- it says nothing about
        // the ActivityPub `actor` field inside the JSON body, which is attacker-controlled content,
        // not something `HttpSignatures.verify` ever inspects. Without this check, ANY actor able to
        // stand up its own valid ActivityPub actor/key pair (i.e. any Fediverse participant) could
        // sign a request with ITS OWN key but set `activity.actor` to an arbitrary OTHER actor's URI
        // -- `dispatchInboundActivity` would then create/mutate that OTHER actor's relationship row
        // using the attacker's own inbox/key (Follow), or CAS an existing PENDING outbound Follow to
        // ACTIVE/REJECTED as if the real target actor had responded (Accept/Reject), or silently
        // undo an ACTIVE relationship (Undo) -- full actor impersonation, entirely bypassing what
        // HTTP Signatures exists to guarantee. `keyId` is conventionally `"<actorUri>#main-key"`
        // (see [HttpSignatures.sign] KDoc) -- the two MUST name the same actor.
        val signingActorUri = keyId.substringBefore("#")
        if (activity.actor != signingActorUri) {
            logDelivery(false, "ACTOR_KEY_MISMATCH", activity.type, activity.id)
            call.respond(HttpStatusCode.Unauthorized, "Activity actor does not match the signing key's actor")
            return@post
        }

        // 8. Dispatch -- an unsupported `type` is graceful degradation (202, logged, no mutation),
        // not an error, matching Fediverse convention and avoiding leaking "we don't support X" as
        // a probe signal.
        val now = nowLocalDateTime()
        dispatchInboundActivity(activity, bodyText, senderKeyInfo, now)
        logDelivery(true, null, activity.type, activity.id)
        call.respond(HttpStatusCode.Accepted)
    }
}

private data class SenderKeyInfo(
    val publicKeyPem: String,
    val inboxUri: String,
)

/**
 * Resolves the public key (and, alongside it, the inbox URI -- needed if a `Follow` from this
 * sender turns out to require creating/reusing a relationship row) for a `keyId` of the
 * conventional `"<actorUri>#main-key"` shape. Prefers an already-known relationship's cached
 * [FederationRelationshipTable.remotePublicKeyPem]/`remoteInboxUri` (no network call); falls back
 * to an SSRF-guarded fetch of the actor document at the `keyId`'s actor URI otherwise. `null` if
 * neither source yields a usable key.
 */
private suspend fun resolveSenderKeyInfo(keyId: String): SenderKeyInfo? {
    val actorUri = keyId.substringBefore("#")
    val cachedRow = transaction { FederationRelationshipStore.findByRemoteActorUri(remoteActorUri = actorUri) }
    val cachedKey = cachedRow?.get(FederationRelationshipTable.remotePublicKeyPem)
    if (cachedRow != null && cachedKey != null) {
        return SenderKeyInfo(publicKeyPem = cachedKey, inboxUri = cachedRow[FederationRelationshipTable.remoteInboxUri])
    }
    val fetched = fetchActorDocument(actorUri) ?: return null
    // Defense in depth alongside the caller's own activity.actor/keyId check: a fetched document
    // that doesn't even claim to BE the actor we asked for (its own `id` disagrees with the URI we
    // fetched) is never trustworthy, regardless of what the caller later compares it against.
    if (fetched.id != actorUri) return null
    return SenderKeyInfo(publicKeyPem = fetched.publicKey.publicKeyPem, inboxUri = fetched.inbox)
}

/**
 * Dispatches a signature-verified, freshly-decoded inbound [Activity] -- see
 * `24-federation.kuml.kts` file header / the V0.8.1 plan's state-machine section for the full
 * Follow/Accept/Reject/Undo transition table. Every branch is idempotent-safe: a Follow received
 * while already `PENDING`/`ACTIVE` is a silent no-op (not an error), and an Accept/Reject/Undo that
 * does not match a known, appropriately-staged relationship is likewise a silent no-op -- a remote
 * server retrying delivery (a real Fediverse behavior) must never corrupt local state.
 */
private fun dispatchInboundActivity(
    activity: Activity,
    activityJson: String,
    senderKeyInfo: SenderKeyInfo,
    now: LocalDateTime,
) {
    when (activity.type) {
        "Follow" ->
            transaction {
                val id =
                    FederationRelationshipStore.upsertByRemoteActorUri(
                        direction = FederationRelationshipDirection.INBOUND,
                        remoteActorUri = activity.actor,
                        remoteInboxUri = senderKeyInfo.inboxUri,
                        remotePublicKeyPem = senderKeyInfo.publicKeyPem,
                        initiatedActivityId = activity.id,
                        now = now,
                    )
                if (id != null) {
                    FederationRelationshipStore.recordEvent(
                        relationshipId = id,
                        eventType = FederationEventType.FOLLOW_RECEIVED,
                        activityId = activity.id,
                        activityJson = activityJson,
                        now = now,
                    )
                }
            }
        "Accept" ->
            transitionOnMatchingOutboundFollow(
                activity = activity,
                activityJson = activityJson,
                now = now,
                newStatus = FederationRelationshipStatus.ACTIVE,
                eventType = FederationEventType.ACCEPT_RECEIVED,
            )
        "Reject" ->
            transitionOnMatchingOutboundFollow(
                activity = activity,
                activityJson = activityJson,
                now = now,
                newStatus = FederationRelationshipStatus.REJECTED,
                eventType = FederationEventType.REJECT_RECEIVED,
            )
        "Undo" ->
            transaction {
                // forUpdate=true + updateStatusIfCurrently CAS -- see FederationRelationshipStore
                // KDoc "Concurrency": serializes against a concurrent Accept/Reject/Undo racing on
                // the SAME relationship (e.g. an ADMIN's acceptInboundFollow committing between this
                // read and this write).
                val existing = FederationRelationshipStore.findByRemoteActorUri(remoteActorUri = activity.actor, forUpdate = true)
                if (existing != null && existing[FederationRelationshipTable.status] == FederationRelationshipStatus.ACTIVE) {
                    val id = existing[FederationRelationshipTable.id]
                    val applied =
                        FederationRelationshipStore.updateStatusIfCurrently(
                            id = id,
                            expectedStatus = FederationRelationshipStatus.ACTIVE,
                            newStatus = FederationRelationshipStatus.UNDONE,
                            now = now,
                        )
                    if (applied) {
                        FederationRelationshipStore.recordEvent(
                            relationshipId = id,
                            eventType = FederationEventType.UNDO_RECEIVED,
                            activityId = activity.id,
                            activityJson = activityJson,
                            now = now,
                        )
                    }
                }
            }
        else -> Unit
    }
}

private fun transitionOnMatchingOutboundFollow(
    activity: Activity,
    activityJson: String,
    now: LocalDateTime,
    newStatus: FederationRelationshipStatus,
    eventType: FederationEventType,
) {
    transaction {
        // forUpdate=true + updateStatusIfCurrently CAS -- see FederationRelationshipStore KDoc
        // "Concurrency": a duplicate/replayed Accept-or-Reject delivery for the same relationship
        // (real Fediverse redelivery behavior) must never double-transition or double-log.
        val existing =
            FederationRelationshipStore.findByRemoteActorUri(remoteActorUri = activity.actor, forUpdate = true) ?: return@transaction
        val objectId = activity.activityObject.asObjectIdOrNull()
        val matches =
            existing[FederationRelationshipTable.direction] == FederationRelationshipDirection.OUTBOUND &&
                existing[FederationRelationshipTable.status] == FederationRelationshipStatus.PENDING &&
                (objectId == null || objectId == existing[FederationRelationshipTable.initiatedActivityId])
        if (matches) {
            val id = existing[FederationRelationshipTable.id]
            val applied =
                FederationRelationshipStore.updateStatusIfCurrently(
                    id = id,
                    expectedStatus = FederationRelationshipStatus.PENDING,
                    newStatus = newStatus,
                    now = now,
                )
            if (applied) {
                FederationRelationshipStore.recordEvent(
                    relationshipId = id,
                    eventType = eventType,
                    activityId = activity.id,
                    activityJson = activityJson,
                    now = now,
                )
            }
        }
    }
}

/** Bounded streaming read, mirrors [network.lapis.cloud.server.routes.registerDocumentRoutes]'/[registerBackupRoutes]'s own `MAX_UPLOAD_BYTES`/`MAX_RESTORE_BUNDLE_BYTES` byte-counting-loop idiom -- returns `null` if [MAX_INBOX_BODY_BYTES] is exceeded, the body discarded rather than partially processed. */
private suspend fun readCappedInboxBody(call: ApplicationCall): ByteArray? {
    val channel = call.receiveChannel()
    val buffer = ByteArray(MAX_INBOX_BODY_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_INBOX_BODY_BYTES) null else buffer.copyOf(total)
}

/**
 * `true` iff [text]'s `{`/`[`/`}`/`]` nesting ever exceeds [maxDepth] -- a single linear pass over
 * the raw characters (bracket counting with minimal string-literal awareness so a bracket inside a
 * quoted JSON string value is never miscounted), deliberately NOT using any JSON parser: even
 * building a [kotlinx.serialization.json.JsonElement] tree is itself a RECURSIVE-descent operation
 * that could overflow the stack on sufficiently deep (but small-in-bytes) attacker-crafted input
 * before this function would ever get a chance to reject it. This scan is the actual DoS defense;
 * [MAX_INBOX_BODY_BYTES] alone does not bound nesting depth (a few KiB of `[[[[...]]]]` can nest
 * thousands of levels deep).
 */
private fun exceedsMaxJsonNestingDepth(
    text: String,
    maxDepth: Int,
): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> {
                depth++
                if (depth > maxDepth) return true
            }
            '}', ']' -> depth--
        }
    }
    return false
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()
