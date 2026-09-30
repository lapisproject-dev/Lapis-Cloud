package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * Outcome of [PublicTextRules.normalize] -- a typed result, never an exception, because every
 * failure maps to one fixed, translated message on the client and to one typed RPC error on the
 * server.
 */
sealed interface PublicTextNormalization {
    /** [text] is trimmed, LF-only, free of control characters and within every limit. */
    data class Ok(
        val text: String,
    ) : PublicTextNormalization

    /** Nothing but whitespace -- for a bio this means "delete", for a chapter description "clear". */
    data object Empty : PublicTextNormalization

    data object TooLong : PublicTextNormalization

    data object TooManyLineBreaks : PublicTextNormalization

    /** A control, bidi-override, line-/paragraph-separator character or a lone surrogate. */
    data object ControlChars : PublicTextNormalization
}

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the ONE normalization/validation of free text that becomes
 * publicly visible (member short introduction, chapter description). Lives in `commonMain` so server
 * (authoritative) and client (live counter) count EXACTLY the same way: Kotlin/JS `String.length`
 * counts UTF-16 units, a limit of "500 characters" must count Unicode code points on both sides.
 */
object PublicTextRules {
    /** Number of Unicode code points -- a valid surrogate pair counts once, a lone surrogate counts as one. */
    fun codePointCount(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    private fun isForbidden(c: Char): Boolean =
        (c.code < 0x20 && c != '\n') ||
            c.code in 0x7F..0x9F ||
            c.code == 0x2028 ||
            c.code == 0x2029 ||
            c.code in 0x202A..0x202E ||
            c.code in 0x2066..0x2069

    private fun hasLoneSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    i += 2
                    continue
                }
                return true
            }
            if (c.isLowSurrogate()) return true
            i++
        }
        return false
    }

    /**
     * Trim, `\r\n`/`\r` -> `\n`, tab -> space, runs of more than two line breaks -> two; then reject
     * control characters ([PublicTextNormalization.ControlChars]), more than [maxLineBreaks] line
     * breaks, or more than [maxCodePoints] code points.
     */
    fun normalize(
        raw: String,
        maxCodePoints: Int,
        maxLineBreaks: Int,
    ): PublicTextNormalization {
        val unified =
            raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\t', ' ')
                .trim()
        if (unified.isEmpty()) return PublicTextNormalization.Empty
        if (unified.any { isForbidden(it) } || hasLoneSurrogate(unified)) return PublicTextNormalization.ControlChars
        val collapsed = unified.replace(Regex("\n{3,}"), "\n\n")
        if (collapsed.count { it == '\n' } > maxLineBreaks) return PublicTextNormalization.TooManyLineBreaks
        if (codePointCount(collapsed) > maxCodePoints) return PublicTextNormalization.TooLong
        return PublicTextNormalization.Ok(collapsed)
    }
}

/** Welle V1.9.20 -- limits and consent version of the public short introduction of a member. */
object MemberPublicBioRules {
    const val MAX_CODEPOINTS = 500
    const val MAX_LINE_BREAKS = 8

    /**
     * Version tag of the consent wording shown in the publish dialog (`MemberPublicProfileCard`).
     * A change of that wording REQUIRES a new version -- `MemberPublicBioConsentTextPinTest` fails
     * the build otherwise. A stored consent with an older version is NOT effective (the bio
     * disappears from every public surface until the member confirms the new wording).
     */
    const val CONSENT_TEXT_VERSION = "member-bio-public-v1"

    fun normalize(raw: String): PublicTextNormalization =
        PublicTextRules.normalize(raw = raw, maxCodePoints = MAX_CODEPOINTS, maxLineBreaks = MAX_LINE_BREAKS)
}

/** Where a member is currently publicly listed -- drives the "Derzeit öffentlich auf" status line. */
@Serializable
enum class PublicListingPlace { BOARD, POLITICIANS }

/**
 * The caller's OWN public-profile state (Vorstand / Politiker). Never carries a token, a storage
 * key or another member's data. [eligible] is computed server-side: a current EXECUTIVE_BOARD
 * mandate or an ACTIVE politician profile.
 */
@Serializable
data class OwnPublicProfileDto(
    val eligible: Boolean,
    val isBoardMember: Boolean,
    val isPolitician: Boolean,
    val displayName: String,
    /** German label of the board role, e.g. "Vorsitz" -- `null` for a non-board member. */
    val roleLabel: String?,
    /** Public office text of a politician (mandate text, shortened), `null` if none. */
    val office: String?,
    val bioText: String?,
    val bioPublic: Boolean,
    /** `true` when a consent was given for an OLDER wording -- the bio is NOT public until confirmed again. */
    val bioConsentOutdated: Boolean,
    /** Only the STATE of the photo switch (the image itself is managed on the photo card). */
    val photoPublic: Boolean,
    val politicianListingEffective: Boolean,
    val publicOn: List<PublicListingPlace>,
    val requiredConsentTextVersion: String = MemberPublicBioRules.CONSENT_TEXT_VERSION,
)

/** Welle V1.9.20 -- what happened to a member's public short introduction. No text, no PII. */
@Serializable
data class MemberPublicBioAuditSnapshot(
    val action: MemberPublicBioAuditAction,
    val bioPublic: Boolean,
    val consentTextVersion: String?,
)

/** Welle V1.9.20 -- see [MemberPublicBioAuditSnapshot]. Append-only. */
@Serializable
enum class MemberPublicBioAuditAction {
    SAVED,
    DELETED,
    PUBLISHED,
    UNPUBLISHED,
    REMOVED_BY_MODERATION,
    REVOKED_ON_STATUS_LOSS,
}
