package network.lapis.cloud.server.partner

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerDiscoveredServerTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

internal val RECURSION_NOW = LocalDateTime(2026, 11, 5, 10, 0, 0)

/**
 * A fake network of several partner servers: `host -> feed bytes` (404 for an unknown host), counting the requests per host. The host of a
 * request is read from the `Host` header (the connection itself is pinned to a fixed public address, as in production).
 */
internal class FakeWorld {
    val feeds = ConcurrentHashMap<String, ByteArray>()
    val calls = ConcurrentHashMap<String, AtomicInteger>()
    val etags = ConcurrentHashMap<String, String>()
    val net = FakeNetwork()

    /** Called with the host of every request (tests advance a fake clock here). */
    @Volatile var onRequest: (String) -> Unit = {}

    /** Serves a feed for [host]: its events point at [host] itself (the parser requires that). */
    fun serve(
        host: String,
        serverId: Uuid = Uuid.random(),
        partners: List<String> = emptyList(),
        eventIds: List<String> = listOf("e1"),
        hidden: Boolean = false,
        name: String = "Server $host",
        title: (String) -> String = { "Treffen $host $it" },
        startsAt: String = "2026-11-12T19:00:00+01:00",
        endsAt: String = "2026-11-12T21:00:00+01:00",
        extra: String = "",
    ): Uuid {
        feeds[host] =
            feedJson(
                events =
                    eventIds.map {
                        feedEventJson(
                            id = it,
                            title = title(it),
                            url = "https://$host/veranstaltung/$it",
                            startsAt = startsAt,
                            endsAt = endsAt,
                            extra = extra,
                        )
                    },
                serverId = serverId,
                partners = partnersJson(partners),
                partnersHidden = hidden,
                name = name,
            )
        return serverId
    }

    fun callsTo(host: String): Int = calls[host]?.get() ?: 0

    fun totalCalls(): Int = calls.values.sumOf { it.get() }

    fun fetcher(): PartnerFeedFetcher =
        fakeFetcher(net = net) { request ->
            val host = request.headers[HttpHeaders.Host]?.substringBefore(':') ?: ""
            calls.getOrPut(host) { AtomicInteger() }.incrementAndGet()
            onRequest(host)
            val body = feeds[host]
            if (body == null) respond(content = "", status = HttpStatusCode.NotFound) else json(body = body)
        }
}

internal fun partnersJson(hosts: List<String>): String =
    PARTNER_FEED_JSON.encodeToString(
        ListSerializer(PartnerFeedPartner.serializer()),
        hosts.map { PartnerFeedPartner(kind = PARTNER_FEED_PARTNER_KIND, host = it, name = "Name $it") },
    )

/** Inserts a DIRECT partner as the probe would (with a stored partner list). Returns its row id. */
internal fun addDirectPartner(
    host: String,
    serverId: Uuid = Uuid.random(),
    partners: List<String> = emptyList(),
    now: LocalDateTime = RECURSION_NOW,
    eventIds: List<String> = listOf("d1"),
    displayName: String? = null,
    eventStart: LocalDateTime = LocalDateTime(2026, 11, 12, 18, 0, 0),
    eventEnd: LocalDateTime = LocalDateTime(2026, 11, 12, 20, 0, 0),
): Uuid {
    val parsed =
        PartnerParseResult.Ok(
            remoteServerId = serverId,
            remoteName = "Direkt $host",
            events =
                eventIds.map {
                    ParsedPartnerEvent(
                        id = it,
                        title = "Direkt $it",
                        startsAt = eventStart,
                        endsAt = eventEnd,
                        timeZone = "Europe/Berlin",
                        online = false,
                        place = null,
                        summary = null,
                        recurrenceSentence = null,
                        url = "https://$host/veranstaltung/$it",
                    )
                },
            partnersJson = if (partners.isEmpty()) null else partnersJson(partners),
            droppedEvents = 0,
        )
    val result =
        transaction {
            PartnerServerStore.insertProbedPartner(
                host = host,
                displayName = displayName,
                parsed = parsed,
                etag = null,
                now = now,
            )
        }
    return (result as PartnerInsertResult.Inserted).id
}

internal fun wipePartnerTables() {
    transaction {
        PartnerEventTable.deleteAll()
        PartnerDiscoveredServerTable.deleteAll()
        PartnerServerTable.deleteAll()
        PartnerBlocklistTable.deleteAll()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[partnerRecursionEnabled] = true
            it[partnerRecursionDepth] = 2.toShort()
            it[partnerRecursionLastRunAt] = null
            it[partnerRecursionLastSummary] = null
        }
    }
}

internal fun runnerFor(
    world: FakeWorld,
    now: () -> LocalDateTime = { RECURSION_NOW },
    monotonic: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic,
    budget: kotlin.time.Duration = PartnerRecursionLimits.RUN_TIME_BUDGET,
) = PartnerRecursionRunner(
    fetcher = world.fetcher(),
    monotonic = monotonic,
    clock = now,
    publicBaseUrl = "https://own.example.com",
    timeBudget = budget,
)
