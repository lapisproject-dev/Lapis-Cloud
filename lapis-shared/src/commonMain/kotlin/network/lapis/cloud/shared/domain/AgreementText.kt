package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V1.9.134 -- which registration document an [AgreementTextVersionDto] belongs to. `MEMBERSHIP` is the Beitrittsvertrag a registrant
 * accepts in `registerApplication`/`applyForMembership`, `FRIEND` the terms of use of a FRIEND account. V1.9.143 added `REC_ROOM` and
 * `REC_PULPIT`, the two texts of the optional pulpit recording; nobody acknowledges them persistently (see `docs/features/pulpit-recording.adoc`).
 */
@Serializable
enum class AgreementTextKind {
    MEMBERSHIP,
    FRIEND,

    /** V1.9.143 -- the notice shown in an encounter room whose pulpit may be recorded ("the pulpit is recorded, the congregation is not"). */
    REC_ROOM,

    /** V1.9.143 -- the consent the person at the pulpit gives to being recorded. */
    REC_PULPIT,

    /**
     * V1.9.144 -- the explicit consent to the optional member finder (a special category of data: it can reveal a political or philosophical
     * conviction). Unlike the two REC kinds a consent IS recorded persistently (`member_finder_consent_event`), but it is not an
     * acknowledgment column: a new published version simply ends every effective consent until the member consents again.
     */
    FINDER,

    /** V1.9.148 -- the Art. 9 entry notice a non-member acknowledges before entering a CHURCH_SERVICE encounter room. */
    ENC_CHURCH,

    /** V1.9.148 -- the same for an ASSEMBLY room. */
    ENC_ASSEM,

    /**
     * V1.9.156 -- the consent every participant (member, guest with an account, guest without an account) gives before entering an EVENT room whose
     * organizer allowed recording and live streaming. Unlike the REC kinds a consent IS recorded persistently (`event_capture_acknowledgment`: version,
     * hash, event, a day -- no name, no clock time). The paragraph rule of the encounter notices applies ([EncounterConsentLayout]), the "+rec"
     * suffix and the code-owned recording paragraph do not.
     */
    EVENT_REC,
}

/**
 * V1.9.196 -- how the body of an agreement text version is displayed. `PLAIN` is every text that existed before V112 (shown as text, exactly as
 * before); `MARKDOWN` is a version authored from V1.9.196 on (the SERVER renders it to sanitized HTML, the client never renders Markdown itself).
 * The format is not part of the consent hash and cannot change after publication.
 */
@Serializable
enum class AgreementTextFormat {
    PLAIN,
    MARKDOWN,
    ;

    /** The value of `agreement_text_*.body_format`. */
    val dbValue: String get() = name.lowercase()

    companion object {
        /** An unknown value falls back to [PLAIN] -- always safe, because [PLAIN] is displayed as text. */
        fun fromDb(value: String): AgreementTextFormat = if (value == "markdown") MARKDOWN else PLAIN
    }
}

/** V1.9.156 -- `true` for the one event-room capture consent ([AgreementTextKind.EVENT_REC]). */
fun AgreementTextKind.isEventCapture(): Boolean = this == AgreementTextKind.EVENT_REC

/** V1.9.148 -- `true` for the two encounter entry notices ([AgreementTextKind.ENC_CHURCH], [AgreementTextKind.ENC_ASSEM]). */
fun AgreementTextKind.isEncounterConsent(): Boolean = this == AgreementTextKind.ENC_CHURCH || this == AgreementTextKind.ENC_ASSEM

/**
 * V1.9.148 -- the ONE paragraph rule of an encounter entry notice, shared by the server (validation, the split into headline and key
 * points) and the back-office preview. Paragraph 1 is the headline, paragraphs 2-4 are the key points the guest reads without clicking; the
 * whole body (hashed verbatim) is the expandable full text. The recording sentence is owned by the code, never by an admin.
 */
object EncounterConsentLayout {
    const val MIN_PARAGRAPHS: Int = 4
    const val HEADLINE_MAX: Int = 160
    const val KEY_POINT_MAX: Int = 600

