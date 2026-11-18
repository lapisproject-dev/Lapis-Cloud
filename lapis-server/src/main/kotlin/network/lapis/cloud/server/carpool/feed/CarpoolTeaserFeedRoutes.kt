package network.lapis.cloud.server.carpool.feed

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.federation.FederationConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.ifNoneMatchHits
import network.lapis.cloud.server.routes.rateLimitKeyFor
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolFederationFeedAuditSnapshot
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

private sealed interface FeedOutcome {
    data object Refused : FeedOutcome

    class NotModified(
        val etag: String,
    ) : FeedOutcome

    class Ok(
        val body: ByteArray,
        val etag: String,
    ) : FeedOutcome
}

/**
 * V1.9.312 "Mitfahrzentrale-Foederation" W2 -- `GET /api/federation/carpool-feed/v1`: the signed teaser feed a DIRECT partner server may pull.
 * Default off: it answers only if the instance switch is on, the partner is active, not blocked, has carpool sharing on, announced a signing key
 * (pinned by the partner poll) AND the request is signed with that key. Everything else is ONE answer ([respondUnavailable]) -- no oracle for which
 * partners or keys exist.
 *
 * Verification uses the server's OWN host [ownHost] and the constant [CARPOOL_TEASER_FEED_PATH] (never the request's Host header or path), so a
 * signature made for another server cannot be replayed here. No outgoing call happens while a request is answered. Only a `200` is audited
 * (item count and `complete` flag, no content); refusals and `304` write nothing (no unauthenticated writes to the audit chain lock).
 *
 * @param teaserIds `null` when `LAPIS_SECRET_ENCRYPTION_KEY` is not configured: the feed then refuses everything.
 */
