package network.lapis.cloud.client

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import org.w3c.xhr.XMLHttpRequest
import kotlin.coroutines.resume

private external fun encodeURIComponent(value: String): String

/** What the account-less join endpoint answered -- a typed result so the screen never inspects raw status codes. */
internal sealed interface EventGuestJoinHttpResult {
    data class Answer(
        val response: EventGuestJoinResponseDto,
    ) : EventGuestJoinHttpResult

    /** 429: too many requests from this address or for this event. */
    data object RateLimited : EventGuestJoinHttpResult

    /** 409: the acknowledged notice is not the current one (the page is out of date). */
    data object NoticeOutdated : EventGuestJoinHttpResult

    /** 400: the name or the request was not acceptable. */
    data object Invalid : EventGuestJoinHttpResult

    /** Anything else (network, 5xx, an answer that cannot be read). */
    data object Failed : EventGuestJoinHttpResult
}

/** The two requests of the guest join page -- an interface so a DOM test can answer them without a server. */
internal interface EventGuestApi {
    suspend fun state(slug: String): EventOnlineStateDto?

    suspend fun join(
        slug: String,
        request: EventGuestJoinRequestDto,
    ): EventGuestJoinHttpResult
}

/**
 * Welle V1.9.113 -- the plain-HTTP side of the account-less online participation (`GET /api/events/{slug}/online-state`,
 * `POST /api/events/{slug}/online-guest`). No cookie is sent or needed (`withCredentials = false`): the person has no account, and the
 * server must not mix a session into this request. The ticket code travels only in the POST BODY, never in a URL. Plain `Json`
 * (the DTOs carry no Kilua-only types; their time fields are text).
 */
internal object EventGuestHttp : EventGuestApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun state(slug: String): EventOnlineStateDto? =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            xhr.open("GET", "/api/events/${encodeURIComponent(slug)}/online-state")
            xhr.withCredentials = false
            xhr.onload = {
                val parsed =
                    if (xhr.status.toInt() in 200..299) {
                        runCatching { json.decodeFromString(EventOnlineStateDto.serializer(), xhr.responseText) }.getOrNull()
                    } else {
                        null
                    }
                cont.resume(parsed)
                Unit
            }
            xhr.onerror = {
                cont.resume(null)
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send()
        }

    override suspend fun join(
        slug: String,
        request: EventGuestJoinRequestDto,
    ): EventGuestJoinHttpResult =
        suspendCancellableCoroutine { cont ->
            val xhr = XMLHttpRequest()
            xhr.open("POST", "/api/events/${encodeURIComponent(slug)}/online-guest")
            xhr.withCredentials = false
            xhr.setRequestHeader("Content-Type", "application/json")
            xhr.onload = {
                val result =
                    when (xhr.status.toInt()) {
                        in 200..299 ->
                            runCatching { json.decodeFromString(EventGuestJoinResponseDto.serializer(), xhr.responseText) }
                                .fold(
                                    onSuccess = { EventGuestJoinHttpResult.Answer(it) },
                                    onFailure = { EventGuestJoinHttpResult.Failed },
                                )
                        429 -> EventGuestJoinHttpResult.RateLimited
                        409 -> EventGuestJoinHttpResult.NoticeOutdated
                        400 -> EventGuestJoinHttpResult.Invalid
                        else -> EventGuestJoinHttpResult.Failed
                    }
                cont.resume(result)
                Unit
            }
            xhr.onerror = {
                cont.resume(EventGuestJoinHttpResult.Failed)
                Unit
            }
            cont.invokeOnCancellation { xhr.abort() }
            xhr.send(json.encodeToString(EventGuestJoinRequestDto.serializer(), request))
        }
}