    /** "+rec" is reserved for the system (the label regex forbids "+"); an ENC label must leave room for it in `VARCHAR(50)`. */
    const val RECORDING_SUFFIX: String = "+rec"
    const val ENC_LABEL_MAX: Int = AgreementTextRules.VERSION_LABEL_MAX - RECORDING_SUFFIX.length

    /** V1.9.166: "+map" is reserved for the system as well (the label regex forbids "+"); it follows "+rec" if both options are on ("+rec+map"). */
    const val MAP_SUFFIX: String = "+map"

    /** Longest system suffix: "+rec+map". */
    const val ENC_SYSTEM_SUFFIX_MAX: Int = 8

    /**
     * Longest label an acknowledgment can carry: an ENC label plus every system suffix = 54 characters. `encounter_consent_acknowledgment
     * .consent_version` is `VARCHAR(58)` since V111; [ENC_LABEL_MAX] stays 46 so that every label published earlier stays valid.
     */
    const val ENC_ACK_LABEL_MAX: Int = ENC_LABEL_MAX + ENC_SYSTEM_SUFFIX_MAX

    /** The sentence the placeholder holds that is false in a room whose pulpit may be recorded; removed (with its leading space) there. */
    const val NO_RECORDING_SENTENCE: String = "Es wird nichts aufgezeichnet."

    private const val CHURCH_RECORDING: String =
        "In diesem Raum kann die Kanzel aufgezeichnet werden. Ihr eigenes Bild und Ihr Ton werden nie aufgezeichnet."
    private const val ASSEMBLY_RECORDING: String =
        "In diesem Raum kann das Podium aufgezeichnet werden. Ihr eigenes Bild und Ihr Ton werden nie aufgezeichnet."

    /**
     * V1.9.166: the paragraph about the grid map, owned by the code like the recording paragraph (never by an admin). **DRAFT: the wording comes from
     * the vault draft "Entwurf Eintrittshinweis Stufe 3" and has NOT been reviewed by a lawyer.** Known open point (report Q1): non-members
     * (the only people who acknowledge a notice) never stand on the map in wave 1, so the second sentence is untrue for the one audience that
     * confirms it; swapping the wording touches this constant and its pin test only.
     */
    private const val ASSEMBLY_MAP_PARAGRAPH_DRAFT: String =
        "Karte und Position. In diesem Raum gibt es eine Karte. Andere Anwesende sehen darauf, wo Sie " +
            "sich befinden und in welche Richtung Sie gehen, dazu Ihren Namen und Ihre Initialen. Ihre " +
            "Position wird nur im Arbeitsspeicher des Servers gehalten, nicht in der Datenbank, nicht in " +
            "Protokollen und nicht in Exporten. Sie verschwindet, wenn Sie den Raum verlassen, wenn die " +
            "Veranstaltung endet oder wenn der Server neu startet. Es gibt keinen Verlauf Ihrer Wege und " +
            "keine Liste, wer wann wo war."

    private val PARAGRAPH_SEPARATOR = Regex("\n[ \t]*\n+")

    data class Split(
        val headline: String,
        val keyPoints: List<String>,
        val text: String,
    )

    enum class Violation { TOO_FEW_PARAGRAPHS, HEADLINE_TOO_LONG, KEY_POINT_TOO_LONG, LABEL_TOO_LONG, BLOCK_MARKUP_IN_SUMMARY }

    /**
     * V1.9.196: a MARKDOWN text must not open one of paragraphs 1-4 (headline, key points -- shown as plain text outside the full text) with block
     * markup: any LINE of those paragraphs that starts with a heading, list, quote or code-fence marker is refused.
     */
    private val BLOCK_MARKUP_LINE = Regex("""^\s{0,3}(#{1,6}(\s|$)|[-*+]\s|\d{1,9}[.)]\s|>|```|~~~)""")

