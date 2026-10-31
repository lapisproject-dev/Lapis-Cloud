package network.lapis.cloud.server.partner

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.federation.FederationIpPinningPlugin
import network.lapis.cloud.server.federation.SafeFederationTarget
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** What the fake network of a test saw. */
internal class FakeNetwork {
    val resolutions = AtomicInteger(0)
    val requests = AtomicInteger(0)
    val hosts = mutableListOf<String>()

    @Volatile var lastHostHeader: String? = null

    @Volatile var lastUrlHost: String? = null
}

internal fun publicTargetFor(host: String): SafeFederationTarget =
    SafeFederationTarget(
        originalHost = host,
        pinnedAddress = InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)),
    )

/**
 * A [PartnerFeedFetcher] on a fake network: the resolver hands out a public address WITHOUT any DNS lookup (or `null` for a host in
 * [unresolvable]), the client is a [MockEngine] behind the real [FederationIpPinningPlugin]. `handler` answers the requests.
 */
internal fun fakeFetcherClientFactory(
    net: FakeNetwork,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): (SafeFederationTarget) -> HttpClient =
    { target ->
        HttpClient(
            MockEngine { request ->
                net.requests.incrementAndGet()
                net.hosts += request.url.host
                net.lastUrlHost = request.url.host
                net.lastHostHeader = request.headers[HttpHeaders.Host]
                handler(request)
            },
        ) {
            install(FederationIpPinningPlugin) { this.target = target }
            followRedirects = false
            expectSuccess = false
        }
    }

internal fun fakeFetcher(
    net: FakeNetwork = FakeNetwork(),
    unresolvable: Set<String> = emptySet(),
    totalTimeout: Duration = 10.seconds,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): PartnerFeedFetcher =
    PartnerFeedFetcher(
        resolveTarget = { host ->
            net.resolutions.incrementAndGet()
            if (host in unresolvable) null else publicTargetFor(host)
        },
        clientFactory = fakeFetcherClientFactory(net = net, handler = handler),
        totalTimeout = totalTimeout,
    )

internal fun MockRequestHandleScope.json(
    body: ByteArray,
    status: HttpStatusCode = HttpStatusCode.OK,
    etag: String? = null,
): HttpResponseData {
    val headers =
        buildList {
            add(HttpHeaders.ContentType to "application/json; charset=utf-8")
            if (etag != null) add(HttpHeaders.ETag to etag)
        }
    return respond(content = body, status = status, headers = headersOf(*headers.map { it.first to listOf(it.second) }.toTypedArray()))
}

/** The seeded ADMIN of `DevSeedData`, creator of the throwaway events. */
internal val PARTNER_TEST_MEMBER: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")

internal fun insertPartnerTestEvent(
    slug: String,
    title: String = "Treffen $slug",
    visibility: EventVisibility = EventVisibility.PUBLIC,
    status: EventStatus = EventStatus.PUBLISHED,
    startsAt: LocalDateTime = LocalDateTime(2026, 11, 20, 19, 0, 0),
    endsAt: LocalDateTime = LocalDateTime(2026, 11, 20, 21, 0, 0),
    onlineUrl: String? = null,
    description: String = "Beschreibung",
    seriesId: Uuid? = null,
): Uuid {
    val eventId = Uuid.random()
    transaction {
        EventTable.insert {
            it[id] = eventId
            it[EventTable.slug] = slug
            it[EventTable.title] = title
            it[EventTable.description] = description
            it[EventTable.startsAt] = startsAt
            it[EventTable.endsAt] = endsAt
            it[feeAmount] = BigDecimal.ZERO
            it[feeCurrency] = "EUR"
            it[EventTable.status] = status
            it[EventTable.visibility] = visibility
            it[createdAt] = LocalDateTime(2026, 11, 1, 0, 0, 0)
            it[createdBy] = PARTNER_TEST_MEMBER
            it[locationText] = "Saal"
            it[EventTable.onlineUrl] = onlineUrl
            if (onlineUrl != null) it[onlineKind] = EventOnlineKind.EXTERNAL_LINK
            it[EventTable.seriesId] = seriesId
            if (seriesId != null) it[seriesOriginalStart] = startsAt
        }
    }
    return eventId
}
