package network.lapis.cloud.server.articles

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.events.EventImportPolicy
import network.lapis.cloud.server.events.EventText
import network.lapis.cloud.shared.rpc.BadRequestException
import java.security.MessageDigest

/**
 * Welle V1.9.105 -- pure validation of the admin import payload for legacy ARTICLES (JSON array, see `docs/api/article-import.adoc`). No
 * database access, no I/O, no clock of its own (the caller passes `wallNow`). Mirrors [EventImportPolicy] for events.
 *
 * **Security posture.** Strict key whitelist (no `ignoreUnknownKeys`): `status`, `authorId`, `imported`, `coverImage`, `image` and anything
 * else not listed are REJECTED (mass-assignment protection; an image can never be fetched from a URL -- covers are uploaded afterwards
 * through the normal pipeline). Error texts are fixed German sentences naming only entry index and field, never payload content. Texts are
 * stored as PLAIN TEXT / Markdown; HTML is never interpreted (only a non-blocking hint is raised).
 *
 * **Time.** `publishedAt` is a naive wall-clock value of the organization zone (`yyyy-MM-dd` meaning 00:00, or `yyyy-MM-ddTHH:mm`, no
 * offset, no `Z`, no seconds). The importer converts it to the UTC system timestamp the column stores (class A).
 */
object ArticleImportPolicy {
    const val MAX_ENTRIES = 100
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_ORGANIZATION_LABELS = 5
    const val MAX_NESTING_DEPTH = EventImportPolicy.MAX_NESTING_DEPTH

    /** Slugs of imported articles may contain single `-` or `_` separators (two legacy slugs use an underscore). */
    val SLUG_PATTERN = Regex("^[a-z0-9]+(?:[-_][a-z0-9]+)*$")
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val DATE_TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$")

    private val ALLOWED_KEYS = setOf("slug", "title", "excerpt", "body", "publishedAt", "byline")
    private val SAFE_KEY_NAME = Regex("^[A-Za-z0-9_]{1,40}$")
    private val PARSER = Json