    /** The sentence code appends to the notice of a room whose pulpit may be recorded. [kind] must be an ENC kind. */
    fun recordingParagraph(kind: AgreementTextKind): String =
        when (kind) {
            AgreementTextKind.ENC_ASSEM -> ASSEMBLY_RECORDING
            else -> CHURCH_RECORDING
        }

    /**
     * The map paragraph of [kind]. Only the assembly profile has a map; every other kind is an [IllegalArgumentException] (fail closed: a
     * church service must never be handed a map text by mistake).
     */
    fun mapParagraph(kind: AgreementTextKind): String =
        when (kind) {
            AgreementTextKind.ENC_ASSEM -> ASSEMBLY_MAP_PARAGRAPH_DRAFT
            else -> throw IllegalArgumentException("only the assembly profile has a map")
        }

    /** Paragraphs = split of the NORMALIZED [body] at blank lines (blank paragraphs dropped, each trimmed). */
    fun paragraphs(body: String): List<String> =
        AgreementTextRules
            .normalizeBody(body)
            .split(PARAGRAPH_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** headline = paragraph 1, key points = paragraphs 2-4 (+ [extraKeyPoint] if given), text = [body] verbatim. Throws if the layout is invalid. */
    fun split(
        body: String,
        extraKeyPoint: String? = null,
    ): Split = requireNotNull(splitOrNull(body = body, extraKeyPoint = extraKeyPoint)) { "encounter notice layout is invalid" }

    /** Like [split], but `null` instead of an exception when a layout rule is violated. */
    fun splitOrNull(
        body: String,
        extraKeyPoint: String? = null,
    ): Split? = splitOrNull(body = body, extraKeyPoints = listOfNotNull(extraKeyPoint))

    /** V1.9.166: like [splitOrNull] with several code-owned paragraphs appended in the given order (recording, then map). */
    fun splitOrNull(
        body: String,
        extraKeyPoints: List<String>,
    ): Split? {
        if (violation(label = "", body = body) != null) return null
        val paragraphs = paragraphs(body)
        val keyPoints = paragraphs.subList(1, MIN_PARAGRAPHS) + extraKeyPoints
        return Split(headline = paragraphs[0], keyPoints = keyPoints, text = body)
    }

    /** V1.9.196: `true` if any LINE of [body] starts with block markup (heading, list, quote, code fence) -- used for the short REC texts. */
    fun hasBlockMarkup(body: String): Boolean = AgreementTextRules.normalizeBody(body).lines().any { BLOCK_MARKUP_LINE.containsMatchIn(it) }

    /** The first violated rule, or `null`. */
    fun violation(
        label: String,
        body: String,
        format: AgreementTextFormat = AgreementTextFormat.PLAIN,
    ): Violation? {
        if (label.length > ENC_LABEL_MAX) return Violation.LABEL_TOO_LONG
        val paragraphs = paragraphs(body)
        if (paragraphs.size < MIN_PARAGRAPHS) return Violation.TOO_FEW_PARAGRAPHS
        if (format == AgreementTextFormat.MARKDOWN) {
            if (paragraphs.subList(0, MIN_PARAGRAPHS).any { paragraph ->
                    paragraph.lines().any { BLOCK_MARKUP_LINE.containsMatchIn(it) }
                }
            ) {
                return Violation.BLOCK_MARKUP_IN_SUMMARY
            }
        }
        if (paragraphs[0].length > HEADLINE_MAX) return Violation.HEADLINE_TOO_LONG
        if (paragraphs.subList(1, MIN_PARAGRAPHS).any { it.length > KEY_POINT_MAX }) return Violation.KEY_POINT_TOO_LONG
        return null
    }
}

/**
 * V1.9.134 -- the one set of rules for an administered agreement text, shared by client (live counter, label suggestion) and server
 * (validation, hash). The hash formula is the same one the built-in placeholders already use: `sha256("$versionLabel\n$body")` over UTF-8,
 * lower-case hex, taken over the NORMALIZED body ([normalizeBody]) -- the client never computes a hash itself.
 */
object AgreementTextRules {
    /** Maximum body length in UTF-16 code units (`String.length`), identical on both sides. */
    const val MAX_CHARS: Int = 60_000

