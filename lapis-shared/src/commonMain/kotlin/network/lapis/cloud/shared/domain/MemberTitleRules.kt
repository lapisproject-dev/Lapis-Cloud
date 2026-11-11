package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/** V151 -- why a member title was rejected (never carries the value). */
@Serializable
enum class MemberTitleViolation { TOO_LONG, INVALID_CHARACTERS }

/**
 * V151 -- DOM-free rules for the member title ("Dr.", "Prof. Dr.", "Dipl.-Ing."), shared by the server and the client forms.
 *
 * The title is free text: it is only trimmed and whitespace-collapsed, never re-cased and never given a trailing dot. The length is
 * counted in code points (what a PostgreSQL `varchar(50)` counts). `<`, `>`, control characters and invisible bidi/format characters
 * are rejected; letters and mails escape on output as well, this is defence in depth.
 */
object MemberTitleRules {
    const val MAX = 50

    /** Proposals for the client's `datalist`; the field stays free text. */
    val SUGGESTIONS: List<String> = listOf("Dr.", "Prof.", "Prof. Dr.", "Dipl.-Ing.", "Dipl.-Kfm.", "M.Sc.")

    private val blanks = Regex(" {2,}")

    /** Trims, collapses runs of blanks to one blank (line breaks and tabs inside are NOT folded: they are rejected by [violation]); an empty result is `null` ("clear the title"). */
    fun normalize(raw: String?): String? = raw?.trim()?.replace(blanks, " ")?.takeIf { it.isNotEmpty() }

    /** Checks an already [normalize]d value; `null` is always acceptable. */
    fun violation(normalized: String?): MemberTitleViolation? {
        if (normalized == null) return null
        if (normalized.any { isForbidden(it) }) return MemberTitleViolation.INVALID_CHARACTERS
        return if (codePointLength(normalized) > MAX) MemberTitleViolation.TOO_LONG else null
    }

    private fun isForbidden(c: Char): Boolean =
        c.isISOControl() ||
            c == '<' ||
            c == '>' ||
            c in '‪'..'‮' ||
            c in '⁦'..'⁩' ||
            c == '‎' ||
            c == '‏' ||
            c in '​'..'‍' ||
            c == '﻿'

    private fun codePointLength(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            i += if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}
