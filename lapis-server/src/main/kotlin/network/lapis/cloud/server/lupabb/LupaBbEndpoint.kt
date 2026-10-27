package network.lapis.cloud.server.lupabb

import java.net.URI
import java.net.URISyntaxException

/**
 * V1.9.226 -- the ONE base URL of the LupaBB API. There is no setting, no environment variable and no request field that can change it: the
 * sync can only talk to [PRODUCTION] (no SSRF through a user chosen target).
 *
 * The constructor is `internal` only so that the tests of this module can point a client at a fake server on the loopback interface; it
 * accepts exactly two shapes and refuses everything else: `https://lupabb.de/...` and `http://127.0.0.1:<port>/...` (or `localhost`,
 * `[::1]`). `LupaBbEndpointTripwireTest` pins that `src/main` creates exactly one instance ([PRODUCTION]) and mentions the host nowhere else.
 */
internal class LupaBbEndpoint internal constructor(
    val baseUrl: String,
) {
    init {
        val uri =
            try {
                URI(baseUrl)
            } catch (_: URISyntaxException) {
                throw IllegalArgumentException("The LupaBB endpoint is not a valid URL")
            }
        // the host is read from the PARSED URL (a prefix check would let `http://localhost:80@evil.example/` through)
        val production = uri.scheme == "https" && uri.host == PRODUCTION_HOST && uri.port == -1
        val loopback = uri.scheme == "http" && uri.host in LOOPBACK_HOSTS && uri.port > 0
        require(production || loopback) { "The LupaBB endpoint must be the production host or a loopback address" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "The LupaBB endpoint carries no credentials, query or fragment"
        }
        require(baseUrl.endsWith("/")) { "The LupaBB endpoint must end with a slash" }
    }

    override fun toString(): String = "LupaBbEndpoint"

    companion object {
        private const val PRODUCTION_HOST = "lupabb.de"
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

        val PRODUCTION = LupaBbEndpoint("https://lupabb.de/verwaltung10/index.php/api/")
    }
}