    /** Width of the acknowledgment columns `agreement_version` / `terms_version`. */
    const val VERSION_LABEL_MAX: Int = 50

    val VERSION_LABEL_REGEX: Regex = Regex("^[0-9A-Za-z][0-9A-Za-z._-]{0,49}$")

    /** `"YYYY-MM-DD.vN"` -- the label scheme the built-in placeholders use. */
    fun suggestLabel(
        today: LocalDate,
        n: Int,
    ): String = "$today.v$n"

    /** CRLF and CR become LF, trailing whitespace of the whole document is dropped. Applied before hashing and storing. */
    fun normalizeBody(raw: String): String = raw.replace("\r\n", "\n").replace('\r', '\n').trimEnd()

    fun isValidLabel(label: String): Boolean = VERSION_LABEL_REGEX.matches(label)

    /**
     * `true` if [body] contains a character that must not appear in a legal text: a C0 control character other than line feed and tab, DEL,
     * or a bidirectional control (U+202A..U+202E, U+2066..U+2069 -- "Trojan Source"), so that what is displayed equals what is hashed.
     */
    fun invalidCharacter(body: String): Boolean =
        body.any { c ->
            val code = c.code
            (code < 0x20 && c != '\n' && c != '\t') ||
                code == 0x7F ||
                code in 0x202A..0x202E ||
                code in 0x2066..0x2069
        }
}

/** A published version without its text (lists, overview). */
@Serializable
data class AgreementTextVersionSummaryDto(
    val kind: AgreementTextKind,
    val versionLabel: String,
    val validFrom: LocalDate,
    val sha256: String,
    val charCount: Int,
    val authorDisplayName: String,
    val publishedAt: LocalDateTime,
    val sequenceNo: Int,
    /** V1.9.196 -- how [AgreementTextVersionDto.body] is displayed; old servers/rows are [AgreementTextFormat.PLAIN]. */
    val format: AgreementTextFormat = AgreementTextFormat.PLAIN,
)

/** A published version with its full text. */
@Serializable
data class AgreementTextVersionDto(
    val summary: AgreementTextVersionSummaryDto,
    val body: String,
    /** V1.9.196 -- sanitized HTML rendered by the server, only for a [AgreementTextFormat.MARKDOWN] version; `null` for plain text. */
    val renderedHtml: String? = null,
)

/** The single draft of a [kind] (at most one exists). */
@Serializable
data class AgreementTextDraftDto(
    val kind: AgreementTextKind,
    val versionLabel: String,
    val validFrom: LocalDate,
    val body: String,
    val sha256: String,
    val charCount: Int,
    val updatedByDisplayName: String,
    val updatedAt: LocalDateTime,
    /** V1.9.196 -- a draft saved before V1.9.196 is [AgreementTextFormat.PLAIN] until the next save (which makes it MARKDOWN). */
    val format: AgreementTextFormat = AgreementTextFormat.PLAIN,
)

/** The answer of "is there a draft": Kilua RPC does not transmit a nullable return type, so the absence is a field. */
@Serializable
data class AgreementTextDraftLookupDto(
    val draft: AgreementTextDraftDto?,
)

/** Per kind: is the built-in placeholder still the live text, what is current, is there a draft, how many have an older acknowledgment. */
@Serializable
data class AgreementTextKindOverviewDto(
    val kind: AgreementTextKind,
    val placeholderActive: Boolean,
    val current: AgreementTextVersionSummaryDto?,
    val builtinVersionLabel: String,
    val hasDraft: Boolean,
    val olderAcknowledgmentCount: Int?,
)

@Serializable
data class AgreementTextOverviewDto(
    val kinds: List<AgreementTextKindOverviewDto>,
)

@Serializable
data class AgreementTextDraftInput(
    val kind: AgreementTextKind,
    val versionLabel: String,
    val validFrom: LocalDate,
    val body: String,
)
