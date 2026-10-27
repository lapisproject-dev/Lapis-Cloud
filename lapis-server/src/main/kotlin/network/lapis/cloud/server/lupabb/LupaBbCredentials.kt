package network.lapis.cloud.server.lupabb

/** How the API key travels: as the header `X-API-KEY` or (the way the old PZB did it) as the query parameter of the same name. */
internal enum class ApiKeyTransport { HEADER, QUERY }

/**
 * V1.9.226 -- user, password and API key of the LupaBB API. Deliberately NOT a data class (no `copy`, no generated `toString`/`equals`), and
 * [toString] prints nothing of the content.
 */
internal class LupaBbCredentials(
    val username: String,
    val password: String,
    val apiKey: String,
) {
    override fun toString(): String = "LupaBbCredentials(***)"
}
