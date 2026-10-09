package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.shared.rpc.BadRequestException
import java.security.MessageDigest

/**
 * Welle V1.9.82 -- pure validation of the admin import payload for PAST events (JSON array, see `docs/api/event-import.adoc`). No
 * database access, no I/O, no clock of its own (the caller passes `wallNow`).
 *
 * **Security posture.** Strict key whitelist per entry (no `ignoreUnknownKeys`): `status`, `visibility`, `fee*`, `createdBy`, `imported`
 * and anything else not listed are REJECTED (mass-assignment protection). The server fixes everything not in the whitelist. Error texts
 * are fixed German sentences that name the entry index and the field, never the payload content. Texts are stored as PLAIN TEXT -- HTML
 * is never interpreted (only a non-blocking hint is raised). No URL is ever fetched (syntax check only).
 *
 * **Time.** `startsAt`/`endsAt` are naive wall-clock values of the organization zone (class B): format `yyyy-MM-ddTHH:mm`, no offset, no
 * `Z`, no seconds. They are stored AS TYPED and compared with `wallNow`; nothing is converted to UTC here (that happens only when the feed
 * is rendered).
 */
object EventImportPolicy {
    const val MAX_ENTRIES = 200
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    /**
     * The format is an array of flat objects (nesting depth 2). One more level is tolerated so that a wrong value such as `"title": ["x"]` is
     * reported per entry; anything deeper is refused BEFORE parsing, so a megabyte of `[` cannot build a million nested tree nodes.
     */
    const val MAX_NESTING_DEPTH = 3

    val SLUG_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    val NAIVE_LOCAL = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")

    private val ALLOWED_KEYS =
        setOf(
            "slug",
            "title",
            "description",
            "locationText",
            "startsAt",
            "endsAt",
            "summary",
            "coverImageAlt",
            "onlineUrl",
            "onlineUrlPublic",
        )
    private val SAFE_KEY_NAME = Regex("^[A-Za-z0-9_]{1,40}$")
    private val PARSER = Json

    /** A fully validated, normalized entry, ready to be inserted. */
    data class ParsedEntry(
        val index: Int,
        val slug: String,
        val title: String,
        val description: String,
        val locationText: String?,
        val onlineUrl: String?,
        val onlineUrlPublic: Boolean,
        val startsAt: LocalDateTime,
        val endsAt: LocalDateTime,
        val summary: String?,
        val coverImageAlt: String?,
    )

    sealed interface EntryCheck {
        val index: Int

        data class Valid(
            val entry: ParsedEntry,
            val hints: List<String>,
        ) : EntryCheck {
            override val index: Int get() = entry.index
        }

        data class Invalid(
            override val index: Int,
            val slug: String?,
            val title: String?,
            val reasons: List<String>,
        ) : EntryCheck
    }

    /**
     * Parses the payload. Top-level problems (size, not valid JSON, not an array, more than [MAX_ENTRIES] entries) throw
     * [BadRequestException]; problems of single entries are reported as [EntryCheck.Invalid].
     */
    fun parse(
        json: String,
        wallNow: LocalDateTime,
    ): List<EntryCheck> {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            throw BadRequestException("Die Datei ist zu groß (maximal 2 MiB).")
        }
        if (exceedsNestingDepth(json)) throw BadRequestException("Die Datei ist zu tief verschachtelt.")
        val root: JsonElement =
            try {
                PARSER.parseToJsonElement(json)
            } catch (_: SerializationException) {
                throw BadRequestException("Die Datei ist kein gültiges JSON.")
            } catch (_: IllegalArgumentException) {
                throw BadRequestException("Die Datei ist kein gültiges JSON.")
            }
        if (root !is JsonArray) throw BadRequestException("Die Datei muss eine Liste (JSON-Array) von Veranstaltungen enthalten.")
        if (root.size > MAX_ENTRIES) throw BadRequestException("Die Datei enthält zu viele Einträge (maximal $MAX_ENTRIES).")

