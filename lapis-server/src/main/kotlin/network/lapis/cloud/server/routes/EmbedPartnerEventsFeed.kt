package network.lapis.cloud.server.routes

import io.ktor.http.Parameters
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import network.lapis.cloud.server.partner.ForeignText
import network.lapis.cloud.server.partner.PartnerEventDisplay
import network.lapis.cloud.server.partner.PartnerEventView

private const val MAX_ORIGIN_NAME_CHARS = 80
private const val HTTPS_PREFIX = "https://"

/**
 * V1.9.248 -- one FOREIGN event in `GET /api/embed/v1/events?quelle=alle`. Foreign, unreviewed text: a consumer renders it as text only.
 * `online` and `via` have no defaults on purpose: the encoder omits default values, and `false` / `[]` must always be on the wire.
 * There is deliberately no online link: the partner feed has none.
 */
@Serializable
internal data class EmbedPartnerEventItemDto(
    val title: String,
    /** UTC, `...Z`. */
    val startsAt: String,
    val endsAt: String,
    /** IANA zone of the origin, UTC when it does not validate. */
    val timezone: String,
    val locationText: String? = null,
    val online: Boolean,
    val summary: String? = null,
    val seriesText: String? = null,
    /** https, the origin's own host, re-validated. */
    val url: String,
    val originName: String,
    val originHost: String,
    val via: List<String>,
)

@Serializable
internal data class EmbedEventListWithPartnersResponse(
    val events: List<EmbedEventListItemDto>,
    val partnerEvents: List<EmbedPartnerEventItemDto>,
)

/** Exactly one `quelle` parameter, exactly `alle`. Repeated, differently spelled or unknown values are the default feed. */
internal fun wantsPartnerEvents(params: Parameters): Boolean = params.getAll("quelle")?.let { it.size == 1 && it[0] == "alle" } == true

/** Pure. `null` = the event is dropped (its link does not pass the re-validation). */
internal fun PartnerEventView.toEmbedPartnerEventItem(): EmbedPartnerEventItemDto? {
    // The partner times are UTC already: format only (embedFeedUtc would convert a second time).
    val safeUrl = PartnerEventDisplay.revalidatedUrl(url = url, originHost = originHost) ?: return null
    if (!safeUrl.startsWith(HTTPS_PREFIX)) return null
    return EmbedPartnerEventItemDto(
        title = title,
        startsAt = embedFeedUtcFromSystem(startsAtUtc),
        endsAt = embedFeedUtcFromSystem(endsAtUtc),
        timezone = if (runCatching { TimeZone.of(timeZone) }.isSuccess) timeZone else "UTC",
        locationText = place?.takeIf { it.isNotBlank() },
        online = online,
        summary = summary,
        seriesText = recurrenceSentence,
        url = safeUrl,
        originName = ForeignText.ellipsize(text = originName ?: originHost, maxChars = MAX_ORIGIN_NAME_CHARS),
        originHost = originHost,
        via = via,
    )
}
