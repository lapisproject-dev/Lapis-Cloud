package network.lapis.cloud.server.mcp.transport

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable

/**
 * Capped body read for `POST /mcp` -- same shape as
 * [network.lapis.cloud.server.federation.readCappedFederationBodyOrNull], mirrored for an inbound
 * server request instead of an outbound client response. **Never** `call.receiveText()` unbounded
 * (see `routes.McpRoutes` KDoc "Body-Limit") -- a body exceeding [maxBytes] returns `null`, mapped
 * by the caller to HTTP 413.
 */
internal suspend fun ApplicationCall.receiveCappedTextOrNull(maxBytes: Int): String? {
    val channel = receiveChannel()
    val buffer = ByteArray(maxBytes + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    if (total > maxBytes) return null
    return buffer.copyOf(total).toString(Charsets.UTF_8)
}