        val seenSlugs = HashSet<String>()
        return root.mapIndexed { index, element ->
            val check =
                if (element is JsonObject) {
                    validateEntry(raw = element, index = index, wallNow = wallNow)
                } else {
                    EntryCheck.Invalid(index = index, slug = null, title = null, reasons = listOf("Eintrag ${index + 1}: Kein Objekt."))
                }
            if (check is EntryCheck.Valid && !seenSlugs.add(check.entry.slug)) {
                EntryCheck.Invalid(
                    index = index,
                    slug = check.entry.slug,
                    title = check.entry.title,
                    reasons = listOf("Eintrag ${index + 1}: Der Slug kommt in der Datei mehrfach vor."),
                )
            } else {
                check
            }
        }
    }

    fun validateEntry(
        raw: JsonObject,
        index: Int,
        wallNow: LocalDateTime,
    ): EntryCheck {
        val label = "Eintrag ${index + 1}"
        val reasons = mutableListOf<String>()
        val hints = mutableListOf<String>()

        for (key in raw.keys) {
            if (key !in ALLOWED_KEYS) {
                val shown = if (SAFE_KEY_NAME.matches(key)) "„$key“" else "ein unbekanntes Feld"
                reasons += "$label: Unbekanntes Feld $shown ist nicht erlaubt."
            }
        }

        // Typed accessors: a wrong type is an error, a missing/null optional field is fine.
        fun text(
            field: String,
            required: Boolean,
        ): String? {
            val element = raw[field]
            if (element == null || element is JsonNull) {
                if (required) reasons += "$label: Pflichtfeld „$field“ fehlt."
                return null
            }
            if (element !is JsonPrimitive || !element.isString) {
                reasons += "$label: Feld „$field“ muss ein Text sein."
                return null
            }
            return element.content
        }

        val rawSlug = text("slug", required = true)
        val rawTitle = text("title", required = true)
        val rawDescription = text("description", required = true)
        val rawLocation = text("locationText", required = false)
        val rawStarts = text("startsAt", required = true)
        val rawEnds = text("endsAt", required = false)
        val rawSummary = text("summary", required = false)
        val rawAlt = text("coverImageAlt", required = false)
        val rawOnline = text("onlineUrl", required = false)
        val publicElement = raw["onlineUrlPublic"]
        val onlineUrlPublic =
            when {
                publicElement == null || publicElement is JsonNull -> false
                publicElement is JsonPrimitive && !publicElement.isString && publicElement.content in setOf("true", "false") ->
                    publicElement.content == "true"
                else -> {
                    reasons += "$label: Feld „onlineUrlPublic“ muss true oder false sein."
                    false
                }
            }

        // Normalization BEFORE the length checks (limits apply to the stored form).
        val title = rawTitle?.let { EventText.normalizeSingleLine(it) }
        val description = rawDescription?.let { EventText.normalizeMultiline(it) }
        val location = rawLocation?.let { EventText.normalizeSingleLine(it) }?.takeIf { it.isNotEmpty() }
        val summary = rawSummary?.let { EventText.normalizeSingleLine(it) }?.takeIf { it.isNotEmpty() }
        val alt = rawAlt?.let { EventText.normalizeSingleLine(it) }?.takeIf { it.isNotEmpty() }
        val online = rawOnline?.let { EventText.normalizeSingleLine(it) }?.takeIf { it.isNotEmpty() }

        val slug = rawSlug
        if (slug != null) {
            if (slug.length > EventPolicy.SLUG_MAX_LENGTH) {
                reasons += "$label: Der Slug ist zu lang (maximal ${EventPolicy.SLUG_MAX_LENGTH} Zeichen)."
            } else if (!SLUG_PATTERN.matches(slug)) {
                reasons += "$label: Der Slug darf nur Kleinbuchstaben, Ziffern und einzelne Bindestriche enthalten."
            }
        }
        if (rawTitle != null) {
            if (title.isNullOrEmpty()) reasons += "$label: Der Titel darf nicht leer sein."
            if (title != null && title.length > EventPolicy.MAX_TITLE_LENGTH) {
                reasons += "$label: Der Titel ist zu lang (maximal ${EventPolicy.MAX_TITLE_LENGTH} Zeichen)."
            }
        }
        if (rawDescription != null) {
            if (description.isNullOrEmpty()) reasons += "$label: Die Beschreibung darf nicht leer sein."
            if (description != null && description.length > EventPolicy.MAX_DESCRIPTION_LENGTH) {
                reasons += "$label: Die Beschreibung ist zu lang (maximal ${EventPolicy.MAX_DESCRIPTION_LENGTH} Zeichen)."
            }
        }
        if (location != null && location.length > EventPolicy.MAX_LOCATION_TEXT_LENGTH) {
            reasons += "$label: Der Ort ist zu lang (maximal ${EventPolicy.MAX_LOCATION_TEXT_LENGTH} Zeichen)."
        }
        if (summary != null && summary.length > EventPolicy.MAX_SUMMARY_LENGTH) {
            reasons += "$label: Der Kurztext ist zu lang (maximal ${EventPolicy.MAX_SUMMARY_LENGTH} Zeichen)."
        }
        if (alt != null && alt.length > EventPolicy.MAX_COVER_IMAGE_ALT_LENGTH) {
            reasons += "$label: Die Bildbeschreibung ist zu lang (maximal ${EventPolicy.MAX_COVER_IMAGE_ALT_LENGTH} Zeichen)."
        }
        if (online != null) {
            if (online.length > EventPolicy.MAX_ONLINE_URL_LENGTH) {
                reasons += "$label: Der Online-Link ist zu lang (maximal ${EventPolicy.MAX_ONLINE_URL_LENGTH} Zeichen)."
            } else if (!EventText.isHttpsUrl(online)) {
                reasons += "$label: Der Online-Link muss mit https:// beginnen."
            }
        }
        if (onlineUrlPublic && !EventText.isHttpsUrl(online)) {
            reasons += "$label: „onlineUrlPublic“ ist nur mit einem gültigen https-Online-Link möglich."
        }
        // Same rule as EventPolicy.validate: a later edit through the normal form must not fail on a missing place.
        if (location == null && online == null) reasons += "$label: Ort oder Online-Link ist erforderlich."

        // Times: naive local, strict shape, real calendar date.
        val startsAt = rawStarts?.let { parseNaive(raw = it, field = "startsAt", label = label, reasons = reasons) }
        val explicitEndsAt = rawEnds?.let { parseNaive(raw = it, field = "endsAt", label = label, reasons = reasons) }
        val endsAt = explicitEndsAt ?: startsAt
        if (rawEnds == null && startsAt != null) {
            hints += "Ende fehlt, gleich Beginn gesetzt."
        }
        if (startsAt != null && endsAt != null) {
            if (endsAt < startsAt) {
                reasons += "$label: Das Ende liegt vor dem Beginn."
            } else if (endsAt > wallNow) {
                reasons += "$label: Es können nur vergangene Veranstaltungen importiert werden."
            }
        }

        if (listOfNotNull(rawTitle, rawDescription, rawLocation, rawSummary, rawAlt).any { EventText.containsHtmlLikeMarkup(it) }) {
            hints += "Enthält HTML-ähnliche Zeichen, wird als Text angezeigt."
        }

        val shownSlug = slug?.takeIf { it.length <= EventPolicy.SLUG_MAX_LENGTH && SLUG_PATTERN.matches(it) }
        val shownTitle = title?.take(EventPolicy.MAX_TITLE_LENGTH)
        if (reasons.isNotEmpty() || slug == null || title == null || description == null || startsAt == null || endsAt == null) {
            return EntryCheck.Invalid(index = index, slug = shownSlug, title = shownTitle, reasons = reasons.distinct())
        }
        return EntryCheck.Valid(
            entry =
                ParsedEntry(
                    index = index,
                    slug = slug,
                    title = title,
                    description = description,
                    locationText = location,
                    onlineUrl = online,
                    onlineUrlPublic = onlineUrlPublic,
                    startsAt = startsAt,
                    endsAt = endsAt,
                    summary = summary,
                    coverImageAlt = alt,
                ),
            hints = hints.distinct(),
        )
    }

    /** `true` iff brackets/braces outside of string literals nest deeper than [MAX_NESTING_DEPTH]. One linear pass, no allocation. */
    internal fun exceedsNestingDepth(json: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> if (++depth > MAX_NESTING_DEPTH) return true
                    ']', '}' -> if (depth > 0) depth--
                }
            }
        }
        return false
    }

    /** SHA-256 over the UTF-8 bytes of exactly [json] (a fresh [MessageDigest] per call). A checksum against accidental edits, not a signature. */
    fun sha256Hex(json: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(json.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    private fun parseNaive(
        raw: String,
        field: String,
        label: String,
        reasons: MutableList<String>,
    ): LocalDateTime? {
        if (!NAIVE_LOCAL.matches(raw)) {
            reasons += "$label: „$field“ muss das Format JJJJ-MM-TTThh:mm (Ortszeit, ohne Zeitzone und Sekunden) haben."
            return null
        }
        return try {
            LocalDateTime.parse(raw)
        } catch (_: IllegalArgumentException) {
            reasons += "$label: „$field“ ist kein gültiges Datum."
            null
        }
    }
}
