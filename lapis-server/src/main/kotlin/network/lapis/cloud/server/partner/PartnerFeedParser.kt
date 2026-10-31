package network.lapis.cloud.server.partner

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** One validated, cleaned event of a partner. Times are UTC (time class A); [timeZone] is only for the display. */
internal data class ParsedPartnerEvent(
    val id: String,
    val title: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime,
    val timeZone: String,
    val online: Boolean,
    val place: String?,
    val summary: String?,
    val recurrenceSentence: String?,
    val url: String,
)

internal sealed interface PartnerParseResult {
    data class Ok(
        val remoteServerId: Uuid,
        val remoteName: String?,
        val events: List<ParsedPartnerEvent>,
        /** The partner's own partner list as compact JSON for a later wave; stored, never interpreted or fetched. `null` when hidden or empty. */
        val partnersJson: String?,
        val droppedEvents: Int,
    ) : PartnerParseResult

    data class Invalid(
        val code: PartnerFetchErrorCode,
    ) : PartnerParseResult
}

/**
 * V1.9.244 -- reads the feed of a FOREIGN server. Nothing in it is trusted: the bytes are decoded strictly, the nesting depth is checked BEFORE
 * the JSON parser sees them (a deep `[[[[...` would overflow its stack), every field is validated and cleaned ([ForeignText]), an event URL must
 * point at the partner's OWN host, and an invalid event is dropped (counted) without invalidating the whole feed.
 */
internal object PartnerFeedParser {
    const val MAX_EVENTS_PER_PARTNER = 200
    const val MAX_JSON_DEPTH = 16
    const val MAX_REMOTE_PARTNERS = 100
    private const val MAX_PARTNERS_JSON_BYTES = 64 * 1024
    private const val MAX_EVENT_LENGTH_DAYS = 31
    private const val MAX_START_AHEAD_DAYS = 366
    private val EVENT_ID = Regex("^[A-Za-z0-9._:-]{1,100}$")

    fun parse(
        bytes: ByteArray,
        partnerHost: String,
        ownServerId: Uuid,
        knownRemoteId: Uuid?,
        now: Instant,
    ): PartnerParseResult {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return invalid()
            }
        if (exceedsDepth(text)) return invalid()
        val feed =
            runCatching { PARTNER_FEED_JSON.decodeFromString(PartnerFeedV1.serializer(), text) }.getOrNull() ?: return invalid()
        if (feed.schemaVersion != 1 || feed.kind != PARTNER_FEED_KIND) return invalid()
        val remoteId = runCatching { Uuid.parse(feed.serverId) }.getOrNull() ?: return invalid()
        if (remoteId == ownServerId) return PartnerParseResult.Invalid(PartnerFetchErrorCode.OWN_SERVER_ID)
        if (knownRemoteId != null && knownRemoteId != remoteId) return PartnerParseResult.Invalid(PartnerFetchErrorCode.SERVER_ID_CHANGED)

        val seen = HashSet<String>()
        var dropped = 0
        val valid = ArrayList<ParsedPartnerEvent>()
        for (raw in feed.events) {
            if (raw.kind != PARTNER_FEED_EVENT_KIND) continue
            val event = parseEvent(raw = raw, partnerHost = partnerHost, now = now)
            if (event == null || !seen.add(event.id)) {
                dropped++
            } else {
                valid += event
            }
        }
        val sorted = valid.sortedWith(compareBy({ it.startsAt }, { it.id }))
        val kept = sorted.take(MAX_EVENTS_PER_PARTNER)
        dropped += sorted.size - kept.size
        if (dropped > 0) logger.info { "PartnerFeedParser: host=$partnerHost dropped=$dropped kept=${kept.size}" }
        return PartnerParseResult.Ok(
            remoteServerId = remoteId,
            remoteName = ForeignText.clean(raw = feed.name, maxChars = 100),
            events = kept,
            partnersJson = if (feed.partnersHidden) null else partnersJsonOf(feed.partners),
            droppedEvents = dropped,
        )
    }

    private fun invalid() = PartnerParseResult.Invalid(PartnerFetchErrorCode.INVALID_FEED)

    /** Counts `{` / `[` outside of strings; no parsing, no recursion. */
    internal fun exceedsDepth(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > MAX_JSON_DEPTH) return true
                    '}', ']' -> depth--
                }
            }
        }
        return false
    }

    private fun parseEvent(
        raw: PartnerFeedEvent,
        partnerHost: String,
        now: Instant,
    ): ParsedPartnerEvent? {
        if (!EVENT_ID.matches(raw.id)) return null
        val title = ForeignText.clean(raw = raw.title, maxChars = 200) ?: return null
        val starts = parseOffset(raw.startsAt) ?: return null
        val ends = parseOffset(raw.endsAt) ?: return null
        if (ends < starts) return null
        if (starts < now - 1.days || starts > now + MAX_START_AHEAD_DAYS.days) return null
        if (ends > starts + MAX_EVENT_LENGTH_DAYS.days) return null
        val url = ownHostUrlOrNull(raw = raw.url, partnerHost = partnerHost) ?: return null
        val zone = if (raw.timeZone.length <= 64 && runCatching { TimeZone.of(raw.timeZone) }.isSuccess) raw.timeZone else "UTC"
        return ParsedPartnerEvent(
            id = raw.id,
            title = title,
            startsAt = starts.toUtcLocal(),
            endsAt = ends.toUtcLocal(),
            timeZone = zone,
            online = raw.online,
            place = ForeignText.clean(raw = raw.place, maxChars = 200),
            summary = ForeignText.clean(raw = raw.summary, maxChars = 300),
            recurrenceSentence = ForeignText.clean(raw = raw.recurrence?.sentence, maxChars = 200),
            url = url,
        )
    }

    private fun parseOffset(raw: String): Instant? =
        runCatching { Instant.fromEpochSeconds(OffsetDateTime.parse(raw).toEpochSecond()) }.getOrNull()

    private fun Instant.toUtcLocal(): LocalDateTime =
        java.time.LocalDateTime
            .ofInstant(toJavaInstant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.SECONDS)
            .toKotlinLocalDateTime()

    /** `https`, no user info, port 443 or none, and the host is EXACTLY the partner's own host -- a partner can only link to itself. */
    private fun ownHostUrlOrNull(
        raw: String,
        partnerHost: String,
    ): String? {
        if (raw.length > 500 || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        return if (host == partnerHost) raw else null
    }

    private fun partnersJsonOf(partners: List<PartnerFeedPartner>): String? {
        val cleaned =
            partners
                .asSequence()
                .filter { it.kind == PARTNER_FEED_PARTNER_KIND }
                .mapNotNull { p ->
                    val host =
                        (PartnerHost.normalize(raw = p.host, ownHost = null) as? PartnerAddressParse.Ok)?.host ?: return@mapNotNull null
                    PartnerFeedPartner(
                        kind = PARTNER_FEED_PARTNER_KIND,
                        host = host,
                        name =
                            ForeignText.clean(raw = p.name, maxChars = 100) ?: host,
                    )
                }.take(MAX_REMOTE_PARTNERS)
                .toList()
        if (cleaned.isEmpty()) return null
        val json = PARTNER_FEED_JSON.encodeToString(ListSerializer(PartnerFeedPartner.serializer()), cleaned)
        return json.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_PARTNERS_JSON_BYTES }
    }
}
