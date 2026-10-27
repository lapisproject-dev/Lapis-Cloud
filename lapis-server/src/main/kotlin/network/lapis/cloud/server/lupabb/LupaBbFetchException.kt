package network.lapis.cloud.server.lupabb

internal enum class LupaBbErrorCode { AUTH_REJECTED, UNAVAILABLE, UNEXPECTED_RESPONSE, LIMIT_EXCEEDED, TIMEOUT }

/**
 * V1.9.226 -- the ONLY exception the LupaBB client lets out. Fixed message, NO `cause`: a Ktor or IO exception carries the request URL (and with
 * the query transport the API key in it), and the RPC layer logs the message and the cause chain of every exception.
 */
internal class LupaBbFetchException(
    val code: LupaBbErrorCode,
) : RuntimeException("LupaBB request failed: $code")
