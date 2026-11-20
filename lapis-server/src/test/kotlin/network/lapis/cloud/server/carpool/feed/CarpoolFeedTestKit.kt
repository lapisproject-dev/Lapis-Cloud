package network.lapis.cloud.server.carpool.feed

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.FederationActorKeyTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.federation.FederationReplayGuard
import network.lapis.cloud.server.federation.HttpSignatures
import network.lapis.cloud.server.partner.ParsedPartnerEvent
import network.lapis.cloud.server.partner.PartnerInsertResult
import network.lapis.cloud.server.partner.PartnerParseResult
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.partner.PinnedSigningKey
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal const val OWN_HOST = "origin.beispiel.de"

/** A partner row with a pinned key (or without), and the private half so a test can sign as that partner. */
internal class FeedPartner(
    val id: Uuid,
    val host: String,
    val keyId: String,
    val privateKeyPem: String,
    val publicKeyPem: String,
)

/** The headers of one signed request. */
internal class SignedGet(
    val signature: String,
    val date: String,
    val digest: String,
)

private val sequence = AtomicInteger()

/**
 * Inserts a direct partner through the store (never a raw UPDATE), with the announced key pinned when [pinned]; carpool sharing on when [sharing],
 * switched off when not [active].
 */
internal fun newFeedPartner(
    host: String = "p" + Uuid.random().toString().take(n = 8) + ".beispiel.de",
    sharing: Boolean = true,
    active: Boolean = true,
    pinned: Boolean = true,
    keyPair: network.lapis.cloud.server.federation.FederationKeyPair = FederationKeyPairGenerator.generate(),
    displayName: String? = null,
    remoteName: String = "Partner",
): FeedPartner {
    val keyId = "https://$host/federation/actor#main-key"
    val now = LocalDateTime(2026, 11, 5, 10, 0, 0)
    val inserted =
        transaction {
            PartnerServerStore.insertProbedPartner(
                host = host,
                displayName = displayName,
                parsed =
                    PartnerParseResult.Ok(
                        remoteServerId = Uuid.random(),
                        remoteName = remoteName,
                        events = emptyList<ParsedPartnerEvent>(),
                        partnersJson = null,
                        droppedEvents = 0,
                        signingKey = if (pinned) PinnedSigningKey(keyId = keyId, publicKeyPem = keyPair.publicKeyPem) else null,
                    ),
                etag = null,
                now = now,
            )
        } as PartnerInsertResult.Inserted
    transaction {
        if (sharing) PartnerServerStore.setCarpoolSharing(id = inserted.id, enabled = true)
        if (!active) PartnerServerStore.setActive(id = inserted.id, active = false, now = now)
    }
    return FeedPartner(
        id = inserted.id,
        host = host,
        keyId = keyId,
        privateKeyPem = keyPair.privateKeyPem,
        publicKeyPem = keyPair.publicKeyPem,
    )
}

/** A signature over `GET <path>` for [host]; each call moves the date by one more second so two calls never produce the same signature (the replay guard). */
internal fun signedGet(
    partner: FeedPartner,
    keyId: String = partner.keyId,
    privateKeyPem: String = partner.privateKeyPem,
    host: String = OWN_HOST,
    path: String = CARPOOL_TEASER_FEED_PATH,
    now: Instant = Clock.System.now() + sequence.incrementAndGet().seconds,
): SignedGet {
    val signed =
        HttpSignatures.sign(
            method = "GET",
            path = path,
            host = host,
            body = ByteArray(0),
            keyId = keyId,
            privateKeyPem = privateKeyPem,
            now = now,
        )
    return SignedGet(signature = signed.signatureHeader, date = signed.dateHeader, digest = signed.digestHeader)
}

/** The own signing key the feed signs its items with (idempotent; the production boot does the same). */
internal fun provisionOwnSigningKey(actorUri: String = "https://$OWN_HOST/federation/actor") {
    // The database is shared by the whole test JVM: a key that another spec provisioned for ITS actor URI (FederationServiceTest uses the configured
    // one) would be kept by ensureProvisioned and sign for the wrong host, so it is replaced first. Which spec runs first depends on the class order.
    transaction { FederationActorKeyTable.deleteWhere { FederationActorKeyTable.actorUri neq actorUri } }
    network.lapis.cloud.server.federation.FederationActorKeyProvisioner
        .ensureProvisioned(actorUri)
}

internal val TEST_BOX = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 1).toByte() })
internal val TEST_IDS = CarpoolTeaserIds(TEST_BOX)

/** What the refusal answer looks like on the wire: status, body and every header except the volatile ones. Equal for every refusal. */
internal suspend fun fingerprint(response: HttpResponse): String =
    buildString {
        append(response.status.value).append('|').append(response.bodyAsText()).append('|')
        response.headers
            .entries()
            .filter { it.key.lowercase() !in setOf("date", "server", "content-length", "connection") }
            .sortedBy { it.key.lowercase() }
            .forEach { append(it.key.lowercase()).append('=').append(it.value.joinToString(",")).append(';') }
    }

internal suspend fun HttpClient.feedGet(
    signed: SignedGet?,
    block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
    get(CARPOOL_TEASER_FEED_PATH) {
        if (signed != null) {
            header("Signature", signed.signature)
            header(HttpHeaders.Date, signed.date)
            header("Digest", signed.digest)
        }
        block()
    }

internal class FeedLimits(
    val ip: Int = 1000,
    val partner: Int = 1000,
    val global: Int = 1000,
)

/** The route under test, with its own limiters, replay guard and counters; the host/path are the injected [OWN_HOST]. */
internal fun feedApplication(
    limits: FeedLimits = FeedLimits(),
    ids: CarpoolTeaserIds? = TEST_IDS,
    ownHost: String? = OWN_HOST,
    buildPermits: Semaphore = CarpoolTeaserFeedLimits.newBuildPermits(),
    block: suspend ApplicationTestBuilder.(CarpoolTeaserFeedCounters) -> Unit,
) {
    val counters = CarpoolTeaserFeedCounters()
    io.ktor.server.testing.testApplication {
        application {
            routing {
                registerCarpoolTeaserFeedRoutes(
                    ipRateLimiter = FederationInboxRateLimiter(maxRequests = limits.ip, window = 1.minutes),
                    partnerRateLimiter = FederationInboxRateLimiter(maxRequests = limits.partner, window = 1.hours),
                    globalRateLimiter = FederationInboxRateLimiter(maxRequests = limits.global, window = 1.hours),
                    replayGuard = FederationReplayGuard(),
                    teaserIds = ids,
                    counters = counters,
                    ownHost = ownHost,
                    buildPermits = buildPermits,
                )
            }
        }
        block(counters)
    }
}
