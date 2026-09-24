package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.rpc.EventReads
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * `list_upcoming_events` -- published, still-upcoming events only. `status = PUBLISHED` is hard-
 * wired inside [EventReads.listPublishedUpcoming], never the manager view -- see that object's
 * KDoc. No participant lists, no registration data.
 */
internal object ListUpcomingEventsTool {
    private const val MIN_LIMIT = 1
    private const val MAX_LIMIT = 50
    private const val DEFAULT_LIMIT = 10

    val definition =
        McpToolDefinition(
            name = "list_upcoming_events",
            title = "Bevorstehende Veranstaltungen",
            description = "Listet veröffentlichte, noch bevorstehende Veranstaltungen -- keine Teilnehmerlisten, keine internen Entwürfe.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("minimum", MIN_LIMIT)
                            put("maximum", MAX_LIMIT)
                            put("description", "Maximale Anzahl Veranstaltungen (Default $DEFAULT_LIMIT).")
                        }
                    }
                },
        )

    @Suppress("UNUSED_PARAMETER")
    fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ) = transaction {
        val limit =
            (arguments ?: JsonObject(emptyMap())).optionalIntArg(
                key = "limit",
                default = DEFAULT_LIMIT,
                range =
                    MIN_LIMIT..MAX_LIMIT,
            )
        val now = DbClock.nowLocalDateTime()
        val events = EventReads.listPublishedUpcoming(now = now, limit = limit)
        buildJsonObject {
            putJsonArray("events") {
                events.forEach { event ->
                    add(
                        buildJsonObject {
                            put("title", event.title)
                            put("startsAt", event.startsAt.toString())
                            put("endsAt", event.endsAt.toString())
                            put("locationText", event.locationText)
                            put("onlineUrl", event.onlineUrl)
                        },
                    )
                }
            }
        }
    }
}
