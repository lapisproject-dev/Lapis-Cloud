package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * V1.9.203 -- the destinations the person chose from the menu search last ("Zuletzt besucht"), newest first, at most [MAX].
 * A per-viewer convenience in `localStorage`: every access is guarded, so private mode, blocked site data, a throwing storage or broken
 * JSON simply mean "no recents" and never break the search. Only routes are stored (no query text).
 */
object MenuRecents {
    const val KEY = "lapis-cloud-menu-recent.v1"
    const val MAX = 5

    private val serializer = ListSerializer(String.serializer())

    /** The stored routes; an empty list when nothing is stored or the value is unusable. */
    fun load(): List<String> =
        runCatching {
            val raw = localStorage.getItem(KEY) ?: return@runCatching emptyList()
            Json
                .decodeFromString(serializer, raw)
                .filter { it.startsWith("/") }
                .distinct()
                .take(MAX)
        }.getOrDefault(emptyList())

    /** Puts [route] first (a repeat moves to the front) and persists the list; a silent no-op when storage is unavailable. */
    fun remember(route: String) {
        runCatching { localStorage.setItem(KEY, Json.encodeToString(serializer, push(load(), route))) }
    }

    /** Pure: [existing] with [route] moved to the front, capped at [MAX]. */
    internal fun push(
        existing: List<String>,
        route: String,
    ): List<String> = (listOf(route) + existing.filter { it != route }).take(MAX)
}
