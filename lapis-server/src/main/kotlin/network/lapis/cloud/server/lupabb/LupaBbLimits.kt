package network.lapis.cloud.server.lupabb

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** V1.9.226 -- every bound of the LupaBB client. A test may shrink them; production uses [DEFAULT]. */
internal data class LupaBbLimits(
    val connectTimeoutMillis: Long = 10_000,
    val requestTimeoutMillis: Long = 30_000,
    val socketTimeoutMillis: Long = 30_000,
    /** The whole fetch (all requests together). */
    val totalTimeout: Duration = 5.minutes,
    val maxResponseBytes: Int = 5 * 1024 * 1024,
    val maxPages: Int = 200,
    val maxRecords: Int = 20_000,
) {
    companion object {
        val DEFAULT = LupaBbLimits()
    }
}
