package network.lapis.cloud.server.share

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.server.routes.EventPublicHtml
import network.lapis.cloud.server.routes.germanShortDateTime
import network.lapis.cloud.server.routes.germanWeekdayShort
import java.time.ZoneId

/**
 * Open Graph description, share text, preview image and schema.org JSON-LD of a public event (V1.9.217). All texts are German, like
 * the event page body itself.
 */
internal object EventShareMeta {
    const val OG_DESCRIPTION_MAX_CODEPOINTS = 300
    private const val SEPARATOR = " · "

    fun shareText(view: EventPublicHtml.View): String = "${view.title} · ${germanShortDateTime(view.startsAt)}"

    private fun firstParagraph(description: String): String {
        val first = EventText.paragraphs(EventText.normalizeMultiline(description)).firstOrNull() ?: return ""
        return ShareLinks.normalize(first.joinToString(" "))
    }

    fun ogDescription(view: EventPublicHtml.View): String {
        val whenPart = "${germanWeekdayShort(view.startsAt)}, ${germanShortDateTime(view.startsAt)} Uhr"
        val where =
            when {
                !view.locationText.isNullOrBlank() -> ShareLinks.normalize(view.locationText)
                view.onlineUrl != null || view.onlineJoinUrl != null -> "Online"
                else -> ""
            }
        val parts = listOf(whenPart, where, firstParagraph(view.description)).filter { it.isNotEmpty() }
        return ShareLinks.truncateCodepoints(value = parts.joinToString(SEPARATOR), max = OG_DESCRIPTION_MAX_CODEPOINTS)
    }

    /** Cover image, else a raster operator logo (PNG/WebP; SVG is rejected by social networks), else `null`. */
    fun ogImage(
        view: EventPublicHtml.View,
        baseUrl: String,
        branding: ResolvedBranding,
    ): String? {
        view.coverImageUrl?.let { return it }
        val logoPath = branding.logoPath?.lowercase() ?: return null
        val raster = logoPath.endsWith(".png") || logoPath.endsWith(".webp")
        return if (branding.logoAvailable && raster) "$baseUrl/api/branding/logo" else null
    }

    /**
     * Image for JSON-LD only (Google requires one): cover, else raster logo, else the public apple-touch-icon. Never `null`.
     * `og:image` deliberately keeps using [ogImage] so Open Graph behaviour is unchanged.
     */
    fun jsonLdImage(
        view: EventPublicHtml.View,
        baseUrl: String,
        branding: ResolvedBranding,
    ): String = ogImage(view = view, baseUrl = baseUrl, branding = branding) ?: "${baseUrl.trimEnd('/')}/apple-touch-icon.png"

    private val CURRENCY = Regex("^[A-Z]{3}$")

    private fun iso(
        dt: kotlinx.datetime.LocalDateTime,
        zone: TimeZone,
    ): String =
        java.time.LocalDateTime
            .of(dt.year, dt.monthNumber, dt.dayOfMonth, dt.hour, dt.minute, dt.second)
            .atZone(ZoneId.of(zone.id))
            .toOffsetDateTime()
            .toString()

    /** The complete, already script-block-safe JSON-LD document. */
    fun jsonLd(
        view: EventPublicHtml.View,
        shareUrl: String,
        image: String,
        branding: ResolvedBranding,
        baseUrl: String,
        orgZone: TimeZone,
    ): String {
        val orgUrl = baseUrl.trimEnd('/')
        val orgName = ShareLinks.normalize(branding.title)
        val physical = !view.locationText.isNullOrBlank()
        val online = view.onlineUrl != null || view.onlineJoinUrl != null
        val obj: JsonObject =
            buildJsonObject {
                put("@context", "https://schema.org")
                put("@type", "Event")
                put("name", ShareLinks.normalize(view.title))
                put("startDate", iso(dt = view.startsAt, zone = orgZone))
                if (view.endsAt > view.startsAt) put("endDate", iso(dt = view.endsAt, zone = orgZone))
                put(
                    "eventAttendanceMode",
                    when {
                        physical && online -> "https://schema.org/MixedEventAttendanceMode"
                        online -> "https://schema.org/OnlineEventAttendanceMode"
                        else -> "https://schema.org/OfflineEventAttendanceMode"
                    },
                )
                put("eventStatus", "https://schema.org/EventScheduled")
                val places = mutableListOf<JsonObject>()
                if (physical) {
                    val text = ShareLinks.normalize(view.locationText.orEmpty())
                    places +=
                        buildJsonObject {
                            put("@type", "Place")
                            put("name", text)
                            put("address", text)
                        }
                }
                if (online) {
                    places +=
                        buildJsonObject {
                            put("@type", "VirtualLocation")
                            put("url", shareUrl)
                        }
                }
                if (places.size == 1) {
                    put("location", places.single())
                } else if (places.size > 1) {
                    put("location", kotlinx.serialization.json.JsonArray(places))
                }
                put("url", shareUrl)
                put("image", JsonPrimitive(image))
                // The data model has no speaker/organizer-person field and `createdBy` is a member (PII) that must never be published:
                // organizer and performer are therefore both the operating organization.
                val organization =
                    buildJsonObject {
                        put("@type", "Organization")
                        if (orgName.isNotEmpty()) put("name", orgName)
                        put("url", orgUrl)
                    }
                put("organizer", organization)
                put("performer", organization)
                offer(view = view, shareUrl = shareUrl, orgZone = orgZone)?.let { put("offers", it) }
                put("description", ogDescription(view))
            }
        return escapeForScriptBlock(Json.encodeToString(JsonObject.serializer(), obj))
    }

    /**
     * The schema.org Offer, or `null` when no honest statement is possible (event over, or registration closed). There is no
     * registration-start field in the data model, so `validFrom` is never emitted.
     */
    private fun offer(
        view: EventPublicHtml.View,
        shareUrl: String,
        orgZone: TimeZone,
    ): JsonObject? {
        if (view.hasEnded || !view.registrationOpen) return null
        val free = view.feeAmount.compareTo(java.math.BigDecimal.ZERO) == 0
        return buildJsonObject {
            put("@type", "Offer")
            put("url", shareUrl)
            put("price", if (free) "0" else view.feeAmount.toPlainString())
            put("priceCurrency", view.feeCurrency.takeIf { CURRENCY.matches(it) } ?: "EUR")
            put("availability", if (view.full) "https://schema.org/SoldOut" else "https://schema.org/InStock")
            view.registrationClosesAt?.let { put("validThrough", iso(dt = it, zone = orgZone)) }
        }
    }

    /** Neutralizes everything that could end or confuse a `<script>` data block; the result is still valid JSON. */
    internal fun escapeForScriptBlock(json: String): String =
        json
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
            .replace(" ", "\\u2028")
            .replace(" ", "\\u2029")
}
