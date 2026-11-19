package network.lapis.cloud.server.partner

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.network.tls.TLSException
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * The ONE classification of a failed outgoing federation fetch into a code, shared by [PartnerFeedFetcher] and the carpool pull client (V1.9.314).
 * Looks through the cause chain (bounded), never at a message. Behaviour is that of the former private `PartnerFeedFetcher.classify`.
 */
internal fun classifyFederationFetchFailure(e: Throwable): PartnerFetchErrorCode {
    var cause: Throwable? = e
    var depth = 0
    while (cause != null && depth++ < 8) {
        when (cause) {
            is HttpRequestTimeoutException, is SocketTimeoutException -> return PartnerFetchErrorCode.TIMEOUT
            is TLSException, is SSLException -> return PartnerFetchErrorCode.TLS
            is ConnectException -> return PartnerFetchErrorCode.UNREACHABLE
        }
        if (cause.javaClass.simpleName == "ConnectTimeoutException") return PartnerFetchErrorCode.TIMEOUT
        cause = cause.cause
    }
    return PartnerFetchErrorCode.UNREACHABLE
}
