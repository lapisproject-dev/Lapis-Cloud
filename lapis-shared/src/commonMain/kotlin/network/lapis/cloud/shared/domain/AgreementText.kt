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
)

/** A published version with its full text. */
@Serializable
data class AgreementTextVersionDto(
    val summary: AgreementTextVersionSummaryDto,
    val body: String,
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