    /** A fully validated, normalized entry, ready to be inserted. [publishedAtWall] is the organization wall-clock (NOT yet UTC). */
    data class ParsedEntry(
        val index: Int,
        val slug: String,
        val title: String,
        val excerpt: String,
        val body: String,
        val publishedAtWall: LocalDateTime,
        val byline: String?,
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

    /** Validates and normalizes the organization labels the board accepts as bylines. */
    fun normalizeOrganizationLabels(labels: List<String>): List<String> {
        if (labels.size > MAX_ORGANIZATION_LABELS) {
            throw BadRequestException("Es sind höchstens $MAX_ORGANIZATION_LABELS Organisationsnamen erlaubt.")
        }
        return labels.map { raw ->
            val label = ArticleBylinePolicy.normalize(raw)
            if (label.isEmpty() || label.length > ArticleBylinePolicy.BYLINE_MAX) {
                throw BadRequestException("Ein Organisationsname muss 1 bis ${ArticleBylinePolicy.BYLINE_MAX} Zeichen lang sein.")
            }
            label
        }
    }

    /**
     * Parses the payload. Top-level problems (size, not valid JSON, not an array, more than [MAX_ENTRIES] entries) throw
     * [BadRequestException]; problems of single entries are reported as [EntryCheck.Invalid].
     */
    fun parse(
        json: String,
        wallNow: LocalDateTime,
        organizationLabels: List<String> = emptyList(),
    ): List<EntryCheck> {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            throw BadRequestException("Die Datei ist zu groß (maximal 2 MiB).")
        }
        if (EventImportPolicy.exceedsNestingDepth(json)) throw BadRequestException("Die Datei ist zu tief verschachtelt.")
        val root: JsonElement =
            try {
                PARSER.parseToJsonElement(json)
            } catch (_: SerializationException) {
                throw BadRequestException("Die Datei ist kein gültiges JSON.")
            } catch (_: IllegalArgumentException) {
                throw BadRequestException("Die Datei ist kein gültiges JSON.")
            }
        if (root !is JsonArray) throw BadRequestException("Die Datei muss eine Liste (JSON-Array) von Artikeln enthalten.")
        if (root.size > MAX_ENTRIES) throw BadRequestException("Die Datei enthält zu viele Einträge (maximal $MAX_ENTRIES).")

        val labelKeys = organizationLabels.map { it.lowercase() }.toSet()
        val seenSlugs = HashSet<String>()
        return root.mapIndexed { index, element ->
            val check =
                if (element is JsonObject) {
                    validateEntry(raw = element, index = index, wallNow = wallNow, organizationLabelKeys = labelKeys)
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
        organizationLabelKeys: Set<String> = emptySet(),
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
        val rawExcerpt = text("excerpt", required = true)
        val rawBody = text("body", required = true)
        val rawPublishedAt = text("publishedAt", required = true)
        val rawByline = text("byline", required = false)

        val title = rawTitle?.let { EventText.normalizeSingleLine(it) }
        val excerpt = rawExcerpt?.let { EventText.normalizeSingleLine(it) }
        val body = rawBody?.let { ArticleImportText.normalizeMarkdownBody(it) }
        val byline = rawByline?.let { ArticleBylinePolicy.normalize(it) }?.takeIf { it.isNotEmpty() }

        val slug = rawSlug
        if (slug != null) {
            if (slug.length > SLUG_MAX_LENGTH) {
                reasons += "$label: Der Slug ist zu lang (maximal $SLUG_MAX_LENGTH Zeichen)."
            } else if (!SLUG_PATTERN.matches(slug)) {
                reasons +=
                    "$label: Der Slug darf nur Kleinbuchstaben, Ziffern und einzelne Bindestriche oder Unterstriche enthalten."
            }
        }
        if (rawTitle != null &&
            title != null &&
            (title.length < ArticlePolicy.TITLE_MIN || title.length > ArticlePolicy.TITLE_MAX)
        ) {
            reasons += "$label: Der Titel muss ${ArticlePolicy.TITLE_MIN} bis ${ArticlePolicy.TITLE_MAX} Zeichen lang sein."
        }
        if (rawExcerpt != null &&
            excerpt != null &&
            (excerpt.length < ArticlePolicy.EXCERPT_MIN || excerpt.length > ArticlePolicy.EXCERPT_MAX)
        ) {
            reasons += "$label: Der Teaser muss ${ArticlePolicy.EXCERPT_MIN} bis ${ArticlePolicy.EXCERPT_MAX} Zeichen lang sein."
        }
        if (rawBody != null && body != null && (body.length < ArticlePolicy.BODY_MIN || body.length > ArticlePolicy.BODY_MAX)) {
            reasons += "$label: Der Text muss ${ArticlePolicy.BODY_MIN} bis ${ArticlePolicy.BODY_MAX} Zeichen lang sein."
        }
        if (byline != null && byline.length > ArticleBylinePolicy.BYLINE_MAX) {
            reasons += "$label: Die Autorenzeile ist zu lang (maximal ${ArticleBylinePolicy.BYLINE_MAX} Zeichen)."
        }

        val publishedAt = rawPublishedAt?.let { parsePublishedAt(raw = it, label = label, reasons = reasons) }
        if (publishedAt != null && publishedAt > wallNow) {
            reasons += "$label: „publishedAt“ liegt in der Zukunft."
        }

        if (byline != null && byline.lowercase() !in organizationLabelKeys) {
            hints += "Autorenzeile ist kein Organisationsname – Einwilligung klären."
        }
        hints += "Ohne Titelbild – nach dem Import einzeln hochladen."
        if (listOfNotNull(rawTitle, rawExcerpt, rawBody, rawByline).any { EventText.containsHtmlLikeMarkup(it) }) {
            hints += "Enthält HTML – wird als Text angezeigt."
        }

        val shownSlug = slug?.takeIf { it.length <= SLUG_MAX_LENGTH && SLUG_PATTERN.matches(it) }
        val shownTitle = title?.take(ArticlePolicy.TITLE_MAX)
        if (reasons.isNotEmpty() || slug == null || title == null || excerpt == null || body == null || publishedAt == null) {
            return EntryCheck.Invalid(index = index, slug = shownSlug, title = shownTitle, reasons = reasons.distinct())
        }
        return EntryCheck.Valid(
            entry =
                ParsedEntry(
                    index = index,
                    slug = slug,
                    title = title,
                    excerpt = excerpt,
                    body = body,
                    publishedAtWall = publishedAt,
                    byline = byline,
                ),
            hints = hints.distinct(),
        )
    }

    /** SHA-256 over the UTF-8 bytes of exactly [json] (a fresh [MessageDigest] per call). A checksum against accidental edits, not a signature. */
    fun sha256Hex(json: String): String = EventImportPolicy.sha256Hex(json)

    private fun parsePublishedAt(
        raw: String,
        label: String,
        reasons: MutableList<String>,
    ): LocalDateTime? {
        val parsed: LocalDateTime? =
            try {
                when {
                    DATE_ONLY.matches(raw) -> LocalDate.parse(raw).let { LocalDateTime.parse("${it}T00:00") }
                    DATE_TIME.matches(raw) -> LocalDateTime.parse(raw)
                    else -> null
                }
            } catch (_: IllegalArgumentException) {
                reasons += "$label: „publishedAt“ ist kein gültiges Datum."
                return null
            }
        if (parsed == null) {
            reasons +=
                "$label: „publishedAt“ muss das Format JJJJ-MM-TT oder JJJJ-MM-TTThh:mm (Ortszeit, ohne Zeitzone und Sekunden) haben."
        }
        return parsed
    }

    /** Same width as `article.slug VARCHAR(120)`. */
    const val SLUG_MAX_LENGTH = 120
}