internal fun Route.registerCarpoolTeaserFeedRoutes(
    ipRateLimiter: FederationInboxRateLimiter,
    partnerRateLimiter: FederationInboxRateLimiter,
    globalRateLimiter: FederationInboxRateLimiter,
    replayGuard: FederationReplayGuard,
    teaserIds: CarpoolTeaserIds?,
    counters: CarpoolTeaserFeedCounters,
    ownHost: String? = PartnerHost.ownHostOf(FederationConfig.publicBaseUrl),
    clock: () -> Instant = { ServerClock.nowInstant() },
) {
    val gate = ownHost?.let { CarpoolTeaserFeedGate(replayGuard = replayGuard, ownHost = it, clock = clock) }
    // Warm the dummy key now, so the first unknown key id costs no more than a known one.
    check(gate == null || DummyVerificationKey.publicKeyPem.isNotEmpty())
    get(CARPOOL_TEASER_FEED_PATH) {
        try {
            handle(
                call = call,
                gate = gate,
                ownHost = ownHost,
                teaserIds = teaserIds,
                limiters = Limiters(ip = ipRateLimiter, partner = partnerRateLimiter, global = globalRateLimiter),
                counters = counters,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: a message could carry content.
            logger.warn { "CarpoolTeaserFeedRoutes: request failed (${e::class.simpleName})" }
            call.respondUnavailable()
        }
    }
}

private class Limiters(
    val ip: FederationInboxRateLimiter,
    val partner: FederationInboxRateLimiter,
    val global: FederationInboxRateLimiter,
)

private suspend fun handle(
    call: ApplicationCall,
    gate: CarpoolTeaserFeedGate?,
    ownHost: String?,
    teaserIds: CarpoolTeaserIds?,
    limiters: Limiters,
    counters: CarpoolTeaserFeedCounters,
) {
    // Before any authentication: a flood per IP never reaches the RSA verification. Not an oracle (it applies to everybody alike).
    if (!limiters.ip.checkAndRecord(rateLimitKeyFor(remoteHost = call.request.origin.remoteHost))) {
        counters.rateLimited()
        call.respondRateLimited()
        return
    }
    if (gate == null || ownHost == null || teaserIds == null) {
        counters.refusedNotApproved()
        call.respondUnavailable()
        return
    }
    val verified =
        gate.authenticate(
            signature = call.request.header("Signature"),
            date = call.request.header(HttpHeaders.Date),
            digest = call.request.header("Digest"),
            contentLength = call.request.header(HttpHeaders.ContentLength),
            transferEncoding = call.request.header(HttpHeaders.TransferEncoding),
            queryString = call.request.queryString(),
        )
    if (verified == null) {
        counters.refusedUnauthenticated()
        call.respondUnavailable()
        return
    }
    val approvedId: Uuid? =
        transaction {
            (
                gate.approve(verified = verified, settings = CarpoolFederationStore.settings())
                    ?: gate.approveTrailing(verified = verified, now = DbClock.nowLocalDateTime())
            )?.id
        }
    if (approvedId == null) {
        counters.refusedNotApproved()
        call.respondUnavailable()
        return
    }
    // Only an approved partner can hit these two, so a 429 says nothing about the consent of anybody else.
    if (!limiters.partner.checkAndRecord(approvedId.toString()) || !limiters.global.checkAndRecord(CarpoolTeaserFeedLimits.GLOBAL_KEY)) {
        counters.rateLimited()
        call.respondRateLimited()
        return
    }
    val ifNoneMatch = call.request.header(HttpHeaders.IfNoneMatch)
    val outcome: FeedOutcome =
        transaction {
            val settings = CarpoolFederationStore.settings()
            // The pin or the consent may have changed since the check above: decide again in THIS transaction.
            val now = DbClock.nowLocalDateTime()
            val full = gate.approve(verified = verified, settings = settings)
            // Wave 3: an approved partner gets the full snapshot; a partner that was served before but is no longer shared with gets ONLY the
            // withdrawals that are still live for it (never an item); everybody else gets the one refusal.
            val row = full ?: gate.approveTrailing(verified = verified, now = now) ?: return@transaction FeedOutcome.Refused
            val built =
                if (full != null) {
                    CarpoolTeaserFeedBuilder.build(
                        partner = row,
                        settings = settings,
                        ids = teaserIds,
                        originHost = ownHost,
                        today = OrganizationTimeZone.today(),
                        now = now,
                    )
                } else {
                    CarpoolTeaserFeedBuilder.buildWithdrawalsOnly(partner = row, ids = teaserIds, now = now)
                }
            val etag = "\"" + sha256Hex(built.body) + "\""
            // Taken AFTER the build on purpose: the served-at moment must not be earlier than anything the answer contains (a consent that was
            // committed before the read has an opt-in moment that is not later than this), or the "served since the consent" filter of the
            // withdrawal could miss a hint that was handed out.
            val servedAt = DbClock.nowLocalDateTime()
            if (ifNoneMatchHits(headerValue = ifNoneMatch, etag = etag)) {
                PartnerServerStore.recordCarpoolFeedServed(id = row.id, itemCount = null, now = servedAt)
                FeedOutcome.NotModified(etag)
            } else {
                PartnerServerStore.recordCarpoolFeedServed(id = row.id, itemCount = built.itemCount, now = servedAt)
                AuditLogRecorder.record(
                    actorMemberId = null,
                    actorRole = null,
                    entityType = AuditEntityType.CARPOOL_FEDERATION_FEED,
                    entityId = row.id,
                    action = AuditAction.CREATE,
                    after =
                        Json.encodeToString(
                            CarpoolFederationFeedAuditSnapshot.serializer(),
                            CarpoolFederationFeedAuditSnapshot(
                                itemCount = built.itemCount,
                                complete = built.complete,
                                withdrawnCount = built.withdrawnCount,
                            ),
                        ),
                )
                FeedOutcome.Ok(body = built.body, etag = etag)
            }
        }
    when (outcome) {
        FeedOutcome.Refused -> {
            counters.refusedNotApproved()
            call.respondUnavailable()
        }
        is FeedOutcome.NotModified -> {
            counters.notModified()
            call.setFeedHeaders(etag = outcome.etag)
            call.respond(HttpStatusCode.NotModified)
        }
        is FeedOutcome.Ok -> {
            counters.served()
            call.setFeedHeaders(etag = outcome.etag)
            call.respondBytes(bytes = outcome.body, contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8))
        }
    }
}

private fun ApplicationCall.setFeedHeaders(etag: String) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header("X-Content-Type-Options", "nosniff")
}

private suspend fun ApplicationCall.respondRateLimited() {
    response.header(HttpHeaders.RetryAfter, "60")
    respondText("""{"error":"rate_limited"}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests)
}

/** The ONLY place that writes the refusal answer: every cause (and every partner) gets exactly these bytes and headers. */
private suspend fun ApplicationCall.respondUnavailable() {
    response.header(HttpHeaders.CacheControl, "private, no-store")
    response.header("X-Content-Type-Options", "nosniff")
    respondText(
        text = """{"error":"unavailable"}""",
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
        status = HttpStatusCode.NotFound,
    )
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
